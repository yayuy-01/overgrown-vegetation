package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.ForestUpkeep;
import com.yayuy.overgrownvegetation.front.FrontDirector;
import com.yayuy.overgrownvegetation.front.Fronts;
import com.yayuy.overgrownvegetation.front.UnitTerrain;
import com.yayuy.overgrownvegetation.horde.UnitPurpose;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 前線と部隊の重さ(2026-11-21): 前線 20・部隊 3,000(9 万体)のとき、部隊を進める 1 回・割り振り役の 1 回・維持費の数え直しの時間をログに出す。
 * 判定はゆるい上限だけ(遅い PC でも落ちないよう)。数字は報告に使う。地形は決め打ち(バイオームの引きの重さは別 = 1 チャンク 1 回で覚える)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FrontPerfGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterFrontPerfGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(FrontPerfGameTests.class);
        }

        private Registration() {
        }
    }

    private FrontPerfGameTests() {
    }

    @GameTest(template = "gametest_empty7", batch = "front_perf", timeoutTicks = 40)
    public static void thousandsOfUnits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.get(level.getServer());
        ResourceLocation z = BuiltInRegistries.ENTITY_TYPE.getKey(G1Species.ZOMBIE.entityType());
        List<Integer> ids = new ArrayList<>();
        try {
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            long now = level.getGameTime();
            for (int i = 0; i < 20; i++) {
                double fx = 30_000_000 + i * 2000.5D, fz = 30_000_000.5D;
                Fronts.Front f = fronts.addSourceForTests(fx, fz);
                ids.add(f.id());
                for (int u = 0; u < 150; u++) {
                    UnitPurpose p = u % 3 == 0 ? UnitPurpose.EXPAND : UnitPurpose.DEFEND;
                    VirtualHordes.Swarm s = h.newUnit(fx + (u % 15) * 16, 64, fz + (u / 15) * 16, f.id(), p, now);
                    s.addHeads(z, 30, 100.0D);
                    if (p == UnitPurpose.EXPAND) s.assign(p, fx + 600, fz + 600, now);
                }
            }
            long t0 = System.nanoTime();
            h.move(level, DesignData.perf().virtualMoveIntervalTicks, DesignData.perf());
            double moveMs = (System.nanoTime() - t0) / 1e6;
            t0 = System.nanoTime();
            FrontDirector.run(level, 1.0D);
            double runMs = (System.nanoTime() - t0) / 1e6;
            ForestUpkeep.invalidate();
            t0 = System.nanoTime();
            ForestUpkeep.priceInForest(level);
            double upkeepMs = (System.nanoTime() - t0) / 1e6;
            OvergrownVegetation.LOGGER.info(String.format(Locale.ROOT, "[前線と部隊の重さ] 前線 20・部隊 3000(90000 体): 進める 1 回 %.2f ms・割り振り役 1 回 %.2f ms・維持費の数え直し %.2f ms",
                    moveMs, runMs, upkeepMs));
            helper.assertTrue(moveMs < 200.0D && runMs < 1000.0D && upkeepMs < 200.0D, String.format(Locale.ROOT, "重すぎる: 進める %.1f・割り振り %.1f・維持費 %.1f ms", moveMs, runMs, upkeepMs));
            helper.succeed();
        } finally {
            UnitTerrain.terrainForTests = null;
            for (VirtualHordes.Swarm s : new ArrayList<>(h.swarms())) if (ids.contains(s.front())) h.removeUnit(s);
            fronts.fronts().removeIf(f -> ids.contains(f.id()));
            ForestUpkeep.invalidate();
        }
    }
}
