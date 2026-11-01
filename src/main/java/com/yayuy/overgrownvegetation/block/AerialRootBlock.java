package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 気根のカーテン(S3、2026-11-01)。【設定】寄生植物の気根。林冠・腕の縁から垂れる赤茶まじりの暗いオリーブの根で、
 * 垂れ蔓と同じく中({@code tip=false})と先({@link #TIP})の 2 つの絵を縦に連ねる。{@link #KIND}: 束(3〜4 本)/ 1 本。
 * 地面に届いた先は根付いた柱({@link AerialRootPillarBlock})になる(置くのは {@code worldgen/TreeSideGrowth})。
 *
 * <p>支え: 真上が気根・真上の下面が塞がっている(葉も塞がる)。
 */
public class AerialRootBlock extends Block {

    public static final MapCodec<AerialRootBlock> CODEC = simpleCodec(AerialRootBlock::new);
    public static final BooleanProperty TIP = BooleanProperty.create("tip");
    public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);

    /** 束 a と 1 本 b。 */
    public enum Kind implements StringRepresentable {
        BUNDLE("bundle"), SINGLE("single");

        private final String name;

        Kind(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 16.0, 13.0);

    public AerialRootBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TIP, true).setValue(KIND, Kind.BUNDLE));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TIP, KIND);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState above = context.getLevel().getBlockState(context.getClickedPos().above());
        Kind kind = above.is(this) ? above.getValue(KIND) : Kind.BUNDLE;
        return defaultBlockState().setValue(KIND, kind).setValue(TIP, !continuesBelow(context.getLevel().getBlockState(context.getClickedPos().below())));
    }

    /** 下が気根か根付いた柱なら、ここは先ではない。 */
    private boolean continuesBelow(BlockState below) {
        return below.is(this) || below.is(ModBlocks.AERIAL_ROOT_PILLAR.get());
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos abovePos = pos.above();
        BlockState above = level.getBlockState(abovePos);
        return above.is(this) || above.isFaceSturdy(level, abovePos, Direction.DOWN);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.UP && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (direction == Direction.DOWN) {
            return state.setValue(TIP, !continuesBelow(neighborState));
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
