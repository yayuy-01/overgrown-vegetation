package com.yayuy.overgrownvegetation.entity.legs;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 地形に合わせた足の置き場(2026-11-07、行動と移動の作り直し・層 1 の「足の置き方」。試作はファルキムネス)。
 *
 * <p><b>見た目だけ</b>: クライアントの tick で、描画する足先の置き場を決める。サーバーの当たり判定・経路は今のまま
 * (Mob は今までどおり箱で動き、ここは「その箱の下で脚がどこを踏んで見えるか」だけを決める)。脚の曲げ(IK)は描画側
 * ({@code client/FalcimnesLegRig})が、ここで決めた足先へ向けて解く。
 *
 * <h2>決め方</h2>
 * <ol>
 *   <li>脚ごとの<b>置きたい所</b> = 体の向きで回した基準の置き場 + 体の動きの先回り(速さ × {@link #lead} tick、回る速さも)。</li>
 *   <li>その真下の地面を上から測る({@link #probe}。ブロックの当たりの形。草など当たりの無い物は通り抜ける)。
 *       幹の中に入る所は、体の側へ寄せて測り直す。</li>
 *   <li>着いている足は動かない。置きたい所から {@link #stepAt} 以上ずれた脚だけが<b>踏み替える</b>。
 *       踏み替えは<b>対角の組み</b>(左前 + 右後 / 右前 + 左後)で、もう片方の組みが宙にある間は待つ。</li>
 *   <li>宙の足は弧を描いて置きたい所へ(着く所は毎 tick 測り直す)。段差を上がるときは高い方の上を越える。</li>
 *   <li>胴の高さ・前後の傾き・左右の傾きは、着いている足の高さから決める({@link #bodyLift} ほか)。</li>
 * </ol>
 * 数値は全部【仮】(2026-11-07 Claude)。
 */
public final class FootPlanter {

    /** 脚の基準の置き場(ブロック、体格 1)。left = 体の左へ、fwd = 前へ。 */
    private final double[] homeLeft, homeFwd;
    /** 対角の組み(脚ごとに 0 か 1)。 */
    private final int[] group;
    private final int n;

    /** 踏み替えるずれ(ブロック、体格 1)【仮】。 */
    public double stepAt = 0.45;
    /** 宙の足を上げる高さ(ブロック、体格 1)【仮】。 */
    public double lift = 0.35;
    /** 体の動きを先回りする tick【仮】。踏み替えの長さと同じくらい(着いた所が置き場の少し前になる)。 */
    public double lead = 6;
    /** 測る範囲: 体の高さから上へ・下へ(ブロック、体格 1)【仮】。上は 1 段を越える分、下は脚が届く分。 */
    public double probeUp = 1.3, probeDown = 2.6;   // 下は 11-07 夜に 1.8 → 2.6(1 マスの段が続く急な坂で下側の足が地面を見つけられず浮いた。3 マスの崖の下までは伸ばさない)
    /** 脚の置き場の横の幅の倍率(1 = 基準。段 3 の「前を起こして細くなる」構えで脚を寄せる、11-08 夜)【仮】。 */
    public double lateral = 1.0;
    /** 踏み替えの長さ(tick)。0 = 既定(速いほど短く 4〜7 tick)。大股でゆっくり運ぶ種が毎 tick 渡す(11-14、ヴェナムネス)。 */
    public int swingTicks = 0;
    /** もう片方の組みが着いてから、この組みが踏み替えを始めるまでに空ける tick(0 = 既定 = 空けない)。左右を半周期ずらして交互に運ぶために(11-14、ヴェナムネス)。 */
    public int restGap = 0;
    /** 胴の傾きを地形の傾きに合わせる割合【仮】。 */
    public static final double FOLLOW = 0.85D;

    // 脚ごとの状態(ワールド座標)
    private final Vec3[] planted, from, now, prev;
    private final boolean[] swinging;
    /** 地面の無い脚(垂らしている)と、地面あり / なしが続いた tick(切り替えのためらい)。 */
    private final boolean[] hanging;
    private final int[] groundStreak, noGroundStreak;
    /** この tick の測り(ground の結果)が地面なしだったか。 */
    private boolean lastHang;
    private final float[] swingT;
    private final int[] swingLen;
    private boolean ready;
    /** 組みごとの、最後に足が着いてからの tick({@link #restGap} 用)。 */
    private final int[] sinceLanding = {1000, 1000};

    // 胴(今と 1 tick 前。描画で補間する)
    private double lift0, lift1, pitch0, pitch1, roll0, roll1;
    /** 手続きの効き(0 = 手付けのまま、1 = 全部手続き)。切り替えを数 tick でならす。 */
    private float weight0, weight1;
    private Vec3 lastPos;
    private float lastYaw;

    public FootPlanter(double[] homeLeft, double[] homeFwd, int[] group) {
        this.homeLeft = homeLeft;
        this.homeFwd = homeFwd;
        this.group = group;
        this.n = homeLeft.length;
        this.planted = new Vec3[n];
        this.from = new Vec3[n];
        this.now = new Vec3[n];
        this.prev = new Vec3[n];
        this.swinging = new boolean[n];
        this.hanging = new boolean[n];
        this.groundStreak = new int[n];
        this.noGroundStreak = new int[n];
        this.swingT = new float[n];
        this.swingLen = new int[n];
    }

    public int legs() {
        return n;
    }

    /**
     * 1 tick 進める(クライアント)。{@code enabled} が false の間は効きを 0 へ下ろし、足は体に付いて動く
     * (次に効き始めたとき、足が遠くから飛んでこない)。
     */
    public void tick(LivingEntity e, boolean enabled, double scale) {
        Level level = e.level();
        Vec3 pos = e.position();
        float yaw = e.yBodyRot;
        for (int i = 0; i < n; i++) {
            prev[i] = now[i];
        }
        lift0 = lift1;
        pitch0 = pitch1;
        roll0 = roll1;
        vLift0 = vLift;
        vPitch0 = vPitch;
        vRoll0 = vRoll;
        weight0 = weight1;
        weight1 = Mth.clamp(weight1 + (enabled ? 0.25F : -0.25F), 0.0F, 1.0F);

        Vec3 vel = lastPos == null ? Vec3.ZERO : pos.subtract(lastPos);
        float yawRate = lastPos == null ? 0.0F : Mth.wrapDegrees(yaw - lastYaw);
        boolean jumped = lastPos != null && vel.lengthSqr() > 4.0D * 4.0D;   // 置き直し・遠くへ飛んだ
        lastPos = pos;
        lastYaw = yaw;

        if (!ready || jumped || weight1 <= 0.0F) {
            // 置き場へそのまま(効いていない間も体に付いて動く)
            for (int i = 0; i < n; i++) {
                Vec3 h = ground(level, desired(pos, yaw, i, Vec3.ZERO, 0.0F, scale), pos, yaw, scale);
                hanging[i] = lastHang;
                if (lastHang) {
                    h = hangPoint(pos, yaw, i, scale);
                }
                groundStreak[i] = 0;
                noGroundStreak[i] = 0;
                wantY[i] = h.y;
                planted[i] = h;
                now[i] = h;
                if (!ready || jumped) {
                    prev[i] = h;
                }
                swinging[i] = false;
            }
            ready = true;
            updateBody(pos, scale, true);
            return;
        }

        double horiz = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        // 速いほど踏み替えを短く(4〜7 tick)【仮】
        int len = swingTicks > 0 ? swingTicks : Mth.clamp((int) Math.round(7.0D - horiz / scale * 25.0D), 4, 7);

        // 置きたい所。地面あり / なしの切り替えにはためらいを持たせる(縁で毎 tick 入れ替わると足が震えた、11-07 夜)
        Vec3[] want = new Vec3[n];
        boolean[] wantHang = new boolean[n];
        for (int i = 0; i < n; i++) {
            Vec3 g = ground(level, desired(pos, yaw, i, vel, yawRate, scale), pos, yaw, scale);
            if (lastHang) {
                noGroundStreak[i]++;
                groundStreak[i] = 0;
            } else {
                groundStreak[i]++;
                noGroundStreak[i] = 0;
            }
            if (hanging[i]) {
                wantHang[i] = groundStreak[i] < GROUND_AFTER;
            } else {
                wantHang[i] = noGroundStreak[i] >= HANG_AFTER;
            }
            if (wantHang[i]) {
                want[i] = hangPoint(pos, yaw, i, scale);
            } else if (lastHang) {
                want[i] = planted[i];   // まだ地面なしが続いていない: 今の置き場のまま
            } else {
                want[i] = g;
            }
        }
        for (int i = 0; i < n; i++) {
            wantY[i] = want[i].y;
        }
        // 宙の足を進める
        for (int i = 0; i < n; i++) {
            if (!swinging[i]) {
                continue;
            }
            swingT[i] += 1.0F / swingLen[i];
            if (swingT[i] >= 1.0F) {
                swinging[i] = false;
                planted[i] = want[i];
                now[i] = want[i];
                hanging[i] = wantHang[i];
                sinceLanding[group[i]] = 0;
            } else {
                now[i] = arc(from[i], want[i], swingT[i], scale);
            }
        }
        // 垂らしている脚: 体に付いた決まった形へゆっくり寄せる(踏み替えには加わらない)
        for (int i = 0; i < n; i++) {
            if (!swinging[i] && hanging[i] && wantHang[i]) {
                now[i] = now[i].lerp(want[i], 0.3D);
                planted[i] = now[i];
            }
        }
        sinceLanding[0]++;
        sinceLanding[1]++;
        // 踏み替えを始める(組みごと。もう片方の組みが宙にある間は待つ)
        for (int g = 0; g < 2; g++) {
            boolean otherUp = false, selfUp = false, need = false, force = false;
            for (int i = 0; i < n; i++) {
                if (swinging[i]) {
                    if (group[i] == g) {
                        selfUp = true;
                    } else {
                        otherUp = true;
                    }
                }
                if (group[i] == g && !swinging[i]) {
                    if (hanging[i] && wantHang[i]) {
                        continue;
                    }
                    if (hanging[i] != wantHang[i]) {
                        need = true;   // 垂らす ⇄ 着く の切り替えは踏み替えで
                        continue;
                    }
                    double err = error(planted[i], want[i]);
                    if (err > stepAt * scale) {
                        need = true;
                    }
                    if (err > stepAt * scale * 2.2D) {
                        force = true;   // 置いていかれすぎ(押された・急に回った)
                    }
                }
            }
            if (selfUp || !need || ((otherUp || sinceLanding[1 - g] < restGap) && !force)) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                if (group[i] != g || swinging[i] || (hanging[i] && wantHang[i])) {
                    continue;
                }
                if (hanging[i] != wantHang[i] || error(planted[i], want[i]) > stepAt * scale * 0.25D) {
                    swinging[i] = true;
                    swingT[i] = 0.0F;
                    swingLen[i] = len;
                    from[i] = now[i];
                }
            }
        }
        // 着いている足はそのまま
        for (int i = 0; i < n; i++) {
            if (!swinging[i] && !hanging[i]) {
                now[i] = planted[i];
            }
        }
        updateBody(pos, scale, false);
    }

    /** 置き場を寄せて測り直す順(前後の割合, 横の割合)【仮】。 */
    private static final double[][] PULLS = {{1, 1}, {0.6, 1}, {0.2, 1}, {1, 0.7}, {0.6, 0.7}, {0.2, 0.7}, {0.4, 0.45}};
    /** 地面なしが続いたら垂らす / 地面ありが続いたら着く(tick)【仮】。 */
    private static final int HANG_AFTER = 3, GROUND_AFTER = 2;
    /** 胴の高さ: いちばん低い足へ脚が届く量・いちばん高い足へ脚を畳める量(ブロック、体格 1)【仮】。 */
    private static final double DOWN_REACH = 0.3D, UP_REACH = 1.0D;

    /** 垂らす足の置き場: 基準の置き場の少し内・体の高さから 1.2 下(描画の IK が届く所へ寄せる)。 */
    private Vec3 hangPoint(Vec3 pos, float yawDeg, int i, double scale) {
        float yaw = yawDeg * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        double l = homeLeft[i] * lateral * scale * 0.8D, f = homeFwd[i] * scale * 0.8D;
        return new Vec3(pos.x + lx * l + fx * f, pos.y - 1.2D * scale, pos.z + lz * l + fz * f);
    }

    /** 置きたい所(地面を測る前)。 */
    private Vec3 desired(Vec3 pos, float yawDeg, int i, Vec3 vel, float yawRate, double scale) {
        float yaw = (yawDeg + yawRate * (float) lead) * Mth.DEG_TO_RAD;
        // 前 = (−sin, 0, cos)、左 = (cos, 0, sin)(MC の向き: yaw 0 で +Z を向き、左が +X)
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        double l = homeLeft[i] * lateral * scale, f = homeFwd[i] * scale;
        return new Vec3(pos.x + lx * l + fx * f + vel.x * lead, pos.y, pos.z + lz * l + fz * f + vel.z * lead);
    }

    /** ずれ(水平の距離 + 高さの差を少し重く)。 */
    private static double error(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z, dy = a.y - b.y;
        return Math.sqrt(dx * dx + dz * dz + 1.5D * dy * dy);
    }

    /** 宙の足の弧。段を上がるときは高い方の上を越える。 */
    private Vec3 arc(Vec3 a, Vec3 b, float t, double scale) {
        double s = t * t * (3.0D - 2.0D * t);
        double x = Mth.lerp(s, a.x, b.x), z = Mth.lerp(s, a.z, b.z);
        double base = Mth.lerp(s, a.y, b.y);
        double top = Math.max(a.y, b.y) + lift * scale;
        double up = Math.sin(Math.PI * t);
        return new Vec3(x, base + (top - base) * up, z);
    }

    /**
     * 地面の高さを測る。真下が幹など(測り始めがブロックの中)や、届く所に地面が無い(崖の縁)ときは、体の側へ寄せて測り直す
     * (縁の手前に足を掛ける)。どこにも無ければ {@link #lastHang} を立てる(呼んだ側が垂らす)。
     */
    private Vec3 ground(Level level, Vec3 at, Vec3 body, float yawDeg, double scale) {
        lastHang = false;
        // 置き場のずれを体の前後と横に分け、まず前後だけ、次に横も縮めて試す(体の中心へまっすぐ寄せると左右の幅が潰れた、11-07 夜)
        float yaw = yawDeg * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        double dx = at.x - body.x, dz = at.z - body.z;
        double f = dx * fx + dz * fz, l = dx * lx + dz * lz;
        for (double[] k : PULLS) {
            double x = body.x + fx * f * k[0] + lx * l * k[1], z = body.z + fz * f * k[0] + lz * l * k[1];
            double y = probe(level, x, z, body.y + probeUp * scale, body.y - probeDown * scale);
            if (Double.isNaN(y) || Double.isInfinite(y)) {
                continue;
            }
            return new Vec3(x, y, z);
        }
        lastHang = true;
        return new Vec3(at.x, body.y, at.z);
    }

    /**
     * (x, z) の列を top から bottom へ測る。最初の当たりの面の高さ。測り始めがブロックの中なら NaN、
     * 届く所に地面が無ければ −∞。
     */
    private static double probe(Level level, double x, double z, double top, double bottom) {
        Vec3 a = new Vec3(x, top, z), b = new Vec3(x, bottom, z);
        BlockHitResult hit = level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                (net.minecraft.world.entity.Entity) null));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return Double.NEGATIVE_INFINITY;
        }
        if (hit.isInside()) {
            return Double.NaN;
        }
        return hit.getLocation().y;
    }

    /**
     * 胴の高さと傾き(地面に着いている足から。垂らしている足は数えない)。
     * 高さは足の高さの平均を、<b>いちばん低い足へ脚が届く</b>({@link #DOWN_REACH})・いちばん高い足へ畳める({@link #UP_REACH})範囲に収める
     * (急な坂で下側の足が届かず浮いた、11-07 夜)。傾きは前と後ろ・左と右の足の高さの差を置き場どうしの距離で割った角の {@link #FOLLOW}。
     */
    private void updateBody(Vec3 pos, double scale, boolean snap) {
        double sum = 0, fy = 0, hy = 0, ly = 0, ry = 0, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        int cnt = 0, fn = 0, hn = 0, ln = 0, rn = 0;
        double fz = 0, hz = 0, lw = 0, rw = 0;
        for (int i = 0; i < n; i++) {
            if (hanging[i]) {
                continue;
            }
            // ワールドの高さで持つ(実体の y は段で 1 tick に 1 マス跳ねるので使わない、11-07 夜)。宙の足は踏み替えの途中の高さ
            // (弧の持ち上げは除く)、そこへ置きたい所の高さ(先回りした所 = 先読み)を混ぜる
            double base;
            if (swinging[i]) {
                double s = swingT[i] * swingT[i] * (3.0D - 2.0D * swingT[i]);
                base = Mth.lerp(s, from[i].y, wantY[i]);
            } else {
                base = now[i].y;
            }
            double y = Mth.lerp(LOOK_AHEAD, base, wantY[i]);
            sum += y;
            cnt++;
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            if (homeFwd[i] > 0) {
                fy += y;
                fz += homeFwd[i];
                fn++;
            } else {
                hy += y;
                hz += homeFwd[i];
                hn++;
            }
            if (homeLeft[i] > 0) {
                ly += y;
                lw += homeLeft[i] * lateral;
                ln++;
            } else {
                ry += y;
                rw += homeLeft[i] * lateral;
                rn++;
            }
        }
        double tY = pos.y, tPitch = 0, tRoll = 0;
        if (cnt > 0) {
            tY = sum / cnt;
            tY = Math.max(tY, maxY - UP_REACH * scale);
            tY = Math.min(tY, minY + DOWN_REACH * scale);   // 届かない足を作らない方を勝たせる
            tY = Mth.clamp(tY, pos.y - 1.8D * scale, pos.y + 0.8D * scale);
        }
        if (fn > 0 && hn > 0) {
            tPitch = Math.atan2(fy / fn - hy / hn, (fz / fn - hz / hn) * scale);
        }
        if (ln > 0 && rn > 0) {
            tRoll = Math.atan2(ly / ln - ry / rn, (lw / ln - rw / rn) * scale);
        }
        // 地形の傾きの 8.5 割(体が長いので、全部合わせると尾・頭が坂へ刺さる)。上限 26 度・17 度【仮】
        tPitch = Mth.clamp(tPitch * FOLLOW, -0.45D, 0.45D);
        tRoll = Mth.clamp(tRoll * FOLLOW, -0.3D, 0.3D);
        if (snap) {
            lift0 = lift1 = tY;
            pitch0 = pitch1 = tPitch;
            roll0 = roll1 = tRoll;
            vLift = vPitch = vRoll = 0;
            vLift0 = vPitch0 = vRoll0 = 0;
            return;
        }
        // 臨界減衰のばねで追う(1 tick を 4 つに割って積む)。傾きは角速度に上限
        for (int k = 0; k < 4; k++) {
            double dt = 0.25D;
            vLift += (SPRING_Y * SPRING_Y * (tY - lift1) - 2.0D * SPRING_Y * vLift) * dt;
            vLift = Mth.clamp(vLift, -MAX_VY * scale, MAX_VY * scale);
            lift1 += vLift * dt;
            vPitch += (SPRING_A * SPRING_A * (tPitch - pitch1) - 2.0D * SPRING_A * vPitch) * dt;
            vPitch = Mth.clamp(vPitch, -MAX_VA, MAX_VA);
            pitch1 += vPitch * dt;
            vRoll += (SPRING_A * SPRING_A * (tRoll - roll1) - 2.0D * SPRING_A * vRoll) * dt;
            vRoll = Mth.clamp(vRoll, -MAX_VA, MAX_VA);
            roll1 += vRoll * dt;
        }
    }

    /** 胴のばね: 高さ・傾きの固有角振動数(/tick。臨界減衰で目標の 9 割へ約 8 tick)【仮】。 */
    private static final double SPRING_Y = 0.5D, SPRING_A = 0.45D;
    /** 胴の上下の速さの上限(ブロック/tick、体格 1)と傾きの角速度の上限(ラジアン/tick ≒ 3.4 度)【仮】。 */
    private static final double MAX_VY = 0.18D, MAX_VA = 0.06D;
    /** 胴の目標に混ぜる「置きたい所の高さ」の割合(先読み)【仮】。 */
    private static final double LOOK_AHEAD = 0.35D;
    private double vLift, vPitch, vRoll;
    /** tick の初めの速さ(描画の補間を速さも使うエルミートにして、tick の継ぎ目で動きが折れないように)。 */
    private double vLift0, vPitch0, vRoll0;

    /** 3 次のエルミート補間(1 tick の間。p0・v0 から p1・v1 へ)。 */
    private static double hermite(float t, double p0, double v0, double p1, double v1) {
        double t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * p0 + (t3 - 2 * t2 + t) * v0 + (-2 * t3 + 3 * t2) * p1 + (t3 - t2) * v1;
    }
    /** この tick の置きたい所の高さ(先読みと、宙の足の行き先)。 */
    private final double[] wantY = new double[8];

    // ------------------------------------------------------------------ 描画から読む

    public boolean ready() {
        return ready;
    }

    /** 足先(ワールド座標、補間)。 */
    public Vec3 foot(int i, float pt) {
        Vec3 a = prev[i] == null ? now[i] : prev[i];
        return a.lerp(now[i], pt);
    }

    /** 胴のワールドの高さ(補間。実体の y から切り離してある。描画は これ − 実体の y を胴に足す)。 */
    public double bodyWorldY(float pt) {
        return hermite(pt, lift0, vLift0, lift1, vLift);
    }

    /** 胴の前後の傾き(ラジアン、+ で前が上がる)。 */
    public double bodyPitch(float pt) {
        return hermite(pt, pitch0, vPitch0, pitch1, vPitch);
    }

    /** 胴の左右の傾き(ラジアン、+ で左が上がる)。 */
    public double bodyRoll(float pt) {
        return hermite(pt, roll0, vRoll0, roll1, vRoll);
    }

    public float weight(float pt) {
        return Mth.lerp(pt, weight0, weight1);
    }

    public boolean swinging(int i) {
        return swinging[i];
    }

    /** 踏み替えの進み(0〜1。着いている間は 0)。 */
    public float swingProgress(int i) {
        return swinging[i] ? swingT[i] : 0.0F;
    }

    /** 垂らしている(地面の無い)脚か。 */
    public boolean hanging(int i) {
        return hanging[i];
    }
}
