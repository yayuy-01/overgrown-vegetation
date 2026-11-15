package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * クリペイムネスの通信(2026-11-15)。共有の {@code ModNetwork} とは別の登録にして、版を自分で持つ(サルティムネスと同じ作り)。
 * 版 1: 抱え込まれた本人のカメラ {@link ClipeimnesHoldPayload}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ClipeimnesNetwork {

    private ClipeimnesNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("clipeimnes-1");
        registrar.playToClient(
                ClipeimnesHoldPayload.TYPE,
                ClipeimnesHoldPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.ClipeimnesHoldCamera.receive(payload)));
    }
}
