package com.yayuy.overgrownvegetation.survival.expedition;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.survival.FatigueTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * <b>駆胞薬</b>(2026-11-16 サバイバルの背骨 段 4、決定 38)。焼いて死なせた胞子の殻を飲み、体に胞子と戦う力を起こす
 * (種痘のような 18〜19 世紀の医術)。胞子喰い茸か休眠嚢を混ぜて効かせる。
 *
 * <ul>
 *     <li>1 本で感染が 1 段下がる。どの段で飲んでも同じ(11-16 夜ユーザー: 薬は 1 種類)。段の進みは 0 に戻る。</li>
 *     <li>段 1 で飲むと治る。暴露量も 0 にして、すぐまたかからないようにする。</li>
 *     <li>飲むと疲労が {@code expedition.cureFatigue}(+10%【仮】、ユーザー指定)たまる。続けて何本も飲みにくい。</li>
 *     <li>感染していなければ飲めない(無駄に減らない)。</li>
 * </ul>
 */
public class CureDraughtItem extends Item {

    private static final int DRINK_TICKS = 32;

    public CureDraughtItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (SporeInfectionEffects.stageOf(player) <= 0) {
            if (!level.isClientSide()) {
                player.displayClientMessage(Component.translatable("item.overgrown_vegetation.cure_draught.not_infected"), true);
            }
            return InteractionResultHolder.fail(stack);
        }
        return ItemUtils.startUsingInstantly(level, player, hand);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide() && entity instanceof Player player) {
            apply(player);
            if (player instanceof ServerPlayer sp) sp.awardStat(Stats.ITEM_USED.get(this));
        }
        if (entity instanceof Player player && player.hasInfiniteMaterials()) return stack;
        stack.shrink(1);
        if (stack.isEmpty()) return new ItemStack(Items.GLASS_BOTTLE);
        if (entity instanceof Player player && !player.getInventory().add(new ItemStack(Items.GLASS_BOTTLE))) {
            player.drop(new ItemStack(Items.GLASS_BOTTLE), false);
        }
        return stack;
    }

    /** 1 本ぶん効かせる(GameTest からも呼ぶ)。効いたら true(感染していなければ何もしない)。 */
    public static boolean apply(Player player) {
        int stage = SporeInfectionEffects.stageOf(player);
        if (stage <= 0) return false;
        SporeInfectionEffects.setPlayerStage(player, stage - 1);
        if (stage - 1 <= 0) {
            SporeExposureTracker.setDose(player, 0.0D, player.level().getGameTime());
        }
        double cost = DesignData.sporeField().expedition().cureFatigue();
        if (cost > 0.0D) FatigueTracker.set(player, FatigueTracker.value(player) + cost);
        player.level().playSound(null, player.blockPosition(), SoundEvents.GENERIC_DRINK, player.getSoundSource(), 0.6F, 0.9F);
        return true;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return DRINK_TICKS;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.DRINK;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.cure_draught.desc").withStyle(ChatFormatting.GRAY));
    }
}
