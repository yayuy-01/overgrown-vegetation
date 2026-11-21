package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestEconomy;
import com.yayuy.overgrownvegetation.director.ForestUpkeep;
import com.yayuy.overgrownvegetation.front.CellLife;
import com.yayuy.overgrownvegetation.front.FrontDirector;
import com.yayuy.overgrownvegetation.front.Fronts;
import com.yayuy.overgrownvegetation.front.UnitTerrain;
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
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 前線と部隊 区切り 2(2026-11-21): 部隊が読み込まれていない所で動く・実物になる・戻る・補充・保存と復元・前線の合流・作るのは 8 割の関所まで・広げる。
 * 世界の台帳を汚さないよう、部隊と前線は切り離した台帳({@code load(new CompoundTag())})で確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FrontUnitGameTests {

    private static final String BATCH = "front_units";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FrontUnitGameTests.class);
        }

        private Registration() {
        }
    }

    private FrontUnitGameTests() {
    }

    private static ResourceLocation zombie() {
        return BuiltInRegistries.ENTITY_TYPE.getKey(G1Species.ZOMBIE.entityType());
    }

    /** 読み込まれていない所で進む・水は回り道・山は半分の速さ。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void unitMovesOnMapAvoidingWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        var t = DesignData.perf();
        try {
            // 陸だけ: まっすぐ進む
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            VirtualHordes.Swarm s = h.newUnit(5_000_000, 64, 5_000_000, 1, UnitPurpose.EXPAND, 0L);
            s.addHeads(zombie(), 10, 100.0D);
            s.assign(UnitPurpose.EXPAND, 5_000_400, 5_000_000, 0L);
            h.move(level, 20, t);
            double landStep = s.x() - 5_000_000;
            helper.assertTrue(landStep > 0.5D && Math.abs(s.z() - 5_000_000) < 1.0E-6D, "陸をまっすぐ進まない: " + s.x() + ", " + s.z());
            // 山: 半分
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.MOUNTAIN;
            double x0 = s.x();
            h.move(level, 20, t);
            double mStep = s.x() - x0;
            helper.assertTrue(Math.abs(mStep - landStep * DesignData.front().mountainSpeedFactor) < 1.0E-6D, "山で半分の速さにならない: " + mStep + " / " + landStep);
            // 前が水(x の正の向きの先のチャンクが水): 横へ回る
            int waterFrom = ((int) Math.floor(s.x()) >> 4) + 1;
            UnitTerrain.terrainForTests = (cx, cz) -> cx >= waterFrom && cz == ((int) 5_000_000 >> 4) ? UnitTerrain.WATER : UnitTerrain.LAND;
            double z0 = s.z();
            h.move(level, 20, t);
            helper.assertTrue(Math.abs(s.z() - z0) > 0.1D, "前が水なのに横へ回らない: z " + z0 + " → " + s.z());
            helper.assertTrue(!UnitTerrain.blocked(UnitTerrain.kind(level, s.x(), s.z())), "水の升に入った");
            // 守るは動かない
            VirtualHordes.Swarm d = h.newUnit(6_000_000, 64, 6_000_000, 1, UnitPurpose.DEFEND, 0L);
            d.addHeads(zombie(), 10, 100.0D);
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            h.move(level, 200, t);
            helper.assertTrue(d.x() == 6_000_000 && d.z() == 6_000_000, "守る部隊が動いた");
            helper.succeed();
        } finally {
            UnitTerrain.terrainForTests = null;
        }
    }

    private static List<Mob> realOf(ServerLevel level, long unitId) {
        List<Mob> out = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob m && m.isAlive() && m.getPersistentData().contains(VirtualHordes.UNIT_KEY)
                    && m.getPersistentData().getLong(VirtualHordes.UNIT_KEY) == unitId) out.add(m);
        }
        return out;
    }

    /** プレイヤーのそばで実物になる(上限まで)・倒れたら補充・遠くなったら戻る(傷は戻ると治る)。頭数は倒された分しか減らない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void unitMaterializesRefillsAndReturns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        var t = DesignData.perf();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        List<double[]> playersBefore = VirtualHordes.playersForTests;
        Integer capBefore = VirtualHordes.realCapForTests;
        VirtualHordes.Swarm s = null;
        try {
            s = h.newUnit(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 77, UnitPurpose.DEFEND, level.getGameTime());
            s.addHeads(zombie(), 6, 100.0D);
            VirtualHordes.playersForTests = List.<double[]>of(new double[]{at.getX(), at.getZ()});
            int base = VirtualHordes.realHeads(level);
            VirtualHordes.realCapForTests = base + 3;
            h.materialize(level, t);
            List<Mob> real = realOf(level, s.id());
            helper.assertTrue(real.size() == 3 && s.size() == 3, "上限 3 まで実物にならない: 実物 " + real.size() + "・残り " + s.size());
            helper.assertTrue(real.stream().allMatch(m -> m.getPersistentData().getInt(VirtualHordes.FRONT_KEY) == 77), "前線の印が付いていない");
            // 1 体倒し、1 体傷つける → 補充で 1 体出る
            real.get(0).kill();
            real.get(1).setHealth(real.get(1).getMaxHealth() * 0.5F);
            h.materialize(level, t);
            helper.assertTrue(realOf(level, s.id()).size() == 3 && s.size() == 2, "倒れた分を補充しない: 実物 " + realOf(level, s.id()).size() + "・残り " + s.size());
            // 遠くへ: 戻る(傷ついたものも)
            VirtualHordes.playersForTests = List.<double[]>of(new double[]{at.getX() + 10_000, at.getZ()});
            h.dematerialize(level, t);
            helper.assertTrue(realOf(level, s.id()).isEmpty() && s.size() == 5, "戻らない・頭数が合わない: 実物 " + realOf(level, s.id()).size() + "・頭数 " + s.size() + "(欲しい 5)");
            helper.succeed();
        } finally {
            VirtualHordes.playersForTests = playersBefore;
            VirtualHordes.realCapForTests = capBefore;
            if (s != null) for (Mob m : realOf(level, s.id())) m.discard();
        }
    }

    /** 部隊の保存と復元(前線・目的・払った額・狩りの的・空の部隊も残る)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void unitSavesAndLoads(GameTestHelper helper) {
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        VirtualHordes.Swarm s = h.newUnit(100, 64, 200, 5, UnitPurpose.EXPAND, 42L);
        s.addHeads(zombie(), 12, 100.0D);
        java.util.UUID u = java.util.UUID.randomUUID();
        s.setHunt(u);
        VirtualHordes.Swarm empty = h.newUnit(300, 64, 400, 6, UnitPurpose.DEFEND, 43L);
        CompoundTag tag = h.save(new CompoundTag(), helper.getLevel().registryAccess());
        VirtualHordes back = VirtualHordes.load(tag);
        VirtualHordes.Swarm r = back.byId(s.id());
        helper.assertTrue(r != null && r.front() == 5 && r.purpose() == UnitPurpose.EXPAND && Math.abs(r.paid() - 1200.0D) < 1.0E-6D && u.equals(r.hunt())
                && r.size() == 12, "部隊が復元されない");
        helper.assertTrue(back.byId(empty.id()) != null, "空の部隊が復元で消えた(実物が戻る先が無くなる)");
        Fronts f = Fronts.load(new CompoundTag());
        Fronts.Front a = f.fronts().isEmpty() ? null : f.fronts().get(0);
        helper.assertTrue(a == null, "空の前線の台帳に前線がある");
        helper.succeed();
    }

    /** 前線は巨大樹ごと。ハイヴノードを倒された前線の部隊は近くの前線へ合流する。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void frontsFollowTreesAndJoinOnDeath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        Fronts fronts = Fronts.load(new CompoundTag());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        int ax = 7_100_000, bx = 7_100_400, z = 7_100_000;
        long ka = ((long) ax << 32) | (z & 0xFFFFFFFFL), kb = ((long) bx << 32) | (z & 0xFFFFFFFFL);
        try {
            pd.recordGiantTreeOrigin(ax, z);
            pd.recordGiantTreeOrigin(bx, z);
            FrontDirector.sync(level, fronts, h);
            Fronts.Front fa = fronts.byTree(ka), fb = fronts.byTree(kb);
            helper.assertTrue(fa != null && fb != null, "巨大樹ごとに前線ができない");
            VirtualHordes.Swarm s = h.newUnit(ax, 64, z, fa.id(), UnitPurpose.DEFEND, 0L);
            s.addHeads(zombie(), 10, 100.0D);
            pd.killGiantTreeNear(new BlockPos(ax, 64, z), 4.0D);
            FrontDirector.sync(level, fronts, h);
            helper.assertTrue(fronts.byTree(ka) == null, "倒された巨大樹の前線が残った");
            helper.assertTrue(s.front() == fb.id(), "残りの部隊が近くの前線へ合流しない: " + s.front());
            helper.succeed();
        } finally {
            pd.forgetGiantTreeForTests(ka);
            pd.forgetGiantTreeForTests(kb);
        }
    }

    /** 作るのは維持費の 8 割の関所まで。そこで止まり、稼ぎ − 維持費は正のまま。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void productionStopsAtUpkeepGate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        Double income = ForestEconomy.incomeOverride();
        double budget = d.available(level);
        Integer phase = DirectorGates.phaseOverride();
        Fronts fronts = Fronts.load(new CompoundTag());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        try {
            DirectorGates.setPhaseForTests(3);
            ForestEconomy.setIncomeForTests(1200.0D); // ふつうは毎秒 1 → 維持費の上限は毎秒 0.8 = 段階 0 で 200 体ほど
            ForestUpkeep.setSampleForTests(0.0D, 0.0D);
            d.setBudget(level, 1.0E9D);
            Fronts.Front f = fronts.addSourceForTests(8_000_000.5D, 8_000_000.5D);
            for (int i = 0; i < 2000; i++) FrontDirector.produce(level, fronts, h, f, 1.0D);
            int heads = 0;
            for (VirtualHordes.Swarm s : h.swarms()) heads += s.size();
            double upkeep = ForestUpkeep.perSecond(level), limit = ForestDirector.upkeepLimitPerSecond(level);
            helper.assertTrue(heads > 0 && upkeep <= limit + 1.0E-9D, "8 割を超えて作った: " + upkeep + " > " + limit);
            helper.assertTrue(upkeep > limit * 0.9D, "8 割に届く前に止まった: " + upkeep + " / " + limit + "(" + heads + " 体)");
            helper.assertTrue(ForestDirector.incomePerSecond(level) - upkeep > 0.0D, "稼ぎ − 維持費が正でない");
            int max = DesignData.front().unitMax(3);
            helper.assertTrue(h.swarms().stream().allMatch(s -> s.size() <= max), "部隊の上限を超えた");
            helper.succeed();
        } finally {
            ForestUpkeep.setSampleForTests(null, null);
            ForestEconomy.setIncomeForTests(income);
            DirectorGates.setPhaseForTests(phase);
            d.setBudget(level, budget);
        }
    }

    /** 部隊の中の進化(区切り 6): F3 で、部隊 1 つにつき evolvePerUnit 体の段階 0 が 1 段目へ。値段は進化先 − 段階 0。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void unitEvolvesInLedger(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        Integer phase = DirectorGates.phaseOverride();
        var stage = DirectorGates.stageOverride();
        Fronts fronts = Fronts.load(new CompoundTag());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        try {
            DirectorGates.setPhaseForTests(3);
            DirectorGates.setStageForTests(com.yayuy.overgrownvegetation.phase.PhaseStage.PEAK);
            ForestUpkeep.setSampleForTests(0.0D, 0.0D);
            d.setBudget(level, 1.0E9D);
            Fronts.Front f = fronts.addSourceForTests(41_000_000.5D, 41_000_000.5D);
            VirtualHordes.Swarm s = h.newUnit(41_000_000, 64, 41_000_000, f.id(), UnitPurpose.DEFEND, level.getGameTime());
            s.addHeads(zombie(), 10, 100.0D);
            FrontDirector.evolve(level, h, f);
            int n = DesignData.front().evolvePerUnit;
            ResourceLocation dev = OvergrownVegetation.id("developed_parasitized_zombie");
            helper.assertTrue(s.counts().getOrDefault(dev, 0) == n && s.counts().getOrDefault(zombie(), 0) == 10 - n,
                    "部隊の中で進化しない: " + s.counts());
            helper.succeed();
        } finally {
            ForestUpkeep.setSampleForTests(null, null);
            DirectorGates.setPhaseForTests(phase);
            DirectorGates.setStageForTests(stage);
            d.setBudget(level, budget);
        }
    }

    /** 部隊の中の根付き(11-21): 守りの持ち場に着いた部隊の段階 0 が根付きの姿へ。根付いた頭数は狩り・襲撃に抜かれない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void defendersRootAndStayPut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        Integer phase = DirectorGates.phaseOverride();
        var stage = DirectorGates.stageOverride();
        Fronts fronts = Fronts.load(new CompoundTag());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        try {
            DirectorGates.setPhaseForTests(3);
            DirectorGates.setStageForTests(com.yayuy.overgrownvegetation.phase.PhaseStage.PEAK);
            ForestUpkeep.setSampleForTests(0.0D, 0.0D);
            d.setBudget(level, 1.0E9D);
            Fronts.Front f = fronts.addSourceForTests(42_000_000.5D, 42_000_000.5D);
            VirtualHordes.Swarm s = h.newUnit(42_000_100, 64, 42_000_000, f.id(), UnitPurpose.DEFEND, level.getGameTime()); // 持ち場(前線の真ん中から離れた所)
            s.addHeads(zombie(), 10, 100.0D);
            FrontDirector.root(level, h, f);
            net.minecraft.world.entity.EntityType<?> rt = com.yayuy.overgrownvegetation.director.G1Fate.rootedTypeOf(G1Species.ZOMBIE);
            if (rt == null) {
                helper.succeed(); // ゾンビの根付きの姿が無い構成
                return;
            }
            ResourceLocation rid = BuiltInRegistries.ENTITY_TYPE.getKey(rt);
            int n = s.counts().getOrDefault(rid, 0);
            helper.assertTrue(n == DesignData.front().rootedPerUnit, "守りの部隊が根付かない: " + s.counts());
            var taken = s.takeHeads(s.size());
            helper.assertTrue(!taken.containsKey(rid) && s.counts().getOrDefault(rid, 0) == n, "根付いた姿が抜き出された: " + taken);
            helper.succeed();
        } finally {
            ForestUpkeep.setSampleForTests(null, null);
            DirectorGates.setPhaseForTests(phase);
            DirectorGates.setStageForTests(stage);
            d.setBudget(level, budget);
        }
    }
    /** 広げる部隊は升の生き物を感染させ、稼ぎが入り、感染した数だけ部隊が太る。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void expandUnitInfectsCellAndGrows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        try {
            CellLife.register(new CellLife() {
                @Override
                public Result infect(ServerLevel l, int cellX, int cellZ, int attackers, double seconds) {
                    return new Result(3, 1, 600.0D, false);
                }

                @Override
                public View view(ServerLevel l, int cellX, int cellZ) {
                    return new View(20, 3, false);
                }
            });
            d.setBudget(level, 0.0D);
            VirtualHordes.Swarm s = h.newUnit(9_000_000, 64, 9_000_000, 999, UnitPurpose.EXPAND, level.getGameTime());
            s.addHeads(zombie(), 10, 100.0D);
            s.assign(UnitPurpose.EXPAND, 9_000_000, 9_000_000, level.getGameTime());
            h.move(level, 1, DesignData.perf()); // 着く
            FrontDirector.expand(level, h, 1.0D);
            helper.assertTrue(s.size() == 13, "感染した 3 体が部隊に加わらない: " + s.size());
            helper.assertTrue(Math.abs(d.available(level) - 600.0D) < 1.0E-6D, "升の感染の稼ぎが入らない: " + d.available(level));
            helper.succeed();
        } finally {
            CellLife.register(null);
            d.setBudget(level, budget);
        }
    }
}
