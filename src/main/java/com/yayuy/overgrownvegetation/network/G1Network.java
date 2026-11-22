package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * G1 段階 0 の通信(2026-11-08)。共有の {@code ModNetwork}(版の番号を並行するセッションが上げる)とは別の登録にして、版を自分で持つ。
 * 版 1: 崩れ落ちの知らせ {@link CorpseCollapsePayload}(2026-10-09 の版を parked から戻した)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class G1Network {

    private G1Network() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("g1-1");
        registrar.playToClient(
                CorpseCollapsePayload.TYPE,
                CorpseCollapsePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.G1Client.onCollapse(payload)));
    }
}
