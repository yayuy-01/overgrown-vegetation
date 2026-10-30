package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.entity.StickyNet;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 粘りの投網(2026-10-16、⑳)。雪玉のように投げる。 */
public class StickyNetItem extends Item {

    public StickyNetItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL, 0.5F, 0.6F);
        if (!level.isClientSide()) {
            StickyNet net = new StickyNet(level, player);
            net.setItem(stack.copyWithCount(1));
            net.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.2F, 1.0F);
            level.addFreshEntity(net);
        }
        player.getCooldowns().addCooldown(this, 20);
        stack.consume(1, player);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
