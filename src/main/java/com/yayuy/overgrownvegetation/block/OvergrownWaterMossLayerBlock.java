package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 「藻」。汚染された地形の水中の露出面(側面)を、薄い苔({@link OvergrownMossLayerBlock})と
 * まったく同じ多面ブロック({@link MultifaceBlock})として覆う。薄い苔の水中版。
 *
 * <p>薄い苔との違いは「常に水を含む(waterlogged)」ことだけ:
 * <ul>
 *     <li>崩れて面が 1 つも無くなったら空気ではなく水へ戻る({@link #updateShape})。</li>
 *     <li>水中に設置すると自動で waterlogged になる({@link MultifaceBlock#getStateForPlacement} が
 *         {@code WATERLOGGED} プロパティの有無を見て処理してくれる)。</li>
 * </ul>
 * バニラのヒカリゴケ({@link net.minecraft.world.level.block.GlowLichenBlock})の水没処理の写経。
 *
 * <p>汚染時の自動生成は {@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader}
 * の水中露出面抽選(側面)に組み込まれている({@code waterMossLayerChance})。
 */
public class OvergrownWaterMossLayerBlock extends MultifaceBlock implements BonemealableBlock, SimpleWaterloggedBlock {

    public static final MapCodec<OvergrownWaterMossLayerBlock> CODEC = simpleCodec(OvergrownWaterMossLayerBlock::new);
    private static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    private final MultifaceSpreader spreader = new MultifaceSpreader(this);

    public OvergrownWaterMossLayerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(WATERLOGGED, Boolean.FALSE));
    }

    @Override
    public MapCodec<OvergrownWaterMossLayerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(WATERLOGGED);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        BlockState updated = super.updateShape(state, direction, neighborState, level, pos, neighborPos);
        // 面が 1 つも無くなったら、薄い苔なら空気になるところを水へ戻す(気泡を残さない)。
        return updated.isAir() ? Fluids.WATER.defaultFluidState().createLegacyBlock() : updated;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getFluidState().isEmpty();
    }

    @Override
    public MultifaceSpreader getSpreader() {
        return this.spreader;
    }

    @Override
    public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state) {
        return Direction.stream()
                .anyMatch(direction -> this.spreader.canSpreadInAnyDirection(state, level, pos, direction.getOpposite()));
    }

    @Override
    public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state) {
        return true;
    }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        this.spreader.spreadFromRandomFaceTowardRandomDirection(state, level, pos, random);
    }
}
