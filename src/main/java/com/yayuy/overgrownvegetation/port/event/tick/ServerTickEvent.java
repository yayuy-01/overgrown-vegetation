package com.yayuy.overgrownvegetation.port.event.tick;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;

import java.util.function.BooleanSupplier;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code net.neoforged.neoforge.event.tick.ServerTickEvent}({@code .Pre}・{@code .Post})。
 * {@link TickBridge} が Forge の {@code TickEvent.ServerTickEvent}(START・END)から作って {@code MinecraftForge.EVENT_BUS} に投げる。
 * 受け手は本流と同じ書き方({@code @SubscribeEvent public static void on(ServerTickEvent.Post e)})。
 */
public class ServerTickEvent extends Event {

    private final MinecraftServer server;
    private final BooleanSupplier hasTime;

    protected ServerTickEvent(MinecraftServer server, BooleanSupplier hasTime) {
        this.server = server;
        this.hasTime = hasTime;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public boolean hasTime() {
        return hasTime.getAsBoolean();
    }

    public static class Pre extends ServerTickEvent {
        public Pre() {
            this(null, () -> false);
        }

        public Pre(MinecraftServer server, BooleanSupplier hasTime) {
            super(server, hasTime);
        }
    }

    public static class Post extends ServerTickEvent {
        public Post() {
            this(null, () -> false);
        }

        public Post(MinecraftServer server, BooleanSupplier hasTime) {
            super(server, hasTime);
        }
    }
}
