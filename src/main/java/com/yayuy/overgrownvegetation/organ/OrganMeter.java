package com.yayuy.overgrownvegetation.organ;

import java.util.Arrays;
import java.util.Locale;

/**
 * 器官の処理の重さの物差し(2026-11-18、F7 の見込みの器官の数で測るため)。{@link OrganWorld} の毎 tick の処理(育ち・置く・枯らす・役割・守り)の
 * 壁の時計を tick ごとに覚え、{@code /ov perf organ stats} で平均・P95・P99・最大を出す。覚えるのは直近 {@link #N} tick。
 */
public final class OrganMeter {
    private OrganMeter() {
    }

    static final int N = 6000;
    private static final long[] RING = new long[N];
    private static int next;
    private static int filled;
    private static long blocks;

    static void record(long nanos) {
        RING[next] = nanos;
        next = (next + 1) % N;
        if (filled < N) filled++;
    }

    /** 置いた・枯らしたブロックの数(stats で出す)。 */
    static void blocks(int n) {
        blocks += n;
    }

    private static final java.util.ArrayDeque<String> SLOW = new java.util.ArrayDeque<>();

    /** 5 ms を超えた器官 1 つの処理(直近 20)。 */
    static void slow(String line) {
        if (SLOW.size() >= 20) SLOW.removeFirst();
        SLOW.addLast(line);
    }

    public static java.util.List<String> slowLines() {
        return java.util.List.copyOf(SLOW);
    }

    public static void reset() {
        SLOW.clear();
        next = 0;
        filled = 0;
        blocks = 0;
    }

    public static String summary() {
        if (filled == 0) return "器官の処理: まだ数えていない";
        long[] s = Arrays.copyOf(filled < N ? Arrays.copyOf(RING, filled) : RING.clone(), filled);
        Arrays.sort(s);
        double sum = 0;
        for (long v : s) sum += v;
        return String.format(Locale.ROOT, "器官の処理 %d tick: 平均 %.3f ms・P95 %.3f・P99 %.3f・最大 %.3f・置いた/枯らしたブロック %d", filled,
                sum / filled / 1e6, s[(int) Math.min(filled - 1, Math.floor(filled * 0.95))] / 1e6,
                s[(int) Math.min(filled - 1, Math.floor(filled * 0.99))] / 1e6, s[filled - 1] / 1e6, blocks);
    }
}
