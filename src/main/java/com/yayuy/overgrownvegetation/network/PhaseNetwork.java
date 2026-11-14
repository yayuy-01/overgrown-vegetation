package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * フェーズの通信(2026-11-14 フェーズの小さな段)。共有の {@code ModNetwork} とは別の登録にして版を自分で持つ
 * ({@code SurvivalNetwork} と同じ作法)。版 1: 今のフェーズと段 {@link PhaseStatePayload}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class PhaseNetwork {

    private PhaseNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("phase-1");
        registrar.playToClient(
                PhaseStatePayload.TYPE,
                PhaseStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.phase.ClientPhaseState.accept(payload)));
    }
}
