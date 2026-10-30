package com.yayuy.overgrownvegetation.proto;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** 粘菌の絨毯の試作の描画を登録する(クライアントだけ)。 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MoldCarpetProtoClient {

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(MoldCarpetProto.TYPE, MoldNetRenderer::new);
    }

    private MoldCarpetProtoClient() {
    }
}
