package com.yayuy.overgrownvegetation.worldevent;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * ワールドイベントの日程(2026-11-16)。ゲームに依らない素の状態機械で、{@link WorldEventData} が保存し {@link WorldEvents} が毎 tick 回す
 * (GameTest はワールドを使わずに回す)。時計はオーバーワールドの {@code dayTime}(時刻に結び付いたイベントなので)。
 *
 * <ol>
 *   <li><b>休み</b>(IDLE): {@link #nextDueDay} の日になったら、そのときのフェーズで起きてよい物からくじを引く(最初の 1 回もくじ。ユーザー)。</li>
 *   <li><b>待ち</b>(WAITING): 時刻の決まった物は、窓からくじで選んだ始まりの時刻を待つ。雨で見えない物({@code skipInRain})は
 *       始まりに雨なら次の窓へ。地平線を歩く影は、朝か夕の窓で霞・霧が {@code shadowMinFog} 以上の時を待ち、霧の残りで長さを切る。
 *       待ちが {@code fixedWaitDays}(影は {@code fogWaitDays})を過ぎたらその種を外して引き直す。</li>
 *   <li><b>起きている</b>(ACTIVE): 始まりと終わりの時刻(絶対の dayTime)を持つ。同時に 1 つだけ。終わったら、始まりの日 + 7〜15 日【仮】を次のくじの日にする。</li>
 * </ol>
 *
 * <p><b>寝て夜を飛ばしたとき</b>(11-16 ユーザー決定「そのまま終わる」= 見逃し): 時刻が {@link #JUMP} tick より大きく進んで終わりを越えたら普通に終わる。
 * 始まる前に飛ばされた物も見逃しで、次のくじへ進む。持ち越しの仕組みは残してあり、{@code maxCarry} を 1 以上にすると、見えた割合が {@code carrySeen} 未満の物を次の同じ時間帯へ回す(既定 0 = 使わない)。
 * <b>時刻を戻したとき</b>(/time set): 起きている物が始まる前まで戻ったら次の窓へ組み直し、休みの次のくじの日が遠すぎれば今日から数え直す。
 */
public final class WorldEventSchedule {

    public static final long NONE = Long.MIN_VALUE;
    public static final int DAY = 24000;
    /** これより大きく時刻が進んだら「飛んだ」(眠り・/time)とみなす(tick)。 */
    public static final long JUMP = 200L;

    public enum State { IDLE, WAITING, ACTIVE }

    /** ゲームから受け取る今の様子。 */
    public interface Env {
        /** 今のフェーズ(0〜10)。 */
        int phase();

        /** オーバーワールドで雨・雷雨が降っているか。 */
        boolean raining();

        /** 時刻 t の濃霧の度合い 0〜1(天気の予定の純関数。先読みに使う)。 */
        double fog(long t);
    }

    private State state = State.IDLE;
    private long nextDueDay = NONE;
    @Nullable
    private WorldEventKind kind;
    private long start = NONE, end = NONE;
    private long giveUpDay = NONE;
    private int carry;
    private long draws;
    private long lastT = NONE;
    /** 間隔を数え始めた日(前のイベントの始まりの日。影の霧待ちを「この日 + intervalMax + 1」までに抑える)。 */
    private long anchorDay = NONE;
    private boolean forced;
    /** 待ちをあきらめた種(次のくじ 1 回だけ外す)。 */
    @Nullable
    private WorldEventKind skip;

    // ---- フェーズの合図(くじとは別枠。11-16 全体管理・event-look の頼み) ----
    /** 前の tick のフェーズ(-1 = まだ見ていない。初めて見たフェーズでは合図を出さない)。 */
    private int lastPhase = -1;
    /** 夜を待っている合図(並んだ順に 1 夜ずつ)。 */
    private final java.util.ArrayDeque<WorldEventKind> omenQueue = new java.util.ArrayDeque<>();
    /** 先頭の合図の予定(NONE = まだ決めていない)・始まったか・持ち越しの回数。 */
    private long omenStart = NONE, omenEnd = NONE;
    private boolean omenOn;
    private int omenCarry;

    // ---------------------------------------------------------------- 読む

    public State state() {
        return state;
    }

    /** 起きている、または待っている種(休みは null)。 */
    @Nullable
    public WorldEventKind kind() {
        return kind;
    }

    /** 起きている種(フェーズの合図を含む。起きていなければ null)。 */
    @Nullable
    public WorldEventKind active() {
        if (omenOn) return omenQueue.peekFirst();
        return state == State.ACTIVE ? kind : null;
    }

    /** 起きている物の始まり・終わり(フェーズの合図を含む。送る値)。起きていなければ NONE。 */
    public long activeStart() {
        return omenOn ? omenStart : state == State.ACTIVE ? start : NONE;
    }

    public long activeEnd() {
        return omenOn ? omenEnd : state == State.ACTIVE ? end : NONE;
    }

    /** 今フェーズの合図が起きているか。 */
    public boolean omenActive() {
        return omenOn;
    }

    /** 夜を待っている・起きているフェーズの合図(先頭が次)と、先頭の予定の始まり(NONE = 未定)。 */
    public java.util.List<WorldEventKind> omens() {
        return java.util.List.copyOf(omenQueue);
    }

    public long omenStart() {
        return omenStart;
    }

    public long start() {
        return start;
    }

    public long end() {
        return end;
    }

    public long nextDueDay() {
        return nextDueDay;
    }

    public long giveUpDay() {
        return giveUpDay;
    }

    public int carry() {
        return carry;
    }

    public boolean forced() {
        return forced;
    }

    /** 起きている物の進み 0〜1(起きていなければ 0)。 */
    public double progress(long t) {
        long s = activeStart(), e = activeEnd();
        if (s == NONE || e <= s) return 0.0D;
        return Math.max(0.0D, Math.min(1.0D, (t - s) / (double) (e - s)));
    }

    public static long day(long t) {
        return Math.floorDiv(t, (long) DAY);
    }

    // ---------------------------------------------------------------- 回す

    /** 1 tick 進める。送り直しが要る変化(種・始まり・終わり・状態)があれば true。 */
    public boolean step(long t, long seed, WorldEventTable tab, Env env) {
        WorldEventKind a0 = active();
        long st0 = activeStart(), en0 = activeEnd();
        int phase = env.phase();
        if (lastPhase >= 0 && phase > lastPhase) {
            for (WorldEventKind k : WorldEventKind.values()) {
                int at = tab.omenPhase(k);
                if (at > lastPhase && at <= phase) omenQueue.addLast(k);
            }
        }
        lastPhase = phase;
        if (nextDueDay == NONE) {
            anchorDay = day(t);
            nextDueDay = day(t) + gapDays(seed, tab);
        }
        if (lastT != NONE && t < lastT) {
            backward(t, seed, tab);
        }
        if (state == State.ACTIVE) {
            active(t, seed, tab);
        }
        omen(t, tab);
        if (state == State.IDLE && day(t) >= nextDueDay) {
            draw(t, seed, tab, env);
        }
        if (state == State.WAITING) {
            waiting(t, seed, tab, env);
        }
        lastT = t;
        return active() != a0 || activeStart() != st0 || activeEnd() != en0;
    }

    /**
     * フェーズの合図(F3 の短いオーロラ・F7 の胞子の柱)。くじとは別枠で、上がった後の次の日没({@code omenTod})に 1 夜ずつ起こす。
     * 同時に 1 つの決まりは守る: くじのイベントが起きている夜は次の日没へ送り、合図の間はくじのイベントを次の窓へ送る。
     * 寝て飛ばされたら、くじと同じ持ち越しの決まり。サーバーが覚えているので、入り直した人・後から入った人にも送られる。
     */
    private void omen(long t, WorldEventTable tab) {
        WorldEventKind k = omenQueue.peekFirst();
        if (k == null) return;
        if (omenStart == NONE || (!omenOn && t < lastT && omenStart - t > DAY)) {
            planOmen(t, tab, k);
        }
        if (omenOn) {
            if (t < omenStart) { // 時刻を戻した
                omenOn = false;
                planOmen(t, tab, k);
            } else if (t >= omenEnd) {
                boolean jumped = lastT != NONE && t - lastT > JUMP;
                double seen = Math.max(0.0D, (lastT - omenStart) / (double) Math.max(1L, omenEnd - omenStart));
                omenOn = false;
                if (jumped && seen < tab.carrySeen && omenCarry < tab.maxCarry) {
                    omenCarry++;
                    planOmen(t, tab, k);
                } else {
                    omenQueue.pollFirst();
                    omenStart = omenEnd = NONE;
                    omenCarry = 0;
                }
            }
            return;
        }
        if (t < omenStart) return;
        if (t >= omenEnd && tab.maxCarry <= 0) { // 寝て飛ばした: 見逃し(11-16 ユーザー)
            omenQueue.pollFirst();
            omenStart = omenEnd = NONE;
            omenCarry = 0;
            return;
        }
        if (t >= omenEnd || state == State.ACTIVE) {
            planOmen(t + 1, tab, k); // 飛ばされた・くじのイベントの夜: 次の日没へ
            return;
        }
        omenOn = true;
    }

    private void planOmen(long t, WorldEventTable tab, WorldEventKind k) {
        long s = day(t) * DAY + tab.omenTod;
        if (s < t) s += DAY;
        omenStart = s;
        omenEnd = s + tab.omenLength(k);
    }

    private void draw(long t, long seed, WorldEventTable tab, Env env) {
        List<WorldEventKind> pool = new ArrayList<>();
        double total = 0.0D;
        for (WorldEventKind k : WorldEventKind.values()) {
            WorldEventTable.Spec sp = tab.spec(k);
            if (sp.weight() > 0 && env.phase() >= sp.minPhase() && k != skip) {
                pool.add(k);
                total += sp.weight();
            }
        }
        skip = null;
        if (pool.isEmpty()) {
            nextDueDay = day(t) + 1;
            return;
        }
        WorldEventKind pick = null;
        while (pick == null && !pool.isEmpty()) {
            double r = unit(seed) * total;
            WorldEventKind p = pool.get(pool.size() - 1);
            for (WorldEventKind k : pool) {
                r -= tab.spec(k).weight();
                if (r < 0) {
                    p = k;
                    break;
                }
            }
            if (tab.spec(p).needFog() && !fogAhead(t, tab, env, fogDays(t, tab))) {
                // 待ちの日数の中に霞・霧の窓が見えない(朝の霧は予定で分かる): 影を外して引き直す。日程を 10 日止めないため
                pool.remove(p);
                total -= tab.spec(p).weight();
                continue;
            }
            pick = p;
        }
        if (pick == null) {
            nextDueDay = day(t) + 1;
            return;
        }
        kind = pick;
        carry = 0;
        forced = false;
        state = State.WAITING;
        resetGiveUp(t, tab);
        plan(t, seed, tab);
    }

    private void resetGiveUp(long t, WorldEventTable tab) {
        WorldEventTable.Spec sp = tab.spec(kind);
        giveUpDay = day(t) + (long) (sp.needFog() ? fogDays(t, tab) : Math.ceil(tab.fixedWaitDays));
    }

    /** 待ちの始まりを決める(時刻の決まった物だけ。霧待ちは NONE)。t より後の、窓からくじで選んだ時刻。 */
    private void plan(long t, long seed, WorldEventTable tab) {
        WorldEventTable.Spec sp = tab.spec(kind);
        if (sp.needFog()) {
            start = end = NONE;
            return;
        }
        int[] w = sp.windows()[(int) Math.min(sp.windows().length - 1, Math.floor(unit(seed) * sp.windows().length))];
        int span = Math.floorMod(w[1] - w[0], DAY);
        int tod = Math.floorMod(w[0] + (int) Math.floor(unit(seed) * (span + 1)), DAY);
        long s = day(t) * DAY + tod;
        if (s < t) s += DAY;
        start = s;
        end = s + sp.length();
    }

    private void waiting(long t, long seed, WorldEventTable tab, Env env) {
        if (day(t) > giveUpDay) {
            skip = kind;
            toIdle();
            nextDueDay = day(t);
            return;
        }
        WorldEventTable.Spec sp = tab.spec(kind);
        if (sp.needFog()) {
            if (inWindow(sp, Math.floorMod(t, (long) DAY)) && env.fog(t) >= tab.shadowMinFog) {
                long len = fogLeft(t, sp.length(), tab.shadowMinFog * 0.5D, env);
                if (len >= tab.shadowMinTicks) {
                    state = State.ACTIVE;
                    start = t;
                    end = t + len;
                }
            }
            return;
        }
        if (start == NONE) {
            plan(t, seed, tab);
        }
        if (t < start) return;
        if (t >= end) {
            if (tab.maxCarry > 0) {
                plan(t, seed, tab); // 始まる前に飛ばされた(眠り・/time): 持ち越すなら次の窓へ
            } else {
                finish(t, seed, tab); // 見逃し(11-16 ユーザー「寝て夜を飛ばしたらそのまま終わる」)
            }
            return;
        }
        if (sp.skipInRain() && env.raining()) {
            plan(t + 1, seed, tab); // 雨で見えない: 次の窓へ(あきらめの日までに晴れなければ引き直し)
            return;
        }
        if (omenOn) {
            plan(t + 1, seed, tab); // フェーズの合図の夜: 次の窓へ(同時に 1 つ)
            return;
        }
        state = State.ACTIVE; // 飛んで途中に入ったときは、途中から(見える分だけ)
    }

    private void active(long t, long seed, WorldEventTable tab) {
        if (t < end) return;
        boolean jumped = lastT != NONE && t - lastT > JUMP;
        double seen = lastT == NONE || end <= start ? 1.0D : Math.max(0.0D, (lastT - start) / (double) (end - start));
        if (!forced && jumped && seen < tab.carrySeen && carry < tab.maxCarry) {
            carry++;
            state = State.WAITING;
            resetGiveUp(t, tab);
            plan(t, seed, tab);
            return;
        }
        finish(t, seed, tab);
    }

    private void finish(long t, long seed, WorldEventTable tab) {
        if (forced) {
            nextDueDay = Math.max(nextDueDay, day(t) + 1);
        } else {
            anchorDay = day(start);
            nextDueDay = Math.max(day(start) + gapDays(seed, tab), day(t) + 1);
        }
        toIdle();
    }

    private void toIdle() {
        state = State.IDLE;
        kind = null;
        start = end = NONE;
        giveUpDay = NONE;
        carry = 0;
        forced = false;
    }

    private void backward(long t, long seed, WorldEventTable tab) {
        switch (state) {
            case ACTIVE -> {
                if (t < start) {
                    if (forced) {
                        toIdle();
                    } else {
                        state = State.WAITING;
                        resetGiveUp(t, tab);
                        plan(t, seed, tab);
                    }
                }
            }
            case WAITING -> {
                resetGiveUp(t, tab);
                plan(t, seed, tab);
            }
            case IDLE -> {
                if (nextDueDay > day(t) + (long) Math.ceil(tab.intervalMaxDays)) {
                    nextDueDay = day(t) + gapDays(seed, tab);
                    anchorDay = day(t);
                }
            }
        }
        if (state == State.IDLE && nextDueDay > day(t) + (long) Math.ceil(tab.intervalMaxDays)) {
            nextDueDay = day(t) + gapDays(seed, tab);
            anchorDay = day(t);
        }
    }

    static boolean inWindow(WorldEventTable.Spec sp, long tod) {
        for (int[] w : sp.windows()) {
            if (w[0] <= w[1] ? tod >= w[0] && tod <= w[1] : tod >= w[0] || tod <= w[1]) return true;
        }
        return false;
    }

    /** 影が霧を待ってよい日数: fogWaitDays と「間隔を数え始めた日 + intervalMax + 1」までの短い方(少なくとも 1 日)。間隔 7〜15 日を守るため。 */
    long fogDays(long t, WorldEventTable tab) {
        long d = (long) Math.ceil(tab.fogWaitDays);
        if (anchorDay != NONE) d = Math.min(d, anchorDay + (long) Math.ceil(tab.intervalMaxDays) + 1 - day(t));
        return Math.max(1L, d);
    }

    /** t から days 日の間に、影の窓の中で霧が shadowMinFog 以上・残り shadowMinTicks 以上になる時があるか(100 tick 刻みの先読み)。 */
    static boolean fogAhead(long t, WorldEventTable tab, Env env, long days) {
        WorldEventTable.Spec sp = tab.spec(WorldEventKind.HORIZON_SHADOW);
        long until = t + days * DAY;
        for (long u = t; u < until; u += 100) {
            if (inWindow(sp, Math.floorMod(u, (long) DAY)) && env.fog(u) >= tab.shadowMinFog
                    && fogLeft(u, sp.length(), tab.shadowMinFog * 0.5D, env) >= tab.shadowMinTicks) {
                return true;
            }
        }
        return false;
    }

    /** 霧が below を下回るまでの長さ(100 tick 刻み、max まで)。 */
    static long fogLeft(long t, int max, double below, Env env) {
        for (int dt = 100; dt < max; dt += 100) {
            if (env.fog(t + dt) < below) return dt;
        }
        return max;
    }

    // ---------------------------------------------------------------- コマンドから

    /** 今すぐ起こす(フェーズ・時刻・霧・雨を見ない。確かめ用)。progress = 途中から(0〜1)。日程は乱さない(終わったら元の次のくじの日へ)。 */
    public void forceStart(WorldEventKind k, long t, double progress, WorldEventTable tab) {
        forceStart(k, t, progress, tab.spec(k).length());
    }

    /** 長さ(tick)を指定して今すぐ起こす。起きていたフェーズの合図は次の日没へ送る(同時に 1 つ)。 */
    public void forceStart(WorldEventKind k, long t, double progress, int length) {
        int len = Math.max(1, length);
        if (omenOn) {
            omenOn = false;
            omenStart = omenEnd = NONE;
        }
        state = State.ACTIVE;
        kind = k;
        forced = true;
        carry = 0;
        giveUpDay = NONE;
        start = t - Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * len);
        end = start + len;
        lastT = t;
    }

    /** 止める。確かめ用に起こした物なら日程はそのまま、そうでなければ今日から 7〜15 日後に次のくじ。 */
    public void stop(long t, long seed, WorldEventTable tab) {
        if (omenOn) { // フェーズの合図を止める(並んでいる次の合図は残す)
            omenOn = false;
            omenQueue.pollFirst();
            omenStart = omenEnd = NONE;
            omenCarry = 0;
            return;
        }
        if (state == State.IDLE) return;
        boolean wasForced = forced;
        toIdle();
        nextDueDay = wasForced ? Math.max(nextDueDay, day(t) + 1) : day(t) + gapDays(seed, tab);
        if (!wasForced) anchorDay = day(t);
    }

    /** 次のくじを今すぐ引く(起きている・待っている物は捨てる)。引いた種は窓・霧を待ってから起きる。 */
    @Nullable
    public WorldEventKind drawNow(long t, long seed, WorldEventTable tab, Env env) {
        toIdle();
        nextDueDay = day(t);
        step(t, seed, tab, env);
        return kind;
    }

    // ---------------------------------------------------------------- くじ

    /** 次のくじまでの日数(intervalMin〜intervalMax の整数)。 */
    private long gapDays(long seed, WorldEventTable tab) {
        double lo = Math.ceil(tab.intervalMinDays), hi = Math.floor(tab.intervalMaxDays);
        if (hi < lo) hi = lo;
        return (long) Math.min(hi, lo + Math.floor(unit(seed) * (hi - lo + 1)));
    }

    /** 0〜1 のくじ(シードと引いた回数で決まる。保存するので再起動しても同じ並び)。 */
    private double unit(long seed) {
        long z = seed ^ (++draws * 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    // ---------------------------------------------------------------- 保存

    public CompoundTag save(CompoundTag tag) {
        tag.putString("state", state.name());
        tag.putLong("nextDueDay", nextDueDay);
        tag.putString("kind", kind != null ? kind.id : "");
        tag.putLong("start", start);
        tag.putLong("end", end);
        tag.putLong("giveUpDay", giveUpDay);
        tag.putInt("carry", carry);
        tag.putLong("draws", draws);
        tag.putLong("lastT", lastT);
        tag.putLong("anchorDay", anchorDay);
        tag.putBoolean("forced", forced);
        tag.putString("skip", skip != null ? skip.id : "");
        tag.putInt("lastPhase", lastPhase);
        tag.putString("omens", String.join(",", omenQueue.stream().map(x -> x.id).toList()));
        tag.putLong("omenStart", omenStart);
        tag.putLong("omenEnd", omenEnd);
        tag.putBoolean("omenOn", omenOn);
        tag.putInt("omenCarry", omenCarry);
        return tag;
    }

    public static WorldEventSchedule load(CompoundTag tag) {
        WorldEventSchedule s = new WorldEventSchedule();
        if (!tag.contains("nextDueDay")) return s;
        try {
            s.state = State.valueOf(tag.getString("state"));
        } catch (IllegalArgumentException e) {
            s.state = State.IDLE;
        }
        s.nextDueDay = tag.getLong("nextDueDay");
        s.kind = WorldEventKind.byId(tag.getString("kind"));
        s.start = tag.getLong("start");
        s.end = tag.getLong("end");
        s.giveUpDay = tag.getLong("giveUpDay");
        s.carry = tag.getInt("carry");
        s.draws = tag.getLong("draws");
        s.lastT = tag.getLong("lastT");
        s.anchorDay = tag.contains("anchorDay") ? tag.getLong("anchorDay") : NONE;
        s.forced = tag.getBoolean("forced");
        s.skip = WorldEventKind.byId(tag.getString("skip"));
        s.lastPhase = tag.contains("lastPhase") ? tag.getInt("lastPhase") : -1;
        for (String id : tag.getString("omens").split(",")) {
            WorldEventKind o = WorldEventKind.byId(id);
            if (o != null) s.omenQueue.addLast(o);
        }
        if (!s.omenQueue.isEmpty() && tag.contains("omenStart")) {
            s.omenStart = tag.getLong("omenStart");
            s.omenEnd = tag.getLong("omenEnd");
            s.omenOn = tag.getBoolean("omenOn");
            s.omenCarry = tag.getInt("omenCarry");
        }
        if (s.state != State.IDLE && s.kind == null) {
            s.toIdle(); // 種が消えた古いセーブ
        }
        return s;
    }
}
