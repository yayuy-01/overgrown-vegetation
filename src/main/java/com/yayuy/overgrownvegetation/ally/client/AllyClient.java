package com.yayuy.overgrownvegetation.ally.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyEntities;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterRenderBuffersEvent;

/** 同盟軍の描画の登録(2026-10-01)。共有のクライアントの登録を太らせないよう、ここで兵士の描画だけ登録する。 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AllyClient {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        for (SoldierKind kind : SoldierKind.values()) {
            event.registerEntityRenderer(AllyEntities.type(kind), SoldierRenderer::new);
        }
    }

    /** 兵士の肌と銃の描画の溜め場所({@link SoldierRenderer#registerBuffers}、2026-10-07)。 */
    @SubscribeEvent
    public static void registerRenderBuffers(RegisterRenderBuffersEvent event) {
        SoldierRenderer.registerBuffers(event);
    }

    private AllyClient() {
    }
}
