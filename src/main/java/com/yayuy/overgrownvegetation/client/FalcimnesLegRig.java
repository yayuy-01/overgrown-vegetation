package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.model.GeoModel;

/**
 * ファルキムネスの脚を地形に合わせて曲げる(2026-11-07、描画だけ)。足先の置き場は {@link FootPlanter}(エンティティのクライアントの tick)が決め、
 * ここはフレームごとに<b>胴の高さ・傾き</b>を足し、<b>4 本の脚を IK で置き場へ曲げる</b>。
 *
 * <h2>IK</h2>
 * 脚は 3 本の骨(太もも {@code leg*} → すね → 足先。モデル r5、{@code tools/falcimnes-anim/FalcRerig})。変数は太ももの X・Y、すねの X、足先の X の 4 つで、
 * <b>アニメの生成器({@code FalcLegs})と同じ</b>減衰付き最小二乗(数値のヤコビアン)で解き、<b>手付けの構え(このフレームのキーの値)へ弱く引く</b>。
 * だから平らな地面では手付けの待機とほぼ同じ形になり、遠くの個体(手付けの歩きのまま)と見た目が揃う。前のフレームの解から解き継ぐ。
 *
 * <h2>座標</h2>
 * GeckoLib の中の値(json の X を反転、回転の X・Y も反転)。骨の行列 = 平行移動(−posX, posY, posZ)·pivot へ·Rz·Ry·Rx·pivot から戻す(px)。
 * ワールド → モデル: 体の向き(補間)で回し、体格で割り、×16。モデルの +X = 体の右、−Z = 前。
 *
 * <h2>罠</h2>
 * 骨はモデルごとに使い回し(個体ごとではない)。書いた骨は {@link GeoBone#resetStateChanges()} で印を下ろす(下ろさないと、キーの無い骨で
 * 書いた値が次のフレームにも残って積み上がる。記憶 geckolib-custom-anim-and-easing-traps)。
 */
public final class FalcimnesLegRig {

    /** 脚ごとの骨の名前(太もも, すね, 足先)。0 = 左前, 1 = 左後, 2 = 右前, 3 = 右後({@link FalcimnesEntity#LEG_HOME_LEFT} と同じ順)。 */
    static final String[][] LEGS = {{"leg", "bone13", "bone15"}, {"leg2", "bone17", "bone19"}, {"leg3", "bone26", "bone28"}, {"leg4", "bone30", "bone32"}};
    /** 胴を傾ける中心(中の値の px)。脚の付け根の間・腰の高さ(生成器の FalcBody.HUB × 焼き込み 1.8)。 */
    static final float HUB_Y = 25.2F, HUB_Z = -0.9F;
    /** 関節の回転の合計の範囲(中の値の度。json の X の符号を反転)。生成器の FalcLegs.A_TOTAL・B_TOTAL と同じ。 */
    static final double A_MIN = 10, A_MAX = 65, B_MIN = -120, B_MAX = -10;
    /** 太ももの振れの上限(手付けの構えからの足し分、度)。 */
    static final double ROOT_X = 60, ROOT_Y = 70;
    /** 引き寄せの強さと重み(太もも X, Y, すね, 足先)。生成器の REG_* の比。 */
    static final double LAMBDA = 1e-5;
    static final double[] REG = {1.5, 1.0, 2.5, 0.6};
    /** これより遠い個体は手付けの歩きのまま(ブロック)【仮】。 */
    public static final double NEAR = 40.0D;
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    /** 首で打ち消す胴の前後の傾きの割合【仮】。 */
    static final float NECK_COUNTER = 0.5F;

    /** 足先の点(足先の骨の、静止の中の座標 px)。モデルから 1 回だけ読む。 */
    private static float[][] toe;
    /** 脚が届く長さ(付け根 → 足先、px)。伸ばしきった長さの 0.95【仮】。 */
    private static double[] reach;
    private static GeoBone toeFor;

    private FalcimnesLegRig() {
    }

    public static void apply(GeoModel<FalcimnesEntity> model, FalcimnesEntity e, AnimationState<FalcimnesEntity> state) {
        FootPlanter fp = e.footPlanter();
        float pt = state.getPartialTick();
        if (fp == null || !fp.ready()) {
            return;
        }
        float w = fp.weight(pt);
        GeoBone body = model.getAnimationProcessor().getBone("body");
        GeoBone b20 = model.getAnimationProcessor().getBone("bone20");
        if (body == null || b20 == null) {
            return;
        }
        net.minecraft.client.Camera cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        if (cam.getPosition().distanceToSqr(e.position()) < NEAR * NEAR) {
            e.markLegsRendered();   // 近くで描いた: 次の tick も手続きの足を使う
        }
        if (w <= 0.001F) {
            return;
        }
        double scale = Math.max(e.getScale(), 0.05F);

        // ---- 胴: 高さと傾き(手付けの値に足す)。傾きは腰の中心まわり(body の pivot は尾の付け根にあるので、位置のずらしで中心を移す)
        float pitch = (float) fp.bodyPitch(pt) * w, roll = (float) fp.bodyRoll(pt) * w;
        double eyI = Mth.lerp(pt, e.yo, e.getY());
        // 胴の高さはワールドで持った値(実体の y は段で跳ねるので、その差をここで打ち消す)。差は −2〜+1.2 ブロックに収める
        double liftB = Mth.clamp(fp.bodyWorldY(pt) - eyI, -2.0D * scale, 1.2D * scale);
        float lift = (float) (liftB / scale * 16.0D) * w;
        float rx = pitch, rz = -roll;   // 中の X+ = 前が上がる / 中の Z+ = 左が下がる
        // 段 3: 性格の構えの胴(FalcimnesPostureRig.body。ファイル座標: 高さ px・前後 + で前が下がる・横)。足は下の IK で地面に残る
        // 技の間は鎌と同じく消す(技のアニメは構え無しで検算してある。低い構えのまま切り上げを溜めると鎌が地面へ入る)
        float[] pb = FalcimnesPostureRig.body(e);
        float pk = w * e.postureFade * (1.0F - e.squeeze(pt));   // 段 3: 狭い所の構えの間は薄める
        lift += pb[0] * pk;
        rx += -pb[1] * Mth.DEG_TO_RAD * pk;
        rz += pb[2] * Mth.DEG_TO_RAD * pk;
        body.setRotX(body.getRotX() + rx);
        body.setRotZ(body.getRotZ() + rz);
        // 腰の中心 h のまわりに回す: 平行移動 t = (R − I)(p − h)(p = body の pivot)
        float[] d = {body.getPivotX() - 0.0F, body.getPivotY() - HUB_Y, body.getPivotZ() - HUB_Z};
        float[] rd = rotZX(d, rz, rx);
        float tx = rd[0] - d[0], ty = rd[1] - d[1], tz = rd[2] - d[2];
        body.setPosX(body.getPosX() - tx);   // 位置の X は描画で反転される(translate(−posX, …))
        body.setPosY(body.getPosY() + ty + lift);
        body.setPosZ(body.getPosZ() + tz);
        body.resetStateChanges();
        // 首は胴の傾きの半分だけ逆へ起こす(頭を水平に近く保つ。本物の動物の頭の安定化)【仮 0.5】
        GeoBone neck = model.getAnimationProcessor().getBone("neck");
        if (neck != null) {
            neck.setRotX(neck.getRotX() - rx * NECK_COUNTER);
            neck.resetStateChanges();
        }
        if (DEV_LOG && e.hasCustomName() && e.getCustomName().getString().equals("walk")) {
            // 確かめ用(テストクライアントだけ): 描画した胴のワールドの高さ・傾きを毎フレーム書く(段で胴が跳ねるかを数字で見る)
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT, "FALC_BODY %.3f %.4f %.4f %.4f %.4f",
                    e.tickCount + pt, eyI + liftB * w, pitch, roll, eyI));
        }

        // ---- body · bone20 の行列(中の値、px)。脚の付け根の親の座標へ足先を移すため
        // 段 2(11-07 夜): 体ごとの構えの根の骨 stance(上半身の層が技で動かす)を含める。含めないと踏み込みの間に足が地面から滑った
        GeoBone stance = model.getAnimationProcessor().getBone("stance");
        double[] mBody = boneMatrix(body, stance != null ? boneMatrix(stance, null) : null);
        double[] mParent = boneMatrix(b20, mBody);
        double[] inv = invertRigid(mParent);

        // ---- ワールド → モデル
        double ex = Mth.lerp(pt, e.xo, e.getX()), ey = Mth.lerp(pt, e.yo, e.getY()), ez = Mth.lerp(pt, e.zo, e.getZ());
        float yaw = Mth.rotLerp(pt, e.yBodyRotO, e.yBodyRot) * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);

        loadToes(model);
        double[][] sol = e.legSolution();
        for (int i = 0; i < LEGS.length; i++) {
            GeoBone th = model.getAnimationProcessor().getBone(LEGS[i][0]);
            GeoBone sh = model.getAnimationProcessor().getBone(LEGS[i][1]);
            GeoBone ft = model.getAnimationProcessor().getBone(LEGS[i][2]);
            if (th == null || sh == null || ft == null || toe == null) {
                continue;
            }
            Vec3 f = fp.foot(i, pt);
            double dx = f.x - ex, dy = f.y - ey, dz = f.z - ez;
            double left = dx * lx + dz * lz, fwd = dx * fx + dz * fz;
            double[] target = apply(inv, -left / scale * 16.0D, (dy - 0.01D) / scale * 16.0D, -fwd / scale * 16.0D);
            clampToReach(target, th, reach[i]);

            // 手付けの値(このフレームのキー)= 引き寄せる基準
            double[] ref = {th.getRotX(), th.getRotY(), sh.getRotX(), ft.getRotX()};
            double[] x = sol[i] != null && e.legSolutionFresh() ? sol[i].clone() : ref.clone();
            solve(x, ref, th, sh, ft, toe[i], target);
            sol[i] = x;
            th.setRotX(lerp(w, ref[0], x[0]));
            th.setRotY(lerp(w, ref[1], x[1]));
            sh.setRotX(lerp(w, ref[2], x[2]));
            ft.setRotX(lerp(w, ref[3], x[3]));
            th.resetStateChanges();
            sh.resetStateChanges();
            ft.resetStateChanges();
        }
        e.markLegSolution();
    }

    /**
     * 脚が届かない置き場を届く所へ寄せる(付け根から {@code r} px の球の中へ)。まっすぐ縮めると足が地面から浮くので、
     * <b>高さを保ったまま体の側へ寄せる</b>(高さだけで届かないときは線に沿って縮める)。
     */
    static void clampToReach(double[] t, GeoBone th, double r) {
        double hx = t[0] - th.getPivotX(), hy = t[1] - th.getPivotY(), hz = t[2] - th.getPivotZ();
        double d2 = hx * hx + hy * hy + hz * hz;
        if (d2 <= r * r) {
            return;
        }
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

    /** x = (太もも X, 太もも Y, すね X, 足先 X)(中の値、ラジアン)を解く。 */
    static void solve(double[] x, double[] ref, GeoBone th, GeoBone sh, GeoBone ft, float[] toeLocal, double[] target) {
        double[] lo = {ref[0] - Math.toRadians(ROOT_X), ref[1] - Math.toRadians(ROOT_Y), Math.toRadians(A_MIN), Math.toRadians(B_MIN)};
        double[] hi = {ref[0] + Math.toRadians(ROOT_X), ref[1] + Math.toRadians(ROOT_Y), Math.toRadians(A_MAX), Math.toRadians(B_MAX)};
        double[] J = new double[12];
        double[] A = new double[16];
        double[] g = new double[4];
        for (int it = 0; it < 8; it++) {
            double[] p = toe(x, th, sh, ft, toeLocal);
            double r0 = target[0] - p[0], r1 = target[1] - p[1], r2 = target[2] - p[2];
            if (r0 * r0 + r1 * r1 + r2 * r2 < 0.0025D && it > 0) {
                break;
            }
            for (int k = 0; k < 4; k++) {
                double keep = x[k];
                x[k] = keep + 1e-3;
                double[] q = toe(x, th, sh, ft, toeLocal);
                x[k] = keep;
                J[k] = (q[0] - p[0]) / 1e-3;
                J[4 + k] = (q[1] - p[1]) / 1e-3;
                J[8 + k] = (q[2] - p[2]) / 1e-3;
            }
            // (JᵀJ + λW) Δ = Jᵀ r − λW (x − ref)。角は度の大きさで重みを付ける(生成器と同じ物差し)
            for (int a = 0; a < 4; a++) {
                for (int b = 0; b < 4; b++) {
                    A[a * 4 + b] = J[a] * J[b] + J[4 + a] * J[4 + b] + J[8 + a] * J[8 + b];
                }
                double wReg = LAMBDA * REG[a] * (180.0D / Math.PI) * (180.0D / Math.PI);
                A[a * 4 + a] += wReg + 1e-6;
                g[a] = J[a] * r0 + J[4 + a] * r1 + J[8 + a] * r2 - wReg * (x[a] - ref[a]);
            }
            double[] step = solve4(A, g);
            if (step == null) {
                break;
            }
            // 悪くなる歩幅は半分にして試し直す(届かない所では解が前後のフレームで行ったり来たりして脚が震えた、11-07 夜)
            double before = cost(x, ref, r0, r1, r2);
            double[] keep = x.clone();
            boolean accepted = false;
            for (double s = 1.0D; s >= 0.125D; s *= 0.5D) {
                for (int k = 0; k < 4; k++) {
                    x[k] = Mth.clamp(keep[k] + Mth.clamp(step[k] * s, -0.35D, 0.35D), lo[k], hi[k]);
                }
                double[] q = toe(x, th, sh, ft, toeLocal);
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

    /** 解く量: 足先のずれの二乗 + 手付けの構えからの離れ(重み付き)。 */
    private static double cost(double[] x, double[] ref, double r0, double r1, double r2) {
        double c = r0 * r0 + r1 * r1 + r2 * r2;
        for (int a = 0; a < 4; a++) {
            double d = x[a] - ref[a];
            c += LAMBDA * REG[a] * (180.0D / Math.PI) * (180.0D / Math.PI) * d * d;
        }
        return c;
    }

    /** 変数 x での足先(太ももの親 = bone20 の座標、px)。 */
    static double[] toe(double[] x, GeoBone th, GeoBone sh, GeoBone ft, float[] toeLocal) {
        double[] m = local(th, x[0], x[1], th.getRotZ(), null);
        m = local(sh, x[2], sh.getRotY(), sh.getRotZ(), m);
        m = local(ft, x[3], ft.getRotY(), ft.getRotZ(), m);
        return apply(m, toeLocal[0], toeLocal[1], toeLocal[2]);
    }

    /** 4×4 の連立(ガウスの消去、部分ピボット)。 */
    static double[] solve4(double[] A, double[] b) {
        double[] m = A.clone();
        double[] v = b.clone();
        for (int c = 0; c < 4; c++) {
            int p = c;
            for (int r = c + 1; r < 4; r++) {
                if (Math.abs(m[r * 4 + c]) > Math.abs(m[p * 4 + c])) {
                    p = r;
                }
            }
            if (Math.abs(m[p * 4 + c]) < 1e-12) {
                return null;
            }
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
                for (int k = c; k < 4; k++) {
                    m[r * 4 + k] -= f * m[c * 4 + k];
                }
                v[r] -= f * v[c];
            }
        }
        double[] out = new double[4];
        for (int r = 3; r >= 0; r--) {
            double s = v[r];
            for (int k = r + 1; k < 4; k++) {
                s -= m[r * 4 + k] * out[k];
            }
            out[r] = s / m[r * 4 + r];
        }
        return out;
    }

    // ------------------------------------------------------------------ 行列(3×4、行優先: [r00 r01 r02 tx, r10 … ty, r20 … tz])

    static double[] boneMatrix(GeoBone b, double[] parent) {
        double[] m = local(b, b.getRotX(), b.getRotY(), b.getRotZ(), null);
        // 位置のずらし(translate(−posX, posY, posZ))を先に
        m[3] += -b.getPosX();
        m[7] += b.getPosY();
        m[11] += b.getPosZ();
        return parent == null ? m : mul(parent, m);
    }

    /** T(pivot)·Rz·Ry·Rx·T(−pivot)(位置のずらしは含めない)。parent があれば parent · それ。 */
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
                if (c == 3) {
                    s += a[r * 4 + 3];
                }
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
        // 回転は転置、平行移動は −Rᵀt
        double[] o = {m[0], m[4], m[8], 0, m[1], m[5], m[9], 0, m[2], m[6], m[10], 0};
        o[3] = -(o[0] * m[3] + o[1] * m[7] + o[2] * m[11]);
        o[7] = -(o[4] * m[3] + o[5] * m[7] + o[6] * m[11]);
        o[11] = -(o[8] * m[3] + o[9] * m[7] + o[10] * m[11]);
        return o;
    }

    /** v を Rz(z)·Rx(x) で回す(胴の傾き。Y は 0)。 */
    static float[] rotZX(float[] v, float z, float x) {
        double cx = Math.cos(x), sx = Math.sin(x);
        double y1 = v[1] * cx - v[2] * sx, z1 = v[1] * sx + v[2] * cx;
        double cz = Math.cos(z), sz = Math.sin(z);
        return new float[]{(float) (v[0] * cz - y1 * sz), (float) (v[0] * sz + y1 * cz), (float) z1};
    }

    /** 骨 b の pivot と点の距離(pivot は親の中の座標。b の回転はその点を動かさないので、子の pivot との距離は姿勢によらない)。 */
    private static double dist(GeoBone b, double x, double y, double z) {
        return Math.sqrt(sq(b.getPivotX() - x) + sq(b.getPivotY() - y) + sq(b.getPivotZ() - z));
    }

    private static double sq(double v) {
        return v * v;
    }

    /** 足先の骨の箱(先の節)の下端の中心(中の座標 px)。頂点は 1/16 ブロックで持たれているので ×16。 */
    private static void loadToes(GeoModel<FalcimnesEntity> model) {
        GeoBone first = model.getAnimationProcessor().getBone(LEGS[0][2]);
        if (first == null || first == toeFor) {
            return;
        }
        float[][] t = new float[LEGS.length][];
        double[] r = new double[LEGS.length];
        for (int i = 0; i < LEGS.length; i++) {
            GeoBone ft = model.getAnimationProcessor().getBone(LEGS[i][2]);
            if (ft == null || ft.getCubes().isEmpty()) {
                return;
            }
            GeoCube c = ft.getCubes().get(0);
            double minY = Double.MAX_VALUE;
            for (GeoQuad q : c.quads()) {
                if (q == null) continue;
                for (GeoVertex v : q.vertices()) {
                    minY = Math.min(minY, v.position().y());
                }
            }
            double sx = 0, sz = 0;
            int nv = 0;
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
            t[i] = new float[]{(float) (sx / nv * 16.0D), (float) (minY * 16.0D), (float) (sz / nv * 16.0D)};
            GeoBone th = model.getAnimationProcessor().getBone(LEGS[i][0]), sh = model.getAnimationProcessor().getBone(LEGS[i][1]);
            r[i] = 0.95D * (dist(sh, th.getPivotX(), th.getPivotY(), th.getPivotZ()) + dist(ft, sh.getPivotX(), sh.getPivotY(), sh.getPivotZ())
                    + Math.sqrt(sq(t[i][0] - ft.getPivotX()) + sq(t[i][1] - ft.getPivotY()) + sq(t[i][2] - ft.getPivotZ())));
        }
        toe = t;
        reach = r;
        toeFor = first;
    }
}
