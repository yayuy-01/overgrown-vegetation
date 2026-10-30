package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * オーバーワールドの tick から {@link RaidData#tick(ServerLevel)} を定期的に呼び出す
 * ({@link com.yayuy.overgrownvegetation.phase.PhaseTicker} と同じ間引き方)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class RaidTicker {

    /**
     * 何 tick おきに判定するか(100 tick = 5 秒)。発生判定は粗くて構わない。ピースフルの間はこの幅ずつ次の予定を後ろへずらす
     * ({@link RaidData#tick})。小規模レイドは 2026-10-10 にユーザー指定で削除。
     */
    static final int INTERVAL = 100;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (serverLevel.dimension() != Level.OVERWORLD) {
            return;
        }
        if (serverLevel.getGameTime() % INTERVAL != 0L) {
            return;
        }
        RaidData.get(serverLevel).tick(serverLevel);
    }

    private RaidTicker() {
    }
}
