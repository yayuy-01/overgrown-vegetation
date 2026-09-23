package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import com.yayuy.overgrownvegetation.block.SporeDeviceKind;
import com.yayuy.overgrownvegetation.data.EffectTable;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * 次元ごとの「胞子装置」({@link com.yayuy.overgrownvegetation.block.SporeDeviceBlock})の設置位置の一覧。
 * {@link SporeConcentration} が、あるチャンクの胞子濃度に効いている装置を探すのに使う
 * (半径内の装置を毎回ブロック走査で探すより、設置済み一覧を舐める方が圧倒的に軽い)。
 *
 * <p>設置/撤去はブロック側({@code onPlace} / {@code onRemove})が登録・解除する。稼働中かどうかは
 * 設置位置の {@link SporeDeviceBlockEntity#isActive()} を見る(チャンクが未ロードなら非稼働扱い)。
 * 稼働中の装置の一覧は {@link #ACTIVE_CACHE} に短時間キャッシュし、設置/撤去/稼働状態の変化で捨てる。
 *
 * <h2>効果範囲はブロック単位 (2026-09-11)</h2>
 * <p>装置の効果半径は {@link EffectTable.DeviceSpec#radiusBlocks}(既定 48 ブロック、水平の円形)。
 * 変化量は装置から対象までの水平距離(ブロック)に比例して {@code centerPercent} → {@code edgePercent} と直線的に減る。
 * <ul>
 *     <li><b>胞子濃度</b>(チャンク単位、{@link #deltaAt}): <b>装置のあるチャンクは距離に関係なく必ず {@code centerPercent}</b>
 *         (ユーザー仕様: 装置のチャンクは −100% のまま)。他のチャンクは装置からそのチャンクの中心ブロックまでの距離で求める。
 *         既定値なら隣のチャンク(中心まで約 16 ブロック)で −70、2 つ隣で −40、3 つ隣で −10。</li>
 *     <li><b>ブロック汚染・汚染シダ湧きの阻害</b>({@link #inhibits(ServerLevel, BlockPos, Inhibit)}): 判定地点そのものまでの
 *         距離で求める(ブロック単位)。装置の真横なら 100%、48 ブロック先なら 10% の確率で不発。</li>
 *     <li><b>バイオーム化の阻害</b>(チャンク単位): 胞子濃度と同じチャンク判定で、変化量 > 0 なら必ず止める。</li>
 * </ul>
 * 複数台の範囲が重なる場所はそれぞれの変化量を<b>合算</b>する(胞子濃度は {@link SporeConcentration} 側で 0〜100 に丸め、
 * 阻害率は 1 で頭打ち)。
 *
 * <h2>除去装置の阻害効果</h2>
 * <p>稼働中の除去装置は胞子濃度を下げるだけでなく、効果範囲内で次の 3 つを<b>阻害</b>する
 * ({@link EffectTable.DeviceSpec#inhibitCorruption} 等で個別に on/off)。
 * <ul>
 *     <li>{@link Inhibit#CORRUPTION} — ブロックの汚染。{@link com.yayuy.overgrownvegetation.corruption.CorruptionRegistry#canCorrupt}
 *         が全経路(汚染シダ・死体・過繫茂バイオームのにじみ/深部汚染・建築浸食・クリーパー爆発)の共通の関門なので
 *         そこで 1 回だけ判定する。</li>
 *     <li>{@link Inhibit#FERN_SPAWN} — 汚染シダ/汚染水草のスポーン({@link com.yayuy.overgrownvegetation.entity.BlightFernPlacement#spawn})。</li>
 *     <li>{@link Inhibit#BIOME_CONVERSION} — チャンクの過繫茂/繫茂バイオーム化
 *         ({@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter#maybeConvert} /
 *         {@code maybeConvertToOvergrowth})。</li>
 * </ul>
 * 胞子濃度と違い、<b>過繫茂バイオームのチャンクでも阻害は効く</b>(過繫茂の内側で進む深部汚染や
 * 湧きも止められる — そうでないと過繫茂に飲まれた拠点を取り返す手段が無い)。
 *
 * <p>{@code canCorrupt} は深部汚染などから 1 tick に数千回呼ばれるが、1 回の判定は「稼働中の除去装置の配列を舐めて
 * 距離を測る」だけ(装置は普通数台)。稼働中判定(チャンク取得 + BlockEntity 参照)は {@link #ACTIVE_CACHE} で省く。
 */
public final class SporeDeviceData extends SavedData {

    private static final String ID = "overgrown_vegetation_spore_devices";

    public static final SavedData.Factory<SporeDeviceData> FACTORY =
            new SavedData.Factory<>(SporeDeviceData::new, (tag, registries) -> load(tag), null);

    /** 除去装置が阻害する対象。 */
    public enum Inhibit {
        /** ブロックの汚染(全経路)。判定地点までのブロック距離。 */
        CORRUPTION,
        /** 汚染シダ/汚染水草のスポーン。判定地点までのブロック距離。 */
        FERN_SPAWN,
        /** チャンクの過繫茂/繫茂バイオーム化。チャンク単位(胞子濃度と同じ判定)、範囲内なら必ず。 */
        BIOME_CONVERSION
    }

    /** 稼働中の装置の位置({@link BlockPos#asLong})の一覧。{@code computedGameTime} から {@link #ACTIVE_CACHE_TTL} tick 有効。 */
    private record ActiveDevices(long[] purifiers, long[] diffusers, long computedGameTime) {
        static final ActiveDevices EMPTY = new ActiveDevices(new long[0], new long[0], Long.MIN_VALUE);

        long[] listFor(SporeDeviceKind kind) {
            return kind == SporeDeviceKind.PURIFIER ? purifiers : diffusers;
        }
    }

    private static final Map<ResourceKey<Level>, ActiveDevices> ACTIVE_CACHE = new HashMap<>();
    /** 稼働中一覧のキャッシュ寿命(tick)。装置の状態変化時は寿命に関係なく捨てる。 */
    private static final long ACTIVE_CACHE_TTL = 40L;

    private final LongArrayList purifiers = new LongArrayList();
    private final LongArrayList diffusers = new LongArrayList();

    private SporeDeviceData() {
    }

    private static SporeDeviceData load(CompoundTag tag) {
        SporeDeviceData data = new SporeDeviceData();
        for (long packed : tag.getLongArray("purifiers")) {
            data.purifiers.add(packed);
        }
        for (long packed : tag.getLongArray("diffusers")) {
            data.diffusers.add(packed);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("purifiers", purifiers.toLongArray());
        tag.putLongArray("diffusers", diffusers.toLongArray());
        return tag;
    }

    public static SporeDeviceData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private LongArrayList listFor(SporeDeviceKind kind) {
        return kind == SporeDeviceKind.PURIFIER ? purifiers : diffusers;
    }

    public void add(BlockPos pos, SporeDeviceKind kind) {
        long packed = pos.asLong();
        LongArrayList list = listFor(kind);
        if (!list.contains(packed)) {
            list.add(packed);
            setDirty();
            invalidateInhibitionAll();
        }
    }

    public void remove(BlockPos pos) {
        long packed = pos.asLong();
        if (purifiers.rem(packed) | diffusers.rem(packed)) {
            setDirty();
            invalidateInhibitionAll();
        }
    }

    // ------------------------------------------------------------------
    // 胞子濃度(チャンク単位)
    // ------------------------------------------------------------------

    /**
     * {@code chunk} の胞子濃度に対する、稼働中の装置による変化量 %(除去装置は負、拡散装置は正)。
     * 装置のあるチャンクは必ず {@code centerPercent}、他のチャンクは装置からチャンク中心までの水平距離(ブロック)で
     * {@link EffectTable.DeviceSpec#percentAt}。複数の装置の範囲が重なる場所は<b>合算</b>する(除去 −70 と −40 が重なれば −110。
     * 最終的な胞子濃度は {@link SporeConcentration} 側で 0〜100 に丸める)。
     * 過繫茂バイオームのチャンクには呼び出し側({@link SporeConcentration})が適用しない。
     */
    public static double deltaAt(ServerLevel level, ChunkPos chunk) {
        ActiveDevices active = activeDevices(level);
        if (active.purifiers.length == 0 && active.diffusers.length == 0) {
            return 0.0D;
        }
        double reduction = sumForChunk(active.purifiers, SporeDeviceKind.PURIFIER.spec(), chunk);
        double boost = sumForChunk(active.diffusers, SporeDeviceKind.DIFFUSER.spec(), chunk);
        return boost - reduction;
    }

    /** チャンク単位の変化量 % の合算(常に 0 以上)。装置のあるチャンクは距離に関係なく centerPercent。 */
    private static double sumForChunk(long[] devices, EffectTable.DeviceSpec spec, ChunkPos chunk) {
        double total = 0.0D;
        double centerX = (chunk.x << 4) + 8.0D;
        double centerZ = (chunk.z << 4) + 8.0D;
        for (long packed : devices) {
            int x = BlockPos.getX(packed);
            int z = BlockPos.getZ(packed);
            if ((x >> 4) == chunk.x && (z >> 4) == chunk.z) {
                total += spec.centerPercent();
                continue;
            }
            double dx = x + 0.5D - centerX;
            double dz = z + 0.5D - centerZ;
            total += spec.percentAt(Math.sqrt(dx * dx + dz * dz));
        }
        return total;
    }

    /** ブロック単位の変化量 % の合算(常に 0 以上)。判定地点の中心から装置の中心までの水平距離。 */
    private static double sumForBlock(long[] devices, EffectTable.DeviceSpec spec, BlockPos pos) {
        double total = 0.0D;
        int radius = spec.radiusBlocks();
        double px = pos.getX() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        for (long packed : devices) {
            int x = BlockPos.getX(packed);
            int z = BlockPos.getZ(packed);
            // 円の外接正方形で先に弾く(1 tick に数千回呼ばれるので sqrt を減らす)。
            if (Math.abs(x - pos.getX()) > radius + 1 || Math.abs(z - pos.getZ()) > radius + 1) {
                continue;
            }
            double dx = x + 0.5D - px;
            double dz = z + 0.5D - pz;
            total += spec.percentAt(Math.sqrt(dx * dx + dz * dz));
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 阻害効果
    // ------------------------------------------------------------------

    /**
     * {@code pos} に対する、稼働中の除去装置の阻害率(0〜1)。{@code what} の阻害が
     * {@link EffectTable.DeviceSpec} で無効なら常に 0。ブロック汚染・シダ湧きは判定地点までのブロック距離、
     * バイオーム化はチャンク単位。変化量 % の合算を 100 で割り、1 で頭打ち。
     */
    public static double inhibitionAt(ServerLevel level, BlockPos pos, Inhibit what) {
        if (!inhibitEnabled(what)) {
            return 0.0D;
        }
        ActiveDevices active = activeDevices(level);
        if (active.purifiers.length == 0) {
            return 0.0D;
        }
        EffectTable.DeviceSpec spec = SporeDeviceKind.PURIFIER.spec();
        double percent = what == Inhibit.BIOME_CONVERSION
                ? sumForChunk(active.purifiers, spec, new ChunkPos(pos))
                : sumForBlock(active.purifiers, spec, pos);
        return percent <= 0.0D ? 0.0D : Math.min(1.0D, percent / 100.0D);
    }

    /** チャンク版。{@link Inhibit#BIOME_CONVERSION} 向け(他の種類を渡すとチャンク中心のブロックで判定する)。 */
    public static double inhibitionAt(ServerLevel level, ChunkPos chunk, Inhibit what) {
        if (!inhibitEnabled(what)) {
            return 0.0D;
        }
        ActiveDevices active = activeDevices(level);
        if (active.purifiers.length == 0) {
            return 0.0D;
        }
        double percent = sumForChunk(active.purifiers, SporeDeviceKind.PURIFIER.spec(), chunk);
        return percent <= 0.0D ? 0.0D : Math.min(1.0D, percent / 100.0D);
    }

    /**
     * {@code pos} での<b>稼働中の胞子除去装置の強さ</b>(0〜1)。複数台あれば合算し、1 で頭打ち。
     *
     * <p>{@link #inhibitionAt} と違って {@link Inhibit} を取らない ——
     * つまり <b>{@link EffectTable.DeviceSpec} の阻害フラグを見ない</b>。
     * 「ブロックの汚染を止めるか」「シダの湧きを止めるか」といった個別の設定とは無関係に、
     * <b>装置の圏内かどうかだけ</b>を聞きたい用途のためのもの。
     */
    public static double purifierStrengthAt(ServerLevel level, BlockPos pos) {
        ActiveDevices active = activeDevices(level);
        if (active.purifiers.length == 0) {
            return 0.0D;
        }
        double percent = sumForBlock(active.purifiers, SporeDeviceKind.PURIFIER.spec(), pos);
        return percent <= 0.0D ? 0.0D : Math.min(1.0D, percent / 100.0D);
    }

    /**
     * {@code pos} が稼働中の胞子除去装置の効果範囲内か。
     *
     * <p>強さは見ない —— <b>縁でも中心でも等しく「範囲内」</b>。
     * 使っているのはスポロフィルのバフ消し({@link com.yayuy.overgrownvegetation.entity.SporophyllAura})で、
     * あそこは「効きが弱まる」ではなく「消える」なので、確率判定にすると
     * 縁で付いたり消えたりを繰り返して見苦しくなる。
     */
    public static boolean insidePurifier(ServerLevel level, BlockPos pos) {
        return purifierStrengthAt(level, pos) > 0.0D;
    }

    /**
     * {@code pos} での {@code what} を阻害するか(確率判定)。阻害率が 1 なら必ず true、0 なら必ず false。
     * {@link Inhibit#BIOME_CONVERSION} だけは確率ではなく「範囲内なら必ず阻害」。
     */
    public static boolean inhibits(ServerLevel level, BlockPos pos, Inhibit what) {
        return roll(level, inhibitionAt(level, pos, what), what);
    }

    /** チャンク版({@link Inhibit#BIOME_CONVERSION} 向け)。 */
    public static boolean inhibits(ServerLevel level, ChunkPos chunk, Inhibit what) {
        return roll(level, inhibitionAt(level, chunk, what), what);
    }

    private static boolean roll(ServerLevel level, double strength, Inhibit what) {
        if (strength <= 0.0D) {
            return false;
        }
        if (what == Inhibit.BIOME_CONVERSION || strength >= 1.0D) {
            return true;
        }
        return level.random.nextDouble() < strength;
    }

    private static boolean inhibitEnabled(Inhibit what) {
        EffectTable.DeviceSpec spec = SporeDeviceKind.PURIFIER.spec();
        return switch (what) {
            case CORRUPTION -> spec.inhibitCorruption();
            case FERN_SPAWN -> spec.inhibitFernSpawn();
            case BIOME_CONVERSION -> spec.inhibitBiomeConversion();
        };
    }

    // ------------------------------------------------------------------
    // 稼働中の装置一覧のキャッシュ
    // ------------------------------------------------------------------

    /** 稼働中の装置の位置一覧(キャッシュ付き)。 */
    private static ActiveDevices activeDevices(ServerLevel level) {
        SporeDeviceData data = get(level);
        if (data.purifiers.isEmpty() && data.diffusers.isEmpty()) {
            return ActiveDevices.EMPTY;
        }
        long now = level.getGameTime();
        ActiveDevices cached = ACTIVE_CACHE.get(level.dimension());
        if (cached != null) {
            long age = now - cached.computedGameTime();
            if (age >= 0 && age < ACTIVE_CACHE_TTL) {
                return cached;
            }
        }
        ActiveDevices fresh = new ActiveDevices(
                data.collectActive(level, SporeDeviceKind.PURIFIER),
                data.collectActive(level, SporeDeviceKind.DIFFUSER),
                now);
        ACTIVE_CACHE.put(level.dimension(), fresh);
        return fresh;
    }

    private long[] collectActive(ServerLevel level, SporeDeviceKind kind) {
        LongArrayList list = listFor(kind);
        if (list.isEmpty()) {
            return new long[0];
        }
        LongArrayList active = new LongArrayList(list.size());
        for (int i = 0; i < list.size(); i++) {
            long packed = list.getLong(i);
            if (isActive(level, packed)) {
                active.add(packed);
            }
        }
        return active.toLongArray();
    }

    /**
     * 稼働中一覧のキャッシュを全次元ぶん捨てる。装置の設置/撤去({@link #add} / {@link #remove})と、
     * 稼働状態の変化({@link SporeDeviceBlockEntity#serverTick})から呼ぶ。
     */
    public static void invalidateInhibitionAll() {
        ACTIVE_CACHE.clear();
    }

    private static boolean isActive(ServerLevel level, long packed) {
        int cx = BlockPos.getX(packed) >> 4;
        int cz = BlockPos.getZ(packed) >> 4;
        LevelChunk loaded = level.getChunkSource().getChunkNow(cx, cz);
        if (loaded == null) {
            return false;
        }
        return loaded.getBlockEntity(BlockPos.of(packed)) instanceof SporeDeviceBlockEntity device && device.isActive();
    }
}
