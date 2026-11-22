package com.yayuy.overgrownvegetation.perf;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.server.ServerStoppedEvent;
import com.yayuy.overgrownvegetation.port.event.tick.ServerTickEvent;

/**
 * <b>予算の番人</b>と周期処理の<b>計測</b>(2026-10-06、ユーザー指定の ①②)。
 *
 * <h2>計測</h2>
 * <p>{@link PerfJob} ごとに、直近 {@value #WINDOW} tick の「1 tick にかかった時間・こなした仕事の数」を輪の配列に貯める。
 * {@code /ov perf} が表にする。{@code System.nanoTime} を 1 回の処理につき 2〜3 回読むだけなので、常に付けっぱなしでよい。
 *
 * <h2>予算の番人</h2>
 * <p>後回しにできる処理({@link PerfJob#deferrable})が 1 tick に使ってよい時間を、<b>サーバーの空き時間</b>から決める:
 * <pre>
 *   空き = 目標(既定 40 ms) − (直近 20 tick の平均の 1 tick − そのうち後回しにできる処理の平均)
 * </pre>
 * mod の後回しにできる処理<b>を除いた</b>重さで空きを測るので、自分の仕事で自分を絞る揺れは起きない。
 * サーバーが軽いとき(平均 10 ms など)は空きが 30 ms もあり、今までどおり各処理の 1 tick の上限
 * (台帳の書き換え 512 マスなど)で止まる —— <b>軽いときの動きは変わらない</b>。重くなったときだけ、空きを使い切った処理から
 * 次の tick へ回す。結果は同じで、遅れるだけ。
 *
 * <p>止めっぱなしにはしない: {@value #STARVE_TICKS} tick 回っていない処理は、空きが無くても 1 単位だけ進める
 * (ずっと重いサーバーでも書き換えはいつか終わる)。
 *
 * <p>GameTest のサーバーでは働かない(数百のテストを並べて回すので常に重い。番人が絞るとテストが時間切れになる)。
 *
 * <h2>使い方</h2>
 * <pre>
 *   if (!TickBudget.mayStart(PerfJob.X)) return;      // 今は休む
 *   long start = TickBudget.begin();
 *   int done = 0;
 *   try {
 *       while (仕事がある &amp;&amp; 今までの上限 &amp;&amp; (done == 0 || TickBudget.timeLeft())) { 1 単位; done++; }
 *   } finally {
 *       TickBudget.end(PerfJob.X, start, done);
 *   }
 * </pre>
 * 入れ子にしないこと({@link PerfJob} の説明)。サーバースレッドからだけ呼ぶ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class TickBudget {

    /** 計測の窓(tick)。サーバーの {@code tickTimesNanos} と同じ 100 にして、同じ添え字で並べる。 */
    public static final int WINDOW = 100;
    /** 空きを測る平均の長さ(tick)。1 秒。仮決め 2026-10-06。 */
    static final int AVERAGE_TICKS = 20;
    /** これだけ回っていない処理は空きが無くても 1 単位進める(2 秒)。仮決め 2026-10-06。 */
    static final int STARVE_TICKS = 40;
    /** 空きが無いときでも 1 tick に残す最小の時間(0.5 ms)。仮決め 2026-10-06。 */
    static final long MIN_CAP_NANOS = 500_000L;

    private static final int JOBS = PerfJob.values().length;
    private static final long[][] JOB_NANOS = new long[JOBS][WINDOW];
    private static final int[][] JOB_UNITS = new int[JOBS][WINDOW];
    /** 番人に止められた回数(窓の中)。 */
    private static final int[][] JOB_HELD = new int[JOBS][WINDOW];
    private static final int[] LAST_RUN = new int[JOBS];
    /**
     * 後回しにできる処理の合計(tick ごと)のうち、<b>サーバーの 1 tick の計測の内側</b>で走ったもの。
     * {@code ServerTickEvent.Post} はサーバーが 1 tick の時間を測り終えた後に来る({@code MinecraftServer#tickServer})ので、
     * そこで走る処理(基地の工事・エンドの侵食)の時間は {@code tickTimesNanos} に入っていない —— 空きの計算で引かない。
     */
    private static final long[] DEFERRABLE_NANOS = new long[WINDOW];

    private static boolean inPost;
    private static int tick;
    private static int slot;
    private static boolean active;
    private static long capNanos = Long.MAX_VALUE;
    private static long headroomNanos = Long.MAX_VALUE;
    private static long baseNanos;
    private static long spentNanos;
    private static long jobDeadline = Long.MAX_VALUE;

    private TickBudget() {
    }

    /** tick の頭で、前の tick までの重さから今の tick の予算を決める。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        tick = server.getTickCount();
        slot = Math.floorMod(tick, WINDOW);
        for (int j = 0; j < JOBS; j++) {
            JOB_NANOS[j][slot] = 0L;
            JOB_UNITS[j][slot] = 0;
            JOB_HELD[j][slot] = 0;
        }
        DEFERRABLE_NANOS[slot] = 0L;
        spentNanos = 0L;
        inPost = false;
        jobDeadline = Long.MAX_VALUE;
        com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor.setInPeriodicJob(false); // 閉じ忘れの保険

        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        active = cfg.tickBudgetEnabled.get() && !(server instanceof GameTestServer);
        long[] times = server.getTickTimesNanos();
        int count = Math.min(AVERAGE_TICKS, tick - 1);
        if (count <= 0) {
            capNanos = Long.MAX_VALUE;
            headroomNanos = Long.MAX_VALUE;
            return;
        }
        long sumTick = 0L;
        long sumDeferrable = 0L;
        for (int i = 1; i <= count; i++) {
            int s = Math.floorMod(tick - i, WINDOW);
            sumTick += times[s];
            sumDeferrable += DEFERRABLE_NANOS[s];
        }
        baseNanos = Math.max(0L, (sumTick - sumDeferrable) / count);
        long target = (long) (cfg.tickBudgetTargetMspt.get() * 1_000_000.0D);
        headroomNanos = target - baseNanos;
        capNanos = active ? Math.max(MIN_CAP_NANOS, headroomNanos) : Long.MAX_VALUE;
    }

    /** ここから先(tick の時間を測り終えた後)の処理は、空きの計算で引かない({@link #DEFERRABLE_NANOS})。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerTickPost(ServerTickEvent.Post event) {
        inPost = true;
    }

    /** この tick にここまで番人の内側で走った後回しにできる処理の合計(ns。{@code HordeMeter} の止まりの内訳用)。 */
    public static long thisTickDeferrableNanos() {
        return DEFERRABLE_NANOS[slot];
    }

    /** この tick にいちばん長く走った周期処理の名前と ms(無ければ "-")。 */
    public static String thisTickTopJob() {
        int best = -1;
        long bestNanos = 0;
        for (int j = 0; j < JOBS; j++) {
            if (JOB_NANOS[j][slot] > bestNanos) {
                bestNanos = JOB_NANOS[j][slot];
                best = j;
            }
        }
        return best < 0 ? "-" : PerfJob.values()[best].name() + String.format(java.util.Locale.ROOT, ":%.1f", bestNanos / 1e6);
    }

    /** ワールドを閉じたら記録を捨てる(次のワールドの表に前のワールドの数字が混ざらないように)。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        for (int j = 0; j < JOBS; j++) {
            java.util.Arrays.fill(JOB_NANOS[j], 0L);
            java.util.Arrays.fill(JOB_UNITS[j], 0);
            java.util.Arrays.fill(JOB_HELD[j], 0);
            LAST_RUN[j] = 0;
        }
        java.util.Arrays.fill(DEFERRABLE_NANOS, 0L);
        capNanos = Long.MAX_VALUE;
        headroomNanos = Long.MAX_VALUE;
        spentNanos = 0L;
        jobDeadline = Long.MAX_VALUE;
    }

    /**
     * その処理をこの tick に始めてよいか。後回しにできない処理は常に true。
     * 後回しにできる処理は、この tick の予算が残っている・または {@value #STARVE_TICKS} tick 回っていないとき true。
     * false を返したら、その処理はこの tick は何もしない(待ち行列はそのまま)。
     */
    public static boolean mayStart(PerfJob job) {
        if (!job.deferrable || spentNanos < capNanos || tick - LAST_RUN[job.ordinal()] >= STARVE_TICKS) {
            return true;
        }
        JOB_HELD[job.ordinal()][slot]++;
        return false;
    }

    /** 処理を始める。戻り値を {@link #end} に渡す。{@link #timeLeft} の締め切りもここで決まる。 */
    public static long begin() {
        long now = System.nanoTime();
        jobDeadline = capNanos == Long.MAX_VALUE ? Long.MAX_VALUE : now + Math.max(0L, capNanos - spentNanos);
        com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor.setInPeriodicJob(true); // 汚染の周期処理は物を落とさない(11-17)
        return now;
    }

    /**
     * いま測っている処理が、次の 1 単位へ進んでよいか(この tick の予算の残り)。番人が働いていなければ常に true。
     * 1 単位目は呼ばずに必ず進めること({@code done == 0 ||})—— 止めっぱなしを防ぐ。
     */
    public static boolean timeLeft() {
        return jobDeadline == Long.MAX_VALUE || System.nanoTime() < jobDeadline;
    }

    /**
     * 「1 tick に N 個まで」の予算を 1 つ減らす。64 個ごとに {@link #timeLeft} を見て、この tick の時間を使い切っていたら 0 を返す
     * (呼ぶ側の {@code budget > 0} のループを抜けさせる)。{@code budget--} の代わりに {@code budget = TickBudget.spend(budget)}。
     */
    public static int spend(int budget) {
        int next = budget - 1;
        return (next & 63) == 0 && !timeLeft() ? 0 : next;
    }

    /** 処理を終える。{@code units} はこの tick にこなした仕事の数(表に出すだけ)。 */
    public static void end(PerfJob job, long start, int units) {
        long spent = System.nanoTime() - start;
        int j = job.ordinal();
        JOB_NANOS[j][slot] += spent;
        JOB_UNITS[j][slot] += units;
        if (job.deferrable) {
            spentNanos += spent;
            if (!inPost) {
                DEFERRABLE_NANOS[slot] += spent;
            }
        }
        if (units > 0) {
            LAST_RUN[j] = tick;
        }
        jobDeadline = Long.MAX_VALUE;
        com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor.setInPeriodicJob(false);
    }

    // ---------------------------------------------------------------- 表示用

    /** 番人が今働いているか(設定でオン、かつ GameTest のサーバーでない)。 */
    public static boolean active() {
        return active;
    }

    /** この tick の、後回しにできる処理の予算(ns)。番人が働いていなければ {@link Long#MAX_VALUE}。 */
    public static long capNanos() {
        return capNanos;
    }

    /** 空き = 目標 − mod の後回しにできる処理を除いた 1 tick の平均(ns)。負なら目標を超えている。 */
    public static long headroomNanos() {
        return headroomNanos;
    }

    /** mod の後回しにできる処理を除いた、直近 1 秒の 1 tick の平均(ns)。 */
    public static long baseNanos() {
        return baseNanos;
    }

    /** 直近 {@value #WINDOW} tick の、その処理の 1 tick の平均(ns)。 */
    public static double averageNanos(PerfJob job) {
        long sum = 0L;
        for (long n : JOB_NANOS[job.ordinal()]) {
            sum += n;
        }
        return sum / (double) WINDOW;
    }

    /** 直近 {@value #WINDOW} tick で、その処理がいちばん長くかかった tick の時間(ns)。 */
    public static long maxNanos(PerfJob job) {
        long max = 0L;
        for (long n : JOB_NANOS[job.ordinal()]) {
            max = Math.max(max, n);
        }
        return max;
    }

    /** 直近 {@value #WINDOW} tick にこなした仕事の数。 */
    public static int units(PerfJob job) {
        int sum = 0;
        for (int n : JOB_UNITS[job.ordinal()]) {
            sum += n;
        }
        return sum;
    }

    /** 直近 {@value #WINDOW} tick で、番人に止められた回数。 */
    public static int held(PerfJob job) {
        int sum = 0;
        for (int n : JOB_HELD[job.ordinal()]) {
            sum += n;
        }
        return sum;
    }

    /** 直近 {@value #WINDOW} tick の、後回しにできる処理の合計の 1 tick の平均(ns)。 */
    public static double averageDeferrableNanos() {
        double sum = 0.0D;
        for (PerfJob job : PerfJob.values()) {
            if (job.deferrable) {
                sum += averageNanos(job);
            }
        }
        return sum;
    }
}
