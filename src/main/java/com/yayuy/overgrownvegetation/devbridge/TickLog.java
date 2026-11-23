package com.yayuy.overgrownvegetation.devbridge;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * サーバーの 1 tick の時間を次の N tick ぶん全部とって、平均・p50/p95/p99/p99.9・最大と、自動保存の tick を分けて書く(11-23 サーバーの tick の軽量化)。
 * devbridge の `ticklog <名前> <tick 数>`。結果は devbridge/ticklog-<名前>.txt と out.log の `TICKLOG …`。
 * 1 tick = ServerTickEvent.Pre から Post まで(自動保存も入る)。tick sprint 中なら Post から次の Post までも書く(tick の間の仕事も入る)。
 */
final class TickLog {
    private TickLog() {
    }

    private static boolean registered;
    private static volatile boolean on;
    private static String name;
    private static int want, n;
    private static long[] work, gap;
    private static long[] gameTick;
    private static long[] wall;
    private static boolean[] saved;
    private static long pre, lastPost;
    private static boolean saveThisTick;
    private static Path out;
    private static Consumer<String> log;
    private static long spread0, spreadTicks0;

    static String start(MinecraftServer server, Path dir, String arg, Consumer<String> logger) {
        String[] p = arg.trim().split("\\s+");
        if (p.length < 2) return "ticklog <名前> <tick 数>";
        int ticks = Integer.parseInt(p[1]);
        server.execute(() -> {
            if (!registered) {
                registered = true;
                NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, TickLog::onPre);
                NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, TickLog::onPost);
                NeoForge.EVENT_BUS.addListener(TickLog::onSave);
            }
            name = p[0];
            want = ticks;
            n = 0;
            work = new long[ticks];
            gap = new long[ticks];
            gameTick = new long[ticks];
            wall = new long[ticks];
            saved = new boolean[ticks];
            pre = lastPost = 0L;
            out = dir.resolve("ticklog-" + name + ".txt");
            log = logger;
            spread0 = com.yayuy.overgrownvegetation.perf.SpreadSave.saved;
            spreadTicks0 = com.yayuy.overgrownvegetation.perf.SpreadSave.ticks;
            on = true;
        });
        return "TICKLOG 始め " + p[0] + " " + ticks;
    }

    private static void onPre(ServerTickEvent.Pre e) {
        if (!on) return;
        pre = System.nanoTime();
    }

    private static void onSave(LevelEvent.Save e) {
        if (on) saveThisTick = true;
    }

    private static void onPost(ServerTickEvent.Post e) {
        if (!on || pre == 0L) return;
        long now = System.nanoTime();
        work[n] = now - pre;
        gap[n] = lastPost == 0L ? 0L : now - lastPost;
        gameTick[n] = e.getServer().overworld().getGameTime();
        wall[n] = System.currentTimeMillis();
        saved[n] = saveThisTick;
        saveThisTick = false; // tick の間の保存(save-all など)は次の tick に印が付く(時間は Post→Post の方に入る)
        lastPost = now;
        n++;
        if (n >= want) {
            on = false;
            write();
        }
    }

    private static void write() {
        StringBuilder sb = new StringBuilder();
        String head = summary("全部", work, n, null) + " | " + summary("保存の無い tick", work, n, false) + " | 保存の tick " + list(true)
                + " | 先に書いたチャンク " + (com.yayuy.overgrownvegetation.perf.SpreadSave.saved - spread0) + "(余り時間の無い tick " + (com.yayuy.overgrownvegetation.perf.SpreadSave.ticks - spreadTicks0) + ")";
        sb.append(head).append('\n');
        sb.append(summary("Post→Post(sprint 中の 1 tick の間)", gap, n, null)).append('\n');
        // 重い順に 30 tick
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Long.compare(work[b], work[a]));
        sb.append("重い順:\n");
        for (int i = 0; i < Math.min(30, n); i++) {
            int k = idx[i];
            sb.append(String.format(Locale.ROOT, "  #%d gametime=%d %.1f ms%s%n", k, gameTick[k], work[k] / 1.0E6D, saved[k] ? " (保存)" : ""));
        }
        try {
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            StringBuilder csv = new StringBuilder("i,gametime,workMs,gapMs,saved,epochMs\n");
            for (int i = 0; i < n; i++)
                csv.append(i).append(',').append(gameTick[i]).append(',').append(String.format(Locale.ROOT, "%.3f,%.3f", work[i] / 1.0E6D, gap[i] / 1.0E6D)).append(',').append(saved[i] ? 1 : 0)
                        .append(',').append(wall[i]).append('\n');
            Files.writeString(out.resolveSibling("ticklog-" + name + ".csv"), csv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            sb.append("書けない: ").append(ex);
        }
        if (log != null) log.accept("TICKLOG " + name + " " + head);
    }

    private static String list(boolean savedOnly) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (saved[i] != savedOnly) continue;
            if (sb.length() > 1) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%.0f", work[i] / 1.0E6D));
        }
        return sb.append("] ms").toString();
    }

    private static String summary(String label, long[] v, int count, Boolean savedFilter) {
        long[] a = new long[count];
        int m = 0;
        double sum = 0.0D;
        for (int i = 0; i < count; i++) {
            if (savedFilter != null && saved[i] != savedFilter) continue;
            a[m++] = v[i];
            sum += v[i];
        }
        if (m == 0) return label + ": なし";
        a = Arrays.copyOf(a, m);
        Arrays.sort(a);
        return String.format(Locale.ROOT, "%s: n=%d 平均 %.2f ms p50 %.2f p95 %.2f p99 %.2f p99.9 %.2f 最大 %.1f",
                label, m, sum / m / 1.0E6D, pct(a, 0.50D), pct(a, 0.95D), pct(a, 0.99D), pct(a, 0.999D), a[m - 1] / 1.0E6D);
    }

    private static double pct(long[] sorted, double q) {
        int i = (int) Math.min(sorted.length - 1, Math.floor(q * sorted.length));
        return sorted[i] / 1.0E6D;
    }
}
