package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModFlyers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** 「飛ぶ型」の Mob のクライアントの登録(2026-11-15)。共有の {@code OvergrownVegetationClient} は並行するセッションも触るので、ここに寄せた。 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FlyerClient {

    private FlyerClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModFlyers.VELOPHYLL.get(), VelophyllRenderer::new);
        // ヴェロフィルの胞子爆弾(11-15): 絵は【仮】で胞子嚢のアイテム(少し大きく)
        event.registerEntityRenderer(ModFlyers.VELOPHYLL_BOMB.get(),
                ctx -> new net.minecraft.client.renderer.entity.ThrownItemRenderer<>(ctx, 1.8F, false));
        event.registerEntityRenderer(ModFlyers.SEMINARIA.get(), SeminariaRenderer::new);
        event.registerEntityRenderer(ModFlyers.PEDICULA.get(), PediculaRenderer::new);
        event.registerEntityRenderer(ModFlyers.ACUMNES.get(), AcumnesRenderer::new);
        event.registerEntityRenderer(ModFlyers.ACUMNES_SPINE.get(), AcumnesSpineRenderer::new);
    }
}
