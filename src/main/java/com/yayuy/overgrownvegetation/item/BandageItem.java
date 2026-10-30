package com.yayuy.overgrownvegetation.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 包帯(2026-10-16、㉒)。繊維草の繊維で作る。巻くとしばらく少しずつ回復する(仮決め: 1.5 秒かけて巻き、再生 I を 15 秒 = 体力 6 ほど)。
 */
public class BandageItem extends Item {

    public static final int USE_TICKS = 30;
    public static final int REGEN_TICKS = 300;

    public BandageItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_TICKS;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BRUSH;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return ItemUtils.startUsingInstantly(level, player, hand);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide()) {
            apply(entity);
            level.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 1.0F, 1.2F);
        }
        if (!(entity instanceof Player p) || !p.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        return stack;
    }

    public static void apply(LivingEntity entity) {
        entity.addEffect(new MobEffectInstance(MobEffects.REGENERATION, REGEN_TICKS, 0));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.bandage").withStyle(ChatFormatting.GRAY));
    }
}
