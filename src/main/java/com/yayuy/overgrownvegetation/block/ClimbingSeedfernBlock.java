package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 幹に巻き付く種子シダ(S1、2026-11-01)。【史実】リギノプテリスは茎の鉤状の毛と気根で他の木によじ登った。
 * 幹の面に貼り付く立体の模型(らせんの茎 + 気根のひげ + 小さな羽状の葉)で、面から 1/16 浮く。{@link #FACING} は葉の向く側(梯子と同じ。
 * 幹は {@code FACING} の反対側)。{@link #PART}: 上がり(右巻き)/ 下がり(左巻き)/ 先端(いちばん上の段)。
 * 茎は 1 面で 1 段上がるので、4 面とも同じ模型を回すだけで角を回って切れ目なく続く(下絵の仮決め)。
 */
public class ClimbingSeedfernBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<ClimbingSeedfernBlock> CODEC = simpleCodec(ClimbingSeedfernBlock::new);

    /** 巻き方と段。 */
    public enum Part implements StringRepresentable {
        UP("up"), DOWN("down"), TIP("tip");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.NORTH, Block.box(0.0, 0.0, 12.0, 16.0, 16.0, 16.0),
            Direction.SOUTH, Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 4.0),
            Direction.EAST, Block.box(0.0, 0.0, 0.0, 4.0, 16.0, 16.0),
            Direction.WEST, Block.box(12.0, 0.0, 0.0, 16.0, 16.0, 16.0));

    public ClimbingSeedfernBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.UP));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos wall = pos.relative(facing.getOpposite());
        return level.getBlockState(wall).isFaceSturdy(level, wall, facing);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        if (!face.getAxis().isHorizontal()) {
            return null;
        }
        BlockState state = defaultBlockState().setValue(FACING, face);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
