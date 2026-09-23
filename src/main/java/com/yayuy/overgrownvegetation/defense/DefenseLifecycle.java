package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 忌避灯・機関砲台・胞子警報器・巣の噴出孔・胞子ノードの台帳はどれもメモリ上の static なので、
 * ワールドを閉じたら空にする(次に別のワールドを開いたとき古い座標が残らないように。2026-09-19)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DefenseLifecycle {

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RepellentLamps.clearAll();
        GunTurrets.clearAll();
        SporeAlarms.clearAll();
        com.yayuy.overgrownvegetation.nest.NestVents.clearAll();
        com.yayuy.overgrownvegetation.nest.NestNodes.clearAll();
        com.yayuy.overgrownvegetation.nest.NestCrew.clearAll();
        com.yayuy.overgrownvegetation.nest.BuilderGroups.clearAll();
        com.yayuy.overgrownvegetation.nest.NestGrowthQueue.clearAll();
    }

    private DefenseLifecycle() {
    }
}
