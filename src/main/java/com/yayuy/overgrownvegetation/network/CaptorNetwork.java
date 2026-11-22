package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.network.RegisterPayloadHandlersEvent;
import com.yayuy.overgrownvegetation.port.network.PayloadRegistrar;

/**
 * 捕まえて乗せる Mob の通信(2026-11-16 コードの見直し ①14 で SaltimnesNetwork・ClipeimnesNetwork をまとめた)。
 * 共有の {@code ModNetwork} とは別の登録にして、版を自分で持つ。版 1: 捕まった本人のカメラ {@link CaptorPayload}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CaptorNetwork {

    private CaptorNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("captor-1");
        registrar.playToClient(
                CaptorPayload.TYPE,
                CaptorPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.CaptorCamera.receive(payload)));
    }
}
