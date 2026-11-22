package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * ひげ地衣(S4、2026-11-01)。【現実】サルオガセの仲間。腕・枝の下面から垂れる灰緑の糸。{@link #KIND}: 細い / 房。
 * {@link #PART}: 1 段({@code short})/ 2 段の上({@code top})/ 2 段の下({@code tip})。2 段の絵は一続きに描いて上下に分けてある。
 * 支え: 1 段と 2 段の上は真上の下面が塞がっている、2 段の下は真上が同じ地衣の上の段。
 */
public class BeardLichenBlock extends Block {


    public enum Kind implements StringRepresentable {
        FINE("fine"), TUFT("tuft");

        private final String name;

        Kind(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public enum Part implements StringRepresentable {
        SHORT("short"), TOP("top"), TIP("tip");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 16.0, 14.0);

    public BeardLichenBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(KIND, Kind.FINE).setValue(PART, Part.SHORT));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(KIND, PART);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos abovePos = pos.above();
        BlockState above = level.getBlockState(abovePos);
        if (state.getValue(PART) == Part.TIP) {
            return above.is(this) && above.getValue(PART) == Part.TOP;
        }
        return above.isFaceSturdy(level, abovePos, Direction.DOWN);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.UP && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (direction == Direction.DOWN && state.getValue(PART) == Part.TOP
                && !(neighborState.is(this) && neighborState.getValue(PART) == Part.TIP)) {
            // 下の段が無くなったら 1 段に戻る(切れた糸の先)
            return state.setValue(PART, Part.SHORT);
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
