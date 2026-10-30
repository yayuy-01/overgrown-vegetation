package com.yayuy.overgrownvegetation.client.skyboss;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.skyboss.SkyBossHang;
import com.yayuy.overgrownvegetation.skyboss.SkyBossPose;
import com.yayuy.overgrownvegetation.skyboss.SkyBossShapes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 空の巨大生物の GeckoLib モデル(2026-10-18)。形・アニメ・絵は {@code tools/g5-models}(build.sh SkyGlaucus)の書き出しを素材のフォルダへ写したもの。
 * <ul>
 *   <li>{@code geo/sky_glaucus.geo.json}(箱 1592・骨 946)</li>
 *   <li>{@code animations/sky_glaucus.animation.json}(54 本)</li>
 *   <li>{@code textures/entity/sky_glaucus.png}(2048×1024)</li>
 * </ul>
 *
 * <h2>頭の道をなぞる曲がり</h2>
 * {@link SkyBossRenderer} が毎フレーム、道の上の節の向きから「親の節に対する曲がり」(頭・b02〜b18・尾。b01 はモデル全体の向き)(GeckoLib の骨の回転 Z·Y·X、ラジアン)を出して {@link #BENDS} に置き、
 * ここでアニメの値に<b>足す</b>。足してよいのは、体の層(body)のアニメが体の骨を毎フレームキーしていて、GeckoLib が毎フレーム値を書き直すから
 * (キーの無い骨に足すと積み上がって歪む。{@code OvergrownNexusModel} の javadoc)。
 *
 * <h2>弱点(10-18 夜)</h2>
 * 避雷針が壊れた後の体の反動({@code skyboss/SkyBossRecoil})も同じく足す(b01 の回転と body の位置も、うねりが毎フレームキーしている)。
 * 壊れた蕾は骨ごと隠し(子の花弁も)、生え直しの間は大きさを 0 から 1 へ。蕾の骨はどのアニメもキーしていないので、GeckoLib が毎フレーム元の大きさへ戻す → ここで毎フレーム書く。
 *
 * <h2>背の垂れ草(10-19)</h2>
 * 板 74 枚を節の向きから出した真下へ倒す(背が上では脇へ寝て垂れる、旋回の傾きでも振れる)。蕾と同じく毎フレーム書く。{@link #hangs}
 */
public final class SkyBossModel extends GeoModel<SkyBossImpostor> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/sky_glaucus.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/sky_glaucus.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/sky_glaucus.animation.json");

    /** 曲げる骨の名前と、そのフレームの曲がり {x, y, z}(ラジアン)。{@link SkyBossRenderer} が書く。 */
    static final String[] BEND_BONES;
    static final float[][] BENDS;
    /**
     * 腹 ⇄ 背を転がる間、口の触手が重力で垂れる(決定。10-19)。触手の根元の骨の回転 z に足す角度(ラジアン)。GeckoLib の骨は Z·Y·X の順なので、
     * z に足すと親(頭)の長さの軸のまわりに回る = 体が転がるのに遅れて、触手が下に残る。転がりの真ん中でいちばん大きく、転がり終えると 0(仮)。
     */
    static float oralSwing;
    private static final String[] ORAL_ROOTS = {"oral1_s1_l", "oral2_s1_l", "oral1_s1_r", "oral2_s1_r"};

    /** 時刻 t(描画の時刻)の {@link #oralSwing}。 */
    static float oralSwing(com.yayuy.overgrownvegetation.skyboss.SkyBossFlight f, double t) {
        double d = f.rollTo - f.rollFrom;
        if (f.rollStart == com.yayuy.overgrownvegetation.skyboss.SkyBossFlight.NONE || Math.abs(d) < 1.0) {
            return 0.0F;
        }
        double p = (f.rollAt(t) - f.rollFrom) / d;
        if (p <= 0.0 || p >= 1.0) {
            return 0.0F;
        }
        return (float) (-Math.signum(d) * Math.toRadians(com.yayuy.overgrownvegetation.skyboss.SkyBossTuning.ORAL_SWING_DEG) * Math.sin(Math.PI * p));
    }

    /** 避雷針が壊れた後の体の反動のうち {@link #BENDS} に入らない分: b01 の回転 x(ラジアン)と体の根 body の位置 y(モデルの単位)。{@link SkyBossRenderer} が書く。 */
    static float recoilB01;
    static float recoilBodyY;

    /**
     * 背の垂れ草を重力で垂らす(決定。10-19 ユーザー「垂らしましょう」。角度は {@code skyboss/SkyBossHang})。{@link SkyBossRenderer} が毎フレーム、
     * 体の骨の行列まで出した姿勢と時刻(tick、partialTick 込み)を置く。板の骨はどのアニメもキーしていないので、毎フレーム静止の値 + 角度を書く(足すと積み上がる)。
     * 追いつきの状態(角度・速さ)はここだけが持つ。時刻が戻った・1 秒より飛んだ(呼び直し・遠くから戻った)ときは、追いつかずにその場の向きへ。
     */
    static SkyBossPose hangPose;
    static double hangTime;
    private GeoBone[] hangBones;
    private SkyBossHang.Plate[] hangPlates;
    private int[] hangParent;
    private double[] hangPhi;
    private double[] hangVel;
    private double hangLast = Double.NaN;
    private final double[] hangDown = new double[3];
    private final float[] hangRot = new float[3];
    private final float[] hangPos = new float[3];
    private final org.joml.Quaternionf hangQ = new org.joml.Quaternionf();
    private final org.joml.Matrix3f hangM = new org.joml.Matrix3f();
    private final org.joml.Vector3f hangE = new org.joml.Vector3f();

    static {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add("head");
        for (int i = 2; i <= 18; i++) {
            names.add(String.format("b%02d", i));
        }
        for (int k = 1; k <= 16; k++) {
            names.add(String.format("tail%02d", k));
        }
        BEND_BONES = names.toArray(new String[0]);
        BENDS = new float[BEND_BONES.length][3];
    }

    @Override
    public ResourceLocation getModelResource(SkyBossImpostor animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(SkyBossImpostor animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(SkyBossImpostor animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(SkyBossImpostor animatable, long instanceId, AnimationState<SkyBossImpostor> state) {
        super.setCustomAnimations(animatable, instanceId, state);
        for (int i = 0; i < BEND_BONES.length; i++) {
            GeoBone bone = getAnimationProcessor().getBone(BEND_BONES[i]);
            if (bone == null) {
                continue;
            }
            float[] b = BENDS[i];
            bone.setRotX(bone.getRotX() + b[0]);
            bone.setRotY(bone.getRotY() + b[1]);
            bone.setRotZ(bone.getRotZ() + b[2]);
        }
        GeoBone b01 = getAnimationProcessor().getBone("b01");
        if (b01 != null) {
            b01.setRotX(b01.getRotX() + recoilB01);
        }
        GeoBone root = getAnimationProcessor().getBone("body");
        if (root != null) {
            root.setPosY(root.getPosY() + recoilBodyY);
        }
        if (oralSwing != 0.0F) {
            for (String n : ORAL_ROOTS) {
                GeoBone b = getAnimationProcessor().getBone(n);
                if (b != null) {
                    b.setRotZ(b.getRotZ() + oralSwing);
                }
            }
        }
        buds();
        hangs();
        eyes();
    }

    // ------------------------------------------------------------------ 目の瞳(10-19 夕方)
    /**
     * 決定「ボスにめちゃめちゃ近づいて目の近くへ行くと、目がプレイヤーを追う」「瞳の動き: ばらばら → 一斉に向く・一つだけ違う瞳」。
     * ふだんは瞳ごとに別々の方をゆっくり見回し、カメラが目から {@link #EYE_TRACK_RANGE} の中で目の前の側に来ると、0.6 秒ほどで一斉にこちらを向く
     * (前の大きな目の 1 つの瞳だけは見回したまま)。瞳は目の骨の子で、目の面の中を位置で滑る(道具の eye_look と同じ動かし方)。滑る幅は瞳の大きさから
     * (道具の瞳ごとの幅に近い値)。向きの符号は {@link #EYE_SIGN} で直せる(確かめ用 {@link #eyeForce})。眠っている間は動かさない。数値は仮。
     */
    static final double EYE_TRACK_RANGE = 220.0;
    /** 目の局所の x(後ろ)・y の符号(左・右の目)。ゲームで向きが逆なら -1 に。 */
    static final float[][] EYE_SIGN = {{1.0F, 1.0F}, {1.0F, 1.0F}};
    /** 確かめ用(devbridge): 瞳を決めた向き (x, y)(-1〜1)へ。null = ふだん。 */
    public static float[] eyeForce;
    private GeoBone[][] pupils;
    private float[][] pupilLook;
    private double eyeFocus;
    private double eyeLast = Double.NaN;
    private final double[] eyeC = new double[3];
    private final double[] eyeN = new double[3];
    private final double[] eyeA = new double[3];

    private void eyes() {
        SkyBossPose ps = hangPose;
        if (ps == null || ps.bone == null || !ps.hasShapes()
                || ClientSkyBoss.flight().restKind == com.yayuy.overgrownvegetation.skyboss.SkyBossFlight.REST_SLEEP) {
            return;
        }
        if (pupils == null || (pupils.length > 0 && pupils[0].length > 0 && getAnimationProcessor().getBone(pupils[0][0].getName()) != pupils[0][0])) {
            collectPupils();
        }
        double t = hangTime;
        double dt = Double.isNaN(eyeLast) ? 0.0 : Math.max(0.0, Math.min(1.0, (t - eyeLast) / 20.0));
        eyeLast = t;
        net.minecraft.world.phys.Vec3 cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double[][] dir = new double[6][2];
        boolean near = false;
        for (int e = 0; e < 6; e++) {
            String name = "eye" + (e % 3 + 1) + (e < 3 ? "_l" : "_r");
            if (!ps.spotWorld(name, eyeC) || !ps.spotOutward(name, eyeN) || !ps.spotAxis(name, eyeA)) {
                continue;
            }
            // 目の面の中の向き: x = 体の軸(後ろ)を面へ落としたもの、y = 内向き × x
            double k = eyeA[0] * eyeN[0] + eyeA[1] * eyeN[1] + eyeA[2] * eyeN[2];
            double ax = eyeA[0] - eyeN[0] * k;
            double ay = eyeA[1] - eyeN[1] * k;
            double az = eyeA[2] - eyeN[2] * k;
            double al = Math.max(1.0E-6, Math.sqrt(ax * ax + ay * ay + az * az));
            ax /= al;
            ay /= al;
            az /= al;
            double bx = -eyeN[1] * az + eyeN[2] * ay;
            double by = -eyeN[2] * ax + eyeN[0] * az;
            double bz = -eyeN[0] * ay + eyeN[1] * ax;
            double vx = cam.x - eyeC[0];
            double vy = cam.y - eyeC[1];
            double vz = cam.z - eyeC[2];
            double d = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (d < 1.0E-6) {
                continue;
            }
            boolean front = (vx * eyeN[0] + vy * eyeN[1] + vz * eyeN[2]) > 0.0;
            if (d < EYE_TRACK_RANGE && front) {
                near = true;
            }
            dir[e][0] = (vx * ax + vy * ay + vz * az) / d * 1.4;
            dir[e][1] = (vx * bx + vy * by + vz * bz) / d * 1.4;
        }
        // 一斉に向く / 見回しに戻る(0.6 秒ほど)
        double target = near || eyeForce != null ? 1.0 : 0.0;
        eyeFocus += (target - eyeFocus) * Math.min(1.0, dt * 5.0);
        for (int e = 0; e < pupils.length; e++) {
            GeoBone[] row = pupils[e];
            float[] sign = EYE_SIGN[e < 3 ? 0 : 1];
            double tx = eyeForce != null ? eyeForce[0] : dir[e][0];
            double ty = eyeForce != null ? eyeForce[1] : dir[e][1];
            double tl = Math.hypot(tx, ty);
            if (tl > 1.0) {
                tx /= tl;
                ty /= tl;
            }
            for (int i = 0; i < row.length; i++) {
                double p1 = hash(e, i, 1) * Math.PI * 2.0;
                double p2 = hash(e, i, 2) * Math.PI * 2.0;
                double wx = 0.75 * Math.sin(t * (0.011 + 0.006 * hash(e, i, 3)) + p1);
                double wy = 0.75 * Math.sin(t * (0.014 + 0.006 * hash(e, i, 4)) + p2);
                // 前の大きな目の 1 つの瞳だけは見回したまま
                boolean odd = (e == 0 || e == 3) && i == Math.max(0, row.length - 4);
                double f = odd ? 0.0 : eyeFocus;
                double ux = wx + (tx - wx) * f;
                double uy = wy + (ty - wy) * f;
                row[i].setPosX((float) (ux * pupilLook[e][i]) * sign[0]);
                row[i].setPosY((float) (uy * pupilLook[e][i]) * sign[1]);
            }
        }
    }

    /** 瞳の骨(eyeN_pMM_l/r)と、滑る幅(瞳の板の半分の大きさの 1/4、0.3 まで。道具の瞳ごとの幅に近い)。 */
    private void collectPupils() {
        java.util.List<GeoBone[]> eyes = new java.util.ArrayList<>();
        java.util.List<float[]> looks = new java.util.ArrayList<>();
        for (String side : new String[]{"_l", "_r"}) {
            for (int e = 1; e <= 3; e++) {
                java.util.List<GeoBone> row = new java.util.ArrayList<>();
                java.util.List<Float> lk = new java.util.ArrayList<>();
                for (int i = 1; i < 100; i++) {
                    GeoBone b = getAnimationProcessor().getBone(String.format("eye%d_p%02d%s", e, i, side));
                    if (b == null) {
                        break;
                    }
                    row.add(b);
                    double half = 0.5;
                    if (!b.getCubes().isEmpty()) {
                        var size = b.getCubes().get(0).size();
                        half = Math.max(size.x, size.y) * 0.5;
                    }
                    lk.add((float) Math.min(0.25 * half, 0.3));
                }
                eyes.add(row.toArray(new GeoBone[0]));
                float[] l = new float[lk.size()];
                for (int i = 0; i < l.length; i++) {
                    l[i] = lk.get(i);
                }
                looks.add(l);
            }
        }
        pupils = eyes.toArray(new GeoBone[0][]);
        pupilLook = looks.toArray(new float[0][]);
    }

    private static double hash(int a, int b, int c) {
        long h = a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL + c * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** 背の垂れ草の板を、節の向きから出した真下へ倒す。 */
    private void hangs() {
        SkyBossPose ps = hangPose;
        SkyBossShapes s = SkyBossShapes.get();
        if (ps == null || s == null || ps.bone == null) {
            return;
        }
        if (hangBones == null || (hangBones.length > 0 && getAnimationProcessor().getBone(hangBones[0].getName()) != hangBones[0])) {
            collectHangs(s);
        }
        double t = hangTime;
        double dt = (t - hangLast) / 20.0;
        boolean snap = Double.isNaN(hangLast) || dt < 0.0 || dt > 1.0;
        hangLast = t;
        for (int i = 0; i < hangBones.length; i++) {
            SkyBossHang.Plate p = hangPlates[i];
            SkyBossHang.localDown(ps.bone[hangParent[i]], hangDown);
            // 呼び直しのときは外(体の脇)の側から決める
            double target = SkyBossHang.target(p, hangDown[0], hangDown[1], hangDown[2], snap ? p.max() : hangPhi[i]);
            if (snap) {
                hangPhi[i] = target;
                hangVel[i] = 0.0;
            } else if (dt > 0.0) {
                SkyBossHang.follow(p, hangPhi, hangVel, i, target, dt);
            }
            SkyBossHang.pose(p, hangPhi[i], hangRot, hangPos, hangQ, hangM, hangE);
            if (SkyBossDebugDraw.ENABLED) {
                measureHang(ps, i);
            }
            GeoBone b = hangBones[i];
            BoneSnapshot init = b.getInitialSnapshot();
            b.setRotX(init.getRotX() + hangRot[0]);
            b.setRotY(init.getRotY() + hangRot[1]);
            b.setRotZ(init.getRotZ() + hangRot[2]);
            b.setPosX(init.getOffsetX() + hangPos[0]);
            b.setPosY(init.getOffsetY() + hangPos[1]);
            b.setPosZ(init.getOffsetZ() + hangPos[2]);
        }
    }

    // ------------------------------------------------------------------ 確かめ用(devbridge だけ)
    /** 板ごとの、世界の真下からの角度(度)と、自分の側(体の横の向き)へ倒れているか(+)。 */
    private static float[] hangAngle = new float[0];
    private static float[] hangSide = new float[0];
    private static boolean[] hangLimit = new boolean[0];
    private final double[] hangDir = new double[3];

    private void measureHang(SkyBossPose ps, int i) {
        if (hangAngle.length != hangBones.length) {
            hangAngle = new float[hangBones.length];
            hangSide = new float[hangBones.length];
            hangLimit = new boolean[hangBones.length];
        }
        SkyBossHang.Plate p = hangPlates[i];
        SkyBossHang.direction(p, hangPhi[i], hangDir);
        org.joml.Matrix4d m = ps.bone[hangParent[i]];
        double wx = m.m00() * hangDir[0] + m.m10() * hangDir[1] + m.m20() * hangDir[2];
        double wy = m.m01() * hangDir[0] + m.m11() * hangDir[1] + m.m21() * hangDir[2];
        double wz = m.m02() * hangDir[0] + m.m12() * hangDir[1] + m.m22() * hangDir[2];
        double l = Math.sqrt(wx * wx + wy * wy + wz * wz);
        hangAngle[i] = (float) Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, -wy / l))));
        // 自分の側 = 骨の空間の x の付け根の符号の向き
        double sx = Math.signum(hangBones[i].getPivotX());
        double ox = m.m00() * sx;
        double oy = m.m01() * sx;
        double oz = m.m02() * sx;
        double ol = Math.sqrt(ox * ox + oy * oy + oz * oz);
        hangSide[i] = (float) ((wx * ox + wy * oy + wz * oz) / (l * ol));
        hangLimit[i] = Math.abs(hangPhi[i] - p.max()) < 1.0E-3 || Math.abs(hangPhi[i] - p.min()) < 1.0E-3;
    }

    /** devbridge の {@code skyboss hang}: 板の真下からの角度のまとめ(1 行)。 */
    public static String hangReport() {
        int n = hangAngle.length;
        if (n == 0) {
            return "垂れ草の板が無い(まだ描いていない)";
        }
        float min = 999.0F;
        float max = -1.0F;
        double sum = 0.0;
        int up = 0;
        int own = 0;
        int lying = 0;
        int limit = 0;
        for (int i = 0; i < n; i++) {
            min = Math.min(min, hangAngle[i]);
            max = Math.max(max, hangAngle[i]);
            sum += hangAngle[i];
            if (hangAngle[i] > 120.0F) {
                up++;
            }
            if (hangAngle[i] > 45.0F) {
                lying++;
                if (hangSide[i] > 0.0F) {
                    own++;
                }
            }
            if (hangLimit[i]) {
                limit++;
            }
        }
        return String.format(java.util.Locale.ROOT, "板 %d 枚 真下から 最小 %.1f° 平均 %.1f° 最大 %.1f° / 上を向く(120° より) %d / 45° より倒れた %d 枚のうち自分の側へ %d / 体に当たって止まる %d",
                n, min, sum / n, max, up, lying, own, limit);
    }

    /** devbridge の {@code skyboss arcs}: 放電の雷を最後に描いたフレームの頂点の数(10-19)。 */
    public static String arcReport() {
        return SkyBossArcs.report();
    }

    /** devbridge の {@code skyboss lines on|off}: 確かめ用の線を描くか。 */
    public static void debugLines(boolean on) {
        SkyBossDebugDraw.show = on;
    }

    /** 板の骨(hangNNN_l / _r)と、その親の体の骨(形のファイルの並び)・板の決まった値を集める(モデルを読み直したら集め直す)。 */
    private void collectHangs(SkyBossShapes s) {
        List<GeoBone> bones = new ArrayList<>();
        List<SkyBossHang.Plate> plates = new ArrayList<>();
        List<Integer> parents = new ArrayList<>();
        for (GeoBone b : getAnimationProcessor().getRegisteredBones()) {
            if (!b.getName().matches("hang\\d{3}_[lr]") || b.getParent() == null || b.getCubes().isEmpty()) {
                continue;
            }
            int par = s.bodyIndex(b.getParent().getName());
            if (par < 0) {
                continue;
            }
            bones.add(b);
            plates.add(new SkyBossHang.Plate(b.getPivotX(), b.getPivotY(), b.getCubes().get(0).rotation().y));
            parents.add(par);
        }
        hangBones = bones.toArray(new GeoBone[0]);
        hangPlates = plates.toArray(new SkyBossHang.Plate[0]);
        hangParent = parents.stream().mapToInt(Integer::intValue).toArray();
        hangPhi = new double[hangBones.length];
        hangVel = new double[hangBones.length];
        hangLast = Double.NaN;
    }

    /** 壊れた蕾を隠し、生え直しの間は育てる。 */
    private void buds() {
        com.yayuy.overgrownvegetation.skyboss.SkyBossShapes s = com.yayuy.overgrownvegetation.skyboss.SkyBossShapes.get();
        if (s == null) {
            return;
        }
        com.yayuy.overgrownvegetation.skyboss.SkyBossParts parts = ClientSkyBoss.parts();
        double t = ClientSkyBoss.animClock();
        for (int i = 0; i < s.budCount; i++) {
            GeoBone bone = getAnimationProcessor().getBone(s.budName[i]);
            if (bone == null) {
                continue;
            }
            float k = parts.budScale(i, t);
            bone.setHidden(k <= 0.0F);
            if (k > 0.0F && k < 1.0F) {
                bone.updateScale(k, k, k);
            }
        }
    }
}
