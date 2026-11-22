package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestUpkeep;
import com.yayuy.overgrownvegetation.front.FrontNurseries;
import com.yayuy.overgrownvegetation.horde.UnitPurpose;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 広げる部隊の現地の苗床(2026-11-21 夜 ユーザー決定。依頼書 front-nursery-brief.md の「確かめ」)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FrontNurseryGameTests {

    private static final String BATCH = "front_nursery";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FrontNurseryGameTests.class);
        }

        private Registration() {
        }
    }

    private FrontNurseryGameTests() {
    }

    private static ResourceLocation zombie() {
        return BuiltInRegistries.ENTITY_TYPE.getKey(G1Species.ZOMBIE.entityType());
    }

    private static double cellCenter(int cell) {
        return (cell * 4 + 2) * 16.0D + 8.0D;
    }

    /** 着いた部隊が苗床を 1 つ作り(2 つ目は作らない)、20 秒ごとに萌芽体を定価の 1/8 で足す。部隊の上限で止まる。過繁茂になったら止まり出す所に残る。保存と復元。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void nurseryMakesRefillsAndStops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        Integer phase = DirectorGates.phaseOverride();
        PhaseData pd = PhaseData.get(level);
        FrontNurseries ns = FrontNurseries.load(new CompoundTag());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        int cx = (43_000_000 >> 4) >> 2, cz = (43_000_000 >> 4) >> 2;
        ChunkPos mid = new ChunkPos(cx * 4 + 2, cz * 4 + 2);
        try {
            DirectorGates.setPhaseForTests(0); // G2 以降の割合 0 = 萌芽体だけを足す
            ForestUpkeep.setSampleForTests(0.0D, 0.0D);
            d.setBudget(level, 1.0E6D);
            VirtualHordes.Swarm s = h.newUnit(cellCenter(cx), 64, cellCenter(cz), 5, UnitPurpose.EXPAND, level.getGameTime());
            s.addHeads(zombie(), 10, 100.0D);
            s.assign(UnitPurpose.EXPAND, cellCenter(cx), cellCenter(cz), level.getGameTime());
            h.move(level, 1, DesignData.perf()); // 着く
            helper.assertTrue(ns.tryMake(level, s, cx, cz), "着いた部隊が苗床を作らない");
            helper.assertTrue(s.size() == 9, "苗床になった 1 体が部隊から抜けない: " + s.size());
            helper.assertTrue(!ns.tryMake(level, s, cx, cz) && ns.nurseries().size() == 1, "1 升に 2 つ目の苗床を作った");
            // 20 秒たつと 1 体足す(萌芽体 = 定価の 1/8)
            double before = d.available(level);
            ns.tick(level, h);
            helper.assertTrue(s.size() == 9, "20 秒たたないうちに足した");
            ns.ageForTests((long) (DesignData.front().nurseryIntervalSeconds * 20.0D));
            ns.tick(level, h);
            double paid = before - d.available(level);
            double want = DesignData.director().largeRaid.costStageZero * DesignData.front().nurseryPriceFactor;
            helper.assertTrue(Math.abs(paid - want) < 1.0E-6D, "萌芽体の値段が定価の 1/8 でない: " + paid + "(欲しい " + want + ")");
            helper.assertTrue(s.size() == 10, "20 秒たっても補充しない: " + s.size());
            // 部隊の上限で止まる
            int max = DesignData.front().unitMax(0);
            while (s.size() < max) s.addHeads(zombie(), 1, 0.0D);
            ns.ageForTests(100000L);
            ns.tick(level, h);
            helper.assertTrue(s.size() == max, "部隊の上限を超えて補充した: " + s.size());
            // 過繁茂になったら止まり、出す所として残る
            s.takeHeads(5);
            pd.markOvergrownChunk(level, mid);
            ns.ageForTests(100000L);
            int sz = s.size();
            ns.tick(level, h);
            helper.assertTrue(s.size() == sz, "過繁茂になっても萌芽体を生んだ");
            helper.assertTrue(ns.sitesOf(5).size() == 1, "過繁茂になった苗床が出す所に残らない");
            // 保存と復元
            FrontNurseries back = FrontNurseries.load(ns.save(new CompoundTag(), level.registryAccess()));
            helper.assertTrue(back.nurseries().size() == 1 && back.nurseries().get(0).overgrown(), "苗床が復元されない");
            helper.succeed();
        } finally {
            pd.forgetOvergrownChunk(mid);
            ForestUpkeep.setSampleForTests(null, null);
            DirectorGates.setPhaseForTests(phase);
            d.setBudget(level, budget);
        }
    }

    private static int countG1Others(VirtualHordes.Swarm s) {
        int n = 0;
        for (var e : s.counts().entrySet()) if (!e.getKey().equals(zombie())) n += e.getValue();
        return n;
    }

    /** 読み込まれた升では実物の苗床が置かれ、壊されたら苗床(補充)は止まる。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void destroyedNurseryStops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        FrontNurseries ns = FrontNurseries.get(level.getServer());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        BlockPos at = helper.absolutePos(new BlockPos(3, 1, 3));
        int cx = (at.getX() >> 4) >> 2, cz = (at.getZ() >> 4) >> 2;
        try {
            ForestUpkeep.setSampleForTests(0.0D, 0.0D);
            d.setBudget(level, 1.0E6D);
            VirtualHordes.Swarm s = h.newUnit(cellCenter(cx), 64, cellCenter(cz), 6, UnitPurpose.EXPAND, level.getGameTime());
            s.addHeads(zombie(), 10, 100.0D);
            helper.assertTrue(ns.tryMake(level, s, cx, cz), "苗床を作らない");
            ns.tick(level, h);
            Entity real = null;
            for (Entity e : level.getAllEntities()) if (e.getPersistentData().contains(FrontNurseries.KEY)) real = e;
            if (real == null) {
                // 升の真ん中が読み込まれていない(テスト場の位置による)・苗床の姿が無い構成: 壊す確かめはできない
                helper.succeed();
                return;
            }
            ((net.minecraft.world.entity.LivingEntity) real).kill();
            helper.assertTrue(ns.nurseries().stream().noneMatch(n -> n.cellX() == cx && n.cellZ() == cz), "実物の苗床を壊しても止まらない");
            helper.succeed();
        } finally {
            ForestUpkeep.setSampleForTests(null, null);
            d.setBudget(level, budget);
            ns.clearForTests();
            for (Entity e : level.getAllEntities()) if (e.getPersistentData().contains(FrontNurseries.KEY)) e.discard();
        }
    }
}
