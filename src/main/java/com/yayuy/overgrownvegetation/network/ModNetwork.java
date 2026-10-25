package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * この MOD の通信の登録。通信を足したら版を 1 つ上げる(違う版の MOD どうしは接続の時点ではじかれる)。
 *
 * <p>罠: ワールドに入るとき、近くのチャンクの通信がクライアントの最初の tick より先に届く。受け手は
 * 「まだ準備ができていない」状態を前提にする(メモリ client-payload-before-first-tick)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModNetwork {

    /** 通信の版。 */
    public static final String VERSION = "1";

    private ModNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        // 例: registrar.playToClient(SomePayload.TYPE, SomePayload.STREAM_CODEC, SomePayload::handle);
    }
}
