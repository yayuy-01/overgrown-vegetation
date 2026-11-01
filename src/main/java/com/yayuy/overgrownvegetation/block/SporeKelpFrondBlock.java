package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

/**
 * 胞子昆布の葉(O2、2026-11-01)。封印木の向き付きの板 {@link SigillariaFringeBlock} の仕組み({@link #FACING} = 付け根が接する芯の向き)のまま、
 * 絵を帯の葉に替え、水中専用にしたもの。横 = 柱の側面から開く帯、上 = 腕の下に垂れる帯(下にもう 1 枚垂れることがある)。
 *
 * <p>封印木の板と違い支えを持つ: 付け根の向きに柱(垂れる葉は柱か上の垂れる葉)が無くなると崩れて水に戻る。
 * {@link OvergrownLeavesBlock} を継ぐので、木の一部としての判定({@code instanceof})にも乗る(海の中なので木と重ならない)。
 */
public class SporeKelpFrondBlock extends SigillariaFringeBlock implements LiquidBlockContainer {

    public static final MapCodec<SporeKelpFrondBlock> CODEC = simpleCodec(SporeKelpFrondBlock::new);

    public SporeKelpFrondBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends SigillariaFringeBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        return fluid.is(Fluids.WATER) && fluid.isSource() ? super.getStateForPlacement(context) : null;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockState core = level.getBlockState(pos.relative(facing));
        if (core.getBlock() instanceof SporeKelpStalkBlock) {
            return true;
        }
        return facing == Direction.UP && core.is(this) && core.getValue(FACING) == Direction.UP;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
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

    @Override
    protected FluidState getFluidState(BlockState state) {
        return Fluids.WATER.getSource(false);
    }

    @Override
    public boolean canPlaceLiquid(@Nullable Player player, BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluid) {
        return false;
    }
}
