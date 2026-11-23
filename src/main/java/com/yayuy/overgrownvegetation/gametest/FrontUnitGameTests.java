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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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
        public static void onRegisterFrontUnitGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
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
            FrontDirector.setCountsForTests(f.id(), 1_000_000, 0); // 目標の頭数で止まらないように(広い樹域)
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
            FrontDirector.clearCountsForTests();
            ForestUpkeep.setSampleForTests(null, null);
            ForestEconomy.setIncomeForTests(income);
            DirectorGates.setPhaseForTests(phase);
            d.setBudget(level, budget);
        }
    }

    /** 11-22 ユーザー決定: 守りの持ち場の postEdgeShare は樹域の縁、残りは巨大樹のまわり。縁の無い樹域は巨大樹のまわり。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void defendPostsGoToTerritoryEdge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FrontDirector.clearCountsForTests();
        Fronts fronts = Fronts.load(new CompoundTag());
        try {
            Fronts.Front f = fronts.addSourceForTests(9_100_000.5D, 9_100_000.5D);
            Fronts.Front bare = fronts.addSourceForTests(9_200_000.5D, 9_200_000.5D);
            int fcx = 9_100_000 >> 4, fcz = 9_100_000 >> 4;
            long[] edge = new long[20];
            java.util.Set<Long> set = new java.util.HashSet<>();
            for (int i = 0; i < edge.length; i++) {
                edge[i] = net.minecraft.world.level.ChunkPos.asLong(fcx + 30, fcz - 10 + i); // 巨大樹から 30 チャンク(postRadiusChunks の外)
                set.add(edge[i]);
            }
            FrontDirector.setEdgesForTests(f.id(), edge);
            var random = net.minecraft.util.RandomSource.create(7L);
            int n = 4000, onEdge = 0;
            for (int i = 0; i < n; i++) {
                double[] p = FrontDirector.postForTests(level, f, random);
                if (set.contains(net.minecraft.world.level.ChunkPos.asLong(net.minecraft.util.Mth.floor(p[0]) >> 4, net.minecraft.util.Mth.floor(p[1]) >> 4))) onEdge++;
            }
            double share = onEdge / (double) n, want = DesignData.front().postEdgeShare;
            helper.assertTrue(Math.abs(share - want) < 0.04D, "縁に置く割合が違う: " + share + "(欲しい " + want + ")");
            for (int i = 0; i < 200; i++) {
                double[] p = FrontDirector.postForTests(level, bare, random);
                helper.assertTrue(Math.abs(p[0] - bare.x()) < 16.0D * (DesignData.front().postRadiusChunks + 2) && Math.abs(p[1] - bare.z()) < 16.0D * (DesignData.front().postRadiusChunks + 2), "縁の無い樹域が巨大樹のまわりに置かない");
            }
            helper.succeed();
        } finally {
            FrontDirector.clearCountsForTests();
        }
    }

    /** 11-22 ユーザー決定: 目標を超えた樹域の守りのうち超えた分だけを、rebalanceMaxBlocks の中のいちばん近い足りない樹域へ移す(数は減らさない)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void surplusDefendersMoveToShortTerritory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FrontDirector.clearCountsForTests();
        Fronts fronts = Fronts.load(new CompoundTag());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        try {
            var t = DesignData.front();
            double x0 = 9_300_000.5D;
            Fronts.Front a = fronts.addSourceForTests(x0, x0);
            Fronts.Front b = fronts.addSourceForTests(x0 + 400.0D, x0);
            Fronts.Front farShort = fronts.addSourceForTests(x0 - t.rebalanceMaxBlocks - 200.0D, x0);
            ResourceLocation type = ResourceLocation.withDefaultNamespace("zombie");
            VirtualHordes.Swarm s = h.newUnit(x0 + 100.0D, 64.0D, x0, a.id(), UnitPurpose.DEFEND, level.getGameTime());
            s.addHeads(type, 120, 10.0D);
            int targetA = Math.max(t.unitTargetMin, (int) Math.ceil(250 * t.unitTargetPerChunk));
            int targetB = Math.max(t.unitTargetMin, (int) Math.ceil(100 * t.unitTargetPerChunk));
            FrontDirector.setCountsForTests(a.id(), 250, 120);
            FrontDirector.setCountsForTests(b.id(), 100, 0);
            FrontDirector.setCountsForTests(farShort.id(), 2000, 0);
            long before = FrontDirector.rebalancedHeads;
            FrontDirector.rebalance(level, fronts, h, new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap());
            int inA = 0, inB = 0, inFar = 0;
            for (VirtualHordes.Swarm u : h.swarms()) {
                if (u.front() == a.id()) inA += u.size();
                else if (u.front() == b.id()) {
                    inB += u.size();
                    helper.assertTrue(u.purpose() == UnitPurpose.DEFEND && !u.arrived(), "移した部隊が守りの持ち場へ歩いていない");
                } else if (u.front() == farShort.id()) inFar += u.size();
            }
            int surplus = 120 - targetA, moved = Math.min(surplus, targetB);
            helper.assertTrue(inA + inB + inFar == 120, "移すと数が変わった: " + (inA + inB + inFar));
            helper.assertTrue(inB == moved && inA == 120 - moved, "超えた分だけ移していない: A " + inA + "・B " + inB + "(欲しい B " + moved + ")");
            helper.assertTrue(inFar == 0, "rebalanceMaxBlocks より遠い樹域へ移した");
            helper.assertTrue(FrontDirector.heads(a.id()) == 120 - moved && FrontDirector.heads(b.id()) == moved, "移した後の頭数の数え方が違う: " + FrontDirector.heads(a.id()));
            helper.assertTrue(FrontDirector.rebalancedHeads - before == moved, "移した頭数の記録が違う");
            // 目標ちょうどなら移さない
            FrontDirector.rebalance(level, fronts, h, new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap());
            helper.assertTrue(FrontDirector.rebalancedHeads - before == moved, "目標ちょうどなのにまた移した");
            helper.succeed();
        } finally {
            FrontDirector.clearCountsForTests();
        }
    }

    /** 11-22 ユーザー決定「内側は予備で駆けつける」: 外側の樹域が攻められると、縁の無い内側の樹域の予備が その縁へ向かい(下限は残す)、攻められなくなると戻る。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void innerReserveRushesToAttackedEdge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FrontDirector.clearCountsForTests();
        Fronts fronts = Fronts.load(new CompoundTag());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        try {
            var t = DesignData.front();
            double x0 = 9_500_000.5D;
            Fronts.Front outer = fronts.addSourceForTests(x0, x0);
            Fronts.Front inner = fronts.addSourceForTests(x0 + Math.min(400.0D, t.reinforceMaxBlocks - 50.0D), x0);
            Fronts.Front tooFar = fronts.addSourceForTests(x0 - t.reinforceMaxBlocks - 200.0D, x0);
            int ocx = 9_500_000 >> 4;
            long[] edge = new long[10];
            java.util.Set<Long> set = new java.util.HashSet<>();
            for (int i = 0; i < edge.length; i++) {
                edge[i] = net.minecraft.world.level.ChunkPos.asLong(ocx - 20, ocx - 5 + i);
                set.add(edge[i]);
            }
            FrontDirector.setEdgesForTests(outer.id(), edge);
            ResourceLocation type = ResourceLocation.withDefaultNamespace("zombie");
            VirtualHordes.Swarm home = h.newUnit(inner.x() + 20.0D, 64.0D, inner.z(), inner.id(), UnitPurpose.DEFEND, level.getGameTime());
            home.addHeads(type, 100, 10.0D);
            VirtualHordes.Swarm far = h.newUnit(tooFar.x() + 20.0D, 64.0D, tooFar.z(), tooFar.id(), UnitPurpose.DEFEND, level.getGameTime());
            far.addHeads(type, 100, 10.0D);
            long now = level.getGameTime();
            FrontDirector.markAttackedForTests(outer.id(), now);
            FrontDirector.reinforce(level, fronts, h, outer, new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap());
            int went = 0, stayed = 0, farMoved = 0;
            VirtualHordes.Swarm goer = null;
            for (VirtualHordes.Swarm u : h.swarms()) {
                if (u.front() == inner.id()) {
                    if (FrontDirector.reinforcing(u.id())) {
                        went += u.size();
                        goer = u;
                        helper.assertTrue(set.contains(net.minecraft.world.level.ChunkPos.asLong(net.minecraft.util.Mth.floor(u.tx()) >> 4, net.minecraft.util.Mth.floor(u.tz()) >> 4)), "駆けつけた予備が外側の縁へ向かっていない");
                    } else stayed += u.size();
                } else if (u.front() == tooFar.id() && u != far) farMoved += u.size();
            }
            int want = Math.min((int) Math.floor(100 * t.reinforceShare), 100 - t.reinforceKeepHeads);
            helper.assertTrue(went == want && stayed == 100 - want, "出した数が違う: " + went + "・残り " + stayed + "(欲しい " + want + ")");
            helper.assertTrue(stayed >= t.reinforceKeepHeads, "巨大樹のまわりに下限を残していない");
            helper.assertTrue(farMoved == 0 && far.size() == 100, "reinforceMaxBlocks より遠い予備が出た");
            // もう一度攻められても、出している分と合わせて割合まで
            FrontDirector.reinforce(level, fronts, h, outer, new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap());
            int went2 = 0;
            for (VirtualHordes.Swarm u : h.swarms()) if (u.front() == inner.id() && FrontDirector.reinforcing(u.id())) went2 += u.size();
            helper.assertTrue(went2 == want, "2 回目で割合を超えて出した: " + went2);
            // 攻められなくなってすぐは戻らない・reinforceReturnDays たつと戻る
            FrontDirector.returnReinforcements(level, fronts, h, now + 20L);
            helper.assertTrue(goer != null && FrontDirector.reinforcing(goer.id()), "攻められなくなってすぐ戻った");
            FrontDirector.returnReinforcements(level, fronts, h, now + (long) (t.reinforceReturnDays * 24000.0D) + 1L);
            helper.assertTrue(!FrontDirector.reinforcing(goer.id()), "戻らない");
            helper.assertTrue(Math.abs(goer.tx() - inner.x()) < 16.0D * (t.postRadiusChunks + 2) && Math.abs(goer.tz() - inner.z()) < 16.0D * (t.postRadiusChunks + 2), "元の巨大樹のまわりへ戻っていない");
            helper.succeed();
        } finally {
            FrontDirector.clearCountsForTests();
        }
    }

    /**
     * 11-22 ユーザー決定: 取り置き = 1 日の稼ぎ × reserveIncomeDays(半日分)。樹域の頭数が目標(過繁茂のチャンク数 × unitTargetPerChunk)に
     * 届いたら作らずに貯め、減ったらまた作る。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void productionStopsAtTerritoryTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        Double income = ForestEconomy.incomeOverride();
        double budget = d.available(level);
        Integer phase = DirectorGates.phaseOverride();
        Fronts fronts = Fronts.load(new CompoundTag());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        try {
            DirectorGates.setPhaseForTests(3);
            ForestEconomy.setIncomeForTests(1.0E7D);
            ForestUpkeep.setSampleForTests(0.0D, 0.0D);
            var t = DesignData.front();
            double want = Math.min(ForestDirector.incomePerSecond(level) * com.yayuy.overgrownvegetation.data.EconomyTable.SECONDS_PER_DAY * t.reserveIncomeDays,
                    ForestDirector.cap(level) * t.reserveCapMaxShare);
            helper.assertTrue(Math.abs(FrontDirector.reserve(level) - want) < 1.0E-6D * Math.max(1.0D, want), "取り置きが 半日分の稼ぎ・上限 × reserveCapMaxShare の小さい方でない: " + FrontDirector.reserve(level) + "(欲しい " + want + ")");
            d.setBudget(level, 1.0E12D);
            Fronts.Front f = fronts.addSourceForTests(9_000_000.5D, 9_000_000.5D);
            int chunks = 1000;
            FrontDirector.setCountsForTests(f.id(), chunks, 0);
            int target = FrontDirector.target(f.id());
            helper.assertTrue(target == Math.max(t.unitTargetMin, (int) Math.ceil(chunks * t.unitTargetPerChunk)), "目標の頭数が違う: " + target);
            for (int i = 0; i < 4000 && !FrontDirector.atTarget(f.id()); i++) FrontDirector.produce(level, fronts, h, f, 1.0D);
            int heads = 0;
            for (VirtualHordes.Swarm s : h.swarms()) heads += s.size();
            helper.assertTrue(heads == target, "目標で止まらない: " + heads + " 体(目標 " + target + ")");
            double before = d.available(level);
            for (int i = 0; i < 50; i++) FrontDirector.produce(level, fronts, h, f, 1.0D);
            int after = 0;
            for (VirtualHordes.Swarm s : h.swarms()) after += s.size();
            helper.assertTrue(after == heads && d.available(level) >= before, "目標に届いたのに作った: " + after);
            // 倒されて減った: また作る
            FrontDirector.setCountsForTests(f.id(), chunks, target - 5);
            for (int i = 0; i < 50; i++) FrontDirector.produce(level, fronts, h, f, 1.0D);
            int again = 0;
            for (VirtualHordes.Swarm s : h.swarms()) again += s.size();
            helper.assertTrue(again == heads + 5, "減った分を作り直さない: " + again);
            helper.succeed();
        } finally {
            FrontDirector.clearCountsForTests();
            ForestUpkeep.setSampleForTests(null, null);
            ForestEconomy.setIncomeForTests(income);
            DirectorGates.setPhaseForTests(phase);
            d.setBudget(level, budget);
        }
    }

    /**
     * 11-22 全体管理２: 取り置きは上限 × reserveCapMaxShare を超えない(稼ぎが上限に比べて大きくても、作るのが止まって抜けられなくならない)。
     * 稼ぎが小さいときは半日分のまま。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void reserveNeverExceedsCap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Double income = ForestEconomy.incomeOverride();
        try {
            var t = DesignData.front();
            double cap = ForestDirector.cap(level);
            ForestEconomy.setIncomeForTests(cap * 100.0D); // 1 日の稼ぎが上限の 100 倍
            double r = FrontDirector.reserve(level);
            helper.assertTrue(r <= cap * t.reserveCapMaxShare + 1.0E-6D, "取り置きが上限 × " + t.reserveCapMaxShare + " を超えた: " + r + " / 上限 " + cap);
            helper.assertTrue(r < cap, "取り置きが上限に届いた: " + r);
            ForestEconomy.setIncomeForTests(1200.0D); // 小さい稼ぎ: 半日分のまま
            double small = ForestDirector.incomePerSecond(level) * com.yayuy.overgrownvegetation.data.EconomyTable.SECONDS_PER_DAY * t.reserveIncomeDays;
            helper.assertTrue(small < cap * t.reserveCapMaxShare, "前提: 小さい稼ぎの半日分が上限の割合より小さい");
            helper.assertTrue(Math.abs(FrontDirector.reserve(level) - small) < 1.0E-6D, "稼ぎが小さいのに半日分にならない: " + FrontDirector.reserve(level));
            helper.succeed();
        } finally {
            ForestEconomy.setIncomeForTests(income);
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

    /** 11-22 ユーザー決定: 守りはふだん根付かない。攻められたときだけ根付き、根付いた個体は部隊から外れて前哨に残る(部隊が動いてもついていかない)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void defendersRootOnlyWhenAttacked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        Integer phase = DirectorGates.phaseOverride();
        var stage = DirectorGates.stageOverride();
        Fronts fronts = Fronts.load(new CompoundTag());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        var outposts = com.yayuy.overgrownvegetation.front.FrontOutposts.get(level.getServer());
        var keep = new ArrayList<>(outposts.posts());
        try {
            outposts.clearForTests();
            FrontDirector.clearCountsForTests();
            DirectorGates.setPhaseForTests(3);
            DirectorGates.setStageForTests(com.yayuy.overgrownvegetation.phase.PhaseStage.PEAK);
            ForestUpkeep.setSampleForTests(0.0D, 0.0D);
            d.setBudget(level, 1.0E9D);
            net.minecraft.world.entity.EntityType<?> rt = com.yayuy.overgrownvegetation.director.G1Fate.rootedTypeOf(G1Species.ZOMBIE);
            if (rt == null) {
                helper.succeed(); // ゾンビの根付きの姿が無い構成
                return;
            }
            Fronts.Front f = fronts.addSourceForTests(42_000_000.5D, 42_000_000.5D);
            VirtualHordes.Swarm s = h.newUnit(42_000_100, 64, 42_000_000, f.id(), UnitPurpose.DEFEND, level.getGameTime()); // 持ち場(前線の真ん中から離れた所)
            s.addHeads(zombie(), 10, 100.0D);
            FrontDirector.assignForTests(level, h, f, 0.0D);
            helper.assertTrue(outposts.posts().isEmpty(), "攻められていないのに根付いた: " + outposts.posts().size());
            FrontDirector.assignForTests(level, h, f, 1.0E9D);
            int n = outposts.posts().size();
            helper.assertTrue(n == DesignData.front().rootedPerUnit, "攻められたのに根付かない(または多すぎる): " + n);
            ResourceLocation rid = BuiltInRegistries.ENTITY_TYPE.getKey(rt);
            helper.assertTrue(s.counts().getOrDefault(rid, 0) == 0, "根付いた姿が部隊の中に残っている: " + s.counts());
            var p = outposts.posts().get(0);
            helper.assertTrue(p.kind() == com.yayuy.overgrownvegetation.front.FrontOutposts.Kind.DEFEND && p.type().equals(rid), "前哨の記録が違う");
            helper.assertTrue(Math.abs(p.x() - 42_000_100) <= 6 && Math.abs(p.z() - 42_000_000) <= 6, "部隊の居る所に残っていない");
            s.assign(UnitPurpose.DEFEND, 42_000_900.0D, 42_000_000.0D, level.getGameTime());
            helper.assertTrue(outposts.posts().size() == n && outposts.posts().get(0).x() == p.x(), "部隊が動くと前哨もついていった");
            helper.succeed();
        } finally {
            outposts.clearForTests();
            outposts.posts().addAll(keep);
            FrontDirector.clearCountsForTests();
            ForestUpkeep.setSampleForTests(null, null);
            DirectorGates.setPhaseForTests(phase);
            DirectorGates.setStageForTests(stage);
            d.setBudget(level, budget);
        }
    }

    /** 11-22 ユーザー決定: 広げる部隊が巨大樹から遠い升に着いたら、行進の間に取り込んだ生き物を根付きの前哨に変える(近い升では変えない)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void expandersLeaveRootedOutposts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        Integer phase = DirectorGates.phaseOverride();
        var stage = DirectorGates.stageOverride();
        Fronts fronts = Fronts.load(new CompoundTag());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        var outposts = com.yayuy.overgrownvegetation.front.FrontOutposts.get(level.getServer());
        var keep = new ArrayList<>(outposts.posts());
        try {
            outposts.clearForTests();
            DirectorGates.setPhaseForTests(3);
            DirectorGates.setStageForTests(com.yayuy.overgrownvegetation.phase.PhaseStage.PEAK);
            ForestUpkeep.setSampleForTests(0.0D, 0.0D);
            d.setBudget(level, 1.0E9D);
            var t = DesignData.front();
            G1Species sp = com.yayuy.overgrownvegetation.director.G1Fate.rootedTypeOf(G1Species.COW) != null ? G1Species.COW
                    : com.yayuy.overgrownvegetation.director.G1Fate.rootedTypeOf(G1Species.ZOMBIE) != null ? G1Species.ZOMBIE : null;
            if (sp == null) {
                helper.succeed(); // 根付きの姿が無い構成
                return;
            }
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(sp.entityType());
            double x0 = 43_000_000.5D;
            Fronts.Front f = fronts.addSourceForTests(x0, x0);
            int far = (int) Math.ceil(t.outpostMinTreeBlocks / 64.0D) + 1;
            int fcx = (43_000_000 >> 4) >> 2, fcz = fcx;
            VirtualHordes.Swarm s = h.newUnit(x0, 64, x0, f.id(), UnitPurpose.EXPAND, level.getGameTime());
            s.addHeads(id, t.outpostAfterInfected, 0.0D);
            com.yayuy.overgrownvegetation.front.FrontOutposts.noteInfected(s.id(), t.outpostAfterInfected - 1);
            helper.assertTrue(outposts.tryOutpost(level, s, f, fcx + far, fcz) == 0, "取り込んだ数が足りないのに根付いた");
            com.yayuy.overgrownvegetation.front.FrontOutposts.noteInfected(s.id(), 1);
            helper.assertTrue(outposts.tryOutpost(level, s, f, fcx + 1, fcz) == 0, "巨大樹に近い升で根付いた");
            int done = outposts.tryOutpost(level, s, f, fcx + far, fcz);
            int want = Math.min(t.outpostMaxPerCell, t.outpostAfterInfected);
            helper.assertTrue(done == want && outposts.posts().size() == want, "遠い升で前哨にした数が違う: " + done + "(欲しい " + want + ")");
            helper.assertTrue(outposts.posts().get(0).kind() == com.yayuy.overgrownvegetation.front.FrontOutposts.Kind.EXPAND, "広げる部隊の前哨の印が違う");
            helper.assertTrue(s.counts().getOrDefault(id, 0) == t.outpostAfterInfected - want, "取り込んだ生き物が部隊から抜けていない: " + s.counts());
            com.yayuy.overgrownvegetation.front.FrontOutposts.noteInfected(s.id(), t.outpostAfterInfected);
            helper.assertTrue(outposts.tryOutpost(level, s, f, fcx + far, fcz) == 0, "升 1 つの上限を超えて根付いた");
            helper.succeed();
        } finally {
            outposts.clearForTests();
            outposts.posts().addAll(keep);
            ForestUpkeep.setSampleForTests(null, null);
            DirectorGates.setPhaseForTests(phase);
            DirectorGates.setStageForTests(stage);
            d.setBudget(level, budget);
        }
    }

    /** 11-22 ユーザー決定: 森に飲み込まれた(まわり outpostWitherDepthBlocks が全部森の)前哨は枯れ、値段の outpostRefundShare を森の稼ぎへ返す。縁の前哨は残る。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void swallowedOutpostsWitherAndRefund(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        var outposts = com.yayuy.overgrownvegetation.front.FrontOutposts.get(level.getServer());
        var keep = new ArrayList<>(outposts.posts());
        PhaseData pd = PhaseData.get(level);
        List<net.minecraft.world.level.ChunkPos> marked = new ArrayList<>();
        try {
            outposts.clearForTests();
            var t = DesignData.front();
            int r = (int) Math.ceil(t.outpostWitherDepthBlocks / 16.0D) + 1;
            int c0 = 44_000_000 >> 4;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    var cp = new net.minecraft.world.level.ChunkPos(c0 + dx, c0 + dz);
                    if (!pd.isOvergrownChunk(cp) && pd.markOvergrownChunk(level, cp)) marked.add(cp);
                }
            }
            ResourceLocation type = zombie();
            var EXPAND = com.yayuy.overgrownvegetation.front.FrontOutposts.Kind.EXPAND;
            var DEFEND = com.yayuy.overgrownvegetation.front.FrontOutposts.Kind.DEFEND;
            long now = level.getGameTime();
            long wait = (long) (t.outpostDefendWitherDays * 24000.0D);
            int fid = 770_001;
            outposts.addForTests(44_000_008, 44_000_008, 0, type, 1000.0D, EXPAND, now); // 森の真ん中
            outposts.addForTests((c0 + r) * 16 + 8, 44_000_008, 0, type, 1000.0D, EXPAND, now); // 森の縁
            // 11-22 見直し: 守りの根付きは森の真ん中でも、樹域が最近攻められていれば枯れない
            outposts.addForTests(44_000_010, 44_000_010, fid, type, 800.0D, DEFEND, now - wait - 10);
            FrontDirector.markAttackedForTests(fid, now);
            d.setBudget(level, 0.0D);
            long before = com.yayuy.overgrownvegetation.front.FrontOutposts.withered;
            double back = outposts.witherSwallowed(level);
            helper.assertTrue(com.yayuy.overgrownvegetation.front.FrontOutposts.withered - before == 1 && outposts.posts().size() == 2, "飲み込まれた広げる前哨だけが枯れていない: 残り " + outposts.posts().size());
            helper.assertTrue(outposts.posts().stream().anyMatch(p -> p.kind() == DEFEND), "守りの根付きが森の奥ですぐ枯れた");
            helper.assertTrue(Math.abs(back - 1000.0D * t.outpostRefundShare) < 1.0E-6D, "返した額が違う: " + back);
            helper.assertTrue(d.available(level) >= back - 1.0E-6D, "森の稼ぎへ返っていない: " + d.available(level));
            // 攻められなくなって outpostDefendWitherDays たった: 守りの根付きも枯れて半分を返す
            FrontDirector.markAttackedForTests(fid, now - wait - 1);
            double back2 = outposts.witherSwallowed(level);
            helper.assertTrue(outposts.posts().size() == 1 && outposts.posts().get(0).kind() == EXPAND, "攻められなくなった守りの根付きが枯れない: 残り " + outposts.posts().size());
            helper.assertTrue(Math.abs(back2 - 800.0D * t.outpostRefundShare) < 1.0E-6D, "守りの根付きの返した額が違う: " + back2);
            var again = com.yayuy.overgrownvegetation.front.FrontOutposts.load(outposts.save(new CompoundTag(), level.registryAccess()));
            helper.assertTrue(again.posts().size() == 1 && again.posts().get(0).x() == (c0 + r) * 16 + 8, "読み直すと消える");
            helper.succeed();
        } finally {
            for (var cp : marked) pd.forgetOvergrownChunk(cp);
            outposts.clearForTests();
            outposts.posts().addAll(keep);
            FrontDirector.clearCountsForTests();
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

    /** 11-21 夜: 胞子で森の縁が expandSearchChunks より遠くへ出ても、上限 expandSearchMaxChunks まで探し広げて、いちばん近い森の縁の升へ向かう。ほかが向かっている升は選ばない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void expandSearchWidensBeyondForestEdge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        com.yayuy.overgrownvegetation.phase.PhaseData pd = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
        int x = 27_000_000, z = 27_000_000;
        int fcx = (x >> 4) >> 2, fcz = (z >> 4) >> 2;
        int edge = 11; // 森は前線から 10 升(40 チャンク)。縁の外は 11 升 = 44 チャンク > 24
        Fronts.Front f = fronts.addSourceForTests(x + 0.5D, z + 0.5D);
        java.util.List<net.minecraft.world.level.ChunkPos> marked = new java.util.ArrayList<>();
        try {
            com.yayuy.overgrownvegetation.front.UnitTerrain.terrainForTests = (cx, cz) -> com.yayuy.overgrownvegetation.front.UnitTerrain.LAND;
            CellLife.register(new CellLife() {
                @Override
                public Result infect(ServerLevel l, int cellX, int cellZ, int attackers, double seconds) {
                    return new Result(0, 0, 0.0D, false);
                }

                @Override
                public View view(ServerLevel l, int cellX, int cellZ) {
                    return new View(20, 3, false);
                }
            });
            for (int dx = -(edge - 1); dx <= edge - 1; dx++) {
                for (int dz = -(edge - 1); dz <= edge - 1; dz++) {
                    net.minecraft.world.level.ChunkPos c = new net.minecraft.world.level.ChunkPos((fcx + dx) * 4 + 2, (fcz + dz) * 4 + 2);
                    pd.markOvergrownChunk(level, c);
                    marked.add(c);
                }
            }
            helper.assertTrue(DesignData.front().expandSearchMaxChunks / 4 > edge, "テストの前提: 上限が縁より狭い");
            long t1 = FrontDirector.expandTargetForTests(level, h, f);
            helper.assertTrue(t1 != Long.MIN_VALUE, "24 チャンクの外の森の縁を探さない");
            int r1 = Math.max(Math.abs((int) (t1 >> 32) - fcx), Math.abs((int) t1 - fcz));
            helper.assertTrue(r1 == edge, "いちばん近い森の縁の升でない: " + r1 + " 升");
            // その升へ 1 部隊が向かったら、次はほかの升
            VirtualHordes.Swarm s = h.newUnit(x, 64, z, f.id(), UnitPurpose.EXPAND, level.getGameTime());
            s.addHeads(zombie(), 10, 100.0D);
            s.assign(UnitPurpose.EXPAND, ((int) (t1 >> 32) * 4 + 2) * 16.0D + 8.0D, ((int) t1 * 4 + 2) * 16.0D + 8.0D, level.getGameTime());
            long t2 = FrontDirector.expandTargetForTests(level, h, f);
            helper.assertTrue(t2 != Long.MIN_VALUE && t2 != t1, "ほかの部隊が向かっている升を選んだ");
            helper.succeed();
        } finally {
            com.yayuy.overgrownvegetation.front.UnitTerrain.terrainForTests = null;
            CellLife.register(null);
            for (net.minecraft.world.level.ChunkPos c : marked) pd.forgetOvergrownChunk(c);
            fronts.fronts().remove(f);
        }
    }

    /** 11-22 見直し: 樹域の数え直しの「いちばん近い樹域」は、升の索引で引いても総当たりと同じ(同じ距離は一覧の前の方)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void territoryIndexMatchesBruteForce(GameTestHelper helper) {
        java.util.Random rnd = new java.util.Random(12345L);
        for (int round = 0; round < 6; round++) {
            int n = round == 0 ? 1 : round * 40;
            double spread = round % 2 == 0 ? 30_000.0D : 3_000.0D;
            double[] xs = new double[n], zs = new double[n];
            for (int i = 0; i < n; i++) {
                if (i > 0 && rnd.nextInt(8) == 0) { // 同じ所の樹域(同じ距離)
                    xs[i] = xs[i - 1];
                    zs[i] = zs[i - 1];
                } else {
                    xs[i] = Math.floor((rnd.nextDouble() - 0.5D) * spread) + 0.5D;
                    zs[i] = Math.floor((rnd.nextDouble() - 0.5D) * spread) + 0.5D;
                }
            }
            FrontDirector.NearestIndex index = new FrontDirector.NearestIndex(xs, zs);
            for (int q = 0; q < 5000; q++) {
                double x = (rnd.nextInt(8000) - 4000) * 16.0D + 8.0D, z = (rnd.nextInt(8000) - 4000) * 16.0D + 8.0D; // 遠い所も
                if (q % 2 == 0) {
                    x = Math.floor(((rnd.nextDouble() - 0.5D) * spread) / 16.0D) * 16.0D + 8.0D;
                    z = Math.floor(((rnd.nextDouble() - 0.5D) * spread) / 16.0D) * 16.0D + 8.0D;
                }
                int a = index.nearest(x, z), b = FrontDirector.NearestIndex.brute(xs, zs, x, z);
                if (a != b) {
                    helper.fail("索引と総当たりが違う: 樹域 " + n + "・(" + x + ", " + z + ") 索引 " + a + " 総当たり " + b);
                    return;
                }
            }
        }
        helper.succeed();
    }

    /** 11-22 見直し・ユーザー決定: 前哨と前線のフィロセンスは、実物が立っていなくても いつも維持費に数える。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void outpostsAndWatchersAlwaysPayUpkeep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var outposts = com.yayuy.overgrownvegetation.front.FrontOutposts.get(level.getServer());
        var watchers = com.yayuy.overgrownvegetation.front.FrontWatchers.get(level.getServer());
        var keepO = new ArrayList<>(outposts.posts());
        var keepW = new ArrayList<>(watchers.sites());
        try {
            outposts.clearForTests();
            watchers.sites().clear();
            ForestUpkeep.invalidate();
            double before = ForestUpkeep.priceInForest(level) + ForestUpkeep.priceOutside(level);
            outposts.addForTests(45_000_008, 45_000_008, 0, zombie(), 1234.0D); // 読み込まれていない所(記録だけ)
            watchers.addForTests(45_000_200, 45_000_008, 0);
            ForestUpkeep.invalidate();
            double after = ForestUpkeep.priceInForest(level) + ForestUpkeep.priceOutside(level);
            double want = 1234.0D + com.yayuy.overgrownvegetation.front.FrontWatchers.price();
            helper.assertTrue(Math.abs(after - before - want) < 1.0E-6D, "前哨・フィロセンスの記録が維持費に入らない: 差 " + (after - before) + "(欲しい " + want + ")");
            helper.succeed();
        } finally {
            outposts.clearForTests();
            outposts.posts().addAll(keepO);
            watchers.sites().clear();
            watchers.sites().addAll(keepW);
            ForestUpkeep.invalidate();
        }
    }

    /** 11-22 ユーザー決定: 全体の頭数が目標の合計の unitGlobalCapShare 倍を超えている間は、目標に届いていない樹域も作らない。下がれば作る。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void globalHeadCapStopsAllProduction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        Double income = ForestEconomy.incomeOverride();
        double budget = d.available(level);
        Integer phase = DirectorGates.phaseOverride();
        Fronts fronts = Fronts.load(new CompoundTag());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        try {
            var t = DesignData.front();
            if (t.unitGlobalCapShare <= 0.0D) {
                helper.succeed(); // 全体の上限を切った構成
                return;
            }
            DirectorGates.setPhaseForTests(3);
            ForestEconomy.setIncomeForTests(1.0E7D);
            ForestUpkeep.setSampleForTests(0.0D, 0.0D);
            d.setBudget(level, 1.0E12D);
            Fronts.Front a = fronts.addSourceForTests(46_000_000.5D, 46_000_000.5D);
            Fronts.Front b = fronts.addSourceForTests(46_000_300.5D, 46_000_000.5D);
            int chunks = 1000;
            FrontDirector.setCountsForTests(a.id(), chunks, 0);
            FrontDirector.setCountsForTests(b.id(), chunks, 0);
            int target = FrontDirector.target(a.id());
            int over = (int) Math.ceil(2 * target * t.unitGlobalCapShare) + 10; // a だけで全体の上限を超える
            FrontDirector.setCountsForTests(a.id(), chunks, over);
            long skips = FrontDirector.globalCapSkips;
            for (int i = 0; i < 30; i++) FrontDirector.produce(level, fronts, h, b, 1.0D);
            int made = 0;
            for (VirtualHordes.Swarm s : h.swarms()) made += s.size();
            helper.assertTrue(made == 0 && FrontDirector.globalCapSkips > skips, "全体の上限を超えているのに作った: " + made);
            helper.assertTrue(FrontDirector.overGlobalCap(fronts), "全体の割合の判定が違う: " + FrontDirector.globalRatio(fronts));
            FrontDirector.setCountsForTests(a.id(), chunks, target); // 全体 = 目標の半分
            for (int i = 0; i < 30; i++) FrontDirector.produce(level, fronts, h, b, 1.0D);
            made = 0;
            for (VirtualHordes.Swarm s : h.swarms()) made += s.size();
            helper.assertTrue(made > 0, "全体の上限の下なのに足りない樹域が作らない");
            helper.succeed();
        } finally {
            FrontDirector.clearCountsForTests();
            ForestUpkeep.setSampleForTests(null, null);
            ForestEconomy.setIncomeForTests(income);
            DirectorGates.setPhaseForTests(phase);
            d.setBudget(level, budget);
        }
    }

    /** 11-22 ユーザー決定: 広げる部隊が感染させた分は、まず rebalanceMaxBlocks の中の目標に届いていない樹域へ回し、無ければ今の部隊に入れる。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void infectedHeadsGoToShortTerritory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fronts fronts = Fronts.load(new CompoundTag());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        try {
            var t = DesignData.front();
            double x0 = 47_000_000.5D;
            Fronts.Front a = fronts.addSourceForTests(x0, x0);
            Fronts.Front b = fronts.addSourceForTests(x0 + 300.0D, x0); // 近い・足りない
            Fronts.Front far = fronts.addSourceForTests(x0 + t.rebalanceMaxBlocks + 2000.0D, x0); // 遠い・足りない
            int chunks = 1000;
            FrontDirector.setCountsForTests(a.id(), chunks, 0);
            int target = FrontDirector.target(a.id());
            FrontDirector.setCountsForTests(a.id(), chunks, target); // 自分は届いている
            FrontDirector.setCountsForTests(b.id(), chunks, target - 5);
            FrontDirector.setCountsForTests(far.id(), chunks, 0);
            VirtualHordes.Swarm s = h.newUnit(x0 + 100.0D, 64, x0, a.id(), UnitPurpose.EXPAND, level.getGameTime());
            s.addHeads(zombie(), 10, 0.0D);
            List<ResourceLocation> got = new ArrayList<>();
            for (int i = 0; i < 8; i++) got.add(zombie());
            int kept = FrontDirector.routeInfectedForTests(level, fronts, h, s, got);
            int toB = 0, toFar = 0;
            for (VirtualHordes.Swarm u : h.swarms()) {
                if (u == s) continue;
                if (u.front() == b.id() && u.purpose() == UnitPurpose.DEFEND) toB += u.size();
                if (u.front() == far.id()) toFar += u.size();
            }
            helper.assertTrue(toB == 5 && toFar == 0, "足りない樹域へ回した数が違う: 近い " + toB + "・遠い " + toFar);
            helper.assertTrue(kept == 3 && s.size() == 13, "残りが今の部隊に入らない: 残した " + kept + "・部隊 " + s.size());
            helper.assertTrue(FrontDirector.heads(b.id()) == target && FrontDirector.heads(a.id()) == target + 3, "頭数の数えが違う");
            // 近くに足りない樹域が無い: 全部いまの部隊へ
            List<ResourceLocation> more = new ArrayList<>();
            for (int i = 0; i < 4; i++) more.add(zombie());
            int kept2 = FrontDirector.routeInfectedForTests(level, fronts, h, s, more);
            helper.assertTrue(kept2 == 4 && s.size() == 17, "足りない樹域が無いのに今の部隊に入らない: " + kept2 + "・部隊 " + s.size());
            helper.succeed();
        } finally {
            FrontDirector.clearCountsForTests();
        }
    }
}
