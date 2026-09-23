package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 水中で、汚染ブロックの上面から生える高さ2マスの下草(汚染された背高海草)。
 *
 * <p>バニラの {@code TallSeagrassBlock} と同じく {@link DoublePlantBlock} をベースに、
 * 上下半分とも常に水を含んだ状態として振る舞う({@link #getFluidState})。
 * 下半分は「下のブロックの上面が塞がっている」かつ「自分のマスが水」で生存し、
 * 上半分は「真下が同じブロックの下半分」で生存する。
 */
public class CorruptedTallWaterGrowthBlock extends DoublePlantBlock {

    public static final MapCodec<CorruptedTallWaterGrowthBlock> CODEC = simpleCodec(CorruptedTallWaterGrowthBlock::new);

    private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 16.0, 14.0);

    public CorruptedTallWaterGrowthBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<CorruptedTallWaterGrowthBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isFaceSturdy(level, pos, Direction.UP);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
        }
        return level.getFluidState(pos).is(FluidTags.WATER) && super.canSurvive(state, level, pos);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return Fluids.WATER.getSource(false);
    }
}
