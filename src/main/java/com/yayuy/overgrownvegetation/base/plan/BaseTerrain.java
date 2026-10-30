package com.yayuy.overgrownvegetation.base.plan;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 基地を地形に合わせる(2026-10-01)。ユーザー指定「村のように地形に沿って壁で囲む」—— 基地の中をまっ平らにはしない。
 *
 * <ol>
 *     <li>自然の地表を 8 マスおきに読み({@link Sampler})、3 × 3 の平均を 2 回かけた<b>なだらかな面</b>を作る(道と壁はこの面に沿う)。
 *     水の上の標本は水面の高さで数える —— 川底で数えると岸の外周が川へ下っていく。</li>
 *     <li>建物ごとに、足元の 4 隅と真ん中のなだらかな面の平均を<b>土台の高さ</b>にする(建物の床は平ら)。</li>
 *     <li>建物の周り 6 マスは、土台から 1 マスごとに 1 段ずつしか離れないように切り盛りする(1:1 の法面)。</li>
 * </ol>
 * 標本は地面(水の下なら水底)と水面の 2 つ。水面が地面より上の標本が<b>水</b>で、配置計画は区画を水に掛けない
 * (ユーザー指摘 2026-10-01「広げると川を貫いて川が消える」)。読む範囲は最大の基地より一回り広い({@link #EXTENT})
 * —— 世界生成で作った基地を後でレベル 5 まで上げても、内側の高さが変わらない。標本だけ持てば作り直せるので、ゲーム側はこれを保存する。
 */
public final class BaseTerrain {

    /** 自然の地表を読む口(基地の局所座標)。 */
    public interface Sampler {
        /** 一番上の固いブロックの y(水の下なら水底)。 */
        int groundY(int x, int z);

        /** 水面の y(水が無ければ {@link #groundY} と同じ)。 */
        default int surfaceY(int x, int z) {
            return groundY(x, z);
        }
    }

    public static final int STEP = 8;
    /** 標本を読む範囲の半幅(区画の届く限界 + 払う幅 + 余白)。 */
    public static final int EXTENT = BasePlan.GRID + 10;
    public static final int N = 2 * ((EXTENT + STEP - 1) / STEP) + 1;
    private static final int ORIGIN = -((N - 1) / 2) * STEP;
    /** 建物の周りで切り盛りする幅。 */
    public static final int SLOPE_REACH = 6;

    private final short[] ground;
    private final short[] surface;
    private final double[] smooth;
    private final Map<Plot, Integer> pads = new ConcurrentHashMap<>();

    private BaseTerrain(short[] ground, short[] surface) {
        this.ground = ground;
        this.surface = surface;
        short[] top = new short[ground.length];
        for (int i = 0; i < top.length; i++) {
            top[i] = (short) Math.max(ground[i], surface[i]);
        }
        this.smooth = smooth(top);
    }

    /** 地表を読んで作る。 */
    public static BaseTerrain sample(Sampler sampler) {
        short[] g = new short[N * N];
        short[] s = new short[N * N];
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                int x = ORIGIN + i * STEP;
                int z = ORIGIN + j * STEP;
                int gy = sampler.groundY(x, z);
                g[j * N + i] = (short) gy;
                s[j * N + i] = (short) Math.max(gy, sampler.surfaceY(x, z));
            }
        }
        return new BaseTerrain(g, s);
    }

    /** 保存しておいた標本から作り直す。{@code surface} が無い(古い保存)なら水は無いものとする。 */
    public static BaseTerrain fromSamples(short[] ground, short[] surface) {
        if (ground.length != N * N) {
            throw new IllegalArgumentException("標本の数が合わない: " + ground.length + " != " + N * N);
        }
        short[] s = surface != null && surface.length == ground.length ? surface.clone() : ground.clone();
        return new BaseTerrain(ground.clone(), s);
    }

    /** 地面の標本(保存用)。 */
    public short[] samples() {
        return ground.clone();
    }

    /** 水面の標本(保存用)。 */
    public short[] surfaceSamples() {
        return surface.clone();
    }

    private static double[] smooth(short[] s) {
        double[] a = new double[s.length];
        for (int i = 0; i < s.length; i++) {
            a[i] = s[i];
        }
        for (int pass = 0; pass < 2; pass++) {
            double[] b = new double[a.length];
            for (int j = 0; j < N; j++) {
                for (int i = 0; i < N; i++) {
                    double sum = 0;
                    int n = 0;
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            int ii = i + di;
                            int jj = j + dj;
                            if (ii >= 0 && jj >= 0 && ii < N && jj < N) {
                                sum += a[jj * N + ii];
                                n++;
                            }
                        }
                    }
                    b[j * N + i] = sum / n;
                }
            }
            a = b;
        }
        return a;
    }

    /** なだらかな面の高さ(双線形補間)。 */
    public double design(int x, int z) {
        double fx = (x - ORIGIN) / (double) STEP;
        double fz = (z - ORIGIN) / (double) STEP;
        int i = (int) Math.floor(fx);
        int j = (int) Math.floor(fz);
        i = Math.max(0, Math.min(N - 2, i));
        j = Math.max(0, Math.min(N - 2, j));
        double tx = Math.max(0, Math.min(1, fx - i));
        double tz = Math.max(0, Math.min(1, fz - j));
        double a = smooth[j * N + i];
        double b = smooth[j * N + i + 1];
        double c = smooth[(j + 1) * N + i];
        double d = smooth[(j + 1) * N + i + 1];
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
    }

    /** 長方形 [x0, x1] × [z0, z1](端を含む)にかかる標本のどれかが水か。 */
    public boolean anyWater(int x0, int z0, int x1, int z1) {
        int i0 = Math.max(0, Math.floorDiv(x0 - ORIGIN + STEP - 1, STEP));
        int i1 = Math.min(N - 1, Math.floorDiv(x1 - ORIGIN, STEP));
        int j0 = Math.max(0, Math.floorDiv(z0 - ORIGIN + STEP - 1, STEP));
        int j1 = Math.min(N - 1, Math.floorDiv(z1 - ORIGIN, STEP));
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                if (surface[j * N + i] > ground[j * N + i]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 浄化圏 {@code l} を {@code margin} マス広げた八角形の中の標本のどれかが水か。外接の四角で見る {@link #anyWater(int, int, int, int)}
     * と違い、八角形の欠けた角の外の水は数えない(半幅も斜めの辺も {@code margin} だけ外へ出す: 0.71 × 2 × margin ≒ √2 × margin)。
     */
    public boolean anyWater(Lobe l, int margin) {
        Lobe g = new Lobe(l.zone(), l.level(), l.cx(), l.cz(), l.rx() + margin, l.rz() + margin, l.parent(), l.box());
        int i0 = Math.max(0, Math.floorDiv(g.x0() - ORIGIN + STEP - 1, STEP));
        int i1 = Math.min(N - 1, Math.floorDiv(g.x1() - ORIGIN, STEP));
        int j0 = Math.max(0, Math.floorDiv(g.z0() - ORIGIN + STEP - 1, STEP));
        int j1 = Math.min(N - 1, Math.floorDiv(g.z1() - ORIGIN, STEP));
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                if (surface[j * N + i] > ground[j * N + i] && g.contains(ORIGIN + i * STEP, ORIGIN + j * STEP)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 長方形の中のなだらかな面の高低差(5 × 5 の点で測る)。 */
    public double relief(int x0, int z0, int x1, int z1) {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int a = 0; a <= 4; a++) {
            for (int b = 0; b <= 4; b++) {
                double h = design(x0 + (x1 - x0) * a / 4, z0 + (z1 - z0) * b / 4);
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
            }
        }
        return hi - lo;
    }

    /**
     * 区画 {@code p} の土台の高さを決めておく(研究所の連絡通路: 両端の棟の低い方。{@link BaseEmitter} が作るときに決める)。
     * 決めた区画は {@link #pad} がこの値を返す。
     */
    void pinPad(Plot p, int pad) {
        pads.put(p, pad);
    }

    /** 区画の土台の高さ(足元の 4 隅と真ん中のなだらかな面の平均)。 */
    public int pad(Plot p) {
        return pads.computeIfAbsent(p, q -> (int) Math.round((design(q.x0(), q.z0()) + design(q.x1(), q.z0())
                + design(q.x0(), q.z1()) + design(q.x1(), q.z1())
                + design((q.x0() + q.x1()) / 2, (q.z0() + q.z1()) / 2)) / 5.0));
    }

    /**
     * (x, z) の仕上がりの地面の高さ。建物の足元は土台、その周り {@link #SLOPE_REACH} マスは土台から 1 マスにつき 1 段の範囲に収め、
     * それより外はなだらかな面。{@code near} は (x, z) の近くの区画(探す手間を省くため、呼ぶ側が渡す)。
     */
    public int target(int x, int z, Iterable<Plot> near) {
        // 建物の足元が先(扉の前の列より強い。弾薬庫の前の防爆壁・正門の前の検問所は、前の建物の扉の前の列にかかる)
        for (Plot p : near) {
            if (p.distance(x, z) == 0) {
                return pad(p);
            }
        }
        int base = (int) Math.round(design(x, z));
        int lo = Integer.MIN_VALUE;
        int hi = Integer.MAX_VALUE;
        int nearestPad = base;
        int nearestDist = Integer.MAX_VALUE;
        for (Plot p : near) {
            int dist = p.distance(x, z);
            if (dist > SLOPE_REACH) {
                continue;
            }
            // 正面(正門は裏も)の外は、扉の前の 1 列を土台の続きに数える(その列は土台の高さ、その先は 1 マスにつき 1 段)
            int eff = front(p, x, z) ? dist - 1 : dist;
            int pad = pad(p);
            if (eff == 0) {
                return pad;
            }
            lo = Math.max(lo, pad - eff);
            hi = Math.min(hi, pad + eff);
            if (eff < nearestDist) {
                nearestDist = eff;
                nearestPad = pad;
            }
        }
        if (lo > hi) {
            return Math.max(nearestPad - nearestDist, Math.min(nearestPad + nearestDist, base));
        }
        return Math.max(lo, Math.min(hi, base));
    }

    /**
     * (x, z) が区画の正面(正門は基地の内側の裏も)の外で、正面の幅の内か。扉の前の 1 列は土台と同じ高さにする —— 扉の前の地面が土台より
     * 1 段高いと、扉の上の壁に頭がつかえて入れなかった(2026-10-02 の歩ける点検で、起伏のある所の見張り台・正門の塔に見つけた)。
     */
    private static boolean front(Plot p, int x, int z) {
        // 正門は裏も、研究所の棟は左右も(左右の壁の真ん中に連絡通路の出入口がある。2026-10-02 夜)
        Facing[] sides = p.kind() == BuildingKind.GATEHOUSE ? new Facing[]{p.facing(), p.facing().opposite()}
                : researchBuilding(p.kind()) ? new Facing[]{p.facing(), p.facing().rotateCw(1), p.facing().rotateCw(3)}
                : new Facing[]{p.facing()};
        for (Facing f : sides) {
            boolean hit = switch (f) {
                case SOUTH -> z > p.z1() && x >= p.x0() && x <= p.x1();
                case NORTH -> z < p.z0() && x >= p.x0() && x <= p.x1();
                case EAST -> x > p.x1() && z >= p.z0() && z <= p.z1();
                case WEST -> x < p.x0() && z >= p.z0() && z <= p.z1();
            };
            if (hit) {
                return true;
            }
        }
        return false;
    }

    /** 研究所の棟(本館・第 2 研究棟・大型試験棟)か。左右の壁に連絡通路の出入口がある。 */
    static boolean researchBuilding(BuildingKind k) {
        return k == BuildingKind.FIELD_LAB || k == BuildingKind.ANNEX_LAB || k == BuildingKind.TEST_HALL;
    }
}
