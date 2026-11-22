package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.network.RegisterPayloadHandlersEvent;
import com.yayuy.overgrownvegetation.port.network.PayloadRegistrar;

/**
 * 声の加工の通信(2026-11-14)。共有の {@code ModNetwork} とは別の登録にして版を自分で持つ({@code G1Network} と同じ作法)。
 * 版 1: 汚した声を鳴らす {@link CrushedVoicePayload}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class VoiceNetwork {

    private VoiceNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("voice-1");
        registrar.playToClient(
                CrushedVoicePayload.TYPE,
                CrushedVoicePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.yayuy.overgrownvegetation.client.voice.CrushedVoiceClient.play(payload)));
    }
}
