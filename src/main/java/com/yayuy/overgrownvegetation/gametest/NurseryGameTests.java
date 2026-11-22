package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirectorTable;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.G1Fate;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.NurseryParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Nursery;
import com.yayuy.overgrownvegetation.registry.ModG1;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.Supplier;

/**
 * G1 の苗床(2026-11-13、司令塔 第 1 段の区切り 6)。期待値は種の表 {@link G1Nursery} の長さと、設計値({@code director.json})から。
 * テスト場は 7 × 7(床は相対 y = 1、物は y = 2)。萌芽体は床を汚染するので、出たらすぐ消す。
 * 世界に 1 つの予算を書き換えるのは {@link #directorPaysToHatchFromNursery} だけ。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class NurseryGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(NurseryGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "g1_nursery";
    private static final Vec3 CENTER = new Vec3(3.5, 2.0, 3.5);

    private NurseryGameTests() {
    }

    /** 自分では萌芽体も生まず、司令塔にも聞かない苗床(時計はテストが決める)。 */
    private static NurseryParasitized spawnNursery(GameTestHelper helper, G1Nursery kind) {
        NurseryParasitized m = helper.spawn(ModG1.nursery(kind).get(), CENTER);
        m.setTimersForTests(99999, 99999);
        m.setSwallowedForTests(false);
        m.setSpawnPoolForTests(List.of());
        return m;
    }

    private static List<BlightFernEntity> ferns(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(BlightFernEntity.class, helper.getBounds().inflate(1.0D));
    }

    /** 11 種とも登録されていて、芽と大きな嚢の数が書き出しの記録どおり(チビゾンビと鶏は 4・1、ほかは 6・2)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void elevenNurseriesWithBudsAndPods(GameTestHelper helper) {
        helper.assertTrue(G1Nursery.values().length == 11, "苗床が 11 種でない: " + G1Nursery.values().length);
        for (G1Nursery n : G1Nursery.values()) {
            boolean small = n == G1Nursery.BABY_ZOMBIE || n == G1Nursery.CHICKEN;
            helper.assertTrue(n.buds == (small ? 4 : 6) && n.pods == (small ? 1 : 2), n + " の芽・嚢の数が違う: " + n.buds + "・" + n.pods);
            NurseryParasitized m = spawnNursery(helper, n);
            helper.assertTrue(m.nursery() == n, n + " の苗床の型が引けない");
            m.discard();
        }
        helper.succeed();
    }

    /** 萌芽体をただで生む: 芽の動きの tick 12 に、近くへ萌芽体が 1 体出る。予算は減らない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void sproutsBlightFernForFree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NurseryParasitized m = spawnNursery(helper, G1Nursery.COW);
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(ferns(helper).isEmpty(), "生む前から萌芽体がいる");
            m.setTimersForTests(1, 99999);
        });
        int[] sproutTicks = {0};
        helper.onEachTick(() -> {
            if (m.action() == NurseryParasitized.ACTION_SPROUT) {
                sproutTicks[0]++;
                helper.assertTrue(m.slot() >= 1 && m.slot() <= G1Nursery.COW.buds, "芽の番号が範囲の外: " + m.slot());
            }
            List<BlightFernEntity> found = ferns(helper);
            if (found.isEmpty()) return;
            helper.assertTrue(sproutTicks[0] >= G1Nursery.SPROUT_AT, "芽の頭が抜ける前に萌芽体が出た: " + sproutTicks[0]);
            // 予算は払わない: 萌芽体を出す道(sproutFern)は司令塔を通らない。世界に 1 つの予算・払った数は、同じバッチのほかのテストが
            // 同時に動かすので、ここでは数えない(この苗床は出せる Mob が空 = 司令塔から何も買えない)
            found.forEach(net.minecraft.world.entity.Entity::discard);
            m.discard();
            helper.succeed();
        });
    }

    /** その場所が汚染に飲まれたら、萌芽体を生まなくなる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void stopsSproutingOnceSwallowed(GameTestHelper helper) {
        NurseryParasitized m = spawnNursery(helper, G1Nursery.ZOMBIE);
        m.setSwallowedForTests(true);
        helper.runAfterDelay(3, () -> m.setTimersForTests(1, 99999));
        boolean[] sprouted = {false};
        helper.onEachTick(() -> {
            if (m.action() == NurseryParasitized.ACTION_SPROUT) sprouted[0] = true;
        });
        helper.runAfterDelay(3 + G1Nursery.SPROUT + 20, () -> {
            List<BlightFernEntity> found = ferns(helper);
            found.forEach(net.minecraft.world.entity.Entity::discard);
            m.discard();
            helper.assertTrue(!sprouted[0] && found.isEmpty(), "飲まれたのに萌芽体を生んだ");
            helper.succeed();
        });
    }

    /**
     * スポナーの口: 出せる Mob があって予算が足りれば、司令塔が払い、大きな嚢の動きの tick 24 に 1 体出る。足りなければ何もしない。
     * 飲まれていても働く。ここでは G2 以降の代わりに豚を出す。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void directorPaysToHatchFromNursery(GameTestHelper helper) {
        DirectorTable t = DesignData.director();
        ServerLevel level = helper.getLevel();
        ForestDirector director = ForestDirector.get(level);
        double cap = ForestDirector.cap(level);
        if (!t.enabled || cap < t.costSpawn || t.costSpawn <= 0.0D) {
            helper.succeed(); // 設計値が「孵せない」になっている
            return;
        }
        NurseryParasitized m = spawnNursery(helper, G1Nursery.ZOMBIE);
        m.setSwallowedForTests(true);
        List<Supplier<? extends EntityType<? extends Mob>>> pool = List.of(() -> EntityType.PIG);
        m.setSpawnPoolForTests(pool);
        boolean[] rich = {false};
        int[] hatchTicks = {0};
        helper.runAfterDelay(3, () -> {
            director.setBudget(level, 0.0D);
            m.setTimersForTests(99999, 1);
        });
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(m.action() == NurseryParasitized.ACTION_NONE, "予算が無いのに孵し始めた");
            director.setBudget(level, cap);
            m.setTimersForTests(99999, 1);
            rich[0] = true;
        });
        helper.onEachTick(() -> {
            if (!rich[0]) return;
            if (m.action() == NurseryParasitized.ACTION_HATCH) hatchTicks[0]++;
            List<Pig> pigs = level.getEntitiesOfClass(Pig.class, helper.getBounds());
            if (pigs.isEmpty()) return;
            helper.assertTrue(hatchTicks[0] >= G1Nursery.HATCH_AT, "嚢が割れる前に出た: " + hatchTicks[0]);
            double left = director.available(level);
            double slack = ForestDirector.incomePerDay(level) * 300.0D / 24000.0D + 1.0E-6D;
            double price = t.costSpawn * com.yayuy.overgrownvegetation.director.ForestEconomy.table().nurseryLocalFactor; // 11-20: Mob から苗床になった物は孵す Mob を安く
            helper.assertTrue(left >= cap - price - 1.0E-6D && left <= cap - price + slack,
                    "払った量が違う: 残り " + left + "(上限 " + cap + "・値段 " + price + ")");
            pigs.forEach(net.minecraft.world.entity.Entity::discard);
            m.discard();
            director.setBudget(level, 0.0D);
            helper.succeed();
        });
    }

    /** 出せる Mob の一覧が空: 司令塔は予算があっても払わず、苗床は何も孵さない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void emptySpawnPoolBuysNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector director = ForestDirector.get(level);
        long before = director.spawnsBought();
        helper.assertTrue(director.buySpawn(level, RandomSource.create(1L), List.of()) == null, "出せる Mob が無いのに孵そうとした");
        helper.assertTrue(director.spawnsBought() == before, "出せる Mob が無いのに払った");
        // 11-15 から G2・G3 の地上の種が登録されている(spawn/DirectorHooks)。どれが入るかは EnemySpawnGameTests で見る
        helper.succeed();
    }

    /** 判断: 苗床の姿の無い個体・予算が値段に足りないときは苗床を選べない。足りていれば選ばれる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void nurseryNeedsBudgetAndForm(GameTestHelper helper) {
        DirectorTable t = DesignData.director();
        RandomSource random = RandomSource.create(20261113L);
        int noForm = 0, poor = 0, rich = 0;
        for (int i = 0; i < 400; i++) {
            if (ForestDirector.score(t, new ForestDirector.Inputs(false, false, false, t.costNursery * 100.0D, 4.0D, 10.0D), random).route()
                    == ForestDirector.Route.NURSERY) noForm++;
            if (ForestDirector.score(t, new ForestDirector.Inputs(false, false, true, t.costNursery - 0.01D, 4.0D, 10.0D), random).route()
                    == ForestDirector.Route.NURSERY) poor++;
            if (ForestDirector.score(t, new ForestDirector.Inputs(false, false, true, t.costNursery, 4.0D, 10.0D), random).route()
                    == ForestDirector.Route.NURSERY) rich++;
        }
        helper.assertTrue(noForm == 0, "苗床の姿の無い個体に苗床を選んだ: " + noForm);
        helper.assertTrue(t.costNursery <= 0.0D || poor == 0, "予算が足りないのに苗床を選んだ: " + poor);
        helper.assertTrue(rich > 0, "予算が足りているのに、400 回で一度も苗床を選ばない");
        helper.succeed();
    }

    /** 司令塔の道: 苗床に決まった段階 0 の牛は、着くと根を下ろし、牛の苗床に入れ替わって生え始める。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void stageZeroCowBecomesNursery(GameTestHelper helper) {
        AbstractParasitizedMonster cow = helper.spawn(G1Species.COW.entityType(), CENTER);
        cow.goalSelector.removeAllGoals(g -> true);
        cow.targetSelector.removeAllGoals(g -> true);
        G1Fate fate = cow.fate();
        helper.assertTrue(fate.nurseryForm() != null, "牛に苗床の姿が登録されていない");
        fate.setForestForTests(new com.yayuy.overgrownvegetation.director.ForestEdge() {
            @Override
            public double distance(Vec3 pos) {
                return Double.POSITIVE_INFINITY;
            }

            @Override
            public Vec3 away(Vec3 pos) {
                return null;
            }
        });
        fate.beginNursery();
        int omen = DesignData.director().rootOmenTicks;
        helper.runAfterDelay(20 + omen + 6, () -> {
            helper.assertTrue(cow.isRemoved(), "根を下ろしても段階 0 が残っている");
            List<NurseryParasitized> found = helper.getLevel().getEntitiesOfClass(NurseryParasitized.class, helper.getBounds());
            helper.assertTrue(found.size() == 1, "苗床が 1 体でない: " + found.size());
            NurseryParasitized n = found.get(0);
            helper.assertTrue(n.nursery() == G1Nursery.COW && n.isEmerging(), "牛の苗床として生え始めていない");
            n.discard();
            helper.succeed();
        });
    }

    /** 枯れて崩れる: 倒してもすぐには消えず、death_wither の長さで消える。動かない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void nurseryStaysPutAndWithers(GameTestHelper helper) {
        NurseryParasitized m = spawnNursery(helper, G1Nursery.SPIDER);
        Vec3 at = m.position();
        helper.runAfterDelay(3, () -> {
            m.push(0.5D, 0.0D, 0.5D);
            m.knockback(2.0D, 1.0D, 0.0D);
        });
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(m.position().distanceTo(at) < 0.01D, "苗床が動いた");
            m.kill();
        });
        helper.runAfterDelay(12 + G1Nursery.WITHER - 6, () -> helper.assertTrue(!m.isRemoved(), "枯れ終わる前に消えた"));
        helper.runAfterDelay(12 + G1Nursery.WITHER + 4, () -> {
            helper.assertTrue(m.isRemoved(), "枯れ終わっても消えない");
            helper.succeed();
        });
    }

    /** 森に飲まれても枯れない: 育った木に埋まっても、苗床・根付きは窒息しない(本物の地形で、苗床が木の幹に埋まって死んでいた)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void buriedNurseryAndRootedDoNotSuffocate(GameTestHelper helper) {
        NurseryParasitized nursery = helper.spawn(ModG1.nursery(G1Nursery.CHICKEN).get(), new Vec3(2.5, 2.0, 2.5));
        nursery.setTimersForTests(99999, 99999);
        com.yayuy.overgrownvegetation.entity.RootedParasitized rooted = helper.spawn(
                ModG1.rooted(com.yayuy.overgrownvegetation.entity.g1.G1Rooted.CHICKEN).get(), new Vec3(4.5, 2.0, 4.5));
        rooted.targetSelector.removeAllGoals(g -> true);
        rooted.stopCorruptingForTests();
        rooted.setCooldownsForTests(99999, 99999, 99999);
        helper.runAfterDelay(3, () -> {
            for (int y = 2; y <= 3; y++) {
                helper.setBlock(new net.minecraft.core.BlockPos(2, y, 2), net.minecraft.world.level.block.Blocks.OAK_LOG);
                helper.setBlock(new net.minecraft.core.BlockPos(4, y, 4), net.minecraft.world.level.block.Blocks.OAK_LOG);
            }
        });
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(nursery.isAlive() && nursery.getHealth() == nursery.getMaxHealth(), "埋まった苗床が傷ついた: " + nursery.getHealth());
            helper.assertTrue(rooted.isAlive() && rooted.getHealth() == rooted.getMaxHealth(), "埋まった根付きが傷ついた: " + rooted.getHealth());
            // 11-14 ユーザー決定(R6-7): 埋まったら、その上に出る。高さ 2 の幹(相対 y = 2・3)の上面 = 相対 y 4 に載る(宙に浮かない)
            double top = helper.absoluteVec(new Vec3(0.0, 4.0, 0.0)).y;
            helper.assertTrue(Math.abs(nursery.getY() - top) < 0.05D && nursery.onGround(), "苗床が幹の上に出ていない: 幹の上面から " + (nursery.getY() - top));
            helper.assertTrue(Math.abs(rooted.getY() - top) < 0.05D && rooted.onGround(), "根付きが幹の上に出ていない: 幹の上面から " + (rooted.getY() - top));
            helper.assertTrue(Math.abs(nursery.getX() - helper.absoluteVec(new Vec3(2.5, 0.0, 2.5)).x) < 0.01D, "苗床が横へずれた");
            nursery.discard();
            rooted.discard();
            helper.succeed();
        });
    }

    /** 苗床は、森から離れているほど選ばれやすい(11-14 ユーザー決定)。森の中より、縁から遠い個体の方が多く苗床を選ぶ。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void nurseryChosenMoreOftenFarFromForest(GameTestHelper helper) {
        DirectorTable t = DesignData.director();
        RandomSource random = RandomSource.create(20261114L);
        int inside = 0, far = 0;
        for (int i = 0; i < 2000; i++) {
            if (ForestDirector.score(t, new ForestDirector.Inputs(false, false, true, t.costNursery, 1.0E9D, 1.0D, 0.0D), random).route()
                    == ForestDirector.Route.NURSERY) inside++;
            if (ForestDirector.score(t, new ForestDirector.Inputs(false, false, true, t.costNursery, 1.0E9D, 1.0D, t.nurseryFarBlocks), random).route()
                    == ForestDirector.Route.NURSERY) far++;
        }
        if (t.nurseryFar > 0.0D) {
            helper.assertTrue(far > inside, "森から遠い個体の方が苗床を選びやすくなっていない: 森の中 " + inside + " / 遠い " + far);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 寿命(司令塔 第 3 段 区切り 4。11-14 ユーザー N1)
    /** 萌芽体を決めた数(nurseryLifeSprouts)だけ生んだら、その芽の動きが終わったところで枯れて崩れる。1 つ手前では枯れない。 */
    @GameTest(template = TEMPLATE, batch = "g1_nursery_life", timeoutTicks = 200)
    public static void nurseryWithersAfterLifeSprouts(GameTestHelper helper) {
        NurseryParasitized m = spawnNursery(helper, G1Nursery.ZOMBIE);
        int life = DesignData.director().nurseryLifeSprouts;
        m.setSproutedForTests(life - 2);
        helper.runAfterDelay(2, () -> m.setTimersForTests(1, 99999));
        helper.runAfterDelay(2 + G1Nursery.SPROUT + 6, () -> {
            helper.assertTrue(m.isAlive() && m.sprouted() == life - 1, "1 つ手前で枯れた・数えていない: " + m.sprouted());
            m.setTimersForTests(1, 99999);
        });
        helper.runAfterDelay(2 + 2 * (G1Nursery.SPROUT + 6) + 4, () -> {
            helper.assertTrue(!m.isAlive() && m.spent(), "決めた数を生んでも枯れない: " + m.sprouted());
            ferns(helper).forEach(net.minecraft.world.entity.Entity::discard);
            helper.succeed();
        });
    }

    /** 11-17: 1 回孵しても枯れず、決めた回数(nurseryLifeHatches)孵したら、嚢の動きが終わったところで枯れる(飲まれてスポナーだけになった苗床も同じ)。 */
    @GameTest(template = TEMPLATE, batch = "g1_nursery_life", timeoutTicks = 300)
    public static void nurseryWithersAfterLastHatch(GameTestHelper helper) {
        DirectorTable t = DesignData.director();
        ServerLevel level = helper.getLevel();
        ForestDirector director = ForestDirector.get(level);
        NurseryParasitized m = spawnNursery(helper, G1Nursery.ZOMBIE);
        m.setSwallowedForTests(true);
        List<Supplier<? extends EntityType<? extends Mob>>> pool = List.of(() -> EntityType.PIG);
        m.setSpawnPoolForTests(pool);
        double before = director.available(level);
        helper.runAfterDelay(3, () -> {
            director.setBudget(level, t.costSpawn * 2.0D);
            m.setTimersForTests(99999, 1);
        });
        helper.runAfterDelay(3 + G1Nursery.HATCH + 8, () -> {
            helper.assertTrue(m.isAlive() && m.hatches() == 1 && !m.spent(), "1 回孵しただけで枯れた(続けて孵すはず): " + m.hatches());
            m.setHatchesForTests(t.nurseryLifeHatches - 1);
            director.setBudget(level, t.costSpawn * 2.0D);
            m.setTimersForTests(99999, 1);
        });
        helper.runAfterDelay(3 + 2 * (G1Nursery.HATCH + 8) + 4, () -> {
            director.setBudget(level, before);
            level.getEntitiesOfClass(Pig.class, helper.getBounds()).forEach(net.minecraft.world.entity.Entity::discard);
            helper.assertTrue(!m.isAlive() && m.spent(), "決めた回数孵しても枯れない: " + m.hatches());
            helper.succeed();
        });
    }
}
