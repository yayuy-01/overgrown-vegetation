package com.yayuy.overgrownvegetation.survival.decon;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModDecon;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
 * <b>地脈の洗い場</b>(2026-11-14 サバイバルの背骨 段 3a、決定 30)。設定【仮】: 鉱山の坑口や工房で、坑夫や職人が仕事の終わりに道具と作業着の
 * 粉塵・油を洗い流してきた地脈技術の洗い場(換気の機械と同じ「昔からある機械」の系統)。地下水を地脈の力で汲み上げて吹き付けるので、水は要らない。
 * 汚染の前から作れる。玄関に置くのが拠点づくりの目標になる。働きは {@link WasherBlockEntity}。
 *
 * <ul>
 *     <li>洗える物(覆われた○○・汚れた装備・詰まったフィルター)を持って右クリック: 手の山ごと入れる。上に落としても吸い込む。ホッパーでもよい。</li>
 *     <li>素手でしゃがんで右クリック: 洗い上がりを全部取り出す(無ければ洗う前の物)。素手の右クリック・置いたとき: 状態の表示。</li>
 *     <li>上に立つと着ている装備を洗う(段 3b)。</li>
 * </ul>
 * 見た目【仮】: 鋳鉄の槽に真鍮の管、上面は格子(動いている間は水しぶきの絵)。
 */
public class WasherBlock extends BaseEntityBlock {

    public static final MapCodec<WasherBlock> CODEC = simpleCodec(WasherBlock::new);
    public static final BooleanProperty RUNNING = BooleanProperty.create("running");

    public WasherBlock(Properties properties) {
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
        return new WasherBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModDecon.WASHER_BE.get(), WasherBlockEntity::serverTick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.is(ModLey.WIRE.get())) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION; // 線の巻きの側で扱う
        }
        if (!(level.getBlockEntity(pos) instanceof WasherBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (Decon.kind(level, stack) == Decon.Kind.NONE) {
            if (Decon.filterWornOut(stack)) {
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.translatable("message.overgrown_vegetation.decon.filter_worn_out"), true);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            ItemStack rest = be.insert(stack.copy());
            if (!player.getAbilities().instabuild) {
                player.setItemInHand(hand, rest);
            }
            level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.5F, 1.2F);
            player.displayClientMessage(be.info(), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof WasherBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            if (player.isShiftKeyDown()) {
                for (ItemStack s : be.takeAll()) {
                    if (!player.getInventory().add(s)) {
                        player.drop(s, false);
                    }
                }
                level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
            }
            player.displayClientMessage(be.info(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof WasherBlockEntity be) {
            player.displayClientMessage(be.info(), true);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof WasherBlockEntity be) {
            for (int i = 0; i < be.items().getSlots(); i++) {
                ItemStack s = be.items().getStackInSlot(i);
                if (!s.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, s);
                    be.items().setStackInSlot(i, ItemStack.EMPTY);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
