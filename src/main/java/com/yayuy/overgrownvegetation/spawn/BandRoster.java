package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * 森からの距離の帯と、縁だけを選ぶくじの名簿(2026-11-17 ユーザー決定。正本は Vault「敵の湧き方」の【決定 11-17】)。
 *
 * <p>プレイヤーのまわり {@code (2R+1)²} チャンク(R = {@link #R}、41 × 41)の台帳({@link PhaseData#isOvergrownKey})だけを読み、
 * その窓の中で各チャンクから森の境目までの距離(チャンク。8 方向でつながった歩数)を広げて数える。外 = まだ汚れていない土地(+)、内 = 過繁茂(−)。
 * 台帳の中の小さな穴(2 チャンク幅まで。{@link EnemySpawns#outside})は内とみなす。
 * <ul>
 *   <li>G1 の名簿: 外へ 1〜{@code g1Band} チャンク。森に近いほど重い(重み = ((g1Band + 1 − d) / g1Band)²【仮】。16 チャンク先でほぼ 0)。</li>
 *   <li>G2・G3 の名簿: 境目の内側 1〜{@code g23Band}・外側 1〜{@code g23Band} チャンク。重みは同じ【仮】。</li>
 *   <li>どちらも、プレイヤーから湧かせてよい距離(チャンクの真ん中が minBlocks〜maxBlocks + 8)の中だけ。</li>
 * </ul>
 * 世界全体の距離の地図は持たない。作り直しはプレイヤーがチャンクをまたいだときか、{@code rebuildTicks} おきだけ。毎回の試しは名簿から重みで引くだけ。
 */
public final class BandRoster {

    /** 窓の半径(チャンク)。 */
    public static final int R = 20;
    private static final int W = 2 * R + 1;

    /** 名簿の真ん中のチャンクと、作った時刻。 */
    public final int cx, cz;
    public final long builtAt;
    /** 名簿(チャンクの鍵)と重みの累積。 */
    final long[] g1Keys, g23Keys;
    final double[] g1Cum, g23Cum;
    /** 確かめ用: 窓の中の各チャンクの境目までの距離(外 +・内 −・境目が窓に無ければ 0)。 */
    final int[] dist;

    private BandRoster(int cx, int cz, long builtAt, long[] g1Keys, double[] g1Cum, long[] g23Keys, double[] g23Cum, int[] dist) {
        this.cx = cx;
        this.cz = cz;
        this.builtAt = builtAt;
        this.g1Keys = g1Keys;
        this.g1Cum = g1Cum;
        this.g23Keys = g23Keys;
        this.g23Cum = g23Cum;
        this.dist = dist;
    }

    /** 確かめ用: 作り直しにかけた時間の合計(ns)と回数。 */
    public static volatile long buildNanos, builds;

    public int g1Size() {
        return g1Keys.length;
    }

    public int g23Size() {
        return g23Keys.length;
    }

    /** 窓の中のチャンク (x, z) の、境目までの距離(外 +・内 −・分からなければ 0)。窓の外は 0。 */
    public int distanceAt(int x, int z) {
        int i = x - cx + R, j = z - cz + R;
        if (i < 0 || j < 0 || i >= W || j >= W) return 0;
        return dist[i * W + j];
    }

    /** G1 の重み(外 d チャンク)。純粋な計算。 */
    public static double g1Weight(int d, int band) {
        if (d < 1 || d > band) return 0.0D;
        double k = (band + 1.0D - d) / band;
        return k * k;
    }

    /**
     * 名簿を作る。px, pz はプレイヤーの位置(ブロック)。
     */
    public static BandRoster build(PhaseData data, double px, double pz, long now, int g1Band, int g23Band, double minBlocks, double maxBlocks) {
        long t0 = System.nanoTime();
        int pcx = (int) Math.floor(px) >> 4, pcz = (int) Math.floor(pz) >> 4;
        boolean[] in = new boolean[W * W];
        for (int i = 0; i < W; i++) {
            for (int j = 0; j < W; j++) {
                int x = pcx - R + i, z = pcz - R + j;
                in[i * W + j] = data.isOvergrownKey(ChunkPos.asLong(x, z)) || !EnemySpawns.outside(data, x, z);
            }
        }
        int[] dist = new int[W * W];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        // 境目に接するチャンク(8 方向に反対側がある)を 1 として、同じ側へ広げる
        for (int i = 0; i < W; i++) {
            for (int j = 0; j < W; j++) {
                int idx = i * W + j;
                boolean self = in[idx];
                outer:
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        if (di == 0 && dj == 0) continue;
                        int a = i + di, b = j + dj;
                        if (a < 0 || b < 0 || a >= W || b >= W) continue;
                        if (in[a * W + b] != self) {
                            dist[idx] = self ? -1 : 1;
                            q.add(idx);
                            break outer;
                        }
                    }
                }
            }
        }
        while (!q.isEmpty()) {
            int idx = q.poll();
            int i = idx / W, j = idx % W;
            int d = dist[idx];
            int next = d > 0 ? d + 1 : d - 1;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    int a = i + di, b = j + dj;
                    if (a < 0 || b < 0 || a >= W || b >= W) continue;
                    int n = a * W + b;
                    if (dist[n] != 0 || in[n] != in[idx]) continue;
                    dist[n] = next;
                    q.add(n);
                }
            }
        }
        // 名簿
        long[] g1 = new long[W * W], g23 = new long[W * W];
        double[] g1c = new double[W * W], g23c = new double[W * W];
        int n1 = 0, n45 = 0;
        double s1 = 0.0D, s45 = 0.0D;
        double lo = minBlocks, hi = maxBlocks + 8.0D;
        for (int i = 0; i < W; i++) {
            for (int j = 0; j < W; j++) {
                int d = dist[i * W + j];
                if (d == 0) continue;
                int x = pcx - R + i, z = pcz - R + j;
                double bx = x * 16 + 8 - px, bz = z * 16 + 8 - pz;
                double far = Math.sqrt(bx * bx + bz * bz);
                if (far < lo || far > hi) continue;
                long key = ChunkPos.asLong(x, z);
                if (d > 0) {
                    double w = g1Weight(d, g1Band);
                    if (w > 0.0D) {
                        s1 += w;
                        g1[n1] = key;
                        g1c[n1++] = s1;
                    }
                }
                if (Math.abs(d) <= g23Band) {
                    s45 += 1.0D;
                    g23[n45] = key;
                    g23c[n45++] = s45;
                }
            }
        }
        BandRoster r = new BandRoster(pcx, pcz, now, Arrays.copyOf(g1, n1), Arrays.copyOf(g1c, n1), Arrays.copyOf(g23, n45), Arrays.copyOf(g23c, n45), dist);
        buildNanos += System.nanoTime() - t0;
        builds++;
        return r;
    }

    /** G1(group 1)か G2・G3(それ以外)の名簿から、重みで 1 チャンク引く。名簿が空なら Long.MIN_VALUE。 */
    public long draw(int group, RandomSource random) {
        long[] keys = group == 1 ? g1Keys : g23Keys;
        double[] cum = group == 1 ? g1Cum : g23Cum;
        if (keys.length == 0) return Long.MIN_VALUE;
        double r = random.nextDouble() * cum[cum.length - 1];
        int lo = 0, hi = cum.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] < r) lo = mid + 1;
            else hi = mid;
        }
        return keys[lo];
    }
}
