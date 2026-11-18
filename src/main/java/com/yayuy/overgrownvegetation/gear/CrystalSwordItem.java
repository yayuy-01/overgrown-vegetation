package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** 段 3 の刃の共通(結晶を持つ剣。2026-11-18)。耐久のバーは結晶の残り(黄緑)を出し、刃そのものの耐久はツールチップの 1 行。 */
public abstract class CrystalSwordItem extends SwordItem implements LeyCrystal.Powered {

    protected CrystalSwordItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    @Override
    public int crystalCapacity() {
        return DesignData.gearTiers().crystal.weaponCapacity;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return LeyCrystal.barWidth(stack);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return LeyCrystal.BAR_COLOR;
    }

    @Override
    public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
        return slot.allowModification(player) && LeyCrystal.onStackedOn(stack, other, action, player);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(getDescriptionId() + ".desc").withStyle(net.minecraft.ChatFormatting.GRAY));
        LeyCrystal.tooltip(stack, tooltip);
        if (stack.isDamageableItem()) {
            tooltip.add(Component.translatable("item.overgrown_vegetation.tier_helmet.durability",
                    stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage()).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
    }
}
