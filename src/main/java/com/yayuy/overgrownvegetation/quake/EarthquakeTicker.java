package com.yayuy.overgrownvegetation.quake;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;

/**
 * オーバーワールドの tick から {@link EarthquakeData#tick(ServerLevel)} を毎 tick 呼ぶ。
 * 地割れの掘削は「5 秒 = formTicks 個の波」を 1 tick 1 波で進めるため、間引かず毎 tick 必要。
 * 進行中の演出が無ければ {@link EarthquakeData#tick} は即 return するので負荷は無視できる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class EarthquakeTicker {

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (serverLevel.dimension() != Level.OVERWORLD) {
            return;
        }
        EarthquakeData.get(serverLevel).tick(serverLevel);
    }

    private EarthquakeTicker() {
    }
}
