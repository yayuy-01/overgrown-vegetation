package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.phase.KillPoints;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * フェーズのポイントの式(2026-11-14 ユーザー決定。ふつうの 1 日 = 100 点 = 時間 50・面積 40・撃破 10)のテスト。
 * 期待値はどれも設計データ({@code phases.json})から計算する(数字を決め打ちしない)。世界の本物の {@link PhaseData} には触らない。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PhasePointsGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PhasePointsGameTests.class);
        }

        private Registration() {
        }
    }

    /** 自然に上がる最後のフェーズ(F7 まで)。 */
    private static int lastAuto(PhaseTable t) {
        int last = 1;
        for (int n = 2; n <= PhaseTable.MAX_PHASE; n++) {
            if (t.autoAdvance(n) && t.pointsToAdvance(n) != Long.MAX_VALUE) last = n;
            else break;
        }
        return last;
    }

    /** 面積が「ふつうの広さ × k」のまま進んだときに、F1 から {@code to} に着く日数。 */
    private static double daysTo(PhaseTable t, int to, double k) {
        double days = 0.0D;
        for (int p = 1; p < to; p++) {
            double perDay = t.pointsPerTick(p, Math.round(t.normalArea(p) * k)) * PhaseTable.TICKS_PER_DAY;
            days += t.pointsToAdvance(p + 1) / perDay;
        }
        return days;
    }

    /** 時間だけ(面積 0・撃破なし)なら 1 日 timePerDay 点。F7 までの日数 = 閾値の和 ÷ timePerDay(既定 7500 ÷ 50 = 150 日)。 */
    @GameTest(template = "gametest_empty7")
    public static void timeOnlyDays(GameTestHelper helper) {
        PhaseTable t = DesignData.phases();
        int last = lastAuto(t);
        long sum = 0L;
        for (int n = 2; n <= last; n++) sum += t.pointsToAdvance(n);
        double expected = sum / t.timePointsPerDay();
        double got = daysTo(t, last, 0.0D);
        helper.assertTrue(Math.abs(got - expected) < 1.0E-6D, "時間だけの日数 " + got + "(期待 " + expected + ")");
        helper.assertTrue(Math.abs(t.pointsPerTick(1, 0L) * PhaseTable.TICKS_PER_DAY - t.timePointsPerDay()) < 1.0E-9D, "面積 0 で 1 日の点が時間の点と違う");
        helper.succeed();
    }

    /** 面積 0 / ふつう / 2 倍 で、1 日の点が 時間 / 時間 + 面積 / 時間 + 2 × 面積 になり、日数がその分だけ縮む。 */
    @GameTest(template = "gametest_empty7")
    public static void areaScalesDays(GameTestHelper helper) {
        PhaseTable t = DesignData.phases();
        double time = t.timePointsPerDay(), area = t.areaPointsPerDay();
        for (int p = 1; p < lastAuto(t); p++) {
            helper.assertTrue(t.normalArea(p) > 0, "F" + p + " のふつうの広さが無い");
            for (int k = 0; k <= 2; k++) {
                double perDay = t.pointsPerTick(p, (long) t.normalArea(p) * k) * PhaseTable.TICKS_PER_DAY;
                helper.assertTrue(Math.abs(perDay - (time + area * k)) < 1.0E-6D, "F" + p + " 面積 ×" + k + " の 1 日の点 " + perDay);
            }
        }
        int last = lastAuto(t);
        double d0 = daysTo(t, last, 0.0D), d1 = daysTo(t, last, 1.0D), d2 = daysTo(t, last, 2.0D);
        double expected1 = d0 * time / (time + area);
        helper.assertTrue(Math.abs(d1 - expected1) < 1.0E-6D, "ふつうの面積の日数 " + d1 + "(期待 " + expected1 + ")");
        helper.assertTrue(d0 > d1 && d1 > d2, "面積が広いほど早く着かない: " + d0 + " / " + d1 + " / " + d2);
        helper.succeed();
    }

    /** 撃破の点は 1 日 killsPerDayCap で頭打ち(植物側の Mob の種類の点もここで見る)。 */
    @GameTest(template = "gametest_empty7")
    public static void killPointsCapPerDay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseTable t = DesignData.phases();
        int cap = t.killPointsPerDayCap();
        PhaseData data = PhaseData.createDetachedForTests(e -> { });
        data.setPhase(level, 1);
        int a = data.addKillPoints(level, cap - 1);
        int b = data.addKillPoints(level, 5);
        int c = data.addKillPoints(level, 5);
        helper.assertTrue(a == cap - 1 && b == 1 && c == 0, "頭打ちにならない: " + a + " / " + b + " / " + c + "(上限 " + cap + ")");
        helper.assertTrue(data.killPointsToday() == cap, "今日の撃破の点 " + data.killPointsToday());
        // 種類: G1 段階 0 = 0、G1 の 1 段目 = 1、植物側でない Mob は数えない
        helper.assertTrue(KillPoints.kindOf(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(OvergrownVegetation.id("parasitized_zombie"))) == 0, "段階 0 のゾンビが stage0 でない");
        helper.assertTrue(KillPoints.kindOf(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(OvergrownVegetation.id("developed_parasitized_zombie"))) == 1, "1 段目のゾンビが evolved でない");
        helper.assertTrue(KillPoints.kindOf(EntityType.ZOMBIE) == -1, "バニラのゾンビを数えた");
        helper.succeed();
    }

    /** 今の面積(台帳)が減ると、1 日の点の入りが減る(押し返すとフェーズが遅れる)。 */
    @GameTest(template = "gametest_empty7")
    public static void shrinkingAreaLowersIncome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.createDetachedForTests(e -> { });
        data.setPhase(level, 1);
        int base = 9_100_000 + level.random.nextInt(10_000) * 64; // 世界の外れ(本物の台帳とかぶらない所)
        int n = 200;
        double empty = data.incomePerDay();
        for (int i = 0; i < n; i++) data.adoptOvergrownChunk(level, new ChunkPos(base + i, base));
        double full = data.incomePerDay();
        for (int i = 0; i < n / 2; i++) data.forgetOvergrownChunk(new ChunkPos(base + i, base));
        double half = data.incomePerDay();
        for (int i = n / 2; i < n; i++) data.forgetOvergrownChunk(new ChunkPos(base + i, base)); // 胞子の場の写しにも残さない
        helper.assertTrue(full > half && half > empty, "面積が減っても点の入りが減らない: " + empty + " / " + half + " / " + full);
        PhaseTable t = DesignData.phases();
        double expectedHalf = t.timePointsPerDay() + t.areaPointsPerDay() * (n / 2) / (double) t.normalArea(1);
        helper.assertTrue(Math.abs(half - expectedHalf) < 1.0E-6D, "半分にしたときの 1 日の点 " + half + "(期待 " + expectedHalf + ")");
        helper.succeed();
    }

    private PhasePointsGameTests() {
    }
}
