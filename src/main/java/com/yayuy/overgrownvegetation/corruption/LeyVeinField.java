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
     * いちばん近い地脈の線(セルの境界)までの水平の距離(ブロック、歪めた座標の上)。場は極相の外にも続いているので、どこでも測れる
     * (2026-10-16、㉙ 地脈の汲み上げ器の効率。線が無い設定なら {@link Double#MAX_VALUE})。セルの真ん中でおよそ {@code cellSize / 2}。
     */
    public static double distanceToLine(long seed, int blockX, int blockZ) {
        ClimaxTable.LeyVein spec = DesignData.climax().leyVein();
        if (spec.cellSize() <= 0.0D) {
            return Double.MAX_VALUE;
        }
        double[] warped = warp(seed, blockX, blockZ, spec);
        return distanceToBorder(seed, warped[0] / spec.cellSize(), warped[1] / spec.cellSize()) * spec.cellSize();
    }

    /**
     * 地脈の<b>流れ</b>(2026-10-16、ユーザー選択「地面から透ける光の帯」。過繁茂の森で時々、地脈になる線に沿って光が流れる)。
     * {@code out} = {強さ(0〜1、{@link #strengthAt} と同じ), 線の向き x, 線の向き z, 線に沿った座標(ブロック)}。地脈の上でなければ false。
     *
     * <p>線の向きは、境界をはさむ 2 つの母点を結ぶ向きに直角。2 つの母点の順番(ハッシュの大小)で向きの符号を決めるので、
     * 同じ境界の上ではどこでも同じ向きになる(光が線に沿って一方向へ流れる)。向きと沿った座標は歪めた座標の上のもので、
     * 歪みはなめらかなので見た目にはほぼ線に沿う。網目の継ぎ目(3 つのセルが会う所)では向きが変わる。
     * 場と同じくシードと座標だけで決まるので、クライアントへはサーバーが計算して送る(シードはクライアントへ送らない)。
     */
    public static boolean flowAt(long seed, int blockX, int blockZ, double[] out) {
        ClimaxTable.LeyVein spec = DesignData.climax().leyVein();
        if (spec.width() <= 0.0D || spec.cellSize() <= 0.0D) {
            return false;
        }
        double[] warped = warp(seed, blockX, blockZ, spec);
        double cx = warped[0] / spec.cellSize();
        double cz = warped[1] / spec.cellSize();
        int baseX = Mth.floor(cx);
        int baseZ = Mth.floor(cz);
        double bestSq = Double.MAX_VALUE;
        double secondSq = Double.MAX_VALUE;
        double bestX = 0.0D;
        double bestZ = 0.0D;
        double secondX = 0.0D;
        double secondZ = 0.0D;
        long bestHash = 0L;
        long secondHash = 0L;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int gx = baseX + dx;
                int gz = baseZ + dz;
                long h = hash(seed, gx, gz);
                double px = gx + ((h >>> 16) & 0xFFFF) / 65536.0D;
                double pz = gz + ((h >>> 32) & 0xFFFF) / 65536.0D;
                double dSq = (px - cx) * (px - cx) + (pz - cz) * (pz - cz);
                if (dSq < bestSq) {
                    secondSq = bestSq;
                    secondX = bestX;
                    secondZ = bestZ;
                    secondHash = bestHash;
                    bestSq = dSq;
                    bestX = px;
                    bestZ = pz;
                    bestHash = h;
                } else if (dSq < secondSq) {
                    secondSq = dSq;
                    secondX = px;
                    secondZ = pz;
                    secondHash = h;
                }
            }
        }
        if (secondSq == Double.MAX_VALUE) {
            return false;
        }
        double sx = secondX - bestX;
        double sz = secondZ - bestZ;
        double siteDist = Math.sqrt(sx * sx + sz * sz);
        if (siteDist < 1.0e-6D) {
            return false;
        }
        double toBorder = (secondSq - bestSq) / (2.0D * siteDist) * spec.cellSize();
        double half = spec.width() * 0.5D;
        if (toBorder >= half) {
            return false;
        }
        // 2 つの母点を結ぶ向きに直角 = 境界の向き。母点のハッシュの小さい方から大きい方への向きを基準に符号を揃える。
        double tx = -sz / siteDist;
        double tz = sx / siteDist;
        if (Long.compareUnsigned(bestHash, secondHash) > 0) {
            tx = -tx;
            tz = -tz;
        }
        out[0] = 1.0D - toBorder / half;
        out[1] = tx;
        out[2] = tz;
        out[3] = warped[0] * tx + warped[1] * tz;
        return true;
    }

    /**
     * そのチャンクに地脈が通っているか(順 1、2026-10-03。フロエマイトの「地脈のあるチャンクを中心に 3×3」の判定)。
     * 場はシードと座標だけの関数なので、<b>チャンクを読み込まずに</b>隣のチャンクにも聞ける。
     *
     * <p>2 ブロックおきの格子に<b>最後の列・行(15)を足した</b> 9×9 = 81 点で見る。地脈の幅は既定 5 ブロックあるので、
     * チャンクを横切る地脈は必ずどれかの点に掛かる。15 を足したのは、チャンクの縁に沿って
     * <b>端の 1 列だけをかすめる地脈</b>を拾うため(0〜14 の偶数だけだと、15 列目に 8 マス掛かる地脈を見落とした。
     * 2026-10-03 の GameTest)。それでも角を 1〜2 マスかすめるだけのものは拾わないことがあるが、そのときは隣のチャンクに
     * 地脈の本体があるので、3×3 の判定の結果は変わらない。
     * 2 おきの格子が斜めの帯を取りこぼさないのは幅が 2√2(≒ 2.83)以上のときなので、
     * 幅を 3 未満にしたら 1 ブロックおきに見る。
     */
    public static boolean hasVeinInChunk(long seed, int chunkX, int chunkZ) {
        ClimaxTable.LeyVein spec = DesignData.climax().leyVein();
        if (spec.width() <= 0.0D || spec.cellSize() <= 0.0D) {
            return false;
        }
        int step = spec.width() >= 3.0D ? 2 : 1;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int i = 0; i <= 16; i += step) {
            int dz = Math.min(i, 15);
            for (int j = 0; j <= 16; j += step) {
                int dx = Math.min(j, 15);
                if (isVeinAt(seed, baseX + dx, baseZ + dz)) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * {@code (originX, originZ)} からいちばん近い<b>地脈の線の真ん中</b>の点(2026-10-22、地脈探しの針の先)。読み込みは要らない。
     *
     * <p>歪めた座標の上で、いちばん近い母点と 2 番目の母点の垂直二等分線(= 線)までの向きと距離は式で出るので、
     * その分だけ動いては測り直す(歪みはなめらかなので数回で線に着く)。ただし一歩が別の線へ飛ぶことがある(3 つのセルが会う所の近くや
     * 歪みの強い所。2026-10-22 のテストクライアントで 135 ブロック先、GameTest で 19 のところ 46 を返した)ので、歩いて着いた距離を
     * <b>上限</b>にして、その内側を輪で総当たりし(2 おき)、ユークリッド距離でいちばん近い線の点を取る。輪の半径 r より近い点は
     * もう出ないので、見つけた最短の距離を r が越えたら止める。最後に半径 3 で線の真ん中(強さの最大)へ寄せる。
     *
     * @return {@code [x, z]}。線が無い設定か {@code maxRadius} の中に無ければ null
     */
    public static int[] nearestLinePoint(long seed, int originX, int originZ, int maxRadius) {
        ClimaxTable.LeyVein spec = DesignData.climax().leyVein();
        if (spec.width() <= 0.0D || spec.cellSize() <= 0.0D) {
            return null;
        }
        if (strengthAt(seed, originX, originZ) > 0.0D) {
            return strongestNear(seed, originX, originZ, 3); // もう線の帯の中: 近くのいちばん真ん中へ
        }
        // 1. 歩いて上限を得る
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        double px = originX;
        double pz = originZ;
        for (int i = 0; i < MAX_STEPS; i++) {
            int bx = Mth.floor(px);
            int bz = Mth.floor(pz);
            if (strengthAt(seed, bx, bz) > 0.0D) {
                best = new int[]{bx, bz};
                bestD = Math.hypot(bx - originX, bz - originZ);
                break;
            }
            double[] step = borderStep(seed, px + 0.5D, pz + 0.5D, spec);
            if (step == null) {
                break;
            }
            // 遠いうちは 8 割だけ進む(行き過ぎて向こうの線へ飛ばないように)
            double k = Math.hypot(step[0], step[1]) > 4.0D ? 0.8D : 1.0D;
            px += step[0] * k;
            pz += step[1] * k;
        }
        // 2. 上限の内側を輪で総当たり
        for (int r = 2; r <= maxRadius && r < bestD; r += 2) {
            for (int d = -r; d <= r; d += 2) {
                int[][] cands = {{originX + d, originZ - r}, {originX + d, originZ + r}, {originX - r, originZ + d}, {originX + r, originZ + d}};
                for (int[] c : cands) {
                    double dist = Math.hypot(c[0] - originX, c[1] - originZ);
                    if (dist < bestD && isVeinAt(seed, c[0], c[1])) {
                        best = c;
                        bestD = dist;
                    }
                }
            }
        }
        if (best == null) {
            return null;
        }
        int[] hit = strongestNear(seed, best[0], best[1], 3);
        return Math.abs(hit[0] - originX) <= maxRadius && Math.abs(hit[1] - originZ) <= maxRadius ? hit : null;
    }

    /** {@code (x, z)} から半径 {@code r} の中で、線の強さがいちばん大きい(線の真ん中に近い)マス。 */
    private static int[] strongestNear(long seed, int x, int z, int r) {
        int[] best = {x, z};
        double bestS = strengthAt(seed, x, z);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                double s = strengthAt(seed, x + dx, z + dz);
                if (s > bestS + 1.0e-9D || (Math.abs(s - bestS) <= 1.0e-9D && dx * dx + dz * dz < Mth.square(best[0] - x) + Mth.square(best[1] - z))) {
                    bestS = s;
                    best = new int[]{x + dx, z + dz};
                }
            }
        }
        return best;
    }

    /** {@link #nearestLinePoint} の歩数の上限。 */
    private static final int MAX_STEPS = 12;

    /** 歪めた座標の上で、いちばん近い線(2 番目の母点との垂直二等分線)までの移動量(ブロック)。 */
    private static double[] borderStep(long seed, double x, double z, ClimaxTable.LeyVein spec) {
        double[] warped = warp(seed, x, z, spec);
        double cx = warped[0] / spec.cellSize();
        double cz = warped[1] / spec.cellSize();
        int baseX = Mth.floor(cx);
        int baseZ = Mth.floor(cz);
        double bestSq = Double.MAX_VALUE;
        double secondSq = Double.MAX_VALUE;
        double bestX = 0.0D;
        double bestZ = 0.0D;
        double secondX = 0.0D;
        double secondZ = 0.0D;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int gx = baseX + dx;
                int gz = baseZ + dz;
                long h = hash(seed, gx, gz);
                double sx = gx + ((h >>> 16) & 0xFFFF) / 65536.0D;
                double sz = gz + ((h >>> 32) & 0xFFFF) / 65536.0D;
                double dSq = (sx - cx) * (sx - cx) + (sz - cz) * (sz - cz);
                if (dSq < bestSq) {
                    secondSq = bestSq;
                    secondX = bestX;
                    secondZ = bestZ;
                    bestSq = dSq;
                    bestX = sx;
                    bestZ = sz;
                } else if (dSq < secondSq) {
                    secondSq = dSq;
                    secondX = sx;
                    secondZ = sz;
                }
            }
        }
        if (secondSq == Double.MAX_VALUE) {
            return null;
        }
        double nx = secondX - bestX;
        double nz = secondZ - bestZ;
        double siteDist = Math.sqrt(nx * nx + nz * nz);
        if (siteDist < 1.0e-6D) {
            return null;
        }
        double toBorder = (secondSq - bestSq) / (2.0D * siteDist) * spec.cellSize();
        return new double[]{nx / siteDist * toBorder, nz / siteDist * toBorder};
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
    private static double[] warp(long seed, double blockX, double blockZ, ClimaxTable.LeyVein spec) {
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
