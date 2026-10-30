package com.yayuy.overgrownvegetation.base.plan;

/**
 * 基地の浄化圏 1 つ(2026-10-02)。基地は、真ん中に除去塔を立てた八角形の浄化圏をつないだ形で、レベルを上げるたびに
 * 今の基地の縁へ浄化圏を継ぎ足して育つ(ユーザー指定 2026-10-02「案 E(浄化圏がつながる)を元に」)。
 * 浄化圏ごとに区割りは 1 つ。広さは除去装置(半径 3 チャンク)の届く範囲のうち、濃く効く真ん中のあたり(仮)。
 *
 * <p>形は |x − cx| ≤ rx、|z − cz| ≤ rz、|x − cx| + |z − cz| ≤ {@link #cut()} の八角形(角を 45 度に落とした長方形。
 * rx = rz なら正八角形に近い)。
 *
 * @param zone   区割り
 * @param level  このレベルから建つ(中心 = 1)
 * @param cx     中心の x(基地の局所座標)
 * @param cz     中心の z
 * @param rx     中心から東西の辺まで
 * @param rz     中心から南北の辺まで
 * @param parent 継ぎ足した先の浄化圏の番号(中心は −1)
 * @param box    角を少しだけ落とした四角(工廠基地の格子、2026-10-02)。偽なら八角形
 */
public record Lobe(Zone zone, int level, int cx, int cz, int rx, int rz, int parent, boolean box) {

    /** 四角の角を落とす幅(見張り台の立つ角を作る)。6 だと格子の斜め隣どうしの角が 2 マス離れて「重ならないのに近すぎる」で置けなかったので 4。 */
    public static final int BOX_BEVEL = 4;

    /** 八角形の浄化圏。 */
    public Lobe(Zone zone, int level, int cx, int cz, int rx, int rz, int parent) {
        this(zone, level, cx, cz, rx, rz, parent, false);
    }

    /** 斜めの辺の位置(|dx| + |dz| の上限)。 */
    public int cut() {
        return box ? rx + rz - BOX_BEVEL : (int) Math.round(0.71 * (rx + rz));
    }

    public boolean contains(int x, int z) {
        int dx = Math.abs(x - cx);
        int dz = Math.abs(z - cz);
        return dx <= rx && dz <= rz && dx + dz <= cut();
    }

    public int x0() {
        return cx - rx;
    }

    public int z0() {
        return cz - rz;
    }

    public int x1() {
        return cx + rx;
    }

    public int z1() {
        return cz + rz;
    }

    /** 東西に長いか(通りを東西に通す)。 */
    public boolean alongX() {
        return rx >= rz;
    }

    /** 面積(マスの数)。 */
    public int area() {
        int n = 0;
        for (int z = z0(); z <= z1(); z++) {
            for (int x = x0(); x <= x1(); x++) {
                if (contains(x, z)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 八角形の 8 つの角(北の辺の西端から時計回り)。見張り台の候補。 */
    public int[][] vertices() {
        int a = cut() - rz;
        int b = cut() - rx;
        return new int[][]{{cx - a, cz - rz}, {cx + a, cz - rz}, {cx + rx, cz - b}, {cx + rx, cz + b},
                {cx + a, cz + rz}, {cx - a, cz + rz}, {cx - rx, cz + b}, {cx - rx, cz - b}};
    }

    /**
     * 2 つの浄化圏のあいだ: 重なる(正)/ 離れている(負、離れたマスの数のおおよそ)。辺の向きが 4 つ(x・z・x+z・x−z)だけの
     * 凸の形どうしなので、その 4 つの軸で影が離れているかで分かる。斜めの軸は 1 マス = 2 で数え、マスの数にそろえる。
     */
    public int separation(Lobe o) {
        int sx = gap(x0(), x1(), o.x0(), o.x1());
        int sz = gap(z0(), z1(), o.z0(), o.z1());
        int su = gap(cx + cz - cut(), cx + cz + cut(), o.cx + o.cz - o.cut(), o.cx + o.cz + o.cut());
        int sv = gap(cx - cz - cut(), cx - cz + cut(), o.cx - o.cz - o.cut(), o.cx - o.cz + o.cut());
        int apart = Math.max(Math.max(sx, sz), Math.max((su + 1) / 2, (sv + 1) / 2));
        return apart > 0 ? -apart : 1;
    }

    /** 区間 [a0, a1] と [b0, b1] のあいだの空き(重なれば 0 以下)。 */
    private static int gap(int a0, int a1, int b0, int b1) {
        return Math.max(b0 - a1, a0 - b1) - 1;
    }
}
