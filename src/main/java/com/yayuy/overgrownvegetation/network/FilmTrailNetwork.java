package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.network.RegisterPayloadHandlersEvent;
import com.yayuy.overgrownvegetation.port.network.PayloadRegistrar;

/**
 * 水面の膜の跡の通信(2026-11-13)。共有の {@code ModNetwork}(版の番号を並行するセッションが上げる)とは別の登録にして、版を自分で持つ。
 * 版 1: 跡の新しい点 {@link FilmTrailPayload}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class FilmTrailNetwork {

    private FilmTrailNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("film-trail-1");
        registrar.playToClient(
                FilmTrailPayload.TYPE,
                FilmTrailPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.atmosphere.FilmWakes.onPoints(payload)));
    }
}
