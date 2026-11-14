package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.PhaseEvents;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import com.yayuy.overgrownvegetation.phase.PhaseStages;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * フェーズの小さな段 導入 → 定着 → 最盛(2026-11-14)。
 * ワールドの本物の {@link PhaseData} は全テストで共有なので触らず、切り離した入れ物({@link PhaseData#createDetachedForTests})で試す。
 * 期待値は設計データ(隔離フォルダの写しかもしれない)から引く —— 0.3 / 0.7 / 1.0・1/3 / 2/3 を決め打ちしない。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PhaseStageGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PhaseStageGameTests.class);
        }

        private Registration() {
        }
    }

    /** 次のフェーズの閾値があり、自然に上がるフェーズ(既定では F1〜F6)の最初の 1 つ。無ければ -1。 */
    private static int advancingPhase(PhaseTable table) {
        for (int p = 1; p < PhaseTable.MAX_PHASE; p++) {
            if (table.stageSpanPoints(p) != Long.MAX_VALUE && table.autoAdvance(p + 1)) {
                return p;
            }
        }
        return -1;
    }

    /** ポイントが定着・最盛の始まりを越えたところで段が変わる(純粋な計算)。 */
    @GameTest(template = "gametest_empty7")
    public static void stageBoundariesFollowPoints(GameTestHelper helper) {
        PhaseTable table = DesignData.phases();
        int p = advancingPhase(table);
        if (p < 0) {
            helper.fail("自然に上がるフェーズが設計データに無い");
            return;
        }
        long est = table.stageStartPoints(p, PhaseStage.ESTABLISHED);
        long peak = table.stageStartPoints(p, PhaseStage.PEAK);
        long span = table.stageSpanPoints(p);
        helper.assertTrue(0 < est && est < peak && peak < span,
                "区切りの並びがおかしい: est=" + est + " peak=" + peak + " span=" + span);
        helper.assertTrue(table.stageFor(p, 0) == PhaseStage.INTRO, "0 点は導入");
        helper.assertTrue(table.stageFor(p, est - 1) == PhaseStage.INTRO, "定着の 1 点前は導入");
        helper.assertTrue(table.stageFor(p, est) == PhaseStage.ESTABLISHED, "定着の始まりで定着");
        helper.assertTrue(table.stageFor(p, peak - 1) == PhaseStage.ESTABLISHED, "最盛の 1 点前は定着");
        helper.assertTrue(table.stageFor(p, peak) == PhaseStage.PEAK, "最盛の始まりで最盛");
        helper.assertTrue(table.stageFor(p, span * 3) == PhaseStage.PEAK, "閾値を越えても最盛");
        // 区切りは設定の割合どおり(切り上げ)
        helper.assertTrue(est == (long) Math.ceil(span * table.stageFrom(p, PhaseStage.ESTABLISHED)), "定着の始まりの計算");
        helper.assertTrue(table.stageFor(PhaseTable.MAX_PHASE, 123L) == null, "最後のフェーズは物差しが無い");
        helper.succeed();
    }

    /**
     * json の {@code stages} が読まれる(global の区切り・フェーズの倍率の上書き・書いていないフェーズは global)。
     * 既定値と同じ値だと読まれなくても通ってしまうので、わざと違う値で作る。
     */
    @GameTest(template = "gametest_empty7")
    public static void stagesJsonOverridesAreRead(GameTestHelper helper) {
        PhaseTable.Json j = new PhaseTable.Json();
        j.global = new PhaseTable.Global();
        j.global.stages = new PhaseTable.Stages();
        j.global.stages.establishedFrom = 0.5D;
        j.global.stages.peak = 0.9D;
        j.phases = new java.util.HashMap<>();
        PhaseTable.Phase three = new PhaseTable.Phase();
        three.stages = new PhaseTable.Stages();
        three.stages.intro = 0.11D;
        three.stages.peakFrom = 0.8D;
        j.phases.put("3", three);
        PhaseTable t = PhaseTable.fromJson(j);
        helper.assertTrue(t.stageFrom(2, PhaseStage.ESTABLISHED) == 0.5D, "global の establishedFrom: " + t.stageFrom(2, PhaseStage.ESTABLISHED));
        helper.assertTrue(t.stageMultiplier(2, PhaseStage.PEAK) == 0.9D, "global の peak");
        helper.assertTrue(t.stageMultiplier(3, PhaseStage.INTRO) == 0.11D, "フェーズ 3 の intro の上書き");
        helper.assertTrue(t.stageMultiplier(3, PhaseStage.PEAK) == 0.9D, "フェーズ 3 の書いていない peak は global");
        helper.assertTrue(t.stageFrom(3, PhaseStage.PEAK) == 0.8D, "フェーズ 3 の peakFrom の上書き");
        helper.assertTrue(t.stageMultiplier(2, PhaseStage.INTRO) == PhaseTable.DEFAULT.stageMultiplier(2, PhaseStage.INTRO),
                "どこにも書いていない欄は既定");
        // 実際に読み込まれた設計データにも stages の欄が届いている(各フェーズに intro を書いてある)
        helper.assertTrue(DesignData.phases().phase(1).stages != null, "読み込んだ phases.json のフェーズ 1 に stages が無い(json が読めていない?)");
        helper.succeed();
    }

    /** ポイントを足すと段が上がってイベントが 1 回ずつ流れ、閾値でフェーズが上がると導入に戻る。 */
    @GameTest(template = "gametest_empty7")
    public static void stageRisesThenResetsOnPhaseUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseTable table = DesignData.phases();
        int p = advancingPhase(table);
        if (p < 0) {
            helper.fail("自然に上がるフェーズが設計データに無い");
            return;
        }
        List<Event> events = new ArrayList<>();
        PhaseData data = PhaseData.createDetachedForTests(events::add);
        data.setPhase(level, p);
        helper.assertTrue(data.getPhase() == p && data.getStage() == PhaseStage.INTRO, "入った直後は導入");
        events.clear();

        long est = table.stageStartPoints(p, PhaseStage.ESTABLISHED);
        long peak = table.stageStartPoints(p, PhaseStage.PEAK);
        long span = table.stageSpanPoints(p);

        data.addPointsUnscaledForTests(level, est - 1);
        helper.assertTrue(data.getStage() == PhaseStage.INTRO && events.isEmpty(), "定着の 1 点前はまだ導入: " + data.getStage());
        data.addPointsUnscaledForTests(level, 1);
        helper.assertTrue(data.getStage() == PhaseStage.ESTABLISHED, "1/3 を越えて定着: " + data.getStage());
        helper.assertTrue(events.size() == 1 && events.get(0) instanceof PhaseEvents.StageChanged sc
                        && sc.oldStage() == PhaseStage.INTRO && sc.newStage() == PhaseStage.ESTABLISHED && sc.natural(),
                "定着へのイベントが 1 回: " + events);
        events.clear();

        data.addPointsUnscaledForTests(level, peak - est);
        helper.assertTrue(data.getStage() == PhaseStage.PEAK, "2/3 を越えて最盛: " + data.getStage());
        helper.assertTrue(events.size() == 1 && events.get(0) instanceof PhaseEvents.StageChanged sc2
                        && sc2.newStage() == PhaseStage.PEAK, "最盛へのイベントが 1 回: " + events);
        events.clear();

        data.addPointsUnscaledForTests(level, span - peak);
        helper.assertTrue(data.getPhase() == p + 1, "閾値でフェーズが上がる: " + data.getPhase());
        helper.assertTrue(data.getStage() == PhaseStage.INTRO, "上がったら導入に戻る: " + data.getStage());
        helper.assertTrue(events.stream().anyMatch(e -> e instanceof PhaseEvents.PhaseChanged pc
                        && pc.oldPhase() == p && pc.newPhase() == p + 1 && pc.natural() && pc.advanced()),
                "フェーズのイベント: " + events);
        helper.assertTrue(events.stream().noneMatch(e -> e instanceof PhaseEvents.StageChanged),
                "フェーズが上がったときは段のイベントを流さない: " + events);
        helper.succeed();
    }

    /** 倍率の読み方: 解禁前 0・同じフェーズは今の段・過ぎたフェーズは最盛・フェーズ 0 は 0。 */
    @GameTest(template = "gametest_empty7")
    public static void strengthReadsStageMultiplier(GameTestHelper helper) {
        PhaseTable table = DesignData.phases();
        for (PhaseStage s : PhaseStage.values()) {
            helper.assertTrue(PhaseStages.strength(table, 4, s, 4) == table.stageMultiplier(4, s), "同じフェーズは段の倍率: " + s);
            helper.assertTrue(PhaseStages.strength(table, 4, s, 5) == 0.0D, "まだ解禁されていない物は 0: " + s);
            helper.assertTrue(PhaseStages.strength(table, 5, s, 4) == table.stageMultiplier(4, PhaseStage.PEAK),
                    "過ぎたフェーズの物は最盛のまま: " + s);
            helper.assertTrue(PhaseStages.strength(table, 0, s, 1) == 0.0D, "フェーズ 0 は 0");
        }
        helper.assertTrue(table.stageMultiplier(2, PhaseStage.INTRO) <= table.stageMultiplier(2, PhaseStage.ESTABLISHED)
                        && table.stageMultiplier(2, PhaseStage.ESTABLISHED) <= table.stageMultiplier(2, PhaseStage.PEAK),
                "導入 ≤ 定着 ≤ 最盛");
        // 生きたワールドの口も答えを返す(値は他のテストの状態次第なので範囲だけ)
        double live = PhaseStages.currentStrength(helper.getLevel());
        helper.assertTrue(live >= 0.0D, "生きたワールドの倍率: " + live);
        helper.succeed();
    }

    /** 段は保存して読み直しても同じ。段の欄の無い古いセーブはポイントから黙って求める。 */
    @GameTest(template = "gametest_empty7")
    public static void stageSurvivesSaveAndLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseTable table = DesignData.phases();
        int p = advancingPhase(table);
        if (p < 0) {
            helper.fail("自然に上がるフェーズが設計データに無い");
            return;
        }
        PhaseData data = PhaseData.createDetachedForTests(e -> { });
        data.setPhase(level, p);
        data.addPointsUnscaledForTests(level, table.stageStartPoints(p, PhaseStage.ESTABLISHED));
        helper.assertTrue(data.getStage() == PhaseStage.ESTABLISHED, "定着になっている");
        CompoundTag tag = data.save(new CompoundTag(), level.registryAccess());
        List<Event> events = new ArrayList<>();
        PhaseData loaded = PhaseData.loadDetachedForTests(tag, events::add);
        helper.assertTrue(loaded.getPhase() == p && loaded.getStage() == PhaseStage.ESTABLISHED
                        && loaded.getPoints() == data.getPoints(), "読み直しても同じ: " + loaded.getPhase() + " " + loaded.getStage());

        // 古いセーブ(段の欄が無い)・ポイントは最盛の位置
        CompoundTag old = tag.copy();
        old.remove("stage");
        old.putLong("points", table.stageStartPoints(p, PhaseStage.PEAK));
        PhaseData migrated = PhaseData.loadDetachedForTests(old, events::add);
        helper.assertTrue(migrated.getStage() == PhaseStage.PEAK, "古いセーブはポイントから求める: " + migrated.getStage());
        helper.assertTrue(events.isEmpty(), "読み込みではイベントを流さない: " + events);
        helper.succeed();
    }

    /**
     * F8 以降の扱い: 次のフェーズが自然に上がらないフェーズでも閾値の数字があれば段は最盛まで進み、フェーズは上がらない。
     * 最後のフェーズ(閾値の物差しが無い)は導入のまま、コマンドの口で段を決められる。
     */
    @GameTest(template = "gametest_empty7")
    public static void nonAdvancingPhasesKeepStage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseTable table = DesignData.phases();
        // 次が autoAdvance: false で、閾値の数字が書いてあるフェーズ(既定では F7〜F9)
        int stuck = -1;
        for (int p = 1; p < PhaseTable.MAX_PHASE; p++) {
            if (!table.autoAdvance(p + 1) && table.stageSpanPoints(p) != Long.MAX_VALUE) {
                stuck = p;
                break;
            }
        }
        if (stuck > 0) {
            PhaseData data = PhaseData.createDetachedForTests(e -> { });
            data.setPhase(level, stuck);
            data.addPointsUnscaledForTests(level, table.stageSpanPoints(stuck) + 1000L);
            helper.assertTrue(data.getPhase() == stuck, "自然には上がらない: " + data.getPhase());
            helper.assertTrue(data.getStage() == PhaseStage.PEAK, "段は最盛で止まる: " + data.getStage());
        }

        List<Event> events = new ArrayList<>();
        PhaseData last = PhaseData.createDetachedForTests(events::add);
        last.setPhase(level, PhaseTable.MAX_PHASE);
        events.clear();
        last.addPointsUnscaledForTests(level, 1_000_000_000L);
        helper.assertTrue(last.getStage() == PhaseStage.INTRO && last.getPoints() == 0L, "最後のフェーズはポイントも段も動かない");
        long pts = last.setStage(level, PhaseStage.PEAK);
        helper.assertTrue(pts < 0L && last.getStage() == PhaseStage.PEAK, "物差しが無ければ段だけ決まる");
        helper.assertTrue(events.size() == 1 && events.get(0) instanceof PhaseEvents.StageChanged sc && !sc.natural(),
                "コマンドの段のイベント: " + events);

        // コマンドで段を下げる(物差しのあるフェーズ): ポイントがその段の始まりに合う
        int p = advancingPhase(table);
        if (p > 0) {
            PhaseData d = PhaseData.createDetachedForTests(e -> { });
            d.setPhase(level, p);
            d.setStage(level, PhaseStage.PEAK);
            helper.assertTrue(d.getPoints() == table.stageStartPoints(p, PhaseStage.PEAK), "最盛の始まりへ");
            d.setStage(level, PhaseStage.INTRO);
            helper.assertTrue(d.getStage() == PhaseStage.INTRO && d.getPoints() == 0L, "導入へ下げられる");
        }
        helper.succeed();
    }
}
