package com.yayuy.overgrownvegetation.client.crawler;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.GregariaModel;
import com.yayuy.overgrownvegetation.entity.GregariaEntity;
import com.yayuy.overgrownvegetation.registry.ModCrawlers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** 「小さな地這いの虫」の型の Mob のクライアントの登録(2026-11-15)。共有の {@code OvergrownVegetationClient} は並行するセッションも触るので、ここに寄せた。 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CrawlerClient {

    private CrawlerClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModCrawlers.VESICARIA.get(),
                ctx -> new SmallCrawlerRenderer<com.yayuy.overgrownvegetation.entity.VesicariaEntity>(ctx,
                        new com.yayuy.overgrownvegetation.client.VesicariaModel(),
                        com.yayuy.overgrownvegetation.entity.VesicariaEntity.RENDER_SCALE, 0.5F));
        event.registerEntityRenderer(ModCrawlers.GREGARIA.get(),
                ctx -> new SmallCrawlerRenderer<GregariaEntity>(ctx, new GregariaModel(), GregariaEntity.RENDER_SCALE, 0.4F));
        event.registerEntityRenderer(ModCrawlers.STROBILA.get(), ctx -> {
            var r = new SmallCrawlerRenderer<com.yayuy.overgrownvegetation.entity.StrobilaEntity>(ctx,
                    new com.yayuy.overgrownvegetation.client.StrobilaModel(),
                    com.yayuy.overgrownvegetation.entity.StrobilaEntity.RENDER_SCALE, 0.9F);
            r.addRenderLayer(new com.yayuy.overgrownvegetation.client.StrobilaGlowLayer(r));   // 尾の背の光の筋(投げの前触れ)
            return r;
        });
        // ストロビラの球果(11-15 段 2): 絵は【仮】で胞子嚢のアイテム(少し大きく)
        event.registerEntityRenderer(ModCrawlers.STROBILA_CONE.get(),
                ctx -> new net.minecraft.client.renderer.entity.ThrownItemRenderer<>(ctx, 1.4F, false));
    }
}
