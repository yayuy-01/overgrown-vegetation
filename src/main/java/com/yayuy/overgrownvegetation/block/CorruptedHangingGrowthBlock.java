package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 汚染ブロックの下面(天井側)から垂れ下がる根・蔓・苔。
 *
 * <p>{@code HangingRootsBlock} と同じ要領で、真上のブロックの下面が塞がっていれば生存できる。
 * {@link CorruptedGroundGrowthBlock} を上下反転しただけの関係だが、
 * 生存判定の向き({@code below} ではなく {@code above})が異なるため {@link net.minecraft.world.level.block.BushBlock} は使わず
 * {@link Block} を直接継承している。
 */
public class CorruptedHangingGrowthBlock extends Block {

    public static final MapCodec<CorruptedHangingGrowthBlock> CODEC = simpleCodec(CorruptedHangingGrowthBlock::new);

    private static final VoxelShape SHAPE = Block.box(2.0, 2.0, 2.0, 14.0, 16.0, 14.0);

    public CorruptedHangingGrowthBlock(BlockBehaviour.Properties properties) {
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
        BlockPos abovePos = pos.above();
        BlockState above = level.getBlockState(abovePos);
        return above.isFaceSturdy(level, abovePos, Direction.DOWN);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                      LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.UP && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
