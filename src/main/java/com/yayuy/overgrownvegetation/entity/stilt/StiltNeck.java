package com.yayuy.overgrownvegetation.entity.stilt;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 竹馬型の首(2026-11-14、段 2「首の動き」。サーバーとクライアントで同じ計算)。GeckoLib の型を使わない純粋な計算。
 *
 * <p>首は 4 節の入れ子(フィロセンス r2: neck_1〜neck_4、1 節 16 単位、縮めると neck_2〜4 が 11 ずつ重なる)の先に頭が付く。
 * 4 つの関節を<b>同じ回り Q で曲げる</b>(滑らかな弧になる)。弧の弦が「頭を置きたい点」を向くように Q を決め、伸び {@code s}(0 = 縮める、
 * 1 = 伸ばしきる)で長さを合わせる。最後に頭を回して、花の見る軸を「見たい点」へ向ける。
 *
 * <h2>座標</h2>
 * 「胴の中」= body の骨の子の空間(GeckoLib の中の座標。前 = −Z・右 = +X・上 = +Y、モデル単位)。胴そのものは root の pivot まわりに
 * 前へ傾いている(静止 25°・覗くときはさらに {@code lean})。ワールドとの行き来は {@link #toWorld}・{@link #toBody}。
 * サーバーはキーのアニメ(歩きの揺れ)を知らないので、胴の傾きは静止の値 + lean で近似する。
 */
public final class StiltNeck {

    /** 種ごとの形(モデル単位)。 */
    public record Shape(double[] bodyPivot, double bodyRestTiltDeg, double[] neckBase, double seg, double telescope,
                        double[] viewAxis, double rootShift, double maxBendDeg) {
        /** 伸び s のときの付け根から頭の pivot までの長さ(まっすぐのとき)。 */
        public double length(double s) {
            return this.seg * 4.0D - this.telescope * 3.0D * (1.0D - s);
        }
    }

    /** フィロセンス r2 の形。花の見る軸 = head の pivot (0,207,−1) から core の pivot (0,212,−6) への向き(上と前へ 45°)。 */
    public static final Shape PHYLLOSENS = new Shape(new double[]{0, 104, 0}, 25.0D, new double[]{0, 143, -1}, 16.0D, 11.0D,
            normalize(new double[]{0, 5, -5}), 104.0D - 12.0D, 150.0D);

    /** 解いた首の形。q = 1 関節ぶんの回り(行列 3×3)・s = 伸び・headRot = 頭の回り(行列)・head = 頭の pivot(胴の中)。 */
    public record Pose(double[] q, double s, double[] headRot, double[] head) {
        /** GeckoLib の角度(x, y, z)。 */
        public double[] qEuler() {
            return StiltLegIK.euler(this.q);
        }

        public double[] headEuler() {
            return StiltLegIK.euler(this.headRot);
        }
    }

    private StiltNeck() {
    }

    /**
     * 頭の pivot を {@code want}(胴の中)へ置き、花の見る軸を {@code look}(胴の中)へ向ける首の形。届かなければ届く所まで。
     */
    public static Pose solve(Shape sh, double[] want, double[] look) {
        double[] base = sh.neckBase();
        double[] c = StiltLegIK.sub(want, base);
        double dist = StiltLegIK.len(c);
        double[] up = {0, 1, 0};
        double[] dir = dist < 1.0E-6D ? up : StiltLegIK.scale(c, 1.0D / dist);
        // 弦の向き: 静止の首(真上)から dir への回りの軸と角
        double[] axis = StiltLegIK.cross(up, dir);
        double theta = Math.acos(Mth.clamp(StiltLegIK.dot(up, dir), -1.0D, 1.0D));
        if (StiltLegIK.len(axis) < 1.0E-6D) {
            axis = new double[]{1, 0, 0};
        } else {
            axis = StiltLegIK.scale(axis, 1.0D / StiltLegIK.len(axis));
        }
        theta = Math.min(theta, Math.toRadians(sh.maxBendDeg()));
        // 弧は 4 関節で同じだけ曲げる: 弦は 1 関節の角 a の約 2.5 倍の向き。FK で数回合わせる
        double a = theta / 2.5D, s = 1.0D;
        for (int it = 0; it < 4; it++) {
            double[] q = rot(axis, a);
            double l1 = tipLength(sh, q, 1.0D), l0 = tipLength(sh, q, 0.0D);
            s = Mth.clamp(l1 - l0 < 1.0E-6D ? 1.0D : (dist - l0) / (l1 - l0), 0.0D, 1.0D);
            double[] tip = StiltLegIK.sub(tip(sh, q, s), base);
            double got = Math.acos(Mth.clamp(StiltLegIK.dot(up, unit(tip)), -1.0D, 1.0D));
            a += (theta - got) / 2.5D;
        }
        double[] q = rot(axis, a);
        double[] head = tip(sh, q, s);
        // 頭: 首の先の向き Q⁴ の中で、見る軸を look へ
        double[] q4 = StiltLegIK.matmul(StiltLegIK.matmul(q, q), StiltLegIK.matmul(q, q));
        double[] toLook = unit(StiltLegIK.sub(look, head));
        double[] local = StiltLegIK.mul(StiltLegIK.transpose(q4), toLook);
        double[] headRot = StiltLegIK.arc(sh.viewAxis(), local);
        return new Pose(q, s, headRot, head);
    }

    /** 首を q で曲げ、伸び s のときの頭の pivot(胴の中)。 */
    public static double[] tip(Shape sh, double[] q, double s) {
        double l = sh.seg() - sh.telescope() * (1.0D - s);
        double[] p = sh.neckBase().clone();
        double[] m = q;
        double[] lens = {l, l, l, sh.seg()};
        for (int i = 0; i < 4; i++) {
            p = StiltLegIK.add(p, StiltLegIK.mul(m, new double[]{0, lens[i], 0}));
            m = StiltLegIK.matmul(m, q);
        }
        return p;
    }

    private static double tipLength(Shape sh, double[] q, double s) {
        return StiltLegIK.len(StiltLegIK.sub(tip(sh, q, s), sh.neckBase()));
    }

    /** 軸 a(単位)まわりに角 t の回り(ロドリゲス)。 */
    static double[] rot(double[] a, double t) {
        double c = Math.cos(t), s = Math.sin(t), k = 1.0D - c, x = a[0], y = a[1], z = a[2];
        return new double[]{
                c + x * x * k, x * y * k - z * s, x * z * k + y * s,
                y * x * k + z * s, c + y * y * k, y * z * k - x * s,
                z * x * k - y * s, z * y * k + x * s, c + z * z * k};
    }

    static double[] unit(double[] v) {
        double l = StiltLegIK.len(v);
        return l < 1.0E-9D ? new double[]{0, 1, 0} : StiltLegIK.scale(v, 1.0D / l);
    }

    static double[] normalize(double[] v) {
        return unit(v);
    }

    // ------------------------------------------------------------------ ワールドとの行き来

    /** 胴の傾きの行列(root の pivot まわり、GeckoLib の中の X 回り)。静止の前傾 + lean(度、+ で前へ倒す)。 */
    private static double[] bodyTilt(Shape sh, double leanDeg) {
        return StiltLegIK.matrix(-Math.toRadians(sh.bodyRestTiltDeg() + leanDeg), 0.0D, 0.0D);
    }

    /** 胴の中の点 → ワールド。pos = エンティティの位置(胴の箱の底)、yaw = 体の向き(度)。 */
    public static Vec3 toWorld(Shape sh, double[] p, Vec3 pos, float yawDeg, double leanDeg) {
        double[] r = StiltLegIK.add(sh.bodyPivot(), StiltLegIK.mul(bodyTilt(sh, leanDeg), StiltLegIK.sub(p, sh.bodyPivot())));
        double left = -r[0] / 16.0D, fwd = -r[2] / 16.0D, upB = (r[1] - sh.rootShift()) / 16.0D;
        float yaw = yawDeg * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        return new Vec3(pos.x + lx * left + fx * fwd, pos.y + upB, pos.z + lz * left + fz * fwd);
    }

    /** ワールド → 胴の中。 */
    public static double[] toBody(Shape sh, Vec3 w, Vec3 pos, float yawDeg, double leanDeg) {
        float yaw = yawDeg * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        double dx = w.x - pos.x, dz = w.z - pos.z;
        double left = dx * lx + dz * lz, fwd = dx * fx + dz * fz;
        double[] r = {-left * 16.0D, (w.y - pos.y) * 16.0D + sh.rootShift(), -fwd * 16.0D};
        return StiltLegIK.add(sh.bodyPivot(), StiltLegIK.mul(StiltLegIK.transpose(bodyTilt(sh, leanDeg)), StiltLegIK.sub(r, sh.bodyPivot())));
    }

    /** 首の付け根(ワールド)。 */
    public static Vec3 baseWorld(Shape sh, Vec3 pos, float yawDeg, double leanDeg) {
        return toWorld(sh, sh.neckBase(), pos, yawDeg, leanDeg);
    }

    /** 首を伸ばしきったときに頭が届く距離(ブロック)。 */
    public static double reach(Shape sh) {
        return sh.length(1.0D) / 16.0D;
    }

    /** 縮めたときの長さ(ブロック)。 */
    public static double minReach(Shape sh) {
        return sh.length(0.0D) / 16.0D;
    }
}
