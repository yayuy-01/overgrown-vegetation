package com.yayuy.overgrownvegetation.entity.tower;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 監視塔の長い首(2026-11-14、フィロセンスの監視塔。サーバーとクライアントで同じ計算。GeckoLib の型を使わない)。
 *
 * <h2>首の持ち方(アニメの生成器 {@code tools/g5-models/mobs/PhylloTowerR2Anims} と同じ)</h2>
 * 首は {@link Shape#segments} 節(監視塔: 10 節 × 24 単位 = 15 ブロック)。形は<b>節ごとの向き</b> {@code th[i]}(度。根元からの曲げの合計。
 * 0 = 真上 / 90 = 前 / 180 = 真下)で持つ。関節 i の X の回転は {@code th[i] − th[i−1]}。首全体は 1 枚の面の中で曲がり、その面の向き
 * {@code yaw}(度。+ = 右)は首の付け根の 1 節目の Y 回りだけで決める(肩で振る。柱の芯のまわりを回さない)。
 *
 * <h2>解き方</h2>
 * 「頭をこの点へ」(面の中の (前 r, 上 y)、単位)を、<b>見本の形</b>(アニメの覗きの姿勢 {@link #PEEK_TOP} など)から離れすぎないように解く。
 * 先の位置は節の向きの和なので、ヤコビアンは節ごとに独立({@code dTip/dth_i = L(cos th_i, −sin th_i)})。ガウス・ニュートンに
 * 「見本との差」と「曲げの差」の重みを足して数回回す(1 回 10 × 10 の連立 1 次方程式)。まっすぐな棒にしないため、届く距離の
 * {@link #REACH_FRACTION} より遠い点は届く所まで縮める。
 *
 * <h2>座標</h2>
 * 「ファイルの空間」= geo.json の座標(単位 1/16 ブロック、y = 0 が地面、−Z が前、<b>+X が Mob の左</b>。GeckoLib は x を反転して読む)。
 * Mob の枠(左・上・前、ブロック)とワールドの行き来は {@link #toWorld}・{@link #toFrame}。
 */
public final class TowerNeck {

    /** 種ごとの首の形(ファイルの空間・単位)。base = 1 節目の軸、seg = 1 節の長さ、head = 頭の軸から花の芯への差(頭の中)。 */
    public record Shape(double[] base, int segments, double seg) {
        public double length() {
            return this.segments * this.seg;
        }
    }

    /** 監視塔フィロセンス r2(geo の neck_1 の軸 (0, 212, −2)、10 節 × 24)。 */
    public static final Shape PHYLLOSENS = new Shape(new double[]{0, 212, -2}, 10, 24.0D);

    /** 頭を置く点が届く距離の上限(首の長さに対する割合)。これより遠いと首がまっすぐな棒になる。 */
    public static final double REACH_FRACTION = 0.92D;

    // ---- アニメの姿勢の節の向き(生成器の makeShapes を解いた後の値を、関節の曲げの表 anim_numbers_r2.txt から足し上げたもの)
    /** 見張り(頂から S 字に立ち上がり、先が前へ鉤になって花を下げる)。頭の軸 前 3.5・高さ 21.2。 */
    public static final double[] WATCH = {-10, -20, -22, -15, 0, 20, 45, 80, 120, 150};
    /** 木々の間から覗く(首を倒し、林冠の底の下へ水平に差し込む)。面の中の形だけ(アニメの横へのねじりは使わない)。前 10.3・高さ 12.0。 */
    public static final double[] PEEK_GAP = {-7, 13, 52, 97, 129, 133, 122, 108, 115, 134};
    /** 上から垂れて覗く(頂から前へ弧を描いて下り、入口の前で頭を起こす)。前 2.9・高さ 3.1。 */
    public static final double[] PEEK_TOP = {27, 85, 144, 179, 189, 187, 187, 185, 179, 165};
    /** 横から回り込んで覗く(頂から前へ出て斜めに下り、軒の上で折れて低く水平に入口へ)。前 6.2・高さ 1.6。 */
    public static final double[] PEEK_SIDE = {109, 143, 178, 197, 193, 173, 147, 131, 128, 128};

    /** 見本との差・曲げの差の重み(単位² / rad²)【仮・Claude】。大きいほど見本の形を保つ(先が届きにくい)。 */
    static final double W_SEED = 60.0D, W_BEND = 140.0D;

    private TowerNeck() {
    }

    /** 解いた首。th = 節の向き(度)、yaw = 面の向き(度、+ = 右)、tip = 頭の軸(面の中の 前 r・上 y、単位)、miss = 狙いとのずれ(単位)。 */
    public record Pose(double[] th, double yaw, double tipR, double tipY, double miss) {
        /** 関節 i の X の回転(度。ファイルの空間の値 = アニメの json と同じ符号)。 */
        public double bend(int i) {
            return i == 0 ? this.th[0] : this.th[i] - this.th[i - 1];
        }

        public Pose withYaw(double y) {
            return new Pose(this.th, y, this.tipR, this.tipY, this.miss);
        }
    }

    // ------------------------------------------------------------------ 順運動学(面の中)

    /** 節の向き th(度)の首で、関節 k(0 = 付け根、segments = 頭の軸)の面の中の位置 {前 r, 上 y}(単位)。 */
    public static double[] joint(Shape sh, double[] th, int k) {
        double r = 0.0D, y = sh.base()[1];
        for (int i = 0; i < k; i++) {
            double a = Math.toRadians(th[i]);
            r += sh.seg() * Math.sin(a);
            y += sh.seg() * Math.cos(a);
        }
        return new double[]{r, y};
    }

    /** 面の中の点（付け根の軸から前 r、上 y）を、面の向き yaw でファイルの空間へ（面は neck_1 の軸のまわりに回る）。 */
    public static double[] planeToFile(Shape sh, double r, double y, double yawDeg) {
        double a = Math.toRadians(yawDeg);
        // 前 (0, 0, −1) を Y 回りに yaw(Rot.matrix の y 回転と同じ向き: + で −X = 右へ)
        return new double[]{sh.base()[0] - r * Math.sin(a), y, sh.base()[2] - r * Math.cos(a)};
    }

    /** 関節 k のファイルの空間の位置。 */
    public static double[] jointFile(Shape sh, Pose p, int k) {
        double[] j = joint(sh, p.th(), k);
        return planeToFile(sh, j[0], j[1], p.yaw());
    }

    // ------------------------------------------------------------------ 解く

    /**
     * 頭の軸を面の中の (r, y)(単位)へ置く首。見本 seed から始め、見本に近い形を保つ。届かない点は届く所まで縮めてから解く。
     */
    public static Pose solve(Shape sh, double[] seed, double r, double y, double yawDeg) {
        int n = sh.segments();
        double r0 = 0.0D, y0 = sh.base()[1];
        double dr = r - r0, dy = y - y0, d = Math.sqrt(dr * dr + dy * dy), max = sh.length() * REACH_FRACTION;
        if (d > max) {
            dr *= max / d;
            dy *= max / d;
        }
        double tr = r0 + dr, ty = y0 + dy;
        double[] s = new double[n], th = new double[n];
        for (int i = 0; i < n; i++) {
            s[i] = Math.toRadians(seed[i]);
            th[i] = s[i];
        }
        double L = sh.seg();
        double[][] a = new double[n][n];
        double[] b = new double[n];
        for (int it = 0; it < 24; it++) {
            double pr = r0, py = y0;
            for (int i = 0; i < n; i++) {
                pr += L * Math.sin(th[i]);
                py += L * Math.cos(th[i]);
            }
            double er = tr - pr, ey = ty - py, off = Math.sqrt(er * er + ey * ey);
            // 見本に近い形のまま届かない（伸ばしきりに近い）ときは、見本の重みを段々ゆるめる
            double ws = it < 8 || off < 4.0D ? 1.0D : it < 16 ? 0.25D : 0.04D;
            // (JᵀJ + λI + μDᵀD) δ = Jᵀe − λ(th − s) − μDᵀD(th − s)
            for (int i = 0; i < n; i++) {
                double ji0 = L * Math.cos(th[i]), ji1 = -L * Math.sin(th[i]);
                for (int j = 0; j < n; j++) {
                    double jj0 = L * Math.cos(th[j]), jj1 = -L * Math.sin(th[j]);
                    a[i][j] = ji0 * jj0 + ji1 * jj1;
                }
                a[i][i] += ws * W_SEED;
                b[i] = ji0 * er + ji1 * ey - ws * W_SEED * (th[i] - s[i]);
            }
            // 曲げの差 Σ μ((th_i − th_{i−1}) − (s_i − s_{i−1}))²(付け根の 1 節目は th_0 − s_0 そのもの)
            for (int i = 0; i < n; i++) {
                double x = (th[i] - s[i]) - (i == 0 ? 0.0D : th[i - 1] - s[i - 1]);
                a[i][i] += ws * W_BEND;
                b[i] -= ws * W_BEND * x;
                if (i > 0) {
                    a[i][i - 1] -= ws * W_BEND;
                    a[i - 1][i] -= ws * W_BEND;
                    a[i - 1][i - 1] += ws * W_BEND;
                    b[i - 1] += ws * W_BEND * x;
                }
            }
            double[] delta = gauss(a, b, n);
            double m = 0.0D;
            for (int i = 0; i < n; i++) m = Math.max(m, Math.abs(delta[i]));
            double k = m > 0.35D ? 0.35D / m : 1.0D;   // 1 回に 20° より大きく動かさない
            for (int i = 0; i < n; i++) th[i] += delta[i] * k;
            if (m < 1.0E-4D && (off < 4.0D || it >= 16)) break;
        }
        double[] out = new double[n];
        double pr = r0, py = y0;
        for (int i = 0; i < n; i++) {
            out[i] = Math.toDegrees(th[i]);
            pr += L * Math.sin(th[i]);
            py += L * Math.cos(th[i]);
        }
        double miss = Math.sqrt((pr - r) * (pr - r) + (py - y) * (py - y));
        return new Pose(out, yawDeg, pr, py, miss);
    }

    /** 見本の形そのもの(解かない)。 */
    public static Pose of(Shape sh, double[] th, double yawDeg) {
        double[] t = joint(sh, th, sh.segments());
        return new Pose(th.clone(), yawDeg, t[0], t[1], 0.0D);
    }

    private static double[] gauss(double[][] a0, double[] b0, int n) {
        double[][] a = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a0[i], 0, a[i], 0, n);
            a[i][n] = b0[i];
        }
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r;
            double[] t = a[c];
            a[c] = a[p];
            a[p] = t;
            double v = a[c][c];
            if (Math.abs(v) < 1.0E-12D) continue;
            for (int r = c + 1; r < n; r++) {
                double f = a[r][c] / v;
                if (f == 0.0D) continue;
                for (int k = c; k <= n; k++) a[r][k] -= f * a[c][k];
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = a[i][n];
            for (int k = i + 1; k < n; k++) s -= a[i][k] * x[k];
            x[i] = Math.abs(a[i][i]) < 1.0E-12D ? 0.0D : s / a[i][i];
        }
        return x;
    }

    // ------------------------------------------------------------------ 頭の向き

    /**
     * 頭の骨の回り(度、ファイルの空間 = アニメの json と同じ符号の {x, y})。首の先の節の向きの中で、花の見る軸(頭の −Z)を
     * ファイルの空間の向き {@code dir} へ向ける。
     */
    public static double[] headRotation(Pose p, double[] dir) {
        // 首の先の向き = Ry(yaw)·Rx(−th_last)。その転置で dir を頭の親の空間へ
        double yaw = Math.toRadians(p.yaw()), t = Math.toRadians(p.th()[p.th().length - 1]);
        // Ryᵀ
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double x1 = cy * dir[0] - sy * dir[2], y1 = dir[1], z1 = sy * dir[0] + cy * dir[2];
        // Rx(−t)ᵀ = Rx(t)
        double ct = Math.cos(t), st = Math.sin(t);
        double x2 = x1, y2 = ct * y1 - st * z1, z2 = st * y1 + ct * z1;
        double l = Math.sqrt(x2 * x2 + y2 * y2 + z2 * z2);
        if (l < 1.0E-9D) return new double[]{0, 0};
        x2 /= l;
        y2 /= l;
        z2 /= l;
        // Rot.matrix(x, y, 0)·(0, 0, −1) = (−cos x·sin y, −sin x, −cos x·cos y)
        double rx = Math.toDegrees(Math.asin(Mth.clamp(-y2, -1.0D, 1.0D)));
        double ry = Math.toDegrees(Math.atan2(-x2, -z2));
        return new double[]{rx, ry};
    }

    // ------------------------------------------------------------------ ワールドとの行き来

    /** ファイルの空間の点 → ワールド。pos = エンティティの位置(塔の根元の地面)、yaw = 体の向き(度)。 */
    public static Vec3 toWorld(double[] f, Vec3 pos, float yawDeg) {
        double left = f[0] / 16.0D, up = f[1] / 16.0D, fwd = -f[2] / 16.0D;
        float yaw = yawDeg * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        return new Vec3(pos.x + lx * left + fx * fwd, pos.y + up, pos.z + lz * left + fz * fwd);
    }

    /** ワールド → ファイルの空間。 */
    public static double[] toFile(Vec3 w, Vec3 pos, float yawDeg) {
        float yaw = yawDeg * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        double dx = w.x - pos.x, dz = w.z - pos.z;
        double left = dx * lx + dz * lz, fwd = dx * fx + dz * fz;
        return new double[]{left * 16.0D, (w.y - pos.y) * 16.0D, -fwd * 16.0D};
    }

    /** ワールドの向き → ファイルの空間の向き(長さはそのまま、単位はブロック)。 */
    public static double[] dirToFile(Vec3 d, float yawDeg) {
        float yaw = yawDeg * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        double left = d.x * lx + d.z * lz, fwd = d.x * fx + d.z * fz;
        return new double[]{left, d.y, -fwd};
    }

    /** ファイルの空間の点を、面の中の (前 r, 上 y) と面の向き yaw(度)へ。 */
    public static double[] fileToPlane(Shape sh, double[] f) {
        double x = f[0] - sh.base()[0], z = f[2] - sh.base()[2];
        double r = Math.sqrt(x * x + z * z);
        double yaw = r < 1.0E-6D ? 0.0D : Math.toDegrees(Math.atan2(-x, -z));
        return new double[]{r, f[1], yaw};
    }

    /** 首の付け根(ワールド)。 */
    public static Vec3 baseWorld(Shape sh, Vec3 pos, float yawDeg) {
        return toWorld(sh.base(), pos, yawDeg);
    }

    /** 頭の軸(ワールド)。 */
    public static Vec3 headWorld(Shape sh, Pose p, Vec3 pos, float yawDeg) {
        return toWorld(planeToFile(sh, p.tipR(), p.tipY(), p.yaw()), pos, yawDeg);
    }
}
