package com.yayuy.overgrownvegetation.gear.attach;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;

/**
 * 付け物の働きのうち、イベントで足すもの(2026-11-21 夜【仮】)。
 * <ul>
 *   <li>樹皮の当て板: 付けた部位の防御 +{@code armor}(1 枚ごと)。スタックの {@code ATTRIBUTE_MODIFIERS} には書かない(書くと防具自身の防御点を置き換える。10-03 の装飾品と同じ)。
 *   修飾は防具 1 つにつき 1 つに合算し、id に部位を入れる(同じ id が 2 つ付くと例外)。</li>
 *   <li>口覆いの布の付いた頭の防具: 持ち物の画面でフィルターを上で右クリックすると入れ替わる(防護マスクと同じ)。</li>
 *   <li>防具の説明に、付いている付け物とフィルターの残りを出す。</li>
 * </ul>
 * 導晶装飾の布(地脈の力の減り)は払う側から {@link ArmorAttachments#leyCostFactor} を読む。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class AttachEffects {

    private AttachEffects() {
    }

    @SubscribeEvent
    public static void onItemAttributes(ItemAttributeModifierEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof ArmorItem armor)) return;
        double bonus = ArmorAttachments.armorBonus(stack);
        String part = ArmorAttachments.partOf(stack);
        if (bonus == 0.0D || part == null) return;
        event.addModifier(Attributes.ARMOR,
                new AttributeModifier(OvergrownVegetation.id("armor_attachment." + part), bonus, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.bySlot(armor.getEquipmentSlot()));
    }

    @SubscribeEvent
    public static void onStackedOn(ItemStackedOnOtherEvent event) {
        ItemStack target = event.getStackedOnItem();
        ItemStack carried = event.getCarriedItem();
        if (event.getClickAction() != ClickAction.SECONDARY || target.getItem() instanceof com.yayuy.overgrownvegetation.gear.FilterMask) return;
        if (!ArmorAttachments.hasMouthCloth(target) || !AirFilters.isFilter(carried) || AirFilters.clogged(carried)) return;
        if (!event.getSlot().allowModification(event.getPlayer())) return;
        ProtectiveMaskItem.swapFilter(target, carried, event.getPlayer(), event.getCarriedSlotAccess());
        event.getPlayer().playSound(net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.8F, 1.2F);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!ArmorAttachments.canHold(stack)) return;
        List<Component> tip = event.getToolTip();
        List<ItemStack> list = ArmorAttachments.list(stack);
        int used = (int) list.stream().filter(s -> !s.isEmpty()).count();
        if (used == 0) return; // 何も付いていない防具には出さない
        int at = Math.min(tip.size(), 1);
        java.util.ArrayList<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.translatable("armor_attachment.overgrown_vegetation.slots", used, ArmorAttachments.slotCount()).withStyle(ChatFormatting.DARK_GRAY));
        for (ItemStack s : list) {
            if (!s.isEmpty()) lines.add(Component.literal(" ・").append(s.getHoverName()).withStyle(ChatFormatting.GRAY));
        }
        if (ArmorAttachments.hasMouthCloth(stack)) {
            MouthClothItem.filterLine(stack, lines);
            lines.add(Component.translatable("item.overgrown_vegetation.protective_mask.swap_hint").withStyle(ChatFormatting.DARK_GRAY));
        }
        tip.addAll(at, lines);
    }
}
