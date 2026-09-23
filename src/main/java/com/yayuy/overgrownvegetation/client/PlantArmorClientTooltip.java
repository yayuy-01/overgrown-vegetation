package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.PlantArmor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 「寄生植物装備」のツールチップのうち、クライアントの自分のデータ(装着時間)を見る部分。
 * {@link com.yayuy.overgrownvegetation.item.PlantArmorItem#appendHoverText} からクライアント側でだけ呼ばれる
 * (専用サーバーで {@link Minecraft} を参照しないよう別クラスに分けてある)。
 * 装着時間はサーバーから毎秒届く値({@link ClientPlantArmor})。
 */
public final class PlantArmorClientTooltip {

    private PlantArmorClientTooltip() {
    }

    /** 自分が着けている装備なら「次の段階まで mm:ss(N%)」、最終段階なら「装着 h:mm:ss」を足す。 */
    public static void appendNextStage(ItemStack stack, PlantArmor.Stage stage, List<Component> tooltip) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || PlantArmor.worn(mc.player) != stack || ClientPlantArmor.ticks() < 0) {
            return;
        }
        if (stage == PlantArmor.Stage.THREE) {
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.plant_armor.final",
                    ClientPlantArmor.formatTicks(ClientPlantArmor.ticks())).withStyle(ChatFormatting.DARK_GREEN));
            return;
        }
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.plant_armor.next",
                ClientPlantArmor.remainingText(stage), Math.round(ClientPlantArmor.progress(stage) * 100.0F))
                .withStyle(ChatFormatting.DARK_GREEN));
    }
}
