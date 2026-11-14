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
 * 横へ張り出す虫の脚を地形に合わせて曲げる、種に依らない IK(2026-11-15、クリペイムネスのために足した。描画だけ)。
 * {@link LegChainRig}(前後に振る 2 足の 4 骨の脚)と同じ流れで、足先の置き場は {@link FootPlanter} が決め、ここはフレームごとに
 * <b>胴の高さ・傾き</b>を足し、<b>脚を IK で置き場へ曲げる</b>。LegChainRig とは別のクラスにした(ヴェナムネスの動きを変えないため)。
 *
 * <h2>LegChainRig との違い</h2>
 * <ul>
 *   <li><b>脚の骨は 3 つ</b>: 付け根(腿)→ ひざ(すね)→ 足(ふ節)。足先(ふ節の先)が地面に着く点。足の向きは保たない(ふ節はすねと一緒に動く)。</li>
 *   <li><b>変数は 4 つ</b>: 付け根の Y(前後へ振る)・付け根の Z(上げ下げ)・ひざの Z・ひざの X(後脚のようにひざの軸が斜めの脚のため)。
 *       張り出した脚は Y と Z で曲がる(2 足の脚は X)。</li>
 *   <li><b>脚ごとに親の骨の列</b>(中脚は胸、後脚は腹など。どちらも手付けのアニメで動くので、毎フレームそこまでの行列を作る)。</li>
 *   <li><b>胴の高さ・傾きを足す骨</b>を別に指定する(脚の親が 1 つでない体では、共通の祖先 = 根 root)。</li>
 *   <li><b>足先の点</b>は足の骨の箱の頂点に<b>箱の回転を掛けて</b>から一番低い所を取る(GeckoLib は箱の回転を描画のときに掛け、頂点には焼き込まない)。</li>
 * </ul>
 * 基準(引き寄せ先)は骨の初めの形(休め)。座標・行列の決まりは {@link LegChainRig} と同じ(GeckoLib の中の値。+X = 体の右、−Z = 前)。
 * 骨はモデルごとに使い回し。書いた骨は {@link GeoBone#resetStateChanges()} で印を下ろす。
 */
public final class SprawlLegRig {

    /** 脚ごとの骨の名前 {付け根, ひざ, 足}。順は {@code LongBodyRunner#legHomeLeft} と同じ。 */
    private final String[][] legs;
    /** 脚ごとの、付け根の親までの骨(根から順に)。 */
    private final String[][] parents;
    /** 胴の高さ・傾きを足す骨(全部の脚の親の祖先)。 */
    private final String body;
    private final double renderScale;
    /** 変数の範囲(基準からの足し分、度): 付け根 Y・付け根 Z・ひざ Z・ひざ X。 */
    private final double[] range;
    /** 基準へ引く重み(同じ並び)。 */
    private final double[] reg;

    static final double LAMBDA = 1e-5;

    /** 足先の点(足の骨の座標 = 休めの形のモデルの座標 px)と、脚の届く長さ(伸ばしきりの 0.97、px)。モデルから 1 回だけ読む。 */
    private double[][] sole;
    private double[] reach;
    private GeoBone soleFor;

    /** 確かめ用: IK の残り(足先と置き場の距離、ブロック)の最悪と合計・数(全個体)。 */
    public static volatile double residualWorst, residualSum;
    public static volatile long residualCount;

    public SprawlLegRig(String[][] legs, String[][] parents, String body, double renderScale, double[] range, double[] reg) {
        this.legs = legs;
        this.parents = parents;
        this.body = body;
        this.renderScale = renderScale;
        this.range = range;
        this.reg = reg;
    }

    public void apply(GeoModel<?> model, LongBodyRunner e, float pt) {
        FootPlanter fp = e.footPlanter();
        if (fp == null || !fp.ready() || e.legSolution() == null) return;
        var proc = model.getAnimationProcessor();
        GeoBone bodyBone = proc.getBone(this.body);
        if (bodyBone == null) return;
        net.minecraft.client.Camera cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        if (cam.getPosition().distanceToSqr(e.position()) < LegChainRig.NEAR * LegChainRig.NEAR) {
            e.markLegsRendered();
        }
        float w = fp.weight(pt);
        if (w <= 0.001F) return;
        double scale = Math.max(e.getScale(), 0.05F) * this.renderScale;

        // ---- 胴: 高さと傾き(手付けの値に足す)
        float pitch = (float) fp.bodyPitch(pt) * w, roll = (float) fp.bodyRoll(pt) * w;
        double eyI = Mth.lerp(pt, e.yo, e.getY());
        double liftB = Mth.clamp(fp.bodyWorldY(pt) - eyI, -2.0D * scale / this.renderScale, 1.2D * scale / this.renderScale);
        float lift = (float) (liftB / scale * 16.0D) * w;
        bodyBone.setRotX(bodyBone.getRotX() + pitch);
        bodyBone.setRotZ(bodyBone.getRotZ() - roll);
        bodyBone.setPosY(bodyBone.getPosY() + lift);
        bodyBone.resetStateChanges();

        double ex = Mth.lerp(pt, e.xo, e.getX()), ey = Mth.lerp(pt, e.yo, e.getY()), ez = Mth.lerp(pt, e.zo, e.getZ());
        float yaw = Mth.rotLerp(pt, e.yBodyRotO, e.yBodyRot) * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);

        loadSoles(model);
        if (this.sole == null) return;
        double[][] sol = e.legSolution();
        for (int i = 0; i < this.legs.length && i < fp.legs(); i++) {
            GeoBone hip = proc.getBone(this.legs[i][0]), knee = proc.getBone(this.legs[i][1]), foot = proc.getBone(this.legs[i][2]);
            if (hip == null || knee == null || foot == null) continue;
            double[] mParent = null;
            for (String n : this.parents[i]) {
                GeoBone b = proc.getBone(n);
                if (b != null) mParent = LegChainRig.boneMatrix(b, mParent);
            }
            if (mParent == null) continue;
            double[] inv = LegChainRig.invertRigid(mParent);
            Vec3 f = fp.foot(i, pt);
            double dx = f.x - ex, dy = f.y - ey, dz = f.z - ez;
            double left = dx * lx + dz * lz, fwd = dx * fx + dz * fz;
            double[] target = LegChainRig.apply(inv, -left / scale * 16.0D, (dy - 0.01D) / scale * 16.0D, -fwd / scale * 16.0D);
            double[] wanted = target.clone();
            LegChainRig.clampToReach(target, hip, this.reach[i]);

            double[] key = {hip.getRotY(), hip.getRotZ(), knee.getRotZ(), knee.getRotX()};
            double[] ref = {hip.getInitialSnapshot().getRotY(), hip.getInitialSnapshot().getRotZ(),
                    knee.getInitialSnapshot().getRotZ(), knee.getInitialSnapshot().getRotX()};
            double hipX = hip.getInitialSnapshot().getRotX(), kneeY = knee.getInitialSnapshot().getRotY();
            double[] x = sol[i] != null && sol[i].length == 4 && e.legSolutionFresh() ? sol[i].clone() : ref.clone();
            solve(x, ref, hip, knee, foot, hipX, kneeY, this.sole[i], target);
            sol[i] = x;
            if (LongBodyRunnerDev.ON) {
                double[] p = toe(x, hip, knee, foot, hipX, kneeY, this.sole[i]);
                double r = Math.sqrt(sq(p[0] - wanted[0]) + sq(p[1] - wanted[1]) + sq(p[2] - wanted[2])) * scale / 16.0D;
                residualWorst = Math.max(residualWorst, r);
                residualSum += r;
                residualCount++;
                if (r > 0.3D && e.hasCustomName() && e.getCustomName().getString().contains("ikwhy")) {
                    double reachD = Math.sqrt(sq(wanted[0] - hip.getPivotX()) + sq(wanted[1] - hip.getPivotY()) + sq(wanted[2] - hip.getPivotZ()));
                    com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                            "SPRAWL_WHY t %d 脚 %d 外れ %.2f ブロック 付け根からの距離 %.1f px(届く %.1f)宙 %s 垂らす %s 効き %.2f 回る %.1f 前へ %.3f 置き場の高さ %+.2f",
                            e.tickCount, i, r, reachD, this.reach[i], fp.swinging(i), fp.hanging(i), w, e.getTurnRate(), e.clientForwardSpeed(), dy));
                }
                if (residualCount % 2000 == 0) {
                    com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                            "SPRAWL_RES 脚 %d 回: 足先と置き場の距離 最悪 %.3f 平均 %.4f ブロック", residualCount, residualWorst, residualSum / residualCount));
                    residualWorst = 0.0D;
                    residualSum = 0.0D;
                    residualCount = 0;
                }
            }
            hip.setRotX(lerp(w, hip.getRotX(), hipX));
            hip.setRotY(lerp(w, key[0], x[0]));
            hip.setRotZ(lerp(w, key[1], x[1]));
            knee.setRotX(lerp(w, key[3], x[3]));
            knee.setRotY(lerp(w, knee.getRotY(), kneeY));
            knee.setRotZ(lerp(w, key[2], x[2]));
            foot.setRotX(lerp(w, foot.getRotX(), foot.getInitialSnapshot().getRotX()));
            foot.setRotY(lerp(w, foot.getRotY(), foot.getInitialSnapshot().getRotY()));
            foot.setRotZ(lerp(w, foot.getRotZ(), foot.getInitialSnapshot().getRotZ()));
            hip.resetStateChanges();
            knee.resetStateChanges();
            foot.resetStateChanges();
        }
        e.markLegSolution();
    }

    /** 確かめ用の旗(テストクライアント {@code -Dov.devbridge})。 */
    static final class LongBodyRunnerDev {
        static final boolean ON = Boolean.getBoolean("ov.devbridge");
    }

    private static float lerp(float w, double a, double b) {
        return (float) (a + (b - a) * w);
    }

    private static double sq(double v) {
        return v * v;
    }

    // ------------------------------------------------------------------ IK

    private void solve(double[] x, double[] ref, GeoBone hip, GeoBone knee, GeoBone foot, double hipX, double kneeY, double[] soleLocal, double[] target) {
        double[] lo = new double[4], hi = new double[4];
        for (int k = 0; k < 4; k++) {
            lo[k] = ref[k] - Math.toRadians(this.range[k]);
            hi[k] = ref[k] + Math.toRadians(this.range[k]);
        }
        double[] J = new double[12];
        double[] A = new double[16];
        double[] g = new double[4];
        for (int it = 0; it < 8; it++) {
            double[] p = toe(x, hip, knee, foot, hipX, kneeY, soleLocal);
            double r0 = target[0] - p[0], r1 = target[1] - p[1], r2 = target[2] - p[2];
            if (r0 * r0 + r1 * r1 + r2 * r2 < 0.0025D && it > 0) break;
            for (int k = 0; k < 4; k++) {
                double keep = x[k];
                x[k] = keep + 1e-3;
                double[] q = toe(x, hip, knee, foot, hipX, kneeY, soleLocal);
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
            double[] step = LegChainRig.solve4(A, g);
            if (step == null) break;
            double before = cost(x, ref, r0, r1, r2);
            double[] keep = x.clone();
            boolean accepted = false;
            for (double s = 1.0D; s >= 0.125D; s *= 0.5D) {
                for (int k = 0; k < 4; k++) {
                    x[k] = Mth.clamp(keep[k] + Mth.clamp(step[k] * s, -0.35D, 0.35D), lo[k], hi[k]);
                }
                double[] q = toe(x, hip, knee, foot, hipX, kneeY, soleLocal);
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

    /** 変数 x での足先の点(付け根の親の座標、px)。足の骨は休めの回転のまま。 */
    static double[] toe(double[] x, GeoBone hip, GeoBone knee, GeoBone foot, double hipX, double kneeY, double[] soleLocal) {
        double[] m = LegChainRig.local(hip, hipX, x[0], x[1], null);
        m = LegChainRig.local(knee, x[3], kneeY, x[2], m);
        m = LegChainRig.local(foot, foot.getInitialSnapshot().getRotX(), foot.getInitialSnapshot().getRotY(), foot.getInitialSnapshot().getRotZ(), m);
        return LegChainRig.apply(m, soleLocal[0], soleLocal[1], soleLocal[2]);
    }

    /**
     * 足先の点: 足の骨の箱の頂点(ブロック)を px にし、箱の回転(箱の pivot のまわりに Z·Y·X、GeckoLib の描画と同じ順)を掛け、一番低い頂点を取る
     * (ふ節は細長い箱を斜め下へ向けたものなので、その先の角)。届く長さ = 付け根 → ひざ → 足 → 足先の折れ線の長さの 0.97。
     */
    private void loadSoles(GeoModel<?> model) {
        var proc = model.getAnimationProcessor();
        GeoBone first = proc.getBone(this.legs[0][2]);
        if (first == null || first == this.soleFor) return;
        double[][] t = new double[this.legs.length][];
        double[] r = new double[this.legs.length];
        for (int i = 0; i < this.legs.length; i++) {
            GeoBone ft = proc.getBone(this.legs[i][2]);
            if (ft == null || ft.getCubes().isEmpty()) return;
            double[] best = null;
            for (GeoCube c : ft.getCubes()) {
                Vec3 piv = c.pivot(), rot = c.rotation();
                for (GeoQuad q : c.quads()) {
                    if (q == null) continue;
                    for (GeoVertex v : q.vertices()) {
                        double[] p = rotateZYX(v.position().x() * 16.0D - piv.x, v.position().y() * 16.0D - piv.y, v.position().z() * 16.0D - piv.z, rot);
                        p[0] += piv.x;
                        p[1] += piv.y;
                        p[2] += piv.z;
                        if (best == null || p[1] < best[1]) best = p;
                    }
                }
            }
            if (best == null) return;
            t[i] = best;
            GeoBone hip = proc.getBone(this.legs[i][0]), knee = proc.getBone(this.legs[i][1]);
            r[i] = 0.97D * (dist(hip, knee) + dist(knee, ft)
                    + Math.sqrt(sq(best[0] - ft.getPivotX()) + sq(best[1] - ft.getPivotY()) + sq(best[2] - ft.getPivotZ())));
            if (LongBodyRunnerDev.ON) {
                com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                        "SPRAWL_SOLE %s 足先 (%.2f, %.2f, %.2f) px(GeckoLib の中の値。geo の X は符号が逆)届く長さ %.2f px",
                        this.legs[i][2], best[0], best[1], best[2], r[i]));
            }
        }
        this.sole = t;
        this.reach = r;
        this.soleFor = first;
    }

    /** (x, y, z) に Rz·Ry·Rx を掛ける(X を先に)。 */
    static double[] rotateZYX(double x, double y, double z, Vec3 rot) {
        double cx = Math.cos(rot.x), sx = Math.sin(rot.x), cy = Math.cos(rot.y), sy = Math.sin(rot.y), cz = Math.cos(rot.z), sz = Math.sin(rot.z);
        double y1 = y * cx - z * sx, z1 = y * sx + z * cx;
        double x2 = x * cy + z1 * sy, z2 = -x * sy + z1 * cy;
        double x3 = x2 * cz - y1 * sz, y3 = x2 * sz + y1 * cz;
        return new double[]{x3, y3, z2};
    }

    private static double dist(GeoBone a, GeoBone b) {
        return Math.sqrt(sq(a.getPivotX() - b.getPivotX()) + sq(a.getPivotY() - b.getPivotY()) + sq(a.getPivotZ() - b.getPivotZ()));
    }
}
