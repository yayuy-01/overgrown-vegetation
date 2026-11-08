package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 菌糸の染み下り(2026-11-07 ユーザー決定「過繁茂になった場所の地下を、地表から底まで 2〜3 日で、空気に面したブロックだけ汚染する」)。
 *
 * <p>過繁茂の台帳({@link PhaseData#overgrownSinceTick})に載ったチャンクの地下を、載った暦から {@code underground.seepDays} 日(仮決め 2.5)で
 * 地表から世界の底まで、列ごとに一定の速さで下げる。列 c の前線 = 地表 − (地表 − 底) × 経った割合(山の下でも平地の下でも同じ日数で底に届く)。
 * 前線より上の<b>空気に面したブロックだけ</b>(洞窟の壁・床・天井)を {@link CorruptionRegistry} の変換・覆いで汚染する。岩の中の石・液体は触らない。
 * 入り口の有無は見ない(閉じた空洞も深さで決まる)。台帳に無いチャンクの下はきれいなまま。
 *
 * <p>進みはチャンクに保存する({@link ModAttachments#MYCELIUM_SEEP} = 処理し終えた割合)。読み込まれているチャンクだけを見て、
 * 前回の割合から今の割合までの<b>板</b>(深さの範囲)だけを調べる —— 毎回チャンクを全部なめない。読み込まれていなかったチャンクは、
 * 次に読み込まれたときに板が厚くなるだけ(1 回に {@code seepSlabBlocks} ブロックずつに分け、1 tick の上限で何 tick にも分ける)。
 * 自分と周り 8 チャンクが読み込まれているときだけ書き換える(縁のマスの近隣更新で、隣の読み込みを待って止まらないため)。
 * 重さは {@link TickBudget} の {@link PerfJob#MYCELIUM_SEEP}。
 *
 * <p>置き換えたマスは {@code seepCreepSeedChance} の確率で {@link CaveWallCreep} の元に入る(洞窟のつながりで前線より少し先へ這う)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MyceliumSeep {

    /** 割合の単位(attachment の整数 = 割合 × これ)。 */
    public static final int SCALE = 1_000_000;

    /** 読み込まれているオーバーワールドのチャンク(順に見て回る)。 */
    private static final LongLinkedOpenHashSet LOADED = new LongLinkedOpenHashSet();
    /** 今処理している板(無ければ null)。 */
    private static Job current;
    /** 前に探したとき仕事が見つかった(続けて毎 tick 探す)。 */
    private static boolean busy;
    /** 列の地表の高さの控え(チャンク → 高さ 256 列と控えた時刻)。板ごとに 256 列を梢からくぐり直さないため。 */
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<long[]> TOP_CACHE = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
    private static final int TOP_CACHE_MAX = 256;
    /** 控えを使う長さ(tick)。木が育つ・掘られるくらいの変わり方は、数分遅れても前線が少しずれるだけ。 */
    private static final long TOP_CACHE_TICKS = 6000L;
    /** 計測用: 1 tick に置き換えた数の最大(/ov perf とは別。devbridge の seepstat が読む)。 */
    private static int maxPlacedInTick;

    private MyceliumSeep() {
    }

    /** 1 チャンクの 1 枚の板。列ごとの深さの範囲 [dFrom, dTo)(地表から数えた段、0 = 地表のすぐ下の段)を見る。 */
    private static final class Job {
        final long key;
        final int fromPpm, toPpm;
        final int[] top = new int[256];
        /** 地表の高さの控えが古かった: 列を処理するときに測り(Integer.MIN_VALUE の列)、終わったら控えに入れる。 */
        boolean measureTops;
        int cursor;
        /** 今の列(cursor)の途中で時間切れになったときの続きの段(-1 = 列の頭から)。2026-11-08 から 1 列の途中でも止まる。 */
        int colD = -1;

        Job(long key, int fromPpm, int toPpm) {
            this.key = key;
            this.fromPpm = fromPpm;
            this.toPpm = toPpm;
        }
    }

    // --- 時刻と前線(純粋な計算。テストもここを見る) ---

    /** 台帳に載ってから {@code elapsedTicks} 経ったときの割合 × {@link #SCALE}(0〜SCALE)。 */
    public static int targetPpm(long elapsedTicks, double days) {
        if (elapsedTicks <= 0L) return 0;
        double f = elapsedTicks / (days * 24000.0D);
        return f >= 1.0D ? SCALE : (int) Math.floor(f * SCALE);
    }

    /** 列の地表のすぐ下の段 {@code top} から底 {@code minY} までの段の数。 */
    static int columnDepth(int top, int minY) {
        return Math.max(0, top - minY + 1);
    }

    /** 割合 {@code ppm} で処理し終えた段の数(地表から)。 */
    public static int doneDepth(int ppm, int depth) {
        return (int) ((long) ppm * depth / SCALE);
    }

    /** 割合 {@code ppm} のときの前線(処理し終えたいちばん下の y。まだ何もしていなければ top + 1)。 */
    public static int frontY(int top, int minY, int ppm) {
        return top + 1 - doneDepth(ppm, columnDepth(top, minY));
    }

    /** 列の地表のすぐ下の段(壁を這う汚染の「地面より下」と同じ、本当の地面より 2 段下)。 */
    static int columnTop(LevelChunk chunk, int x, int z, int minY) {
        int h = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15); // ChunkAccess は既にいちばん上のブロック
        return CaveMouths.groundTop(chunk, x, h, z, minY) - 2;
    }

    // --- 読み込みの出入り ---

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        // 中では世界にもデータにも触らない(LedgerSpread と同じ)。積むだけ
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            LOADED.add(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            LOADED.remove(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LOADED.clear();
        TOP_CACHE.clear();
        GROW_QUEUE.clear();
        current = null;
        busy = false;
        maxPlacedInTick = 0;
    }

    // --- 毎 tick ---

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || CorruptionSwitch.isOff()) return;
        SporeFieldTable t = DesignData.sporeField();
        if (!t.seepEnabled()) return;
        if (current == null) {
            // 暇なときは間隔ごとに探す。前の回に仕事が見つかっていたら(busy)毎 tick 探す(間隔に 1 枚では、読み込まれた過繁茂が多いと前線に追いつかない)
            if (t.seepIntervalTicks() > 0 && !LOADED.isEmpty() && (busy || level.getGameTime() % t.seepIntervalTicks() == 0)) {
                current = findJob(level, t); // 仕事を探すだけ(軽い: チャンクの数 × 台帳を 1 回引く)
                busy = current != null;
            }
            if (current == null && GROW_QUEUE.isEmpty()) return; // 板も、回した植物も無い
        }
        if (!TickBudget.mayStart(PerfJob.MYCELIUM_SEEP)) return; // 重いときは次の tick へ(遅れるだけで結果は同じ)
        long start = TickBudget.begin();
        long t0 = System.nanoTime();
        int cells = 0;
        try {
            cells = work(level, t, true);
        } finally {
            TickBudget.end(PerfJob.MYCELIUM_SEEP, start, cells);
            long dt = System.nanoTime() - t0;
            statTicks++;
            statSumNs += dt;
            statMaxNs = Math.max(statMaxNs, dt);
            if (dt > 2_000_000L) statOver2++;
            if (dt > 1_000_000L) statOver1++;
        }
    }

    /** 計測用: 仕事のあった tick の数・合計と最大の時間(ns)・板の数・置き換えた数・追いつき(1 枚では足りない遅れの板)の数。 */
    private static long statTicks, statSumNs, statMaxNs, statJobs, statPlaced, statCatchUpJobs, statMaxColNs, statMaxFindNs, statMaxPlaceNs, statOver2, statMaxGrowNs, statOver1, statGrowDeferred, statGrowDropped;

    /** 計測用: 統計を文字列で返して 0 に戻す(1 列・次の板探し・1 マスの置き換えのいちばん長い時間、2 ms を超えた tick の数も)。 */
    public static String takeStats() {
        String s = String.format(java.util.Locale.ROOT, "ticks %d avg %.3f ms max %.3f ms (>2ms %d) jobs %d catchup %d placed %d maxPlacedInTick %d | col %.3f find %.3f place %.3f grow %.3f ms (>1ms %d) 植物の回し %d 捨て %d 待ち %d",
                statTicks, statTicks == 0 ? 0.0D : statSumNs / 1e6 / statTicks, statMaxNs / 1e6, statOver2, statJobs, statCatchUpJobs, statPlaced, maxPlacedInTick,
                statMaxColNs / 1e6, statMaxFindNs / 1e6, statMaxPlaceNs / 1e6, statMaxGrowNs / 1e6, statOver1, statGrowDeferred, statGrowDropped, GROW_QUEUE.size());
        statTicks = statSumNs = statMaxNs = statJobs = statPlaced = statCatchUpJobs = statMaxColNs = statMaxFindNs = statMaxPlaceNs = statOver2 = statMaxGrowNs = statOver1 = statGrowDeferred = statGrowDropped = 0L;
        maxPlacedInTick = 0;
        return s;
    }

    /** 読み込まれたチャンクを順に {@code seepChunksPerPass} 個見て、前線が {@code seepStepBlocks} 以上下がったものの板を返す。 */
    private static Job findJob(ServerLevel level, SporeFieldTable t) {
        PhaseData data = PhaseData.get(level);
        long now = data.clock(level.getServer());
        int n = Math.min(t.seepChunksPerPass(), LOADED.size());
        for (int i = 0; i < n; i++) {
            long key = LOADED.removeFirstLong();
            LOADED.add(key); // 後ろへ回す
            ChunkPos pos = new ChunkPos(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) {
                LOADED.remove(key);
                continue;
            }
            Job job = planJob(level, chunk, data.overgrownSinceTick(pos), now, t);
            if (job != null) return job;
        }
        return null;
    }

    /** このチャンクにやる板があれば作る({@code since} = 台帳に載った暦、-1 = 載っていない)。 */
    private static Job planJob(ServerLevel level, LevelChunk chunk, long since, long now, SporeFieldTable t) {
        if (since < 0L) return null; // 過繁茂でない地域の下はきれいなまま
        int done = chunk.getExistingData(ModAttachments.MYCELIUM_SEEP.get()).orElse(0);
        if (done >= SCALE) return null;
        ChunkPos pos = chunk.getPos();
        int target = targetPpm(now - since, t.seepDays() / speedOf(level, pos, t)); // 巨大樹の根のまわりは速い(2026-11-08)
        if (target <= done) return null;
        int minY = level.getMinBuildHeight();
        // 段の数のめやす: 真ん中と四隅の列の高い方(1 回の板の厚さと「十分下がったか」を決めるだけ。実際の段は列ごと)。
        // 1 列だけだと、そこに縦穴・水があると深さを小さく見て、板が厚く・処理がまれになる(11-07 の確かめで縦穴のチャンクが 1 日目まで動かなかった)
        int h = Math.max(Math.max(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 8, 8), chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0)),
                Math.max(Math.max(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 15, 0), chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 15)),
                        chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 15, 15)));
        int depth = Math.max(1, columnDepth(h - 2, minY));
        if (target < SCALE && (long) (target - done) * depth < (long) t.seepStepBlocks() * SCALE) return null;
        if (!LoadedChunks.neighbourhoodLoaded(level, pos.x, pos.z)) return null; // 隣が読み込まれたらまた回ってくる
        int slab = (int) Math.min(SCALE, (long) t.seepSlabBlocks() * SCALE / depth);
        int to = (int) Math.min(target, (long) done + Math.max(1, slab));
        Job job = new Job(pos.toLong(), done, to);
        statJobs++;
        if (to < target) statCatchUpJobs++;
        long[] cached = TOP_CACHE.getAndMoveToLast(pos.toLong());
        long tick = level.getGameTime();
        if (cached == null || tick - cached[0] > TOP_CACHE_TICKS || tick < cached[0]) {
            // 古い・無い: 列を処理するときに 1 列ずつ測る(梢をくぐる探しが 1 tick の時間の上限の中に入るように)
            java.util.Arrays.fill(job.top, Integer.MIN_VALUE);
            job.measureTops = true;
        } else {
            for (int c = 0; c < 256; c++) job.top[c] = (int) cached[c + 1];
        }
        return job;
    }

    /** 板を測り終えたら地表の高さを控えに入れる。 */
    private static void rememberTops(ServerLevel level, Job job) {
        long[] cached = new long[257];
        cached[0] = level.getGameTime();
        for (int c = 0; c < 256; c++) cached[c + 1] = job.top[c];
        TOP_CACHE.putAndMoveToLast(job.key, cached);
        while (TOP_CACHE.size() > TOP_CACHE_MAX) TOP_CACHE.removeFirst();
    }

    /**
     * 1 tick ぶん進める。今の板が終わって上限(マス・置き換え・時間)に余りがあれば、{@code chain} なら次の板を探して続ける。見たマスの数を返す。
     */
    private static int work(ServerLevel level, SporeFieldTable t, boolean chain) {
        long deadline = System.nanoTime() + (long) (t.seepMaxMillisPerTick() * 1_000_000.0D);
        int cells = 0, placed = 0, cols = 0;
        if (chain && !GROW_QUEUE.isEmpty()) cells += drainGrowQueue(level, t, deadline); // 前の tick から回した植物を先に
        while (current != null && (cols == 0 && cells == 0 || System.nanoTime() < deadline)) {
            int[] r = workJob(level, t, current, t.seepCellsPerTick() - cells, t.seepPlacementsPerTick() - placed, deadline, cols == 0 && cells == 0);
            cells += r[0];
            placed += r[1];
            cols += r[2];
            if (current != null || !chain || cells >= t.seepCellsPerTick() || placed >= t.seepPlacementsPerTick()
                    || System.nanoTime() >= deadline || !TickBudget.timeLeft()) break;
            long f0 = System.nanoTime();
            current = findJob(level, t);
            statMaxFindNs = Math.max(statMaxFindNs, System.nanoTime() - f0);
            busy = current != null;
        }
        if (placed > 0) CorruptionCounter.record(level, placed);
        statPlaced += placed;
        maxPlacedInTick = Math.max(maxPlacedInTick, placed);
        return Math.max(1, cells);
    }

    /** 板 {@code job} を上限まで進める。戻り値 = {見たマス, 置き換えた数, 見た列}。終われば進みを書いて {@link #current} を null にする。 */
    private static int[] workJob(ServerLevel level, SporeFieldTable t, Job job, int cellLimit, int placeLimit, long deadline, boolean first) {
        ChunkPos pos = new ChunkPos(job.key);
        if (!LoadedChunks.neighbourhoodLoaded(level, pos.x, pos.z)) {
            current = null; // 手を離す(進みは書いていないので、次はこの板の頭からやり直す。汚染済みは飛ばす)
            return new int[]{0, 0, 0};
        }
        Area area = new Area(level, pos);
        RandomSource random = level.random;
        int cells = 0, placed = 0, cols = 0;
        int minY = level.getMinBuildHeight();
        // 1 tick に少なくとも 1 列は進める(first)。それ以外は時間の上限・予算の番人・マスと置き換えの上限で止める
        while (job.cursor < 256 && cells < cellLimit && placed < placeLimit
                && ((first && cols == 0) || TickBudget.timeLeft() && System.nanoTime() < deadline)) {
            cols++;
            long c0 = System.nanoTime();
            int c = job.cursor;
            int x = pos.getMinBlockX() + (c & 15), z = pos.getMinBlockZ() + (c >> 4);
            if (job.top[c] == Integer.MIN_VALUE) job.top[c] = columnTop(area.chunk(pos.x, pos.z), x, z, minY);
            int top = job.top[c];
            int depth = columnDepth(top, minY);
            int dTo = doneDepth(job.toPpm, depth);
            int dStart = job.colD >= 0 ? job.colD : doneDepth(job.fromPpm, depth);
            int[] r = column(level, area, x, z, top, dStart, dTo, Integer.MIN_VALUE, Integer.MAX_VALUE, random, t,
                    deadline, placeLimit - placed);
            cells += r[0];
            placed += r[1];
            statMaxColNs = Math.max(statMaxColNs, System.nanoTime() - c0);
            if (r[2] >= dTo) {
                job.cursor++;
                job.colD = -1;
            } else {
                job.colD = r[2]; // 列の途中で時間・置き換えの上限: 続きは次の tick
                break;
            }
        }
        if (job.cursor >= 256) {
            if (job.measureTops) rememberTops(level, job);
            area.chunk(pos.x, pos.z).setData(ModAttachments.MYCELIUM_SEEP.get(), job.toPpm);
            area.chunk(pos.x, pos.z).setUnsaved(true);
            current = null;
        }
        return new int[]{cells, placed, cols};
    }

    /**
     * 列 (x, z) の段 [dFrom, dTo)(地表のすぐ下 {@code top} から数える)のうち、y が [yMin, yMax] のものを見て、空気に面したブロックを汚染する。
     * 戻り値 = {見たマスの数, 置き換えた数, 次の段}(次の段 = {@code dTo} なら列は終わり。それより小さければ時間・置き換えの上限で途中で止めた)。
     * 少なくとも 1 段は進める。時間は置き換えの後と 16 段ごとに見る(2026-11-08、1 列が 1 tick の上限をまたいでいた)。
     * 水に面したブロック(地底湖の底・岸)も汚染し、地表の水辺と同じく水に接した面に水中の植物を生やす({@code seepWaterFacing}、2026-11-08)。
     */
    private static int[] column(ServerLevel level, Area area, int x, int z, int top, int dFrom, int dTo, int yMin, int yMax,
                                RandomSource random, SporeFieldTable t, long deadline, int placeLimit) {
        int cells = 0, placed = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        boolean water = t.seepWaterFacing();
        boolean justPlaced = false;
        for (int d = dFrom; d < dTo; d++) {
            if (d > dFrom && (placed >= placeLimit || (justPlaced || ((d - dFrom) & 15) == 0) && System.nanoTime() >= deadline)) {
                return new int[]{cells, placed, d};
            }
            justPlaced = false;
            int y = top - d;
            if (y < minY || y < yMin) break;
            if (y > yMax) continue;
            cells++;
            if (!area.openNear(y, water)) continue; // この高さの区画(上下と東西南北の隣も)に空気(・水)が無い: 面したマスは無い
            BlockState state = area.state(x, y, z);
            if (state.isAir() || !state.getFluidState().isEmpty()) continue; // 液体・水に浸かったブロック・溶岩の源泉は触らない(壁を這う汚染と同じ)
            int facing = area.openFacing(x, y, z, water);
            if (facing == 0) continue;
            m.set(x, y, z);
            LevelChunk chunk = area.chunk(x >> 4, z >> 4);
            if (OvergrownLayerData.isCorruptedCell(chunk, m, state) || !CorruptionRegistry.canCorrupt(level, m, state)) continue;
            BlockState corrupted = CorruptionRegistry.corrupt(level, m, state, random);
            boolean plants = t.seepPlantShare() > 0.0D && random.nextDouble() < t.seepPlantShare();
            if (CorruptionRegistry.noChange(state, corrupted)) {
                // 覆った(記録だけ。水に接した面は覆いの描き方が藻にする)。今覆ったのなら、ほかの汚染の経路と同じく植物を生やす(2026-11-08)
                boolean newly = OvergrownLayerData.takeNewlyCovered(m);
                if (plants && newly) {
                    BlockPos at = m.immutable();
                    com.yayuy.overgrownvegetation.phase.CorruptionCounter.record(level, 1); // growPlantsIfNewlyCovered と同じく数える
                    if (System.nanoTime() + growMarginNs(t) <= deadline) {
                        long g0 = System.nanoTime();
                        growAt(level, at, state);
                        statMaxGrowNs = Math.max(statMaxGrowNs, System.nanoTime() - g0);
                    } else {
                        deferGrow(at, t);
                    }
                    justPlaced = true;
                }
                continue;
            }
            BlockPos at = m.immutable();
            long p0 = System.nanoTime();
            CorruptionRegistry.placeCorrupted(level, at, state, corrupted, Block.UPDATE_ALL);
            if ((facing & 2) != 0) com.yayuy.overgrownvegetation.corruption.UnderwaterPlants.grow(level, at, corrupted); // 地表の水辺と同じ
            statMaxPlaceNs = Math.max(statMaxPlaceNs, System.nanoTime() - p0);
            if (plants) {
                // 植物(2026-11-08 ユーザー「染み下りにも植物が生えるように」)。時間が残っていれば同じ列で、足りなければ待ち行列へ(次の tick の頭で生やす)
                if (System.nanoTime() + growMarginNs(t) <= deadline) {
                    long g0 = System.nanoTime();
                    growAt(level, at, corrupted);
                    statMaxGrowNs = Math.max(statMaxGrowNs, System.nanoTime() - g0);
                } else {
                    deferGrow(at, t);
                }
            }
            placed++;
            justPlaced = true;
            if (t.seepCreepSeedChance() > 0.0D && random.nextDouble() < t.seepCreepSeedChance()) CaveWallCreep.seed(level, at);
        }
        return new int[]{cells, placed, dTo};
    }

    // --- 植物(2026-11-08) ---

    /** 生やすのを次の tick へ回したマス(BlockPos#asLong)。保存しない(読み込みを外れた・止めたときの分は生えない)。 */
    private static final it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue GROW_QUEUE = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();

    private static long growMarginNs(SporeFieldTable t) {
        return (long) (t.seepGrowMarginMillis() * 1_000_000.0D);
    }

    private static void deferGrow(BlockPos at, SporeFieldTable t) {
        if (GROW_QUEUE.size() < t.seepGrowQueueMax()) GROW_QUEUE.enqueue(at.asLong()); // 溢れた分は生やさない(上限は仮決め)
        else statGrowDropped++;
    }

    /**
     * 汚染したマス 1 つに、自然の過繁茂の洞窟と同じ入口で植物を生やす(洞窟なら天井のアーチ・天井の光源・地脈の根・狐火茸・菌糸・根の簾・
     * 胞子の溜まり・巨大菌の柱・太い蔦・面ごとの植物)。並びは地表の追いつき(LedgerSpreadCatchUp)と同じ(水辺のトクサ → 面ごとの植物)。
     */
    private static void growAt(ServerLevel level, BlockPos at, BlockState host) {
        if (!com.yayuy.overgrownvegetation.corruption.FloorGrowth.tryHorsetail(level, at)) {
            com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.growSurfacePlants(level, at, host,
                    com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.surfaceGrowthChance(level, at));
        }
    }

    /** 待ち行列の植物を、時間の上限(余白を残す)まで生やす。生やした数を返す。読み込まれていない(周りも)・汚染でなくなったマスは捨てる。 */
    private static int drainGrowQueue(ServerLevel level, SporeFieldTable t, long deadline) {
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (!GROW_QUEUE.isEmpty() && (n == 0 || System.nanoTime() + growMarginNs(t) <= deadline && TickBudget.timeLeft())) {
            m.set(GROW_QUEUE.dequeueLong());
            if (!LoadedChunks.neighbourhoodLoaded(level, m.getX() >> 4, m.getZ() >> 4)) continue;
            BlockState s = level.getBlockState(m);
            if (s.isAir() || !OvergrownLayerData.isCorruptedCell(level, m, s)) continue;
            long g0 = System.nanoTime();
            growAt(level, m.immutable(), s);
            statMaxGrowNs = Math.max(statMaxGrowNs, System.nanoTime() - g0);
            statGrowDeferred++;
            n++;
        }
        return n;
    }

    /** 自分と周り 8 チャンク(読み込み済みのもの)を持ち回す。ブロックは区画から直に読む。 */
    private static final class Area {
        final int cx, cz, minSection, sections;
        final LevelChunk[] chunks = new LevelChunk[9];
        /** 区画ごと: 0 = まだ調べていない, 1 = 近くに空気あり, 2 = 無し。 */
        final byte[] airNear;

        Area(ServerLevel level, ChunkPos pos) {
            this.cx = pos.x;
            this.cz = pos.z;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    chunks[(dx + 1) * 3 + dz + 1] = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
            this.minSection = level.getMinSection();
            this.sections = level.getSectionsCount();
            this.airNear = new byte[sections];
        }

        LevelChunk chunk(int chunkX, int chunkZ) {
            int dx = chunkX - cx, dz = chunkZ - cz;
            if (dx < -1 || dx > 1 || dz < -1 || dz > 1) return null;
            return chunks[(dx + 1) * 3 + dz + 1];
        }

        BlockState state(int x, int y, int z) {
            LevelChunk ch = chunk(x >> 4, z >> 4);
            if (ch == null) return net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(); // 範囲の外 = 空気でない扱い
            int si = (y >> 4) - minSection;
            if (si < 0 || si >= sections) return net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            LevelChunkSection s = ch.getSection(si);
            if (s.hasOnlyAir()) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            return s.getBlockState(x & 15, y & 15, z & 15);
        }

        /** 6 方の面したもの: 1 = 空気がある、2 = 水がある(足し合わせ)。{@code water} が false なら水は見ない。0 = どちらも無い。 */
        int openFacing(int x, int y, int z, boolean water) {
            int f = kind(state(x + 1, y, z), water) | kind(state(x - 1, y, z), water) | kind(state(x, y + 1, z), water)
                    | kind(state(x, y - 1, z), water) | kind(state(x, y, z + 1), water) | kind(state(x, y, z - 1), water);
            return f;
        }

        private static int kind(BlockState s, boolean water) {
            if (isOpen(s)) return 1; // 空気と、動きを止めない植物・蔦・苔の層など(2026-11-08: 生やした植物が空気を埋めても奥の壁を取り残さない)
            // 水: 水そのものと、水に浸かった植物(垂れる根・昆布など)。天井に生えた水中の根が床の上に下がっても、床を取り残さない(2026-11-08 の揺れ)。溶岩は数えない
            return water && isWet(s) ? 2 : 0;
        }

        /**
         * 高さ y の区画に、空気(・水)に面したマスがありうるか(自分の区画・上下の区画・東西南北の隣のチャンクの同じ区画のどれかに空気(・水)がある)。
         * {@code water} はどの呼び出しでも同じ値(設計値)なので、答えは区画ごとに 1 回だけ数える。
         */
        boolean openNear(int y, boolean water) {
            int si = (y >> 4) - minSection;
            if (si < 0 || si >= sections) return false;
            byte v = airNear[si];
            if (v == 0) {
                boolean any = hasOpen(chunk(cx, cz), si, water) || hasOpen(chunk(cx, cz), si - 1, water) || hasOpen(chunk(cx, cz), si + 1, water)
                        || hasOpen(chunk(cx + 1, cz), si, water) || hasOpen(chunk(cx - 1, cz), si, water)
                        || hasOpen(chunk(cx, cz + 1), si, water) || hasOpen(chunk(cx, cz - 1), si, water);
                airNear[si] = v = (byte) (any ? 1 : 2);
            }
            return v == 1;
        }

        private boolean hasOpen(LevelChunk ch, int si, boolean water) {
            if (ch == null || si < 0 || si >= sections) return false;
            LevelChunkSection s = ch.getSection(si);
            return s.hasOnlyAir() || s.maybeHas(water ? MyceliumSeep::isOpenOrWater : MyceliumSeep::isOpen);
        }
    }

    private static boolean isOpenOrWater(BlockState s) {
        return isOpen(s) || isWet(s);
    }

    /** 水か、水に浸かったブロック(水を含む状態)。 */
    static boolean isWet(BlockState s) {
        return s.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    /** 開いている(面したブロックを汚染してよい): 空気か、液体を含まず動きを止めないブロック(植物・蔦・苔の層・松明など)。溶岩・水は含まない。 */
    static boolean isOpen(BlockState s) {
        return s.isAir() || !s.blocksMotion() && s.getFluidState().isEmpty();
    }

    // --- 巨大樹の根のまわり(2026-11-08 ユーザー「おすすめで」= 根のまわりは前線を 2 倍の速さに。半径・倍率は仮決め) ---

    /** 巨大樹の中心の写しと、取った tick(1 tick に 1 回だけ写す)。 */
    private static long[] treeOrigins = new long[0];
    private static long treeOriginsTick = Long.MIN_VALUE;

    /**
     * チャンクの前線の速さの倍率: 真ん中が巨大樹の幹の中心から水平 {@code seepRootRadius} 以内なら {@code seepRootSpeed}、外は 1。
     * 深い根・洞窟の柱の場所は記録していないので、幹のまわりの円で近似する(根の長さ 35 × 0.35〜1.1 + 幹の裾の半径)。チャンク単位なので境目は 16 ブロックの段になる。
     */
    static double speedOf(ServerLevel level, ChunkPos pos, SporeFieldTable t) {
        if (t.seepRootSpeed() <= 1.0D || t.seepRootRadius() <= 0) return 1.0D;
        long tick = level.getGameTime();
        if (tick != treeOriginsTick) {
            treeOrigins = PhaseData.get(level).giantTreeOriginSnapshot();
            treeOriginsTick = tick;
        }
        int x = pos.getMiddleBlockX(), z = pos.getMiddleBlockZ();
        long r2 = (long) t.seepRootRadius() * t.seepRootRadius();
        for (long o : treeOrigins) {
            long dx = x - (int) (o >> 32), dz = z - (int) o;
            if (dx * dx + dz * dz <= r2) return t.seepRootSpeed();
        }
        return 1.0D;
    }

    // --- テスト・計測用 ---

    /**
     * テスト用: 1 チャンクを、台帳に載った暦 {@code since}(-1 = 載っていない)・今 {@code now} として、本物と同じ板の組み立てと処理で
     * 今の割合まで進め切る(間隔・予算は飛ばす。進みはチャンクに保存する)。台帳は見ない・書かない。作った板の数を返す。
     */
    public static int runChunkForTests(ServerLevel level, ChunkPos pos, long since, long now) {
        SporeFieldTable t = DesignData.sporeField();
        Job saved = current;
        int jobs = 0;
        try {
            for (int guard = 0; guard < 10_000; guard++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) break;
                Job job = planJob(level, chunk, since, now, t);
                if (job == null) break;
                jobs++;
                current = job;
                while (current == job) work(level, t, false);
            }
        } finally {
            current = saved;
        }
        return jobs;
    }

    /** テスト用: そのマスが汚染されない理由を探す材料(状態・面したもの・区画の判定・汚染してよいか・列の地表)。 */
    public static String whyForTests(ServerLevel level, BlockPos pos) {
        SporeFieldTable t = DesignData.sporeField();
        Area area = new Area(level, new ChunkPos(pos));
        BlockState s = area.state(pos.getX(), pos.getY(), pos.getZ());
        LevelChunk chunk = level.getChunkAt(pos);
        return "状態 " + s + " 面 " + area.openFacing(pos.getX(), pos.getY(), pos.getZ(), t.seepWaterFacing())
                + " 区画 " + area.openNear(pos.getY(), t.seepWaterFacing()) + " canCorrupt " + CorruptionRegistry.canCorrupt(level, pos, s)
                + " 汚染済み " + OvergrownLayerData.isCorruptedCell(chunk, pos, s) + " 地表 " + columnTop(chunk, pos.getX(), pos.getZ(), level.getMinBuildHeight())
                + " 上 " + level.getBlockState(pos.above());
    }

    /** 計測用: そのチャンクの進み(割合 × SCALE)。 */
    public static int progressPpm(LevelChunk chunk) {
        return chunk.getExistingData(ModAttachments.MYCELIUM_SEEP.get()).orElse(0);
    }

    /** 計測用: 列の地表のすぐ下の段。 */
    public static int columnTopForStats(LevelChunk chunk, int x, int z, int minY) {
        return columnTop(chunk, x, z, minY);
    }

    /** 計測用: 読み込まれているチャンクの数・今の板があるか。 */
    public static String debugState() {
        return "loaded " + LOADED.size() + " job " + (current == null ? "-" : new ChunkPos(current.key) + " " + current.fromPpm + "→" + current.toPpm + " col " + current.cursor);
    }
}
