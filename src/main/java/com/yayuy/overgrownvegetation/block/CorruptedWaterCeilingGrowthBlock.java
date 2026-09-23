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
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 水中で、汚染ブロックの下面(天井側)から垂れ下がる汚染植物。
 *
 * <p>{@link CorruptedHangingGrowthBlock} を水中向けにしたもの。常に水を含んだ状態として振る舞い、
 * 真上のブロックの下面が塞がっていて、かつ自分のマスが水であれば生存する。崩れるときは水へ戻す。
 */
public class CorruptedWaterCeilingGrowthBlock extends Block {

    public static final MapCodec<CorruptedWaterCeilingGrowthBlock> CODEC = simpleCodec(CorruptedWaterCeilingGrowthBlock::new);

    private static final VoxelShape SHAPE = Block.box(2.0, 2.0, 2.0, 14.0, 16.0, 14.0);

    public CorruptedWaterCeilingGrowthBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (!level.getFluidState(pos).is(FluidTags.WATER)) {
            return false;
        }
        BlockPos abovePos = pos.above();
        return level.getBlockState(abovePos).isFaceSturdy(level, abovePos, Direction.DOWN);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return !state.canSurvive(level, pos)
                ? Blocks.WATER.defaultBlockState()
                : super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return Fluids.WATER.getSource(false);
    }
}
