package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>枝の継ぎ目</b>({@code overgrown_branch}。2026-11-01 ユーザー決定「作る」。形・太さ・繋がる相手は仮決め)。
 * 階段状に並ぶ枝の原木の隙間を埋め、枝先を先細りにして、木の輪郭を滑らかにする。柵と同じ仕組みで、面で接する隣が
 * {@code #overgrown_vegetation:branch_connectable}(原木・継ぎ目自身・原木から化けた幹)ならその向きへ腕を出す。葉には繋げない。
 *
 * <p>形は中央の芯 + 芯から面までの腕。芯は腕と同じ太さ(太い 6、細い 4。11-01 ユーザー指定)で、継ぎ目は一本の棒に見える。当たり判定も同じ形。
 * 生成で置くときは {@link #connectedState} で 6 向きを見て組む(生成中は隣の {@code updateShape} が呼ばれないことがあるので)。
 * 置くのは {@code worldgen/BranchJoints} の計画に従う {@code worldgen/ForcedTreeFeature}。
 *
 * <p>封印木など別の木の継ぎ目は、このクラスのまま絵(blockstate)と名前だけ替えた別ブロックとして登録する。
 */
public class BranchJointBlock extends Block implements SimpleWaterloggedBlock {

    public static final MapCodec<BranchJointBlock> CODEC = simpleCodec(BranchJointBlock::new);
    public static final BooleanProperty NORTH = PipeBlock.NORTH;
    public static final BooleanProperty EAST = PipeBlock.EAST;
    public static final BooleanProperty SOUTH = PipeBlock.SOUTH;
    public static final BooleanProperty WEST = PipeBlock.WEST;
    public static final BooleanProperty UP = PipeBlock.UP;
    public static final BooleanProperty DOWN = PipeBlock.DOWN;
    public static final BooleanProperty THICK = BooleanProperty.create("thick");
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    /** 芯の一辺と腕の一辺(1/16 ブロック)。11-01 ユーザー指定で芯を腕と同じ太さに(一本の棒に見せる。前は芯 10 / 6)。 */
    public static final int THICK_CORE = 6, THICK_ARM = 6, THIN_CORE = 4, THIN_ARM = 4;

    private static final Map<Integer, VoxelShape> SHAPES = new HashMap<>();

    public BranchJointBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false)
                .setValue(THICK, true).setValue(WATERLOGGED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN, THICK, WATERLOGGED);
    }

    /** {@code neighbor} へ腕を出すか。 */
    public static boolean connectsTo(BlockState neighbor) {
        return neighbor.is(ModTags.BRANCH_CONNECTABLE);
    }

    /** {@code base} の 6 向きの腕を、{@code pos} のまわりの今のブロックから組み直した状態(水も)。 */
    public static BlockState connectedState(BlockGetter level, BlockPos pos, BlockState base) {
        BlockState out = base;
        for (Direction d : Direction.values()) {
            out = out.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d), connectsTo(level.getBlockState(pos.relative(d))));
        }
        return out.setValue(WATERLOGGED, level.getFluidState(pos).getType() == Fluids.WATER);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // しゃがんで置くと細い継ぎ目(仮決め)
        return connectedState(context.getLevel(), context.getClickedPos(),
                defaultBlockState().setValue(THICK, !context.isSecondaryUseActive()));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level,
            BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(direction), connectsTo(neighborState));
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeOf(state);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return !state.getValue(WATERLOGGED);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    /** 芯 + 出ている腕の和(太さと 6 向きの組ごとに 1 度だけ作る)。 */
    public static VoxelShape shapeOf(BlockState state) {
        boolean thick = state.getValue(THICK);
        int key = thick ? 64 : 0;
        Direction[] dirs = Direction.values();
        for (int i = 0; i < dirs.length; i++) {
            if (state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(dirs[i]))) {
                key |= 1 << i;
            }
        }
        synchronized (SHAPES) {
            return SHAPES.computeIfAbsent(key, k -> buildShape(k));
        }
    }

    private static VoxelShape buildShape(int key) {
        boolean thick = (key & 64) != 0;
        double c = (16 - (thick ? THICK_CORE : THIN_CORE)) / 2.0;
        double a = (16 - (thick ? THICK_ARM : THIN_ARM)) / 2.0;
        VoxelShape shape = Block.box(c, c, c, 16 - c, 16 - c, 16 - c);
        Direction[] dirs = Direction.values();
        for (int i = 0; i < dirs.length; i++) {
            if ((key & (1 << i)) == 0) {
                continue;
            }
            VoxelShape arm = switch (dirs[i]) {
                case DOWN -> Block.box(a, 0, a, 16 - a, c, 16 - a);
                case UP -> Block.box(a, 16 - c, a, 16 - a, 16, 16 - a);
                case NORTH -> Block.box(a, a, 0, 16 - a, 16 - a, c);
                case SOUTH -> Block.box(a, a, 16 - c, 16 - a, 16 - a, 16);
                case WEST -> Block.box(0, a, a, c, 16 - a, 16 - a);
                case EAST -> Block.box(16 - c, a, a, 16, 16 - a, 16 - a);
            };
            shape = Shapes.or(shape, arm);
        }
        return shape.optimize();
    }
}
