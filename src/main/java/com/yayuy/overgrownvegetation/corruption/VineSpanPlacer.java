package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 太い蔦の組を何 tick かに分けて置く待ち行列(2026-11-07 ユーザー賛成「組を置くのを何 tick かに分ける」。前は 1 組を 1 tick で置き、13〜18 ms かかった)。
 *
 * <p>{@link VineSpan#build} が形(芯のマス)を決めたら、ここへ入れる。1 tick に {@link #CELLS_PER_TICK} マスか {@link #NANOS_PER_TICK} まで
 * (仮決め 64 マス・2 ms)、先に葉を全部置き、次に植物({@link VineSpan#decorateCell})を付ける。組の記録({@link VineSpanRecord})は
 * {@code build} が置き始めに入れる。
 *
 * <p>チャンクが読み込まれていないマスに来たら、その組はそこで待つ(次の tick に同じマスからやり直す。ほかの組は先へ進む)。
 * {@link #WAIT_LIMIT_TICKS}(仮決め 6000 = 5 分)待っても読み込まれなければ、その組の残りは捨てる(置いた所までが残る)。
 * 待ち行列は保存しない: サーバーを止めると置きかけの組は置いた所までで終わる(記録は残るので、そこへ新しい組は出ない)。仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class VineSpanPlacer {

    /** 1 tick に置くマスの上限と時間の上限(仮決め)。 */
    public static final int CELLS_PER_TICK = 64;
    public static final long NANOS_PER_TICK = 2_000_000L;
    /** 読み込まれていないマスで待つ上限(tick、仮決め)。 */
    public static final int WAIT_LIMIT_TICKS = 6000;

    /** dev の数え: 置く仕事の 1 tick あたりの時間の合計と最大(ナノ秒)、置き終えた組・捨てた組の数。 */
    public static final long[] NANOS = new long[2];
    public static int DONE;
    public static int DROPPED;

    private static final Map<ResourceKey<Level>, ArrayDeque<Job>> QUEUES = new HashMap<>();

    private VineSpanPlacer() {
    }

    private static final class Job {
        final long[] order;
        final LongOpenHashSet cells;
        final LongOpenHashSet tuftTips;
        final boolean cave;
        final RandomSource random;
        int index;
        boolean decorating;
        int waited;

        Job(long[] order, LongOpenHashSet tuftTips, boolean cave, long seed) {
            this.order = order;
            this.cells = new LongOpenHashSet(order);
            this.tuftTips = tuftTips;
            this.cave = cave;
            this.random = RandomSource.create(seed);
        }
    }

    static void enqueue(ServerLevel level, long[] cells, LongOpenHashSet tuftTips, boolean cave, long seed) {
        QUEUES.computeIfAbsent(level.dimension(), k -> new ArrayDeque<>()).add(new Job(cells, new LongOpenHashSet(tuftTips), cave, seed));
    }

    /** 置きかけの組の数(dev・テスト用)。 */
    public static int pending(ServerLevel level) {
        ArrayDeque<Job> q = QUEUES.get(level.dimension());
        return q == null ? 0 : q.size();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ArrayDeque<Job> q = QUEUES.get(level.dimension());
        if (q == null || q.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        int budget = CELLS_PER_TICK;
        BlockState leaf = ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState();
        List<Job> waiting = new ArrayList<>();
        while (budget > 0 && !q.isEmpty() && System.nanoTime() - start < NANOS_PER_TICK) {
            Job job = q.peekFirst();
            boolean blocked = false;
            while (budget > 0 && System.nanoTime() - start < NANOS_PER_TICK) {
                if (job.index >= job.order.length) {
                    if (job.decorating) {
                        break;
                    }
                    job.decorating = true;
                    job.index = 0;
                }
                long key = job.order[job.index];
                if (level.getChunkSource().getChunkNow(BlockPos.getX(key) >> 4, BlockPos.getZ(key) >> 4) == null) {
                    blocked = true;
                    break;
                }
                if (job.decorating) {
                    VineSpan.decorateCell(level, key, job.cells, job.tuftTips, job.cave, job.random);
                } else if (com.yayuy.overgrownvegetation.corruption.pushback.RingWards.blocks(level, BlockPos.of(key))) {
                    // 帯の線の向こう・杭の範囲へは蔦を渡さない(押し返し 11-18 夜)
                } else {
                    level.setBlock(BlockPos.of(key), leaf, Block.UPDATE_CLIENTS);
                }
                job.index++;
                budget--;
            }
            if (job.decorating && job.index >= job.order.length) {
                q.pollFirst();
                DONE++;
            } else if (blocked) {
                q.pollFirst();
                if (++job.waited > WAIT_LIMIT_TICKS) {
                    DROPPED++;
                } else {
                    waiting.add(job); // 読み込まれるまで後ろで待つ(この tick はもう見ない)
                }
            } else {
                break; // 予算を使い切った
            }
        }
        q.addAll(waiting);
        long dt = System.nanoTime() - start;
        NANOS[0] += dt;
        NANOS[1] = Math.max(NANOS[1], dt);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QUEUES.clear();
    }
}
