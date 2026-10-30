package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 忌避灯・機関砲台・胞子警報器・遮蔽盾の台帳はどれもメモリ上の static なので、
 * ワールドを閉じたら空にする(次に別のワールドを開いたとき古い座標が残らないように。2026-09-19)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DefenseLifecycle {

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RepellentLamps.clearAll();
        GunTurrets.clearAll();
        GunTurretClaims.clearAll();
        SporeAlarms.clearAll();
        BeamShields.clearAll(); // 遮蔽盾の台帳(2026-09-24 軽量化、監査 1230)
        StakeWards.clearAll(); // 受け流しの杭の囲い(2026-10-16)
        com.yayuy.overgrownvegetation.spore.TentShelters.clearAll(); // 防胞子の天幕(2026-10-16)
    }

    private DefenseLifecycle() {
    }
}
