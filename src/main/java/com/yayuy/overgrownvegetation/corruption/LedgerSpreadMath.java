package com.yayuy.overgrownvegetation.corruption;


/**
 * 台帳の書き換え待ちの<b>計算だけ</b>(地表の塗り残しの模様)。世界にも台帳にも触らないので、GameTest から直接叩ける。
 * 2026-10-30: 止まっていた「5 分おきに縁を面の数 × 25%」の判定(回の数え方・確率・乱数の種)と極相の齢の判定を消した。
 */
public final class LedgerSpreadMath {

    /** 1 日の長さ(tick)。 */
    public static final long TICKS_PER_DAY = 24000L;

    /** F1 開始から F5 までにかかった暦の長さ(tick)。逆転していたら 0。 */
    public static long phaseDelay(long phase1Clock, long phase5Clock) {
        return Math.max(0L, phase5Clock - phase1Clock);
    }

    // --- 地表の塗り残しの模様 ---

    /**
     * 列 {@code (x, z)} の模様の値(0〜1)。座標とシードだけで決まる値ノイズを 2 段重ねたもの(格子 {@code cell} ブロックと
     * その 0.4 倍)。連続なので、チャンクの境目でも模様がつながる(四角い塗り残しにならない)。
     * 汚染するかは {@code patch < noiseThreshold(割合)} で決める。
     */
    public static double patch(long seed, int x, int z, double cell) {
        double coarse = valueNoise(seed, x / cell, z / cell);
        double fine = valueNoise(seed ^ 0x632BE59BD9B4E019L, x / (cell * 0.4D), z / (cell * 0.4D));
        return coarse * 0.7D + fine * 0.3D;
    }

    /**
     * {@link #patch} がこの値より小さい列の割合が、おおよそ {@code coverage} になるしきい値。
     * 値ノイズは真ん中に寄った分布なので、割合をそのまましきい値にすると塗りすぎる。分布の分位点を 1 度だけ数えて引く。
     */
    public static double noiseThreshold(double coverage) {
        if (coverage <= 0.0D) {
            return 0.0D;
        }
        if (coverage >= 1.0D) {
            return 1.0D;
        }
        double[] q = quantiles();
        double pos = coverage * (q.length - 1);
        int i = (int) Math.floor(pos);
        double t = pos - i;
        return i + 1 < q.length ? q[i] + (q[i + 1] - q[i]) * t : q[q.length - 1];
    }

    private static volatile double[] quantileTable;

    private static double[] quantiles() {
        double[] table = quantileTable;
        if (table == null) {
            int side = 256;
            double[] samples = new double[side * side];
            int n = 0;
            for (int x = 0; x < side; x++) {
                for (int z = 0; z < side; z++) {
                    samples[n++] = patch(0x5EEDL, x * 7, z * 7, 6.0D);
                }
            }
            java.util.Arrays.sort(samples);
            table = new double[101];
            for (int k = 0; k <= 100; k++) {
                table[k] = samples[Math.min(samples.length - 1, (int) Math.round(k / 100.0D * (samples.length - 1)))];
            }
            table[0] = 0.0D;
            table[100] = 1.0D;
            quantileTable = table;
        }
        return table;
    }

    private static double valueNoise(long seed, double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double sx = fx * fx * (3.0D - 2.0D * fx);
        double sz = fz * fz * (3.0D - 2.0D * fz);
        double a = lattice(seed, x0, z0);
        double b = lattice(seed, x0 + 1, z0);
        double c = lattice(seed, x0, z0 + 1);
        double d = lattice(seed, x0 + 1, z0 + 1);
        double top = a + (b - a) * sx;
        double bottom = c + (d - c) * sx;
        return top + (bottom - top) * sz;
    }

    private static double lattice(long seed, int x, int z) {
        return (mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL)) >>> 11) * 0x1.0p-53;
    }

    /** SplitMix64 の仕上げ。 */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private LedgerSpreadMath() {
    }
}
