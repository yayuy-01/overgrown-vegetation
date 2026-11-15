package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.CaptorMount;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 捕まえて乗せる Mob({@link CaptorMount})に捕まっている間、乗り物の体力の欄と「左 Shift で降りる」の案内を描かない(2026-11-16、
 * 全体管理経由のユーザー決定「降りられないのに案内が出るのはまぎらわしい」)。案内はバニラが乗った瞬間に画面の下の一行(overlay message)へ出すので、
 * 乗っている間はその一行を描かない(ほかの知らせもその間は出ない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CaptorMountHud {

    private CaptorMountHud() {
    }

    @SubscribeEvent
    public static void onLayer(RenderGuiLayerEvent.Pre event) {
        ResourceLocation name = event.getName();
        if (!name.equals(VanillaGuiLayers.VEHICLE_HEALTH) && !name.equals(VanillaGuiLayers.OVERLAY_MESSAGE)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getVehicle() instanceof CaptorMount) event.setCanceled(true);
    }
}
