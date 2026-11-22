package com.yayuy.overgrownvegetation.corruption;

/**
 * 汚染で生える木の間隔(2026-10-15 ユーザー指定「木の密度を下げる」)。
 *
 * <p>前は汚染した上面ごとに約 10%({@code growthChance × treeChance})で苗木を植え、間隔の決まりが無かったので、
 * 木が 3 ブロックおきほどに立ち、葉が何層も重なって林床が暗かった(その下で下草が茂っているのもおかしい)。
 *
 * <p>今は世界を {@code spacing} ブロックの格子に分け、<b>1 マスに木を 1 本まで</b>にする。木が立てるのはマスの中の
 * 決まった 1 列(マスの縁から {@code spacing / 4} 内側の範囲で、ワールドのシードとマスの座標から決める)だけで、
 * マスの {@code clearing} の割合は木の無い空き地にする。隣のマスの木とは少なくとも {@code 2 × (spacing / 4)} 離れる。
 *
 * <p>座標とシードだけで決まる(周りのブロックを見ない)ので軽く、チャンクの境・台帳の書き換え・後から読み込んだ所でも
 * 同じ答えになる。その列が水や石なら木は生えない(そのまま空き地になる)。
 */
public final class TreeSpacing {

    /** (x, z) の列に木を植えてよいか。{@code spacing} が 1 以下なら常に true(前の挙動)。 */
    public static boolean allowsTree(long seed, int x, int z, int spacing, double clearing) {
        if (spacing <= 1) {
            return true;
        }
        int cellX = Math.floorDiv(x, spacing);
        int cellZ = Math.floorDiv(z, spacing);
        long h = mix(seed ^ (cellX * 0x9E3779B97F4A7C15L) ^ (cellZ * 0xC2B2AE3D27D4EB4FL));
        if (unit(h) < clearing) {
            return false;
        }
        int margin = spacing / 4;
        int span = Math.max(1, spacing - 2 * margin);
        h = mix(h);
        int ax = cellX * spacing + margin + (int) Math.floorMod(h, (long) span);
        h = mix(h);
        int az = cellZ * spacing + margin + (int) Math.floorMod(h, (long) span);
        return x == ax && z == az;
    }

    /** 林冠の穴の格子の大きさ(ブロック)と、木を植えない輪の半径(ブロック)。仮決め。 */
    public static final int GAP_GRID = 36;
    public static final double GAP_RADIUS_MIN = 8.0;
    public static final double GAP_RADIUS_MAX = 11.0;
    private static final long GAP_SALT = 0x5A1F6C3D9B2E4071L;

    /**
     * <b>林冠の穴</b>(2026-10-16、ユーザー選択「濃い森の光 = 森に穴を残す + 葉を樹冠の数で数える」の前者)。
     * (x, z) が「木を植えない輪」の中か。世界を {@link #GAP_GRID} ブロックの格子に分け、マスごとに {@code chance} の確率で
     * 半径 {@link #GAP_RADIUS_MIN}〜{@link #GAP_RADIUS_MAX} の輪を 1 つ置く(中心はマスの縁から半径の最大だけ内側なので、
     * 輪は自分のマスからはみ出さず、1 マスだけ見ればよい)。木の枝と葉は幹から 6〜7 ブロック広がるので、{@link #allowsTree} の空き地
     * (格子 1 マス)では上がふさがって林冠に穴が開かなかった。輪の中に幹が無ければ、輪の真ん中の上に半径 1〜4 ブロックほどの穴が残り、
     * 森を濃くしても光の筋が地面まで差し込む(低い朝夕の光は斜めに入って別の樹冠に当たるので、主に昼の筋)。
     * 座標とシードだけで決まる(チャンクの境・後から読み込んだ所でも同じ)。既に生えた木は減らない。
     */
    public static boolean inCanopyGap(long seed, int x, int z, double chance) {
        double[] gap = canopyGap(seed, Math.floorDiv(x, GAP_GRID), Math.floorDiv(z, GAP_GRID), chance);
        if (gap == null) {
            return false;
        }
        double dx = x + 0.5 - gap[0];
        double dz = z + 0.5 - gap[1];
        return dx * dx + dz * dz < gap[2] * gap[2];
    }

    /** 格子のマス (cellX, cellZ) の林冠の穴 {中心 x, 中心 z, 半径}。無ければ null。 */
    public static double[] canopyGap(long seed, int cellX, int cellZ, double chance) {
        if (chance <= 0.0) {
            return null;
        }
        long h = mix(seed ^ GAP_SALT ^ (cellX * 0x9E3779B97F4A7C15L) ^ (cellZ * 0xC2B2AE3D27D4EB4FL));
        if (unit(h) >= chance) {
            return null;
        }
        double span = GAP_GRID - 2.0 * GAP_RADIUS_MAX;
        h = mix(h);
        double cx = cellX * (double) GAP_GRID + GAP_RADIUS_MAX + unit(h) * span;
        h = mix(h);
        double cz = cellZ * (double) GAP_GRID + GAP_RADIUS_MAX + unit(h) * span;
        h = mix(h);
        double r = GAP_RADIUS_MIN + unit(h) * (GAP_RADIUS_MAX - GAP_RADIUS_MIN);
        return new double[]{cx, cz, r};
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private TreeSpacing() {
    }
}
