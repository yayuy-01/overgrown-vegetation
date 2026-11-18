package com.yayuy.overgrownvegetation.worldgen.grammar;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * {@link GrammarStructureFeature} が {@code stage_over_ticks: true} のとき、生成したブロックを
 * <b>複数ゲームティックに分けて</b>設置するキュー。{@link com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue}
 * の簡易版(1 本のブロック列を順に置くだけ。装飾フェーズなし)。
 *
 * <p>1 ティックあたりの設置数は巨大樹と同じ設定 {@code giantTreeBlocksPerTick} を流用する
 * (どちらも「worldgen で作った大量ブロックをティック分割で置く」用途なので)。
 *
 * <p>キューはメモリ上のみ。生成途中でセーブ&終了すると未完成で止まるが数秒で終わるので実害は小さい。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GrammarGrowthQueue {

    private static final int UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    public record StagedBlock(long packedPos, BlockState state) {
    }

    private static final class Job {
        final ServerLevel level;
        final List<StagedBlock> blocks;
        int cursor;

        Job(ServerLevel level, List<StagedBlock> blocks) {
            this.level = level;
            this.blocks = blocks;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();

    public static void enqueue(ServerLevel level, List<StagedBlock> blocks) {
        if (level == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        JOBS.add(new Job(level, blocks));
        OvergrownVegetation.LOGGER.info("grammar_structure: {} ブロックをティック分割で設置します", blocks.size());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (JOBS.isEmpty() || !(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        // サーバーが重いときは予算の番人が次の tick へ回す(2026-10-06。結果は同じで遅れるだけ)。
        if (JOBS.stream().noneMatch(job -> job.level == serverLevel) || !TickBudget.mayStart(PerfJob.GRAMMAR_GROWTH)) {
            return;
        }
        long start = TickBudget.begin();
        int perTick = OvergrownVegetationConfig.COMMON.giantTreeBlocksPerTick.get();
        int left = perTick;
        try {
            left = tickJobs(serverLevel, perTick);
        } finally {
            TickBudget.end(PerfJob.GRAMMAR_GROWTH, start, perTick - left);
        }
    }

    /** 1 tick ぶん置く。戻り値は使い残した予算。 */
    private static int tickJobs(ServerLevel serverLevel, int perTick) {
        int budget = perTick;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext() && budget > 0) {
            Job job = it.next();
            if (job.level != serverLevel) {
                continue;
            }
            while (job.cursor < job.blocks.size() && budget > 0) {
                StagedBlock sb = job.blocks.get(job.cursor);
                cursor.set(BlockPos.getX(sb.packedPos()), BlockPos.getY(sb.packedPos()), BlockPos.getZ(sb.packedPos()));
                // 読み込み・生成の途中なら触らず、このジョブは次の tick に同じ所から(11-18 見直し ③4。isLoaded は生成中でも true)
                com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.Edge edge = com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.edge(serverLevel, cursor, 0);
                if (edge == com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.Edge.WAIT) break;
                if (edge == com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.Edge.READY) {
                    serverLevel.setBlock(cursor.immutable(), sb.state(), UPDATE_FLAGS);
                }
                job.cursor++;
                budget = TickBudget.spend(budget);
            }
            if (job.cursor >= job.blocks.size()) {
                it.remove();
            }
        }
        return Math.max(0, budget);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            JOBS.removeIf(job -> job.level == serverLevel);
        }
    }

    private GrammarGrowthQueue() {
    }
}
