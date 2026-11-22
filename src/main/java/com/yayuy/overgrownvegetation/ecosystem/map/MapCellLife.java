package com.yayuy.overgrownvegetation.ecosystem.map;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.front.CellLife;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 前線と部隊の口 {@link CellLife}(front/ にある。向こうのファイル)の中身(2026-11-21)。{@link MapEcology} をそのまま渡す。
 * heads = 生きている頭数(満員 − 殺された分)、infected = 感染した数(どちらも整数に切り捨て。left() = 感染させられる残り)。
 */
public final class MapCellLife implements CellLife {

    @Override
    public Result infect(ServerLevel level, int cellX, int cellZ, int attackers, double seconds) {
        MapEcology.InfectResult r = MapEcology.infect(level, cellX, cellZ, attackers, seconds);
        if (r.infected() == 0 && !r.becameOvergrown()) return Result.NOTHING;
        return new Result(r.infected(), r.villagers(), r.income(), r.becameOvergrown());
    }

    @Override
    public View view(ServerLevel level, int cellX, int cellZ) {
        MapEcology.CellView v = MapEcology.view(level, cellX, cellZ);
        int infected = (int) Math.floor(v.infected() + 1.0E-9D);
        return new View(infected + v.heads(), infected, v.barren());
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent
        public static void onCommonSetup(FMLCommonSetupEvent event) {
            CellLife.register(new MapCellLife());
        }

        private Setup() {
        }
    }
}
