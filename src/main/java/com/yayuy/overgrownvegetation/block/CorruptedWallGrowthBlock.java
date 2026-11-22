package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * 汚染ブロックの側面から生えるサンゴ状の蔦・地衣類。
 *
 * <p>はしごのような壁に張り付く板ではなく、バニラのサンゴの群体({@code BaseCoralWallFanBlock}、
 * 例: {@code tube_coral_wall_fan})と同じ構造で作っている。{@code FACING} が向く方向と反対側にある
 * ブロックの面から、放射状に枝分かれしたモデルが壁の外へせり出す形になる。
 * {@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader} が露出した側面を見つけて
 * 自動的に生やすほか、アイテムとして手で設置することもできる。
 */
public class CorruptedWallGrowthBlock extends HorizontalDirectionalBlock {


    // サンゴの壁面ファン(BaseCoralWallFanBlock)と同じ当たり判定。
    private static final VoxelShape NORTH_SHAPE = Block.box(0.0, 4.0, 5.0, 16.0, 12.0, 16.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(0.0, 4.0, 0.0, 16.0, 12.0, 11.0);
    private static final VoxelShape WEST_SHAPE = Block.box(5.0, 4.0, 0.0, 16.0, 12.0, 16.0);
    private static final VoxelShape EAST_SHAPE = Block.box(0.0, 4.0, 0.0, 11.0, 12.0, 16.0);

    public CorruptedWallGrowthBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            case EAST -> EAST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }

    private boolean canAttachTo(BlockGetter level, BlockPos pos, Direction direction) {
        return level.getBlockState(pos).isFaceSturdy(level, pos, direction);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        return canAttachTo(level, pos.relative(facing.getOpposite()), facing);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                      LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return direction.getOpposite() == state.getValue(FACING) && !state.canSurvive(level, pos)
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = this.defaultBlockState();
        LevelReader level = context.getLevel();
        BlockPos pos = context.getClickedPos();

        for (Direction direction : context.getNearestLookingDirections()) {
            if (direction.getAxis().isHorizontal()) {
                state = state.setValue(FACING, direction.getOpposite());
                if (state.canSurvive(level, pos)) {
                    return state;
                }
            }
        }
        return null;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
