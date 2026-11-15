package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * サルティムネスの通信(2026-11-15)。共有の {@code ModNetwork} とは別の登録にして、版を自分で持つ。
 * 版 1: 押さえ込まれた本人のカメラ {@link SaltimnesPinPayload}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class SaltimnesNetwork {

    private SaltimnesNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("saltimnes-1");
        registrar.playToClient(
                SaltimnesPinPayload.TYPE,
                SaltimnesPinPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.SaltimnesPinCamera.receive(payload)));
    }
}
