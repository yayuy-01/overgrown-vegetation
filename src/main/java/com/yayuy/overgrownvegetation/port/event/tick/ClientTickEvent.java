package com.yayuy.overgrownvegetation.port.event.tick;

import net.minecraftforge.eventbus.api.Event;

/** 【1.20.1 だけの物・互換の層】NeoForge の {@code net.neoforged.neoforge.client.event.ClientTickEvent}({@code .Pre}・{@code .Post})。{@link TickBridge} が作る。 */
public class ClientTickEvent extends Event {

    public static class Pre extends ClientTickEvent {
        public Pre() {
        }
    }

    public static class Post extends ClientTickEvent {
        public Post() {
        }
    }
}
