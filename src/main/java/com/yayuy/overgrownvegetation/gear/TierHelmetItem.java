package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import net.minecraft.ChatFormatting;
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
 * 段の防具の頭(2026-11-17 決定 11「頭はマスクと一体で、フィルターを使う」)。段 1 = 遠征服の頭巾、段 2 = 札鎧の兜。
 * フィルターの扱い(残り・入れ替え・除染)は防護マスクと同じ({@link FilterMask}・{@link AirFilters})。減りは {@code filterWear} 倍【仮】。
 *
 * <p>耐久のバーはフィルターの残りを出す(マスクと同じ見え方)。防具としての耐久はツールチップの 1 行で出す【仮】。
 */
public class TierHelmetItem extends TierArmorItem implements FilterMask {

    public TierHelmetItem(Holder<ArmorMaterial> material, Supplier<GearTierTable.Armor> spec, Properties properties) {
        super(material, Type.HELMET, spec, properties);
    }

    @Override
    public double filterWear() {
        return spec().filterWear;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return AirFilters.barVisible(stack) || stack.isDamaged();
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return AirFilters.barVisible(stack) ? AirFilters.barWidth(stack) : super.getBarWidth(stack);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return AirFilters.barVisible(stack) ? AirFilters.barColor(stack) : super.getBarColor(stack);
    }

    /** 持ち物の画面でフィルターをこの頭の上で右クリックすると入れ替わる(防護マスクと同じ)。 */
    @Override
    public boolean overrideOtherStackedOnMe(ItemStack head, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
        if (action != ClickAction.SECONDARY || !AirFilters.isFilter(other) || AirFilters.clogged(other) || !slot.allowModification(player)) {
            return false;
        }
        ProtectiveMaskItem.swapFilter(head, other, player, access);
        player.playSound(net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.8F, 1.2F);
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (AirFilters.clogged(stack)) {
            tooltip.add(Component.translatable("item.overgrown_vegetation.protective_mask.spent").withStyle(ChatFormatting.RED));
        } else {
            tooltip.add(Component.translatable("item.overgrown_vegetation.protective_mask.filter", AirFilters.percentLeft(stack))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (stack.isDamageableItem()) {
            tooltip.add(Component.translatable("item.overgrown_vegetation.tier_helmet.durability",
                    stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage()).withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.translatable("item.overgrown_vegetation.protective_mask.swap_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
