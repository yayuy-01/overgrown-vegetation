package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * 水面に浮かぶ 1 枚の膜(2026-11-01): 川・池の藻の膜と胞子の膜(W5)、海の浮き藻の帯(O4)。睡蓮の葉と同じ 1 枚板だが、
 * 隣の膜と絵がつながるよう回さず、色付けもしない。水源の真上の空気にだけ置ける(流れの上には置けない)。当たり判定は無い。
 * <p>
 * 通った跡(2026-11-13)はブロックではなく、膜を描くシェーダーが抜く({@code corruption.FilmTrails}・{@code client.atmosphere.FilmWakes})。
 */
public class FloatingFilmBlock extends Block {


    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0);

    public FloatingFilmBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        FluidState below = level.getFluidState(pos.below());
        return below.is(Fluids.WATER) && below.isSource() && level.getFluidState(pos).isEmpty();
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return !state.canSurvive(level, pos)
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
