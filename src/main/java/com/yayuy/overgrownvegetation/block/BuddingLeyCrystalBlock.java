package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AmethystBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/**
 * 芽吹く地脈の結晶(2026-10-22。ユーザー指定「地脈の線の上にまれに生え、アメジストと同じく芽から育つ」)。
 * バニラの芽吹くアメジストと同じく、ランダムな tick に {@link #GROW_CHANCE} の 1 で隣に芽を出すか、隣の芽を 1 段育てる
 * (小 → 中 → 大 → 塊)。バニラは 6 方向から 1 つを選ぶが、これは地表に生えて下と横の多くが土で塞がるので、
 * <b>空いている面と自分の芽がある面</b>からだけ選ぶ(仮決め。上だけ空いていれば 1 段およそ 6 分、塊まで 20 分弱)。
 * 壊しても何も落ちない(バニラと同じ。結晶を採り尽くさないように)。
 */
public class BuddingLeyCrystalBlock extends AmethystBlock {

    public static final MapCodec<BuddingLeyCrystalBlock> CODEC = simpleCodec(BuddingLeyCrystalBlock::new);
    /** 芽が伸びる確率の逆数(バニラと同じ 5)。 */
    public static final int GROW_CHANCE = 5;

    public BuddingLeyCrystalBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<BuddingLeyCrystalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextInt(GROW_CHANCE) == 0) {
            grow(level, pos, random);
        }
    }

    /** 1 回ぶん育てる(空いている面に芽を出すか、芽を 1 段育てる)。育てたら true。GameTest からも呼ぶ。 */
    public static boolean grow(ServerLevel level, BlockPos pos, RandomSource random) {
        List<Direction> open = new ArrayList<>(6);
        for (Direction d : Direction.values()) {
            BlockState there = level.getBlockState(pos.relative(d));
            if (canClusterGrowAtState(there) || (next(there) != null && there.getValue(AmethystClusterBlock.FACING) == d)) {
                open.add(d);
            }
        }
        if (open.isEmpty()) {
            return false;
        }
        Direction d = open.get(random.nextInt(open.size()));
        BlockPos at = pos.relative(d);
        BlockState there = level.getBlockState(at);
        Block block = canClusterGrowAtState(there) ? ModBlocks.SMALL_LEY_CRYSTAL_BUD.get() : next(there);
        if (block == null) {
            return false;
        }
        level.setBlockAndUpdate(at, block.defaultBlockState()
                .setValue(AmethystClusterBlock.FACING, d)
                .setValue(AmethystClusterBlock.WATERLOGGED, there.getFluidState().getType() == Fluids.WATER));
        return true;
    }

    /** その芽の次の段。塊か芽でなければ null。 */
    private static Block next(BlockState state) {
        if (state.is(ModBlocks.SMALL_LEY_CRYSTAL_BUD.get())) {
            return ModBlocks.MEDIUM_LEY_CRYSTAL_BUD.get();
        }
        if (state.is(ModBlocks.MEDIUM_LEY_CRYSTAL_BUD.get())) {
            return ModBlocks.LARGE_LEY_CRYSTAL_BUD.get();
        }
        if (state.is(ModBlocks.LARGE_LEY_CRYSTAL_BUD.get())) {
            return ModBlocks.LEY_CRYSTAL.get();
        }
        return null;
    }

    public static boolean canClusterGrowAtState(BlockState state) {
        return state.isAir() || state.is(net.minecraft.world.level.block.Blocks.WATER) && state.getFluidState().getAmount() == 8;
    }
}
