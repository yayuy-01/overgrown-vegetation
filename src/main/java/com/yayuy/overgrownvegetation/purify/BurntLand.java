package com.yayuy.overgrownvegetation.purify;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.OvergrowthDepth;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;

/**
 * 焼けた荒地の台帳(順 2 の着手順 9、2026-10-08)。オーバーワールドに 1 つの SavedData で、チャンク → 焼けた時刻(汚染の暦)を持つ。
 *
 * <p><b>ユーザー指定</b>: 火炎放射器の跡は焼けた荒地(10-01)/ ゲーム内 3 日で元のバイオームに戻る・焼けている間は汚染が入らない(10-08)。
 * <b>Claude 仮決め</b>(仕様は {@code docs/ORDER2_SPEC.md} の「着手順 9 の中身」):
 * <ul>
 *     <li><b>焼けた荒地になる</b>({@link #consider}): 火炎放射器で焼いたチャンクのうち、繁茂系のバイオームか汚染の台帳に載っている陸のチャンクで、
 *     地面の汚染が森がチャンクを取るときと同じ物差しを下回ったもの({@link OvergrownBiomeConverter#burnable})。極相は除く(焼いても戻らない)。
 *     バイオームを焼けた荒地({@link ModBiomes#BURNT_WASTELAND})に塗り、汚染の台帳から外す({@link PhaseData#forgetOvergrownChunk})。</li>
 *     <li><b>焼けている間</b>: 台帳の広がり・書き換え待ち・バイオームの書き換え(過繁茂・繁茂)・ブロックの汚染(全経路の関門)・汚染シダの湧きが
 *     このチャンクを飛ばす({@link #isBurnt} を見る所)。</li>
 *     <li><b>元に戻る</b>: 焼けてから {@link #REVERT_TICKS} たったら、読み込まれていれば世界生成のときのバイオームへ戻し
 *     ({@link OvergrownBiomeConverter#restoreOriginal})、地表に残った汚染も消して({@link Burning#cleanChunk}。2026-10-08 にクライアントで足した。
 *     戻した時点で半分近く汚染が残り、周りの森がすぐ取り返していた)台帳から消す。読み込まれていなければ台帳から消して「戻す待ち」に移し(この時点で普通の土地 =
 *     また飲まれうる)、読み込まれたときにバイオームがまだ焼けた荒地なら戻す。焼けた土は、バイオームが戻った後にゆっくり土に戻る
 *     ({@link BurntSoilBlock})。</li>
 * </ul>
 *
 * <p>台帳から外したチャンクがまた飲まれると、フェーズポイントがもう一度入る(過繁茂になったチャンクの数え直し)。進み方の目安として許容する。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BurntLand extends SavedData {

    public static final String ID = "overgrown_vegetation_burnt_land";

    /** 元のバイオームに戻るまで(ユーザー指定 10-08: ゲーム内 3 日。汚染の暦 = 眠った夜も数える)。 */
    public static final long REVERT_TICKS = 3L * 24000L;
    /** 戻す時刻が来たかの見回りの間隔(tick)。 */
    private static final int CHECK_INTERVAL = 200;
    /** 1 tick に戻すチャンクの上限(元のバイオームを世界の生成器から引き直すので、1 チャンク約 1500 回の計算)。 */
    private static final int REVERTS_PER_TICK = 2;
    /** 炎で変わったチャンクを、焼けた荒地にできるかまとめて見る間隔(tick)。 */
    private static final int CONSIDER_INTERVAL = 20;

    public static final SavedData.Factory<BurntLand> FACTORY =
            new SavedData.Factory<>(BurntLand::new, (tag, registries) -> load(tag), null);

    /** 焼けた荒地のチャンク({@link ChunkPos#toLong})→ 焼けた時刻(汚染の暦)。 */
    private final Long2LongOpenHashMap since = new Long2LongOpenHashMap();
    /** 戻す時刻が来たが読み込まれていなかったチャンク(読み込まれたときにバイオームを戻す)。 */
    private final LongOpenHashSet pendingRevert = new LongOpenHashSet();

    // --- 保存しない ---

    /** 読み込まれていて戻す番を待つチャンク。 */
    private static final LongLinkedOpenHashSet REVERT_QUEUE = new LongLinkedOpenHashSet();
    /** 読み込まれたばかりのチャンク(ChunkEvent.Load の中では世界にもデータにも触らず、ここに積むだけ)。 */
    private static final LongLinkedOpenHashSet LOADED = new LongLinkedOpenHashSet();
    /** 炎で汚染が変わったチャンク(焼けた荒地にできるか、まとめて見る。{@link #noteBurnt})。 */
    private static final LongLinkedOpenHashSet PENDING = new LongLinkedOpenHashSet();
    /** 汚染の関門から毎回引くので、台帳の場所を覚えておく(サーバーごと)。 */
    @Nullable
    private static BurntLand cached;
    @Nullable
    private static MinecraftServer cachedServer;

    public BurntLand() {
    }

    public static BurntLand get(MinecraftServer server) {
        BurntLand data = cached;
        if (data != null && cachedServer == server) {
            return data;
        }
        data = server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
        cached = data;
        cachedServer = server;
        return data;
    }

    private static BurntLand load(CompoundTag tag) {
        BurntLand data = new BurntLand();
        long[] keys = tag.getLongArray("chunks");
        long[] times = tag.getLongArray("since");
        for (int i = 0; i < Math.min(keys.length, times.length); i++) {
            data.since.put(keys[i], times[i]);
        }
        for (long key : tag.getLongArray("pendingRevert")) {
            data.pendingRevert.add(key);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        long[] keys = new long[since.size()];
        long[] times = new long[since.size()];
        int i = 0;
        for (Long2LongMap.Entry e : since.long2LongEntrySet()) {
            keys[i] = e.getLongKey();
            times[i] = e.getLongValue();
            i++;
        }
        tag.putLongArray("chunks", keys);
        tag.putLongArray("since", times);
        tag.putLongArray("pendingRevert", pendingRevert.toLongArray());
        return tag;
    }

    // ------------------------------------------------------------------ 問い合わせ(汚染の側から)

    /** そのマスのチャンクが焼けた荒地か(オーバーワールドだけ)。汚染の関門から毎回呼ばれるので軽くしてある。 */
    public static boolean isBurnt(ServerLevel level, BlockPos pos) {
        return level.dimension() == Level.OVERWORLD
                && isBurntKey(level.getServer(), ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** {@link #isBurnt} のチャンク版。 */
    public static boolean isBurntChunk(ServerLevel level, ChunkPos pos) {
        return level.dimension() == Level.OVERWORLD && isBurntKey(level.getServer(), pos.toLong());
    }

    /** オーバーワールドのチャンク {@code key}({@link ChunkPos#toLong})が焼けた荒地か。 */
    public static boolean isBurntKey(MinecraftServer server, long key) {
        BurntLand data = get(server);
        return !data.since.isEmpty() && data.since.containsKey(key);
    }

    /** 焼けた荒地のチャンクの数。 */
    public int size() {
        return since.size();
    }

    /** 戻す待ち(読み込まれていなかった)のチャンクの数。 */
    public int pendingSize() {
        return pendingRevert.size();
    }

    /** 焼けた時刻(汚染の暦)。焼けた荒地でなければ {@link Long#MIN_VALUE}。 */
    public long burntAt(long key) {
        return since.containsKey(key) ? since.get(key) : Long.MIN_VALUE;
    }

    // ------------------------------------------------------------------ 焼けた荒地にする

    /**
     * 炎で汚染が変わった(火炎放射器の炎が燃え尽きた {@link Burning#burnOut}・ふつうの火 {@link VanillaFire})。そのチャンクを焼けた荒地の候補に積む
     * (オーバーワールドだけ)。炎は 1 つずつ燃え尽きるので、毎回数えずに {@value #CONSIDER_INTERVAL} tick ごとにまとめて見る。
     */
    public static void noteBurnt(ServerLevel level, BlockPos pos) {
        // 実験的要素のオン・オフに関係なく積む(10-09 に一度オフで止めたが、10-10 にユーザー指定「炎の要素は隔離しない」で戻した)
        if (level.dimension() == Level.OVERWORLD) {
            PENDING.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        }
    }

    /** 積んであるチャンクの数(GameTest 用)。 */
    public static int pendingCount() {
        return PENDING.size();
    }

    /** そのマスのチャンクが候補に積まれているか(GameTest 用。ほかのテストが同じチャンクを積んでいることがあるので、数より確か)。 */
    public static boolean isPending(BlockPos pos) {
        return PENDING.contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** 積んだチャンクを見て、条件を満たせば焼けた荒地にする({@link #consider})。焼けた荒地にした数を返す(GameTest からも)。 */
    public static int considerPending(ServerLevel level) {
        int flipped = 0;
        while (!PENDING.isEmpty()) {
            if (consider(level, new ChunkPos(PENDING.removeFirstLong()))) {
                flipped++;
            }
        }
        return flipped;
    }

    /**
     * 火炎放射器で焼いたチャンクを見て、条件を満たせば焼けた荒地にする({@link #considerPending} から。読み込まれているチャンクだけ)。
     *
     * @return 焼けた荒地にしたら true
     */
    public static boolean consider(ServerLevel level, ChunkPos pos) {
        if (level.dimension() != Level.OVERWORLD || isBurntChunk(level, pos)) {
            return false;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) {
            return false;
        }
        PhaseData phase = PhaseData.get(level);
        if (phase.isClimaxChunk(pos)) {
            return false; // 極相は焼いても戻らない(岩盤まで書き換え済み・地脈・器官)
        }
        boolean corrupted = phase.isOvergrownChunk(pos)
                || ModBiomes.isOvergrownLike(level.getBiome(pos.getMiddleBlockPosition(level.getMinBuildHeight())));
        if (!corrupted || !OvergrownBiomeConverter.burnable(level, chunk)) {
            return false;
        }
        return flip(level, chunk);
    }

    /**
     * チャンクを焼けた荒地にする(条件は見ない。{@link #consider} とコマンド・テストから)。極相とオーバーワールド以外は断る。
     *
     * @return したら true
     */
    public static boolean flip(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if (level.dimension() != Level.OVERWORLD) {
            return false;
        }
        PhaseData phase = PhaseData.get(level);
        BurntLand data = get(level.getServer());
        if (data.since.containsKey(pos.toLong()) || phase.isClimaxChunk(pos)) {
            return false;
        }
        OvergrownBiomeConverter.paintBurnt(level, chunk);
        phase.forgetOvergrownChunk(pos);
        data.since.put(pos.toLong(), phase.clock(level.getServer()));
        data.pendingRevert.remove(pos.toLong());
        data.setDirty();
        OvergrowthDepth.clearCache();
        OvergrownVegetation.LOGGER.info("焼けた荒地: チャンク {} ({} 日で元のバイオームへ)", pos, REVERT_TICKS / 24000L);
        return true;
    }

    // ------------------------------------------------------------------ 元に戻す

    /**
     * チャンクを元に戻す(読み込まれていること): バイオームがまだ焼けた荒地なら世界生成のときのバイオームへ戻し、地表に残った汚染も消して
     * ({@link Burning#cleanChunk}。周り 8 チャンクも読み込まれているときだけ)、台帳から消す。
     * バイオームが焼けた荒地でなくなっていれば(戻す待ちの間に森が飲んで塗り直した)、バイオームにも地表にも触らない。
     */
    public static void revert(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        BurntLand data = get(level.getServer());
        if (level.getBiome(pos.getMiddleBlockPosition(level.getMinBuildHeight())).is(ModBiomes.BURNT_WASTELAND)) {
            OvergrownBiomeConverter.restoreOriginal(level, chunk);
            if (LoadedChunks.neighbourhoodLoaded(level, pos.x, pos.z)) {
                Burning.cleanChunk(level, chunk);
            }
        }
        long key = pos.toLong();
        boolean changed = data.since.containsKey(key);
        data.since.remove(key);
        changed |= data.pendingRevert.remove(key);
        if (changed) {
            data.setDirty();
        }
        OvergrownVegetation.LOGGER.info("焼けた荒地: チャンク {} を元のバイオームへ戻した", pos);
    }

    /**
     * 戻す時刻が来たチャンクを振り分ける: 読み込まれていれば戻す番に並べ、読み込まれていなければ台帳から消して戻す待ちへ。
     * {@code /ov burnt age} とテストからも呼ぶ。
     */
    public static void scheduleDue(MinecraftServer server) {
        BurntLand data = get(server);
        if (data.since.isEmpty()) {
            return;
        }
        ServerLevel level = server.overworld();
        long clock = PhaseData.get(level).clock(server);
        ObjectIterator<Long2LongMap.Entry> it = data.since.long2LongEntrySet().iterator();
        while (it.hasNext()) {
            Long2LongMap.Entry e = it.next();
            if (clock - e.getLongValue() < REVERT_TICKS) {
                continue;
            }
            long key = e.getLongKey();
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null) {
                REVERT_QUEUE.add(key);
            } else {
                it.remove();
                data.pendingRevert.add(key);
                data.setDirty();
            }
        }
    }

    /**
     * 戻す番のチャンクを最大 {@code max} 個戻す(読み込まれなくなっていたものは戻す待ちへ。周り 8 チャンクがまだ読み込まれていないものは
     * 後回し —— 地表の後始末の縁の更新で、読み込みを待たないように)。戻した数を返す。
     */
    public static int drain(ServerLevel level, int max) {
        BurntLand data = get(level.getServer());
        int done = 0;
        int budget = REVERT_QUEUE.size();
        while (done < max && budget-- > 0 && !REVERT_QUEUE.isEmpty()) {
            long key = REVERT_QUEUE.removeFirstLong();
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                if (data.since.containsKey(key)) {
                    data.since.remove(key);
                    data.pendingRevert.add(key);
                    data.setDirty();
                }
                continue;
            }
            if (!data.since.containsKey(key) && !data.pendingRevert.contains(key)) {
                continue;
            }
            if (!LoadedChunks.neighbourhoodLoaded(level, cx, cz)) {
                REVERT_QUEUE.add(key); // 周りが読み込まれるまで後回し
                continue;
            }
            revert(level, chunk);
            done++;
        }
        return done;
    }

    /** テスト・コマンド用: 焼けた時刻を {@code ticks} だけ昔へずらす(戻す時刻を早める)。ずらしたチャンクの数を返す。 */
    public static int age(MinecraftServer server, long ticks) {
        BurntLand data = get(server);
        for (Long2LongMap.Entry e : data.since.long2LongEntrySet()) {
            e.setValue(e.getLongValue() - ticks);
        }
        data.setDirty();
        return data.since.size();
    }

    // ------------------------------------------------------------------ 毎 tick

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            LOADED.add(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server instanceof GameTestServer) {
            LOADED.clear(); // GameTest は自分で呼ぶ(scheduleDue・drain)
            return;
        }
        ServerLevel level = server.overworld();
        BurntLand data = get(server);
        if (!LOADED.isEmpty()) {
            if (!data.pendingRevert.isEmpty()) {
                for (long key : LOADED) {
                    if (data.pendingRevert.contains(key)) {
                        REVERT_QUEUE.add(key);
                    }
                }
            }
            LOADED.clear();
        }
        if (level.getGameTime() % CHECK_INTERVAL == 0L) {
            scheduleDue(server);
        }
        if (!PENDING.isEmpty() && level.getGameTime() % CONSIDER_INTERVAL == 0L) {
            considerPending(level);
        }
        if (REVERT_QUEUE.isEmpty() || !TickBudget.mayStart(PerfJob.BURNT_LAND)) {
            return;
        }
        long t0 = TickBudget.begin();
        int done = drain(level, REVERTS_PER_TICK);
        TickBudget.end(PerfJob.BURNT_LAND, t0, done);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        REVERT_QUEUE.clear();
        LOADED.clear();
        PENDING.clear();
        cached = null;
        cachedServer = null;
    }
}
