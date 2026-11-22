package com.yayuy.overgrownvegetation.port.event.tick;

import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.Event;

import java.util.function.BooleanSupplier;

/** 【1.20.1 だけの物・互換の層】NeoForge の {@code LevelTickEvent}({@code .Pre}・{@code .Post})。サーバーとクライアントの両方で来る。{@link TickBridge} が作る。 */
public class LevelTickEvent extends Event {

    private final Level level;
    private final BooleanSupplier hasTime;

    protected LevelTickEvent(Level level, BooleanSupplier hasTime) {
        this.level = level;
        this.hasTime = hasTime;
    }

    public Level getLevel() {
        return level;
    }

    public boolean hasTime() {
        return hasTime.getAsBoolean();
    }

    public static class Pre extends LevelTickEvent {
        public Pre() {
            this(null, () -> false);
        }

        public Pre(Level level, BooleanSupplier hasTime) {
            super(level, hasTime);
        }
    }

    public static class Post extends LevelTickEvent {
        public Post() {
            this(null, () -> false);
        }

        public Post(Level level, BooleanSupplier hasTime) {
            super(level, hasTime);
        }
    }
}
