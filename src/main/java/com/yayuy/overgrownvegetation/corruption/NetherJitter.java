package com.yayuy.overgrownvegetation.corruption;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.world.level.ChunkPos;

/**
 * <b>湖と地面の境目のまだら</b>(2026-11-11 ユーザー決定「胞子の後で」)。段階を当てはめるとき、列 (x, z) を<b>どのチャンクの段階で変えるか</b>
 * (持ち主)を、座標をなめらかな揺らぎで ± {@code amp} ブロックずらした所のチャンクで決める。湖・殻・搾られた岩・泥炭の境目が
 * チャンクの四角い線にならず、波打つ。
 *
 * <p>揺らぎは {@code scale} ブロックおきの格子に乱数を置いて滑らかに補間した値(1 ブロックごとの乱数だと点々のごま塩になる)。
 * どの列も持ち主はちょうど 1 つなので、各段階はどの列にも 1 回だけ当てはまる。{@code amp} は 16 未満(周り 8 チャンクの中に収まる)。
 */
public final class NetherJitter {

    private NetherJitter() {
    }

    /** 列 (x, z) の持ち主のチャンク(ChunkPos の long)。{@code amp} = 0 なら列のあるチャンク。 */
    public static long owner(long seed, int x, int z, int amp, int scale) {
        if (amp <= 0) return ChunkPos.asLong(x >> 4, z >> 4);
        int ox = (int) Math.round(amp * noise(seed, x, z, scale, 0x51A7));
        int oz = (int) Math.round(amp * noise(seed, x, z, scale, 0x2E9B));
        return ChunkPos.asLong((x + ox) >> 4, (z + oz) >> 4);
    }

    /** そのチャンクが持ち主の列(x, z を交互に詰めた配列)。チャンクの 16×16 と、まわり {@code amp} ブロックの帯から選ぶ。 */
    public static int[] ownedColumns(long seed, ChunkPos pos, int amp, int scale) {
        long me = pos.toLong();
        IntArrayList out = new IntArrayList(600);
        int m = Math.max(0, Math.min(15, amp));
        for (int z = pos.getMinBlockZ() - m; z <= pos.getMaxBlockZ() + m; z++) {
            for (int x = pos.getMinBlockX() - m; x <= pos.getMaxBlockX() + m; x++) {
                if (owner(seed, x, z, m, scale) == me) {
                    out.add(x);
                    out.add(z);
                }
            }
        }
        return out.toIntArray();
    }

    /**
     * 覆いのまだら(2026-11-11): そのマスが「割合 {@code fraction} の側」に入るか。1 ブロックごとの乱数だと 1 マスずつの
     * ごま塩(モザイク)になったので、粒 {@code scale} ブロックのなめらかな揺らぎ + 少しの乱数で、かたまりの斑にする。
     * 割合は揺らぎの分布の分位点で合わせる(全体でほぼ {@code fraction})。
     */
    public static boolean patch(long seed, int x, int y, int z, double fraction, int scale, int salt) {
        if (fraction <= 0) return false;
        if (fraction >= 1) return true;
        double v = 0.8 * noise(seed, x + y * 7, z - y * 5, scale, salt) + 0.2 * (NetherGround.hash01(seed, x, y, z, salt) * 2 - 1);
        return v < quantile(fraction);
    }

    private static double[] QUANTILES;

    /** 揺らぎ(0.8 × なめらか + 0.2 × 乱数)の分布の分位点(起動後 1 回、決まった種で見本を取る)。 */
    private static double quantile(double f) {
        double[] q = QUANTILES;
        if (q == null) {
            double[] s = new double[20000];
            for (int i = 0; i < s.length; i++) {
                int x = i % 200 * 3, z = i / 200 * 3;
                s[i] = 0.8 * noise(12345L, x, z, 5, 77) + 0.2 * (NetherGround.hash01(12345L, x, 0, z, 77) * 2 - 1);
            }
            java.util.Arrays.sort(s);
            QUANTILES = q = s;
        }
        int i = (int) Math.max(0, Math.min(q.length - 1, Math.round(f * (q.length - 1))));
        return q[i];
    }

    /** なめらかな値の揺らぎ(−1〜1)。 */
    static double noise(long seed, int x, int z, int scale, int salt) {
        double fx = x / (double) scale, fz = z / (double) scale;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = fx - ix, tz = fz - iz;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = lattice(seed, ix, iz, salt), b = lattice(seed, ix + 1, iz, salt);
        double c = lattice(seed, ix, iz + 1, salt), d = lattice(seed, ix + 1, iz + 1, salt);
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * tz;
    }

    private static double lattice(long seed, int ix, int iz, int salt) {
        return NetherGround.hash01(seed, ix, salt, iz, 41) * 2 - 1;
    }
}
