package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirectorTable;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.G1Fate;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.registry.ModG1;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 森の知性(司令塔)の第 1 段(2026-11-13): 森の予算・判断・見に来る時刻・進化の前兆と入れ替え。
 * 期待値は設計値({@code director.json} = {@link DesignData#director()})から引く。テスト場は 7 × 7(床は相対 y = 1、物は y = 2)。
 * 世界に 1 つの予算を書き換えるのは {@link #visitWaitsWithoutBudgetThenPaysToEvolve} だけ(ほかは計算か、司令塔を通さない口)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class DirectorGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterDirectorGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(DirectorGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "director";
    private static final Vec3 CENTER = new Vec3(3.5, 2.0, 3.5);

    private DirectorGameTests() {
    }

    /** 自分では動かない・狙わない段階 0(司令塔の時計だけが進む)。 */
    private static AbstractParasitizedMonster spawnStageZero(GameTestHelper helper, G1Species s) {
        AbstractParasitizedMonster m = helper.spawn(s.entityType(), CENTER);
        m.goalSelector.removeAllGoals(g -> true);
        m.targetSelector.removeAllGoals(g -> true);
        return m;
    }

    private static List<DevelopedParasitized> developedInArena(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(DevelopedParasitized.class, GtCompat.bounds(helper));
    }

    // ------------------------------------------------------------------ 予算
    /** 森の予算: 過繁茂のチャンクが多いほど速く貯まり、ハイヴノードで上限が上がり、上限で止まる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void budgetGrowsWithForestAndStopsAtCap(GameTestHelper helper) {
        DirectorTable t = DesignData.director();
        // 11-14 ユーザー決定の収支: 収入 = フェーズの基本 × 面積の割合(幅に収める)、上限 = 1 日分 + ハイヴノードごとに 0.5 日分
        helper.assertTrue(t.income(1, 1.0D) == t.incomeBase[1] && t.income(7, 1.0D) == t.incomeBase[7] && t.incomeBase[7] > t.incomeBase[1],
                "フェーズの基本で貯まっていない");
        helper.assertTrue(Math.abs(t.income(5, 0.0D) - t.incomeBase[5] * t.areaRatioMin) < 1.0E-9D
                && Math.abs(t.income(5, 99.0D) - t.incomeBase[5] * t.areaRatioMax) < 1.0E-9D, "面積の割合が幅に収まっていない");
        // 11-22 ユーザー決定: 上限 = 基礎値 + ハイヴノード × capPerHiveNode(1 日の稼ぎには比例しない)
        helper.assertTrue(Math.abs(t.cap(0) - t.capBase) < 1.0E-9D
                && Math.abs(t.cap(3) - t.cap(0) - 3 * t.capPerHiveNode) < 1.0E-9D, "上限が基礎値とハイヴノードで決まっていない");

        double cap = 1000.0D;
        double oneDay = ForestDirector.accrued(0.0D, 24000L, 7.0D, cap);
        helper.assertTrue(Math.abs(oneDay - 7.0D) < 1.0E-9D, "1 日ぶんの貯まりが違う: " + oneDay);
        double halfDay = ForestDirector.accrued(2.0D, 12000L, 7.0D, cap);
        helper.assertTrue(Math.abs(halfDay - 5.5D) < 1.0E-9D, "半日ぶんの貯まりが違う: " + halfDay);
        helper.assertTrue(ForestDirector.accrued(0.0D, 24000L * 1000L, 7.0D, 30.0D) == 30.0D, "上限で止まっていない");
        helper.assertTrue(ForestDirector.accrued(80.0D, 0L, 7.0D, 30.0D) == 30.0D, "上限が下がったのに予算が残っている");
        helper.assertTrue(ForestDirector.accrued(5.0D, 24000L, 0.0D, 30.0D) == 5.0D, "森が無いのに貯まっている");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 判断
    /** 判断: 予算が値段に足りない・進化体が無い個体は進化を選べない。足りていれば進化も選ばれる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void evolveNeedsBudgetAndDevelopedForm(GameTestHelper helper) {
        DirectorTable t = DesignData.director();
        RandomSource random = RandomSource.create(20261113L);
        int poor = 0, noForm = 0, rich = 0;
        for (int i = 0; i < 400; i++) {
            if (ForestDirector.score(t, new ForestDirector.Inputs(true, false, t.costEvolve - 0.01D, 4.0D, 10.0D), random).route()
                    == ForestDirector.Route.EVOLVE) poor++;
            if (ForestDirector.score(t, new ForestDirector.Inputs(false, false, t.costEvolve * 100.0D, 4.0D, 10.0D), random).route()
                    == ForestDirector.Route.EVOLVE) noForm++;
            if (ForestDirector.score(t, new ForestDirector.Inputs(true, false, t.costEvolve, 4.0D, 10.0D), random).route()
                    == ForestDirector.Route.EVOLVE) rich++;
        }
        helper.assertTrue(poor == 0, "予算が足りないのに進化を選んだ: " + poor);
        helper.assertTrue(noForm == 0, "進化体の無い個体に進化を選んだ: " + noForm);
        helper.assertTrue(rich > 0, "予算が足りていて人が近く日もたっているのに、400 回で一度も進化を選ばない");
        helper.succeed();
    }

    /** 見に来る時刻: 最小〜最大の間に収まり、早い個体も遅い個体もいる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void firstVisitSpreadsFromMinutesToDays(GameTestHelper helper) {
        DirectorTable t = DesignData.director();
        RandomSource random = RandomSource.create(20261113L);
        double middle = Math.sqrt((double) t.firstVisitMinTicks * t.firstVisitMaxTicks);
        int early = 0, late = 0;
        for (int i = 0; i < 500; i++) {
            int d = G1Fate.firstDelay(t, random);
            helper.assertTrue(d >= t.firstVisitMinTicks && d <= t.firstVisitMaxTicks, "見に来る時刻が範囲の外: " + d);
            if (d < middle) early++;
            else late++;
        }
        if (t.firstVisitMaxTicks > t.firstVisitMinTicks * 2) {
            helper.assertTrue(early > 100 && late > 100, "見に来る時刻が片寄っている: 早い " + early + " / 遅い " + late);
        }
        helper.succeed();
    }

    /** 司令塔が見に来るのは、1 段目のある種の段階 0 だけ(ゴーレム・1 段目には来ない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void onlyStageZeroWithDevelopedFormIsVisited(GameTestHelper helper) {
        AbstractParasitizedMonster zombie = spawnStageZero(helper, G1Species.ZOMBIE);
        AbstractParasitizedMonster golem = spawnStageZero(helper, G1Species.IRON_GOLEM);
        DevelopedParasitized developed = helper.spawn(ModG1.developed(G1Developed.ZOMBIE).get(), CENTER);
        boolean z = zombie.fate() != null, g = golem.fate() != null, d = developed.fate() != null;
        zombie.discard();
        golem.discard();
        developed.discard();
        helper.assertTrue(z, "段階 0 のゾンビに司令塔の時計が無い");
        helper.assertTrue(!g, "ゴーレムに司令塔の時計がある");
        helper.assertTrue(!d, "1 段目に司令塔の時計がある");
        helper.succeed();
    }

    /** 見に来る時刻は保存される(読み込み直しても同じ時刻に見に来る)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void visitTimeSurvivesSaveAndLoad(GameTestHelper helper) {
        AbstractParasitizedMonster zombie = spawnStageZero(helper, G1Species.ZOMBIE);
        long when = helper.getLevel().getGameTime() + 54321L;
        zombie.fate().setNextVisit(when);
        CompoundTag tag = new CompoundTag();
        zombie.saveWithoutId(tag);
        AbstractParasitizedMonster copy = G1Species.ZOMBIE.entityType().create(helper.getLevel());
        copy.load(tag);
        long loaded = copy.fate().nextVisit();
        zombie.discard();
        helper.assertTrue(loaded == when, "見に来る時刻が保存されていない: " + loaded + " ≠ " + when);
        helper.succeed();
    }

    /** 前兆の途中で保存されても、読み込み後に固まらず(NoAI が残らない)、前兆の続きから進化する。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void omenSurvivesSaveWithoutFreezing(GameTestHelper helper) {
        AbstractParasitizedMonster zombie = spawnStageZero(helper, G1Species.ZOMBIE);
        zombie.fate().beginEvolve();
        CompoundTag tag = new CompoundTag();
        zombie.saveWithoutId(tag);
        AbstractParasitizedMonster copy = G1Species.ZOMBIE.entityType().create(helper.getLevel());
        copy.load(tag);
        boolean savedNoAi = tag.getBoolean("NoAI");
        ForestDirector.Route route = copy.fate().route();
        int omen = copy.omen();
        zombie.discard();
        helper.assertTrue(!savedNoAi, "前兆の一時的な停止が NoAI として保存された(読み込み後に固まる)");
        helper.assertTrue(route == ForestDirector.Route.EVOLVE && omen == G1Fate.OMEN_EVOLVE, "前兆の途中が保存されていない: " + route + " / " + omen);
        helper.succeed();
    }

    // ------------------------------------------------------------------ 前兆と入れ替え
    /** 進化: 前兆の間は立ち止まり、前兆が終わると同じ場所で 1 段目に入れ替わって evolve_in を流す。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void stageZeroTremblesThenBecomesDeveloped(GameTestHelper helper) {
        int omen = DesignData.director().evolveOmenTicks;
        AbstractParasitizedMonster zombie = spawnStageZero(helper, G1Species.ZOMBIE);
        Vec3 at = zombie.position();
        helper.runAfterDelay(5, () -> {
            zombie.fate().beginEvolve();
            helper.assertTrue(zombie.omen() == G1Fate.OMEN_EVOLVE, "前兆が同期されていない");
            helper.assertTrue(zombie.isNoAi(), "前兆の間に立ち止まっていない");
        });
        helper.runAfterDelay(5 + omen - 2, () -> {
            helper.assertTrue(zombie.isAlive(), "前兆が終わる前に消えた");
            helper.assertTrue(developedInArena(helper).isEmpty(), "前兆が終わる前に 1 段目が出た");
        });
        helper.runAfterDelay(5 + omen + 2, () -> {
            helper.assertTrue(zombie.isRemoved(), "前兆が終わっても段階 0 が残っている");
            List<DevelopedParasitized> found = developedInArena(helper);
            helper.assertTrue(found.size() == 1, "1 段目が 1 体でない: " + found.size());
            DevelopedParasitized d = found.get(0);
            helper.assertTrue(d.dev() == G1Developed.ZOMBIE, "種が違う: " + d.dev());
            helper.assertTrue(d.isEvolving(), "evolve_in を流していない");
            helper.assertTrue(d.position().distanceTo(at) < 0.5D, "入れ替わった場所がずれている: " + d.position().distanceTo(at));
            d.discard();
            helper.succeed();
        });
    }

    /** 進化の前兆の間に倒せば、1 段目は出ない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void killedDuringOmenDoesNotEvolve(GameTestHelper helper) {
        int omen = DesignData.director().evolveOmenTicks;
        AbstractParasitizedMonster zombie = spawnStageZero(helper, G1Species.ZOMBIE);
        helper.runAfterDelay(5, () -> zombie.fate().beginEvolve());
        helper.runAfterDelay(5 + Math.max(1, omen / 2), () -> {
            helper.assertTrue(zombie.isAlive() && zombie.omen() == G1Fate.OMEN_EVOLVE, "前兆の途中でない");
            zombie.kill();
        });
        helper.runAfterDelay(5 + omen + 30, () -> {
            helper.assertTrue(developedInArena(helper).isEmpty(), "前兆の間に倒したのに 1 段目が出た");
            helper.succeed();
        });
    }

    /**
     * 見に来たとき予算が足りなければ何もせず、後でまた見に来る。足りていれば(何度か見に来るうちに)進化を選び、値段ぶんを払う。
     * 揺らぎで「何もしない」になる回があるので、進化を選ぶまで毎 tick 見に来させる。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public static void visitWaitsWithoutBudgetThenPaysToEvolve(GameTestHelper helper) {
        DirectorTable t = DesignData.director();
        ServerLevel level = helper.getLevel();
        ForestDirector director = ForestDirector.get(level);
        double cap = ForestDirector.cap(level);
        if (!t.enabled || cap < t.costEvolve || t.costEvolve <= 0.0D) {
            helper.succeed(); // 設計値が「進化できない」になっている(上限が値段より低い)
            return;
        }
        AbstractParasitizedMonster zombie = spawnStageZero(helper, G1Species.ZOMBIE);
        zombie.fate().setNoRootedForTests(true);
        boolean[] rich = {false};
        helper.runAfterDelay(5, () -> {
            director.setBudget(level, 0.0D);
            zombie.fate().setNextVisit(level.getGameTime());
        });
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(zombie.fate().route() == ForestDirector.Route.NONE && zombie.omen() == G1Fate.OMEN_NONE,
                    "予算が無いのに道が決まった: " + zombie.fate().route());
            long wait = zombie.fate().nextVisit() - level.getGameTime();
            helper.assertTrue(wait > t.revisitMinTicks - 10, "何もしなかった後の「また見に来る」が近すぎる: " + wait);
            director.setBudget(level, cap);
            rich[0] = true;
        });
        helper.onEachTick(() -> {
            if (!rich[0] || !zombie.isAlive()) return;
            if (zombie.fate().route() == ForestDirector.Route.EVOLVE) {
                double left = director.available(level);
                double slack = ForestDirector.incomePerDay(level) * 400.0D / 24000.0D + 1.0E-6D;
                double price = ForestDirector.routeCost(t, ForestDirector.Route.EVOLVE, zombie); // 11-21 進化先の危険度に応じた追加の費用
                helper.assertTrue(left >= cap - price - 1.0E-6D && left <= cap - price + slack,
                        "払った量が違う: 残り " + left + "(上限 " + cap + "・値段 " + price + ")");
                zombie.discard();
                director.setBudget(level, 0.0D); // 世界に 1 つの予算を残さない(ほかのテストの段階 0 が進化しないように)
                helper.succeed();
                return;
            }
            director.setBudget(level, cap);
            zombie.fate().setNextVisit(level.getGameTime());
        });
    }

    // ------------------------------------------------------------------ 根付きの道(区切り 2): 森から離れて歩く → 根を下ろす
    /** テスト場の西の端(相対 x = 0)より西が全部森、という見え方。離れる向きは東。 */
    private static com.yayuy.overgrownvegetation.director.ForestEdge forestToTheWest(GameTestHelper helper) {
        double edgeX = helper.absoluteVec(new Vec3(0.0, 2.0, 0.0)).x;
        return new com.yayuy.overgrownvegetation.director.ForestEdge() {
            @Override
            public double distance(Vec3 pos) {
                return Math.max(0.0D, pos.x - edgeX);
            }

            @Override
            public Vec3 away(Vec3 pos) {
                return new Vec3(1.0D, 0.0D, 0.0D);
            }
        };
    }

    /** 森から離れて歩く Goal だけを残した段階 0(標的は選ばない)。 */
    private static AbstractParasitizedMonster spawnWalker(GameTestHelper helper, Vec3 at) {
        AbstractParasitizedMonster m = helper.spawn(G1Species.ZOMBIE.entityType(), at);
        m.goalSelector.removeAllGoals(g -> !(g instanceof com.yayuy.overgrownvegetation.director.G1WalkAwayGoal));
        m.targetSelector.removeAllGoals(g -> true);
        return m;
    }

    /** 升までの距離: 升の中は 0、外は辺・角までの長さ。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void forestEdgeDistanceToChunk(GameTestHelper helper) {
        helper.assertTrue(com.yayuy.overgrownvegetation.director.ForestEdge.toChunk(new Vec3(8, 0, 8), 0, 0) == 0.0D, "升の中が 0 でない");
        helper.assertTrue(Math.abs(com.yayuy.overgrownvegetation.director.ForestEdge.toChunk(new Vec3(26, 0, 8), 0, 0) - 10.0D) < 1.0E-9D, "辺までの距離が違う");
        helper.assertTrue(Math.abs(com.yayuy.overgrownvegetation.director.ForestEdge.toChunk(new Vec3(19, 0, -4), 0, 0) - 5.0D) < 1.0E-9D, "角までの距離が違う");
        helper.assertTrue(Math.abs(com.yayuy.overgrownvegetation.director.ForestEdge.toChunk(new Vec3(-35, 0, -24), -2, -2) - 3.0D) < 1.0E-9D, "負の升で距離が違う");
        helper.succeed();
    }

    /** 判断: 根付きの姿の無い種・予算が値段に足りないときは根付きを選べない。足りていれば選ばれる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void rootedNeedsBudgetAndRootedForm(GameTestHelper helper) {
        DirectorTable t = DesignData.director();
        RandomSource random = RandomSource.create(20261113L);
        int noForm = 0, poor = 0, rich = 0;
        for (int i = 0; i < 400; i++) {
            if (ForestDirector.score(t, new ForestDirector.Inputs(false, false, t.costRooted * 100.0D, 4.0D, 10.0D), random).route()
                    == ForestDirector.Route.ROOTED) noForm++;
            if (ForestDirector.score(t, new ForestDirector.Inputs(false, true, t.costRooted - 0.01D, 4.0D, 10.0D), random).route()
                    == ForestDirector.Route.ROOTED) poor++;
            if (ForestDirector.score(t, new ForestDirector.Inputs(false, true, t.costRooted, 4.0D, 10.0D), random).route()
                    == ForestDirector.Route.ROOTED) rich++;
        }
        helper.assertTrue(noForm == 0, "根付きの姿の無い種に根付きを選んだ: " + noForm);
        helper.assertTrue(poor == 0, "予算が足りないのに根付きを選んだ: " + poor);
        helper.assertTrue(rich > 0, "予算が足りているのに、400 回で一度も根付きを選ばない");
        helper.succeed();
    }

    /**
     * 根付き: 森から離れる向きへ歩き、決めた長さだけ離れたら立ち止まって根を下ろし、根付きの姿に入れ替わる。
     * 根付きの姿はまだ組み込んでいないので、ここでは代わりに豚を出す。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void walksAwayFromForestThenRootsDown(GameTestHelper helper) {
        AbstractParasitizedMonster zombie = spawnWalker(helper, new Vec3(1.5, 2.0, 3.5));
        double edgeX = helper.absoluteVec(new Vec3(0.0, 2.0, 0.0)).x;
        G1Fate fate = zombie.fate();
        fate.setForestForTests(forestToTheWest(helper));
        fate.setRootedFormForTests(() -> net.minecraft.world.entity.EntityType.PIG);
        fate.beginRooted();
        fate.setAwayWantedForTests(4.0D);
        boolean[] sawRootOmen = {false};
        double[] omenAt = {Double.NaN};
        helper.onEachTick(() -> {
            if (zombie.isAlive() && zombie.omen() == G1Fate.OMEN_ROOT && !sawRootOmen[0]) {
                sawRootOmen[0] = true;
                omenAt[0] = zombie.getX() - edgeX;
                helper.assertTrue(zombie.isNoAi(), "根を下ろす間に立ち止まっていない");
            }
            if (!zombie.isRemoved()) return;
            List<net.minecraft.world.entity.animal.Pig> pigs = helper.getLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.animal.Pig.class, GtCompat.bounds(helper));
            helper.assertTrue(sawRootOmen[0], "根を下ろす前兆なしに入れ替わった");
            helper.assertTrue(omenAt[0] >= 4.0D - 1.0E-6D, "森の縁から 4 ブロック離れる前に根を下ろした: " + omenAt[0]);
            helper.assertTrue(pigs.size() == 1, "根付きの姿(代わりの豚)が 1 体でない: " + pigs.size());
            pigs.forEach(net.minecraft.world.entity.Entity::discard);
            helper.succeed();
        });
    }

    /** 根付き: 着けないまま歩く時間を使い切ったら、その場で根を下ろす。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void walkTimeoutRootsDownInPlace(GameTestHelper helper) {
        int timeout = DesignData.director().walkTimeoutTicks;
        AbstractParasitizedMonster zombie = spawnStageZero(helper, G1Species.ZOMBIE);
        G1Fate fate = zombie.fate();
        fate.setForestForTests(forestToTheWest(helper));
        fate.beginRooted();
        fate.setAwayWantedForTests(10000.0D);
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(zombie.omen() == G1Fate.OMEN_NONE, "着いていないのに根を下ろした");
            fate.setWalkedForTests(timeout - 3);
        });
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(zombie.omen() == G1Fate.OMEN_ROOT, "歩く時間を使い切っても根を下ろさない(歩いた " + fate.walkedTicks() + ")");
            zombie.discard();
            helper.succeed();
        });
    }

    /** 根付き: 森から離れて歩いている途中で倒せば、根付きは出ない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void killedWhileWalkingAwayDoesNotRoot(GameTestHelper helper) {
        AbstractParasitizedMonster zombie = spawnWalker(helper, new Vec3(1.5, 2.0, 3.5));
        G1Fate fate = zombie.fate();
        fate.setForestForTests(forestToTheWest(helper));
        fate.setRootedFormForTests(() -> net.minecraft.world.entity.EntityType.PIG);
        fate.beginRooted();
        fate.setAwayWantedForTests(4.0D);
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(zombie.isAlive() && fate.walkingAway(), "歩いている途中でない");
            zombie.kill();
        });
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Pig.class, GtCompat.bounds(helper)).isEmpty(),
                    "歩いている途中で倒したのに根付きが出た");
            helper.succeed();
        });
    }

    /** 戦っている最中(標的がいる)の個体には見に来ない: 見に来る時刻が少し先へ延びるだけで、道は決まらない(11-13 ユーザー決定)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void notVisitedWhileFighting(GameTestHelper helper) {
        AbstractParasitizedMonster zombie = spawnStageZero(helper, G1Species.ZOMBIE);
        net.minecraft.world.entity.animal.Cow cow = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.COW, new Vec3(5.5, 2.0, 5.5));
        long before = ForestDirector.get(helper.getLevel()).decidedCount(ForestDirector.Route.NONE);
        helper.runAfterDelay(3, () -> {
            zombie.setTarget(cow);
            helper.assertTrue(G1Fate.inCombat(zombie), "標的がいるのに戦っている最中でない");
            zombie.fate().setNextVisit(helper.getLevel().getGameTime());
        });
        helper.runAfterDelay(6, () -> {
            long wait = zombie.fate().nextVisit() - helper.getLevel().getGameTime();
            helper.assertTrue(zombie.fate().route() == ForestDirector.Route.NONE && zombie.omen() == G1Fate.OMEN_NONE, "戦っている最中に道が決まった");
            helper.assertTrue(wait > 0 && wait <= 100, "戦っている最中の見直しの待ちが違う(司令塔が見に来た?): " + wait);
            zombie.setTarget(null);
            helper.assertTrue(!G1Fate.inCombat(zombie), "標的がいなくなっても戦っている最中のまま");
            zombie.discard();
            cow.discard();
            helper.succeed();
        });
    }

    /** 森から離れて歩いている途中で殴られたら殴り返す: その間は歩いた時間を数えず、相手がいなくなればまた歩きに戻る(11-13 ユーザー決定)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void walkerFightsBackWhenHit(GameTestHelper helper) {
        AbstractParasitizedMonster zombie = spawnStageZero(helper, G1Species.ZOMBIE);
        net.minecraft.world.entity.animal.Cow cow = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.COW, new Vec3(5.5, 2.0, 5.5));
        G1Fate fate = zombie.fate();
        fate.setForestForTests(forestToTheWest(helper));
        fate.beginRooted();
        fate.setAwayWantedForTests(10000.0D);
        int[] walked = {0};
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(!G1Fate.retaliating(zombie) && fate.walkedTicks() > 0, "殴られる前から歩いていない");
            zombie.hurt(helper.getLevel().damageSources().mobAttack(cow), 1.0F);
            helper.assertTrue(G1Fate.retaliating(zombie), "殴られたのに殴り返す状態にならない");
            walked[0] = fate.walkedTicks();
        });
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(fate.walkedTicks() == walked[0], "殴り返している間も歩いた時間を数えた: " + walked[0] + " → " + fate.walkedTicks());
            cow.discard();
        });
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(!G1Fate.retaliating(zombie), "相手がいなくなっても殴り返す状態のまま");
            helper.assertTrue(fate.walkedTicks() > walked[0], "相手がいなくなっても歩きに戻らない");
            zombie.discard();
            helper.succeed();
        });
    }

    /** 殴り返す時間は 10 秒: バニラが殴ってきた相手の記録を 100 tick で消しても、相手が生きている間は 200 tick 近くまで続く。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 260)
    public static void retaliationLastsTenSeconds(GameTestHelper helper) {
        AbstractParasitizedMonster zombie = spawnStageZero(helper, G1Species.ZOMBIE);
        net.minecraft.world.entity.animal.Cow cow = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.COW, new Vec3(5.5, 2.0, 5.5));
        helper.runAfterDelay(5, () -> zombie.hurt(helper.getLevel().damageSources().mobAttack(cow), 1.0F));
        helper.runAfterDelay(5 + 150, () -> helper.assertTrue(G1Fate.retaliating(zombie) && G1Fate.inCombat(zombie),
                "殴られて 150 tick でもう戦いが終わったことになっている"));
        helper.runAfterDelay(5 + G1Fate.COMBAT_QUIET_TICKS + 10, () -> {
            helper.assertTrue(!G1Fate.retaliating(zombie) && !G1Fate.inCombat(zombie), "10 秒たっても戦っている最中のまま");
            zombie.discard();
            cow.discard();
            helper.succeed();
        });
    }
}
