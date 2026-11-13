package com.yayuy.overgrownvegetation.survival.vent;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModLey;
import com.yayuy.overgrownvegetation.registry.ModVent;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * <b>地脈の換気機</b>(2026-11-14 サバイバルの背骨 段 2c)。設定: 鉱山や工房の粉塵・煙・坑内の悪い空気を吸うために、人々が昔から使ってきた
 * 地脈技術の換気の機械(決定 16)。胞子のために作られた物ではないが胞子にも効く。汚染が始まる前から作れる(決定 9)。
 * 働きは {@link VentBlockEntity}・{@link Ventilators}。
 *
 * <ul>
 *     <li>フィルターを持って右クリック: 入れる(入っていた物は手元へ)。素手でしゃがんで右クリック: 取り出す。</li>
 *     <li>置いたとき・素手で右クリック: 動いているか・フィルターの残り・中の FE・直近に抜いた量。</li>
 * </ul>
 * 見た目【仮】: 鋳鉄の台に真鍮の胴、上に送風の羽根と格子(動いている間は羽根がぼやける)。
 */
public class VentBlock extends BaseEntityBlock {

    public static final MapCodec<VentBlock> CODEC = simpleCodec(VentBlock::new);
    public static final BooleanProperty RUNNING = BooleanProperty.create("running");

    public VentBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(RUNNING, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(RUNNING);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VentBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModVent.VENTILATOR_BE.get(), VentBlockEntity::serverTick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.is(ModLey.WIRE.get())) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION; // 線の巻きの側で扱う
        }
        if (!AirFilters.isFilter(stack) || !(level.getBlockEntity(pos) instanceof VentBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (AirFilters.clogged(stack)) {
            if (!level.isClientSide) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.overgrown_vegetation.ley_ventilator.filter_clogged"), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            ItemStack old = be.filter();
            ItemStack in = stack.copyWithCount(1);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            be.setFilter(in);
            if (!old.isEmpty() && !player.getInventory().add(old)) {
                player.drop(old, false);
            }
            level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.BLOCKS, 0.8F, 1.1F);
            player.displayClientMessage(be.info(), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof VentBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            if (player.isShiftKeyDown() && !be.filter().isEmpty()) {
                ItemStack old = be.filter();
                be.setFilter(ItemStack.EMPTY);
                if (!player.getInventory().add(old)) {
                    player.drop(old, false);
                }
                level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.BLOCKS, 0.8F, 0.9F);
            }
            player.displayClientMessage(be.info(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof VentBlockEntity be) {
            player.displayClientMessage(be.info(), true);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof VentBlockEntity be && !be.filter().isEmpty()) {
            Containers.dropItemStack(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, be.filter());
            be.setFilter(ItemStack.EMPTY);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
