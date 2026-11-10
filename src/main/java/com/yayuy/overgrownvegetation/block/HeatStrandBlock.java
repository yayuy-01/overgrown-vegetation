package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
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
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 熱水の森の、天井から何段も垂れる紐(2026-11-09 段階 1): 根のカーテン(細・太)・熱の光るツタ。
 * 上から 胴 × n・先 と連なる。いちばん上は天井の下面に、ほかは上の紐(先でない)にぶら下がる。
 * 支えが無くなったら次の tick に壊れる(上から順に落ちる)。{@link HorsetailBlock} を上下逆にしたもの。
 */
public class HeatStrandBlock extends Block {

    public static final MapCodec<HeatStrandBlock> CODEC = simpleCodec(HeatStrandBlock::new);
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 16.0, 13.0);

    public enum Part implements StringRepresentable {
        BODY("body"), TIP("tip");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public HeatStrandBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(PART, Part.TIP));
    }

    @Override
    protected MapCodec<? extends HeatStrandBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART);
    }

    /** {@code top}(天井のすぐ下)から下へ {@code length} 段を置く。下のマスが空いているかは呼び手が確かめておく。 */
    public static void placeStrand(LevelAccessor level, BlockPos top, int length, BlockState base) {
        for (int i = 0; i < length; i++) {
            Part part = i == length - 1 ? Part.TIP : Part.BODY;
            level.setBlock(top.below(i), base.setValue(PART, part), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState below = context.getLevel().getBlockState(context.getClickedPos().below());
        return defaultBlockState().setValue(PART, below.is(this) ? Part.BODY : Part.TIP);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos abovePos = pos.above();
        BlockState above = level.getBlockState(abovePos);
        if (above.is(this)) {
            return above.getValue(PART) == Part.BODY;
        }
        return above.isFaceSturdy(level, abovePos, Direction.DOWN);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.UP && !state.canSurvive(level, pos)) {
            level.scheduleTick(pos, this, 1);
        }
        if (direction == Direction.DOWN) {
            Part part = state.getValue(PART);
            if (part == Part.BODY && !neighbor.is(this)) {
                return state.setValue(PART, Part.TIP);
            }
            if (part == Part.TIP && neighbor.is(this)) {
                return state.setValue(PART, Part.BODY);
            }
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }
}
