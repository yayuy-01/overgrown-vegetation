package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.spore.GearContamination;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 汚染された装備のツールチップに「胞子汚染: 42%」の 1 行を足す。
 * 値は {@code spore_contamination} コンポーネント(クライアントへ同期済み)から読む。
 *
 * <p>鍛冶台で樹液結晶を打ち込んだ装備には「樹液結晶処理済み」の 1 行も足す。
 * こちらは汚染度 0% でも出す(処理したことが買う前から分かるように)。
 *
 * <p>どちらも<b>状態だけ</b>を淡々と書く。汚染の進みが何分の1になるか、性能が何%落ちるか、
 * 過繫茂からの被ダメージが何%減るかといった効果の解説は載せない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class GearContaminationTooltip {

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (GearContamination.isSapCrystalTreated(event.getItemStack())) {
            event.getToolTip().add(Component.translatable("tooltip.overgrown_vegetation.sap_crystal_treated")
                    .withStyle(ChatFormatting.AQUA));
        }
        float percent = GearContamination.percent(event.getItemStack());
        if (percent <= 0.0F) {
            return;
        }
        ChatFormatting color = percent >= 75.0F ? ChatFormatting.RED
                : percent >= 25.0F ? ChatFormatting.GOLD
                : ChatFormatting.YELLOW;
        event.getToolTip().add(Component.translatable(
                "tooltip.overgrown_vegetation.spore_contamination",
                String.format("%.0f", percent)).withStyle(color));
    }

    private GearContaminationTooltip() {
    }
}
