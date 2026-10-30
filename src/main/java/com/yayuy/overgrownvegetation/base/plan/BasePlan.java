package com.yayuy.overgrownvegetation.base.plan;

import java.util.List;

/**
 * 基地 1 つの配置計画(2026-10-01、10-02 に浄化圏をつなぐ形へ作り直し)。{@link BasePlanner#plan} が作る。
 * 地形の高さはまだ入っていない(水平の割り付けだけ)。
 *
 * <p>座標は基地の中心が原点の局所座標(x = 東、z = 南。正門は中心の浄化圏の南の辺の真ん中)。基地は八角形の浄化圏
 * ({@link Lobe})をつないだ形で、その外周 {@link #BAND} マス(浄化圏の和の外に面したマス)が外周の帯になる:
 * レベル 1〜3 は外側の列に金網 3 段 + 天辺に有刺鉄線、レベル 4〜5 は 2 マス厚の鉄筋コンクリートの壁(ユーザー指定)。
 * 外周の外 {@link #WIRE_OFFSET} マスに鉄条網の輪、{@link #CLEAR_MARGIN} マスまで草木を払う。
 *
 * <p>マスごとの役割は作るときに 1 回だけ地図にする({@link #inDist}・{@link #outDist}・{@link #plotAt}・{@link #surfaceAt})。
 * 距離はチェビシェフ(斜めも 1)—— 角で外周が切れない。斜めの辺の外側の列は階段状(4 方向につながる)になる。
 *
 * <p>建物はレベルごとに浄化圏ごと足していき、レベル L の計画はレベル L−1 の浄化圏と建物を全部含む
 * —— <b>レベルを上げても内側の建物は動かない</b>(上げたときは役割の変わったマスと新しい建物だけ置き直す)。
 * 古い外周の帯と巡回路は残さず、ふつうの中のマスになる(ユーザー指定 2026-10-02「古い外周は残さない」)。
 */
public final class BasePlan {

    public static final int MAX_LEVEL = 5;
    /** このレベルから外周が壁になる(それより下は金網と有刺鉄線。ユーザー指定 2026-10-01)。 */
    public static final int WALL_LEVEL = 4;
    /** 壁の高さ(地面から。上 1 段は胸壁の塀)。 */
    public static final int WALL_HEIGHT = 6;
    /** 金網の高さ(この上に有刺鉄線)。 */
    public static final int FENCE_HEIGHT = 3;
    /** 外周の帯の幅(外側の列 + 内の列)。 */
    public static final int BAND = 2;
    /**
     * 内側の柵の列(外までの距離)。巡回路(3..5、踏み固めた土)と中の砂利の境目の、砂利の側の 1 列
     * (ユーザー指定 2026-10-02「砂利と土の境目に金網フェンスの壁」。高さ・全レベルに立てるのは仮)。
     */
    public static final int INNER_FENCE = 6;
    /** 鉄条網の輪の、外周の外側の列からの距離。 */
    public static final int WIRE_OFFSET = 5;
    /** 外周の外の、木を払い地形をなじませる幅。 */
    public static final int CLEAR_MARGIN = 10;
    /** 浄化圏の縁が届く限界(中心から)。世界生成の構造物は始まりのチャンクから ±8 チャンクまでしか置けない。 */
    public static final int MAX_REACH = 108;
    /** 役割の地図の半幅。 */
    public static final int GRID = MAX_REACH + CLEAR_MARGIN + 8;
    static final int SIZE = 2 * GRID + 1;
    private static final int FAR = 127;

    public final long seed;
    public final int level;
    /** 基地の種類(広げ方が変わる)。 */
    public final BaseType type;
    public final List<Lobe> lobes;
    public final List<Plot> plots;
    /** 中: 外までの距離(外側の列 = 1)、外: −(中までの距離)。 */
    private final byte[] dist;
    /** 足元にかかる建物の番号 + 1(0 = 無し)。 */
    private final short[] plotIndex;
    /** 地面の仕上げ({@link Surface} の番号)。 */
    private final byte[] surface;
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;
    private final int area;

    BasePlan(long seed, int level, BaseType type, List<Lobe> lobes, List<Plot> plots, byte[] surface, byte[] dist) {
        this.seed = seed;
        this.level = level;
        this.type = type;
        this.lobes = List.copyOf(lobes);
        this.plots = List.copyOf(plots);
        this.dist = dist;
        this.surface = surface;
        this.plotIndex = new short[SIZE * SIZE];
        for (int n = 0; n < this.plots.size(); n++) {
            Plot p = this.plots.get(n);
            for (int z = Math.max(p.z0(), -GRID); z <= Math.min(p.z1(), GRID); z++) {
                for (int x = Math.max(p.x0(), -GRID); x <= Math.min(p.x1(), GRID); x++) {
                    plotIndex[idx(x, z)] = (short) (n + 1);
                }
            }
        }
        // 手を入れる範囲: 浄化圏の和 + 払う幅、建物の法面、地面の塗り(正門の外の道)
        int x0 = Integer.MAX_VALUE;
        int z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int z1 = Integer.MIN_VALUE;
        for (Lobe l : this.lobes) {
            x0 = Math.min(x0, l.x0() - CLEAR_MARGIN - 1);
            z0 = Math.min(z0, l.z0() - CLEAR_MARGIN - 1);
            x1 = Math.max(x1, l.x1() + CLEAR_MARGIN + 1);
            z1 = Math.max(z1, l.z1() + CLEAR_MARGIN + 1);
        }
        for (Plot p : this.plots) {
            x0 = Math.min(x0, p.x0() - BaseTerrain.SLOPE_REACH);
            z0 = Math.min(z0, p.z0() - BaseTerrain.SLOPE_REACH);
            x1 = Math.max(x1, p.x1() + BaseTerrain.SLOPE_REACH);
            z1 = Math.max(z1, p.z1() + BaseTerrain.SLOPE_REACH);
        }
        for (int z = -GRID; z <= GRID; z++) {
            for (int x = -GRID; x <= GRID; x++) {
                if (surface[idx(x, z)] != 0) {
                    x0 = Math.min(x0, x);
                    z0 = Math.min(z0, z);
                    x1 = Math.max(x1, x);
                    z1 = Math.max(z1, z);
                }
            }
        }
        this.minX = Math.max(x0, -GRID);
        this.minZ = Math.max(z0, -GRID);
        this.maxX = Math.min(x1, GRID);
        this.maxZ = Math.min(z1, GRID);
        int n = 0;
        for (byte b : dist) {
            if (b > 0) {
                n++;
            }
        }
        this.area = n;
    }

    // ---------- 浄化圏の和の地図 ----------

    /** 浄化圏の和の地図(中 = true)。 */
    static boolean[] mask(List<Lobe> lobes) {
        boolean[] mask = new boolean[SIZE * SIZE];
        for (Lobe l : lobes) {
            paint(mask, l);
        }
        return mask;
    }

    /** 地図に浄化圏 1 つを足す。 */
    static void paint(boolean[] mask, Lobe l) {
        for (int z = Math.max(l.z0(), -GRID); z <= Math.min(l.z1(), GRID); z++) {
            for (int x = Math.max(l.x0(), -GRID); x <= Math.min(l.x1(), GRID); x++) {
                if (l.contains(x, z)) {
                    mask[idx(x, z)] = true;
                }
            }
        }
    }

    /** 距離の地図(中 = 外までのチェビシェフ距離、外 = −中までの距離。どちらも {@value #FAR} で頭打ち)。 */
    static byte[] distanceField(boolean[] mask) {
        final int inf = 1 << 20;
        int[] in = new int[mask.length];
        int[] out = new int[mask.length];
        for (int i = 0; i < mask.length; i++) {
            in[i] = mask[i] ? inf : 0;
            out[i] = mask[i] ? 0 : inf;
        }
        chebyshev(in, 0);
        chebyshev(out, inf);
        byte[] r = new byte[mask.length];
        for (int i = 0; i < mask.length; i++) {
            r[i] = mask[i] ? (byte) Math.min(in[i], FAR) : (byte) -Math.min(out[i], FAR);
        }
        return r;
    }

    /** 2 回なめるチェビシェフ距離。地図の外のマスは {@code beyond}。 */
    private static void chebyshev(int[] d, int beyond) {
        int n = SIZE;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int i = z * n + x;
                if (d[i] == 0) {
                    continue;
                }
                int v = d[i];
                v = Math.min(v, at(d, x - 1, z, beyond) + 1);
                v = Math.min(v, at(d, x - 1, z - 1, beyond) + 1);
                v = Math.min(v, at(d, x, z - 1, beyond) + 1);
                v = Math.min(v, at(d, x + 1, z - 1, beyond) + 1);
                d[i] = v;
            }
        }
        for (int z = n - 1; z >= 0; z--) {
            for (int x = n - 1; x >= 0; x--) {
                int i = z * n + x;
                if (d[i] == 0) {
                    continue;
                }
                int v = d[i];
                v = Math.min(v, at(d, x + 1, z, beyond) + 1);
                v = Math.min(v, at(d, x + 1, z + 1, beyond) + 1);
                v = Math.min(v, at(d, x, z + 1, beyond) + 1);
                v = Math.min(v, at(d, x - 1, z + 1, beyond) + 1);
                d[i] = v;
            }
        }
    }

    private static int at(int[] d, int x, int z, int beyond) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
            return beyond;
        }
        return d[z * SIZE + x];
    }

    static int idx(int x, int z) {
        return (z + GRID) * SIZE + (x + GRID);
    }

    static boolean onGrid(int x, int z) {
        return x >= -GRID && x <= GRID && z >= -GRID && z <= GRID;
    }

    /** 距離の地図の (x, z) の値(地図の外は外)。 */
    static int fieldAt(byte[] field, int x, int z) {
        return onGrid(x, z) ? field[idx(x, z)] : -FAR;
    }

    // ---------- マスの役割 ----------

    /** 外周が壁か(レベル 4〜5)。偽なら金網と有刺鉄線。 */
    public boolean wallMode() {
        return level >= WALL_LEVEL;
    }

    /** 浄化圏の和の中なら、外までの距離(外側の列 = 1)。外なら 0。 */
    public int inDist(int x, int z) {
        return onGrid(x, z) ? Math.max(0, dist[idx(x, z)]) : 0;
    }

    /** 浄化圏の和の外なら、中までの距離(外側の列のすぐ外 = 1)。中なら 0。 */
    public int outDist(int x, int z) {
        if (!onGrid(x, z)) {
            return FAR;
        }
        byte b = dist[idx(x, z)];
        return b < 0 ? -b : 0;
    }

    /** 浄化圏の和の中(外周の帯を含む)か。 */
    public boolean inside(int x, int z) {
        return inDist(x, z) > 0;
    }

    /** (x, z) に足元がかかる建物。無ければ null。 */
    public Plot plotAt(int x, int z) {
        if (!onGrid(x, z)) {
            return null;
        }
        short p = plotIndex[idx(x, z)];
        return p == 0 ? null : plots.get(p - 1);
    }

    /** 外周の帯(外側の列とその内の列。塔・門の足元を除く)か。 */
    public boolean isPerimeter(int x, int z) {
        int d = inDist(x, z);
        if (d < 1 || d > BAND) {
            return false;
        }
        Plot p = plotAt(x, z);
        return p == null || p.kind() != BuildingKind.WATCHTOWER && p.kind() != BuildingKind.GATEHOUSE;
    }

    /** 外周の外側の列(金網・胸壁の塀が載る)か。 */
    public boolean isOuterRow(int x, int z) {
        return inDist(x, z) == 1;
    }

    /**
     * 金網の支柱を立てるマスか。まっすぐな辺は 6 マスおき(東西の辺は x、南北の辺は z で数える)、斜めの辺の階段も
     * 階段に沿って 6 マスおき(北東〜南西の向きは x − z、北西〜南東の向きは x + z で数える)、枝分かれするマスは必ず。
     * 階段のマスをどれも支柱にすると、斜めの辺が塀の柱の列になる。
     */
    public boolean isFencePost(int x, int z) {
        if (!isOuterRow(x, z)) {
            return false;
        }
        boolean w = isOuterRow(x - 1, z);
        boolean e = isOuterRow(x + 1, z);
        boolean n = isOuterRow(x, z - 1);
        boolean s = isOuterRow(x, z + 1);
        int horizontal = (w ? 1 : 0) + (e ? 1 : 0);
        int vertical = (n ? 1 : 0) + (s ? 1 : 0);
        if (horizontal + vertical >= 3 || horizontal + vertical == 0) {
            return true;
        }
        if (vertical == 0) {
            return Math.floorMod(x, 6) == 0;
        }
        if (horizontal == 0) {
            return Math.floorMod(z, 6) == 0;
        }
        // 階段の段: 東と南(か西と北)につながる段は北東〜南西に並ぶ(x + z がほぼ一定)
        int dx = e ? 1 : -1;
        int dz = s ? 1 : -1;
        return dx * dz > 0 ? Math.floorMod(x - z, 6) == 0 : Math.floorMod(x + z, 6) == 0;
    }

    /** 鉄条網の輪の上か(道・建物の足元は空ける)。 */
    public boolean isWire(int x, int z) {
        return outDist(x, z) == WIRE_OFFSET && plotAt(x, z) == null && surfaceAt(x, z) == Surface.NATURAL;
    }

    /** 草木を払う範囲(浄化圏の和の中と、外の {@link #CLEAR_MARGIN} マス)か。 */
    public boolean isClear(int x, int z) {
        return inside(x, z) || outDist(x, z) <= CLEAR_MARGIN;
    }

    /** (x, z) の地面の仕上げ(重なりは {@link Surface} の順が後のものが勝つ)。どれにも入らなければ {@link Surface#NATURAL}。 */
    public Surface surfaceAt(int x, int z) {
        if (!onGrid(x, z)) {
            return Surface.NATURAL;
        }
        return Surface.values()[surface[idx(x, z)]];
    }

    // ---------- 大きさ ----------

    /** 手を入れる範囲(端を含む)。この外のマスには何も置かない。 */
    public int minX() {
        return minX;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxZ() {
        return maxZ;
    }

    /** 浄化圏の和の面積(外周の帯を含む)。 */
    public int area() {
        return area;
    }

    /** 浄化圏の和の外接長方形 {x0, z0, x1, z1}。 */
    public int[] bounds() {
        int x0 = Integer.MAX_VALUE;
        int z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int z1 = Integer.MIN_VALUE;
        for (Lobe l : lobes) {
            x0 = Math.min(x0, l.x0());
            z0 = Math.min(z0, l.z0());
            x1 = Math.max(x1, l.x1());
            z1 = Math.max(z1, l.z1());
        }
        return new int[]{x0, z0, x1, z1};
    }
}
