package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.registry.ModBiomes;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 朝霧(2026-10-16 ユーザー指定「朝霧の谷に胞子がたまる」「山の上から森を見たとき、森が霧がかって見えるように」)。
 *
 * <p>夜明け前に、汚染地域とそのまわりの<b>低い所</b>へ霧の層がたまり、日が高くなると晴れる。サーバー(感染が進みやすい・
 * 胞子の露)とクライアント(見た目 {@code client/MorningMistView})が<b>同じ式</b>で答えを出す —— 時刻と朝ごとの当たりは
 * 時刻だけ、層の高さは地面の高さだけから決まる(どちらも読み込まれた所だけを見る。読み込みの差で縁が少し違うのは承知の上)。
 *
 * <ul>
 *     <li><b>いつ</b>: 3 時にでき始め、5〜7 時(日の出の前後)がいちばん濃く、9 時半に晴れる。朝ごとに当たりがあり、
 *     2 割の朝は霧が出ず、残りは薄い朝〜濃い朝(朝の番号のハッシュ。シードは見ない —— クライアントはシードを知らない)。
 *     雨の間は出ない。</li>
 *     <li><b>どこに</b>: 霧の層のてっぺん = まわり(9 × 9 の 16 ブロックの格子 = 144 ブロック四方)の表面(梢・地面・水面のいちばん上)の低い方から
 *     {@link #LOW_PERCENTILE} の高さ + 層の厚さ。谷は深く埋まり、尾根や丘は霧の上に出る。平らな森は梢の上まで厚さぶん霧に沈む。
 *     森からはみ出す分は格子 {@link #PROX_CELLS} 個まで(霧は境を気にしない)。</li>
 *     <li><b>層の中</b>: てっぺんから {@link #SOFT} ブロックかけて濃くなり、その下は一定。</li>
 * </ul>
 *
 * <p>数値は全部 Claude の仮決め。
 */
public final class MorningMist {

    /** 真夜中からの時刻(tick)。でき始め・濃さいっぱい・薄れ始め・晴れる。 */
    static final int FORM_START = 3000;
    static final int FULL_FROM = 5000;
    static final int FULL_UNTIL = 7000;
    static final int GONE = 9500;

    /** 霧が出ない朝の割合と、出る朝のいちばん薄い濃さ。 */
    static final double NO_MIST_CHANCE = 0.2;
    static final double LIGHT_MIN = 0.45;

    /** 地面を見る格子(ブロック)と、低い所を探す窓(格子の数、±)。 */
    public static final int GRID = 16;
    static final int WINDOW = 4;
    /** 窓の中の地面の高さの低い方から何割の所を霧の底にするか。 */
    static final double LOW_PERCENTILE = 0.4;
    /** 林冠の雲海の高さに使う、表面の高さの高い方の所(2026-10-17。見た目だけ。仮決め)。 */
    static final double HIGH_PERCENTILE = 0.9;
    /** 窓の中で読めた列がこれより少なければ、そこは分からない(霧を出さない)。 */
    static final int MIN_SAMPLES = 12;
    /** 層の厚さ(ブロック): 薄い朝 〜 濃い朝。晴れていくにつれ半分まで下がる。 */
    static final double DEPTH_MIN = 5.0;
    static final double DEPTH_RANGE = 5.0;
    /** てっぺんのぼかしの厚さ(ブロック)。 */
    public static final double SOFT = 3.0;
    /** 汚染地域からはみ出す距離(格子の数)。 */
    static final double PROX_CELLS = 3.0;
    /** 空の明るさがこれ未満の所(洞窟・建物の中)は霧の中に数えない。 */
    static final int MIN_SKY_LIGHT = 6;

    /** 調べた地面・低い所を覚えておく時間(tick)。全部読めなかった所はすぐ調べ直す。 */
    static final long TTL = 6000L;
    static final long TTL_PARTIAL = 100L;

    static final int MISSING = Integer.MIN_VALUE;
    private static final long SALT = 0x6D6973742D6D6F72L;

    // ---- いつ ----

    /** 真夜中からの tick(0〜23999)。バニラの dayTime 0 は朝 6 時。 */
    public static int sinceMidnight(long dayTime) {
        return (int) Math.floorMod(dayTime + 6000L, 24000L);
    }

    /** その朝の番号(真夜中で替わる)。 */
    public static long morningKey(long dayTime) {
        return Math.floorDiv(dayTime + 6000L, 24000L);
    }

    /** 時刻だけで決まる霧の出方(0〜1)。 */
    public static double timeCurve(long dayTime) {
        int s = sinceMidnight(dayTime);
        if (s <= FORM_START || s >= GONE) {
            return 0.0;
        }
        if (s < FULL_FROM) {
            return smooth((s - FORM_START) / (double) (FULL_FROM - FORM_START));
        }
        if (s <= FULL_UNTIL) {
            return 1.0;
        }
        return 1.0 - smooth((s - FULL_UNTIL) / (double) (GONE - FULL_UNTIL));
    }

    /** その朝の霧の当たり(0 = 出ない朝、{@link #LIGHT_MIN}〜1)。 */
    public static double morningAmount(long key) {
        double u = unit(mix(key * 0x9E3779B97F4A7C15L ^ SALT));
        if (u < NO_MIST_CHANCE) {
            return 0.0;
        }
        return LIGHT_MIN + (1.0 - LIGHT_MIN) * (u - NO_MIST_CHANCE) / (1.0 - NO_MIST_CHANCE);
    }

    /** 時刻と雨だけから決まる濃さ(0〜1)。 */
    public static double strength(long dayTime, double rainLevel) {
        double t = timeCurve(dayTime);
        if (t <= 0.0) {
            return 0.0;
        }
        return t * morningAmount(morningKey(dayTime)) * Mth.clamp(1.0 - rainLevel, 0.0, 1.0);
    }

    /** この次元の今の朝霧の濃さ(0〜1)。場所は見ない。空の無い次元・天井のある次元は 0。 */
    public static double strength(Level level) {
        if (!hasMistSky(level)) {
            return 0.0;
        }
        return strength(level.getDayTime(), level.getRainLevel(1.0F));
    }

    public static boolean hasMistSky(Level level) {
        return level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    /** 層の厚さ(ブロック)。 */
    public static double depth(long dayTime) {
        double amount = morningAmount(morningKey(dayTime));
        return (DEPTH_MIN + DEPTH_RANGE * amount) * (0.5 + 0.5 * timeCurve(dayTime));
    }

    /** 層のてっぺんからの高さ {@code h}(負 = 中)での濃さの割合(0〜1)。シェーダーの ovMistF と同じ形。 */
    public static double insideFactor(double h) {
        if (h >= 0.0) {
            return 0.0;
        }
        if (h <= -SOFT) {
            return 1.0;
        }
        return -h / SOFT;
    }

    // ---- 遊び ----

    /**
     * その場所が朝霧の中にある度合い(0〜1) = 濃さ × 層の中の深さ × 汚染地域の近さ。空の明るさ {@link #MIN_SKY_LIGHT} 未満
     * (洞窟・建物の中)と、読み込まれていない所は 0。
     */
    public static double exposureAt(Level level, double x, double y, double z) {
        double s = strength(level);
        if (s <= 0.0) {
            return 0.0;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return 0.0;
        }
        if (level.getBrightness(LightLayer.SKY, pos) < MIN_SKY_LIGHT) {
            return 0.0;
        }
        double[] out = new double[3];
        if (!sample(level, x, z, out)) {
            return 0.0;
        }
        double top = out[0] + depth(level.getDayTime());
        return s * out[1] * insideFactor(y - top);
    }

    /**
     * 空気中の胞子の量(見た目の光の筋・光る胞子の濃さに掛ける倍率。ユーザーの (4)「乾いた真昼は薄い」)。
     * 夜は 0.95、霧の朝は 1.0 + 霧の中の深さ × 0.5、真昼は 0.8(10〜16 時)、雨で半分まで。仮決め(真昼の見た目はユーザーが「これで良い」としたので下げすぎない)。
     */
    public static double airSpores(long dayTime, double rainLevel, double mistHere) {
        int s = sinceMidnight(dayTime);
        double base;
        if (s < FORM_START) {
            base = 0.95;
        } else if (s < FULL_UNTIL) {
            base = Mth.lerp((s - FORM_START) / (double) (FULL_UNTIL - FORM_START), 0.95, 1.0);
        } else if (s < 10000) {
            base = Mth.lerp((s - FULL_UNTIL) / (double) (10000 - FULL_UNTIL), 1.0, 0.8);
        } else if (s < 16000) {
            base = 0.8;
        } else if (s < 20000) {
            base = Mth.lerp((s - 16000) / 4000.0, 0.8, 0.95);
        } else {
            base = 0.95;
        }
        return (base + 0.5 * Mth.clamp(mistHere, 0.0, 1.0)) * (1.0 - 0.5 * Mth.clamp(rainLevel, 0.0, 1.0));
    }

    // ---- どこに(地面の低い所) ----

    /**
     * (x, z) の霧の底の高さ(低い所の高さ。てっぺんはこれ + {@link #depth})と汚染地域の近さ(0〜1)、表面の高い方の高さ({@link #HIGH_PERCENTILE}。
     * 林冠の雲海の見た目に使う)を {@code out}(長さ 3)へ。
     * 格子の中心の値を 2 次補間する。分からなければ false。
     */
    public static boolean sample(Level level, double x, double z, double[] out) {
        Cache cache = cache(level);
        long now = level.getGameTime();
        double fx = x / GRID - 0.5;
        double fz = z / GRID - 0.5;
        int gx = Mth.floor(fx);
        int gz = Mth.floor(fz);
        double tx = fx - gx;
        double tz = fz - gz;
        double low = 0.0;
        double high = 0.0;
        double prox = 0.0;
        double weight = 0.0;
        for (int i = 0; i < 4; i++) {
            int dx = i & 1;
            int dz = i >> 1;
            double w = (dx == 0 ? 1.0 - tx : tx) * (dz == 0 ? 1.0 - tz : tz);
            if (w <= 0.0) {
                continue;
            }
            double[] c = cell(level, cache, gx + dx, gz + dz, now);
            if (Double.isNaN(c[0])) {
                continue;
            }
            low += w * c[0];
            high += w * c[4];
            prox += w * c[1];
            weight += w;
        }
        if (weight < 0.25) {
            return false;
        }
        out[0] = low / weight;
        out[2] = high / weight;
        // 分からない格子の分は近さを薄める(読み込みの縁で霧が急に途切れないように)。
        out[1] = prox / weight * Math.min(1.0, weight * 1.5);
        return true;
    }

    /** 格子 (gx, gz) の {低い所の高さ(NaN = 分からない), 汚染地域の近さ, 調べた時刻, 全部読めたか, 高い所の高さ}。 */
    static double[] cell(Level level, Cache cache, int gx, int gz, long now) {
        long key = ChunkPos.asLong(gx, gz);
        double[] c = cache.cells.get(key);
        if (c != null && now - (long) c[2] < (c[3] > 0.5 ? TTL : TTL_PARTIAL) && now >= (long) c[2]) {
            return c;
        }
        int size = 2 * WINDOW + 1;
        int[] heights = new int[size * size];
        int n = 0;
        double prox = 0.0;
        for (int dz = -WINDOW; dz <= WINDOW; dz++) {
            for (int dx = -WINDOW; dx <= WINDOW; dx++) {
                long[] col = column(level, cache, gx + dx, gz + dz, now);
                if (col[0] == MISSING) {
                    continue;
                }
                heights[n++] = (int) col[0];
                if (col[1] != 0L) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    prox = Math.max(prox, Mth.clamp(1.0 - (d - 0.5) / PROX_CELLS, 0.0, 1.0));
                }
            }
        }
        double low = Double.NaN;
        double high = Double.NaN;
        if (n >= MIN_SAMPLES) {
            low = percentile(heights, n, LOW_PERCENTILE);
            high = percentile(heights, n, HIGH_PERCENTILE);
        }
        c = new double[] {low, prox, now, n == size * size ? 1.0 : 0.0, high};
        if (cache.cells.size() > 50_000) {
            cache.cells.clear();
        }
        cache.cells.put(key, c);
        return c;
    }

    /** 格子の列 (gx, gz) の {地面の高さ(読めなければ MISSING), 汚染地域か(0/1), 調べた時刻}。 */
    static long[] column(Level level, Cache cache, int gx, int gz, long now) {
        long key = ChunkPos.asLong(gx, gz);
        long[] col = cache.columns.get(key);
        if (col != null && now - col[2] < (col[0] == MISSING ? TTL_PARTIAL : TTL) && now >= col[2]) {
            return col;
        }
        int x = gx * GRID + GRID / 2;
        int z = gz * GRID + GRID / 2;
        int ground = surfaceOf(level, x, z);
        long overgrown = 0L;
        if (ground != MISSING && ModBiomes.isOvergrownLike(level.getBiome(new BlockPos(x, ground, z)))) {
            overgrown = 1L;
        }
        col = new long[] {ground, overgrown, now};
        if (cache.columns.size() > 100_000) {
            cache.columns.clear();
        }
        cache.columns.put(key, col);
        return col;
    }

    /**
     * (x, z) の列の表面の高さ(梢・地面・水面のいちばん上 = 高さ地図 MOTION_BLOCKING)。読み込まれていなければ MISSING。
     *
     * <p>初めは木・葉を抜いた地面から測ったが、密林の木(高さ 20〜30)の梢が霧の上に出て、山から見ても森が霞まなかった
     * (2026-10-16 の撮影)。本物の朝霧も谷を梢の上まで埋めるので、梢から測る。巨大樹のような飛び抜けた梢は、低い方から
     * {@link #LOW_PERCENTILE} の所を取るので効かない。
     */
    static int surfaceOf(Level level, int x, int z) {
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
            return MISSING;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    /** 小さい方から {@code q} の所の値(0 番目〜n−1 番目の間を補間)。{@code values} の頭 n 個を並べ替える。 */
    static double percentile(int[] values, int n, double q) {
        Arrays.sort(values, 0, n);
        double at = q * (n - 1);
        int i = (int) Math.floor(at);
        int j = Math.min(n - 1, i + 1);
        return Mth.lerp(at - i, values[i], values[j]);
    }

    // ---- 覚えておく所(次元ごと・サーバーとクライアントで別々) ----

    static final class Cache {
        final Long2ObjectOpenHashMap<long[]> columns = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<double[]> cells = new Long2ObjectOpenHashMap<>();
    }

    private static final Map<Level, Cache> CACHES = Collections.synchronizedMap(new WeakHashMap<>());

    static Cache cache(Level level) {
        return CACHES.computeIfAbsent(level, l -> new Cache());
    }

    private static double smooth(double t) {
        t = Mth.clamp(t, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private MorningMist() {
    }
}
