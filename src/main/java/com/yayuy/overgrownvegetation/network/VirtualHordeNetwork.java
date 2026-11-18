package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 仮想の群れの通信(2026-11-18 大群の軽量化 A)。共有の {@code ModNetwork} とは別の登録にして、版を自分で持つ。
 * 版 1: まわりの仮想の群れ {@link VirtualHordePayload}。版 2(11-18): 群れごとの並びの番号の始まり firstIndex を足した。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class VirtualHordeNetwork {

    private VirtualHordeNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("virtual-horde-2");
        registrar.playToClient(VirtualHordePayload.TYPE, VirtualHordePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.yayuy.overgrownvegetation.client.horde.ClientVirtualHordes.receive(payload)));
    }
}
