package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModLeapers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** 「跳ぶ型」の Mob のクライアントの登録(2026-11-14)。共有の {@code OvergrownVegetationClient} は並行するセッションも触るので、ここに寄せた。 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LeaperClient {

    private LeaperClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModLeapers.SALTIMNES.get(), SaltimnesRenderer::new);
        // ファランギオタルブス(G3、11-16 段 1): 小さな地這いの虫の型の描画(段のならし・前後の傾き)。描画倍率 1(geo に焼き込み済み)
        event.registerEntityRenderer(ModLeapers.PHALANGIOTARBUS.get(),
                ctx -> new com.yayuy.overgrownvegetation.client.crawler.SmallCrawlerRenderer<com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity>(ctx,
                        new com.yayuy.overgrownvegetation.client.crawler.CritterModel<>(com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity.ID), 1.0F, 0.8F));
    }
}
