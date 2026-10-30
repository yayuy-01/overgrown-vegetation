package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.item.BatteryItem;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 地脈の汲み上げ器(2026-10-16、㉙。ユーザー指定「魔法の道具の燃料 = 地脈の雫を作る設備」「地脈が生まれる場所に近いほど効率が良い」)。
 * どこにでも置けて、置いたとき・素手で右クリックしたときに効率とたまった数を出す(地脈の線は極相の外では見えないので、これで探す)。
 * 素手で右クリックするとたまった雫を全部取り出す。中身は {@link LeyPumpBlockEntity}。
 * 2026-10-22: 地脈電池を持って右クリックで電池を入れる(入っている間は雫の代わりに充電)。しゃがんで素手の右クリックで取り出す。壊すと電池が落ちる。
 */
public class LeyPumpBlock extends BaseEntityBlock {

    public static final MapCodec<LeyPumpBlock> CODEC = simpleCodec(LeyPumpBlock::new);
    /** 雫が 1 つ以上たまっている(ガラスの中が光る)。 */
    public static final BooleanProperty FILLED = BooleanProperty.create("filled");
    private static final VoxelShape SHAPE = Block.box(2.0D, 0.0D, 2.0D, 14.0D, 15.0D, 14.0D);

    public LeyPumpBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FILLED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FILLED);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LeyPumpBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.LEY_PUMP.get(), LeyPumpBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof Player player && level.getBlockEntity(pos) instanceof LeyPumpBlockEntity pump) {
            report(player, pump);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof LeyPumpBlockEntity pump)) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel) {
            // しゃがんで素手 → 電池を取り出す(2026-10-22、地脈電池)。普通の素手 → 雫を取り出して様子を出す(電池は入れたまま)。
            if (player.isSecondaryUseActive() && pump.hasBattery()) {
                give(player, pump.takeBattery());
                level.playSound(null, pos, SoundEvents.COPPER_BREAK, SoundSource.BLOCKS, 0.8F, 1.3F);
                report(player, pump);
                return InteractionResult.SUCCESS;
            }
            int n = pump.takeAll();
            if (n > 0) {
                give(player, new ItemStack(ModItems.LEY_DROP.get(), n));
                level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 0.8F, 1.3F);
            }
            int samples = pump.takeSamples();
            if (samples > 0) {
                // 未知の試料(2026-10-24): 雫と一緒に出てくる
                give(player, new ItemStack(ModItems.UNKNOWN_SAMPLE.get(), samples));
            }
            report(player, pump);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** 地脈電池(満タンでも空でも)を持って右クリック → 1 個入れる。前に入っていた電池は手元に戻る(2026-10-22)。 */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!(stack.is(ModItems.BATTERY.get()) || stack.is(ModItems.EMPTY_BATTERY.get()))) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof LeyPumpBlockEntity pump)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level instanceof ServerLevel) {
            ItemStack back = pump.swapBattery(stack.copyWithCount(1));
            stack.consume(1, player);
            give(player, back);
            level.playSound(null, pos, SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, 1.0F, 1.3F);
            report(player, pump);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    /** 壊したら入っていた電池を落とす。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof LeyPumpBlockEntity pump) {
            ItemStack battery = pump.takeBattery();
            if (!battery.isEmpty()) {
                Block.popResource(level, pos, battery);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private static void give(Player player, ItemStack stack) {
        if (!stack.isEmpty() && !player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static void report(Player player, LeyPumpBlockEntity pump) {
        // 前兆の間(2026-10-24)は今の揺れた値と警告を出す
        boolean omen = com.yayuy.overgrownvegetation.phase.LeyOmen.isActive();
        int percent = (int) Math.round((omen ? pump.currentEfficiency() : pump.efficiency()) * 100.0D);
        ChatFormatting color = percent >= 60 ? ChatFormatting.AQUA : percent >= 25 ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY;
        Component msg = pump.hasBattery()
                ? Component.translatable("message.overgrown_vegetation.ley_pump.status_battery", percent,
                        pump.batteryCharge() / 1200, BatteryItem.CAPACITY / 1200, pump.stored(), LeyPumpBlockEntity.MAX_STORED)
                : Component.translatable("message.overgrown_vegetation.ley_pump.status", percent,
                        pump.stored(), LeyPumpBlockEntity.MAX_STORED);
        net.minecraft.network.chat.MutableComponent line = msg.copy().withStyle(color);
        if (omen) {
            line.append(Component.literal("  ")).append(Component.translatable("message.overgrown_vegetation.ley_pump.omen_warning")
                    .withStyle(ChatFormatting.GOLD));
        }
        player.displayClientMessage(line, true);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.ley_pump").withStyle(ChatFormatting.GRAY));
    }
}
