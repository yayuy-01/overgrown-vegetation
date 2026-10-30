package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.ArmorAccessory;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 装飾品・拡張パーツが付いている防具の説明に「装飾品: 暗視の装飾品(暗い所が少し見える)」の行を足す(順 1 の着手順 9)。
 * バニラの防具にも付くので、アイテムのクラスではなくこのイベントで出す。属性で効くものは、
 * 「〜に装備したとき」の行にも数値が出る({@code ArmorAccessoryEffects})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ArmorAccessoryTooltip {

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof ArmorItem)) {
            return;
        }
        for (ArmorAccessory.Kind kind : ArmorAccessory.Kind.values()) {
            ArmorAccessory a = ArmorAccessory.attached(stack, kind);
            if (a != null) {
                event.getToolTip().add(Component.translatable("tooltip.overgrown_vegetation.accessory.attached",
                        Component.translatable(kind.nameKey()), a.item().getDescription(), a.effect())
                        .withStyle(ChatFormatting.DARK_AQUA));
            }
        }
    }

    private ArmorAccessoryTooltip() {
    }
}
