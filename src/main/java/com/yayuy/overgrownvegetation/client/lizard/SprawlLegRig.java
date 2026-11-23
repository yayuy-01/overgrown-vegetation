package com.yayuy.overgrownvegetation.client.lizard;

import com.yayuy.overgrownvegetation.client.runner.LegChainRig;
import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import com.yayuy.overgrownvegetation.entity.lizard.LizardRunner;
import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.model.GeoModel;

/**
 * 横へ張った脚(這う爬虫類の脚)を地形に合わせて曲げる、種に依らない IK(2026-11-15、トカゲ型のために書き起こした。描画だけ)。
 * 足先の置き場は {@link FootPlanter}(エンティティのクライアントの tick)が決め、ここはフレームごとに<b>胴の高さ・傾き</b>を足し、
 * <b>脚を IK で置き場へ曲げる</b>。二足の柱のような脚の {@link LegChainRig} と違い、脚は付け根から横へ張り出し、上の骨が水平に振れて足を前後へ運ぶ。
 *
 * <h2>脚の骨</h2>
 * 1 本は 2 骨(上腕 → 前腕。足の骨が無く、前腕の先の爪が足の裏)か 3 骨(太もも → すね → 足)。変数は 4 つ:
 * 付け根の Y(水平に振る = 足を前後へ)・付け根の Z(体の前後の軸まわり = 脚を上げ下げ)・2 つ目の X(先を前後へ傾ける)・2 つ目の Z(先を内外へ)。
 * 3 骨の足は、2 つ目の X と 付け根・2 つ目の Z の足し分を打ち消して、地面に対する向きを保つ。
 * 減衰付き最小二乗(数値のヤコビアン)で解き、休めの形(骨の初めの形)へ弱く引く。
 *
 * <h2>箱の回転</h2>
 * この型のモデルは脚の<b>骨は回さず、箱を回して</b>横へ張らせてある(prac2_g5_lancer 系)。足の裏の点は、箱の回転(GeckoLib の焼き込み後の値:
 * pivot は px、回転は Rz·Ry·Rx)を頂点に掛けてから一番低い所を取る。
 *
 * <h2>座標と罠</h2>
 * {@link LegChainRig} と同じ(GeckoLib の中の値・モデルの +X = 体の右・−Z = 前、書いた骨は {@link GeoBone#resetStateChanges()})。
 */
public final class SprawlLegRig {

    /** 脚ごとの骨の名前(付け根から先へ、2 か 3)。順は {@code LongBodyRunner#legHomeLeft}(左前・右前・左後ろ・右後ろ)と同じ。 */
    private final String[][] legs;
    /** 脚ごとの、付け根の親までの骨(根から順に)。 */
    private final String[][] parents;
    /** 高さ・傾きを足す胴の骨。 */
    private final String body;
    private final double renderScale;
    /** 変数の範囲(休めからの足し分、度): 付け根 Y・付け根 Z・2 つ目 X・2 つ目 Z。 */
    private final double[] range;
    /** 休めへ引く重み(同じ並び)。 */
    private final double[] reg;

    static final double LAMBDA = 1e-5;
    /** これより遠い個体は手付けのまま(ブロック)【仮】。トカゲ型は体が低く横に広いので、足の置き方が遠くからも見える。 */
    public static final double NEAR = 48.0D;

    private float[][] sole;
    private double[] reach;
    private GeoBone soleFor;

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
        GeoBone trunk = (software.bernie.geckolib.cache.object.GeoBone) proc.getBone(this.body);
        if (trunk == null) return;
        net.minecraft.client.Camera cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        if (cam.getPosition().distanceToSqr(e.position()) < NEAR * NEAR) {
            e.markLegsRendered();
        }
        float w = fp.weight(pt);
        if (w <= 0.001F) return;
        double scale = Math.max(e.getScale(), 0.05F) * this.renderScale;

        // ---- 胴: 高さと傾き(手付けの値に足す)
        float pitch = (float) fp.bodyPitch(pt) * w, roll = (float) fp.bodyRoll(pt) * w;
        double eyI = Mth.lerp(pt, e.yo, e.getY());
        double crouch = e instanceof LizardRunner lz ? lz.crouch(pt) : 0.0D;   // 動いている間は胴を低く(這う)
        double liftB = Mth.clamp(fp.bodyWorldY(pt) - eyI - crouch, -1.5D * scale / this.renderScale, 1.0D * scale / this.renderScale);
        float lift = (float) (liftB / scale * 16.0D) * w;
        trunk.setRotX(trunk.getRotX() + pitch);
        trunk.setRotZ(trunk.getRotZ() - roll);
        trunk.setPosY(trunk.getPosY() + lift);
        trunk.resetStateChanges();

        double ex = Mth.lerp(pt, e.xo, e.getX()), ey = Mth.lerp(pt, e.yo, e.getY()), ez = Mth.lerp(pt, e.zo, e.getZ());
        float yaw = Mth.rotLerp(pt, e.yBodyRotO, e.yBodyRot) * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);

        loadSoles(model);
        if (this.sole == null) return;
        double[][] sol = e.legSolution();
        for (int i = 0; i < this.legs.length && i < fp.legs(); i++) {
            String[] names = this.legs[i];
            GeoBone a = (software.bernie.geckolib.cache.object.GeoBone) proc.getBone(names[0]), b = (software.bernie.geckolib.cache.object.GeoBone) proc.getBone(names[1]);
            GeoBone c = names.length > 2 ? (software.bernie.geckolib.cache.object.GeoBone) proc.getBone(names[2]) : null;
            if (a == null || b == null || (names.length > 2 && c == null)) continue;
            double[] mParent = null;
            for (String n : this.parents[i]) {
                GeoBone p = (software.bernie.geckolib.cache.object.GeoBone) proc.getBone(n);
                if (p != null) mParent = LegChainRig.boneMatrix(p, mParent);
            }
            if (mParent == null) continue;
            double[] inv = LegChainRig.invertRigid(mParent);

            Vec3 f = fp.foot(i, pt);
            double dx = f.x - ex, dy = f.y - ey, dz = f.z - ez;
            double left = dx * lx + dz * lz, fwd = dx * fx + dz * fz;
            double[] target = LegChainRig.apply(inv, -left / scale * 16.0D, (dy - 0.01D) / scale * 16.0D, -fwd / scale * 16.0D);
            double[] wanted = target.clone();
            LegChainRig.clampToReach(target, a, this.reach[i]);

            double[] key = {a.getRotY(), a.getRotZ(), b.getRotX(), b.getRotZ()};
            double[] ref = {a.getInitialSnapshot().getRotY(), a.getInitialSnapshot().getRotZ(), b.getInitialSnapshot().getRotX(), b.getInitialSnapshot().getRotZ()};
            double[] footRef = c == null ? null : new double[]{c.getInitialSnapshot().getRotX() - pitch, c.getInitialSnapshot().getRotZ(), c.getInitialSnapshot().getRotY()};
            double[] footKey = c == null ? null : new double[]{c.getRotX(), c.getRotZ(), c.getRotY()};
            double[] x = sol[i] != null && sol[i].length == 4 && e.legSolutionFresh() ? sol[i].clone() : ref.clone();
            solve(x, ref, a, b, c, footRef, this.sole[i], target);
            sol[i] = x;
            if (DEV_HOOKS && e.hasCustomName() && e.getCustomName().getString().contains("reach")) {
                double[] got = toe(x, ref, a, b, c, footRef, this.sole[i]);
                double miss = Math.sqrt(sq(got[0] - wanted[0]) + sq(got[1] - wanted[1]) + sq(got[2] - wanted[2])) / 16.0D * scale;
                if (miss > this.missMax[i] && i < 2) {
                    // 前脚だけ: 外れたときの足の置き場(体の前へ・上へ、ブロック)・届かず縮めたか・宙か・解の角(度)
                    double clampD = Math.sqrt(sq(target[0] - wanted[0]) + sq(target[1] - wanted[1]) + sq(target[2] - wanted[2])) / 16.0D * scale;
                    this.missWhy[i] = String.format(java.util.Locale.ROOT, "[前 %+.2f 上 %+.2f 縮め %.2f %s Y %.0f Z %.0f X2 %.0f Z2 %.0f]", fwd, dy, clampD,
                            fp.swinging(i) ? "宙" : "着", Math.toDegrees(x[0]), Math.toDegrees(x[1]), Math.toDegrees(x[2]), Math.toDegrees(x[3]));
                }
                this.missMax[i] = Math.max(this.missMax[i], miss);
            }
            a.setRotY(lerp(w, key[0], x[0]));
            a.setRotZ(lerp(w, key[1], x[1]));
            b.setRotX(lerp(w, key[2], x[2]));
            b.setRotZ(lerp(w, key[3], x[3]));
            a.resetStateChanges();
            b.resetStateChanges();
            if (c != null) {
                double[] fr = foot(x, ref, footRef);
                c.setRotX(lerp(w, footKey[0], fr[0]));
                c.setRotZ(lerp(w, footKey[1], fr[1]));
                c.setRotY(lerp(w, footKey[2], fr[2]));
                c.resetStateChanges();
            }
        }
        e.markLegSolution();
        if (DEV_HOOKS && e.tickCount != this.missTick && e.hasCustomName() && e.getCustomName().getString().contains("reach")) {
            // 確かめ用: 前の tick に描いたフレームのうち、足の裏が置き場から一番離れた距離(ブロック、脚ごと)。届いていれば 0 に近い
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "LIZ_REACH t %d 速さ %.3f 低く %.2f 外れ 左前 %.3f 右前 %.3f 左後 %.3f 右後 %.3f", e.tickCount, e.clientForwardSpeed(), crouch,
                    this.missMax[0], this.missMax[1], this.missMax[2], this.missMax[3]) + " " + this.missWhy[0] + " " + this.missWhy[1]);
            java.util.Arrays.fill(this.missMax, 0.0D);
            java.util.Arrays.fill(this.missWhy, "");
            this.missTick = e.tickCount;
        }
    }

    private static final boolean DEV_HOOKS = Boolean.getBoolean("ov.devbridge");
    private final double[] missMax = new double[4];
    private final String[] missWhy = {"", ""};
    private int missTick = -1;

    /** 3 骨の足の X・Z・Y: 休めの向きから、上の骨で傾いた分と付け根の水平の振りを打ち消す(着いている足が付け根と一緒に回らない。11-15 段 2)。 */
    private static double[] foot(double[] x, double[] ref, double[] footRef) {
        return new double[]{footRef[0] - (x[2] - ref[2]), footRef[1] - (x[1] - ref[1]) - (x[3] - ref[3]), footRef[2] - (x[0] - ref[0])};
    }

    private static float lerp(float w, double a, double b) {
        return (float) (a + (b - a) * w);
    }

    // ------------------------------------------------------------------ IK

    private void solve(double[] x, double[] ref, GeoBone a, GeoBone b, GeoBone c, double[] footRef, float[] soleLocal, double[] target) {
        double[] lo = new double[4], hi = new double[4];
        for (int k = 0; k < 4; k++) {
            lo[k] = ref[k] - Math.toRadians(this.range[k]);
            hi[k] = ref[k] + Math.toRadians(this.range[k]);
        }
        double[] J = new double[12];
        double[] A = new double[16];
        double[] g = new double[4];
        for (int it = 0; it < 8; it++) {
            double[] p = toe(x, ref, a, b, c, footRef, soleLocal);
            double r0 = target[0] - p[0], r1 = target[1] - p[1], r2 = target[2] - p[2];
            if (r0 * r0 + r1 * r1 + r2 * r2 < 0.0025D && it > 0) break;
            for (int k = 0; k < 4; k++) {
                double keep = x[k];
                x[k] = keep + 1e-3;
                double[] q = toe(x, ref, a, b, c, footRef, soleLocal);
                x[k] = keep;
                J[k] = (q[0] - p[0]) / 1e-3;
                J[4 + k] = (q[1] - p[1]) / 1e-3;
                J[8 + k] = (q[2] - p[2]) / 1e-3;
            }
            for (int r = 0; r < 4; r++) {
                for (int s = 0; s < 4; s++) {
                    A[r * 4 + s] = J[r] * J[s] + J[4 + r] * J[4 + s] + J[8 + r] * J[8 + s];
                }
                double wReg = LAMBDA * this.reg[r] * (180.0D / Math.PI) * (180.0D / Math.PI);
                A[r * 4 + r] += wReg + 1e-6;
                g[r] = J[r] * r0 + J[4 + r] * r1 + J[8 + r] * r2 - wReg * (x[r] - ref[r]);
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
                double[] q = toe(x, ref, a, b, c, footRef, soleLocal);
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
        double cst = r0 * r0 + r1 * r1 + r2 * r2;
        for (int k = 0; k < 4; k++) {
            double d = x[k] - ref[k];
            cst += LAMBDA * this.reg[k] * (180.0D / Math.PI) * (180.0D / Math.PI) * d * d;
        }
        return cst;
    }

    /** 変数 x での足の裏の点(付け根の親の座標、px)。 */
    private static double[] toe(double[] x, double[] ref, GeoBone a, GeoBone b, GeoBone c, double[] footRef, float[] soleLocal) {
        double[] m = LegChainRig.local(a, a.getRotX(), x[0], x[1], null);
        m = LegChainRig.local(b, x[2], b.getRotY(), x[3], m);
        if (c != null) {
            double[] fr = foot(x, ref, footRef);
            m = LegChainRig.local(c, fr[0], fr[2], fr[1], m);
        }
        return LegChainRig.apply(m, soleLocal[0], soleLocal[1], soleLocal[2]);
    }

    // ------------------------------------------------------------------ 足の裏

    /**
     * 足の裏の点(脚の最後の骨の箱を回転させた頂点のうち一番低い高さにあるものの平均、px)と、届く長さ(骨の付け根どうしの距離 + 最後の付け根から足の裏、の 0.95)。
     * モデルから 1 回だけ読む。
     */
    private void loadSoles(GeoModel<?> model) {
        var proc = model.getAnimationProcessor();
        String[] firstLeg = this.legs[0];
        GeoBone first = (software.bernie.geckolib.cache.object.GeoBone) proc.getBone(firstLeg[firstLeg.length - 1]);
        if (first == null || first == this.soleFor) return;
        float[][] t = new float[this.legs.length][];
        double[] r = new double[this.legs.length];
        for (int i = 0; i < this.legs.length; i++) {
            String[] names = this.legs[i];
            GeoBone last = (software.bernie.geckolib.cache.object.GeoBone) proc.getBone(names[names.length - 1]);
            if (last == null || last.getCubes().isEmpty()) return;
            java.util.List<double[]> pts = new java.util.ArrayList<>();
            double minY = Double.MAX_VALUE;
            for (GeoCube cube : last.getCubes()) {
                for (GeoQuad q : cube.quads()) {
                    if (q == null) continue;
                    for (GeoVertex v : q.vertices()) {
                        double[] p = cubeVertexPx(cube, v);
                        pts.add(p);
                        minY = Math.min(minY, p[1]);
                    }
                }
            }
            double sx = 0, sz = 0;
            int nv = 0;
            for (double[] p : pts) {
                if (p[1] < minY + 0.05D) {
                    sx += p[0];
                    sz += p[2];
                    nv++;
                }
            }
            t[i] = new float[]{(float) (sx / nv), (float) minY, (float) (sz / nv)};
            double len = 0.0D;
            for (int k = 1; k < names.length; k++) {
                GeoBone p0 = (software.bernie.geckolib.cache.object.GeoBone) proc.getBone(names[k - 1]), p1 = (software.bernie.geckolib.cache.object.GeoBone) proc.getBone(names[k]);
                len += Math.sqrt(sq(p1.getPivotX() - p0.getPivotX()) + sq(p1.getPivotY() - p0.getPivotY()) + sq(p1.getPivotZ() - p0.getPivotZ()));
            }
            len += Math.sqrt(sq(t[i][0] - last.getPivotX()) + sq(t[i][1] - last.getPivotY()) + sq(t[i][2] - last.getPivotZ()));
            r[i] = 0.95D * len;
        }
        this.sole = t;
        this.reach = r;
        this.soleFor = first;
    }

    /** 箱の頂点(ブロック)を箱の回転で回し、px にする(GeckoLib の描画と同じ: pivot へ・Rz·Ry·Rx・pivot から戻す)。 */
    static double[] cubeVertexPx(GeoCube cube, GeoVertex v) {
        double px = v.position().x() * 16.0D, py = v.position().y() * 16.0D, pz = v.position().z() * 16.0D;
        Vec3 rot = cube.rotation(), pv = cube.pivot();
        if (rot == null || pv == null || rot.lengthSqr() < 1e-12) return new double[]{px, py, pz};
        double x = px - pv.x, y = py - pv.y, z = pz - pv.z;
        // Rx
        double cx = Math.cos(rot.x), sx = Math.sin(rot.x);
        double y1 = y * cx - z * sx, z1 = y * sx + z * cx;
        // Ry
        double cy = Math.cos(rot.y), sy = Math.sin(rot.y);
        double x2 = x * cy + z1 * sy, z2 = -x * sy + z1 * cy;
        // Rz
        double cz = Math.cos(rot.z), sz = Math.sin(rot.z);
        double x3 = x2 * cz - y1 * sz, y3 = x2 * sz + y1 * cz;
        return new double[]{x3 + pv.x, y3 + pv.y, z2 + pv.z};
    }

    private static double sq(double v) {
        return v * v;
    }
}
