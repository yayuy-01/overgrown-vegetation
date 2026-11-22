package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 岸から垂れる根(W3、2026-11-01)。岸の木の幹・草の岸の下面や、水面の高さの岸の壁から水の中へ 2〜5 段垂れる赤茶の根。
 * バニラの垂れ蔦と同じく中と先({@link #TIP})の 2 つの絵を縦に連ねる。水中専用(自分が水源、崩れると水に戻る)。
 *
 * <p>支え: 真上が根、真上の下面が塞がっている、または(いちばん上の段だけ)横に塞がった面がある(岸の壁から出る)。
 */
public class WaterRootsBlock extends Block implements LiquidBlockContainer {

    public static final BooleanProperty TIP = BooleanProperty.create("tip");

    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 16.0, 13.0);

    public WaterRootsBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(TIP, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TIP);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        if (!fluid.is(Fluids.WATER) || !fluid.isSource()) {
            return null;
        }
        BlockState below = context.getLevel().getBlockState(context.getClickedPos().below());
        return defaultBlockState().setValue(TIP, !below.is(this));
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return isSupported(level, pos, this);
    }

    /** {@code pos} に根を下げられるか(真上が根・真上の下面が塞がる・横の面が塞がる)。 */
    public static boolean isSupported(LevelReader level, BlockPos pos, Block roots) {
        BlockPos above = pos.above();
        BlockState up = level.getBlockState(above);
        if (up.is(roots) || up.isFaceSturdy(level, above, Direction.DOWN)) {
            return true;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(d);
            if (level.getBlockState(side).isFaceSturdy(level, side, d.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!state.canSurvive(level, pos)) {
            return Blocks.WATER.defaultBlockState();
        }
        if (direction == Direction.DOWN) {
            return state.setValue(TIP, !neighborState.is(this));
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return Fluids.WATER.getSource(false);
    }

    @Override
    public boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluid) {
        return false;
    }
}
