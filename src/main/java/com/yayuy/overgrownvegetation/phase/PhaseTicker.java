package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * オーバーワールドの tick から {@link PhaseData#tick(ServerLevel)} を定期的に呼び出す
 * (フェーズ0→1移行の時刻ゲートと、フェーズ1以降の時間経過ポイントの加算)。
 * 間引いて呼んでも、経過 tick 数分のポイントはまとめて加算されるので取りこぼしは無い。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PhaseTicker {

    /** 何 tick おきに判定するか(100 tick = 5 秒)。日数境界を跨いだかどうかの判定だけなので粗くて構わない。 */
    private static final int INTERVAL = 100;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (serverLevel.dimension() != Level.OVERWORLD) {
            return;
        }
        if (serverLevel.getGameTime() % INTERVAL != 0) {
            return;
        }

        PhaseData.get(serverLevel).tick(serverLevel);
    }

    private PhaseTicker() {
    }
}
