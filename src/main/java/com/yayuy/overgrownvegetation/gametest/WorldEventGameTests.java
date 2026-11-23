package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.ley.LeyPumpBlockEntity;
import com.yayuy.overgrownvegetation.network.WorldEventPayload;
import com.yayuy.overgrownvegetation.registry.ModLey;
import com.yayuy.overgrownvegetation.worldevent.WorldEventKind;
import com.yayuy.overgrownvegetation.worldevent.WorldEventSchedule;
import com.yayuy.overgrownvegetation.worldevent.WorldEventTable;
import com.yayuy.overgrownvegetation.worldevent.WorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongToDoubleFunction;

/**
 * ワールドイベント 7 つ(2026-11-16 world-events)。日程 {@link WorldEventSchedule} は素の Java なので、ワールドの時刻・フェーズ・本物の保存には触らず
 * 偽の様子({@link FakeEnv})で何千日も回して比べる(並べて走るほかのテストへ漏れないように)。地脈の大潮の効き目だけは本物の汲み上げ器と本物の日程を使うので、
 * 別の batch にして最後に止める。期待値は設計値({@code world_events.json})から。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class WorldEventGameTests {

    private static final Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final String TEMPLATE = "gametest_empty7";
    /** 本物の日程を使う(地脈の大潮)。ほかの batch と同時に走らせない。 */
    private static final String TIDE_BATCH = "world_events_tide";
    private static final long SEED = 0x1234ABCDL;
    private static final int STEP = 20;

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterWorldEventGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(WorldEventGameTests.class);
        }

        private Registration() {
        }
    }

    private WorldEventGameTests() {
    }

    static final class FakeEnv implements WorldEventSchedule.Env {
        int phase = 10;
        boolean rain;
        LongToDoubleFunction fog = t -> 1.0D;

        @Override
        public int phase() {
            return phase;
        }

        @Override
        public boolean raining() {
            return rain;
        }

        @Override
        public double fog(long t) {
            return fog.applyAsDouble(t);
        }
    }

    record Run(WorldEventKind kind, long start, long end) {
    }

    /** from から days 日、STEP tick ごとに回して、起きたイベントを並べる。 */
    private static List<Run> simulate(WorldEventSchedule s, FakeEnv env, WorldEventTable tab, long from, int days) {
        List<Run> out = new ArrayList<>();
        WorldEventKind was = s.active();
        for (long t = from; t < from + (long) days * WorldEventSchedule.DAY; t += STEP) {
            s.step(t, SEED, tab, env);
            WorldEventKind now = s.active();
            if (now != null && (was == null || out.isEmpty() || out.get(out.size() - 1).start() != s.start())) {
                out.add(new Run(now, s.start(), s.end()));
            }
            was = now;
        }
        return out;
    }

    /** 1 種だけ重さ 1(ほかは 0)の設計値。 */
    private static WorldEventTable only(WorldEventKind k) {
        WorldEventTable.Json j = new WorldEventTable.Json();
        j.events = new HashMap<>();
        for (WorldEventKind x : WorldEventKind.values()) {
            WorldEventTable.EventJson e = new WorldEventTable.EventJson();
            e.weight = x == k ? 1.0D : 0.0D;
            j.events.put(x.id, e);
        }
        return WorldEventTable.fromJson(j);
    }

    private static long day(long t) {
        return WorldEventSchedule.day(t);
    }

    // ---------------------------------------------------------------- 日程の間隔・同時に 1 つ

    /** 次のくじは前の始まりの日から 7〜15 日(雨なし・霧はいつもあるので待ちは 1 日以内)。重なりは無く、F10 なら 7 つとも起きる。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void intervalAndOneAtATime(GameTestHelper helper) {
        WorldEventTable tab = DesignData.worldEvents();
        FakeEnv env = new FakeEnv();
        List<Run> runs = simulate(new WorldEventSchedule(), env, tab, 0L, 1500);
        helper.assertTrue(runs.size() > 60, "1500 日で " + runs.size() + " 回しか起きない");
        helper.assertTrue(day(runs.get(0).start()) <= (long) tab.intervalMaxDays + 1, "最初の 1 回が遅い: " + day(runs.get(0).start()) + " 日目");
        Map<WorldEventKind, Integer> count = new EnumMap<>(WorldEventKind.class);
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            count.merge(r.kind(), 1, Integer::sum);
            helper.assertTrue(r.end() > r.start(), "長さが 0: " + r);
            helper.assertTrue(r.end() - r.start() <= tab.spec(r.kind()).length(), "設計値より長い: " + r);
            if (i > 0) {
                Run p = runs.get(i - 1);
                long gap = day(r.start()) - day(p.start());
                helper.assertTrue(r.start() >= p.end(), "重なった: " + p + " と " + r);
                helper.assertTrue(gap >= (long) tab.intervalMinDays && gap <= (long) tab.intervalMaxDays + 1,
                        "間隔 " + gap + " 日(" + p + " → " + r + ")");
            }
        }
        for (WorldEventKind k : WorldEventKind.values()) {
            if (tab.spec(k).weight() <= 0) {
                helper.assertTrue(!count.containsKey(k), k.id + " は重さ 0 なのにくじで起きた");
                continue;
            }
            helper.assertTrue(count.getOrDefault(k, 0) > 0, k.id + " が一度も起きない: " + count);
        }
        LOG.info("[ワールドイベント] 1500 日で {} 回: {}", runs.size(), count);
        helper.succeed();
    }

    /** 起きている間に別のイベントを起こすと、前の物は消えて 1 つだけになる。止めると何も起きていない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void forcedReplacesAndStops(GameTestHelper helper) {
        WorldEventTable tab = DesignData.worldEvents();
        WorldEventSchedule s = new WorldEventSchedule();
        FakeEnv env = new FakeEnv();
        s.step(1000L, SEED, tab, env);
        long due = s.nextDueDay();
        s.forceStart(WorldEventKind.HALO, 1000L, 0.0D, tab);
        s.forceStart(WorldEventKind.ECLIPSE, 1000L, 0.5D, tab);
        helper.assertTrue(s.active() == WorldEventKind.ECLIPSE, "日食になっていない: " + s.active());
        helper.assertTrue(Math.abs(s.progress(1000L) - 0.5D) < 1e-3, "途中から始まらない: " + s.progress(1000L));
        s.step(1020L, SEED, tab, env);
        helper.assertTrue(s.active() == WorldEventKind.ECLIPSE, "1 tick で消えた");
        s.stop(1040L, SEED, tab);
        helper.assertTrue(s.active() == null && s.state() == WorldEventSchedule.State.IDLE, "止まらない");
        helper.assertTrue(s.nextDueDay() == due, "確かめ用の止めで日程が変わった: " + due + " → " + s.nextDueDay());
        helper.succeed();
    }

    // ---------------------------------------------------------------- フェーズ

    /** F3 より前は胞子のオーロラと光る潮が出ない(ほかは出る)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void plantEventsWaitForF3(GameTestHelper helper) {
        WorldEventTable tab = DesignData.worldEvents();
        helper.assertTrue(tab.spec(WorldEventKind.AURORA).minPhase() == 3 && tab.spec(WorldEventKind.GLOWING_TIDE).minPhase() == 3,
                "設計値の minPhase が 3 でない");
        FakeEnv env = new FakeEnv();
        env.phase = 2;
        List<Run> runs = simulate(new WorldEventSchedule(), env, tab, 0L, 1500);
        helper.assertTrue(runs.size() > 60, "F2 で " + runs.size() + " 回しか起きない");
        for (Run r : runs) {
            helper.assertTrue(r.kind() != WorldEventKind.AURORA && r.kind() != WorldEventKind.GLOWING_TIDE, "F2 で起きた: " + r);
        }
        env.phase = 3;
        List<Run> later = simulate(new WorldEventSchedule(), env, tab, 0L, 1500);
        helper.assertTrue(later.stream().anyMatch(r -> r.kind() == WorldEventKind.AURORA), "F3 でオーロラが起きない");
        helper.succeed();
    }

    // ---------------------------------------------------------------- 霧

    /** 地平線を歩く影は霞・霧の日だけ: 霧が無ければ起きず(ほかは起きる)、霧のある朝だけなら必ず霧の中・窓の中で始まり、霧の残りで切れる。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void shadowOnlyOnFoggyDays(GameTestHelper helper) {
        WorldEventTable tab = DesignData.worldEvents();
        FakeEnv env = new FakeEnv();
        env.fog = t -> 0.0D;
        List<Run> dry = simulate(new WorldEventSchedule(), env, tab, 0L, 1500);
        helper.assertTrue(dry.size() > 60, "霧なしで " + dry.size() + " 回しか起きない(影で詰まった?)");
        helper.assertTrue(dry.stream().noneMatch(r -> r.kind() == WorldEventKind.HORIZON_SHADOW), "霧が無いのに影が出た");

        // 4 日に 1 度、4 時〜9 時に霧(9 時に晴れる)
        LongToDoubleFunction fog = t -> {
            long tod = Math.floorMod(t, (long) WorldEventSchedule.DAY);
            long d = day(t + 2000L);
            return d % 4 == 0 && (tod >= 22000L || tod < 3000L) ? 1.0D : 0.0D;
        };
        env.fog = fog;
        WorldEventTable shadowOnly = only(WorldEventKind.HORIZON_SHADOW);
        List<Run> foggy = simulate(new WorldEventSchedule(), env, shadowOnly, 0L, 600);
        helper.assertTrue(foggy.size() > 20, "霧の朝があるのに影が " + foggy.size() + " 回");
        for (Run r : foggy) {
            long tod = Math.floorMod(r.start(), (long) WorldEventSchedule.DAY);
            helper.assertTrue(fog.applyAsDouble(r.start()) >= shadowOnly.shadowMinFog, "霧の無い時に始まった: " + r);
            helper.assertTrue(tod >= 22500L || tod <= 1500L, "朝の窓の外で始まった: tod " + tod);
            helper.assertTrue(fog.applyAsDouble(r.end() - 1) > 0 || r.end() - r.start() < shadowOnly.spec(WorldEventKind.HORIZON_SHADOW).length(),
                    "霧が晴れた後も続く: " + r);
            long clearAt = (day(r.start() + 2000L)) * WorldEventSchedule.DAY + 3000L;
            helper.assertTrue(r.end() <= clearAt + 100, "霧が晴れた後まで続く: " + r + "(晴れ " + clearAt + ")");
        }
        helper.succeed();
    }

    /** 本物の濃霧の予定(雨の無い世界)で 3000 日: 影が起きる回数・長さ・イベントの間隔の最長を測る。影は起き、間隔は延びすぎない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void shadowWithRealFog(GameTestHelper helper) {
        WorldEventTable tab = DesignData.worldEvents();
        FakeEnv env = new FakeEnv();
        long fogSeed = 98765L;
        env.fog = t -> com.yayuy.overgrownvegetation.spore.field.PrevailingWind.fogLevel(fogSeed, t,
                com.yayuy.overgrownvegetation.spore.field.PrevailingWind.Weather.CLEAR, null);
        List<Run> runs = simulate(new WorldEventSchedule(), env, tab, 0L, 3000);
        int shadows = 0;
        long minLen = Long.MAX_VALUE, maxLen = 0, sumLen = 0, maxGap = 0;
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            if (r.kind() == WorldEventKind.HORIZON_SHADOW) {
                shadows++;
                long len = r.end() - r.start();
                minLen = Math.min(minLen, len);
                maxLen = Math.max(maxLen, len);
                sumLen += len;
            }
            if (i > 0) maxGap = Math.max(maxGap, day(r.start()) - day(runs.get(i - 1).start()));
        }
        LOG.info("[ワールドイベント] 本物の霧で 3000 日: {} 回・影 {} 回・影の長さ {}〜{} tick(平均 {})・間隔の最長 {} 日",
                runs.size(), shadows, shadows > 0 ? minLen : 0, maxLen, shadows > 0 ? sumLen / shadows : 0, maxGap);
        helper.assertTrue(shadows > 0, "本物の霧で影が一度も起きない");
        helper.assertTrue(maxGap <= (long) tab.intervalMaxDays + 1, "間隔の最長 " + maxGap + " 日");
        helper.succeed();
    }

    /** 空のイベントは雨の夜には始めない(窓を送り、あきらめの日を過ぎたら引き直す)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void skyEventsWaitOutRain(GameTestHelper helper) {
        WorldEventTable tab = only(WorldEventKind.METEOR_SHOWER);
        FakeEnv env = new FakeEnv();
        env.rain = true;
        List<Run> runs = simulate(new WorldEventSchedule(), env, tab, 0L, 60);
        helper.assertTrue(runs.isEmpty(), "雨なのに流星群が起きた: " + runs);
        env.rain = false;
        WorldEventSchedule s = new WorldEventSchedule();
        List<Run> clear = simulate(s, env, tab, 0L, 60);
        helper.assertTrue(!clear.isEmpty(), "晴れても流星群が起きない");
        helper.succeed();
    }

    // ---------------------------------------------------------------- 眠り・時刻を戻す


    /** 寝て夜を飛ばしたら見逃し(11-16 ユーザー「そのまま終わる」): 途中で寝ても、始まる前に寝ても、持ち越さずに次のくじへ。時刻を戻すと次のくじの日を数え直す。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void sleepMissesAndTimeRewind(GameTestHelper helper) {
        WorldEventTable tab = only(WorldEventKind.METEOR_SHOWER);
        helper.assertTrue(DesignData.worldEvents().maxCarry == 0, "設計値の maxCarry が 0 でない(持ち越しはやめた)");
        FakeEnv env = new FakeEnv();
        WorldEventSchedule s = new WorldEventSchedule();
        long t = 0L;
        while (s.active() == null && t < 40L * WorldEventSchedule.DAY) {
            s.step(t, SEED, tab, env);
            t += STEP;
        }
        helper.assertTrue(s.active() == WorldEventKind.METEOR_SHOWER, "流星群が起きない");
        long start = s.start(), end = s.end();
        s.step(start + 100L, SEED, tab, env);
        s.step(end + 50L, SEED, tab, env); // 始まってすぐ寝た
        helper.assertTrue(s.state() == WorldEventSchedule.State.IDLE && s.active() == null, "見逃しにならない: " + s.state() + " " + s.kind());
        long gap = s.nextDueDay() - day(start);
        helper.assertTrue(gap >= (long) tab.intervalMinDays && gap <= (long) tab.intervalMaxDays, "次のくじまで " + gap + " 日");

        // 次のくじを引いて待っている間(始まる前)に寝た
        t = end + 50L;
        while (s.state() != WorldEventSchedule.State.WAITING && t < end + 40L * WorldEventSchedule.DAY) {
            t += STEP;
            s.step(t, SEED, tab, env);
        }
        helper.assertTrue(s.state() == WorldEventSchedule.State.WAITING && s.start() > t, "待ちに入らない: " + s.state());
        long st2 = s.start(), en2 = s.end();
        s.step(en2 + 50L, SEED, tab, env);
        helper.assertTrue(s.state() == WorldEventSchedule.State.IDLE && s.kind() == null, "始まる前に寝ても見逃しにならない: " + s.state());
        long gap2 = s.nextDueDay() - day(st2);
        helper.assertTrue(gap2 >= (long) tab.intervalMinDays && gap2 <= (long) tab.intervalMaxDays, "見逃しの後の次のくじまで " + gap2 + " 日");

        s.step(10L, SEED, tab, env); // /time set で 0 日目へ戻した
        helper.assertTrue(s.nextDueDay() <= (long) tab.intervalMaxDays, "戻した後の次のくじが遠い: " + s.nextDueDay());
        helper.succeed();
    }

    // ---------------------------------------------------------------- フェーズの合図(F3 の短いオーロラ・F7 の胞子の柱)

    /** くじを止めた設計値(重さを全部 0。合図だけを見る)。 */
    private static WorldEventTable noLottery() {
        WorldEventTable.Json j = new WorldEventTable.Json();
        j.events = new HashMap<>();
        for (WorldEventKind x : WorldEventKind.values()) {
            WorldEventTable.EventJson e = new WorldEventTable.EventJson();
            e.weight = 0.0D;
            j.events.put(x.id, e);
        }
        return WorldEventTable.fromJson(j);
    }

    /** 回して、合図が起きたら止める。起きなければ until で止まる。 */
    private static long runUntilActive(WorldEventSchedule s, FakeEnv env, WorldEventTable tab, long from, long until) {
        long t = from;
        while (s.active() == null && t < until) {
            s.step(t, SEED, tab, env);
            if (s.active() != null) return t;
            t += STEP;
        }
        return t;
    }

    /**
     * F3 に上がると次の日没に短いオーロラ、F7 に上がると次の日没から胞子の柱(一夜)。2→8 と飛んでも両方が 1 夜ずつ順に出る。
     * 初めて見たフェーズ(古いワールドに入れたとき)では出さない。くじのイベントの夜は次の日没へ送る。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void phaseOmensAtNextSunset(GameTestHelper helper) {
        WorldEventTable tab = noLottery();
        WorldEventTable design = DesignData.worldEvents();
        helper.assertTrue(design.omenPhase(WorldEventKind.AURORA) == 3 && design.omenPhase(WorldEventKind.SPORE_PILLARS) == 7,
                "設計値の合図のフェーズが 3・7 でない");
        helper.assertTrue(design.spec(WorldEventKind.SPORE_PILLARS).weight() == 0, "胞子の柱がくじに入っている");
        FakeEnv env = new FakeEnv();
        env.phase = 5;
        WorldEventSchedule fresh = new WorldEventSchedule();
        simulate(fresh, env, tab, 0L, 3);
        helper.assertTrue(fresh.omens().isEmpty(), "初めて見たフェーズで合図が出た: " + fresh.omens());

        WorldEventSchedule s = new WorldEventSchedule();
        env.phase = 2;
        long t = 5L * WorldEventSchedule.DAY + 2000L;
        s.step(t, SEED, tab, env);
        env.phase = 8; // F2 → F8(F3・F7 をまたぐ)
        t += STEP;
        s.step(t, SEED, tab, env);
        helper.assertTrue(s.omens().equals(List.of(WorldEventKind.AURORA, WorldEventKind.SPORE_PILLARS)), "合図の並び: " + s.omens());
        helper.assertTrue(s.active() == null, "昼のうちに出た");
        t = runUntilActive(s, env, tab, t, t + 2L * WorldEventSchedule.DAY);
        helper.assertTrue(s.active() == WorldEventKind.AURORA && s.omenActive(), "オーロラが出ない: " + s.active());
        helper.assertTrue(Math.floorMod(s.activeStart(), (long) WorldEventSchedule.DAY) == tab.omenTod && day(s.activeStart()) == 5,
                "その日の日没でない: " + s.activeStart());
        helper.assertTrue(s.activeEnd() - s.activeStart() == tab.omenLength(WorldEventKind.AURORA), "短いオーロラの長さ " + (s.activeEnd() - s.activeStart()));
        helper.assertTrue(s.state() == WorldEventSchedule.State.IDLE, "くじの枠を使った");
        while (s.active() != null) {
            t += STEP;
            s.step(t, SEED, tab, env);
        }
        helper.assertTrue(s.omens().equals(List.of(WorldEventKind.SPORE_PILLARS)), "オーロラの後の並び: " + s.omens());
        t = runUntilActive(s, env, tab, t, t + 2L * WorldEventSchedule.DAY);
        helper.assertTrue(s.active() == WorldEventKind.SPORE_PILLARS && day(s.activeStart()) == 6, "次の夜に胞子の柱が出ない: " + s.active() + " " + s.activeStart());
        helper.assertTrue(s.activeEnd() - s.activeStart() == tab.omenLength(WorldEventKind.SPORE_PILLARS), "胞子の柱が一夜でない");

        // くじのイベントが起きている夜は次の日没へ
        WorldEventSchedule c = new WorldEventSchedule();
        env.phase = 2;
        long u = 10L * WorldEventSchedule.DAY + 12000L;
        c.step(u, SEED, tab, env);
        c.forceStart(WorldEventKind.METEOR_SHOWER, u, 0.0D, tab);
        env.phase = 3;
        for (long v = u + STEP; v < u + 3000L; v += STEP) c.step(v, SEED, tab, env);
        helper.assertTrue(c.active() == WorldEventKind.METEOR_SHOWER, "同時に 2 つになった・流星群が消えた: " + c.active());
        helper.assertTrue(day(c.omenStart()) == 11, "合図が次の日没へ送られない: " + c.omenStart());
        helper.succeed();
    }

    /** 合図の夜を寝て飛ばしたら見逃し(11-16 ユーザー「そのまま終わる」。始まる前に寝ても同じで、次の合図へ進む)。待っている合図・起きている合図は保存して読み直しても同じ(入り直し・再起動)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void phaseOmensSurviveSleepAndReload(GameTestHelper helper) {
        WorldEventTable tab = noLottery();
        FakeEnv env = new FakeEnv();
        WorldEventSchedule s = new WorldEventSchedule();
        env.phase = 6;
        long t = 3L * WorldEventSchedule.DAY + 1000L;
        s.step(t, SEED, tab, env);
        env.phase = 7;
        t += STEP;
        s.step(t, SEED, tab, env);
        WorldEventSchedule pending = WorldEventSchedule.load(s.save(new CompoundTag()).copy());
        helper.assertTrue(pending.omens().equals(s.omens()) && pending.omenStart() == s.omenStart(), "待っている合図が読み直しで消えた");
        t = runUntilActive(s, env, tab, t, t + WorldEventSchedule.DAY);
        helper.assertTrue(s.active() == WorldEventKind.SPORE_PILLARS, "胞子の柱が出ない");
        long st = s.activeStart(), en = s.activeEnd();
        s.step(st + 100L, SEED, tab, env);
        WorldEventSchedule mid = WorldEventSchedule.load(s.save(new CompoundTag()).copy());
        helper.assertTrue(mid.active() == WorldEventKind.SPORE_PILLARS && mid.activeStart() == st && mid.activeEnd() == en,
                "起きている合図が読み直しで変わった");
        s.step(en + 50L, SEED, tab, env); // 寝た
        helper.assertTrue(s.active() == null && s.omens().isEmpty(), "寝ても合図が残った: " + s.omens() + " " + s.omenStart());
        // 読み直した方も同じに進む
        mid.step(en + 50L, SEED, tab, env);
        helper.assertTrue(mid.omens().equals(s.omens()) && mid.omenStart() == s.omenStart(), "読み直した方の進みが違う");

        // 始まる前に寝た: F2 → F8 で 2 つ並び、オーロラの夜を寝て飛ばすとオーロラは見逃し、胞子の柱は次の夜に残る
        WorldEventSchedule b = new WorldEventSchedule();
        env.phase = 2;
        long u = 5L * WorldEventSchedule.DAY + 2000L;
        b.step(u, SEED, tab, env);
        env.phase = 8;
        b.step(u + STEP, SEED, tab, env);
        b.step(5L * WorldEventSchedule.DAY + 23500L, SEED, tab, env); // 18:30 の前から翌朝 5:30 まで寝た
        helper.assertTrue(b.omens().equals(List.of(WorldEventKind.SPORE_PILLARS)), "始まる前に寝たオーロラの後の並び: " + b.omens());
        long v = runUntilActive(b, env, tab, 5L * WorldEventSchedule.DAY + 23500L + STEP, 8L * WorldEventSchedule.DAY);
        helper.assertTrue(b.active() == WorldEventKind.SPORE_PILLARS && day(b.activeStart()) == 6, "胞子の柱が次の夜に出ない: " + b.active() + " " + v);
        helper.succeed();
    }

    // ---------------------------------------------------------------- 保存と復元

    /** 途中で保存して読み直すと、同じ様子・同じくじの並びで続く。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void saveAndRestore(GameTestHelper helper) {
        WorldEventTable tab = DesignData.worldEvents();
        FakeEnv env = new FakeEnv();
        WorldEventSchedule a = new WorldEventSchedule();
        long mid = 0L;
        // 起きている途中で止める
        for (long t = 0; t < 400L * WorldEventSchedule.DAY; t += STEP) {
            a.step(t, SEED, tab, env);
            mid = t;
            if (a.active() != null && a.progress(t) > 0.3) break;
        }
        helper.assertTrue(a.active() != null, "起きている所で止まらない");
        CompoundTag tag = a.save(new CompoundTag());
        WorldEventSchedule b = WorldEventSchedule.load(tag.copy());
        helper.assertTrue(b.active() == a.active() && b.start() == a.start() && b.end() == a.end() && b.nextDueDay() == a.nextDueDay(),
                "読み直した様子が違う");
        List<Run> ra = simulate(a, env, tab, mid + STEP, 300);
        List<Run> rb = simulate(b, env, tab, mid + STEP, 300);
        helper.assertTrue(ra.equals(rb), "読み直した後の並びが違う: " + ra.size() + " / " + rb.size());
        helper.assertTrue(a.save(new CompoundTag()).equals(b.save(new CompoundTag())), "300 日後の保存が違う");
        WorldEventSchedule empty = WorldEventSchedule.load(new CompoundTag());
        helper.assertTrue(empty.state() == WorldEventSchedule.State.IDLE && empty.nextDueDay() == WorldEventSchedule.NONE, "空の保存から読めない");
        helper.succeed();
    }

    // ---------------------------------------------------------------- 地脈の大潮(本物の日程と汲み上げ器)

    /** 地脈の大潮の間だけ、汲み上げ器が leyTidePumpMultiplier 倍汲む。止めると元に戻る。送る中身も大潮になる。 */
    @GameTest(template = TEMPLATE, batch = TIDE_BATCH, timeoutTicks = 20)
    public static void leyTideSpeedsPump(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        WorldEventTable tab = DesignData.worldEvents();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlockAndUpdate(pos, ModLey.PUMP.get().defaultBlockState());
        LeyPumpBlockEntity pump = (LeyPumpBlockEntity) level.getBlockEntity(pos);
        helper.assertTrue(pump != null, "汲み上げ器が置けない");
        double eff = LeyLines.efficiency(level.getSeed(), pos.getX(), pos.getZ());
        WorldEvents.startNow(server, WorldEventKind.HALO, 0.0D); // 大潮でない物を起こしておく
        try {
            helper.assertTrue(WorldEvents.pumpMultiplier(level) == 1.0D, "日暈で倍率が変わった");
            int ticks = 200;
            int e0 = pump.energy();
            for (int i = 0; i < ticks; i++) pump.generate();
            int base = pump.energy() - e0;
            WorldEvents.startNow(server, WorldEventKind.LEY_TIDE, 0.0D);
            helper.assertTrue(WorldEvents.pumpMultiplier(level) == tab.leyTidePumpMultiplier, "大潮の倍率 " + WorldEvents.pumpMultiplier(level));
            WorldEventPayload p = WorldEvents.payload(server);
            helper.assertTrue(p.eventKind() == WorldEventKind.LEY_TIDE && p.end() - p.start() == tab.spec(WorldEventKind.LEY_TIDE).length(),
                    "送る中身が大潮でない: " + p);
            int e1 = pump.energy();
            for (int i = 0; i < ticks; i++) pump.generate();
            int tide = pump.energy() - e1;
            double expect = base * tab.leyTidePumpMultiplier;
            helper.assertTrue(base > 0, "汲んでいない(効率 " + eff + ")");
            helper.assertTrue(Math.abs(tide - expect) <= 2.0D, "大潮で " + tide + " FE(ふだん " + base + "・期待 " + expect + ")");
            // 後から入った人・入り直した人へ送る中身(ログインで payload をそのまま送る)は、長さを指定した合図も始まり・終わりのまま
            WorldEvents.startNow(server, WorldEventKind.AURORA, 0.0D, tab.omenLength(WorldEventKind.AURORA));
            WorldEventPayload late = WorldEvents.payload(server);
            helper.assertTrue(late.eventKind() == WorldEventKind.AURORA && late.end() - late.start() == tab.omenLength(WorldEventKind.AURORA),
                    "後から入った人に送る中身が違う: " + late);
            helper.assertTrue(level.getServer().getLevel(net.minecraft.world.level.Level.NETHER) == null
                    || WorldEvents.pumpMultiplier(level.getServer().getLevel(net.minecraft.world.level.Level.NETHER)) == 1.0D, "ネザーでも速い");
        } finally {
            com.yayuy.overgrownvegetation.worldevent.WorldEventData.get(server).schedule()
                    .stop(level.getDayTime(), WorldEvents.seed(server), tab);
            level.removeBlock(pos, false);
        }
        helper.assertTrue(WorldEvents.pumpMultiplier(level) == 1.0D, "止めても倍率が残る");
        helper.assertTrue(WorldEvents.payload(server) == WorldEventPayload.NONE, "止めても送る中身が残る");
        helper.succeed();
    }
}
