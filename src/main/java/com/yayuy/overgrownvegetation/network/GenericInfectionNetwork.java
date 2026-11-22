package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.network.RegisterPayloadHandlersEvent;
import com.yayuy.overgrownvegetation.port.network.PayloadRegistrar;

/**
 * 汎用の感染体の通信(2026-11-15)。共有の {@code ModNetwork} とは別の登録にして、版を自分で持つ({@link G1Network} と同じ)。
 * 版 1: 印 {@link GenericInfectionPayload}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class GenericInfectionNetwork {

    private GenericInfectionNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("generic-infect-1");
        registrar.playToClient(
                GenericInfectionPayload.TYPE,
                GenericInfectionPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    // クライアントのクラスはこのラムダが走るとき(クライアントだけ)に初めて読まれる
                    if (payload.turned()) {
                        com.yayuy.overgrownvegetation.client.infect.GenericInfectionClient.put(payload.entityId(), payload.seed(),
                                payload.startTick(), payload.transformTicks(), payload.boss());
                    } else {
                        com.yayuy.overgrownvegetation.client.infect.GenericInfectionClient.remove(payload.entityId());
                    }
                }));
    }
}
