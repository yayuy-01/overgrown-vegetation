package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * <b>流れている溶岩に汚染が来たら、その流れの源泉を玄武岩へ固める</b>(2026-10-02 ユーザー指定)。
 *
 * <p>固めた源泉は 2026-10-05 夜から<b>覆われた玄武岩</b>(3 割は過繁茂しているブロック。{@link CorruptionRegistry#solidifiedLava})。
 * それまでは素の玄武岩で、その上に植物が生えて違和感があった(ユーザー指摘)。
 *
 * <p>源泉そのものは以前から {@code LAVA → BASALT} で固まる。流れている溶岩は固めると流れの途中に穴が空いたまま
 * 残るので触らなかったが、それだと滝や流れの先に汚染が来ても溶岩は流れ続ける。そこで流れを<b>上流へ</b>辿って
 * 源泉を見つけ、源泉だけを固める。流れはバニラの液体の更新で自然に消える。
 *
 * <p>上流の決め方: 真上の溶岩(落ちてくる流れ)と、横で自分より量の多い溶岩(源泉は量 8)。
 * 辿るのは {@link #MAX_VISITED} マス・源泉 {@link #MAX_SOURCES} 個まで —— ネザーの溶岩の海の縁では、
 * 流れに接している源泉だけが固まり、海は汚染が来るたびに縁から少しずつ固まっていく(仮決め)。
 *
 * <p>流れが消えるまでの数十 tick のあいだ、同じ流れに何度も汚染が来るので、
 * 一度辿ったマスは {@link #COOLDOWN_TICKS} のあいだ辿り直さない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LavaSolidifier {

    /** 1 回で辿るマスの上限(仮決め)。 */
    private static final int MAX_VISITED = 256;
    /** 1 回で固める源泉の上限(仮決め)。 */
    private static final int MAX_SOURCES = 16;
    /** 同じマスを辿り直さない時間(仮決め)。 */
    private static final long COOLDOWN_TICKS = 100L;

    /** 辿ったマス → 辿ったゲーム時刻。次元ごとに分けていない(座標が重なっても辿り直しが 5 秒遅れるだけ)。 */
    private static final Long2LongOpenHashMap RECENT = new Long2LongOpenHashMap();

    /** 流れている溶岩か(源泉ではない溶岩)。 */
    static boolean isFlowingLava(BlockState state) {
        return state.is(Blocks.LAVA) && !state.getFluidState().isSource();
    }

    /**
     * {@code flowing}(流れている溶岩)から上流へ辿って、見つけた源泉を玄武岩へ固める。
     *
     * @return 固めた源泉の数
     */
    static int solidifySourcesOf(ServerLevel level, BlockPos flowing) {
        long now = level.getGameTime();
        long start = flowing.asLong();
        if (now - RECENT.getOrDefault(start, Long.MIN_VALUE / 2) < COOLDOWN_TICKS) {
            return 0;
        }
        if (RECENT.size() > 8192) {
            RECENT.clear();
        }

        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongOpenHashSet seen = new LongOpenHashSet();
        queue.enqueue(start);
        seen.add(start);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        int solidified = 0;

        while (!queue.isEmpty() && seen.size() <= MAX_VISITED && solidified < MAX_SOURCES) {
            long key = queue.dequeueLong();
            RECENT.put(key, now);
            cursor.set(key);
            BlockState state = level.getBlockState(cursor);
            if (!state.is(Blocks.LAVA)) {
                continue;
            }
            FluidState fluid = state.getFluidState();
            if (fluid.isSource()) {
                if (CorruptionRegistry.canCorrupt(level, cursor, state)) {
                    // 記録で覆った玄武岩(3 割は過繁茂しているブロック)。2026-10-05 夜まではバニラの玄武岩だった(植物が素の玄武岩に生えて違和感)。
                    BlockPos at = cursor.immutable();
                    BlockState basalt = CorruptionRegistry.solidifiedLava(at);
                    CorruptionRegistry.placeCorrupted(level, at, state, basalt, Block.UPDATE_ALL);
                    if (CorruptionRegistry.coversInPlace(basalt)) {
                        CorruptionRegistry.coverInPlace(level, at, basalt); // 2026-10-30 から覆われた玄武岩 = 玄武岩 + 記録
                    }
                    solidified++;
                }
                continue; // 源泉より上流は無い
            }
            int amount = fluid.getAmount();
            for (Direction dir : Direction.values()) {
                if (dir == Direction.DOWN) {
                    continue;
                }
                next.setWithOffset(cursor, dir);
                long nextKey = next.asLong();
                if (seen.contains(nextKey) || !com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, next)) {
                    continue;
                }
                FluidState other = level.getFluidState(next);
                if (!other.is(net.minecraft.tags.FluidTags.LAVA)) {
                    continue;
                }
                boolean upstream = dir == Direction.UP || other.isSource() || other.getAmount() > amount;
                if (upstream) {
                    seen.add(nextKey);
                    queue.enqueue(nextKey);
                }
            }
        }
        return solidified;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RECENT.clear();
    }

    private LavaSolidifier() {
    }
}
