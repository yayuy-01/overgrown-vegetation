package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.network.RegisterPayloadHandlersEvent;
import com.yayuy.overgrownvegetation.port.network.PayloadRegistrar;

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
        // 版 2(2026-11-16): フェーズが上がったときの合図 PhaseCuePayload を足した。
        PayloadRegistrar registrar = event.registrar("phase-2");
        registrar.playToClient(
                PhaseCuePayload.TYPE,
                PhaseCuePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.atmosphere.PhaseCue.accept(payload)));
        registrar.playToClient(
                PhaseStatePayload.TYPE,
                PhaseStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.phase.ClientPhaseState.accept(payload)));
    }
}
