package com.yayuy.overgrownvegetation.port.event.tick;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code EntityTickEvent}({@code .Pre} は取り消せる・{@code .Post})。
 * Forge 1.20.1 には全部の Entity の tick のイベントが無いので、F の mixin(port/mixin、{@code overgrown_vegetation.port.mixins.json})が
 * ServerLevel・ClientLevel の {@code tickNonPassenger}・{@code tickPassenger} の所で投げる。Pre を取り消すと、その tick の {@code tick()}/{@code rideTick()} を飛ばす。
 */
public class EntityTickEvent extends Event {

    private final Entity entity;

    protected EntityTickEvent(Entity entity) {
        this.entity = entity;
    }

    public Entity getEntity() {
        return entity;
    }

    @Cancelable
    public static class Pre extends EntityTickEvent {
        public Pre() {
            this(null);
        }

        public Pre(Entity entity) {
            super(entity);
        }
    }

    public static class Post extends EntityTickEvent {
        public Post() {
            this(null);
        }

        public Post(Entity entity) {
            super(entity);
        }
    }
}
