package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>汚染されたエンドポータルの枠</b>(2026-10-02 ユーザー指定)。目の代わりに、ハイヴノードのような
 * 光る植物の腫瘍がはめ込まれていて、それでエンドポータルが無理やり開いている。
 *
 * <p>森が要塞のポータルの部屋に届くと {@link com.yayuy.overgrownvegetation.corruption.PortalCorruption} が
 * 輪の 12 個の枠をこれへ置き換え、内側 3 × 3 にエンドポータルを置く。壊せない(元の枠と同じ硬さ)。
 * 向きはバニラの枠と同じ {@link #FACING}(置き換えるときに引き継ぐ)。形は目のはまった枠と同じ。
 */
public class OvergrownEndPortalFrameBlock extends HorizontalDirectionalBlock {


    private static final VoxelShape BASE_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 13.0, 16.0);
    private static final VoxelShape TUMOR_SHAPE = Block.box(4.0, 13.0, 4.0, 12.0, 16.0, 12.0);
    private static final VoxelShape FULL_SHAPE = Shapes.or(BASE_SHAPE, TUMOR_SHAPE);

    public OvergrownEndPortalFrameBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return FULL_SHAPE;
    }

    /** バニラの目のはまった枠と同じく、コンパレーターには 15 を返す。 */
    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return 15;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
