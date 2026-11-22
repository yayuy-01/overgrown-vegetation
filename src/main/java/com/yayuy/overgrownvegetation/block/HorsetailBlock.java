package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 浅い淵に群生するトクサ(2026-11-02、下絵 16 の案 A。ユーザー指定): cross の段重ね。下から 根元・(中 × 0〜2)・先 と積み、
 * 根元は塞がった上面の上、ほかは下のトクサの上だけに立つ。下が支えでなくなったら次の tick に壊れる(メドゥロサと同じく上へ順に崩れる)。
 * 水没できる(根元の 1 段が深さ 1 の水に立つ)。当たり判定なし、鋏・シルクタッチで取れる(林床の下草と同じ)。
 * 先が折れたら、残った中の段が先の絵になる。
 */
public class HorsetailBlock extends Block implements SimpleWaterloggedBlock {

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 16.0, 13.0);

    public enum Part implements StringRepresentable {
        BOTTOM("bottom"), MIDDLE("middle"), TOP("top");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public HorsetailBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(PART, Part.BOTTOM).setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART, WATERLOGGED);
    }

    /**
     * {@code bottom} から高さ {@code height}(2 以上)の 1 本を置く。根元が水源の中なら水没。上のマスは呼び手が空気か確かめておく。
     */
    public static void placeStem(LevelAccessor level, BlockPos bottom, int height, BlockState base) {
        for (int i = 0; i < height; i++) {
            BlockPos p = bottom.above(i);
            Part part = i == 0 ? Part.BOTTOM : (i == height - 1 ? Part.TOP : Part.MIDDLE);
            boolean water = level.getFluidState(p).is(Fluids.WATER) && level.getFluidState(p).isSource();
            level.setBlock(p, base.setValue(PART, part).setValue(WATERLOGGED, water), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Vec3 offset = state.getOffset(level, pos);
        return SHAPE.move(offset.x, offset.y, offset.z);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        BlockState below = context.getLevel().getBlockState(pos.below());
        boolean water = context.getLevel().getFluidState(pos).getType() == Fluids.WATER;
        return defaultBlockState().setValue(PART, below.is(this) ? Part.TOP : Part.BOTTOM).setValue(WATERLOGGED, water);
    }

    /** 根元は塞がった上面の上、中・先は下のトクサ(先でない)の上。 */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState under = level.getBlockState(below);
        if (state.getValue(PART) == Part.BOTTOM) {
            return !under.is(this) && under.isFaceSturdy(level, below, Direction.UP);
        }
        return under.is(this) && under.getValue(PART) != Part.TOP;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        if (direction == Direction.DOWN && !state.canSurvive(level, pos)) {
            level.scheduleTick(pos, this, 1);
        }
        if (direction == Direction.UP) {
            Part part = state.getValue(PART);
            if (part == Part.MIDDLE && !neighbor.is(this)) {
                return state.setValue(PART, Part.TOP);
            }
            if (part == Part.TOP && neighbor.is(this)) {
                return state.setValue(PART, Part.MIDDLE);
            }
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return !state.getValue(WATERLOGGED);
    }
}
