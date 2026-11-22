package com.yayuy.overgrownvegetation.ley;

import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * <b>地脈の汲み上げ器</b>(2026-11-13 サバイバルの背骨 段 2b)。地面から地脈を吸い上げて FE にする。働きは {@link LeyPumpBlockEntity}。
 * <ul>
 *     <li>置いたとき・素手で右クリック: 効率(線の真上で 100%)・線までの距離・汲む速さ・中の量を出す(決定 20「置いたとき・右クリックで効率が分かる」)。</li>
 *     <li>電池を持って右クリック: 電池を差す。素手でしゃがんで右クリック: 抜く。</li>
 * </ul>
 * 見た目【仮】: 鋳鉄の胴に真鍮の管と目盛り。電池を差すと胴の窓に電池が見える。
 */
public class LeyPumpBlock extends BaseEntityBlock {


    public LeyPumpBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LeyBatteryHolder.BATTERY, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LeyBatteryHolder.BATTERY);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LeyPumpBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModLey.PUMP_BE.get(), LeyPumpBlockEntity::serverTick);
    }

    @Nullable
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.is(ModLey.WIRE.get())) {
            return InteractionResult.PASS; // 線の巻きの側で扱う
        }
        if (level.getBlockEntity(pos) instanceof LeyPumpBlockEntity be && LeyBatteryHolder.insert(level, pos, state, player, stack, be)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return null;
    }

    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof LeyPumpBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown() && LeyBatteryHolder.takeOut(level, pos, state, player, be)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            player.displayClientMessage(be.info(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof LeyPumpBlockEntity be) {
            player.displayClientMessage(be.info(), true);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof LeyPumpBlockEntity be) {
            LeyBatteryHolder.drop(level, pos, be);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** 1.20.1: 1.21 の useItemOn → useWithoutItem(主の手だけ)の順を use 1 つで呼ぶ。useItemOn の null = 1.21 の PASS_TO_DEFAULT_BLOCK_INTERACTION。 */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        InteractionResult r = useItemOn(player.getItemInHand(hand), state, level, pos, player, hand, hitResult);
        if (r != null) {
            return r;
        }
        return hand == InteractionHand.MAIN_HAND ? useWithoutItem(state, level, pos, player, hitResult) : InteractionResult.PASS;
    }
}
