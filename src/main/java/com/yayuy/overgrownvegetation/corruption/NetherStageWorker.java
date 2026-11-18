package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.NetherSpreadTable;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * <b>ネザーの段階を地面に当てはめる</b>(2026-11-11 骨組み)。前線が届いたチャンクのうち、<b>読み込まれていて周り 8 チャンクも読み込まれている</b>
 * ものだけを、1 tick の予算({@code blocksPerTick}・{@code sectionsPerTick}・{@link TickBudget})の中で少しずつ進める。
 *
 * <table>
 *     <tr><th>段階</th><th>地面</th><th>バイオーム</th></tr>
 *     <tr><td>1 先駆け</td><td>小さな植物が枯れる・ナイリウムが搾られた岩へ(+ 芽生えては枯れる差し込み口)</td><td>繁茂</td></tr>
 *     <tr><td>2 熱を吸う</td><td>海の外の溶岩・マグマが殻へ。海の表面が岸から沖へ 1 輪ずつ殻へ</td><td>繁茂</td></tr>
 *     <tr><td>3 水</td><td>(残った海の表面を全部殻に)面が搾られた岩へ・床の一部が泥炭・くぼみに池</td><td>過繁茂</td></tr>
 *     <tr><td>4 雲と雨</td><td>海の列が岸から沖へ 1 輪ずつ湖へ(土手つき)</td><td>過繁茂</td></tr>
 *     <tr><td>5 湿地</td><td>(残った海の列を全部湖に)泥炭が広がりきる</td><td>過繁茂</td></tr>
 * </table>
 * 終わった段階は台帳({@code applied})に残るので、読み込み直しても続きから。殻と湖の輪は毎回世界を見て決めるので、途中で止まっても続きから進む。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class NetherStageWorker {

    /** 今進めているチャンク(読み込まれていて、まだやることがある)。 */
    private static final LongLinkedOpenHashSet ACTIVE = new LongLinkedOpenHashSet();
    private static final LongArrayFIFOQueue LOADED = new LongArrayFIFOQueue();
    private static final Long2ObjectOpenHashMap<Job> JOBS = new Long2ObjectOpenHashMap<>();
    /** 計測用: 直近の tick の置き換え数・セクション数・使った時間(ナノ秒)。 */
    static int lastBlocks, lastSections;
    static long lastNanos, maxNanos, totalBlocks, sumNanos, workTicks;

    /** 1 チャンクの途中の状態(保存しない)。 */
    private static final class Job {
        /** 今やっている見回り(段階の番号)と、セクションの位置。 */
        int pass;
        int section;
        long nextRing;
        boolean crustDone;
        boolean lakeDone;
        long nextPioneer;
        /** 持ち主がこのチャンクの列(境目のまだら)。 */
        int[] cols;
        /** 段階 1 の前線の見回り: 次に始める時刻と、今の見回りの前線の進み(0〜1)。 */
        long nextSweep;
        double sweepProgress;
        /** 起点からいちばん遠い持ち主の列までの距離(ブロック)。前線は段階 1 の終わりにここへ届く。 */
        int reach;
        /** 覆いの見回りが終わり、植物の仕上げ(帯の予算の中で少しずつ)をしている。 */
        boolean finishing;
        /** 殻の上の植物(管の樹)の次の呼び出しの時刻と、終わったか。 */
        long nextHeat;
        boolean heatDone;
    }

    private NetherStageWorker() {
    }

    static void clear() {
        ACTIVE.clear();
        JOBS.clear();
        LOADED.clear();
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        // 骨組みが止まっている(実験的要素がオフ)間は積まない(11-18 見直し ⑤3。前は tick が回らないまま増える一方だった)
        if (event.getLevel() instanceof ServerLevel level && NetherLedger.active(level)) {
            LOADED.enqueue(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && NetherLedger.isLedgerLevel(level)) {
            long key = event.getChunk().getPos().toLong();
            ACTIVE.remove(key);
            JOBS.remove(key);
        }
    }

    /** 前線が届いた(読み込まれていれば今から進める)。 */
    static void noticeArrival(ServerLevel level, long key) {
        if (level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null) {
            ACTIVE.add(key);
        }
    }

    static void tick(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long now) {
        // 読み込まれたチャンクのうち森の物を拾う(隣が読み込まれて縁が進めるようになった分も)
        for (int i = 0; i < 256 && !LOADED.isEmpty(); i++) {
            long key = LOADED.dequeueLong();
            int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long n = ChunkPos.asLong(x + dx, z + dz);
                    if (data.isReached(n) && level.getChunkSource().getChunkNow(x + dx, z + dz) != null) ACTIVE.add(n);
                }
            }
        }
        lastBlocks = 0;
        lastSections = 0;
        if (ACTIVE.isEmpty()) {
            lastNanos = 0;
            return;
        }
        if (!TickBudget.mayStart(PerfJob.NETHER_LEDGER)) return;
        long start = TickBudget.begin();
        long t0 = System.nanoTime();
        int[] budget = {t.blocksPerTick, t.sectionsPerTick};
        int visits = ACTIVE.size();
        try {
            for (int i = 0; i < visits && budget[0] > 0 && budget[1] >= 0; i++) {
                long key = ACTIVE.removeFirstLong();
                boolean keep = step(level, data, t, now, key, budget);
                if (keep) ACTIVE.addAndMoveToLast(key);
                else JOBS.remove(key);
            }
        } finally {
            int used = t.blocksPerTick - budget[0];
            lastBlocks = used;
            lastSections = t.sectionsPerTick - budget[1];
            totalBlocks += used;
            lastNanos = System.nanoTime() - t0;
            maxNanos = Math.max(maxNanos, lastNanos);
            if (used > 0) {
                sumNanos += lastNanos;
                workTicks++;
            }
            TickBudget.end(PerfJob.NETHER_LEDGER, start, Math.max(1, used));
        }
    }

    /** 1 チャンクぶん進める。まだやることがあれば true。 */
    private static boolean step(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long now, long key, int[] budget) {
        if (!data.isReached(key)) return false;
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return false;
        if (!LoadedChunks.neighbourhoodLoaded(level, cx, cz)) return false; // 隣が読み込まれたら LOADED から戻る
        int target = t.stageAt(now - data.arrivalOf(key));
        Job job = JOBS.computeIfAbsent(key, k -> new Job());
        if (job.cols == null) job.cols = NetherJitter.ownedColumns(level.getSeed(), new ChunkPos(key), t.edgeJitterBlocks, t.edgeJitterScale);
        int applied = data.appliedStage(key);
        ChunkPos pos = chunk.getPos();
        int sections = chunk.getSectionsCount();

        // 段階 1: 先駆け。2026-11-12 から、枯れ・ナイリウム → 搾られた岩・覆いを、起点(当たった所・根の入ってきた側)から
        // 段階 1 の間に少しずつ広げる(coverSweepTicks ごとにセクションを舐め直す。前線より内側だけ変える)
        if (applied < 1) {
            long age = now - data.arrivalOf(key);
            long span = Math.max(1, t.stageStartTicks[1]);
            if (job.pass != 1) {
                job.pass = 1;
                job.section = -1;
                OvergrownBiomeConverter.setNetherStageBiome(level, pos, false);
            }
            if (job.section < 0 && !job.finishing && now >= job.nextSweep) {
                job.section = sections - 1; // 次の見回りを始める
                job.sweepProgress = Math.min(1.0, age / (double) span);
            }
            long origin = data.coverOriginOf(key);
            if (job.reach == 0) job.reach = farthest(job.cols, origin, t.coverFrontBlocks);
            boolean sweeping = job.section >= 0;
            while (job.section >= 0 && budget[1] > 0 && budget[0] > 0) {
                int sy = chunk.getSectionYFromSectionIndex(job.section--);
                budget[0] -= NetherGround.pioneerSection(level, job.cols, sy, origin, job.sweepProgress, job.reach, t.coverStage1);
                budget[0] -= NetherGround.coverSection(level, job.cols, sy, t.coverStage1, origin, job.sweepProgress, job.reach); // 2026-11-12 から覆われたブロックを置くので予算に数える
                budget[1]--;
            }
            if (job.section >= 0) return true;
            if (sweeping) job.nextSweep = now + t.coverSweepTicks; // 見回りを終えたこの tick だけ次の時刻を決める(待っている間に先へ延ばさない)
            if (!job.finishing && (!sweeping || job.sweepProgress < 1.0)) {
                if (now >= job.nextPioneer && budget[0] > 0) {
                    job.nextPioneer = now + t.ringTicks;
                    NetherFloraHooks.pioneerTick(level, chunk, age, budget);
                }
                return true;
            }
            // 植物の仕上げ(段階 1 の間に描ききれなかった分)も帯の予算の中で。終わるまで段階 1 のまま(2026-11-12)
            job.finishing = true;
            if (budget[0] <= 0 || !NetherFloraHooks.finishPioneer(level, chunk, budget)) return true;
            finish(level, data, chunk, key, 1);
            applied = 1;
        }
        if (target == 1) {
            if (now >= job.nextPioneer && budget[0] > 0) {
                job.nextPioneer = now + t.ringTicks;
                NetherFloraHooks.pioneerTick(level, chunk, now - data.arrivalOf(key), budget);
            }
            return true;
        }

        // 段階 2: 熱を吸う(海の外の溶岩・マグマ → 殻)
        if (applied < 2) {
            if (job.pass != 2) {
                job.pass = 2;
                job.section = sections - 1;
            }
            while (job.section >= 0 && budget[1] > 0 && budget[0] > 0) {
                budget[0] -= NetherLava.outsideSection(level, chunk, job.section--);
                budget[1]--;
            }
            if (job.section >= 0) return true;
            finish(level, data, chunk, key, 2);
            applied = 2;
        }
        // 海の表面の殻: 岸から沖へ 1 輪ずつ(段階 3 に入ったら残りを全部)
        if (!job.crustDone && (now >= job.nextRing || target >= 3) && budget[0] > 0) {
            int r = NetherLava.crustRing(level, job.cols, target >= 3, budget[0]);
            if (r < 0) {
                job.crustDone = true;
            } else {
                budget[0] -= r;
                job.nextRing = now + t.ringTicks;
            }
        }
        // 殻の上の植物(管の樹。2026-11-12): 殻の輪と同じ間隔で。殻が終わった後の残りも帯の予算の中で
        if (!job.heatDone && now >= job.nextHeat && budget[0] > 0) {
            job.nextHeat = now + t.ringTicks;
            boolean crustDone = job.crustDone;
            if (NetherFloraHooks.heatTick(level, chunk, job.cols, crustDone, budget) && crustDone) job.heatDone = true;
        }
        if (target == 2) return true;
        if (!job.crustDone) return true; // 殻が終わってから水
        if (!job.heatDone) return true; // 管の樹を立て終えてから水

        // 段階 3: 水(面が搾られた岩・泥炭・池)
        if (applied < 3) {
            if (job.pass != 3) {
                job.pass = 3;
                job.section = sections - 1;
                OvergrownBiomeConverter.setNetherStageBiome(level, pos, true);
            }
            while (job.section >= 0 && budget[1] > 0 && budget[0] > 0) {
                budget[0] -= NetherGround.lateSection(level, job.cols, chunk.getSectionYFromSectionIndex(job.section--), t);
                budget[1]--;
            }
            if (job.section >= 0) return true;
            if (budget[0] <= 0) return true;
            budget[0] -= NetherGround.ponds(level, job.cols, t);
            finish(level, data, chunk, key, 3);
            applied = 3;
        }
        if (target == 3) return true;

        // 段階 4: 雲と雨(湖)
        if (applied < 4) {
            finish(level, data, chunk, key, 4);
            applied = 4;
        }
        if (!job.lakeDone && (now >= job.nextRing || target >= 5) && budget[0] > 0) {
            int[] cells = {0};
            int r = NetherLava.lakeRing(level, job.cols, t, target >= 5, budget[0], cells);
            if (r < 0) {
                job.lakeDone = true;
            } else {
                budget[0] -= r;
                job.nextRing = now + t.ringTicks;
                if (cells[0] > 0) {
                    data.lakeCells.addTo(key, cells[0]);
                    data.setDirty();
                    checkLake(level, data, t, key);
                }
            }
        }
        if (target == 4) return true;
        if (!job.lakeDone) return true;

        // 段階 5: 湿地
        if (applied < 5) {
            if (budget[0] <= 0) return true;
            budget[0] -= NetherGround.peatColumns(level, job.cols, t);
            finish(level, data, chunk, key, 5);
        }
        return false; // 全部終わった
    }

    /** 起点からいちばん遠い列までの距離(下限 {@code min}。覆いの前線が段階 1 の終わりにちょうど届くように)。 */
    static int farthest(int[] cols, long origin, int min) {
        double best = 0;
        int ox = BlockPos.getX(origin), oz = BlockPos.getZ(origin);
        for (int c = 0; c + 1 < cols.length; c += 2) {
            double dx = cols[c] - ox, dz = cols[c + 1] - oz;
            best = Math.max(best, Math.sqrt(dx * dx + dz * dz));
        }
        return Math.max(min, (int) Math.ceil(best));
    }

    private static void finish(ServerLevel level, NetherLedgerData data, LevelChunk chunk, long key, int stage) {
        data.applied.put(key, (byte) stage);
        data.setDirty();
        if (stage == 1) backflow(level, chunk);
        NetherFloraHooks.stageApplied(level, chunk, stage);
    }

    /**
     * 森が別のネザーポータルを含むチャンクに着いたら(段階 1)、今の逆流の決まり({@code portal.json} の {@code backflow})で
     * オーバーワールドへ戻る(11-11 の仮決め。ユーザー了承の 7 項目)。森の出口のポータルは侵食済みなので何も起きない。
     */
    private static void backflow(ServerLevel level, LevelChunk chunk) {
        com.yayuy.overgrownvegetation.data.PortalTable table = com.yayuy.overgrownvegetation.data.DesignData.portal();
        if (!table.enabled() || !table.backflow()) return;
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        for (int si = 0; si < chunk.getSectionsCount(); si++) {
            net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSection(si);
            if (section.hasOnlyAir() || !section.maybeHas(s -> s.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(si) << 4;
            for (int i = 0; i < 4096; i++) {
                if (section.getBlockState(i & 15, i >> 8, (i >> 4) & 15).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) {
                    PortalCorruption.infectNow(level, new BlockPos(x0 + (i & 15), baseY + (i >> 8), z0 + ((i >> 4) & 15)));
                    return; // 1 チャンクに 1 つで足りる(同じポータルの残りのマスは侵食済みになる)
                }
            }
        }
    }

    /**
     * 湖(水面のあるチャンクが辺でつながった塊)が {@code skyPillarLakeCells} を超え、まだ天を支える樹の口を呼んでいなければ、
     * 水面がいちばん多いチャンクの中心に近い水面で 1 回呼ぶ。
     */
    private static void checkLake(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long start) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(start);
        seen.add(start);
        int total = 0;
        long best = start;
        int bestCells = -1;
        while (!queue.isEmpty()) {
            long k = queue.dequeueLong();
            if (data.pillars.contains(k)) return; // この湖にはもう 1 本ある
            int c = data.lakeCells.get(k);
            total += c;
            if (c > bestCells) {
                bestCells = c;
                best = k;
            }
            int x = ChunkPos.getX(k), z = ChunkPos.getZ(k);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                long n = ChunkPos.asLong(x + d[0], z + d[1]);
                if (data.lakeCells.get(n) > 0 && seen.add(n)) queue.enqueue(n);
            }
        }
        if (total < t.skyPillarLakeCells) return;
        BlockPos water = centerWater(level, best);
        if (water == null) return;
        data.pillars.add(best);
        data.setDirty();
        NetherFloraHooks.lakeReady(level, water, total);
    }

    private static BlockPos centerWater(ServerLevel level, long key) {
        int s = NetherLava.surfaceY(level);
        int x0 = ChunkPos.getX(key) << 4, z0 = ChunkPos.getZ(key) << 4;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        int bestD = Integer.MAX_VALUE;
        for (int c = 0; c < 256; c++) {
            int lx = c & 15, lz = c >> 4;
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, p.set(x0 + lx, s, z0 + lz))) continue;
            if (!NetherLava.isWater(level.getBlockState(p))) continue;
            int d = (lx - 8) * (lx - 8) + (lz - 8) * (lz - 8);
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    // ---- 命令・テスト ----

    public static int activeCount() {
        return ACTIVE.size();
    }

    public static String perfLine() {
        return String.format("進めているチャンク %d・前回の status から: 仕事のあった tick %d・平均 %.2f ms・最大 %.2f ms・置き換え %d",
                ACTIVE.size(), workTicks, workTicks == 0 ? 0.0 : sumNanos / 1e6 / workTicks, maxNanos / 1e6, totalBlocks)
                + String.format("・植物: 呼び出し %d・平均 %.2f ms・最大 %.2f ms・置いた %d", NetherFloraHooks.calls,
                NetherFloraHooks.calls == 0 ? 0.0 : NetherFloraHooks.nanos / 1e6 / NetherFloraHooks.calls, NetherFloraHooks.maxNanos / 1e6, NetherFloraHooks.blocks);
    }

    public static void resetPerf() {
        NetherFloraHooks.calls = 0;
        NetherFloraHooks.nanos = 0;
        NetherFloraHooks.maxNanos = 0;
        NetherFloraHooks.blocks = 0;
        maxNanos = 0;
        totalBlocks = 0;
        sumNanos = 0;
        workTicks = 0;
    }

    /** テスト用: そのチャンクを今すぐ進める対象にする。 */
    public static void activateForTests(long key) {
        ACTIVE.add(key);
    }

    /** テスト用: 予算を気にせず、そのチャンクで今の段階までの作り変えを終わらせる(最大 {@code steps} 回)。 */
    public static void runForTests(ServerLevel level, long key, long now, int steps) {
        NetherLedgerData data = NetherLedgerData.get(level);
        NetherSpreadTable t = com.yayuy.overgrownvegetation.data.DesignData.netherSpread();
        for (int i = 0; i < steps; i++) {
            int[] budget = {1 << 20, 64};
            Job job = JOBS.get(key);
            if (job != null) {
                job.nextRing = 0;
                job.nextHeat = 0;
            }
            if (!step(level, data, t, now, key, budget)) break;
        }
    }

    static Long2IntOpenHashMap lakeCellsView(NetherLedgerData data) {
        return data.lakeCells;
    }
}
