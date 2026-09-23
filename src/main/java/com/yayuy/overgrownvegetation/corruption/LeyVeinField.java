package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>地脈</b>の走る場所を決める場(2026-09-22)。
 *
 * <h2>なぜボロノイの境界なのか</h2>
 * <p>欲しいのは「1 チャンクで完結しない、巨大な網目」。<b>ボロノイ図の境界線</b>がそのまま網目になる ——
 * 平面をセルで埋めると、セルとセルの境目が必ず繋がった線の網になり、行き止まりができない。
 * セルの大きさ({@code cellSize}、既定 192 ブロック = 12 チャンク)が網目の粗さになる。
 *
 * <h2>チャンクをまたいで必ず繋がる</h2>
 * <p>この場は<b>シードと座標だけの決定的な関数</b>で、チャンクの内容も隣のチャンクも見ない。
 * どのチャンクをいつ計算しても同じ答えになるので、チャンクごとに独立して地脈を置いても
 * <b>境目で線がずれない</b>。乱数で線を引くやり方だと必ずチャンクの縁で途切れる。
 *
 * <h2>まっすぐにしない</h2>
 * <p>素のボロノイは直線の多角形になるので、座標をノイズで歪めてから
 * ({@code warp})セルを引く。地脈がうねって、人工的な折れ線に見えなくなる。
 */
public final class LeyVeinField {

    /** ワールドシードごとの歪みノイズ。{@link OvergrowthRegionMap} と同じ作り。 */
    private static final Map<Long, SimplexNoise> WARP_NOISE = new ConcurrentHashMap<>();

    /** 地脈のセルのシード塩。地域マップと同じ母点を使うと、環境の境目と地脈が重なってしまう。 */
    private static final long SALT = 0x1E4A1E17L;

    private LeyVeinField() {
    }

    /**
     * その座標が地脈の上にある度合い(0〜1)。境界線のちょうど上で 1、{@code width} の半分だけ
     * 離れると 0。地脈ブロックを置くかどうかの判定にも、器官の生成地点を選ぶのにも使う。
     */
    public static double strengthAt(long seed, int blockX, int blockZ) {
        ClimaxTable.LeyVein spec = DesignData.climax().leyVein();
        if (spec.width() <= 0.0D || spec.cellSize() <= 0.0D) {
            return 0.0D;
        }
        double[] warped = warp(seed, blockX, blockZ, spec);
        double toBorder = distanceToBorder(seed, warped[0] / spec.cellSize(), warped[1] / spec.cellSize())
                * spec.cellSize();
        double half = spec.width() * 0.5D;
        if (toBorder >= half) {
            return 0.0D;
        }
        return 1.0D - (toBorder / half);
    }

    /** その座標に地脈ブロックを置いてよいか。 */
    public static boolean isVeinAt(long seed, int blockX, int blockZ) {
        return strengthAt(seed, blockX, blockZ) > 0.0D;
    }

    /**
     * {@code (originX, originZ)} から<b>いちばん近い地脈の上の点</b>を探す(2026-09-22)。
     * {@code /ov climax ley} が「地脈が見つからない」ときのテレポート先に使う。
     *
     * <p>地脈は網目なので、どこに立っていても {@code cellSize} の半分ほど歩けば必ずぶつかる
     * (既定 192 ブロックなら 100 ブロック以内)。それでも見つからないのは
     * {@code width} を 0 にしているときくらい。
     *
     * <p>チェビシェフ距離の輪を内側から広げて、最初に当たった点を返す。輪の上は
     * <b>2 ブロックおき</b>に見る —— 地脈の幅は既定 5 ブロックあるので、2 おきでも跨がない。
     *
     * @param maxRadius 探す範囲(ブロック)
     * @return 見つからなければ {@code null}。戻り値の y は入れていない({@code x}/{@code z} だけ)
     */
    public static int[] nearestVein(long seed, int originX, int originZ, int maxRadius) {
        if (isVeinAt(seed, originX, originZ)) {
            return new int[]{originX, originZ};
        }
        for (int r = 2; r <= maxRadius; r += 2) {
            for (int d = -r; d <= r; d += 2) {
                int[] hit = firstHit(seed,
                        new int[]{originX + d, originX + d, originX - r, originX + r},
                        new int[]{originZ - r, originZ + r, originZ + d, originZ + d});
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 4 つの候補のうち、最初に地脈の上に載っているものを返す。 */
    private static int[] firstHit(long seed, int[] xs, int[] zs) {
        for (int i = 0; i < xs.length; i++) {
            if (isVeinAt(seed, xs[i], zs[i])) {
                return new int[]{xs[i], zs[i]};
            }
        }
        return null;
    }

    /** 座標をノイズで歪める(地脈をうねらせる)。戻りは {@code [x, z]}。 */
    private static double[] warp(long seed, int blockX, int blockZ, ClimaxTable.LeyVein spec) {
        double x = blockX;
        double z = blockZ;
        double amount = spec.warp();
        if (amount > 0.0D) {
            SimplexNoise noise = WARP_NOISE.computeIfAbsent(seed ^ SALT,
                    s -> new SimplexNoise(RandomSource.create(s)));
            double scale = 1.0D / Math.max(16.0D, spec.cellSize() * 0.75D);
            double amp = spec.cellSize() * amount;
            x += noise.getValue(blockX * scale, blockZ * scale) * amp;
            z += noise.getValue(blockZ * scale + 1337.0D, blockX * scale - 911.0D) * amp;
        }
        return new double[]{x, z};
    }

    /**
     * セル座標 {@code (cx, cz)} から、いちばん近い母点と 2 番目の母点の<b>垂直二等分線</b>
     * (= ボロノイの境界)までの距離(セル単位)。
     *
     * <p>周囲 3×3 のセルの母点を見れば足りる(母点はセルの中に 1 つずつ置くので、
     * 2 番目に近い母点が 2 セル以上離れることはない)。
     */
    private static double distanceToBorder(long seed, double cx, double cz) {
        int baseX = Mth.floor(cx);
        int baseZ = Mth.floor(cz);

        double bestSq = Double.MAX_VALUE;
        double bestX = 0.0D;
        double bestZ = 0.0D;
        double secondSq = Double.MAX_VALUE;
        double secondX = 0.0D;
        double secondZ = 0.0D;

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int gx = baseX + dx;
                int gz = baseZ + dz;
                long h = hash(seed, gx, gz);
                // セルの中の母点の位置(0〜1 をセルの内側へ)。
                double px = gx + ((h >>> 16) & 0xFFFF) / 65536.0D;
                double pz = gz + ((h >>> 32) & 0xFFFF) / 65536.0D;
                double ddx = px - cx;
                double ddz = pz - cz;
                double dSq = ddx * ddx + ddz * ddz;
                if (dSq < bestSq) {
                    secondSq = bestSq;
                    secondX = bestX;
                    secondZ = bestZ;
                    bestSq = dSq;
                    bestX = px;
                    bestZ = pz;
                } else if (dSq < secondSq) {
                    secondSq = dSq;
                    secondX = px;
                    secondZ = pz;
                }
            }
        }
        if (secondSq == Double.MAX_VALUE) {
            return Double.MAX_VALUE;
        }
        // 点から 2 母点の垂直二等分線までの距離 = (d2² - d1²) / (2 * 母点間の距離)。
        double siteDist = Math.sqrt((secondX - bestX) * (secondX - bestX) + (secondZ - bestZ) * (secondZ - bestZ));
        if (siteDist < 1.0e-6D) {
            return Double.MAX_VALUE;
        }
        return (secondSq - bestSq) / (2.0D * siteDist);
    }

    private static long hash(long seed, int x, int z) {
        long h = seed ^ SALT;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return h;
    }
}
