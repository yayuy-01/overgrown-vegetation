package com.yayuy.overgrownvegetation.client.attach;

import com.mojang.blaze3d.platform.InputConstants;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.gear.attach.AttachNetwork;
import com.yayuy.overgrownvegetation.registry.ModArmorAttach;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.ClientTickEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import com.yayuy.overgrownvegetation.port.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 防具の付け物のクライアント側(2026-11-21 夜): 台とベルトの画面・ベルトを開くキー(既定 B【仮】)・体に付け物を描く層。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AttachClient {

    public static final KeyMapping OPEN_BELT = new KeyMapping("key.overgrown_vegetation.belt_pouch",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.overgrown_vegetation");

    private AttachClient() {
    }

    @SubscribeEvent
    public static void onScreens(FMLClientSetupEvent event) {
        // 1.20.1: RegisterMenuScreensEvent が無い。準備の知らせで MenuScreens に入れる(本スレッドで)
        event.enqueueWork(() -> {
            net.minecraft.client.gui.screens.MenuScreens.register(ModArmorAttach.BENCH_MENU.get(), AttachBenchScreen::new);
            net.minecraft.client.gui.screens.MenuScreens.register(ModArmorAttach.BELT_MENU.get(), BeltPouchScreen::new);
        });
    }

    @SubscribeEvent
    public static void onKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_BELT);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (String skin : event.getSkins()) { // 1.20.1: スキンの形は文字列
            EntityRenderer<?> r = event.getSkin(skin);
            if (r instanceof LivingEntityRenderer lr) lr.addLayer(new AttachmentLayer(lr));
        }
        EntityRenderer<?> stand = event.getRenderer(EntityType.ARMOR_STAND);
        if (stand instanceof LivingEntityRenderer lr) lr.addLayer(new AttachmentLayer(lr));
    }

    /** キーを押したらサーバーへ知らせる(ゲームのバス)。 */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class Keys {
        private Keys() {
        }

        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            while (OPEN_BELT.consumeClick()) {
                if (mc.player != null && mc.screen == null) PacketDistributor.sendToServer(AttachNetwork.OpenBelt.INSTANCE);
            }
        }
    }
}
