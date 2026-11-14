package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 監視塔フィロセンスの通信(2026-11-14)。共有の {@code ModNetwork} とは別の登録にして、版を自分で持つ。
 * 版 1: 見られている強さ {@link PhyllosensGazePayload}(画面の縁の効果)。版 2(11-14): 出来事(見え始め・見えている・印・切れた)と塔の id を足した。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class PhyllosensNetwork {

    private PhyllosensNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("phyllosens-2");
        registrar.playToClient(
                PhyllosensGazePayload.TYPE,
                PhyllosensGazePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.PhyllosensGazeEdge.receive(payload)));
    }
}
