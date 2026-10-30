package com.yayuy.overgrownvegetation.corruption;

import net.minecraft.util.RandomSource;

/**
 * 台帳の広がり(2026-09-26、2026-09-28 に規則を変更)の<b>計算だけ</b>。世界にも台帳にも触らないので、GameTest から直接叩ける。
 *
 * <p>台帳の広がりは<b>判定の間隔ごとに 1 回</b>(ユーザー指定 5 分 = 6000 tick、1 日 4 回)、過繁茂と辺で接しているチャンクを、
 * 接している面の数に応じた確率で過繁茂にする({@link LedgerSpread})。確率はユーザー指定の 1 面 25%・2 面 50%・3 面 75%・4 面 100%。
 * 全部のチャンクをその回の初めの台帳から一斉に判定する(その回に塗ったチャンクが、同じ回のうちに隣を塗ることはない)。
 *
 * <p>この規則だと過繁茂は縁の長さに比例して増え、5 分おきなら前線は 1 日に約 1.8 チャンク進む(2026-09-28 の見積もり:
 * 16×3 チャンクの帯から 5 日で約 530、20 日で約 5000、40 日で約 18000、90 日で約 88000 チャンク。昇格ポイントはこの増え方から逆算)。
 */
public final class LedgerSpreadMath {

    /** 1 日の長さ(tick)。 */
    public static final long TICKS_PER_DAY = 24000L;

    /**
     * 汚染の暦 {@code clock} が何回目の判定の区切りの中か(0 始まり。{@code intervalTicks} の倍数の境目で 1 つ増える)。
     * 境目を越えたら、その前の回の判定をする。
     */
    public static long periodOf(long clock, int intervalTicks) {
        return Math.floorDiv(clock, Math.max(1, intervalTicks));
    }

    /**
     * 辺で接している過繁茂の数 {@code contacts}(1〜4)のときに、1 回の判定で過繁茂になる確率。
     * 表の外は端の値(5 以上は無いが念のため)。接していなければ 0。
     */
    public static double chanceFor(int contacts, double[] chances) {
        if (contacts <= 0 || chances.length == 0) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, chances[Math.min(contacts, chances.length) - 1]));
    }

    /**
     * 1 回の判定。{@code contacts[i]} 面で接しているチャンク i を、{@link #chanceFor} の確率で選ぶ。
     * {@code excluded[i]} が true のもの(胞子除去装置の範囲・ワールドの境界の外など)は選ばない。選んだ添字を返す。
     * 乱数は候補の並び順に 1 つずつ引く(確率 0 と 1 のものも引くので、同じ乱数なら並びが同じ限り結果も同じ)。
     */
    public static int[] roll(int[] contacts, boolean[] excluded, double[] chances, RandomSource random) {
        int[] picked = new int[contacts.length];
        int n = 0;
        for (int i = 0; i < contacts.length; i++) {
            double roll = random.nextDouble();
            if (excluded != null && excluded[i]) {
                continue;
            }
            if (roll < chanceFor(contacts[i], chances)) {
                picked[n++] = i;
            }
        }
        return java.util.Arrays.copyOf(picked, n);
    }

    /** {@code period} 回目の判定に使う乱数の種(世界のシードと回の番号だけで決まる)。 */
    public static long periodSeed(long worldSeed, long period) {
        return mix(worldSeed ^ (period * 0x9E3779B97F4A7C15L));
    }

    /**
     * 極相の齢が足りたか(2026-09-26 のユーザー指定)。
     * 「過繁茂になってからの日数 − F1 開始から F5 までにかかった日数」が {@code minAgeTicks} 以上。
     * 差し引く分があるので、F5 になった瞬間に古いチャンクが一斉に極相になることはなく、
     * いちばん古いチャンク(F1 開始のころに過繁茂になったもの)が F5 の {@code minAgeTicks} 後に最初に届き、
     * あとは過繁茂になった順に続く。
     */
    public static boolean climaxAgeReached(long overgrownSince, long now, long minAgeTicks,
                                           long phase1Clock, long phase5Clock) {
        return now - overgrownSince - phaseDelay(phase1Clock, phase5Clock) >= minAgeTicks;
    }

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
