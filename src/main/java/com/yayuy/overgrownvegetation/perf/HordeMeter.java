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
        if (on) tickStart = System.nanoTime();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityPre(EntityTickEvent.Pre event) {
        if (on && !event.getEntity().level().isClientSide && event.getEntity() instanceof Mob && isMod(event.getEntity())) {
            measuringMob = true;
            mobTickStart = System.nanoTime();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityPost(EntityTickEvent.Post event) {
        if (measuringMob && !event.getEntity().level().isClientSide) {
            measuringMob = false;
            mobTicks++;
            mobTickNanos += System.nanoTime() - mobTickStart;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTickPost(ServerTickEvent.Post event) {
        if (!on) return;
        TICK_NANOS[ticksInSecond++] = System.nanoTime() - tickStart;
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
        String line = String.format(Locale.ROOT,
                "%s\t%d\t%s\t%.2f\t%.1f\t%.2f\t%d\t%d\t%d\t%d\t%d\t%d\t%.3f\t%d\t%d\t%d\t%.3f\t%d\t%d\t%.3f\t%d\t%.3f\t%d\t%d\t%s\t%d",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)), server.getTickCount(), tag,
                sum / 1e6 / ticksInSecond, max / 1e6, cpuPerTick,
                mods, near, mid, far, targeting, others,
                mobTicks == 0 ? 0.0D : mobTickNanos / 1e6 / ticksInSecond, mobTicks,
                pathCalls, pathCallsMod, pathNanos / 1e6 / ticksInSecond,
                pushCalls, pushFound, pushNanos / 1e6 / ticksInSecond,
                sendCalls, sendNanos / 1e6 / ticksInSecond, packets, held,
                (cfg.mobThinkThrottleEnabled.get() ? "thr" : "-") + (cfg.partIndexEnabled.get() ? "+pi" : ""), server.overworld().getPartEntities().size());
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
                    + "\tpath\tpath_mod\tpath_ms\tpush\tpush_found\tpush_ms\tsend\tsend_ms\tpackets\theld\tswitches\tparts\tticks_ms");
            out.newLine();
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
    }

    static void tag(String t) {
        tag = t;
    }

    static String describe() {
        return on ? "書き出し中: " + file + "(窓の名前 " + tag + ")" : "止まっている";
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        stop();
    }
}
