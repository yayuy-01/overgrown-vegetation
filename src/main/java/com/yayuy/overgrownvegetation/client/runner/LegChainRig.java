package com.yayuy.overgrownvegetation.client.runner;

import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.model.GeoModel;

/**
 * 脚を地形に合わせて曲げる、種に依らない IK(2026-11-14、ヴェナムネスのために {@code FalcimnesLegRig} から書き起こした。描画だけ)。
 * 足先の置き場は {@link FootPlanter}(エンティティのクライアントの tick)が決め、ここはフレームごとに<b>胴の高さ・傾き</b>を足し、
 * <b>脚を IK で置き場へ曲げる</b>。
 *
 * <h2>脚の骨</h2>
 * 1 本は 4 つの骨: 付け根(太もも)→ ひざ(すね)→ 足首(ふ節)→ 足。変数は付け根の X・Z、ひざの X、足首の X の 4 つ。
 * 足は<b>手付けの構えと同じ向きを地面に対して保つ</b>(付け根・ひざ・足首の X の和の変化と胴の前後の傾きを打ち消す)。
 * 減衰付き最小二乗(数値のヤコビアン)で解き、手付けの構え(基準)へ弱く引く。基準は骨の初めの形(休めの形)か、このフレームのキーの値。
 * ファルキムネス(3 骨 + 付け根の Y)は {@code FalcimnesLegRig} のまま(まだこちらへ移していない)。
 *
 * <h2>座標</h2>
 * GeckoLib の中の値(json の X を反転、回転の X・Y も反転)。骨の行列 = 平行移動(−posX, posY, posZ)·pivot へ·Rz·Ry·Rx·pivot から戻す(px)。
 * ワールド → モデル: 体の向きで回し、(体格 × 描画倍率)で割り、×16。モデルの +X = 体の右、−Z = 前。
 *
 * <h2>罠</h2>
 * 骨はモデルごとに使い回し(個体ごとではない)。書いた骨は {@link GeoBone#resetStateChanges()} で印を下ろす
 * (下ろさないと、キーの無い骨で書いた値が次のフレームにも残って積み上がる。記憶 geckolib-custom-anim-and-easing-traps)。
 * 足先の点・届く長さはこの rig(= モデル 1 つ)が持つ(種ごとに rig を 1 つ作る)。
 */
public final class LegChainRig {

    /** 脚ごとの骨の名前 {付け根, ひざ, 足首, 足}。順は {@code LongBodyRunner#legHomeLeft} と同じ。 */
    private final String[][] legs;
    /** 脚の付け根の親までの骨(根から順に)。最後の骨が胴(高さ・傾きを足す。その pivot のまわりに傾ける)。 */
    private final String[] parents;
    /** 描画倍率(geo に焼き込まず、描画で掛けている倍率)。 */
    private final double renderScale;
    /** 変数の範囲(基準からの足し分、度): 付け根 X・付け根 Z・ひざ X・足首 X。 */
    private final double[] range;
    /** 基準へ引く重み(付け根 X・Z・ひざ・足首)。 */
    private final double[] reg;
    /** 基準: true = 骨の初めの形(休め)、false = このフレームのキーの値。 */
    private final boolean refRest;

    static final double LAMBDA = 1e-5;
    /** これより遠い個体は手付けのまま(ブロック)【仮】。 */
    public static final double NEAR = 40.0D;

    /** 足の裏の点(足の骨の箱の一番下の面の中心、モデルの中の座標 px)。モデルから 1 回だけ読む。 */
    private float[][] sole;
    private double[] reach;
    private GeoBone soleFor;

    public LegChainRig(String[][] legs, String[] parents, double renderScale, double[] range, double[] reg, boolean refRest) {
        this.legs = legs;
        this.parents = parents;
        this.renderScale = renderScale;
        this.range = range;
        this.reg = reg;
        this.refRest = refRest;
    }

    public void apply(GeoModel<?> model, LongBodyRunner e, float pt) {
        FootPlanter fp = e.footPlanter();
        if (fp == null || !fp.ready() || e.legSolution() == null) return;
        var proc = model.getAnimationProcessor();
        GeoBone body = proc.getBone(this.parents[this.parents.length - 1]);
        if (body == null) return;
        net.minecraft.client.Camera cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        if (cam.getPosition().distanceToSqr(e.position()) < NEAR * NEAR) {
            e.markLegsRendered();
        }
        float w = fp.weight(pt);
        if (w <= 0.001F) return;
        double scale = Math.max(e.getScale(), 0.05F) * this.renderScale;

        // ---- 胴: 高さと傾き(手付けの値に足す)。胴の骨の pivot のまわりに回す
        float pitch = (float) fp.bodyPitch(pt) * w, roll = (float) fp.bodyRoll(pt) * w;
        double eyI = Mth.lerp(pt, e.yo, e.getY());
        double liftB = Mth.clamp(fp.bodyWorldY(pt) - eyI, -2.0D * scale / this.renderScale, 1.2D * scale / this.renderScale);
        float lift = (float) (liftB / scale * 16.0D) * w;
        body.setRotX(body.getRotX() + pitch);
        body.setRotZ(body.getRotZ() - roll);
        body.setPosY(body.getPosY() + lift);
        body.resetStateChanges();

        double[] mParent = null;
        for (String n : this.parents) {
            GeoBone b = proc.getBone(n);
            if (b != null) mParent = boneMatrix(b, mParent);
        }
        double[] inv = invertRigid(mParent);

        double ex = Mth.lerp(pt, e.xo, e.getX()), ey = Mth.lerp(pt, e.yo, e.getY()), ez = Mth.lerp(pt, e.zo, e.getZ());
        float yaw = Mth.rotLerp(pt, e.yBodyRotO, e.yBodyRot) * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);

        loadSoles(model);
        if (this.sole == null) return;
        double[][] sol = e.legSolution();
        for (int i = 0; i < this.legs.length && i < fp.legs(); i++) {
            GeoBone th = proc.getBone(this.legs[i][0]), sh = proc.getBone(this.legs[i][1]);
            GeoBone an = proc.getBone(this.legs[i][2]), ft = proc.getBone(this.legs[i][3]);
            if (th == null || sh == null || an == null || ft == null) continue;
            Vec3 f = fp.foot(i, pt);
            double dx = f.x - ex, dy = f.y - ey, dz = f.z - ez;
            double left = dx * lx + dz * lz, fwd = dx * fx + dz * fz;
            double[] target = apply(inv, -left / scale * 16.0D, (dy - 0.01D) / scale * 16.0D, -fwd / scale * 16.0D);
            clampToReach(target, th, this.reach[i]);

            double[] key = {th.getRotX(), th.getRotZ(), sh.getRotX(), an.getRotX(), ft.getRotX()};
            double[] ref = this.refRest
                    ? new double[]{th.getInitialSnapshot().getRotX(), th.getInitialSnapshot().getRotZ(), sh.getInitialSnapshot().getRotX(),
                    an.getInitialSnapshot().getRotX(), ft.getInitialSnapshot().getRotX()}
                    : key;
            // 足の向きは基準の向きを地面に対して保つ: 足の X = 基準の足 + (基準の X の和 − 解の X の和) − 胴の前後の傾き
            double footBase = ref[4] + ref[0] + ref[2] + ref[3] - pitch + e.footPitch(i, pt) * w;
            double[] x = sol[i] != null && sol[i].length == 4 && e.legSolutionFresh() ? sol[i].clone() : new double[]{ref[0], ref[1], ref[2], ref[3]};
            solve(x, ref, th, sh, an, ft, footBase, this.sole[i], target);
            sol[i] = x;
            th.setRotX(lerp(w, key[0], x[0]));
            th.setRotZ(lerp(w, key[1], x[1]));
            sh.setRotX(lerp(w, key[2], x[2]));
            an.setRotX(lerp(w, key[3], x[3]));
            ft.setRotX(lerp(w, key[4], footBase - x[0] - x[2] - x[3]));
            th.resetStateChanges();
            sh.resetStateChanges();
            an.resetStateChanges();
            ft.resetStateChanges();
        }
        e.markLegSolution();
    }

    static void clampToReach(double[] t, GeoBone th, double r) {
        double hx = t[0] - th.getPivotX(), hy = t[1] - th.getPivotY(), hz = t[2] - th.getPivotZ();
        double d2 = hx * hx + hy * hy + hz * hz;
        if (d2 <= r * r) return;
        if (Math.abs(hy) < r * 0.98D) {
            double h = Math.sqrt(hx * hx + hz * hz), want = Math.sqrt(r * r - hy * hy);
            double k = h > 1e-6 ? want / h : 0.0D;
            t[0] = th.getPivotX() + hx * k;
            t[2] = th.getPivotZ() + hz * k;
        } else {
            double k = r / Math.sqrt(d2);
            t[0] = th.getPivotX() + hx * k;
            t[1] = th.getPivotY() + hy * k;
            t[2] = th.getPivotZ() + hz * k;
        }
    }

    private static float lerp(float w, double a, double b) {
        return (float) (a + (b - a) * w);
    }

    // ------------------------------------------------------------------ IK

    private void solve(double[] x, double[] ref, GeoBone th, GeoBone sh, GeoBone an, GeoBone ft, double footBase, float[] soleLocal, double[] target) {
        double[] lo = new double[4], hi = new double[4];
        for (int k = 0; k < 4; k++) {
            lo[k] = ref[k] - Math.toRadians(this.range[k]);
            hi[k] = ref[k] + Math.toRadians(this.range[k]);
        }
        double[] J = new double[12];
        double[] A = new double[16];
        double[] g = new double[4];
        for (int it = 0; it < 8; it++) {
            double[] p = toe(x, th, sh, an, ft, footBase, soleLocal);
            double r0 = target[0] - p[0], r1 = target[1] - p[1], r2 = target[2] - p[2];
            if (r0 * r0 + r1 * r1 + r2 * r2 < 0.0025D && it > 0) break;
            for (int k = 0; k < 4; k++) {
                double keep = x[k];
                x[k] = keep + 1e-3;
                double[] q = toe(x, th, sh, an, ft, footBase, soleLocal);
                x[k] = keep;
                J[k] = (q[0] - p[0]) / 1e-3;
                J[4 + k] = (q[1] - p[1]) / 1e-3;
                J[8 + k] = (q[2] - p[2]) / 1e-3;
            }
            for (int a = 0; a < 4; a++) {
                for (int b = 0; b < 4; b++) {
                    A[a * 4 + b] = J[a] * J[b] + J[4 + a] * J[4 + b] + J[8 + a] * J[8 + b];
                }
                double wReg = LAMBDA * this.reg[a] * (180.0D / Math.PI) * (180.0D / Math.PI);
                A[a * 4 + a] += wReg + 1e-6;
                g[a] = J[a] * r0 + J[4 + a] * r1 + J[8 + a] * r2 - wReg * (x[a] - ref[a]);
            }
            double[] step = solve4(A, g);
            if (step == null) break;
            double before = cost(x, ref, r0, r1, r2);
            double[] keep = x.clone();
            boolean accepted = false;
            for (double s = 1.0D; s >= 0.125D; s *= 0.5D) {
                for (int k = 0; k < 4; k++) {
                    x[k] = Mth.clamp(keep[k] + Mth.clamp(step[k] * s, -0.35D, 0.35D), lo[k], hi[k]);
                }
                double[] q = toe(x, th, sh, an, ft, footBase, soleLocal);
                if (cost(x, ref, target[0] - q[0], target[1] - q[1], target[2] - q[2]) < before) {
                    accepted = true;
                    break;
                }
            }
            if (!accepted) {
                System.arraycopy(keep, 0, x, 0, 4);
                break;
            }
        }
    }

    private double cost(double[] x, double[] ref, double r0, double r1, double r2) {
        double c = r0 * r0 + r1 * r1 + r2 * r2;
        for (int a = 0; a < 4; a++) {
            double d = x[a] - ref[a];
            c += LAMBDA * this.reg[a] * (180.0D / Math.PI) * (180.0D / Math.PI) * d * d;
        }
        return c;
    }

    /** 変数 x での足の裏の点(付け根の親の座標、px)。 */
    static double[] toe(double[] x, GeoBone th, GeoBone sh, GeoBone an, GeoBone ft, double footBase, float[] soleLocal) {
        double[] m = local(th, x[0], th.getRotY(), x[1], null);
        m = local(sh, x[2], sh.getRotY(), sh.getRotZ(), m);
        m = local(an, x[3], an.getRotY(), an.getRotZ(), m);
        m = local(ft, footBase - x[0] - x[2] - x[3], ft.getRotY(), ft.getRotZ(), m);
        return apply(m, soleLocal[0], soleLocal[1], soleLocal[2]);
    }

    static double[] solve4(double[] A, double[] b) {
        double[] m = A.clone();
        double[] v = b.clone();
        for (int c = 0; c < 4; c++) {
            int p = c;
            for (int r = c + 1; r < 4; r++) if (Math.abs(m[r * 4 + c]) > Math.abs(m[p * 4 + c])) p = r;
            if (Math.abs(m[p * 4 + c]) < 1e-12) return null;
            if (p != c) {
                for (int k = 0; k < 4; k++) {
                    double t = m[c * 4 + k];
                    m[c * 4 + k] = m[p * 4 + k];
                    m[p * 4 + k] = t;
                }
                double t = v[c];
                v[c] = v[p];
                v[p] = t;
            }
            for (int r = c + 1; r < 4; r++) {
                double f = m[r * 4 + c] / m[c * 4 + c];
                for (int k = c; k < 4; k++) m[r * 4 + k] -= f * m[c * 4 + k];
                v[r] -= f * v[c];
            }
        }
        double[] out = new double[4];
        for (int r = 3; r >= 0; r--) {
            double s = v[r];
            for (int k = r + 1; k < 4; k++) s -= m[r * 4 + k] * out[k];
            out[r] = s / m[r * 4 + r];
        }
        return out;
    }

    // ------------------------------------------------------------------ 行列(3×4、行優先)

    static double[] boneMatrix(GeoBone b, double[] parent) {
        double[] m = local(b, b.getRotX(), b.getRotY(), b.getRotZ(), null);
        m[3] += -b.getPosX();
        m[7] += b.getPosY();
        m[11] += b.getPosZ();
        return parent == null ? m : mul(parent, m);
    }

    static double[] local(GeoBone b, double x, double y, double z, double[] parent) {
        double cx = Math.cos(x), sx = Math.sin(x), cy = Math.cos(y), sy = Math.sin(y), cz = Math.cos(z), sz = Math.sin(z);
        double r00 = cz * cy, r01 = cz * sy * sx - sz * cx, r02 = cz * sy * cx + sz * sx;
        double r10 = sz * cy, r11 = sz * sy * sx + cz * cx, r12 = sz * sy * cx - cz * sx;
        double r20 = -sy, r21 = cy * sx, r22 = cy * cx;
        double px = b.getPivotX(), py = b.getPivotY(), pz = b.getPivotZ();
        double[] m = {
                r00, r01, r02, px - (r00 * px + r01 * py + r02 * pz),
                r10, r11, r12, py - (r10 * px + r11 * py + r12 * pz),
                r20, r21, r22, pz - (r20 * px + r21 * py + r22 * pz)};
        return parent == null ? m : mul(parent, m);
    }

    static double[] mul(double[] a, double[] b) {
        double[] o = new double[12];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 4; c++) {
                double s = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c];
                if (c == 3) s += a[r * 4 + 3];
                o[r * 4 + c] = s;
            }
        }
        return o;
    }

    static double[] apply(double[] m, double x, double y, double z) {
        return new double[]{
                m[0] * x + m[1] * y + m[2] * z + m[3],
                m[4] * x + m[5] * y + m[6] * z + m[7],
                m[8] * x + m[9] * y + m[10] * z + m[11]};
    }

    static double[] invertRigid(double[] m) {
        double[] o = {m[0], m[4], m[8], 0, m[1], m[5], m[9], 0, m[2], m[6], m[10], 0};
        o[3] = -(o[0] * m[3] + o[1] * m[7] + o[2] * m[11]);
        o[7] = -(o[4] * m[3] + o[5] * m[7] + o[6] * m[11]);
        o[11] = -(o[8] * m[3] + o[9] * m[7] + o[10] * m[11]);
        return o;
    }

    private static double dist(GeoBone b, double x, double y, double z) {
        return Math.sqrt(sq(b.getPivotX() - x) + sq(b.getPivotY() - y) + sq(b.getPivotZ() - z));
    }

    private static double sq(double v) {
        return v * v;
    }

    /** 足の骨の箱の一番下の面の中心(全部の箱で一番低い頂点の高さにある頂点の平均)と、脚の届く長さ(伸ばしきりの 0.95)。 */
    private void loadSoles(GeoModel<?> model) {
        var proc = model.getAnimationProcessor();
        GeoBone first = proc.getBone(this.legs[0][3]);
        if (first == null || first == this.soleFor) return;
        float[][] t = new float[this.legs.length][];
        double[] r = new double[this.legs.length];
        for (int i = 0; i < this.legs.length; i++) {
            GeoBone ft = proc.getBone(this.legs[i][3]);
            if (ft == null || ft.getCubes().isEmpty()) return;
            double minY = Double.MAX_VALUE;
            for (GeoCube c : ft.getCubes()) {
                for (GeoQuad q : c.quads()) {
                    if (q == null) continue;
                    for (GeoVertex v : q.vertices()) minY = Math.min(minY, v.position().y());
                }
            }
            double sx = 0, sz = 0;
            int nv = 0;
            for (GeoCube c : ft.getCubes()) {
                for (GeoQuad q : c.quads()) {
                    if (q == null) continue;
                    for (GeoVertex v : q.vertices()) {
                        if (v.position().y() < minY + 1e-4) {
                            sx += v.position().x();
                            sz += v.position().z();
                            nv++;
                        }
                    }
                }
            }
            t[i] = new float[]{(float) (sx / nv * 16.0D), (float) (minY * 16.0D), (float) (sz / nv * 16.0D)};
            GeoBone th = proc.getBone(this.legs[i][0]), sh = proc.getBone(this.legs[i][1]), an = proc.getBone(this.legs[i][2]);
            r[i] = 0.95D * (dist(sh, th.getPivotX(), th.getPivotY(), th.getPivotZ()) + dist(an, sh.getPivotX(), sh.getPivotY(), sh.getPivotZ())
                    + dist(ft, an.getPivotX(), an.getPivotY(), an.getPivotZ())
                    + Math.sqrt(sq(t[i][0] - ft.getPivotX()) + sq(t[i][1] - ft.getPivotY()) + sq(t[i][2] - ft.getPivotZ())));
        }
        this.sole = t;
        this.reach = r;
        this.soleFor = first;
    }
}
