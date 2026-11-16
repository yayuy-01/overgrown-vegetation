package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModLizards;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** 「トカゲ型」の Mob のクライアントの登録(2026-11-15)。共有の {@code OvergrownVegetationClient} は並行するセッションも触るので、ここに寄せた。 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LizardClient {

    private LizardClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModLizards.PILIMNES.get(), PilimnesRenderer::new);
        event.registerEntityRenderer(ModLizards.PILIMNES_SPINE.get(), SpineRenderer::pilimnes);
    }
}
