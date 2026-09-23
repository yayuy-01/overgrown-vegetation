package com.yayuy.overgrownvegetation.worldgen.grammar;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
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
        int budget = OvergrownVegetationConfig.COMMON.giantTreeBlocksPerTick.get();
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
                if (serverLevel.isLoaded(cursor)) {
                    serverLevel.setBlock(cursor.immutable(), sb.state(), UPDATE_FLAGS);
                }
                job.cursor++;
                budget--;
            }
            if (job.cursor >= job.blocks.size()) {
                it.remove();
            }
        }
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
