package com.yayuy.overgrownvegetation.perf;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * <b>大群の重さの内訳を 1 秒ごとに書き出す</b>({@code /ov perf horde start|stop|tag}、2026-11-18 大群のための軽量化 B)。
 *
 * <p>11-17 の測りは、ほかのセッションが CPU を使っていて 1 tick の ms(壁の時計)が比べにならなかった。そこで、混んでいても
 * 比べられる物差しを一緒に出す:
 * <ul>
 *     <li>サーバースレッドの <b>CPU 時間</b> ÷ tick(ほかのプロセスに取られた時間は入らない。Windows は 1 回ごとが粗いので 1 秒まとめ)</li>
 *     <li><b>回数</b>: 経路探索・押し合い(と見つけた相手の数)・送り(パケットの数)。混み具合に左右されない</li>
 *     <li>この MOD の Mob の tick の合計時間(壁の時計)・近い / 遠い Mob の数・間引いた数</li>
 * </ul>
 * 同じ起動の中で設定を入り切りし、{@code tag} で窓に名前を付けて比べる(起動をまたぐと汚染の広がりで分母が変わる)。
 * 書き出す先は {@code <ゲームのフォルダ>/overgrown_vegetation/perf/horde-*.tsv}。止めている間は数えない(Mixin は真っ先に {@link #on} を見る)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class HordeMeter {

    /** 数えているか(Mixin が真っ先に見る。サーバースレッドだけが書く)。 */
    public static boolean on;

    private static BufferedWriter out;
    private static Path file;
    private static String tag = "-";
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    // ---- 1 秒の窓の合計(サーバースレッド)
    private static long pathCalls, pathNanos, pathCallsMod;
    private static long pushCalls, pushFound, pushNanos, pushCallsMod;
    private static long sendCalls, sendNanos, packets;
    private static long mobTicks, mobTickNanos;
    private static long held;
    private static long tickStart;
    private static long secondCpuStart;
    private static final long[] TICK_NANOS = new long[20];
    private static int ticksInSecond;
    private static long mobTickStart;
    private static boolean measuringMob;

    // ---- 1 tick の止まりの内訳(11-18 ④。100 ms 以上の tick だけ書く)
    private static final long SLOW_NANOS = 100_000_000L;
    private static long tickCpuStart, tickGcStart, tickEntityNanos, tickMobNanos, entityStart;
    private static int entityDepth;
    private static Thread serverThread;
    private static int blockDepth, tickBlocks;
    private static long blockStartNanos, tickBlockNanos, tickBlockLongest;
    private static String tickBlockWho = "-";
    private static long saveStartNanos, tickSaveNanos, tickSaveTopNanos;
    private static String tickSaveTop = "-";
    private static BufferedWriter slowOut;

    private HordeMeter() {
    }

    // ---------------------------------------------------------------- Mixin から

    /** 経路探索 1 回(全 Mob)。 */
    public static void path(Mob mob, long nanos) {
        pathCalls++;
        pathNanos += nanos;
        if (isMod(mob)) pathCallsMod++;
    }

    /** 押し合い 1 回(サーバー側。found = 見つけた相手の数)。 */
    public static void push(Entity self, int found, long nanos) {
        if (nanos > 1_000_000_000L) return; // 入れ子・取りこぼしで始まりが古いまま残った 1 回(11-18 の JFR の窓に 1 つ出た)
        pushCalls++;
        pushFound += found;
        pushNanos += nanos;
        if (isMod(self)) pushCallsMod++;
    }

    /** この MOD の Mob の {@code ServerEntity#sendChanges} 1 回。 */
    public static void send(long nanos) {
        sendCalls++;
        sendNanos += nanos;
    }

    /** この MOD の Mob のパケット 1 つ(見ている人の数には掛けない)。 */
    public static void packet() {
        packets++;
    }

    /** 同期の待ち(managedBlock)の始まり。サーバースレッドの外側の待ちだけを数える。 */
    public static void blockStart() {
        if (Thread.currentThread() != serverThread || blockDepth++ > 0) return;
        blockStartNanos = System.nanoTime();
    }

    /** 同期の待ちの終わり。20 ms を超えた待ちは、呼んだ mod の場所を覚える(この tick でいちばん長いもの)。 */
    public static void blockEnd() {
        if (Thread.currentThread() != serverThread || blockDepth == 0 || --blockDepth > 0) return;
        long n = System.nanoTime() - blockStartNanos;
        tickBlockNanos += n;
        tickBlocks++;
        if (n > 20_000_000L && n > tickBlockLongest) {
            tickBlockLongest = n;
            tickBlockWho = "(バニラ)";
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 0; i < st.length; i++) {
                StackTraceElement f = st[i];
                if (f.getClassName().startsWith("com.yayuy.overgrownvegetation.") && !f.getClassName().contains(".perf.") && !f.getClassName().contains(".mixin.")) {
                    // mod の場所 ← その下のバニラの鎖(待ちに近い側から 8 段)
                    StringBuilder sb = new StringBuilder(shortName(f) + ":" + f.getLineNumber() + " >");
                    for (int k = Math.max(0, i - 8); k < i; k++) sb.append(' ').append(shortName(st[k]));
                    tickBlockWho = sb.toString();
                    break;
                }
            }
        }
    }

    /** SavedData 1 つの保存の始まり(サーバースレッド)。 */
    public static void saveStart() {
        saveStartNanos = System.nanoTime();
    }

    /** SavedData 1 つの保存の終わり。この tick の合計と、いちばん重かった物(クラス名とファイル名)を覚える。 */
    public static void saveEnd(Object data, java.io.File file) {
        if (saveStartNanos == 0L) return;
        long n = System.nanoTime() - saveStartNanos;
        saveStartNanos = 0L;
        tickSaveNanos += n;
        if (n > tickSaveTopNanos) {
            tickSaveTopNanos = n;
            tickSaveTop = data.getClass().getSimpleName() + "(" + file.getName() + ")" + String.format(Locale.ROOT, ":%.1f", n / 1e6);
        }
    }

    private static String shortName(StackTraceElement f) {
        return f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName();
    }

    /** 思考を間引いた 1 回。 */
    public static void held() {
        held++;
    }

    public static boolean isMod(Entity e) {
        return OvergrownVegetation.MODID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace());
    }

    // ---------------------------------------------------------------- tick

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTickPre(ServerTickEvent.Pre event) {
        if (!on) return;
        tickStart = System.nanoTime();
        tickCpuStart = THREADS.getCurrentThreadCpuTime();
        tickGcStart = gcMillis();
        tickEntityNanos = 0;
        tickMobNanos = 0;
        entityDepth = 0;
        serverThread = Thread.currentThread();
        blockDepth = 0;
        tickBlockNanos = 0;
        tickBlocks = 0;
        tickBlockLongest = 0;
        tickBlockWho = "-";
        tickSaveNanos = 0;
        tickSaveTopNanos = 0;
        tickSaveTop = "-";
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityPre(EntityTickEvent.Pre event) {
        if (on && !event.getEntity().level().isClientSide && entityDepth++ == 0) entityStart = System.nanoTime();
        if (on && !event.getEntity().level().isClientSide && event.getEntity() instanceof Mob && isMod(event.getEntity())) {
            measuringMob = true;
            mobTickStart = System.nanoTime();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityPost(EntityTickEvent.Post event) {
        if (on && !event.getEntity().level().isClientSide && entityDepth > 0 && --entityDepth == 0) tickEntityNanos += System.nanoTime() - entityStart;
        if (measuringMob && !event.getEntity().level().isClientSide) {
            measuringMob = false;
            mobTicks++;
            long mt = System.nanoTime() - mobTickStart;
            mobTickNanos += mt;
            tickMobNanos += mt;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTickPost(ServerTickEvent.Post event) {
        if (!on) return;
        long wall = System.nanoTime() - tickStart;
        TICK_NANOS[ticksInSecond++] = wall;
        if (wall >= SLOW_NANOS) slow(event.getServer(), wall);
        if (ticksInSecond < 20) return;
        flush(event.getServer());
    }

    private static void flush(MinecraftServer server) {
        long cpuNow = THREADS.getCurrentThreadCpuTime();
        double cpuPerTick = (cpuNow - secondCpuStart) / 1e6 / ticksInSecond;
        secondCpuStart = cpuNow;
        long sum = 0, max = 0;
        StringBuilder ticks = new StringBuilder();
        for (int i = 0; i < ticksInSecond; i++) {
            sum += TICK_NANOS[i];
            max = Math.max(max, TICK_NANOS[i]);
            if (i > 0) ticks.append(',');
            ticks.append(String.format(Locale.ROOT, "%.1f", TICK_NANOS[i] / 1e6));
        }
        int mods = 0, near = 0, mid = 0, far = 0, targeting = 0, others = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof Mob mob)) continue;
                if (!isMod(e)) {
                    others++;
                    continue;
                }
                mods++;
                if (mob.getTarget() != null) targeting++;
                double best = Double.MAX_VALUE;
                for (Player p : level.players()) best = Math.min(best, p.distanceToSqr(e));
                if (best <= 32 * 32) near++;
                else if (best <= 64 * 64) mid++;
                else far++;
            }
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int[] sprouts = com.yayuy.overgrownvegetation.spore.field.SporeSprouts.countsForTests(server.overworld());
        String line = String.format(Locale.ROOT,
                "%s\t%d\t%s\t%.2f\t%.1f\t%.2f\t%d\t%d\t%d\t%d\t%d\t%d\t%.3f\t%d\t%d\t%d\t%.3f\t%d\t%d\t%.3f\t%d\t%.3f\t%d\t%d\t%s\t%d\t%d\t%d\t%d",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)), server.getTickCount(), tag,
                sum / 1e6 / ticksInSecond, max / 1e6, cpuPerTick,
                mods, near, mid, far, targeting, others,
                mobTicks == 0 ? 0.0D : mobTickNanos / 1e6 / ticksInSecond, mobTicks,
                pathCalls, pathCallsMod, pathNanos / 1e6 / ticksInSecond,
                pushCalls, pushFound, pushNanos / 1e6 / ticksInSecond,
                sendCalls, sendNanos / 1e6 / ticksInSecond, packets, held,
                (cfg.mobThinkThrottleEnabled.get() ? "thr" : "-") + (cfg.partIndexEnabled.get() ? "+pi" : ""), server.overworld().getPartEntities().size(),
                ChunkEdgeGuard.denied, sprouts[0], sprouts[1]);
        ChunkEdgeGuard.denied = 0;
        try {
            out.write(line);
            out.write('\t');
            out.write(ticks.toString());
            out.newLine();
            out.flush();
        } catch (IOException ex) {
            OvergrownVegetation.LOGGER.warn("[OV] horde meter: {}", ex.toString());
        }
        ticksInSecond = 0;
        pathCalls = pathNanos = pathCallsMod = 0;
        pushCalls = pushFound = pushNanos = pushCallsMod = 0;
        sendCalls = sendNanos = packets = 0;
        mobTicks = mobTickNanos = 0;
        held = 0;
    }

    private static long gcMillis() {
        long sum = 0;
        for (java.lang.management.GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = gc.getCollectionTime();
            if (t > 0) sum += t;
        }
        return sum;
    }

    /**
     * 100 ms 以上かかった tick の内訳を {@code horde-slow-*.tsv} へ: 壁の時計・サーバースレッドの CPU 時間・その間の GC(全スレッドの合計。
     * 止めている時間の目安)・エンティティの tick の合計・うちこの MOD の Mob・番人の内側の周期処理・残り(チャンクの tick・送り・保存など)。
     * 壁の時計 − CPU が大きければ、ほかのプロセスとの CPU の取り合いか GC の待ち。
     */
    private static void slow(MinecraftServer server, long wall) {
        if (slowOut == null) return;
        long cpu = THREADS.getCurrentThreadCpuTime() - tickCpuStart;
        long gc = gcMillis() - tickGcStart;
        long deferrable = TickBudget.thisTickDeferrableNanos();
        try {
            slowOut.write(String.format(Locale.ROOT, "%s\t%d\t%s\t%.1f\t%.1f\t%d\t%.1f\t%.1f\t%.1f\t%.1f\t%s\t%d\t%.1f\t%d\t%s\t%.1f\t%s%n",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)), server.getTickCount(), tag,
                    wall / 1e6, cpu / 1e6, gc, tickEntityNanos / 1e6, tickMobNanos / 1e6, deferrable / 1e6,
                    (wall - tickEntityNanos - deferrable) / 1e6, TickBudget.thisTickTopJob(), server.getTickCount() % 6000 == 0 ? 1 : 0, tickBlockNanos / 1e6, tickBlocks, tickBlockWho,
                    tickSaveNanos / 1e6, tickSaveTop));
            slowOut.flush();
        } catch (IOException ex) {
            OvergrownVegetation.LOGGER.warn("[OV] horde meter: {}", ex.toString());
        }
    }

    // ---------------------------------------------------------------- 命令

    /** 書き出しを始める。返すのは書く先(失敗なら null)。 */
    static Path start(MinecraftServer server) {
        stop();
        try {
            Path dir = server.getServerDirectory().resolve(OvergrownVegetation.MODID).resolve("perf");
            Files.createDirectories(dir);
            file = dir.resolve("horde-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)) + ".tsv");
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            out.write("time\ttick\ttag\twall_ms\tmax_ms\tcpu_ms\tmods\tnear32\tmid64\tfar\ttargeting\tothers\tmobtick_ms\tmobticks"
                    + "\tpath\tpath_mod\tpath_ms\tpush\tpush_found\tpush_ms\tsend\tsend_ms\tpackets\theld\tswitches\tparts\tdeferred\tsprout_active\tsprout_waiting\tticks_ms");
            out.newLine();
            slowOut = Files.newBufferedWriter(dir.resolve(file.getFileName().toString().replace("horde-", "horde-slow-")), StandardCharsets.UTF_8);
            slowOut.write("time\ttick\ttag\twall_ms\tcpu_ms\tgc_ms\tentities_ms\tmodmobs_ms\tjobs_ms\trest_ms\ttop_job\tautosave\tblock_ms\tblocks\tblock_who\tsave_ms\tsave_top");
            slowOut.newLine();
        } catch (IOException ex) {
            OvergrownVegetation.LOGGER.warn("[OV] horde meter: {}", ex.toString());
            return null;
        }
        ticksInSecond = 0;
        secondCpuStart = THREADS.getCurrentThreadCpuTime();
        on = true;
        return file;
    }

    static void stop() {
        on = false;
        measuringMob = false;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
        }
        if (slowOut != null) {
            try {
                slowOut.close();
            } catch (IOException ignored) {
            }
            slowOut = null;
        }
    }

    static void tag(String t) {
        tag = t;
    }

    static String describe(MinecraftServer server) {
        return describe() + "・台帳の書き換え待ち " + com.yayuy.overgrownvegetation.corruption.LedgerSpreadData.get(server).pendingCount();
    }

    static String describe() {
        return on ? "書き出し中: " + file + "(窓の名前 " + tag + ")" + (ChunkEdgeGuard.lastDenied == null ? "" : "・最後に先送りした所 " + ChunkEdgeGuard.lastDenied.toShortString()) : "止まっている";
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        stop();
    }
}
