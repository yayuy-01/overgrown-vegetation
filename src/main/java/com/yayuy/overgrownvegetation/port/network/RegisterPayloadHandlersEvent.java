package com.yayuy.overgrownvegetation.port.network;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.event.IModBusEvent;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code RegisterPayloadHandlersEvent}(MOD のバス)。
 * {@code port.PortSetup} が FMLCommonSetupEvent の中で 1 回投げる。受け手の書き方は本流と同じ
 * ({@code @EventBusSubscriber(bus = MOD)} の {@code @SubscribeEvent})。
 */
public class RegisterPayloadHandlersEvent extends Event implements IModBusEvent {

    public RegisterPayloadHandlersEvent() {
    }

    public PayloadRegistrar registrar(String version) {
        return new PayloadRegistrar(version);
    }
}
