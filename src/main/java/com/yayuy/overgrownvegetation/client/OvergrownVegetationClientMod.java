package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.difficulty.VisualSettingsScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * クライアントだけの MOD の入口(2026-11-01)。Mods 画面のこの MOD の「設定」ボタンで演出の詳細設定({@link VisualSettingsScreen})を開く。
 * もう 1 つの入口はバニラの「設定…」画面のボタン({@code client/difficulty/VisualSettingsEntry})。
 *
 * <p>1.20.1: Forge は 1 つの MOD に {@code @Mod} のクラスを 1 つしか持てない(本流の {@code @Mod(dist = CLIENT)} の 2 つ目の入口は無い)。
 * クライアントの準備の知らせで、この MOD の入れ物に設定の画面の作り方({@code ConfigScreenHandler.ConfigScreenFactory})を入れる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OvergrownVegetationClientMod {

    private OvergrownVegetationClientMod() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ModList.get().getModContainerById(OvergrownVegetation.MODID).ifPresent(container ->
                container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new VisualSettingsScreen(parent))));
    }
}
