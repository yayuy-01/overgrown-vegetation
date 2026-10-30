package com.yayuy.overgrownvegetation.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.BiFunction;

/**
 * 雪玉のように投げる道具(2026-10-16、⑧ 焼夷瓶・⑪ 灰煙玉)。投げると {@code factory} の飛び物が出る。1 秒の間を空ける。
 */
public class ThrowableGearItem extends Item {

    private final BiFunction<Level, Player, ThrowableItemProjectile> factory;
    private final String tooltipKey;

    public ThrowableGearItem(Properties properties, BiFunction<Level, Player, ThrowableItemProjectile> factory, String tooltipKey) {
        super(properties);
        this.factory = factory;
        this.tooltipKey = tooltipKey;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SPLASH_POTION_THROW, SoundSource.PLAYERS, 0.6F, 0.8F);
        if (!level.isClientSide()) {
            ThrowableItemProjectile projectile = factory.apply(level, player);
            projectile.setItem(stack.copyWithCount(1));
            projectile.shootFromRotation(player, player.getXRot(), player.getYRot(), -10.0F, 0.9F, 1.0F);
            level.addFreshEntity(projectile);
        }
        player.getCooldowns().addCooldown(this, 20);
        stack.consume(1, player);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(tooltipKey).withStyle(ChatFormatting.GRAY));
    }
}
