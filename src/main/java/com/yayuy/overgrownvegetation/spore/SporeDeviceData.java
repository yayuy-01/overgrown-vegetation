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
    /**
     * 最後にチャンクが読み込まれていたとき<b>止まっていた</b>除去装置の位置(2026-09-26)。
     * 台帳の広がりは、読み込まれていない装置を「止まっていると分かっているもの以外は守る」扱いにする
     * ({@link #guardsChunkFromLedger})。稼働状態は装置の BlockEntity にしか無く、読み込まないと読めないため。
     */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet knownInactivePurifiers =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

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
        for (long packed : tag.getLongArray("knownInactivePurifiers")) {
            data.knownInactivePurifiers.add(packed);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("purifiers", purifiers.toLongArray());
        tag.putLongArray("diffusers", diffusers.toLongArray());
        tag.putLongArray("knownInactivePurifiers", knownInactivePurifiers.toLongArray());
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
        knownInactivePurifiers.remove(packed);
        if (purifiers.rem(packed) | diffusers.rem(packed)) {
            setDirty();
            invalidateInhibitionAll();
        }
    }

    /**
     * テスト用: 登録されている装置を全部外し、戻すための写し({@code [除去装置, 拡散装置]})を返す。TD の GameTest が、ほかのテストの
     * テスト場に残った除去装置の阻害(負けの汚染の 16 ブロック先で阻害率 0.49)で揺れないように使う(2026-09-25)。
     */
    public static long[][] isolateForTests(ServerLevel level) {
        SporeDeviceData data = get(level);
        long[][] snapshot = {data.purifiers.toLongArray(), data.diffusers.toLongArray()};
        data.purifiers.clear();
        data.diffusers.clear();
        data.setDirty();
        invalidateInhibitionAll();
        return snapshot;
    }

    /** テスト用: {@link #isolateForTests} の写しへ戻す。 */
    public static void restoreForTests(ServerLevel level, long[][] snapshot) {
        SporeDeviceData data = get(level);
        data.purifiers.clear();
        data.diffusers.clear();
        data.purifiers.addElements(0, snapshot[0]);
        data.diffusers.addElements(0, snapshot[1]);
        data.setDirty();
        invalidateInhibitionAll();
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
     * {@code pos} が、稼働中の胞子除去装置に<b>汚染から確実に守られている</b>か(2026-09-26)。
     *
     * <p>{@link #inhibits} との違いは<b>縁の扱い</b> —— あちらは強さをそのまま確率にするので、
     * 縁(10%)では 10 回に 9 回は汚染が通る。こちらは<b>圏内なら必ず守る</b>。
     * 使うのは「汚染されると機能が止まる」もの(機関砲台。ユーザー指定 2026-09-26)——
     * 縁で止まったり動いたりされると、守りとして当てにできないため。
     * 設定({@code effects.json} の {@code purifier.inhibitCorruption})で汚染の阻害を切っていれば false。
     */
    public static boolean protectsFromCorruption(ServerLevel level, BlockPos pos) {
        return inhibitEnabled(Inhibit.CORRUPTION) && insidePurifier(level, pos);
    }

    /**
     * ブロック座標の長方形 {@code [minX, maxX] × [minZ, maxZ]} のどこかが、稼働中の除去装置の効果範囲に
     * <b>入るかもしれない</b>か(2026-09-24 軽量化、監査 940)。
     *
     * <p>false なら、その長方形のどのマスでも {@link #insidePurifier} は必ず false。
     * 判定は {@link #sumForBlock} の「外接正方形で弾く」と同じ幅(半径 + 1)で、長方形を外へ広げた範囲に
     * 稼働中の除去装置が 1 台も無いかを見る。スポロフィルのオーラ({@link com.yayuy.overgrownvegetation.entity.SporophyllAura})が
     * 候補ごとに {@link #insidePurifier} を呼ぶ前の足切りに使う。
     */
    public static boolean purifierMayReach(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        ActiveDevices active = activeDevices(level);
        if (active.purifiers.length == 0) {
            return false;
        }
        long reach = (long) SporeDeviceKind.PURIFIER.spec().radiusBlocks() + 1L;
        for (long packed : active.purifiers) {
            int x = BlockPos.getX(packed);
            int z = BlockPos.getZ(packed);
            if (x >= minX - reach && x <= maxX + reach && z >= minZ - reach && z <= maxZ + reach) {
                return true;
            }
        }
        return false;
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

    /**
     * チャンク {@code chunk} の中のどこかのブロックで、{@link #inhibits(ServerLevel, BlockPos, Inhibit)} が
     * true になる(または乱数を引く)<b>かもしれない</b>か(2026-09-24 軽量化、報告 1472)。
     *
     * <p>false なら、そのチャンクのどのブロックでも阻害率はちょうど 0 —— {@code inhibits} は乱数を引かずに
     * false を返す。深部の一括置換のように 1 チャンクで数千回 {@code canCorrupt} を呼ぶ処理が、チャンクの先頭で
     * 1 回だけこれを聞いて、false なら装置の判定を省く
     * ({@link com.yayuy.overgrownvegetation.corruption.CorruptionRegistry#canCorrupt(net.minecraft.world.level.LevelReader,
     * BlockPos, net.minecraft.world.level.block.state.BlockState, boolean)})。
     *
     * <p>判定は {@link #sumForBlock} の「外接正方形で弾く」と同じ幅(半径 + 1)で、チャンクの四辺から外へ広げた
     * 長方形に稼働中の除去装置が 1 台も無いかを見る。迷うときは true(= いつもどおり 1 ブロックずつ判定)に倒す。
     * 同じ tick の中で装置の一覧は変わらないので、チャンクの処理の間は答えを使い回してよい。
     */
    public static boolean mayInhibitBlocksIn(ServerLevel level, ChunkPos chunk, Inhibit what) {
        if (!inhibitEnabled(what)) {
            return false;
        }
        ActiveDevices active = activeDevices(level);
        if (active.purifiers.length == 0) {
            return false;
        }
        if (what == Inhibit.BIOME_CONVERSION) {
            return true; // チャンク単位の判定(sumForChunk)なので、ここでは絞らない。
        }
        long reach = (long) SporeDeviceKind.PURIFIER.spec().radiusBlocks() + 1L;
        long minX = chunk.getMinBlockX() - reach;
        long maxX = chunk.getMaxBlockX() + reach;
        long minZ = chunk.getMinBlockZ() - reach;
        long maxZ = chunk.getMaxBlockZ() + reach;
        for (long packed : active.purifiers) {
            int x = BlockPos.getX(packed);
            int z = BlockPos.getZ(packed);
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                return true;
            }
        }
        return false;
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
    /** 稼働中の除去装置の位置(BlockPos#asLong の写し。胞子の場の吸い込み口、2026-10-23)。 */
    public static long[] activePurifiers(ServerLevel level) {
        return activeDevices(level).purifiers().clone();
    }

    /** 稼働中の拡散装置の位置(BlockPos#asLong の写し。胞子の場の発生源、2026-10-23)。 */
    public static long[] activeDiffusers(ServerLevel level) {
        return activeDevices(level).diffusers().clone();
    }

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
            boolean on = isActive(level, packed);
            if (on) {
                active.add(packed);
            }
            // 読み込まれている除去装置だけ、止まっているかを覚えておく(読み込まれていなければ前の記録のまま)。
            if (kind == SporeDeviceKind.PURIFIER
                    && level.getChunkSource().getChunkNow(BlockPos.getX(packed) >> 4, BlockPos.getZ(packed) >> 4) != null
                    && (on ? knownInactivePurifiers.remove(packed) : knownInactivePurifiers.add(packed))) {
                setDirty();
            }
        }
        return active.toLongArray();
    }

    /**
     * 台帳の広がり({@code LedgerSpread}、2026-09-26)が印を付けてはいけないチャンクか。
     * 除去装置の「バイオームを変えない」範囲({@link #inhibitionAt(ServerLevel, ChunkPos, Inhibit)} と同じく、
     * チャンクの中心までの距離で見る)に入っていれば true。
     *
     * <p><b>装置のチャンクが読み込まれていなくても守る</b>(ユーザー指定: 胞子除去装置の効果が及んでいる範囲には
     * 印を付けない。留守の間の拠点のため)。読み込まれていれば今の稼働状態で、読み込まれていなければ
     * 「最後に見たとき止まっていた」装置({@link #knownInactivePurifiers})だけを外して判断する ——
     * 更新前から置いてあってまだ一度も見ていない装置は守る側に倒す。
     */
    public static boolean guardsChunkFromLedger(ServerLevel level, int chunkX, int chunkZ) {
        return guards(ledgerGuards(level), chunkX, chunkZ);
    }

    /**
     * {@link #guardsChunkFromLedger} の前半: いま台帳から守っている除去装置の位置。1 回の抽選で何千もの候補を
     * 見るときは、これを 1 度だけ作って {@link #guards} に渡す。守る装置が無ければ空。
     */
    public static long[] ledgerGuards(ServerLevel level) {
        if (!inhibitEnabled(Inhibit.BIOME_CONVERSION)) {
            return new long[0];
        }
        SporeDeviceData data = get(level);
        if (data.purifiers.isEmpty()) {
            return new long[0];
        }
        ActiveDevices active = activeDevices(level); // 読み込まれている装置の稼働状態(と止まっている記録)を新しくする
        LongArrayList out = new LongArrayList();
        for (int i = 0; i < data.purifiers.size(); i++) {
            long packed = data.purifiers.getLong(i);
            boolean loaded = level.getChunkSource().getChunkNow(BlockPos.getX(packed) >> 4, BlockPos.getZ(packed) >> 4) != null;
            if (loaded ? contains(active.purifiers, packed) : !data.knownInactivePurifiers.contains(packed)) {
                out.add(packed);
            }
        }
        return out.toLongArray();
    }

    /** {@link #guardsChunkFromLedger} の後半: {@code guardDevices} のどれかの効果範囲にチャンクが入っているか。 */
    public static boolean guards(long[] guardDevices, int chunkX, int chunkZ) {
        if (guardDevices.length == 0) {
            return false;
        }
        EffectTable.DeviceSpec spec = SporeDeviceKind.PURIFIER.spec();
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        long[] one = new long[1];
        for (long packed : guardDevices) {
            one[0] = packed;
            if (sumForChunk(one, spec, chunk) > 0.0D) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(long[] values, long value) {
        for (long v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
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
