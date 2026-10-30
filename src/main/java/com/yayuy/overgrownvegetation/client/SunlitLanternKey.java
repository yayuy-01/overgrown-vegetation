package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.SunlitLanternTogglePayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * サバイバル用アイテムのキー(2026-10-16。操作設定の「Overgrown Vegetation」で変えられる): 腰に下げた陽溜まりのランタンを灯す・消す(既定 N)・
 * 背負った帆布の背嚢を開く(既定 B)。どちらも仮決め。押したらサーバーへ知らせるだけで、サーバーが枠を確かめてから動かす。
 */
public final class SunlitLanternKey {

    public static final KeyMapping TOGGLE = new KeyMapping("key.overgrown_vegetation.sunlit_lantern", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.overgrown_vegetation");
    /** 背負った帆布の背嚢を開く(2026-10-16、㊱。既定 B、仮決め)。 */
    public static final KeyMapping BACKPACK = new KeyMapping("key.overgrown_vegetation.backpack", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.overgrown_vegetation");

    private SunlitLanternKey() {
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE);
            event.register(BACKPACK);
        }

        private Registration() {
        }
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static final class Ticks {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            while (TOGGLE.consumeClick()) {
                if (mc.player != null && mc.getConnection() != null) {
                    PacketDistributor.sendToServer(new SunlitLanternTogglePayload());
                }
            }
            while (BACKPACK.consumeClick()) {
                if (mc.player != null && mc.getConnection() != null) {
                    PacketDistributor.sendToServer(new com.yayuy.overgrownvegetation.network.BackpackOpenPayload());
                }
            }
        }

        private Ticks() {
        }
    }
}
