package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import com.yayuy.overgrownvegetation.front.FrontTrees;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 前線と部隊 区切り 4(2026-11-21): 巨大樹を予算で建てる・足りない分を Mob で補う・育ち途中のノードを倒すと止まる・倒された跡を建て直す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FrontTreeGameTests {

    private static final String BATCH = "front_trees";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FrontTreeGameTests.class);
        }

        private Registration() {
        }
    }

    private FrontTreeGameTests() {
    }

    /** 巨大樹の無い過繁茂のかたまり(64 チャンク以上)に建てる所ができ、予算で払えればすぐ育ち始める。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void clusterGetsSiteFundedByBudget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        FrontTrees trees = FrontTrees.load(new CompoundTag());
        int cell = DesignData.front().treeCellChunks;
        int base = Math.floorDiv(20_000_000 >> 4, cell) * cell; // 升の角
        List<ChunkPos> added = new ArrayList<>();
        try {
            for (int i = 0; i < DesignData.front().treeClusterChunks + 6; i++) added.add(new ChunkPos(base + i % cell, base + i / cell));
            pd.markOvergrownChunks(level, added);
            d.setBudget(level, DesignData.front().treeCost * 2.0D);
            trees.findSites(level);
            FrontTrees.Site s = trees.sites().stream().filter(x -> Math.abs((x.x() >> 4) - base - cell / 2) < cell && Math.abs((x.z() >> 4) - base - cell / 2) < cell)
                    .findFirst().orElse(null);
            helper.assertTrue(s != null, "かたまりに建てる所ができない");
            trees.allowanceForTests(DesignData.front().treeCost); // 稼ぎの 2 割ずつ貯まる額を 1 本分にしておく
            trees.tick(level);
            helper.assertTrue(s.remaining() <= 1.0E-6D, "予算があるのに払っていない: 残り " + s.remaining());
            helper.assertTrue(s.state() == FrontTrees.State.GROWING, "払い終えても育ち始めない: " + s.state());
            helper.succeed();
        } finally {
            for (ChunkPos c : added) pd.forgetOvergrownChunk(c);
            d.setBudget(level, budget);
        }
    }

    /**
     * 11-22 ユーザー決定「巨大樹の場所は 300〜500 間隔でかなり揺れる」: 半径は場所から決まり(同じ場所なら同じ・保存と読み込みで変わらない)、
     * 2 本の間は半径の和(treeSpacingMinBlocks 未満にならない・treeSpacingMaxBlocks を超えない)。揺れは広く散る。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void treeSpacingVariesButNeverUnderMin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        var t = DesignData.front();
        long seed = level.getSeed();
        double lo = Double.MAX_VALUE, hi = 0.0D;
        java.util.Random rnd = new java.util.Random(7L);
        for (int i = 0; i < 2000; i++) {
            int x = rnd.nextInt(2_000_000) - 1_000_000, z = rnd.nextInt(2_000_000) - 1_000_000;
            double r = FrontTrees.spacingRadius(seed, x, z);
            helper.assertTrue(r == FrontTrees.spacingRadius(seed, x, z), "同じ場所で半径が変わる");
            helper.assertTrue(r >= t.treeSpacingMinBlocks * 0.5D - 1.0E-9D && r <= t.treeSpacingMaxBlocks * 0.5D + 1.0E-9D, "半径が範囲の外: " + r);
            lo = Math.min(lo, r);
            hi = Math.max(hi, r);
        }
        helper.assertTrue(hi - lo > (t.treeSpacingMaxBlocks - t.treeSpacingMinBlocks) * 0.5D * 0.8D, "半径があまり揺れない: " + lo + "〜" + hi);
        FrontTrees trees = FrontTrees.load(new CompoundTag());
        int x0 = 22_000_000, z0 = 22_000_000;
        trees.addSite(level, x0, z0, Long.MIN_VALUE);
        double r0 = FrontTrees.spacingRadius(seed, x0, z0);
        // 東へ 1 ブロックずつ: 半径の和より近ければ近すぎ、遠ければ空いている。どちらも 300〜500 の中
        int firstFree = -1;
        for (int d = 250; d <= 560; d++) {
            boolean close = trees.tooClose(level, pd, x0 + d, z0);
            double sum = r0 + FrontTrees.spacingRadius(seed, x0 + d, z0);
            helper.assertTrue(close == (d < sum), "近すぎの判定が半径の和と合わない: 間 " + d + "・和 " + sum);
            if (d < t.treeSpacingMinBlocks) helper.assertTrue(close, "300 ブロック未満なのに置ける: " + d);
            if (d >= t.treeSpacingMaxBlocks) helper.assertTrue(!close, "500 ブロック以上なのに近すぎ: " + d);
            if (!close && firstFree < 0) firstFree = d;
        }
        helper.assertTrue(firstFree >= t.treeSpacingMinBlocks, "300 ブロック未満で空いた: " + firstFree);
        // 保存して読み直しても同じ判定
        FrontTrees again = FrontTrees.load(trees.save(new CompoundTag(), level.registryAccess()));
        for (int d = 250; d <= 560; d += 7) {
            helper.assertTrue(again.tooClose(level, pd, x0 + d, z0) == trees.tooClose(level, pd, x0 + d, z0), "読み直すと間隔が変わる: " + d);
        }
        helper.succeed();
    }

    /** 予算が足りなければ、前線の部隊を建てる所へ集めて使う(使った分の頭数は消える)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void shortBudgetUsesMobs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.get(level.getServer());
        FrontTrees trees = FrontTrees.load(new CompoundTag());
        int x = 21_000_000, z = 21_000_000;
        Fronts.Front f = fronts.addSourceForTests(x + 0.5D, z + 0.5D);
        try {
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            d.setBudget(level, 0.0D);
            VirtualHordes.Swarm u = h.newUnit(x + 30, 64, z, f.id(), UnitPurpose.DEFEND, level.getGameTime());
            u.addHeads(BuiltInRegistries.ENTITY_TYPE.getKey(G1Species.ZOMBIE.entityType()), 500, 100.0D);
            FrontTrees.Site s = trees.addSite(level, x + 100, z, Long.MIN_VALUE);
            double cost = s.remaining();
            int before = total(h, f.id());
            trees.tick(level); // 建てる部隊を呼ぶ
            int building = 0;
            for (VirtualHordes.Swarm w : h.swarms()) if (w.front() == f.id() && w.purpose() == UnitPurpose.BUILD) building += w.size();
            int perHead = (int) DesignData.director().largeRaid.costStageZero;
            helper.assertTrue(building >= Math.ceil(cost / perHead) - 1, "足りない分の Mob を呼ばない: " + building + "(値段 " + cost + ")");
            for (int i = 0; i < 60; i++) h.move(level, 100, DesignData.perf());
            trees.tick(level); // 着いて使う
            helper.assertTrue(s.state() == FrontTrees.State.GROWING, "Mob で補っても育ち始めない: " + s.state() + " 残り " + s.remaining());
            int used = before - total(h, f.id());
            helper.assertTrue(Math.abs(used * perHead - cost) < perHead + 1.0E-6D, "使った頭数が値段に合わない: " + used + " 体");
            helper.succeed();
        } finally {
            UnitTerrain.terrainForTests = null;
            d.setBudget(level, budget);
            for (VirtualHordes.Swarm w : new ArrayList<>(h.swarms())) if (w.front() == f.id()) h.removeUnit(w);
            fronts.fronts().remove(f);
        }
    }

    private static int total(VirtualHordes h, int front) {
        int n = 0;
        for (VirtualHordes.Swarm w : h.swarms()) if (w.front() == front) n += w.size();
        return n;
    }

    /** 育ち途中のハイヴノードを倒すと、その建てる所は止まる。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void killingGrowingNodeStopsSite(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        FrontTrees trees = FrontTrees.get(level.getServer());
        BlockPos at = helper.absolutePos(new BlockPos(3, 1, 3));
        try {
            d.setBudget(level, DesignData.front().treeCost * 2.0D);
            FrontTrees.Site s = trees.addSite(level, at.getX(), at.getZ(), Long.MIN_VALUE);
            trees.allowanceForTests(DesignData.front().treeCost);
            trees.tick(level); // 育ち始める
            trees.tick(level); // 育ち途中のノードを置く
            HiveNodeEntity node = null;
            for (Entity e : level.getAllEntities()) {
                if (e instanceof HiveNodeEntity n && n.getPersistentData().getLong(FrontTrees.SITE_KEY) == s.id()) node = n;
            }
            helper.assertTrue(node != null, "育ち途中のハイヴノードが置かれない");
            node.kill();
            helper.assertTrue(!trees.sites().contains(s), "育ち途中のノードを倒しても止まらない");
            helper.succeed();
        } finally {
            d.setBudget(level, budget);
            trees.clearForTests();
            for (Entity e : level.getAllEntities()) if (e instanceof HiveNodeEntity n && n.getPersistentData().contains(FrontTrees.SITE_KEY)) n.discard();
        }
    }

    /** 倒された巨大樹の跡も建て直す。育ち切ると止まった印が外れる(育ち途中のノードが本物のノードになる)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void deadTreeIsRebuilt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        FrontTrees trees = FrontTrees.load(new CompoundTag());
        BlockPos at = helper.absolutePos(new BlockPos(3, 1, 3));
        long key = ((long) at.getX() << 32) | (at.getZ() & 0xFFFFFFFFL);
        try {
            pd.recordGiantTreeOrigin(at.getX(), at.getZ());
            pd.killGiantTreeNear(at, 2.0D);
            helper.assertTrue(pd.isGiantTreeDead(key), "前提: 巨大樹が止まっていない");
            d.setBudget(level, DesignData.front().treeCost * 2.0D);
            trees.findSites(level);
            FrontTrees.Site s = trees.sites().stream().filter(FrontTrees.Site::rebuild).filter(x -> x.x() == at.getX() && x.z() == at.getZ()).findFirst().orElse(null);
            helper.assertTrue(s != null, "倒された跡に建て直す所ができない");
            trees.allowanceForTests(DesignData.front().treeCost);
            trees.tick(level);
            trees.readyForTests(s);
            trees.tick(level);
            helper.assertTrue(!pd.isGiantTreeDead(key), "育ち切っても建て直されない(止まった印が残った)");
            helper.succeed();
        } finally {
            d.setBudget(level, budget);
            pd.forgetGiantTreeForTests(key);
            for (Entity e : level.getAllEntities()) {
                if (e instanceof HiveNodeEntity n && n.distanceToSqr(at.getX(), at.getY(), at.getZ()) < 64 * 64) {
                    pd.removeHiveNode(n.blockPosition());
                    n.discard();
                }
            }
        }
    }

    /** 巨大樹に払うのは毎秒 稼ぎの treeIncomeShare まで・前線ごとに古い 1 か所だけ(11-21 放置 1 回目: 巨大樹が予算を全部使って部隊を作れなかった)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void treeSpendIsCappedAndOneSitePerFront(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        Fronts fronts = Fronts.get(level.getServer());
        FrontTrees trees = FrontTrees.load(new CompoundTag());
        int x = 31_000_000, z = 31_000_000;
        Fronts.Front f = fronts.addSourceForTests(x + 0.5D, z + 0.5D);
        try {
            d.setBudget(level, 1.0E6D);
            double cost = DesignData.front().treeCost;
            FrontTrees.Site s1 = trees.addSite(level, x + 100, z, Long.MIN_VALUE);
            FrontTrees.Site s2 = trees.addSite(level, x - 100, z, Long.MIN_VALUE);
            helper.assertTrue(s1.remaining() == cost && d.available(level) == 1.0E6D, "建てる所を足しただけで払った");
            trees.allowanceForTests(cost * 0.25D);
            double cap = cost * 0.25D + DesignData.front().treeIncomeShare * ForestDirector.incomePerSecond(level) + 1.0E-6D;
            trees.tick(level);
            double paid = 1.0E6D - d.available(level);
            helper.assertTrue(paid > 0.0D && paid <= cap, "払った額が稼ぎの割合を超えた: " + paid + "(上限 " + cap + ")");
            helper.assertTrue(Math.abs((cost - s1.remaining()) - paid) < 1.0E-6D, "古い所に払っていない: 残り " + s1.remaining());
            helper.assertTrue(s2.remaining() == cost, "同じ前線で 2 か所に払った: 残り " + s2.remaining());
            helper.succeed();
        } finally {
            d.setBudget(level, budget);
            fronts.fronts().remove(f);
        }
    }

    /** 建てる部隊を出しても前線の守りを unitMin より減らさない(11-21 放置 1 回目で守りが 244 → 0)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void buildersKeepDefenders(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.get(level.getServer());
        FrontTrees trees = FrontTrees.load(new CompoundTag());
        int x = 33_000_000, z = 33_000_000;
        Fronts.Front f = fronts.addSourceForTests(x + 0.5D, z + 0.5D);
        try {
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            d.setBudget(level, 0.0D);
            int min = DesignData.front().unitMin;
            VirtualHordes.Swarm u = h.newUnit(x + 30, 64, z, f.id(), UnitPurpose.DEFEND, level.getGameTime());
            u.addHeads(BuiltInRegistries.ENTITY_TYPE.getKey(G1Species.ZOMBIE.entityType()), min + 3, 100.0D);
            trees.addSite(level, x + 100, z, Long.MIN_VALUE); // 値段は頭数の合計より高い
            for (int i = 0; i < 5; i++) {
                trees.tick(level);
                for (int k = 0; k < 20; k++) h.move(level, 100, DesignData.perf());
            }
            int defend = 0;
            for (VirtualHordes.Swarm w : h.swarms()) if (w.front() == f.id() && w.purpose() == UnitPurpose.DEFEND) defend += w.size();
            helper.assertTrue(defend >= min, "建てる部隊に抜かれて守りが unitMin を割った: " + defend);
            helper.succeed();
        } finally {
            UnitTerrain.terrainForTests = null;
            d.setBudget(level, budget);
            for (VirtualHordes.Swarm w : new ArrayList<>(h.swarms())) if (w.front() == f.id()) h.removeUnit(w);
            fronts.fronts().remove(f);
        }
    }

    /** 育ち切って読み込まれていない巨大樹も前線・巨大樹の稼ぎ・管理された森になる(11-21 放置 1 回目: 置かれず前線が 1 つのままだった)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void pendingTreeCountsAsTree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        boolean ticking = com.yayuy.overgrownvegetation.front.FrontDirector.tickInGameTests;
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.get(level.getServer());
        FrontTrees trees = FrontTrees.get(level.getServer());
        int x = 35_000_000, z = 35_000_000;
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        try {
            com.yayuy.overgrownvegetation.front.FrontDirector.tickInGameTests = true; // FrontTrees.active を開く
            int treesBefore = com.yayuy.overgrownvegetation.director.ForestEconomy.sources(level).giantTrees();
            FrontTrees.Site s = trees.addSite(level, x, z, Long.MIN_VALUE);
            helper.assertTrue(!FrontTrees.managed(level, x + 50, z), "前提: 払う前から管理された森になっている");
            trees.readyForTests(s);
            helper.assertTrue(FrontTrees.managed(level, x + 50, z), "置く待ちの巨大樹のまわりが管理された森にならない");
            com.yayuy.overgrownvegetation.front.FrontDirector.sync(level, fronts, h);
            helper.assertTrue(fronts.byTree(key) != null, "置く待ちの巨大樹が前線にならない");
            int treesAfter = com.yayuy.overgrownvegetation.director.ForestEconomy.sources(level).giantTrees();
            helper.assertTrue(treesAfter == treesBefore + 1, "置く待ちの巨大樹が稼がない: " + treesBefore + " → " + treesAfter);
            // 止められたら前線も消える
            trees.clearForTests();
            com.yayuy.overgrownvegetation.front.FrontDirector.sync(level, fronts, h);
            helper.assertTrue(fronts.byTree(key) == null, "置く待ちの所が消えても前線が残った");
            helper.succeed();
        } finally {
            com.yayuy.overgrownvegetation.front.FrontDirector.tickInGameTests = ticking;
            trees.clearForTests();
            Fronts.Front f = fronts.byTree(key);
            if (f != null) fronts.fronts().remove(f);
        }
    }
}
