package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
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
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 水中で、汚染ブロックの上面から昆布のように縦へ伸びる汚染植物。
 *
 * <p>バニラの昆布のようなランダムティック成長は持たず、生成時に
 * {@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader} が数マス分をまとめて積む。
 * 各マスは常に水を含んだ状態として振る舞い({@link #getFluidState})、真下が同じブロック
 * または上面の塞がったブロックであれば生存する。崩れるときは水へ戻す。
 *
 * <p>見た目は {@link #TOP} で2種類に分かれる——柱の途中(中間)か、柱の一番上(頂上)か。
 * バニラの昆布と同じ考え方だが、こちらは向きを持たない1ブロックなので、真上のマスを見て
 * {@link #updateShape} で毎回決め直す(生成順に依存しない)。真上が同じ柱、または
 * {@link ModBlocks#OVERGROWN_WATER_COLUMN_FLOWER 花}であれば中間、それ以外なら頂上。
 * 花は柱の先端そのものなので、花の真下は「中間」になる。
 */
public class CorruptedWaterColumnBlock extends Block {

    public static final MapCodec<CorruptedWaterColumnBlock> CODEC = simpleCodec(CorruptedWaterColumnBlock::new);

    /** true = 柱の一番上(頂上のテクスチャ)。false = 柱の途中(中間のテクスチャ)。 */
    public static final BooleanProperty TOP = BooleanProperty.create("top");

    private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 16.0, 14.0);

    public CorruptedWaterColumnBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TOP, true));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TOP);
    }

    /** {@code above}(真上のマス)が柱の続き(柱本体か花)なら、このマスは中間になる。 */
    private boolean coveredBy(BlockState above) {
        return above.is(this) || above.is(ModBlocks.OVERGROWN_WATER_COLUMN_FLOWER.get());
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        return defaultBlockState()
                .setValue(TOP, !coveredBy(context.getLevel().getBlockState(pos.above())));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (!level.getFluidState(pos).is(FluidTags.WATER)) {
            return false;
        }
        BlockPos belowPos = pos.below();
        BlockState below = level.getBlockState(belowPos);
        return below.is(this) || below.isFaceSturdy(level, belowPos, Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!state.canSurvive(level, pos)) {
            return Blocks.WATER.defaultBlockState();
        }
        if (direction == Direction.UP) {
            state = state.setValue(TOP, !coveredBy(neighborState));
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return Fluids.WATER.getSource(false);
    }
}
