package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.phase.KillPoints;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * フェーズのポイントの式(2026-11-14 夕 ユーザー決定)のテスト: 時間 12 秒で 1 点・面積 毎秒 今の過繁茂のチャンク数 × 0.01・
 * 撃破は今のフェーズの閾値の %(上限なし)。期待値はどれも設計データ({@code phases.json})から計算する。世界の本物の {@link PhaseData} には触らない。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PhasePointsGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterPhasePointsGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(PhasePointsGameTests.class);
        }

        private Registration() {
        }
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= 1.0E-6D * Math.max(1.0D, Math.abs(b));
    }

    /** 面積 0 なら 1 日 timePerDay 点(既定 100 = 12 秒で 1 点)。 */
    @GameTest(template = "gametest_empty7")
    public static void timeOnlyPoints(GameTestHelper helper) {
        PhaseTable t = DesignData.phases();
        double perDay = t.pointsPerTick(0L) * PhaseTable.TICKS_PER_DAY;
        helper.assertTrue(near(perDay, t.timePointsPerDay()), "面積 0 の 1 日の点 " + perDay + "(期待 " + t.timePointsPerDay() + ")");
        double secondsPerPoint = 1.0D / (t.pointsPerTick(0L) * 20.0D);
        helper.assertTrue(near(secondsPerPoint, 24000.0D / 20.0D / t.timePointsPerDay()), "1 点に " + secondsPerPoint + " 秒");
        helper.succeed();
    }

    /** 面積の点は毎秒「チャンク数 × areaPerChunkPerSecond」。台帳(今の過繁茂)が減ると点の入りが減る。 */
    @GameTest(template = "gametest_empty7")
    public static void areaPointsFollowCurrentChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseTable t = DesignData.phases();
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
        double perChunkPerDay = t.areaPointsPerChunkPerSecond() * PhaseTable.TICKS_PER_DAY / 20.0D;
        helper.assertTrue(near(full - empty, n * perChunkPerDay), "チャンク " + n + " の 1 日の面積の点 " + (full - empty) + "(期待 " + n * perChunkPerDay + ")");
        helper.assertTrue(full > half && half > empty, "チャンクが減っても点の入りが減らない: " + empty + " / " + half + " / " + full);
        helper.assertTrue(near(half - empty, (n / 2) * perChunkPerDay), "半分にしたときの面積の点 " + (half - empty));
        helper.succeed();
    }

    /** 撃破は 1 体で今のフェーズの閾値 × %。上限なし(%が 0.1 なら 1000 体で 1 フェーズ分)。種類の分け方も見る。 */
    @GameTest(template = "gametest_empty7")
    public static void killPointsArePercentWithoutCap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseTable t = DesignData.phases();
        PhaseData data = PhaseData.createDetachedForTests(e -> { });
        data.setPhase(level, 1);
        long span = t.stageSpanPoints(1);
        double one = data.addKillPoints(level, 0);
        helper.assertTrue(near(one, span * t.killPercent(0) / 100.0D), "段階 0 の 1 体 " + one + "(閾値 " + span + " の " + t.killPercent(0) + "%)");
        int more = (int) Math.max(1, Math.floor(0.5D * span / one) - 1); // フェーズが上がらない数(上がると 1 体の点が変わる)
        for (int i = 0; i < more; i++) data.addKillPoints(level, 0);
        helper.assertTrue(near(data.killPointsTotal(), (more + 1) * one), "上限があるように見える: " + data.killPointsTotal() + "(期待 " + (more + 1) * one + ")");
        double g4 = t.killPoints(1, 4);
        helper.assertTrue(near(g4, span * t.killPercent(4) / 100.0D) && g4 > one, "G4 の点 " + g4);
        helper.assertTrue(KillPoints.kindOf(BuiltInRegistries.ENTITY_TYPE.get(OvergrownVegetation.id("parasitized_zombie"))) == 0, "段階 0 のゾンビが stage0 でない");
        helper.assertTrue(KillPoints.kindOf(BuiltInRegistries.ENTITY_TYPE.get(OvergrownVegetation.id("developed_parasitized_zombie"))) == 1, "1 段目のゾンビが evolved でない");
        helper.assertTrue(KillPoints.kindOf(EntityType.ZOMBIE) == -1, "バニラのゾンビを数えた");
        helper.succeed();
    }

    /** 小さな段の区切りは、閾値に各フェーズの割合を掛けた所(式が変わっても、閾値が物差しのまま)。 */
    @GameTest(template = "gametest_empty7")
    public static void stageBoundsStayAtThirds(GameTestHelper helper) {
        PhaseTable t = DesignData.phases();
        for (int p = 1; p < PhaseTable.MAX_PHASE; p++) {
            long span = t.stageSpanPoints(p);
            if (span == Long.MAX_VALUE) continue;
            long est = t.stageStartPoints(p, PhaseStage.ESTABLISHED);
            long peak = t.stageStartPoints(p, PhaseStage.PEAK);
            long expEst = (long) Math.ceil(span * t.stageFrom(p, PhaseStage.ESTABLISHED));
            long expPeak = (long) Math.ceil(span * t.stageFrom(p, PhaseStage.PEAK));
            helper.assertTrue(est == expEst && peak == expPeak, "F" + p + " の区切り " + est + " / " + peak + "(期待 " + expEst + " / " + expPeak + ")");
            // 11-18 夜: 区切りはフェーズごと(日数の 3 等分に合わせた点の割合。phases.json の各フェーズの stages)。
            // 点は広さとともに加速するので、日数の 1/3・2/3 は点の 1/3・2/3 より手前 = 0 < 定着 < 最盛 < 閾値 で、定着は 1/3 以下
            helper.assertTrue(0 < est && est < peak && peak < span && est <= Math.ceil(span / 3.0D) + 1,
                    "F" + p + " の区切りがおかしい: " + est + " / " + peak + "(閾値 " + span + ")");
        }
        helper.succeed();
    }

    /** 新しい閾値(11-14 夜の案)のどのフェーズでも、ポイントが 1/3・2/3 を越えた所で定着・最盛に上がり、閾値で次のフェーズへ上がる。 */
    @GameTest(template = "gametest_empty7")
    public static void stagesChangeAtNewThresholds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseTable t = DesignData.phases();
        for (int p = 1; p < PhaseTable.MAX_PHASE; p++) {
            long span = t.stageSpanPoints(p);
            if (span == Long.MAX_VALUE || !t.autoAdvance(p + 1)) continue;
            long est = t.stageStartPoints(p, PhaseStage.ESTABLISHED);
            long peak = t.stageStartPoints(p, PhaseStage.PEAK);
            PhaseData data = PhaseData.createDetachedForTests(e -> { });
            data.setPhase(level, p);
            data.addPointsUnscaledForTests(level, est - 1);
            helper.assertTrue(data.getStage() == PhaseStage.INTRO, "F" + p + " " + (est - 1) + " 点で導入でない: " + data.getStage());
            data.addPointsUnscaledForTests(level, 1);
            helper.assertTrue(data.getStage() == PhaseStage.ESTABLISHED, "F" + p + " " + est + " 点で定着でない: " + data.getStage());
            data.addPointsUnscaledForTests(level, peak - est);
            helper.assertTrue(data.getStage() == PhaseStage.PEAK, "F" + p + " " + peak + " 点で最盛でない: " + data.getStage());
            data.addPointsUnscaledForTests(level, span - peak);
            helper.assertTrue(data.getPhase() == p + 1, "F" + p + " 閾値 " + span + " で次のフェーズへ上がらない: F" + data.getPhase());
        }
        helper.succeed();
    }

    private PhasePointsGameTests() {
    }
}
