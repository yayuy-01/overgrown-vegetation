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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
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
        return helper.getLevel().getEntitiesOfClass(DevelopedParasitized.class, helper.getBounds());
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

    /** 升までの距離: 升の中は 0、外は辺・角までの長さ。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void forestEdgeDistanceToChunk(GameTestHelper helper) {
        helper.assertTrue(com.yayuy.overgrownvegetation.director.ForestEdge.toChunk(new Vec3(8, 0, 8), 0, 0) == 0.0D, "升の中が 0 でない");
        helper.assertTrue(Math.abs(com.yayuy.overgrownvegetation.director.ForestEdge.toChunk(new Vec3(26, 0, 8), 0, 0) - 10.0D) < 1.0E-9D, "辺までの距離が違う");
        helper.assertTrue(Math.abs(com.yayuy.overgrownvegetation.director.ForestEdge.toChunk(new Vec3(19, 0, -4), 0, 0) - 5.0D) < 1.0E-9D, "角までの距離が違う");
        helper.assertTrue(Math.abs(com.yayuy.overgrownvegetation.director.ForestEdge.toChunk(new Vec3(-35, 0, -24), -2, -2) - 3.0D) < 1.0E-9D, "負の升で距離が違う");
        helper.succeed();
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
