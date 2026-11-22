package com.yayuy.overgrownvegetation.corruption.pushback.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModPushBack;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** 押し返す用の道具のクライアントの登録(2026-11-18 夜)。共有の {@code OvergrownVegetationClient} に触らないようここに寄せた。 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PushBackClient {

    private PushBackClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModPushBack.WITHER_DRAUGHT_ENTITY.get(), ctx -> new ThrownItemRenderer<>(ctx, 1.0F, false));
    }
}
