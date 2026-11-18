package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.function.Supplier;

/**
 * 段 3 の地脈の装甲の<b>胴</b>(2026-11-18)。装甲の結晶はここに 1 つだけあり、4 部位の能力({@link LeyArmorPowers})はここから払う。
 * バーは結晶の残り、防具の耐久はツールチップの 1 行。
 */
public class LeyChestItem extends TierArmorItem implements LeyCrystal.Powered {

    public LeyChestItem(Holder<ArmorMaterial> material, Supplier<GearTierTable.Armor> spec, Properties properties) {
        super(material, Type.CHESTPLATE, spec, properties);
    }

    @Override
    public int crystalCapacity() {
        return DesignData.gearTiers().crystal.armorCapacity;
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
