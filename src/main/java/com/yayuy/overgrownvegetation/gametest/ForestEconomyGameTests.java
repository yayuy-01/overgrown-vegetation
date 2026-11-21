package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EconomyTable;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestEconomy;
import com.yayuy.overgrownvegetation.infect.GenericInfection;
import com.yayuy.overgrownvegetation.organ.ForestOrgans;
import com.yayuy.overgrownvegetation.organ.OrganKind;
import com.yayuy.overgrownvegetation.organ.OrganRoles;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spawn.EnemySpawns;
import com.yayuy.overgrownvegetation.spawn.SpawnTable;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 森の資源(バイオマス)の収支(2026-11-20 司令塔の作り直し 区切り 1)。稼ぎの元・感染体への変化・自然湧きの払い・森の中での戻り・
 * 苗床の割引・フェーズの器官の消費・塔の飛翔。どれも世界の予算を触るので 1 つの束で順に。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ForestEconomyGameTests {

    private static final String BATCH = "director_economy";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ForestEconomyGameTests.class);
        }

        private Registration() {
        }
    }

    private ForestEconomyGameTests() {
    }

    /**
     * テストのワールドは台帳がほぼ空で、11-20 夜の稼ぎ(√チャンク数 × 0.8 + …)では稼ぎも上限もほぼ 0 になり、tick をまたいで予算を持つ
     * ほかのテストが成り立たない。GameTest のサーバーでは、前の形のテストのワールドの稼ぎ(F10 の 550 × 下限 0.3 = 1 日 165)に決め打ちにする。
     * 稼ぎそのものを見るテストは、自分の間だけ決め打ちを外す。
     */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class TestIncome {
        @SubscribeEvent
        public static void onStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
            if (event.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) ForestEconomy.setIncomeForTests(165.0D);
        }

        @SubscribeEvent
        public static void onStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
            ForestEconomy.setIncomeForTests(null);
        }

        private TestIncome() {
        }
    }

    /** 稼ぎは √(過繁茂のチャンク数) で増え、押し返すと減る。井戸の器官は 1 本 40(小 10)・巨大樹は 1 本 8 を足す(11-20 夜)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void incomeFollowsChunksLeyAndTrees(GameTestHelper helper) {
        EconomyTable e = ForestEconomy.table();
        helper.assertTrue(Math.abs(e.incomePerDay(300000, 0, 0, 0) - e.chunkSqrtPerDay * Math.sqrt(300000)) < 1.0E-6D, "稼ぎが √ でない");
        helper.assertTrue(Math.abs(e.incomePerDay(40000, 0, 0, 0) - 2.0D * e.incomePerDay(10000, 0, 0, 0)) < 1.0E-6D, "広さ 4 倍で稼ぎが 2 倍にならない");
        helper.assertTrue(Math.abs(e.incomePerDay(900, 1, 1, 3) - e.incomePerDay(900, 0, 0, 0) - (e.leyPerDay + e.leySmallPerDay + 3 * e.treePerDay)) < 1.0E-6D,
                "井戸と巨大樹が決まった量を足さない");

        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        ForestOrgans data = ForestOrgans.get(level);
        Double incomeBefore = ForestEconomy.incomeOverride();
        List<ChunkPos> added = new ArrayList<>();
        List<Integer> planted = new ArrayList<>();
        try {
            ForestEconomy.setIncomeForTests(null);
            for (int i = 0; i < 400; i++) added.add(new ChunkPos(200000 + i % 64, 200000 + i / 64));
            pd.markOvergrownChunks(level, added);
            double withForest = ForestDirector.incomePerDay(level);
            // 押し返す: 足した分の半分を台帳から外す
            for (int i = 0; i < added.size() / 2; i++) pd.forgetOvergrownChunk(added.get(i));
            double pushed = ForestDirector.incomePerDay(level);
            helper.assertTrue(pushed < withForest - 1.0E-6D, "押し返して森が縮んでも稼ぎが減らない: " + withForest + " → " + pushed);
            // 井戸の器官(育ち切った)で決まった量だけ増える
            ForestOrgans.Organ ley = data.plant(level, OrganKind.LEY, false, 0, 3, 0, 3_000_000, 3_000_000);
            planted.add(ley.id);
            ley.state = ForestOrgans.State.ALIVE;
            double withLey = ForestDirector.incomePerDay(level);
            helper.assertTrue(Math.abs(withLey - pushed - e.leyPerDay) < 1.0E-6D, "井戸の器官の稼ぎが違う: +" + (withLey - pushed) + "(欲しい " + e.leyPerDay + ")");
            helper.succeed();
        } finally {
            for (int id : planted) data.remove(id);
            for (ChunkPos c : added) pd.forgetOvergrownChunk(c);
            ForestEconomy.setIncomeForTests(incomeBefore);
        }
    }

    /**
     * 試算の表(11-20 夜。ログ「[森の資源の試算]」に書く)。F1〜F7 のふつうの広さ(phases.json の normalArea)と、その半分に押し返したとき。
     * 井戸と巨大樹の本数は典型の見積もり(井戸 F5 1・F6 1・F7 2、巨大樹 = ハイヴノード F4 5・F5 7・F6〜 10)。値段は送り手が払うのと同じ(段の強さは最盛)。
     * 送り込み: 満額 = 上限 × waveBudgetShare / 定常 = min(1.5 日の稼ぎ × (1 − ほかの出費 35%【仮】), 上限 × waveBudgetShare)。顔ぶれは 6 日目の段。
     * 貯めた襲撃: 上限 + 財布(稼ぎ × purseShare × 1.5 日)+ 1 日の稼ぎ。大きな襲撃: 上限 + 財布(稼ぎ × purseShare × 貯められる日数)+ 1 日の稼ぎ。
     * F5 のふつうの広さで送り込みが 25〜60 体に入ることだけ確かめる(全体管理の目安 40 前後)。
     */
    @GameTest(template = "gametest_empty7", batch = "director_economy_estimate", timeoutTicks = 20)
    public static void estimateTable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EconomyTable e = ForestEconomy.table();
        var dt = DesignData.director();
        Integer phaseBefore = DirectorGates.phaseOverride();
        var stageBefore = DirectorGates.stageOverride();
        int[] ley = {0, 0, 0, 0, 0, 1, 1, 2};
        int[] trees = {0, 0, 0, 1, 5, 7, 10, 10};
        int f5Wave = -1;
        try {
            DirectorGates.setStageForTests(com.yayuy.overgrownvegetation.phase.PhaseStage.PEAK);
            for (int p = 1; p <= 7; p++) {
                DirectorGates.setPhaseForTests(p);
                int area = DesignData.phases().normalArea(p);
                for (int half = 0; half <= 1; half++) {
                    int chunks = half == 0 ? area : area / 2;
                    double income = e.incomePerDay(chunks, ley[p], 0, trees[p]);
                    double cap = income * (dt.capDays + dt.capPerHiveNodeDays * trees[p]);
                    var w6 = dt.base.waveFor(6.0D);
                    double perWave = income * e.waveIncomeDays; // 1 回は 1 日の稼ぎの waveIncomeDays 日分まで
                    int full = com.yayuy.overgrownvegetation.director.BaseRaids.sizeByBudget(level, w6, Math.min(cap * e.waveBudgetShare, perWave)).total();
                    double steadyB = Math.min(Math.min(1.5D * income * 0.65D, cap * e.waveBudgetShare), perWave);
                    int steady = com.yayuy.overgrownvegetation.director.BaseRaids.sizeByBudget(level, w6, steadyB).total();
                    var hoardW = new com.yayuy.overgrownvegetation.data.BaseMemoryTable.Wave(0.0D, w6.stageZero(),
                            (int) Math.round(w6.developed() * dt.assault.hoardShareFactor), w6.g2(), (int) Math.round(w6.g3plus() * dt.assault.hoardShareFactor));
                    double purse = income * dt.raidScale.purseShare;
                    int medium = com.yayuy.overgrownvegetation.director.BaseRaids.sizeByBudget(level, hoardW, purse * 1.5D + income * e.hoardIncomeDays).total();
                    double largeAvg = (1.0D - dt.largeRaid.developedShare - dt.largeRaid.plantShare) * dt.largeRaid.costStageZero
                            + dt.largeRaid.developedShare * (dt.costEvolve + dt.largeRaid.costStageZero) + dt.largeRaid.plantShare * 12.0D;
                    int large = p >= 6 ? (int) ((cap + purse * dt.raidScale.maxSaveDays(p) + income) / largeAvg) : 0;
                    if (p == 5 && half == 0) f5Wave = steady;
                    OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                            "[森の資源の試算] F%d %s: 過繁茂 %d・井戸 %d・巨大樹 %d → 稼ぎ %.0f/日・上限 %.0f / 送り込み 定常 %d 体(予算 %.0f)・満額 %d 体 / 貯めた襲撃 %d 体 / 大きな襲撃 %s",
                            p, half == 0 ? "ふつう" : "半分に押し返す", chunks, ley[p], trees[p], income, cap, steady, steadyB, full, medium,
                            p >= 6 ? large + " 体" : "-"));
                }
            }
            helper.assertTrue(f5Wave >= 25 && f5Wave <= 60, "F5 のふつうの広さの送り込みが目安(40 前後)から大きくずれた: " + f5Wave);
            helper.succeed();
        } finally {
            DirectorGates.setPhaseForTests(phaseBefore);
            DirectorGates.setStageForTests(stageBefore);
        }
    }

    /** 生き物が汎用の感染体に変わると、元の最大体力に応じた量がまとまって入る。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void infectionPaysTheForest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector dir = ForestDirector.get(level);
        double before = dir.available(level);
        try {
            dir.setBudget(level, 0.0D);
            LivingEntity victim = null;
            for (EntityType<?> t : List.of(EntityType.FOX, EntityType.LLAMA, EntityType.GOAT, EntityType.PANDA, EntityType.CAT, EntityType.OCELOT)) {
                if (helper.spawn(t, new BlockPos(3, 2, 3)) instanceof LivingEntity le) {
                    if (GenericInfection.canTurn(le)) {
                        victim = le;
                        break;
                    }
                    le.discard();
                }
            }
            helper.assertTrue(victim != null, "汎用の感染体になれる生き物が見つからない");
            double gain = ForestEconomy.table().infectGain(victim.getMaxHealth());
            helper.assertTrue(GenericInfection.turn(victim, true), "感染体に変わらない");
            double got = dir.available(level);
            helper.assertTrue(Math.abs(got - gain) < 1.0E-6D, "感染体への変化で入った量が違う: " + got + "(欲しい " + gain + ")");
            helper.succeed();
        } finally {
            dir.setBudget(level, before);
        }
    }

    /** 自然湧きは値段を払う(払えなければ湧かない)。消えたら戻り、森の中で倒されたら一部が戻り、森の外では戻らない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void naturalSpawnsPayAndRefund(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector dir = ForestDirector.get(level);
        PhaseData pd = PhaseData.get(level);
        double before = dir.available(level);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        ChunkPos chunk = new ChunkPos(at);
        boolean wasForest = pd.isOvergrownChunk(chunk);
        try {
            EntityType<?> type = com.yayuy.overgrownvegetation.spawn.DirectorHooks.pickG1(level.getRandom()).entityType();
            dir.setBudget(level, 0.0D);
            EnemySpawns.Miss miss = EnemySpawns.place(level, type, at, SpawnTable.Habitat.GROUND, EnemySpawns.EDGE_TAG, 1);
            helper.assertTrue(miss == EnemySpawns.Miss.NO_BUDGET, "予算 0 でも湧いた: " + miss);

            dir.setBudget(level, 10.0D);
            miss = EnemySpawns.place(level, type, at, SpawnTable.Habitat.GROUND, EnemySpawns.EDGE_TAG, 1);
            helper.assertTrue(miss == EnemySpawns.Miss.SPAWNED, "予算があるのに湧かない: " + miss);
            Mob m = EnemySpawns.lastPlaced();
            double price = ForestEconomy.paid(m);
            helper.assertTrue(price > 0.0D && Math.abs(dir.available(level) - (10.0D - price)) < 1.0E-6D, "自然湧きが払っていない: " + price);
            // デスポーン = 森へ戻る
            m.discard();
            helper.assertTrue(Math.abs(dir.available(level) - 10.0D) < 1.0E-6D, "消えた自然湧きの払いが戻らない: " + dir.available(level));

            // 森の外で倒される → 戻らない
            if (wasForest) pd.forgetOvergrownChunk(chunk);
            EnemySpawns.place(level, type, at, SpawnTable.Habitat.GROUND, EnemySpawns.EDGE_TAG, 1);
            m = EnemySpawns.lastPlaced();
            double afterPay = dir.available(level);
            m.kill();
            helper.assertTrue(Math.abs(dir.available(level) - afterPay) < 1.0E-6D, "森の外で倒されたのに戻った");

            // 森の中で倒される → refundShare だけ戻る
            pd.markOvergrownChunks(level, List.of(chunk));
            EnemySpawns.place(level, type, at, SpawnTable.Habitat.GROUND, EnemySpawns.EDGE_TAG, 1);
            m = EnemySpawns.lastPlaced();
            price = ForestEconomy.paid(m);
            afterPay = dir.available(level);
            m.kill();
            double back = price * ForestEconomy.table().refundShare;
            helper.assertTrue(Math.abs(dir.available(level) - afterPay - back) < 1.0E-6D,
                    "森の中で倒されたときの戻りが違う: +" + (dir.available(level) - afterPay) + "(欲しい " + back + ")");
            helper.succeed();
        } finally {
            if (!wasForest) pd.forgetOvergrownChunk(chunk);
            else pd.markOvergrownChunks(level, List.of(chunk));
            dir.setBudget(level, before);
        }
    }

    /** 苗床の群体は全体の値段を軽くし(下限あり)、Mob から苗床になった物の口は値段に倍率を掛ける。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void nurseriesMakeMobsCheaper(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        ForestDirector dir = ForestDirector.get(level);
        double before = dir.available(level);
        List<Integer> planted = new ArrayList<>();
        EconomyTable e = ForestEconomy.table();
        helper.assertTrue(e.nurseryFactor(1.0D) < 1.0D && e.nurseryFactor(1000.0D) == e.nurseryMinFactor, "苗床の割引の式が違う");
        try {
            double f0 = ForestEconomy.priceFactor(level);
            ForestOrgans.Organ n = data.plant(level, OrganKind.NURSERY, false, 0, 4, 0, 3_100_000, 3_100_000);
            planted.add(n.id);
            n.state = ForestOrgans.State.ALIVE;
            double f1 = ForestEconomy.priceFactor(level);
            helper.assertTrue(f1 < f0 - 1.0E-9D, "苗床の群体で値段が軽くならない: " + f0 + " → " + f1);

            // Mob から苗床になった物の口: 同じ種を、倍率つきの口と無しの口で買い比べる
            List<java.util.function.Supplier<? extends EntityType<? extends Mob>>> pool = new ArrayList<>(ForestDirector.nurserySpawns());
            if (pool.isEmpty()) {
                helper.succeed(); // 苗床から出せる種がまだ無い(戻していない)ときは、全体の割引だけ確かめる
                return;
            }
            pool = List.of(pool.get(0));
            net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(3_100_000, 64, 3_100_000);
            dir.setBudget(level, 1000.0D);
            EntityType<?> a = dir.buySpawn(level, level.getRandom(), pool, at);
            double full = dir.lastBoughtPrice();
            EntityType<?> b = dir.buySpawn(level, level.getRandom(), pool, at, e.nurseryLocalFactor);
            double local = dir.lastBoughtPrice();
            if (a == null || b == null) {
                helper.succeed(); // 段の強さのくじで見送られた(フェーズの段しだい)。全体の割引は確かめた
                return;
            }
            helper.assertTrue(Math.abs(local - full * e.nurseryLocalFactor) < 1.0E-6D, "苗床から孵す値段が軽くならない: " + full + " → " + local);
            helper.succeed();
        } finally {
            for (int id : planted) data.remove(id);
            dir.setBudget(level, before);
        }
    }

    /** フェーズを進める器官は予算を使って点を足す。予算が無ければ足さない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void phaseOrganSpendsForPoints(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        ForestDirector dir = ForestDirector.get(level);
        PhaseData pd = PhaseData.get(level);
        double before = dir.available(level);
        List<Integer> planted = new ArrayList<>();
        try {
            ForestOrgans.Organ o = data.plant(level, OrganKind.PHASE, false, 0, 6, 0, 3_200_000, 3_200_000);
            planted.add(o.id);
            o.state = ForestOrgans.State.ALIVE;
            double perDay = 100.0D;
            dir.setBudget(level, 0.0D);
            long p0 = pd.getPoints();
            OrganRoles.phaseSpend(level, o, pd, perDay, 1.0D);
            helper.assertTrue(pd.getPoints() == p0, "予算 0 なのに点が増えた");
            dir.setBudget(level, 10000.0D);
            long p1 = pd.getPoints();
            OrganRoles.phaseSpend(level, o, pd, perDay, 1.0D);
            double spent = 10000.0D - dir.available(level);
            double want = ForestEconomy.table().phaseSpendPerDay;
            helper.assertTrue(Math.abs(spent - want) < 1.0E-6D, "1 日に使う量が違う: " + spent + "(欲しい " + want + ")");
            // 点が増えるか(このワールドのフェーズで点を足せるときだけ。足せないフェーズ = 閾値が無い・最上段では足さない)
            long probe = pd.getPoints();
            pd.addPoints(level, 1L);
            boolean canAdd = pd.getPoints() > probe;
            if (canAdd) pd.removePoints(pd.getPoints() - probe);
            helper.assertTrue(!canAdd || pd.getPoints() > p1, "予算を使ったのに点が増えない");
            pd.removePoints(Math.max(0L, pd.getPoints() - p0));
            helper.succeed();
        } finally {
            for (int id : planted) data.remove(id);
            dir.setBudget(level, before);
        }
    }

    /** 胞子の塔は予算を払って長距離の飛翔を起こす(払えなければ起こさない)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void towerPaysForLongFlight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        ForestDirector dir = ForestDirector.get(level);
        double before = dir.available(level);
        List<Integer> planted = new ArrayList<>();
        try {
            ForestOrgans.Organ o = data.plant(level, OrganKind.TOWER, false, 0, 9, 0, 3_300_000, 3_300_000);
            planted.add(o.id);
            o.state = ForestOrgans.State.ALIVE;
            dir.setBudget(level, 0.0D);
            helper.assertTrue(!OrganRoles.launchFlight(level, o), "予算 0 で飛翔を起こせた");
            dir.setBudget(level, 1000.0D);
            helper.assertTrue(OrganRoles.launchFlight(level, o), "予算があるのに飛翔を起こせない");
            double price = ForestEconomy.table().towerFlightCost;
            helper.assertTrue(Math.abs(1000.0D - dir.available(level) - price) < 1.0E-6D, "飛翔の値段が違う");
            helper.succeed();
        } finally {
            for (int id : planted) data.remove(id);
            dir.setBudget(level, before);
        }
    }
}
