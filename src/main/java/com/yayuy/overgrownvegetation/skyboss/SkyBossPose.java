package com.yayuy.overgrownvegetation.skyboss;

import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * 空の巨大生物の今の姿勢(2026-10-18 夕方)。サーバー(当たり判定)とクライアント(描画・確かめ用の線)が<b>同じ計算</b>で出す。
 *
 * <h2>出すもの</h2>
 * <ol>
 *   <li>頭の道の上の節の点と向き、「親の節に対する曲がり」{@link #bends}(描画が GeckoLib の骨の回転に足すもの。前は {@code SkyBossRenderer} の中にあった)。</li>
 *   <li>体の骨 35 本の世界の変換 {@link #bone}: GeckoLib が描くのと同じ掛け方(根 = 首の輪の点 · b01 の向き · 3 倍 · 首の輪を原点へ、
 *       骨ごとに 親 · T(軸) · Rz·Ry·Rx(うねりのアニメ + 曲がり) · T(−軸)。GeckoLib はモデルの x と、アニメの回転の x・y の符号を裏返す)。</li>
 *   <li>当たり判定の形(世界の座標・ブロック): 胴と頭の輪切りの楕円の筒、腕・触手・避雷針のカプセル、扇の刃の間の三角形の板、蕾の球。
 *       扇・触手・うねりのアニメは {@link SkyBossShapes} の測った点を、{@link SkyBossFanClock} と同じ時刻で引く。</li>
 * </ol>
 * 線分との当たり({@link #raycast})と、点がめり込んでいるか({@link #contact})、いちばん近い距離({@link #nearest})を調べる。
 * <b>ずれる所</b>: 呼吸の倍率・扇の刃のしなりの細かい所・GeckoLib の補間の細かい所・アニメの切り替わりの 1 フレームの遅れ。垂れ草・腹の袋・目・口は形に入れていない。
 */
public final class SkyBossPose {

    public static final int PART_MOSS = 0;
    public static final int PART_ROD = 1;
    public static final int PART_BUD = 2;

    /** 道の上の点の距離(頭の先から、ブロック): 先・輪 0〜18・尾の輪 1〜16。 */
    public static final double[] DIST;
    /** 輪 r の添字 = r + 1。尾の輪 k の添字 = TAIL0 + k(尾の輪 0 = 輪 18)。 */
    public static final int TAIL0 = SkyBossBody.SEGS + 1;
    /** {@link #bends} の骨の数(頭・b02〜b18・尾 16。b01 はモデル全体の向き)。 */
    public static final int BEND_COUNT = 1 + (SkyBossBody.SEGS - 1) + SkyBossBody.TAIL;

    static {
        int n = 1 + (SkyBossBody.SEGS + 1) + SkyBossBody.TAIL;
        DIST = new double[n];
        DIST[0] = 0.0;
        for (int ring = 0; ring <= SkyBossBody.SEGS; ring++) {
            DIST[ring + 1] = SkyBossBody.along(SkyBossBody.Z0 + SkyBossBody.SEG_L * ring);
        }
        for (int k = 1; k <= SkyBossBody.TAIL; k++) {
            DIST[TAIL0 + k] = SkyBossBody.along(SkyBossBody.tailFrontZ(k) + SkyBossBody.TAIL_L);
        }
    }

    // ------------------------------------------------------------------ 道と曲がり
    /** 道の上の点(世界の座標)。 */
    public final double[][] points = new double[DIST.length][3];
    /** モデル全体の向き(b01 の節の向き。腹が上 + 傾き)。 */
    public final Quaternionf b01 = new Quaternionf();
    /** 曲がり {x, y, z}(GeckoLib の骨の回転 Z·Y·X、ラジアン)。並びは頭・b02〜b18・尾 1〜16。 */
    public final float[][] bends = new float[BEND_COUNT][3];

    // ------------------------------------------------------------------ 弱点と体の反動(10-18 夜)
    /** 弱点の壊れ具合(呼ぶ側が置く。null = 全部元どおり)。壊れた避雷針・蕾は形から外し、避雷針が壊れた後は体の反動({@link SkyBossRecoil})を曲がりに足す。 */
    public SkyBossParts parts;
    /** 体の反動のうち、{@link #bends} に入らない分: b01 の回転 x(GeckoLib のラジアン)と、体の根 body の位置 y(モデルの単位)。 */
    public float recoilB01;
    public float recoilBodyY;
    private final double[] recoilDeg = new double[SkyBossRecoil.BONES];

    // ------------------------------------------------------------------ 体の骨と形
    private final SkyBossShapes shapes;
    /** 体の骨の世界の変換(モデルの描画の座標 = x を裏返したモデルの座標、単位 → 世界のブロック)。形が無ければ null。 */
    public final Matrix4d[] bone;
    /** 体の骨 → {@link #bends} の添字。 */
    private final int[] bendOf;
    /** 体の根 body・b01 の骨の添字(体の反動を足す)。 */
    private final int rootBone;
    private final int b01Bone;
    /** 体の骨を計算する順(親が先。形のファイルでは頭が親の b01 より前に並ぶ)。 */
    private final int[] boneOrder;

    /** 輪切りの楕円の筒: 軸の始まり・軸の向き(単位)・横と縦の向き(単位)・長さ・半径。 */
    public int sliceCount;
    public double[] sA;
    public double[] sEx;
    public double[] sEy;
    public double[] sEz;
    public double[] sLen;
    public double[] sRx;
    public double[] sRy;
    /** カプセル: 両端と半径・部位。 */
    public int capCount;
    public double[] cA;
    public double[] cB;
    public double[] cR;
    public int[] cPart;
    /** 三角形の板: 3 隅と厚みの半分。 */
    public int triCount;
    public double[] tP;
    public double[] tH;
    /** 球: 中心と半径・部位。 */
    public int sphCount;
    public double[] spC;
    public double[] spR;
    public int[] spPart;
    /** 確かめ用: 刃の先(世界の座標 {x, y, z, ...}、腕ごと)。 */
    public double[][] bladeTips;
    /** 全体を包む球。 */
    public double boundX;
    public double boundY;
    public double boundZ;
    public double boundR;

    private final Matrix4d root = new Matrix4d();
    private final Matrix4d tmp = new Matrix4d();
    private final Vector3d v = new Vector3d();
    private final double[] seg = new double[2];
    private final double[][] quad = new double[6][3];
    private float[][] fanPts;
    private final float[][] fanPrev;
    private final float[][] fanCur;

    public SkyBossPose() {
        shapes = SkyBossShapes.get();
        if (shapes == null) {
            bone = null;
            bendOf = null;
            rootBone = -1;
            b01Bone = -1;
            boneOrder = null;
            fanPrev = null;
            fanCur = null;
            return;
        }
        bone = new Matrix4d[shapes.bodyCount];
        bendOf = new int[shapes.bodyCount];
        rootBone = shapes.bodyIndex("body");
        b01Bone = shapes.bodyIndex("b01");
        for (int i = 0; i < bendOf.length; i++) {
            bendOf[i] = bendIndex(shapes.bodyName[i]);
        }
        for (int i = 0; i < bone.length; i++) {
            bone[i] = new Matrix4d();
        }
        // 親が先になる順(10-19: 前は並びのままで、頭が親の b01 の前の計算の値を使っていた = 1 tick 古い・作ったばかりの姿勢では原点)
        boneOrder = new int[shapes.bodyCount];
        boolean[] done = new boolean[shapes.bodyCount];
        int ordered = 0;
        while (ordered < boneOrder.length) {
            int added = 0;
            for (int i = 0; i < shapes.bodyCount; i++) {
                int par = shapes.bodyParent[i];
                if (!done[i] && (par < 0 || done[par])) {
                    done[i] = true;
                    boneOrder[ordered++] = i;
                    added++;
                }
            }
            if (added == 0) {
                throw new IllegalStateException("体の骨の親子がつながっていない");
            }
        }
        int slices = 0;
        for (float[][] s : shapes.bodySlices) {
            slices += s.length;
        }
        sliceCount = slices;
        sA = new double[slices * 3];
        sEx = new double[slices * 3];
        sEy = new double[slices * 3];
        sEz = new double[slices * 3];
        sLen = new double[slices];
        sRx = new double[slices];
        sRy = new double[slices];
        int caps = shapes.armCount * (shapes.armPoints - 1) + shapes.tentCount * (shapes.tentPoints - 1) + (shapes.rodPts.length - 1);
        cA = new double[caps * 3];
        cB = new double[caps * 3];
        cR = new double[caps];
        cPart = new int[caps];
        int tris = 0;
        for (int a = 0; a < shapes.armCount; a++) {
            tris += Math.max(0, shapes.bladeCount[a] - 1) * 4;
        }
        tP = new double[tris * 9];
        tH = new double[tris];
        sphCount = shapes.budCount;
        spC = new double[sphCount * 3];
        spR = new double[sphCount];
        spPart = new int[sphCount];
        fanPrev = new float[shapes.armCount][];
        fanCur = new float[shapes.armCount][];
        for (int a = 0; a < shapes.armCount; a++) {
            int n = (shapes.armPoints + shapes.bladeCount[a] * 3) * 3;
            fanPrev[a] = new float[n];
            fanCur[a] = new float[n];
        }
    }

    public boolean hasShapes() {
        return shapes != null;
    }

    /** 避雷針のカプセルの最初の添字(避雷針はカプセルの最後に並ぶ)。形が無ければ -1。 */
    public int rodFirstCapsule() {
        return shapes == null ? -1 : capCount - (shapes.rodPts.length - 1);
    }

    /**
     * 姿勢を出す。{@code hx, hy, hz} = 頭(空気の座標)、{@code bank} = 傾き(度)、{@code t} = 時刻(ゲームの tick、描画では partialTick 込み)。
     * {@code shapesToo} が false なら道と曲がりだけ(描画で毎フレーム出す分)。
     */
    public void compute(SkyBossFlight f, double hx, double hy, double hz, float bank, double t, boolean shapesToo) {
        flight = f;
        f.trail.pointsBehind(hx, hy, hz, DIST, points);
        double ox = SkyBossWorld.worldX(0.0, t);
        double oz = SkyBossWorld.worldZ(0.0, t);
        for (double[] p : points) {
            p[0] += ox;
            p[2] += oz;
        }
        // 体の軸のまわりの向き(180 = 腹が上)+ 傾き。10-19 から節ごと: 転がりは頭から尾へ遅れて伝わる(頭の道をなぞるのと同じ考え)
        frame(points[1], points[2], rollOf(f, t, 1, bank), b01);
        Quaternionf prev = new Quaternionf(b01);
        Quaternionf q = new Quaternionf();
        int k = 0;
        // 頭: 先に向ける角度だけ、頭の向きを世界の上の軸のまわりに回してから(決定「頭をそちらへ向け、少し遅れて体が向きを変える」)
        double lookRad = Math.toRadians(lookOf(f, t));
        double[] tip = points[0];
        double[] neck = points[1];
        double vx = tip[0] - neck[0];
        double vz = tip[2] - neck[2];
        lookTip[0] = neck[0] + vx * Math.cos(lookRad) - vz * Math.sin(lookRad);
        lookTip[1] = tip[1];
        lookTip[2] = neck[2] + vz * Math.cos(lookRad) + vx * Math.sin(lookRad);
        frame(lookTip, neck, rollOf(f, t, 0, bank), q);
        bend(k++, b01, q);
        for (int i = 2; i <= SkyBossBody.SEGS; i++) {
            frame(points[i], points[i + 1], rollOf(f, t, i, bank), q);
            bend(k++, prev, q);
            prev.set(q);
        }
        for (int tk = 1; tk <= SkyBossBody.TAIL; tk++) {
            frame(points[TAIL0 + tk - 1], points[TAIL0 + tk], rollOf(f, t, TAIL0 + tk - 1, bank), q);
            bend(k++, prev, q);
            prev.set(q);
        }
        shudder(f, t);
        recoil(t);
        if (shapesToo && shapes != null) {
            bones(t);
            buildShapes(f, t);
        }
    }

    private final double[] lookTip = new double[3];

    /**
     * 体の骨の行列 {@link #bone} だけ出す({@link #compute} を {@code shapesToo} false で呼んだ後。形は出さない)。
     * 描画が背の垂れ草を重力で垂らすのに使う(うねり・震え・反動の入った節の向き。10-19)。形のファイルが無ければ何もしない。
     */
    public void bodyBones(double t) {
        if (shapes != null) {
            bones(t);
        }
    }

    /** 道の上の点 i(頭から {@link #DIST}[i] ブロック)の節の向き(度)。転がりは尾ほど遅れる({@link SkyBossTuning#ROLL_LAG_TICKS} で尾の端まで)。 */
    private static float rollOf(SkyBossFlight f, double t, int i, float bank) {
        double lag = DIST[i] / SkyBossBody.LENGTH * SkyBossTuning.ROLL_LAG_TICKS;
        // 10-19: 転がりのビームの転がりを足す(速く回るので頭から尾への遅れは短く)
        double beamLag = DIST[i] / SkyBossBody.LENGTH * SkyBossTuning.BEAM_ROLL_LAG;
        // 10-19 夕方: 日光浴の傾き(ゆっくりなので遅れなし)
        // 雲海からの飛び出しのひねり(遅れなし。尾の遅れは breach_body に入っている)
        return (float) (f.rollAt(t - lag) + SkyBossBeam.roll(f, t - beamLag) + SkyBossRhythm.baskRoll(f, t) + SkyBossCloudSea.breachRoll(f, t)) + bank;
    }

    /** 頭を先に向ける角度(度。描画では partialTick で補間: t は tick - 1 + pt、サーバーは tick)。 */
    private static float lookOf(SkyBossFlight f, double t) {
        double u = Math.max(0.0, Math.min(1.0, t - (f.tick - 1)));
        return (float) (f.plook + (f.look - f.plook) * u);
    }

    /**
     * 雷が避雷針に落ちたとき、体じゅうが震える(決まった式。だんだん収まる)。10-19 から、種の爆弾の反動(小さく短い)と段階が変わるとき(大きく長い)も
     * ({@link SkyBossFlight#shakeAt}。雷の {@code shudderAt} は放電の溜めに数えるので分けた)。
     */
    private void shudder(SkyBossFlight f, double t) {
        shake(t, f.shudderAt, SkyBossTuning.SHUDDER_TICKS, SkyBossTuning.SHUDDER_DEG, 5.0);
        if (f.shakeKind == SkyBossFlight.SHAKE_SEED) {
            shake(t, f.shakeAt, SkyBossTuning.SEED_SHAKE_TICKS, SkyBossTuning.SEED_SHAKE_DEG, 4.0);
        } else if (f.shakeKind == SkyBossFlight.SHAKE_CASUAL) {
            shake(t, f.shakeAt, SkyBossTuning.CASUAL_SHAKE_TICKS, SkyBossTuning.CASUAL_SHAKE_DEG, 6.0);
        } else {
            shake(t, f.shakeAt, SkyBossTuning.STAGE_SHAKE_TICKS, SkyBossTuning.STAGE_SHAKE_DEG, 7.0);
        }
    }

    private void shake(double t, long at, int ticks, float deg, double period) {
        if (at == SkyBossFlight.NONE || t < at || t > at + ticks) {
            return;
        }
        double u = (t - at) / ticks;
        double amp = Math.toRadians(deg) * (1.0 - u) * (1.0 - u);
        double w = (t - at) * (2.0 * Math.PI / period);
        for (int k = 0; k < BEND_COUNT; k++) {
            bends[k][0] += (float) (amp * Math.sin(w + k * 1.7));
            bends[k][1] += (float) (amp * Math.sin(w * 1.3 + k * 2.3));
        }
    }

    /**
     * 避雷針が壊れた後の体の反動を曲がりに足す(道具のアニメの度 → GeckoLib のラジアン: x は裏返す)。頭・b02〜b18・尾は {@link #bends} へ、
     * b01 の回転と体の根の位置は {@link #recoilB01}・{@link #recoilBodyY} へ(描画は {@code SkyBossModel} が同じ骨に足す)。
     */
    private void recoil(double t) {
        recoilB01 = 0.0F;
        recoilBodyY = 0.0F;
        double rt = parts == null ? -1.0 : parts.recoilTime(t);
        if (rt < 0.0) {
            return;
        }
        recoilBodyY = (float) SkyBossRecoil.compute(rt, recoilDeg);
        recoilB01 = (float) -Math.toRadians(recoilDeg[1]);
        bends[0][0] += (float) -Math.toRadians(recoilDeg[0]);
        for (int i = 2; i < SkyBossRecoil.BONES; i++) {
            bends[i - 1][0] += (float) -Math.toRadians(recoilDeg[i]);
        }
    }

    // ------------------------------------------------------------------ 体の骨
    /** 最後に {@link #compute} した頭(体・触手の時刻表を読む)。 */
    private SkyBossFlight flight;
    private final double[] actSeg = new double[4];
    /** 体の骨ごとのアニメの回転・位置(道具の値。度とモデルの単位)と、補間の前の区切りの分。 */
    private float[][] bodyRot;
    private float[][] bodyPos;
    private float[][] bodyRotPrev;
    private float[][] bodyPosPrev;

    /** 体の層のアニメ {@code anim}({@link SkyBossActClock#UNDULATE} など)の {@code ticks} tick 目の値を {@code rot}・{@code pos} へ。 */
    private void sampleBody(int anim, double ticks, float[][] rot, float[][] pos) {
        SkyBossShapes s = shapes;
        float[][][] fr;
        float[][][] fp;
        int frames;
        double len;
        SkyBossShapes.BodyAnim ba = anim == SkyBossActClock.UNDULATE ? null : s.bodyAnims.get(SkyBossActClock.BODY_NAMES[anim]);
        if (ba == null) {
            fr = s.undulateRot;
            fp = s.undulatePos;
            frames = s.undulateFrames;
            len = s.undulateLength * 20.0;
        } else {
            fr = ba.rot;
            fp = ba.pos;
            frames = ba.frames;
            len = ba.length * 20.0;
        }
        double u = Math.max(0.0, Math.min(1.0, ticks / len)) * frames;
        int f0 = Math.min(frames - 1, (int) Math.floor(u));
        double k = u - f0;
        for (int i = 0; i < s.bodyCount; i++) {
            float[] a = fr[f0][i];
            float[] b = fr[f0 + 1][i];
            float[] pa = fp[f0][i];
            float[] pb = fp[f0 + 1][i];
            for (int c = 0; c < 3; c++) {
                rot[i][c] = (float) (a[c] + (b[c] - a[c]) * k);
                pos[i][c] = (float) (pa[c] + (pb[c] - pa[c]) * k);
            }
        }
    }

    /** 時刻 t の体の層の値({@link SkyBossActClock#body} の区切りと補間)を {@link #bodyRot}・{@link #bodyPos} へ。 */
    private void bodyAnim(double t) {
        SkyBossShapes s = shapes;
        if (bodyRot == null) {
            bodyRot = new float[s.bodyCount][3];
            bodyPos = new float[s.bodyCount][3];
            bodyRotPrev = new float[s.bodyCount][3];
            bodyPosPrev = new float[s.bodyCount][3];
        }
        double[] sg = actSeg;
        if (flight == null) {
            sg[0] = SkyBossActClock.UNDULATE;
            sg[1] = 0.0;
            sg[2] = -1;
        } else {
            SkyBossActClock.body(flight, t, sg);
        }
        int anim = (int) sg[0];
        double start = sg[1];
        double tr = SkyBossTuning.BODY_TRANSITION;
        boolean loop = anim == SkyBossActClock.UNDULATE;
        sampleBody(anim, SkyBossActClock.animTime(loop, SkyBossActClock.bodyLength(anim), tr, start, t), bodyRot, bodyPos);
        double w = SkyBossActClock.blend(start, tr, t);
        if (w < 1.0 && sg[2] >= 0) {
            int pa = (int) sg[2];
            boolean ploop = pa == SkyBossActClock.UNDULATE;
            sampleBody(pa, SkyBossActClock.animTime(ploop, SkyBossActClock.bodyLength(pa), tr, sg[3], start), bodyRotPrev, bodyPosPrev);
            for (int i = 0; i < s.bodyCount; i++) {
                for (int c = 0; c < 3; c++) {
                    bodyRot[i][c] = (float) (bodyRotPrev[i][c] + (bodyRot[i][c] - bodyRotPrev[i][c]) * w);
                    bodyPos[i][c] = (float) (bodyPosPrev[i][c] + (bodyPos[i][c] - bodyPosPrev[i][c]) * w);
                }
            }
        }
    }

    private void bones(double t) {
        SkyBossShapes s = shapes;
        // 体の層(うねり・10-19 から雲を吐く体の動き。GeckoLib の体のコントローラと同じ時刻表 SkyBossActClock)
        bodyAnim(t);
        double[] r0 = points[1];
        root.identity().translate(r0[0], r0[1], r0[2]).rotate(new org.joml.Quaterniond(b01.x, b01.y, b01.z, b01.w))
                .scale(SkyBossTuning.BLOCKS_PER_UNIT).translate(0.0, -SkyBossBody.Y0, -SkyBossBody.Z0);
        for (int i : boneOrder) {
            int p = s.bodyParent[i];
            Matrix4d parent = p < 0 ? root : bone[p];
            float[] ar = bodyRot[i];
            float[] ap = bodyPos[i];
            // GeckoLib はアニメの回転の x・y を裏返し、度 → ラジアン
            double rx = -Math.toRadians(ar[0]);
            double ry = -Math.toRadians(ar[1]);
            double rz = Math.toRadians(ar[2]);
            int bi = bendOf[i];
            if (bi >= 0) {
                rx += bends[bi][0];
                ry += bends[bi][1];
                rz += bends[bi][2];
            } else if (i == b01Bone) {
                rx += recoilB01;
            }
            float[] pv = s.bodyPivot[i];
            double px = -pv[0];
            double py = pv[1];
            double pz = pv[2];
            // アニメの位置(GeckoLib は x を裏返す)→ 軸 → 回転 → 軸を戻す
            double ox = -ap[0];
            double oy = ap[1] + (i == rootBone ? recoilBodyY : 0.0);
            double oz = ap[2];
            bone[i].set(parent).translate(ox, oy, oz).translate(px, py, pz).rotateZ(rz).rotateY(ry).rotateX(rx).translate(-px, -py, -pz);
        }
    }

    /** 体の骨(形のファイルの並び)→ {@link #bends} の添字(頭・b02〜b18・尾。根の body と b01 は -1)。 */
    private static int bendIndex(String name) {
        if (name.equals("head")) {
            return 0;
        }
        if (name.matches("b\\d\\d")) {
            int n = Integer.parseInt(name.substring(1));
            return n >= 2 ? n - 1 : -1;
        }
        if (name.matches("tail\\d\\d")) {
            return SkyBossBody.SEGS - 1 + Integer.parseInt(name.substring(4));
        }
        return -1;
    }

    /** モデルの座標の点 (x, y, z) を骨 {@code b} で世界へ(x を裏返して描画の座標にしてから)。 */
    /** 技の出どころの点 {@code name}({@link SkyBossShapes#spots})の世界の座標 → {@code out}。形を出した後だけ。無ければ false。 */
    public boolean spotWorld(String name, double[] out) {
        if (shapes == null) {
            return false;
        }
        float[] sp = shapes.spots.get(name);
        if (sp == null) {
            return false;
        }
        toWorld((int) sp[0], sp[1], sp[2], sp[3], out, 0);
        return true;
    }

    /** 蕾 i の真ん中(世界)→ {@code out}。骨の行列だけ使う(描画が {@link #bodyBones} の後にビームの元に使う)。 */
    public void budWorld(int i, double[] out) {
        float[] c = shapes.budCenter[i];
        toWorld(shapes.budOwner[i], c[0], c[1], c[2], out, 0);
    }

    /** 技の出どころ {@code name} の外向き(体の軸から出どころへの向き。世界の単位ベクトル)→ {@code out}。無ければ false。 */
    public boolean spotOutward(String name, double[] out) {
        if (shapes == null) {
            return false;
        }
        float[] sp = shapes.spots.get(name);
        if (sp == null) {
            return false;
        }
        toWorldDir((int) sp[0], sp[1], sp[2] - SkyBossBody.Y0, 0.0, out, 0);
        return true;
    }

    /** 蕾 i の乗っている骨の、体の長さの軸(モデルの +z = 尾の向き。世界の単位ベクトル)→ {@code out}。骨の行列だけ使う。 */
    public void budAxis(int i, double[] out) {
        toWorldDir(shapes.budOwner[i], 0.0, 0.0, 1.0, out, 0);
    }

    /** 技の出どころ {@code name} の乗っている骨の、体の長さの軸(モデルの +z = 尾の向き。世界の単位ベクトル)→ {@code out}。無ければ false。 */
    public boolean spotAxis(String name, double[] out) {
        if (shapes == null) {
            return false;
        }
        float[] sp = shapes.spots.get(name);
        if (sp == null) {
            return false;
        }
        toWorldDir((int) sp[0], 0.0, 0.0, 1.0, out, 0);
        return true;
    }

    /** 腹の触手のカプセルの最初の添字(腕の後、避雷針の前)と、触手 1 本のカプセルの数。形が無ければ -1。 */
    public int tentFirstCapsule() {
        return shapes == null ? -1 : shapes.armCount * (shapes.armPoints - 1);
    }

    public int tentCapsulesEach() {
        return shapes == null ? 0 : shapes.tentPoints - 1;
    }

    public int tentCount() {
        return shapes == null ? 0 : shapes.tentCount;
    }

    /** 蕾 i の外向き(体の軸から蕾の真ん中への向き。世界の単位ベクトル)→ {@code out}。形を出した後だけ。 */
    public void budOutward(int i, double[] out) {
        float[] c = shapes.budCenter[i];
        toWorldDir(shapes.budOwner[i], c[0], c[1] - SkyBossBody.Y0, 0.0, out, 0);
    }

    private void toWorld(int b, double x, double y, double z, double[] out, int off) {
        v.set(-x, y, z);
        bone[b].transformPosition(v);
        out[off] = v.x;
        out[off + 1] = v.y;
        out[off + 2] = v.z;
    }

    private void toWorldDir(int b, double x, double y, double z, double[] out, int off) {
        v.set(-x, y, z);
        bone[b].transformDirection(v);
        v.normalize();
        out[off] = v.x;
        out[off + 1] = v.y;
        out[off + 2] = v.z;
    }

    // ------------------------------------------------------------------ 形
    private void buildShapes(SkyBossFlight f, double t) {
        SkyBossShapes s = shapes;
        double bpu = SkyBossTuning.BLOCKS_PER_UNIT;
        // 輪切り
        int si = 0;
        for (int i = 0; i < s.bodyCount; i++) {
            for (float[] sl : s.bodySlices[i]) {
                toWorld(i, 0.0, sl[2], sl[0], sA, si * 3);
                toWorldDir(i, -1.0, 0.0, 0.0, sEx, si * 3);
                toWorldDir(i, 0.0, 1.0, 0.0, sEy, si * 3);
                toWorldDir(i, 0.0, 0.0, 1.0, sEz, si * 3);
                sLen[si] = (sl[1] - sl[0]) * bpu;
                sRx[si] = sl[3] * bpu;
                sRy[si] = sl[4] * bpu;
                si++;
            }
        }
        // 扇の層の点(時刻表どおり)
        fanPoints(f, t);
        int ci = 0;
        int ti = 0;
        for (int a = 0; a < s.armCount; a++) {
            float[] p = fanPts[a];
            int owner = s.armOwner[a];
            for (int k = 0; k + 1 < s.armPoints; k++) {
                toWorld(owner, p[k * 3], p[k * 3 + 1], p[k * 3 + 2], cA, ci * 3);
                toWorld(owner, p[(k + 1) * 3], p[(k + 1) * 3 + 1], p[(k + 1) * 3 + 2], cB, ci * 3);
                cR[ci] = s.armRadius[a][k] * bpu;
                cPart[ci] = PART_MOSS;
                ci++;
            }
            int nb = s.bladeCount[a];
            int base = s.armPoints * 3;
            double h = s.sheetHalf[a] * bpu;
            if (bladeTips == null) {
                bladeTips = new double[s.armCount][];
            }
            if (bladeTips[a] == null) {
                bladeTips[a] = new double[nb * 3];
            }
            for (int j = 0; j < nb; j++) {
                int ot = base + (j * 3 + 2) * 3;
                toWorld(owner, p[ot], p[ot + 1], p[ot + 2], bladeTips[a], j * 3);
            }
            for (int j = 0; j + 1 < nb; j++) {
                // 刃 j と j+1 の 根元・中ほど・先 の 6 点 → 三角形 4 枚
                double[][] q = quad;
                for (int m = 0; m < 3; m++) {
                    int o1 = base + (j * 3 + m) * 3;
                    int o2 = base + ((j + 1) * 3 + m) * 3;
                    toWorld(owner, p[o1], p[o1 + 1], p[o1 + 2], q[m], 0);
                    toWorld(owner, p[o2], p[o2 + 1], p[o2 + 2], q[3 + m], 0);
                }
                ti = tri(ti, q[0], q[1], q[3], h);
                ti = tri(ti, q[1], q[4], q[3], h);
                ti = tri(ti, q[1], q[2], q[4], h);
                ti = tri(ti, q[2], q[5], q[4], h);
            }
        }
        triCount = ti;
        // 腹の触手(たなびき・10-19 から打つ・絡め取る。GeckoLib の触手のコントローラと同じ時刻表 SkyBossActClock)
        tentAnim(t);
        for (int tn = 0; tn < s.tentCount; tn++) {
            float[] tp = tentNow[tn];
            int owner = s.tentOwner[tn];
            for (int k = 0; k + 1 < s.tentPoints; k++) {
                int o = k * 3;
                int o2 = (k + 1) * 3;
                toWorld(owner, tp[o], tp[o + 1], tp[o + 2], cA, ci * 3);
                toWorld(owner, tp[o2], tp[o2 + 1], tp[o2 + 2], cB, ci * 3);
                cR[ci] = s.tentRadius[tn][k] * bpu;
                cPart[ci] = PART_MOSS;
                ci++;
            }
        }
        // 避雷針(弱点)
        for (int k = 0; k + 1 < s.rodPts.length; k++) {
            float[] a = s.rodPts[k];
            float[] b = s.rodPts[k + 1];
            toWorld(s.rodOwner, a[0], a[1], a[2], cA, ci * 3);
            toWorld(s.rodOwner, b[0], b[1], b[2], cB, ci * 3);
            // 壊れている間(生え直しの途中も)は形から外す(半径 0)
            cR[ci] = parts != null && !parts.rodSolid() ? 0.0 : Math.max(s.rodRadius[k], 1.0F) * bpu;
            cPart[ci] = PART_ROD;
            ci++;
        }
        capCount = ci;
        // 蕾(弱点)
        for (int b = 0; b < s.budCount; b++) {
            float[] c = s.budCenter[b];
            toWorld(s.budOwner[b], c[0], c[1], c[2], spC, b * 3);
            spR[b] = parts != null && !parts.budSolid(b) ? 0.0 : s.budRadius[b] * bpu;
            spPart[b] = PART_BUD;
        }
        // 全体を包む球
        double[] mid = points[DIST.length / 2];
        double r = 0.0;
        for (int i = 0; i < capCount; i++) {
            r = Math.max(r, dist(cA, i * 3, mid) + cR[i]);
            r = Math.max(r, dist(cB, i * 3, mid) + cR[i]);
        }
        for (int i = 0; i < triCount * 3; i++) {
            r = Math.max(r, dist(tP, i * 3, mid) + 20.0);
        }
        for (double[] p : points) {
            r = Math.max(r, Math.sqrt(sq(p[0] - mid[0]) + sq(p[1] - mid[1]) + sq(p[2] - mid[2])) + 100.0);
        }
        boundX = mid[0];
        boundY = mid[1];
        boundZ = mid[2];
        boundR = r;
    }

    private int tri(int i, double[] a, double[] b, double[] c, double h) {
        System.arraycopy(a, 0, tP, i * 9, 3);
        System.arraycopy(b, 0, tP, i * 9 + 3, 3);
        System.arraycopy(c, 0, tP, i * 9 + 6, 3);
        tH[i] = h;
        return i + 1;
    }

    /** 時刻 t の扇の層の点(モデルの座標・持ち主の骨の静止の座標)を {@link #fanPts} へ。 */
    /** 触手ごとの今の軸の点(モデルの座標)と、補間の前の区切りの分。 */
    private float[][] tentNow;
    private float[][] tentPrev;

    /** 触手のアニメ {@code anim} の {@code ticks} tick 目の点を {@code out} へ。 */
    private void sampleTent(int anim, double ticks, float[][] out) {
        SkyBossShapes s = shapes;
        SkyBossShapes.TentAnim ta = anim == SkyBossActClock.DRIFT ? null : s.tentAnims.get(SkyBossActClock.TENT_NAMES[anim]);
        float[][][] pts = ta == null ? s.tentPts : ta.pts;
        int frames = ta == null ? s.driftFrames : ta.frames;
        double len = (ta == null ? s.driftLength : ta.length) * 20.0;
        double u = Math.max(0.0, Math.min(1.0, ticks / len)) * frames;
        int f0 = Math.min(frames - 1, (int) Math.floor(u));
        double k = u - f0;
        for (int tn = 0; tn < s.tentCount; tn++) {
            float[] a = pts[f0][tn];
            float[] b = pts[f0 + 1][tn];
            float[] o = out[tn];
            for (int i = 0; i < o.length; i++) {
                o[i] = (float) (a[i] + (b[i] - a[i]) * k);
            }
        }
    }

    private void tentAnim(double t) {
        SkyBossShapes s = shapes;
        if (tentNow == null) {
            tentNow = new float[s.tentCount][s.tentPoints * 3];
            tentPrev = new float[s.tentCount][s.tentPoints * 3];
        }
        double[] sg = actSeg;
        if (flight == null) {
            sg[0] = SkyBossActClock.DRIFT;
            sg[1] = 0.0;
            sg[2] = -1;
        } else {
            SkyBossActClock.tentacles(flight, t, sg);
        }
        int anim = (int) sg[0];
        double start = sg[1];
        double tr = SkyBossTuning.TENT_TRANSITION;
        boolean loop = anim == SkyBossActClock.DRIFT;
        sampleTent(anim, SkyBossActClock.animTime(loop, SkyBossActClock.tentLength(anim), tr, start, t), tentNow);
        double w = SkyBossActClock.blend(start, tr, t);
        if (w < 1.0 && sg[2] >= 0) {
            int pa = (int) sg[2];
            boolean ploop = pa == SkyBossActClock.DRIFT;
            sampleTent(pa, SkyBossActClock.animTime(ploop, SkyBossActClock.tentLength(pa), tr, sg[3], start), tentPrev);
            for (int tn = 0; tn < s.tentCount; tn++) {
                float[] c = tentNow[tn];
                float[] p = tentPrev[tn];
                for (int i = 0; i < c.length; i++) {
                    c[i] = (float) (p[i] + (c[i] - p[i]) * w);
                }
            }
        }
    }

    private void fanPoints(SkyBossFlight f, double t) {
        double[] sg = seg;
        SkyBossFanClock.segment(f, t, sg);
        int anim = (int) sg[0];
        double start = sg[1];
        double w = SkyBossFanClock.blend(start, t);
        sample(anim, SkyBossFanClock.animTime(anim, start, t), fanCur);
        if (w < 1.0) {
            // 補間の間: 前の区切りのその時の形 → このアニメの 0 秒目の形
            SkyBossFanClock.segment(f, start - 1.0E-3, sg);
            int pa = (int) sg[0];
            double ps = sg[1];
            boolean sameSeg = pa == anim && ps == start;
            if (!sameSeg) {
                sample(pa, SkyBossFanClock.animTime(pa, ps, start), fanPrev);
            } else {
                // 区切りの前が分からない(出した直後など)ときは補間しない
                fanPts = fanCur;
                return;
            }
            for (int a = 0; a < fanCur.length; a++) {
                float[] c = fanCur[a];
                float[] p = fanPrev[a];
                for (int i = 0; i < c.length; i++) {
                    c[i] = (float) (p[i] + (c[i] - p[i]) * w);
                }
            }
        }
        fanPts = fanCur;
    }

    private void sample(int anim, double animTicks, float[][] out) {
        SkyBossShapes.FanAnim fa = shapes.fanAnims.get(SkyBossFanClock.NAMES[anim]);
        double u = Math.max(0.0, Math.min(1.0, animTicks / (fa.length * 20.0))) * fa.frames;
        int f0 = Math.min(fa.frames - 1, (int) Math.floor(u));
        double fr = u - f0;
        for (int a = 0; a < out.length; a++) {
            float[] p0 = fa.pts[f0][a];
            float[] p1 = fa.pts[f0 + 1][a];
            float[] o = out[a];
            for (int i = 0; i < o.length; i++) {
                o[i] = (float) (p0[i] + (p1[i] - p0[i]) * fr);
            }
        }
    }

    // ------------------------------------------------------------------ 向き(前は SkyBossRenderer)
    /** 前の点 → 後ろの点の節の向き: モデルの前(−z)を節の前へ、背(+y)を体の軸のまわりに roll 回した向きへ。 */
    static void frame(double[] front, double[] back, float roll, Quaternionf out) {
        double dx = front[0] - back[0];
        double dy = front[1] - back[1];
        double dz = front[2] - back[2];
        double len = Math.max(1.0E-9, Math.sqrt(dx * dx + dy * dy + dz * dz));
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, dy / len))));
        out.identity()
                .rotateY((float) Math.toRadians(180.0F - yaw))
                .rotateX((float) Math.toRadians(-pitch))
                .rotateZ((float) Math.toRadians(roll));
    }

    private final Matrix3f tmpM = new Matrix3f();
    private final Vector3f tmpE = new Vector3f();
    private final Quaternionf tmpQ = new Quaternionf();

    /** 親の節 {@code parent} に対する子の節 {@code child} の曲がりを、GeckoLib の骨の回転(Z·Y·X)として {@link #bends} の k 番目へ。 */
    private void bend(int k, Quaternionf parent, Quaternionf child) {
        tmpQ.set(parent).conjugate().mul(child);
        tmpM.set(tmpQ).getEulerAnglesZYX(tmpE);
        float[] b = bends[k];
        b[0] = tmpE.x;
        b[1] = tmpE.y;
        b[2] = tmpE.z;
    }

    // ------------------------------------------------------------------ 当たり
    /** 線分との当たり。 */
    public static final class Hit {
        /** 線分の上の割合(0〜1)。 */
        public double t;
        public double x;
        public double y;
        public double z;
        public int part;
        /** 蕾なら蕾の添字(ほかは -1)。 */
        public int index;
    }

    /** 線分 a → b がいちばん最初に当たる所を {@code out} へ(当たらなければ false)。 */
    public boolean raycast(double ax, double ay, double az, double bx, double by, double bz, Hit out) {
        if (shapes == null) {
            return false;
        }
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        // 全体の球で先に外す
        if (segPointDist(ax, ay, az, dx, dy, dz, boundX, boundY, boundZ) > boundR) {
            return false;
        }
        double best = Double.MAX_VALUE;
        int part = PART_MOSS;
        int index = -1;
        for (int i = 0; i < sliceCount; i++) {
            double tt = rayEllipse(i, ax, ay, az, dx, dy, dz);
            if (tt < best) {
                best = tt;
                part = PART_MOSS;
                index = -1;
            }
        }
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 1.0E-9) {
            for (int i = 0; i < capCount; i++) {
                if (cR[i] <= 0.0) {
                    continue;
                }
                double tt = rayCapsule(ax, ay, az, dx / len, dy / len, dz / len, len, cA, cB, i, cR[i]);
                if (tt < best) {
                    best = tt;
                    part = cPart[i];
                    index = -1;
                }
            }
            for (int i = 0; i < sphCount; i++) {
                if (spR[i] <= 0.0) {
                    continue;
                }
                double tt = raySphere(ax, ay, az, dx, dy, dz, spC, i, spR[i]);
                if (tt < best) {
                    best = tt;
                    part = spPart[i];
                    index = i;
                }
            }
        }
        for (int i = 0; i < triCount; i++) {
            double tt = rayTri(ax, ay, az, dx, dy, dz, i);
            if (tt < best) {
                best = tt;
                part = PART_MOSS;
                index = -1;
            }
        }
        if (best > 1.0) {
            return false;
        }
        out.t = best;
        out.x = ax + dx * best;
        out.y = ay + dy * best;
        out.z = az + dz * best;
        out.part = part;
        out.index = index;
        return true;
    }

    /** 楕円の筒 i と線分(始まり a、向き d)の最初の当たりの割合(無ければ MAX)。 */
    private double rayEllipse(int i, double ax, double ay, double az, double dx, double dy, double dz) {
        int o = i * 3;
        double rx = sRx[i];
        double ry = sRy[i];
        double px = ax - sA[o];
        double py = ay - sA[o + 1];
        double pz = az - sA[o + 2];
        double ox = (px * sEx[o] + py * sEx[o + 1] + pz * sEx[o + 2]) / rx;
        double oy = (px * sEy[o] + py * sEy[o + 1] + pz * sEy[o + 2]) / ry;
        double oz = px * sEz[o] + py * sEz[o + 1] + pz * sEz[o + 2];
        double vx = (dx * sEx[o] + dy * sEx[o + 1] + dz * sEx[o + 2]) / rx;
        double vy = (dx * sEy[o] + dy * sEy[o + 1] + dz * sEy[o + 2]) / ry;
        double vz = dx * sEz[o] + dy * sEz[o + 1] + dz * sEz[o + 2];
        double enter;
        double exit;
        double qa = vx * vx + vy * vy;
        double qc = ox * ox + oy * oy - 1.0;
        if (qa < 1.0E-12) {
            if (qc > 0.0) {
                return Double.MAX_VALUE;
            }
            enter = -Double.MAX_VALUE;
            exit = Double.MAX_VALUE;
        } else {
            double qb = 2.0 * (ox * vx + oy * vy);
            double disc = qb * qb - 4.0 * qa * qc;
            if (disc < 0.0) {
                return Double.MAX_VALUE;
            }
            double sq = Math.sqrt(disc);
            enter = (-qb - sq) / (2.0 * qa);
            exit = (-qb + sq) / (2.0 * qa);
        }
        double len = sLen[i];
        if (Math.abs(vz) < 1.0E-12) {
            if (oz < 0.0 || oz > len) {
                return Double.MAX_VALUE;
            }
        } else {
            double t3 = -oz / vz;
            double t4 = (len - oz) / vz;
            enter = Math.max(enter, Math.min(t3, t4));
            exit = Math.min(exit, Math.max(t3, t4));
        }
        if (enter > exit || exit < 0.0 || enter > 1.0) {
            return Double.MAX_VALUE;
        }
        return Math.max(enter, 0.0);
    }

    /** カプセルと線分(始まり a、単位の向き d、長さ len)の最初の当たりの割合(Inigo Quilez の式)。 */
    private static double rayCapsule(double ax, double ay, double az, double rdx, double rdy, double rdz, double len,
                                     double[] ca, double[] cb, int i, double r) {
        int o = i * 3;
        double pax = ca[o];
        double pay = ca[o + 1];
        double paz = ca[o + 2];
        double bax = cb[o] - pax;
        double bay = cb[o + 1] - pay;
        double baz = cb[o + 2] - paz;
        double oax = ax - pax;
        double oay = ay - pay;
        double oaz = az - paz;
        double baba = bax * bax + bay * bay + baz * baz;
        // 始まりが中なら 0
        double tt = baba < 1.0E-12 ? 0.0 : Math.max(0.0, Math.min(1.0, (oax * bax + oay * bay + oaz * baz) / baba));
        if (sq(oax - bax * tt) + sq(oay - bay * tt) + sq(oaz - baz * tt) <= r * r) {
            return 0.0;
        }
        double bard = bax * rdx + bay * rdy + baz * rdz;
        double baoa = bax * oax + bay * oay + baz * oaz;
        double rdoa = rdx * oax + rdy * oay + rdz * oaz;
        double oaoa = oax * oax + oay * oay + oaz * oaz;
        double a = baba - bard * bard;
        double b = baba * rdoa - baoa * bard;
        double c = baba * oaoa - baoa * baoa - r * r * baba;
        double h = b * b - a * c;
        double hit = Double.MAX_VALUE;
        if (h >= 0.0 && a > 1.0E-12) {
            double t = (-b - Math.sqrt(h)) / a;
            double y = baoa + t * bard;
            if (y > 0.0 && y < baba) {
                hit = t;
            } else {
                // 端の球
                double ocx = y <= 0.0 ? oax : ax - cb[o];
                double ocy = y <= 0.0 ? oay : ay - cb[o + 1];
                double ocz = y <= 0.0 ? oaz : az - cb[o + 2];
                double b2 = rdx * ocx + rdy * ocy + rdz * ocz;
                double c2 = ocx * ocx + ocy * ocy + ocz * ocz - r * r;
                double h2 = b2 * b2 - c2;
                if (h2 > 0.0) {
                    hit = -b2 - Math.sqrt(h2);
                }
            }
        }
        if (hit < 0.0 || hit == Double.MAX_VALUE) {
            return Double.MAX_VALUE;
        }
        return hit / len;
    }

    private static double raySphere(double ax, double ay, double az, double dx, double dy, double dz, double[] c, int i, double r) {
        int o = i * 3;
        double ox = ax - c[o];
        double oy = ay - c[o + 1];
        double oz = az - c[o + 2];
        double a = dx * dx + dy * dy + dz * dz;
        double b = 2.0 * (ox * dx + oy * dy + oz * dz);
        double cc = ox * ox + oy * oy + oz * oz - r * r;
        if (cc <= 0.0) {
            return 0.0;
        }
        double disc = b * b - 4.0 * a * cc;
        if (disc < 0.0 || a < 1.0E-12) {
            return Double.MAX_VALUE;
        }
        double t = (-b - Math.sqrt(disc)) / (2.0 * a);
        return t < 0.0 ? Double.MAX_VALUE : t;
    }

    /** 三角形の板 i と線分の当たりの割合(両面。Moller-Trumbore)。 */
    private double rayTri(double ax, double ay, double az, double dx, double dy, double dz, int i) {
        int o = i * 9;
        double e1x = tP[o + 3] - tP[o];
        double e1y = tP[o + 4] - tP[o + 1];
        double e1z = tP[o + 5] - tP[o + 2];
        double e2x = tP[o + 6] - tP[o];
        double e2y = tP[o + 7] - tP[o + 1];
        double e2z = tP[o + 8] - tP[o + 2];
        double px = dy * e2z - dz * e2y;
        double py = dz * e2x - dx * e2z;
        double pz = dx * e2y - dy * e2x;
        double det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1.0E-12) {
            return Double.MAX_VALUE;
        }
        double inv = 1.0 / det;
        double tx = ax - tP[o];
        double ty = ay - tP[o + 1];
        double tz = az - tP[o + 2];
        double u = (tx * px + ty * py + tz * pz) * inv;
        if (u < 0.0 || u > 1.0) {
            return Double.MAX_VALUE;
        }
        double qx = ty * e1z - tz * e1y;
        double qy = tz * e1x - tx * e1z;
        double qz = tx * e1y - ty * e1x;
        double w = (dx * qx + dy * qy + dz * qz) * inv;
        if (w < 0.0 || u + w > 1.0) {
            return Double.MAX_VALUE;
        }
        double t = (e2x * qx + e2y * qy + e2z * qz) * inv;
        return t < 0.0 ? Double.MAX_VALUE : t;
    }

    /** 点がめり込んでいるか。 */
    public static final class Contact {
        /** めり込みの深さ(ブロック)と、外へ出る向き(単位)。 */
        public double depth;
        public double nx;
        public double ny;
        public double nz;
        public int part;
        /** 蕾なら蕾の添字(ほかは -1)。 */
        public int index;
    }

    /** 半径 {@code radius} の球(点 p)がいちばん深くめり込んでいる形を {@code out} へ(めり込んでいなければ false)。 */
    public boolean contact(double px, double py, double pz, double radius, Contact out) {
        if (shapes == null || sq(px - boundX) + sq(py - boundY) + sq(pz - boundZ) > sq(boundR + radius)) {
            return false;
        }
        double best = 0.0;
        boolean found = false;
        for (int i = 0; i < sliceCount; i++) {
            int o = i * 3;
            double qx = px - sA[o];
            double qy = py - sA[o + 1];
            double qz = pz - sA[o + 2];
            double lz = qx * sEz[o] + qy * sEz[o + 1] + qz * sEz[o + 2];
            if (lz < -radius || lz > sLen[i] + radius) {
                continue;
            }
            double rx = sRx[i] + radius;
            double ry = sRy[i] + radius;
            double lx = (qx * sEx[o] + qy * sEx[o + 1] + qz * sEx[o + 2]) / rx;
            double ly = (qx * sEy[o] + qy * sEy[o + 1] + qz * sEy[o + 2]) / ry;
            double q = Math.sqrt(lx * lx + ly * ly);
            if (q >= 1.0) {
                continue;
            }
            double depth = (1.0 - q) * Math.min(rx, ry);
            if (depth > best) {
                double nxl = q < 1.0E-6 ? 0.0 : lx / rx;
                double nyl = q < 1.0E-6 ? 1.0 : ly / ry;
                double nx = sEx[o] * nxl + sEy[o] * nyl;
                double ny = sEx[o + 1] * nxl + sEy[o + 1] * nyl;
                double nz = sEx[o + 2] * nxl + sEy[o + 2] * nyl;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                best = depth;
                found = true;
                out.depth = depth;
                out.nx = nx / nl;
                out.ny = ny / nl;
                out.nz = nz / nl;
                out.part = PART_MOSS;
                out.index = -1;
            }
        }
        for (int i = 0; i < capCount; i++) {
            if (cR[i] <= 0.0) {
                continue;
            }
            closestOnSeg(px, py, pz, cA, cB, i, v);
            double d = Math.sqrt(sq(px - v.x) + sq(py - v.y) + sq(pz - v.z));
            double depth = cR[i] + radius - d;
            if (depth > best) {
                best = depth;
                found = true;
                setNormal(out, px - v.x, py - v.y, pz - v.z, d);
                out.depth = depth;
                out.part = cPart[i];
                out.index = -1;
            }
        }
        for (int i = 0; i < sphCount; i++) {
            if (spR[i] <= 0.0) {
                continue;
            }
            int o = i * 3;
            double d = Math.sqrt(sq(px - spC[o]) + sq(py - spC[o + 1]) + sq(pz - spC[o + 2]));
            double depth = spR[i] + radius - d;
            if (depth > best) {
                best = depth;
                found = true;
                setNormal(out, px - spC[o], py - spC[o + 1], pz - spC[o + 2], d);
                out.depth = depth;
                out.part = spPart[i];
                out.index = i;
            }
        }
        for (int i = 0; i < triCount; i++) {
            closestOnTri(px, py, pz, i, v);
            double d = Math.sqrt(sq(px - v.x) + sq(py - v.y) + sq(pz - v.z));
            double depth = tH[i] + radius - d;
            if (depth > best) {
                best = depth;
                found = true;
                if (d < 1.0E-6) {
                    triNormal(i, out, px, py, pz);
                } else {
                    setNormal(out, px - v.x, py - v.y, pz - v.z, d);
                }
                out.depth = depth;
                out.part = PART_MOSS;
                out.index = -1;
            }
        }
        return found;
    }

    /** 点 p から形のいちばん近い表面までの距離(だいたい。めり込んでいれば 0)。 */
    public double nearest(double px, double py, double pz) {
        if (shapes == null) {
            return Double.MAX_VALUE;
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < sliceCount; i++) {
            int o = i * 3;
            double ex = sA[o] + sEz[o] * sLen[i] * 0.5;
            double ey = sA[o + 1] + sEz[o + 1] * sLen[i] * 0.5;
            double ez = sA[o + 2] + sEz[o + 2] * sLen[i] * 0.5;
            double d = Math.sqrt(sq(px - ex) + sq(py - ey) + sq(pz - ez)) - Math.max(sRx[i], sRy[i]) - sLen[i] * 0.5;
            best = Math.min(best, d);
        }
        for (int i = 0; i < capCount; i++) {
            if (cR[i] <= 0.0) {
                continue;
            }
            closestOnSeg(px, py, pz, cA, cB, i, v);
            best = Math.min(best, Math.sqrt(sq(px - v.x) + sq(py - v.y) + sq(pz - v.z)) - cR[i]);
        }
        for (int i = 0; i < sphCount; i++) {
            if (spR[i] <= 0.0) {
                continue;
            }
            int o = i * 3;
            best = Math.min(best, Math.sqrt(sq(px - spC[o]) + sq(py - spC[o + 1]) + sq(pz - spC[o + 2])) - spR[i]);
        }
        for (int i = 0; i < triCount; i++) {
            closestOnTri(px, py, pz, i, v);
            best = Math.min(best, Math.sqrt(sq(px - v.x) + sq(py - v.y) + sq(pz - v.z)) - tH[i]);
        }
        return Math.max(0.0, best);
    }

    /**
     * {@link #nearest} と同じ測り方で、いちばん近い所の体の表面の点(だいたい)を {@code out} へ(10-19。放電の雷が体のどこから飛ぶか)。
     * 戻り値は距離(体の中なら 0)。形が無ければ {@link Double#MAX_VALUE}。
     */
    public double nearestPoint(double px, double py, double pz, double[] out) {
        if (shapes == null) {
            return Double.MAX_VALUE;
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < sliceCount; i++) {
            int o = i * 3;
            double ex = sA[o] + sEz[o] * sLen[i] * 0.5;
            double ey = sA[o + 1] + sEz[o + 1] * sLen[i] * 0.5;
            double ez = sA[o + 2] + sEz[o + 2] * sLen[i] * 0.5;
            double r = Math.max(sRx[i], sRy[i]) + sLen[i] * 0.5;
            double c = Math.sqrt(sq(px - ex) + sq(py - ey) + sq(pz - ez));
            if (c - r < best) {
                best = c - r;
                surface(ex, ey, ez, px, py, pz, c, Math.min(r, Math.max(sRx[i], sRy[i])), out);
            }
        }
        for (int i = 0; i < capCount; i++) {
            if (cR[i] <= 0.0) {
                continue;
            }
            closestOnSeg(px, py, pz, cA, cB, i, v);
            double c = Math.sqrt(sq(px - v.x) + sq(py - v.y) + sq(pz - v.z));
            if (c - cR[i] < best) {
                best = c - cR[i];
                surface(v.x, v.y, v.z, px, py, pz, c, cR[i], out);
            }
        }
        for (int i = 0; i < sphCount; i++) {
            if (spR[i] <= 0.0) {
                continue;
            }
            int o = i * 3;
            double c = Math.sqrt(sq(px - spC[o]) + sq(py - spC[o + 1]) + sq(pz - spC[o + 2]));
            if (c - spR[i] < best) {
                best = c - spR[i];
                surface(spC[o], spC[o + 1], spC[o + 2], px, py, pz, c, spR[i], out);
            }
        }
        for (int i = 0; i < triCount; i++) {
            closestOnTri(px, py, pz, i, v);
            double c = Math.sqrt(sq(px - v.x) + sq(py - v.y) + sq(pz - v.z));
            if (c - tH[i] < best) {
                best = c - tH[i];
                surface(v.x, v.y, v.z, px, py, pz, c, tH[i], out);
            }
        }
        return Math.max(0.0, best);
    }

    /** 中心 (cx, cy, cz) から点 (px, py, pz) の向きへ半径 r の所(距離 c が 0 なら中心)。 */
    private static void surface(double cx, double cy, double cz, double px, double py, double pz, double c, double r, double[] out) {
        double k = c < 1.0E-6 ? 0.0 : Math.min(1.0, r / c);
        out[0] = cx + (px - cx) * k;
        out[1] = cy + (py - cy) * k;
        out[2] = cz + (pz - cz) * k;
    }

    private static void setNormal(Contact out, double x, double y, double z, double d) {
        if (d < 1.0E-6) {
            out.nx = 0.0;
            out.ny = 1.0;
            out.nz = 0.0;
        } else {
            out.nx = x / d;
            out.ny = y / d;
            out.nz = z / d;
        }
    }

    private void triNormal(int i, Contact out, double px, double py, double pz) {
        int o = i * 9;
        double e1x = tP[o + 3] - tP[o];
        double e1y = tP[o + 4] - tP[o + 1];
        double e1z = tP[o + 5] - tP[o + 2];
        double e2x = tP[o + 6] - tP[o];
        double e2y = tP[o + 7] - tP[o + 1];
        double e2z = tP[o + 8] - tP[o + 2];
        double nx = e1y * e2z - e1z * e2y;
        double ny = e1z * e2x - e1x * e2z;
        double nz = e1x * e2y - e1y * e2x;
        double l = Math.max(1.0E-9, Math.sqrt(nx * nx + ny * ny + nz * nz));
        double side = (px - tP[o]) * nx + (py - tP[o + 1]) * ny + (pz - tP[o + 2]) * nz >= 0.0 ? 1.0 : -1.0;
        out.nx = nx / l * side;
        out.ny = ny / l * side;
        out.nz = nz / l * side;
    }

    private static void closestOnSeg(double px, double py, double pz, double[] a, double[] b, int i, Vector3d out) {
        int o = i * 3;
        double abx = b[o] - a[o];
        double aby = b[o + 1] - a[o + 1];
        double abz = b[o + 2] - a[o + 2];
        double l2 = abx * abx + aby * aby + abz * abz;
        double t = l2 < 1.0E-12 ? 0.0 : ((px - a[o]) * abx + (py - a[o + 1]) * aby + (pz - a[o + 2]) * abz) / l2;
        t = Math.max(0.0, Math.min(1.0, t));
        out.set(a[o] + abx * t, a[o + 1] + aby * t, a[o + 2] + abz * t);
    }

    /** 三角形 i の上で点 p にいちばん近い点(Ericson, Real-Time Collision Detection 5.1.5)。 */
    private void closestOnTri(double px, double py, double pz, int i, Vector3d out) {
        int o = i * 9;
        double ax = tP[o];
        double ay = tP[o + 1];
        double az = tP[o + 2];
        double abx = tP[o + 3] - ax;
        double aby = tP[o + 4] - ay;
        double abz = tP[o + 5] - az;
        double acx = tP[o + 6] - ax;
        double acy = tP[o + 7] - ay;
        double acz = tP[o + 8] - az;
        double apx = px - ax;
        double apy = py - ay;
        double apz = pz - az;
        double d1 = abx * apx + aby * apy + abz * apz;
        double d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0.0 && d2 <= 0.0) {
            out.set(ax, ay, az);
            return;
        }
        double bpx = px - tP[o + 3];
        double bpy = py - tP[o + 4];
        double bpz = pz - tP[o + 5];
        double d3 = abx * bpx + aby * bpy + abz * bpz;
        double d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0.0 && d4 <= d3) {
            out.set(tP[o + 3], tP[o + 4], tP[o + 5]);
            return;
        }
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) {
            double w = d1 / (d1 - d3);
            out.set(ax + abx * w, ay + aby * w, az + abz * w);
            return;
        }
        double cpx = px - tP[o + 6];
        double cpy = py - tP[o + 7];
        double cpz = pz - tP[o + 8];
        double d5 = abx * cpx + aby * cpy + abz * cpz;
        double d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0.0 && d5 <= d6) {
            out.set(tP[o + 6], tP[o + 7], tP[o + 8]);
            return;
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) {
            double w = d2 / (d2 - d6);
            out.set(ax + acx * w, ay + acy * w, az + acz * w);
            return;
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0.0 && (d4 - d3) >= 0.0 && (d5 - d6) >= 0.0) {
            double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            out.set(tP[o + 3] + (tP[o + 6] - tP[o + 3]) * w, tP[o + 4] + (tP[o + 7] - tP[o + 4]) * w, tP[o + 5] + (tP[o + 8] - tP[o + 5]) * w);
            return;
        }
        double denom = 1.0 / (va + vb + vc);
        double vv = vb * denom;
        double ww = vc * denom;
        out.set(ax + abx * vv + acx * ww, ay + aby * vv + acy * ww, az + abz * vv + acz * ww);
    }

    private static double segPointDist(double ax, double ay, double az, double dx, double dy, double dz, double px, double py, double pz) {
        double l2 = dx * dx + dy * dy + dz * dz;
        double t = l2 < 1.0E-12 ? 0.0 : Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / l2));
        return Math.sqrt(sq(ax + dx * t - px) + sq(ay + dy * t - py) + sq(az + dz * t - pz));
    }

    private static double dist(double[] a, int o, double[] b) {
        return Math.sqrt(sq(a[o] - b[0]) + sq(a[o + 1] - b[1]) + sq(a[o + 2] - b[2]));
    }

    private static double lerp(float a, float b, double t) {
        return a + (b - a) * t;
    }

    private static double sq(double v) {
        return v * v;
    }
}
