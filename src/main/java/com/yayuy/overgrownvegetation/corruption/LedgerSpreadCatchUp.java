package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.SpreadTable;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 台帳の広がりで印を付けたチャンクを、読み込まれているときに<b>世界へ反映する</b>(2026-09-26)。
 *
 * <p>1 チャンクにつき: ①バイオームを過繁茂へ({@link OvergrownBiomeConverter#forceOvergrown}。台帳には載っているので
 * 面積もポイントも数え直さない) ②地表の列の {@link SpreadTable#surfaceCoverage} を、座標で決まるまだら模様で汚染し、
 * 草を生やす(過繁茂バイオームの浸食 {@link OvergrownBiomeCreep} と同じ「地表を探す → 雪を剥がす → 汚染 → 植物」)。
 * 1 tick の仕事は {@link SpreadTable#placementsPerTick} と {@link SpreadTable#columnsPerTick} まで。
 *
 * <p>書き換えるのは<b>周り 8 チャンクまで読み込まれているチャンクだけ</b>({@link #neighbourhoodLoaded}、2026-10-06)。
 * 読み込み範囲のいちばん外側のチャンクは、その外が読み込まれるまで待つ。
 * 途中で読み込まれなくなったら、書き換え待ちに残したまま手を離す(次に読み込まれたら最初の列からやり直す。
 * 汚染済みのマスは飛ばすので二重にはならない)。
 * 除去装置の範囲に入ったチャンク(印を付けた後に置かれた)は書き換えずに外す。
 */
final class LedgerSpreadCatchUp {

    /** 読み込まれていて、書き換えを待っているチャンク(実行時だけ)。 */
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    private static long currentKey = Long.MIN_VALUE;
    private static int cursor;

    static void enqueue(long chunkKey) {
        if (chunkKey != currentKey) {
            QUEUE.add(chunkKey);
        }
    }

    static int queued() {
        return QUEUE.size() + (currentKey != Long.MIN_VALUE ? 1 : 0);
    }

    static void clear() {
        QUEUE.clear();
        currentKey = Long.MIN_VALUE;
        cursor = 0;
    }

    static void tick(ServerLevel level, SpreadTable table) {
        if (currentKey == Long.MIN_VALUE && QUEUE.isEmpty()) {
            return;
        }
        // サーバーが重いときは予算の番人が次の tick へ回す(2026-10-06。結果は同じで遅れるだけ)。
        if (!TickBudget.mayStart(PerfJob.LEDGER_CATCH_UP)) {
            return;
        }
        long start = TickBudget.begin();
        int columns = 0;
        try {
            columns = work(level, table);
        } finally {
            TickBudget.end(PerfJob.LEDGER_CATCH_UP, start, columns);
        }
    }

    /** 1 tick ぶん書き換える。戻り値は見た列の数。 */
    private static int work(ServerLevel level, SpreadTable table) {
        int placeBudget = table.placementsPerTick();
        int columnBudget = table.columnsPerTick();
        LedgerSpreadData data = LedgerSpreadData.get(level.getServer());
        while (placeBudget > 0 && columnBudget > 0
                && (columnBudget == table.columnsPerTick() || TickBudget.timeLeft())) {
            if (currentKey == Long.MIN_VALUE) {
                if (QUEUE.isEmpty()) {
                    break;
                }
                currentKey = QUEUE.removeFirstLong();
                cursor = 0;
            }
            long key = currentKey;
            ChunkPos pos = new ChunkPos(key);
            if (!data.isPending(key) || !neighbourhoodLoaded(level, pos.x, pos.z)) {
                // 読み込まれていない(周り 8 チャンクのどれかも含む): 待ちに残す。自分か隣が読み込まれたらまた並ぶ
                // (LedgerSpread#drainLoaded)。
                currentKey = Long.MIN_VALUE;
                continue;
            }
            if (cursor == 0) {
                if (SporeDeviceData.guardsChunkFromLedger(level, pos.x, pos.z)) {
                    data.removePending(key);
                    currentKey = Long.MIN_VALUE;
                    continue;
                }
                com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.Cell fieldCell =
                        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.enabled()
                                ? com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.cell(pos.x, pos.z) : null;
                if (fieldCell != null && fieldCell.stage() == 1) {
                    // 発芽して 2 日未満は繁茂の札(2 日を過ぎたら DepositBiomeSweep が過繁茂へ。相談セッションの答え 2)
                    OvergrownBiomeConverter.maybeConvertToOvergrowth(level, pos);
                } else {
                    OvergrownBiomeConverter.forceOvergrown(level, pos);
                }
                if (com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.enabled()) {
                    // 胞子の場(2026-10-23 第 2 段 E): 地表を四角くまだらに塗らず、点から根のように広がる芽と萌芽体に任せる
                    com.yayuy.overgrownvegetation.spore.field.SporeSprouts.start(level, pos);
                    data.removePending(key);
                    currentKey = Long.MIN_VALUE;
                    columnBudget -= 16;
                    continue;
                }
            }
            int[] used = paintSurface(level, pos, cursor, placeBudget, columnBudget, table);
            placeBudget -= used[0];
            columnBudget -= used[1];
            cursor = used[2];
            if (cursor >= 256) {
                data.removePending(key);
                currentKey = Long.MIN_VALUE;
            }
        }
        return table.columnsPerTick() - columnBudget;
    }

    /**
     * 自分と周り 8 チャンクが読み込み済み(FULL)か(2026-10-06)。地表は {@code UPDATE_ALL} で置くので、チャンクの縁の列は
     * 隣のチャンクのブロックへ近隣更新を出す。隣が読み込みの途中(または読み込み範囲のすぐ外)だと、その
     * {@code getBlockState} でサーバースレッドが隣の読み込み・生成を待って止まっていた(計測でサーバースレッドの半分、
     * 1 tick 最大 1.2 秒。睡眠の後や飛んで移動したときに書き換え待ちが一斉に並ぶ)。
     */
    static boolean neighbourhoodLoaded(ServerLevel level, int cx, int cz) {
        return LoadedChunks.neighbourhoodLoaded(level, cx, cz);
    }

    /** テスト・コマンド用: 1 チャンクをその場で書き換え切る(読み込まれていること)。置き換えたブロック数を返す。 */
    static int convertNow(ServerLevel level, ChunkPos pos, SpreadTable table) {
        LedgerSpreadData data = LedgerSpreadData.get(level.getServer());
        OvergrownBiomeConverter.forceOvergrown(level, pos);
        int[] used = paintSurface(level, pos, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, table);
        data.removePending(pos.toLong());
        return used[0];
    }

    /**
     * {@code start} 列目(0〜255、x が速く回る)から地表を塗る。戻り値 = {置き換えた数, 見た列の数, 次の列}。
     */
    private static int[] paintSurface(ServerLevel level, ChunkPos pos, int start, int placeBudget, int columnBudget,
                                      SpreadTable table) {
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        int minY = level.getMinBuildHeight();
        long seed = level.getSeed();
        double threshold = LedgerSpreadMath.noiseThreshold(table.surfaceCoverage());
        double growth = OvergrownVegetationConfig.COMMON.growthChance.get();
        RandomSource random = level.getRandom();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int placed = 0;
        int columns = 0;
        int c = start;
        while (c < 256 && placed < placeBudget && columns < columnBudget && (columns == 0 || TickBudget.timeLeft())) {
            int x = baseX + (c & 15);
            int z = baseZ + (c >> 4);
            c++;
            columns++;
            if (LedgerSpreadMath.patch(seed, x, z, table.patchSize()) >= threshold) {
                continue; // 塗り残し
            }
            int topY = OvergrownBiomeCreep.surfaceTopY(level, x, z);
            if (topY < minY) {
                continue;
            }
            // 過繁茂バイオームは常夏。元のバイオームで積もっていた雪・氷を剥がしてから地表を取り直す。
            if (OvergrownBiomeCreep.stripFrozenSurface(level, at, x, topY, z)) {
                topY = OvergrownBiomeCreep.surfaceTopY(level, x, z);
            }
            // 梢・幹・橋の下の林床・水底も(2026-11-01 夜、草の下の汚染の抜け)
            BlockPos target = OvergrownBiomeCreep.findTarget(level, at, x, topY, z, minY, table.surfaceDepth());
            if (target == null) {
                continue; // 汚染済み / 岩盤・水で打ち切り / 除去装置の阻害
            }
            BlockState state = level.getBlockState(target);
            BlockState corrupted = CorruptionRegistry.corrupt(level, target, state, random);
            if (CorruptionRegistry.noChange(state, corrupted)) {
                CorruptionSpreader.growPlantsIfNewlyCovered(level, target, state); // 覆っただけのマスにも植物(2026-10-10)
                continue;
            }
            CorruptionRegistry.placeCorrupted(level, target, state, corrupted, Block.UPDATE_ALL);
            CorruptionSpreader.growSurfacePlants(level, target, corrupted, growth);
            placed++;
        }
        if (placed > 0) {
            CorruptionCounter.record(level, placed);
        }
        return new int[]{placed, columns, c};
    }

    private LedgerSpreadCatchUp() {
    }
}
