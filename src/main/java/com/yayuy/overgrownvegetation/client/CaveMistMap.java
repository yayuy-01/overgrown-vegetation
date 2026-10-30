package com.yayuy.overgrownvegetation.client;

import java.util.Arrays;

/**
 * 地下の霧({@link CaveMist})の床と霧のてっぺんの地図。Minecraft のクラスを使わない(ゲームを起動せずに素の Java で検算できるように)。
 * ブロックは {@link World} から読む。
 *
 * <p><b>どこに立って見ても同じ形になる</b>ように作る(2026-10-16 ユーザー指摘「坂のようになっている場所などで、見る立ち位置によって霧ががくがくする」)。
 * 前の作りは床の探し方がカメラの高さで変わり(目より 2 ブロック以上下に天井がある列は「別の洞窟」として捨てていた)、坂の上に立つと下の霧が
 * 丸ごと消え、3 ブロック上り下りするたびに作り直して霧の形が一気に変わっていた。今は:
 * <ul>
 *     <li>床を探す高さの範囲は、カメラの高さを {@link #BASE_STEP} ブロックごとに丸めた所から決める(少し動いても範囲が変わらない)。</li>
 *     <li>列ごとに空き(上に {@link #MIN_HEADROOM} 段以上・岩の天井の下)を {@link #MAX_RUNS} 個まで覚え、どれがカメラのいる洞窟かは
 *     <b>隣の列と空きが 2 ブロック以上重なっているか</b>でカメラの列からたどって決める。坂・段差・崖の下まで同じ洞窟としてつながり、
 *     下にある別の洞窟・上の地上はつながらない。どこから始めても同じ洞窟なら同じ床になる。</li>
 *     <li>霧のてっぺん(窓の床の真ん中の高さ + {@link #DEPTH})を 3×3 でならす(段々畑のような段を消す)。まわり 3×3 の床の一番高い所({@link #floorHi})も出し、
 *     板はそれより上だけに描く(坂の段差に板が刺さって、見る角度で階段のように欠けるのを防ぐ)。</li>
 * </ul>
 * 数値は全部 Claude の仮決め。
 */
final class CaveMistMap {

    /** ブロックの読み口。 */
    interface World {
        int OPEN = 0;
        int SOLID = 1;
        int FLUID = 2;
        int TREE = 3;

        /** そのマスの種類: 空き(空気・草・ツタ・苔など霧が通る物)/ 固い / 水・溶岩の面 / 木の一部(幹・葉。固い)。 */
        int kind(int x, int y, int z);

        /** 列が読み込まれているか。 */
        boolean loaded(int x, int z);

        int blockLight(int x, int y, int z);

        int skyLight(int x, int y, int z);

        /** 列のいちばん上の空気でないブロックの 1 つ上の高さ(高さ地図 WORLD_SURFACE)。 */
        int surface(int x, int z);

        int minY();
    }

    /** 地図の大きさ(マス)と 1 マスの幅(ブロック)。 */
    static final int SIZE = 64;
    static final int TEXEL = 2;
    /** 床を探す範囲の基準の高さの刻みと、基準から上・下の範囲(ブロック)。 */
    static final int BASE_STEP = 8;
    static final int SCAN_UP = 24;
    static final int SCAN_DOWN = 28;
    /** 1 列に覚える空きの数・床の上に要る空きの段数・隣の列と同じ洞窟とみなす空きの重なり(ブロック)。 */
    static final int MAX_RUNS = 3;
    static final int MIN_HEADROOM = 5;
    static final float MIN_OVERLAP = 2.0F;
    /** カメラの列のまわりで、始めの空きを探す距離(マス)。 */
    static final int START_RADIUS = 3;
    /** 霧のてっぺんを決める窓(± マス)・窓の床の高さの低い方からの割合(0.5 = 真ん中)・窓の中で床のある割合の下限(細い坑道を外す)。 */
    static final int WINDOW = 4;
    static final double FLOOR_PERCENTILE = 0.5;
    static final double MIN_OPEN = 0.35;
    /** 霧のてっぺん(窓の床の真ん中 + これ)。立っている人の目(床 + 1.62)より低く、上から見下ろす。 */
    static final double DEPTH = 2.8;
    /** 霧のてっぺんを決めるとき同じ段とみなす床の高さの差(ブロック)。これより深い穴・高い崖は霧の滝で見せる。 */
    static final float LEVEL_RANGE = 3.0F;
    /** 天井を探す高さ(空きが範囲の上まで続く列)と、天井の穴の真下かを見る距離(ブロック)。 */
    static final int ROOF_SCAN = 64;
    static final int HOLE_RIM = 3;
    /** 間の列の床を探す幅(真ん中の床から上下、ブロック)。 */
    static final int FINE_RANGE = 3;

    private static final int NONE = Integer.MIN_VALUE;

    final int originX;
    final int originZ;
    /** 床を探す範囲の基準の高さ(カメラの高さを丸めた所)。 */
    final int baseY;

    /** 列ごとの空き: 床の高さ・空きの上端のブロックの高さ・床の 1 段上の光。 */
    private final float[] runFloor = new float[SIZE * SIZE * MAX_RUNS];
    private final int[] runTop = new int[SIZE * SIZE * MAX_RUNS];
    private final byte[] runBlock = new byte[SIZE * SIZE * MAX_RUNS];
    private final byte[] runSky = new byte[SIZE * SIZE * MAX_RUNS];
    private final byte[] runCount = new byte[SIZE * SIZE];

    /** カメラのいる洞窟の床(無ければ NaN)・まわり 3×3 の床の一番高い所・霧のてっぺん(無ければ NaN)・床の 1 段上の光。 */
    final float[] floor = new float[SIZE * SIZE];
    final float[] floorHi = new float[SIZE * SIZE];
    final float[] top = new float[SIZE * SIZE];
    final byte[] blockLight = new byte[SIZE * SIZE];
    final byte[] skyLight = new byte[SIZE * SIZE];
    /** マスの真ん中の列と、隣のマスとの間の 3 列(x+1・z+1・斜め)の床の一番高い所(床の無いマスは NaN)。 */
    private final float[] fineMax = new float[SIZE * SIZE];

    private int next;
    private int phase;

    CaveMistMap(int originX, int originZ, int baseY) {
        this(originX, originZ, baseY, DEPTH);
    }

    /** {@code depth} = 霧のてっぺんの、窓の床の真ん中からの高さ(設定 caveMistHeight)。 */
    CaveMistMap(int originX, int originZ, int baseY, double depth) {
        this.originX = originX;
        this.originZ = originZ;
        this.baseY = baseY;
        this.depth = depth;
    }

    /** 霧のてっぺんの、窓の床の真ん中からの高さ(ブロック)。 */
    final double depth;

    /** カメラの高さ {@code eyeY} の基準の高さ。 */
    static int baseFor(double eyeY) {
        return Math.floorDiv((int) Math.floor(eyeY), BASE_STEP) * BASE_STEP;
    }

    /** カメラの位置 {@code eyeX, eyeZ} の地図の原点(マスの幅に丸めてカメラを真ん中に)。 */
    static int originFor(double eye) {
        return Math.floorDiv((int) Math.floor(eye), TEXEL) * TEXEL - SIZE * TEXEL / 2;
    }

    /** マス {@code index} の真ん中の列の座標。 */
    int columnX(int index) {
        return originX + (index % SIZE) * TEXEL + TEXEL / 2;
    }

    int columnZ(int index) {
        return originZ + (index / SIZE) * TEXEL + TEXEL / 2;
    }

    /**
     * 少しずつ作る。{@code budgetNanos} を使い切ったら false を返し、次に呼ばれたら続きから。できあがったら true。
     * {@code eye*} はカメラの位置(どの洞窟から霧を出すかの始めの所)。
     */
    boolean step(World world, long budgetNanos, double eyeX, double eyeY, double eyeZ) {
        long start = System.nanoTime();
        if (phase == 0) {
            while (next < SIZE * SIZE) {
                int index = next++;
                int x = columnX(index);
                int z = columnZ(index);
                runCount[index] = 0;
                if (world.loaded(x, z)) {
                    scanColumn(world, index, x, z);
                }
                if (System.nanoTime() - start > budgetNanos) {
                    return false;
                }
            }
            select(eyeX, eyeY, eyeZ);
            phase = 1;
            next = 0;
        }
        if (phase == 1) {
            while (next < SIZE * SIZE) {
                int index = next++;
                fineMax[index] = fineFloor(world, index);
                if (System.nanoTime() - start > budgetNanos) {
                    return false;
                }
            }
            phase = 2;
            next = 0;
        }
        if (phase == 2) {
            double[] window = new double[(2 * WINDOW + 1) * (2 * WINDOW + 1)];
            int need = (int) Math.ceil(MIN_OPEN * window.length);
            while (next < SIZE * SIZE) {
                int index = next++;
                top[index] = windowTop(index, window, need);
                if (System.nanoTime() - start > budgetNanos) {
                    return false;
                }
            }
            smooth();
            phase = 3;
        }
        return true;
    }

    /** 列 (x, z) の空きを上から {@link #MAX_RUNS} 個まで覚える。 */
    private void scanColumn(World world, int index, int x, int z) {
        int windowTop = baseY + SCAN_UP;
        int bottom = Math.max(world.minY(), baseY - SCAN_DOWN);
        int airTop = NONE;
        int n = 0;
        for (int y = windowTop; y >= bottom && n < MAX_RUNS; y--) {
            int kind = world.kind(x, y, z);
            if (kind == World.OPEN) {
                if (airTop == NONE) {
                    airTop = y;
                }
                continue;
            }
            if (airTop == NONE) {
                continue;
            }
            int runTopY = airTop;
            airTop = NONE;
            // 岩棚・天井の上の面などで空きが足りない / 林冠の下・空の下の地面: 捨ててその下を探し続ける。
            if (runTopY - y < MIN_HEADROOM || !roofed(world, x, z, y + 1, runTopY, windowTop)) {
                continue;
            }
            int i = index * MAX_RUNS + n;
            runFloor[i] = kind == World.FLUID ? y + 0.9F : y + 1.0F;
            runTop[i] = runTopY;
            runBlock[i] = (byte) world.blockLight(x, y + 1, z);
            runSky[i] = (byte) world.skyLight(x, y + 1, z);
            n++;
        }
        runCount[index] = (byte) n;
    }

    /**
     * 床 {@code floorY}(床の 1 段上の高さ)の上の空き(上端 {@code runTopY})が岩の天井に覆われているか。林冠の下の地上の森・空の下の地面には霧を出さない。
     * 空の下でも、天井の穴・洞窟の口の真下(まわり {@link #HOLE_RIM} ブロック先の空気のマスのどれかが空の光 14 未満)は数える。岩の中のマスは見ない。
     */
    private static boolean roofed(World world, int x, int z, int floorY, int runTopY, int windowTop) {
        if (runTopY < windowTop) {
            return world.kind(x, runTopY + 1, z) != World.TREE;
        }
        int limit = Math.min(world.surface(x, z), windowTop + ROOF_SCAN);
        for (int y = windowTop + 1; y < limit; y++) {
            int kind = world.kind(x, y, z);
            if (kind == World.OPEN) {
                continue;
            }
            return kind != World.TREE;
        }
        for (int d = 0; d < 4; d++) {
            int dx = d == 0 ? HOLE_RIM : (d == 1 ? -HOLE_RIM : 0);
            int dz = d == 2 ? HOLE_RIM : (d == 3 ? -HOLE_RIM : 0);
            if (world.kind(x + dx, floorY, z + dz) == World.OPEN && world.skyLight(x + dx, floorY, z + dz) < 14) {
                return true;
            }
        }
        return false;
    }

    /**
     * カメラのいる洞窟の床を決める。隣の列の空きと 2 ブロック以上重なる空き同士を 1 つの洞窟としてまとめ(全部の列の空きをまとめるので、
     * どこから始めても同じまとまりになる)、カメラのいる空きのまとまりだけを使う。1 列に同じ洞窟の空きが 2 つ以上あるとき(岩のひさしの上と下など)は、
     * 基準の高さ({@link #baseY} + {@link #BASE_STEP} / 2 − 1.6、カメラの足元くらい)にいちばん近い床を選ぶ。
     * 前はカメラの列から幅優先でたどり、先に着いた道の空きを選んでいたので、ひさし・段の重なる所では始める場所で選ぶ床が変わり、
     * 作り直すたびに霧の形が変わった(10-16 ユーザー指摘「崖から流れるように降りてくる霧が近づくとパッと消える」)。
     */
    private void select(double eyeX, double eyeY, double eyeZ) {
        int count = SIZE * SIZE;
        Arrays.fill(floor, Float.NaN);
        int ci = Math.floorDiv((int) Math.floor(eyeX) - originX, TEXEL);
        int cj = Math.floorDiv((int) Math.floor(eyeZ) - originZ, TEXEL);
        int startCell = -1;
        int startRun = -1;
        double best = Double.POSITIVE_INFINITY;
        // カメラの高さを空きの中に含む空き、無ければ足の下でいちばん近い床。近いマスほど先。
        for (int dj = -START_RADIUS; dj <= START_RADIUS; dj++) {
            for (int di = -START_RADIUS; di <= START_RADIUS; di++) {
                int i = ci + di;
                int j = cj + dj;
                if (i < 0 || j < 0 || i >= SIZE || j >= SIZE) {
                    continue;
                }
                int cell = j * SIZE + i;
                for (int r = 0; r < runCount[cell]; r++) {
                    int k = cell * MAX_RUNS + r;
                    double below = eyeY - runFloor[k];
                    boolean inside = below >= -0.5 && eyeY <= runTop[k] + 1.5;
                    double score = (di * di + dj * dj) + (inside ? 0.0 : 100.0 + Math.abs(below));
                    if (score < best) {
                        best = score;
                        startCell = cell;
                        startRun = r;
                    }
                }
            }
        }
        if (startCell < 0) {
            return;
        }
        // 全部の空きを、隣の列の空きと重なるかでまとめる(素集合)。
        int[] parent = new int[count * MAX_RUNS];
        for (int k = 0; k < parent.length; k++) {
            parent[k] = k;
        }
        for (int cell = 0; cell < count; cell++) {
            int i = cell % SIZE;
            int j = cell / SIZE;
            for (int r = 0; r < runCount[cell]; r++) {
                int k = cell * MAX_RUNS + r;
                for (int d = 0; d < 2; d++) {
                    int ni = i + (d == 0 ? 1 : 0);
                    int nj = j + (d == 1 ? 1 : 0);
                    if (ni >= SIZE || nj >= SIZE) {
                        continue;
                    }
                    int nb = nj * SIZE + ni;
                    for (int rn = 0; rn < runCount[nb]; rn++) {
                        int kn = nb * MAX_RUNS + rn;
                        float overlap = Math.min(runTop[k], runTop[kn]) + 1.0F - Math.max(runFloor[k], runFloor[kn]);
                        if (overlap >= MIN_OVERLAP) {
                            union(parent, k, kn);
                        }
                    }
                }
            }
        }
        int root = find(parent, startCell * MAX_RUNS + startRun);
        float reference = baseY + BASE_STEP / 2.0F - 1.6F;
        for (int cell = 0; cell < count; cell++) {
            int pick = -1;
            float closest = Float.POSITIVE_INFINITY;
            for (int r = 0; r < runCount[cell]; r++) {
                int k = cell * MAX_RUNS + r;
                if (find(parent, k) != root) {
                    continue;
                }
                float d = Math.abs(runFloor[k] - reference);
                if (d < closest) {
                    closest = d;
                    pick = k;
                }
            }
            if (pick >= 0) {
                floor[cell] = runFloor[pick];
                blockLight[cell] = runBlock[pick];
                skyLight[cell] = runSky[pick];
            }
        }
    }

    private static int find(int[] parent, int k) {
        while (parent[k] != k) {
            parent[k] = parent[parent[k]];
            k = parent[k];
        }
        return k;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            if (ra < rb) {
                parent[rb] = ra;
            } else {
                parent[ra] = rb;
            }
        }
    }

    /**
     * マスの真ん中の列の床と、隣のマスとの間の 3 列(x+1・z+1・斜め)の床の一番高い所。間の列は真ん中の床のまわり ±{@link #FINE_RANGE} だけ見る。
     * 地図は 2 ブロックおきの列しか見ないので、その間の 1 ブロックの段差に低い板が刺さっていた(坂で見る角度によって階段のように欠ける)。
     */
    private float fineFloor(World world, int index) {
        float f = floor[index];
        if (Float.isNaN(f)) {
            return Float.NaN;
        }
        int x = columnX(index);
        int z = columnZ(index);
        int from = (int) Math.floor(f) + FINE_RANGE;
        int to = (int) Math.floor(f) - FINE_RANGE;
        float hi = f;
        for (int d = 1; d <= 3; d++) {
            int cx = x + ((d & 1) != 0 ? 1 : 0);
            int cz = z + ((d & 2) != 0 ? 1 : 0);
            if (!world.loaded(cx, cz)) {
                continue;
            }
            boolean openAbove = world.kind(cx, from + 1, cz) == World.OPEN;
            for (int y = from; y >= to; y--) {
                int kind = world.kind(cx, y, cz);
                if (kind == World.OPEN) {
                    openAbove = true;
                    continue;
                }
                if (openAbove) {
                    hi = Math.max(hi, kind == World.FLUID ? y + 0.9F : y + 1.0F);
                    break;
                }
            }
        }
        return hi;
    }

    /**
     * 窓の床の真ん中の高さ + {@link #DEPTH}。窓の中の床が少なければ NaN(細い坑道)。数えるのはこのマスの床から上下 {@link #LEVEL_RANGE} ブロック以内の床だけ
     * (それより高い・低い床は別の段)。前は崖の上の段の床も数えていたので、崖の下のてっぺんが上の段の高さまで上がって霧が崖の下まで続き、
     * その霧が作り直しのたびに変わった(10-16 ユーザー指摘・提案「崖のような所は下まで霧を続かせず途中で消える」)。崖・深い穴を流れ落ちる霧は
     * {@link CaveMist} の霧の滝が受け持つ。
     */
    private float windowTop(int index, double[] window, int need) {
        int i = index % SIZE;
        int j = index / SIZE;
        float own = floor[index];
        int n = 0;
        for (int dj = -WINDOW; dj <= WINDOW; dj++) {
            int jj = j + dj;
            if (jj < 0 || jj >= SIZE) {
                continue;
            }
            for (int di = -WINDOW; di <= WINDOW; di++) {
                int ii = i + di;
                if (ii < 0 || ii >= SIZE) {
                    continue;
                }
                float f = floor[jj * SIZE + ii];
                if (!Float.isNaN(f) && (Float.isNaN(own) || Math.abs(f - own) <= LEVEL_RANGE)) {
                    window[n++] = f;
                }
            }
        }
        if (n < need) {
            return Float.NaN;
        }
        Arrays.sort(window, 0, n);
        double at = FLOOR_PERCENTILE * (n - 1);
        int a = (int) Math.floor(at);
        int b = Math.min(n - 1, a + 1);
        double median = window[a] + (window[b] - window[a]) * (at - a);
        return (float) (median + depth);
    }

    /**
     * まわり 3×3 の床の一番高い所({@link #floorHi})を決め、てっぺんを 3×3 でならす(段々畑のような段を消す)。
     * 前は坂に霧が残るよう、霧の中の床のてっぺんを {@link #floorHi} + 0.8 以上に上げていたが、柱の根元の段・岩棚にも効いて霧がテントのように
     * 柱を登った(10-16 ユーザー指摘「柱に吸い付くように不自然に霧が上がる」)のでやめた。坂の霧は霞が見せる。ならすのは同じ段(床の差 {@link #LEVEL_RANGE} 以内)のマスとだけ
     * (崖の上の段のてっぺんが混ざると、崖の下に霧が積み上がる)。
     */
    private void smooth() {
        int count = SIZE * SIZE;
        for (int cell = 0; cell < count; cell++) {
            int i = cell % SIZE;
            int j = cell / SIZE;
            float hi = Float.NaN;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    int ii = i + di;
                    int jj = j + dj;
                    if (ii < 0 || jj < 0 || ii >= SIZE || jj >= SIZE) {
                        continue;
                    }
                    float f = fineMax[jj * SIZE + ii];
                    if (!Float.isNaN(f) && (Float.isNaN(hi) || f > hi)) {
                        hi = f;
                    }
                }
            }
            floorHi[cell] = Float.isNaN(floor[cell]) ? Float.NaN : hi;
        }
        float[] smoothTop = new float[count];
        for (int cell = 0; cell < count; cell++) {
            int i = cell % SIZE;
            int j = cell / SIZE;
            float sum = 0.0F;
            int n = 0;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    int ii = i + di;
                    int jj = j + dj;
                    if (ii < 0 || jj < 0 || ii >= SIZE || jj >= SIZE) {
                        continue;
                    }
                    int nb = jj * SIZE + ii;
                    if (!Float.isNaN(top[nb]) && (Float.isNaN(floor[cell]) || Float.isNaN(floor[nb]) || Math.abs(floor[nb] - floor[cell]) <= LEVEL_RANGE)) {
                        sum += top[nb];
                        n++;
                    }
                }
            }
            smoothTop[cell] = Float.isNaN(top[cell]) || n == 0 ? Float.NaN : sum / n;
        }
        System.arraycopy(smoothTop, 0, top, 0, count);
    }

    /** できあがったか。 */
    boolean done() {
        return phase == 3;
    }
}
