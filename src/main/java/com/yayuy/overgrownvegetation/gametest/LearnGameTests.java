package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirectorTable;
import com.yayuy.overgrownvegetation.data.KillMemoryTable;
import com.yayuy.overgrownvegetation.director.DangerWatch;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.G1Fate;
import com.yayuy.overgrownvegetation.director.KillMemory;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 司令塔 第 3 段 区切り 1「学ぶ」: 倒された記録・危ない所・進化しやすさ・根付きの点と遠距離の根付き・危ない所へ送る。
 * 記録はテスト用の {@link KillMemory#createForTests}(世界に 1 つの記録は使わない)。送る確かめはまわりの G1 を集めるので、batch を分ける。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class LearnGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterLearnGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(LearnGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "director_learn";

    private LearnGameTests() {
    }

    private static KillMemoryTable k() {
        return DesignData.director().kills;
    }

    /** しきい値は升ごとに 10〜13 のどれか(ユーザー指定の幅)。同じ升ならいつも同じ。升によって違う値が出る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void thresholdIsTenToThirteenPerChunk(GameTestHelper helper) {
        Set<Integer> seen = new HashSet<>();
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                long c = ChunkPos.asLong(x, z);
                int th = KillMemory.threshold(c);
                helper.assertTrue(th >= k().dangerMinKills && th <= k().dangerMaxKills, "しきい値が幅の外: " + th);
                helper.assertTrue(th == KillMemory.threshold(c), "同じ升でしきい値が変わった");
                seen.add(th);
            }
        }
        helper.assertTrue(seen.size() == k().dangerMaxKills - k().dangerMinKills + 1, "幅のどれかが出ない: " + seen);
        helper.succeed();
    }

    /** しきい値の数だけ倒されたら危ない所。半減期で薄れて 1 日後には危なくない。まわり 3 × 3 升の和で数える。保存しても残る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void dangerNeedsThresholdAndFades(GameTestHelper helper) {
        KillMemory m = KillMemory.createForTests();
        long c = ChunkPos.asLong(50, 50);
        int th = KillMemory.threshold(c);
        m.addForTests(c, th - 1, 0.0D);
        helper.assertTrue(m.dangers(0.0D).isEmpty(), "しきい値より少ないのに危ない所になった");
        // 隣の升の 1 体も数える(3 × 3 の和)
        m.addForTests(ChunkPos.asLong(51, 50), 1, 0.0D);
        helper.assertTrue(!m.dangers(0.0D).isEmpty(), "隣の升と合わせてしきい値なのに危ない所にならない");
        helper.assertTrue(m.dangers(0.0D).size() == 1, "同じ戦場が 2 つに数えられた: " + m.dangers(0.0D).size());
        double half = m.killsAround(c, k().halfLifeDays);
        helper.assertTrue(Math.abs(half - th / 2.0D) < 1.0E-6D, "半減期で半分になっていない: " + half);
        helper.assertTrue(m.dangers(k().halfLifeDays).isEmpty(), "1 日後も危ない所のまま");
        KillMemory r = KillMemory.createForTests();
        r.addForTests(c, th + 2, 0.0D);
        helper.assertTrue(r.roundTripForTests().dangers(0.0D).size() == 1, "保存したら危ない所が消えた");
        helper.succeed();
    }

    /** 危ない所の近くは進化しやすい。遠距離でない根付きは、危ない所の近くで選ばれにくい(が、選ばれることもある)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void dangerRaisesEvolveAndLowersMeleeRooted(GameTestHelper helper) {
        DirectorTable t = DesignData.director();
        RandomSource rnd = RandomSource.create(3L);
        int evolveFar = 0, evolveNear = 0, rootFar = 0, rootNear = 0;
        for (int i = 0; i < 600; i++) {
            if (ForestDirector.score(t, new ForestDirector.Inputs(true, false, false, 1000.0D, 1.0E9D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D), rnd)
                    .route() == ForestDirector.Route.EVOLVE) evolveFar++;
            if (ForestDirector.score(t, new ForestDirector.Inputs(true, false, false, 1000.0D, 1.0E9D, 0.0D, 0.0D, 0.0D, 1.0D, 0.0D), rnd)
                    .route() == ForestDirector.Route.EVOLVE) evolveNear++;
            if (ForestDirector.score(t, new ForestDirector.Inputs(false, true, false, 1000.0D, 1.0E9D, 3.0D, 0.0D, 0.0D, 0.0D, 0.0D), rnd)
                    .route() == ForestDirector.Route.ROOTED) rootFar++;
            if (ForestDirector.score(t, new ForestDirector.Inputs(false, true, false, 1000.0D, 1.0E9D, 3.0D, 0.0D, 0.0D, 0.0D, 1.0D), rnd)
                    .route() == ForestDirector.Route.ROOTED) rootNear++;
        }
        helper.assertTrue(evolveNear > evolveFar, "危ない所の近くで進化が増えない: " + evolveNear + " / " + evolveFar);
        helper.assertTrue(rootNear < rootFar, "危ない所の近くで根付きが減らない: " + rootNear + " / " + rootFar);
        helper.succeed();
    }

    /** 遠距離の根付き(ユーザー: スケルトン・エンダーマン)は危ない所から離れる向きへ歩く。ゾンビは遠距離でない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void rangedRootedWalksAwayFromDanger(GameTestHelper helper) {
        helper.assertTrue(k().isRanged("skeleton") && k().isRanged("enderman") && !k().isRanged("zombie"), "遠距離の種が違う: " + k().rangedRooted);
        Vec3 dir = G1Fate.awayFromDanger(new Vec3(1.0D, 0.0D, 0.0D), Vec3.ZERO, new Vec3(0.0D, 0.0D, 10.0D));
        helper.assertTrue(dir.z < 0.0D, "危ない所から離れる向きが混ざっていない: " + dir);
        helper.succeed();
    }

    /** 危ない所へ送る: まわりに 1 段目がいなければ、段階 0 を予算で進化させて、危ない所へ向かわせる(段の数だけ)。予算は戻す。 */
    @GameTest(template = TEMPLATE, batch = "director_learn_world", timeoutTicks = 20)
    public static void sendEvolvesStageZeroTowardDanger(GameTestHelper helper) {
        var level = helper.getLevel();
        List<AbstractParasitizedMonster> mine = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            AbstractParasitizedMonster z = helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(1.5 + i, 2.0, 3.5));
            z.goalSelector.removeAllGoals(g -> true);
            z.targetSelector.removeAllGoals(g -> true);
            mine.add(z);
        }
        int cx = (mine.get(0).getBlockX() >> 4) + 3, cz = mine.get(0).getBlockZ() >> 4;
        KillMemory.Danger d = new KillMemory.Danger(ChunkPos.asLong(cx, cz), cx * 16 + 8.0D, cz * 16 + 8.0D, 13.0D, 10);
        ForestDirector director = ForestDirector.get(level);
        double before = director.available(level);
        try {
            director.setBudget(level, DesignData.director().costEvolve * k().dangerDeveloped);
            int[] r = DangerWatch.send(level, d, RandomSource.create(1L));
            int marching = 0;
            for (AbstractParasitizedMonster z : mine) {
                if (z.fate().route() == ForestDirector.Route.EVOLVE && z.march().active() && z.march().baseId() == -1) marching++;
            }
            helper.assertTrue(r[1] == k().dangerDeveloped && marching == r[1], "危ない所へ進化させて送っていない: " + r[0] + " / " + r[1] + "・向かう " + marching);
        } finally {
            director.setBudget(level, before);
            for (AbstractParasitizedMonster z : mine) z.discard();
        }
        helper.succeed();
    }
}
