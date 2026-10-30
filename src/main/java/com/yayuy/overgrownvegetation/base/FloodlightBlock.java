package com.yayuy.overgrownvegetation.base;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 投光器(2026-10-01)。床に置く照明。台座 + 支柱 + 灯体で、灯体の正面(レンズ)が置いた人のほうを向く。
 * 明るさは 15 で常に点いている(電源・燃料は無い。仮決め)。
 */
public class FloodlightBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<FloodlightBlock> CODEC = simpleCodec(FloodlightBlock::new);

    /** 灯体は向きで回るが、当たり判定は回しても収まる 10×13×10 の箱 1 つにする。 */
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(5.0D, 0.0D, 5.0D, 11.0D, 7.0D, 11.0D),
            Block.box(3.0D, 7.0D, 3.0D, 13.0D, 13.0D, 13.0D));

    public FloodlightBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
}
