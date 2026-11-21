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
        event.registerEntityRenderer(ModFlyers.SEMINARIA.get(), ctx -> {
            var r = new SeminariaRenderer(ctx);
            r.addRenderLayer(new MaskGlowLayer<>(r, MaskGlowLayer.mask("seminaria_glowmask"), com.yayuy.overgrownvegetation.entity.SeminariaEntity::sowGlow));   // 種を落とす間だけ光る筋(11-16)
            return r;
        });
        // セミナリアの種(11-16): 絵は【仮】で胞子嚢のアイテム(小さく)
        event.registerEntityRenderer(ModFlyers.SEMINARIA_SEED.get(),
                ctx -> new net.minecraft.client.renderer.entity.ThrownItemRenderer<>(ctx, 0.6F, false));
        event.registerEntityRenderer(ModFlyers.STENODICTYA_LOBATA.get(), StenodictyaLobataRenderer::new);   // 獲物の環境生物の成虫(11-16)
        event.registerEntityRenderer(ModFlyers.PEDICULA.get(), PediculaRenderer::new);
        event.registerEntityRenderer(ModFlyers.ACUMNES.get(), AcumnesRenderer::new);
        event.registerEntityRenderer(ModFlyers.ACUMNES_SPINE.get(), SpineRenderer::acumnes);
    }
}
