package com.yayuy.overgrownvegetation.port.event.tick;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;

/**
 * 【1.20.1 だけの物・互換の層】Forge の {@code TickEvent.*}(START・END の 1 つのイベント)を、NeoForge の形の Pre・Post に分けて投げ直す。
 * {@code port.PortSetup} が起動のときに 1 回つなぐ。
 */
public final class TickBridge {

    private TickBridge() {
    }

    public static void init() {
        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener(EventPriority.NORMAL, false, TickEvent.ServerTickEvent.class, e ->
                bus.post(e.phase == TickEvent.Phase.START
                        ? new ServerTickEvent.Pre(e.getServer(), e::haveTime)
                        : new ServerTickEvent.Post(e.getServer(), e::haveTime)));
        bus.addListener(EventPriority.NORMAL, false, TickEvent.LevelTickEvent.class, e ->
                bus.post(e.phase == TickEvent.Phase.START
                        ? new LevelTickEvent.Pre(e.level, e::haveTime)
                        : new LevelTickEvent.Post(e.level, e::haveTime)));
        bus.addListener(EventPriority.NORMAL, false, TickEvent.PlayerTickEvent.class, e ->
                bus.post(e.phase == TickEvent.Phase.START
                        ? new PlayerTickEvent.Pre(e.player)
                        : new PlayerTickEvent.Post(e.player)));
        bus.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class, e ->
                bus.post(e.phase == TickEvent.Phase.START ? new ClientTickEvent.Pre() : new ClientTickEvent.Post()));
    }
}
