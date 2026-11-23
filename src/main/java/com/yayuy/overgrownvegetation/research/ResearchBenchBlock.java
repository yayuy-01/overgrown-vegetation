package com.yayuy.overgrownvegetation.research;

import net.minecraft.world.InteractionHand;
import com.yayuy.overgrownvegetation.registry.ModResearch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * <b>研究台</b>(2026-11-17 決定 20・22・23)。右クリックで研究ツリーの画面を開く。壊すと、ここで走っていた研究をやめ、標本を落とす。
 */
public class ResearchBenchBlock extends BaseEntityBlock {


    public ResearchBenchBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ResearchBenchBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModResearch.BENCH_BE.get(), ResearchBenchBlockEntity::serverTick);
    }

    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            if (!ResearchGate.on(level)) { // 実験的要素の「研究」がオフ(11-23 夜)
                sp.displayClientMessage(net.minecraft.network.chat.Component.translatable("research.overgrown_vegetation.experimental_off"), true);
                return InteractionResult.CONSUME;
            }
            Research.openScreen(sp, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server) {
            ResearchData.Active a = ResearchData.get(server.getServer()).active();
            if (a != null && a.bench.equals(GlobalPos.of(server.dimension(), pos))) {
                Research.cancel(server.getServer(), null);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** 1.20.1: 1.21 の useItemOn → useWithoutItem(主の手だけ)の順を use 1 つで呼ぶ。useItemOn の null = 1.21 の PASS_TO_DEFAULT_BLOCK_INTERACTION。 */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        return hand == InteractionHand.MAIN_HAND ? useWithoutItem(state, level, pos, player, hitResult) : InteractionResult.PASS;
    }
}
