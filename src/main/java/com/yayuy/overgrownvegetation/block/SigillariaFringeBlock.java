package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * 封印木の外の葉(2026-11-01 昼に試作を採用): 立方体でなく草花のような板。{@link #FACING} は付け根が接する芯(芯の葉・原木・枝)の向き。
 * 芯が下 = 立ち上がる房(cross)、芯が上 = 垂れる葉(cross)、芯が横 = 芯の面から外へ開く V 字の 2 枚(付け根の板は 11-01 に不採用)、芯の角 = 芯の縦の辺から開く V 字({@link #CORNER})。
 *
 * <p>木の一部としての判定({@code instanceof OvergrownLeavesBlock})を変えないため {@link OvergrownLeavesBlock} を継ぐ。
 * 当たり判定は無い。手で置くとクリックした面の側に付く。板なので隣の面を隠さず、空の光も通す。
 *
 * <p>支え(2026-11-03、元の葉を壊しても板が浮くのを直した): 付け根の芯のマス({@link #supportPos}。角の葉は斜めの芯)が空気・流体になると崩れる
 * (1 tick 後に {@code destroyBlock}。水中なら水に戻る)。角の葉の芯は面で接していないので、芯の葉が消えたとき {@link #checkCorners} で知らせる
 * ({@link OvergrownLeavesBlock#onRemove})。芯の葉そのもの(腕の先の葉)は支えを見ない(バニラの葉の距離のような仕組みが無い)。
 */
public class SigillariaFringeBlock extends OvergrownLeavesBlock {

    public static final MapCodec<SigillariaFringeBlock> CODEC = simpleCodec(SigillariaFringeBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    /**
     * 芯の角(平面で見て斜めの位置)に付く葉。芯は {@link #FACING} とその左隣の向きの間の斜め(north なら北西)にあり、
     * 葉は芯の縦の辺から外へ V 字に開く。{@link #FACING} が横のときだけ意味がある。
     */
    public static final BooleanProperty CORNER = BooleanProperty.create("corner");

    // 選択枠だけ(草と同じくらいの箱を付け根の側へ寄せる)
    private static final VoxelShape DOWN_SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 13.0, 14.0);
    private static final VoxelShape UP_SHAPE = Block.box(2.0, 3.0, 2.0, 14.0, 16.0, 14.0);
    private static final VoxelShape NORTH_SHAPE = Block.box(2.0, 2.0, 0.0, 14.0, 14.0, 12.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(2.0, 2.0, 4.0, 14.0, 14.0, 16.0);
    private static final VoxelShape WEST_SHAPE = Block.box(0.0, 2.0, 2.0, 12.0, 14.0, 14.0);
    private static final VoxelShape EAST_SHAPE = Block.box(4.0, 2.0, 2.0, 16.0, 14.0, 14.0);

    public SigillariaFringeBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.DOWN).setValue(CORNER, false));
    }

    @Override
    protected MapCodec<? extends SigillariaFringeBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CORNER);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case UP -> UP_SHAPE;
            case NORTH -> NORTH_SHAPE;
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            case EAST -> EAST_SHAPE;
            default -> DOWN_SHAPE;
        };
    }

    /** 付け根の芯のマス。角の葉({@link #CORNER} かつ横向き)は {@link #FACING} とその左隣の向きの間の斜め。 */
    public static BlockPos supportPos(BlockState state, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos core = pos.relative(facing);
        return state.getValue(CORNER) && facing.getAxis().isHorizontal() ? core.relative(facing.getCounterClockWise()) : core;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState core = level.getBlockState(supportPos(state, pos));
        return !core.isAir() && !(core.getBlock() instanceof LiquidBlock);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // どの向きの更新でも見る(角の葉の芯は面で接しないので、芯の横の板が消えた知らせでも確かめる)
        if (!state.canSurvive(level, pos)) {
            level.scheduleTick(pos, this, 1);
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    /** 芯 {@code removed} が消えたとき、それを斜めの芯にしている角の葉に崩れる tick を予約する(面で接しないので updateShape が来ない)。 */
    public static void checkCorners(Level level, BlockPos removed) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            p.setWithOffset(removed, d.getStepX() + d.getClockWise().getStepX(), 0, d.getStepZ() + d.getClockWise().getStepZ());
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof SigillariaFringeBlock && s.getValue(CORNER) && !s.canSurvive(level, p)) {
                level.scheduleTick(p.immutable(), s.getBlock(), 1);
            }
        }
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    /** 板なので隣の面は隠さない(芯の葉に穴を開けない)。 */
    @Override
    public boolean hidesNeighborFace(BlockGetter level, BlockPos pos, BlockState state, BlockState neighborState, Direction dir) {
        return false;
    }

    @Override
    public boolean supportsExternalFaceHiding(BlockState state) {
        return false;
    }
}
