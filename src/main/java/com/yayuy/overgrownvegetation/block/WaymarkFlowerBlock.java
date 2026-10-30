package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * 道しるべの花(2026-10-16、ideas.useful_plants)。過繁茂のバイオームに生え、花が必ずいちばん近いハイヴノードの方を向く
 * (エネルギーは根を通ってハイヴノードへ流れ込むので)。⑦ 地脈の羅針盤の材料。向きはランダムな tick で合わせ直す。
 */
public class WaymarkFlowerBlock extends BushBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final VoxelShape SHAPE = Block.box(4.0D, 0.0D, 4.0D, 12.0D, 13.0D, 12.0D);

    public WaymarkFlowerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
        return MapCodec.unit(this);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
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
        return mayPlaceOn(level.getBlockState(pos.below()), level, pos.below());
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        Direction toward = towardNearestNode(level, pos);
        if (toward != null && toward != state.getValue(FACING)) {
            level.setBlock(pos, state.setValue(FACING, toward), Block.UPDATE_CLIENTS);
        }
    }

    /** いちばん近いハイヴノード(水平)の方角。記録が無ければ null。 */
    @Nullable
    public static Direction towardNearestNode(ServerLevel level, BlockPos pos) {
        BlockPos node = nearestNode(level, pos);
        if (node == null) {
            return null;
        }
        return Direction.getNearest(node.getX() - pos.getX(), 0, node.getZ() - pos.getZ());
    }

    @Nullable
    public static BlockPos nearestNode(ServerLevel level, BlockPos pos) {
        long best = Long.MAX_VALUE;
        BlockPos found = null;
        for (long packed : PhaseData.get(level).hiveNodeSnapshot()) {
            BlockPos p = BlockPos.of(packed);
            long dx = p.getX() - pos.getX();
            long dz = p.getZ() - pos.getZ();
            long d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                found = p;
            }
        }
        return found;
    }
}
