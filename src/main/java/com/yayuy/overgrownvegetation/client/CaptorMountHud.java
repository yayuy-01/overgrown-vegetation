package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.CaptorMount;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;

/**
 * 捕まえて乗せる Mob({@link CaptorMount})に捕まっている間、乗り物の体力の欄と「左 Shift で降りる」の案内を描かない(2026-11-16、
 * 全体管理経由のユーザー決定「降りられないのに案内が出るのはまぎらわしい」)。案内はバニラが乗った瞬間に画面の下の一行(overlay message)へ出すので、
 * 乗っている間はその一行を描かない(ほかの知らせもその間は出ない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CaptorMountHud {

    private CaptorMountHud() {
    }

    @SubscribeEvent
    public static void onLayer(RenderGuiOverlayEvent.Pre event) {
        ResourceLocation name = event.getOverlay().id();
        // 1.20.1: 乗り物の体力 = MOUNT_HEALTH、下の一行(overlay message)= RECORD_OVERLAY
        if (!name.equals(VanillaGuiOverlay.MOUNT_HEALTH.id()) && !name.equals(VanillaGuiOverlay.RECORD_OVERLAY.id())) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getVehicle() instanceof CaptorMount) event.setCanceled(true);
    }
}
