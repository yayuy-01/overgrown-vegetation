package com.yayuy.overgrownvegetation.worldgen;

import java.util.function.DoubleSupplier;

/**
 * 巨大樹の真下の「巣の球」の中身の設計図。Minecraft のクラスに依存しない純粋な計算だけを持つ
 * (同じコードを {@code tools/hive-preview} の試作プレビューからも呼べるようにするため)。
 *
 * <h2>形(2026-09-18 版)</h2>
 * <ul>
 *     <li><b>球は中まで詰まっている</b>。外から順に樹皮({@link #BARK})→ 殻({@link #SHELL})→ 詰め物({@link #FILL})。</li>
 *     <li>球の<b>中心にハイヴノードの小部屋</b>({@link #CARVED} の球。半径 {@code cavityRadius})。</li>
 *     <li>小部屋の隣に<b>儀式の広間</b>(寄生植物装備を受け取る演出を再生する部屋)。ハイヴノードは
 *     必ずこの広間の方を向く({@link #chamberAzimuth})。中央の「儀式の場」(半径 {@code chamberHalfWidth}、
 *     高さ {@code chamberHeight})だけは演出の蔦の前提どおり平らな床・平らな天井で、その外側は
 *     角度ごとに広がりが変わる<b>自然にできた穴のような形</b>(縁は床が 1 段上がり天井が 1 段下がる。
 *     壁のあちこちにくぼみ)。小部屋とは幅 3 の口でつながる。</li>
 *     <li>広間の横腹から<b>通路</b>が出て、ハイヴノードの小部屋の周りを {@code tunnelTurns} 周
 *     (既定 1.5 周)らせん状に登りながら回り込み、球面の出口に抜ける。出口は地上に出ている上半分の
 *     帯(仰角 {@code entranceElevationMin〜Max})。うねり({@code winding})は半径と高さの揺れ。</li>
 *     <li>掘った空間に面した詰め物は<b>内張り</b>({@link #LINING})になる。設置側はこれを巨大樹の幹
 *     (原木)にし、面に下草・蔦を生やす。</li>
 * </ul>
 *
 * <p>結果は 1 辺 {@code 2·(radius+bark)+1} の立方体グリッド({@link #cells})で、各マスに種類を入れる。
 * 添字は {@link #index(int, int, int)}(中心からのオフセットで引く)。
 */
public final class HiveChamberLayout {

    /** 何もしない(球の外)。 */
    public static final byte OUTSIDE = 0;
    /** 殻の外側を包む樹皮(巨大樹の原木)。 */
    public static final byte BARK = 1;
    /** 球の表面 1 層(硬化した巨大樹の幹)。 */
    public static final byte SHELL = 2;
    /** 球の詰め物(巨大樹の形成層)。 */
    public static final byte FILL = 3;
    /** 掘った空気(小部屋・儀式の広間・通路)。 */
    public static final byte CARVED = 4;
    /** 掘った空気に面した詰め物 = 通路・広間の壁面(巨大樹の幹)。 */
    public static final byte LINING = 5;

    /** 設定値のまとまり。 */
    public record Params(int radius, int barkThickness, int cavityRadius, int chamberHalfWidth,
            int chamberHeight, double tunnelRadius, double winding, double tunnelTurns,
            double entranceElevationMinDeg, double entranceElevationMaxDeg) {
    }

    /** 儀式の広間の床(空気の一番下の段)の、球の中心からの高さ。ノードの下半分の高さ。 */
    public static final int FLOOR_Y = -1;

    public final Params params;
    /** グリッドの半径(= radius + barkThickness)。 */
    public final int outer;
    /** グリッドの 1 辺。 */
    public final int size;
    public final byte[] cells;
    /** 儀式の広間の床の中心(中心からのオフセット)。 */
    public final int[] chamberCenter = new int[3];
    /** 広間からハイヴノードの小部屋を見る向き(ラジアン、XZ 平面。+X が 0、+Z が π/2)。 */
    public final double chamberFacing;
    /** 小部屋から広間を見る向き(ラジアン)。ハイヴノードはこちらを向く。 */
    public final double chamberAzimuth;
    /** 通路の出口(球面上、中心からのオフセット)。 */
    public final int[] entrance = new int[3];
    /** 通路の歩数(長さの目安)。 */
    public final int tunnelSteps;
    /** 内張り({@link #LINING})のマス数。 */
    public final int liningCount;

    /** 小部屋・広間・口のマスとその隣(チェビシェフ距離 1)。通路はここを掘らない(広間へ勝手に穴が開かないように)。 */
    private final boolean[] protectedCells;
    /** 通路が広間を離れたか。離れるまでは保護を効かせない(出発点は広間の縁なので)。 */
    private boolean protectActive;
    private final int limitSq;

    /**
     * @param random 0〜1 の乱数を返す関数({@code RandomSource::nextDouble} でも {@code Random::nextDouble} でも)
     */
    public HiveChamberLayout(Params p, DoubleSupplier random) {
        this.params = p;
        this.outer = p.radius() + Math.max(0, p.barkThickness());
        this.size = outer * 2 + 1;
        this.cells = new byte[size * size * size];
        this.protectedCells = new boolean[cells.length];
        // 掘ってよい範囲: 殻の 1 つ内側まで(殻は出口以外で破らない)。
        this.limitSq = (p.radius() - 1) * (p.radius() - 1);

        int rSq = p.radius() * p.radius();
        int outerSq = outer * outer;
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dy = -outer; dy <= outer; dy++) {
                for (int dz = -outer; dz <= outer; dz++) {
                    int dSq = dx * dx + dy * dy + dz * dz;
                    if (dSq > outerSq) {
                        continue;
                    }
                    byte kind;
                    if (dSq > rSq) {
                        kind = BARK;
                    } else if (isShellCell(dx, dy, dz, rSq)) {
                        kind = SHELL;
                    } else {
                        kind = FILL;
                    }
                    cells[index(dx, dy, dz)] = kind;
                }
            }
        }

        // 1) ハイヴノードの小部屋: 中心の球。
        carveSphere(0, 0, 0, p.cavityRadius(), Integer.MIN_VALUE);

        // 2) 儀式の広間: 小部屋の隣、ランダムな方角。ノードはこの方角を向く。
        double az = random.getAsDouble() * Math.PI * 2.0;
        int hw = Math.max(2, p.chamberHalfWidth());
        int h = Math.max(3, p.chamberHeight());
        double dist = p.cavityRadius() + hw + 1.0;
        int ccx = (int) Math.round(Math.cos(az) * dist);
        int ccz = (int) Math.round(Math.sin(az) * dist);
        chamberCenter[0] = ccx;
        chamberCenter[1] = FLOOR_Y;
        chamberCenter[2] = ccz;
        chamberAzimuth = az;
        chamberFacing = Math.atan2(-ccz, -ccx);
        double[] rimShape = carveChamber(ccx, ccz, hw, h, random);
        // 小部屋との口: 広間の中心から球の中心へ、幅 3・高さ h の通路。
        carveCorridor(ccx, FLOOR_Y, ccz, 0, FLOOR_Y, 0, 1, h);
        markProtected();

        // 3) 通路: 広間の横腹から出て、小部屋の周りをらせんに回りながら出口へ。
        double elevMin = Math.toRadians(Math.min(p.entranceElevationMinDeg(), p.entranceElevationMaxDeg()));
        double elevMax = Math.toRadians(Math.max(p.entranceElevationMinDeg(), p.entranceElevationMaxDeg()));
        double elev = elevMin + (elevMax - elevMin) * random.getAsDouble();
        int dir = random.getAsDouble() < 0.5 ? 1 : -1;
        // 出発点: 広間の縁のうち、回る向き(dir)側の横腹。
        double sideAz = az + dir * Math.PI / 2.0;
        double startR = Math.max(hw - 0.5, rimAt(rimShape, sideAz) - 1.0);
        double sx = ccx + Math.cos(sideAz) * startR;
        double sz = ccz + Math.sin(sideAz) * startR;
        double[] exitDir = new double[3];
        this.tunnelSteps = carveTunnel(sx, sz, ccx, ccz, hw, dir, elev, exitDir, random);
        entrance[0] = (int) Math.round(exitDir[0] * p.radius());
        entrance[1] = (int) Math.round(exitDir[1] * p.radius());
        entrance[2] = (int) Math.round(exitDir[2] * p.radius());

        // 4) 内張り: 掘った空気に(斜めも含めて)接する詰め物。
        this.liningCount = applyLining();
    }

    /** グリッドの添字(中心からのオフセット)。範囲外は -1。 */
    public int index(int dx, int dy, int dz) {
        if (dx < -outer || dx > outer || dy < -outer || dy > outer || dz < -outer || dz > outer) {
            return -1;
        }
        return ((dx + outer) * size + (dy + outer)) * size + (dz + outer);
    }

    public byte get(int dx, int dy, int dz) {
        int i = index(dx, dy, dz);
        return i < 0 ? OUTSIDE : cells[i];
    }

    private void carve(int dx, int dy, int dz) {
        int i = index(dx, dy, dz);
        if (i >= 0 && cells[i] != OUTSIDE) {
            cells[i] = CARVED;
        }
    }

    /** 球を掘る。{@code minY} より下は掘らない(床を守るため)。殻より外は掘らない。 */
    private void carveSphere(double cx, double cy, double cz, double r, int minY) {
        int ir = (int) Math.ceil(r);
        double edge = (r + 0.35) * (r + 0.35);
        int bx = (int) Math.round(cx), by = (int) Math.round(cy), bz = (int) Math.round(cz);
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dy = -ir; dy <= ir; dy++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    if (y < minY || x * x + y * y + z * z > limitSq) {
                        continue;
                    }
                    double ex = x - cx, ey = y - cy, ez = z - cz;
                    if (ex * ex + ey * ey + ez * ez <= edge) {
                        carve(x, y, z);
                    }
                }
            }
        }
    }

    /**
     * 儀式の広間。中央の「儀式の場」(半径 hw、高さ h)は平らに保ち、その外側を角度ごとに違う広がりで
     * 掘って、自然にできた穴のような輪郭にする。縁は床が 1 段上がり天井が 1 段下がる。
     * 最後に壁のあちこちにくぼみ(球)を足す。
     *
     * @return 縁の形(正弦波の位相 3 つと基準半径)。{@link #rimAt} で角度から半径を引く。
     */
    private double[] carveChamber(int ccx, int ccz, int hw, int h, DoubleSupplier random) {
        double[] shape = {hw + 0.8, random.getAsDouble() * Math.PI * 2.0,
                random.getAsDouble() * Math.PI * 2.0, random.getAsDouble() * Math.PI * 2.0};
        int reach = hw + 4;
        double zoneSq = (hw + 0.5) * (hw + 0.5);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double rSq = dx * dx + dz * dz;
                boolean zone = rSq <= zoneSq;
                double rim = rimAt(shape, Math.atan2(dz, dx));
                double r = Math.sqrt(rSq);
                if (!zone && r > rim) {
                    continue;
                }
                boolean edge = !zone && r > rim - 1.0;
                int from = edge ? FLOOR_Y + 1 : FLOOR_Y;
                int to = edge ? FLOOR_Y + h - 2 : FLOOR_Y + h - 1;
                for (int y = from; y <= to; y++) {
                    int x = ccx + dx, z = ccz + dz;
                    if (x * x + y * y + z * z <= limitSq) {
                        carve(x, y, z);
                    }
                }
            }
        }
        // 壁のくぼみ。儀式の場(中央)にはかからない位置に、縁へ半分埋まる球を置く。
        int pockets = 2 + (int) (random.getAsDouble() * 3.0);
        for (int i = 0; i < pockets; i++) {
            double th = random.getAsDouble() * Math.PI * 2.0;
            double rim = rimAt(shape, th);
            double pr = 1.4 + random.getAsDouble() * 1.0;
            double px = ccx + Math.cos(th) * (rim - 0.3);
            double pz = ccz + Math.sin(th) * (rim - 0.3);
            double py = FLOOR_Y + 0.6 + random.getAsDouble() * 1.2;
            if (Math.hypot(px - ccx, pz - ccz) - pr < hw + 0.5) {
                continue; // 儀式の場に食い込む
            }
            carveSphere(px, py, pz, pr, FLOOR_Y);
        }
        return shape;
    }

    /** 広間の縁の半径(角度 {@code th} の方向)。 */
    private static double rimAt(double[] shape, double th) {
        return shape[0] + 0.9 * Math.sin(2.0 * th + shape[1]) + 0.7 * Math.sin(3.0 * th + shape[2])
                + 0.4 * Math.sin(5.0 * th + shape[3]);
    }

    /** 2 点を結ぶまっすぐな通路(横半幅 {@code halfW}、高さ {@code h}、床は {@code y1}〜)。 */
    private void carveCorridor(int x1, int y1, int z1, int x2, int y2, int z2, int halfW, int h) {
        double len = Math.hypot(x2 - x1, z2 - z1);
        int steps = (int) Math.ceil(len);
        for (int s = 0; s <= steps; s++) {
            double t = steps == 0 ? 0 : (double) s / steps;
            int x = (int) Math.round(x1 + (x2 - x1) * t);
            int z = (int) Math.round(z1 + (z2 - z1) * t);
            int y = (int) Math.round(y1 + (y2 - y1) * t);
            for (int dx = -halfW; dx <= halfW; dx++) {
                for (int dz = -halfW; dz <= halfW; dz++) {
                    for (int dy = 0; dy < h; dy++) {
                        carve(x + dx, y + dy, z + dz);
                    }
                }
            }
        }
    }

    /** 今掘ってある空気(小部屋・広間・口)とその隣を保護する。通路はここを破らない。 */
    private void markProtected() {
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dy = -outer; dy <= outer; dy++) {
                for (int dz = -outer; dz <= outer; dz++) {
                    if (cells[index(dx, dy, dz)] != CARVED) {
                        continue;
                    }
                    for (int ox = -1; ox <= 1; ox++) {
                        for (int oy = -1; oy <= 1; oy++) {
                            for (int oz = -1; oz <= 1; oz++) {
                                int i = index(dx + ox, dy + oy, dz + oz);
                                if (i >= 0) {
                                    protectedCells[i] = true;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * らせんの通路。出発点 (sx, sz) から、球の中心の周りを {@code dir} 向きに {@code tunnelTurns} 周
     * 回りながら半径を広げ、出口の仰角 {@code elev} の高さまで登る。最後は殻・樹皮を突き抜けて外へ。
     * 半径と高さには {@code winding} に応じた揺れを乗せる。
     *
     * @param exitDir 出口の方向(単位ベクトル)を書き戻す
     * @return 歩数
     */
    private int carveTunnel(double sx, double sz, int ccx, int ccz, int hw, int dir, double elev,
            double[] exitDir, DoubleSupplier random) {
        Params p = params;
        int R = p.radius();
        double baseR = Math.max(1.0, p.tunnelRadius());
        double w = Math.max(0.0, Math.min(1.0, p.winding()));
        double phi = Math.PI * 2.0 * Math.max(0.25, p.tunnelTurns());
        double th0 = Math.atan2(sz, sx);
        double rho0 = Math.hypot(sx, sz);
        double th1 = th0 + dir * phi;
        double rEnd = R - 3.0;
        double rho1 = Math.cos(elev) * rEnd;
        double y0 = FLOOR_Y + 1.0;
        double y1 = Math.sin(elev) * rEnd;
        double keepOut = p.cavityRadius() + baseR + 2.5;
        double ph1 = random.getAsDouble() * Math.PI * 2.0;
        double ph2 = random.getAsDouble() * Math.PI * 2.0;
        double ph3 = random.getAsDouble() * Math.PI * 2.0;
        double ampR1 = 0.6 + 1.4 * w, ampR2 = 0.3 + 0.7 * w, ampY = 0.3 + 0.9 * w;
        int n = (int) Math.ceil(phi * (rho0 + rho1) * 0.5 + Math.abs(y1 - y0));
        double leaveSq = (hw + 2.0 + baseR) * (hw + 2.0 + baseR);
        protectActive = false;
        int step = 0;
        double lx = sx, ly = y0, lz = sz;
        for (int i = 0; i <= n; i++, step++) {
            double s = (double) i / n;
            double th = th0 + dir * phi * s;
            double rho = rho0 + (rho1 - rho0) * Math.pow(s, 0.8)
                    + ampR1 * Math.sin(s * phi * 1.7 + ph1) + ampR2 * Math.sin(s * phi * 3.3 + ph2);
            double sm = s * s * (3.0 - 2.0 * s);
            double y = y0 + (y1 - y0) * sm + ampY * Math.sin(s * phi * 2.3 + ph3) * Math.sin(Math.PI * s);
            rho = Math.max(keepOut, rho);
            if (rho * rho + y * y > rEnd * rEnd) {
                rho = Math.sqrt(Math.max(0.0, rEnd * rEnd - y * y));
            }
            double x = Math.cos(th) * rho, z = Math.sin(th) * rho;
            if (!protectActive) {
                double ex = x - ccx, ez = z - ccz;
                if (ex * ex + ez * ez > leaveSq) {
                    protectActive = true;
                }
            }
            double r = baseR * (0.8 + 0.4 * Math.abs(Math.sin(i * 0.37 + ph3)));
            carveTunnelSphere(x, y + r * 0.35, z, r, ccx, ccz, hw, false);
            lx = x; ly = y; lz = z;
        }
        // 出口へ: 最後の点から真っ直ぐ外(半径方向)へ、殻と樹皮を抜けて球の外まで。
        double len = Math.sqrt(lx * lx + ly * ly + lz * lz);
        exitDir[0] = lx / len;
        exitDir[1] = ly / len;
        exitDir[2] = lz / len;
        double target = outer + 1.5;
        int legs = (int) Math.ceil(target - len);
        for (int i = 1; i <= legs; i++, step++) {
            double t = len + i;
            double r = baseR * 0.95;
            carveTunnelSphere(exitDir[0] * t, exitDir[1] * t + r * 0.35, exitDir[2] * t, r, ccx, ccz, hw, true);
        }
        return step;
    }

    /**
     * 通路用の球掘り。殻より外は掘らない({@code breakout} のときだけ出口として突き抜ける)。
     * 小部屋の周り・保護マス(広間とその隣)・儀式の場の真上は掘らない。
     */
    private void carveTunnelSphere(double cx, double cy, double cz, double r, int ccx, int ccz, int hw,
            boolean breakout) {
        int ir = (int) Math.ceil(r);
        double edge = (r + 0.35) * (r + 0.35);
        int bx = (int) Math.round(cx), by = (int) Math.round(cy), bz = (int) Math.round(cz);
        double cavityGuard = params.cavityRadius() + 1.5;
        double cavityGuardSq = cavityGuard * cavityGuard;
        double zoneSq = (hw + 0.5) * (hw + 0.5);
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dy = -ir; dy <= ir; dy++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    double ex = x - cx, ey = y - cy, ez = z - cz;
                    if (ex * ex + ey * ey + ez * ez > edge) {
                        continue;
                    }
                    int i = index(x, y, z);
                    if (i < 0 || cells[i] == OUTSIDE || cells[i] == CARVED) {
                        continue;
                    }
                    int dSq = x * x + y * y + z * z;
                    if (!breakout && dSq > limitSq) {
                        continue;
                    }
                    if (dSq <= cavityGuardSq) {
                        continue;
                    }
                    double zx = x - ccx, zz = z - ccz;
                    if (zx * zx + zz * zz <= zoneSq) {
                        continue;
                    }
                    if (protectActive && protectedCells[i]) {
                        continue;
                    }
                    cells[i] = CARVED;
                }
            }
        }
    }

    /** 掘った空気に(斜めも含めて)接する詰め物を内張りにする。殻・樹皮はそのまま。 */
    private int applyLining() {
        int count = 0;
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dy = -outer; dy <= outer; dy++) {
                for (int dz = -outer; dz <= outer; dz++) {
                    int i = index(dx, dy, dz);
                    if (cells[i] != FILL) {
                        continue;
                    }
                    if (touchesCarved(dx, dy, dz)) {
                        cells[i] = LINING;
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private boolean touchesCarved(int dx, int dy, int dz) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if ((ox | oy | oz) != 0 && get(dx + ox, dy + oy, dz + oz) == CARVED) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 球の表面(殻)のマスか。直交 6 方向のどれか 1 つでも球の外なら殻(閉じた面になる)。 */
    private static boolean isShellCell(int dx, int dy, int dz, int rSq) {
        return sq(dx + 1, dy, dz) > rSq || sq(dx - 1, dy, dz) > rSq
                || sq(dx, dy + 1, dz) > rSq || sq(dx, dy - 1, dz) > rSq
                || sq(dx, dy, dz + 1) > rSq || sq(dx, dy, dz - 1) > rSq;
    }

    private static int sq(int x, int y, int z) {
        return x * x + y * y + z * z;
    }
}
