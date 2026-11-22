package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModPhyllosens;
import com.yayuy.overgrownvegetation.registry.ModStilt;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 竹馬型(11-14 から内部用)と監視塔フィロセンスのクライアントの登録。共有の {@code OvergrownVegetationClient} は並行するセッションも触るので、ここに寄せた。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class StiltClient {

    private StiltClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModStilt.PHYLLOSENS_STILT.get(), StiltPhyllosensRenderer::new);
        event.registerEntityRenderer(ModPhyllosens.PHYLLOSENS.get(), PhyllosensRenderer::new);
    }
}
