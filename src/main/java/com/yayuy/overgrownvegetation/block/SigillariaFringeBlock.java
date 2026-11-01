package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
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
 * 当たり判定は無く、支えの判定も持たない(置かれたまま残る)。手で置くとクリックした面の側に付く。
 * 板なので隣の面を隠さず、空の光も通す。
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
