package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 水中で、汚染ブロックの上面から生える高さ1マスの下草(汚染された海草)。
 *
 * <p>バニラの {@code SeagrassBlock} と同じ考え方で、常に水を含んだ状態として振る舞い
 * ({@link #getFluidState} が水源を返す)、置き換えても水が消えて気泡が残ることはない。
 * 生存条件は下のブロックの上面が塞がっていて、かつ自分のマスが水であること。
 * 見た目(テクスチャ)違いのバリエーションはこの1クラスを使い回す。
 */
public class CorruptedWaterGrowthBlock extends BushBlock {

    public static final MapCodec<CorruptedWaterGrowthBlock> CODEC = simpleCodec(CorruptedWaterGrowthBlock::new);

    private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 12.0, 14.0);

    public CorruptedWaterGrowthBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
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
        return level.getFluidState(pos).is(FluidTags.WATER) && super.canSurvive(state, level, pos);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // 崩れるときは(空気ではなく)水へ戻し、水中に気泡が残らないようにする。
        return !state.canSurvive(level, pos)
                ? Blocks.WATER.defaultBlockState()
                : super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return Fluids.WATER.getSource(false);
    }
}
