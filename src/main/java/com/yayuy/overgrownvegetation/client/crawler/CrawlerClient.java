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
        // 袋の脈の光・死んだ後に赤くしない(11-15 段 2)
        event.registerEntityRenderer(ModCrawlers.VESICARIA.get(), com.yayuy.overgrownvegetation.client.VesicariaRenderer::new);
        event.registerEntityRenderer(ModCrawlers.GREGARIA.get(),
                ctx -> new SmallCrawlerRenderer<GregariaEntity>(ctx, new GregariaModel(), GregariaEntity.RENDER_SCALE, 0.4F));
        event.registerEntityRenderer(ModCrawlers.STROBILA.get(), ctx -> {
            var r = new SmallCrawlerRenderer<com.yayuy.overgrownvegetation.entity.StrobilaEntity>(ctx,
                    new com.yayuy.overgrownvegetation.client.StrobilaModel(),
                    com.yayuy.overgrownvegetation.entity.StrobilaEntity.RENDER_SCALE, 0.9F);
            r.addRenderLayer(new com.yayuy.overgrownvegetation.client.MaskGlowLayer<>(r, com.yayuy.overgrownvegetation.client.MaskGlowLayer.mask("strobila_glowmask"), com.yayuy.overgrownvegetation.entity.StrobilaEntity::tailGlow));   // 尾の背の光の筋(投げの前触れ)
            return r;
        });
        // G2・G3 の作り直し 段 1(11-16): 描画倍率 1(geo に焼き込み済み)
        event.registerEntityRenderer(ModCrawlers.PULMONOSCORPIUS.get(),
                ctx -> new SmallCrawlerRenderer<com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity>(ctx, new CritterModel<>(com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity.ID), 1.0F, 0.6F));
        event.registerEntityRenderer(ModCrawlers.PROTOPHASMA.get(),
                ctx -> new SmallCrawlerRenderer<com.yayuy.overgrownvegetation.entity.ProtophasmaEntity>(ctx, new CritterModel<>(com.yayuy.overgrownvegetation.entity.ProtophasmaEntity.ID), 1.0F, 0.35F));
        event.registerEntityRenderer(ModCrawlers.STENODICTYA_NYMPH.get(),   // 11-17 段 1(0.7 倍の形は geo に焼き込み済み)
                ctx -> new SmallCrawlerRenderer<com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity>(ctx, new CritterModel<>(com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity.ID), 1.0F, 0.3F));
        event.registerEntityRenderer(ModCrawlers.GIANT_SNAIL.get(),
                ctx -> new SmallCrawlerRenderer<com.yayuy.overgrownvegetation.entity.GiantSnailEntity>(ctx, new CritterModel<>(com.yayuy.overgrownvegetation.entity.GiantSnailEntity.ID), 1.0F, 0.4F));
        event.registerEntityRenderer(ModCrawlers.ARTHROPLEURA.get(),
                ctx -> new SmallCrawlerRenderer<com.yayuy.overgrownvegetation.entity.ArthropleuraEntity>(ctx, new CritterModel<>(com.yayuy.overgrownvegetation.entity.ArthropleuraEntity.ID), 1.0F, 0.7F));
        // ストロビラの球果(11-15 段 2): 絵は【仮】で胞子嚢のアイテム(少し大きく)
        event.registerEntityRenderer(ModCrawlers.STROBILA_CONE.get(),
                ctx -> new net.minecraft.client.renderer.entity.ThrownItemRenderer<>(ctx, 1.4F, false));
    }
}
