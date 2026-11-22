package com.yayuy.overgrownvegetation.port.event.tick;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.Event;

/** 【1.20.1 だけの物・互換の層】NeoForge の {@code PlayerTickEvent}({@code .Pre}・{@code .Post})。両方の側で来る。{@link TickBridge} が作る。 */
public class PlayerTickEvent extends Event {

    private final Player player;

    protected PlayerTickEvent(Player player) {
        this.player = player;
    }

    public Player getEntity() {
        return player;
    }

    public static class Pre extends PlayerTickEvent {
        public Pre() {
            this(null);
        }

        public Pre(Player player) {
            super(player);
        }
    }

    public static class Post extends PlayerTickEvent {
        public Post() {
            this(null);
        }

        public Post(Player player) {
            super(player);
        }
    }
}
