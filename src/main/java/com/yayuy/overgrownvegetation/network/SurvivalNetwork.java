package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.network.RegisterPayloadHandlersEvent;
import com.yayuy.overgrownvegetation.port.network.PayloadRegistrar;

/**
 * サバイバルの背骨の通信(2026-11-13)。共有の {@code ModNetwork}(版の番号を並行するセッションが上げる)とは別の登録にして、版を自分で持つ
 * ({@code G1Network} と同じ作法)。版 1: HUD の 2 本のゲージ {@link SurvivalHudPayload}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class SurvivalNetwork {

    private SurvivalNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("survival-1");
        registrar.playToClient(
                SurvivalHudPayload.TYPE,
                SurvivalHudPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.survival.SurvivalHud.accept(payload)));
    }
}
