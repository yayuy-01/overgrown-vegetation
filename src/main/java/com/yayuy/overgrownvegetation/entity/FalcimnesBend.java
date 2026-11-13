package com.yayuy.overgrownvegetation.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * ファルキムネスの体の曲げ(11-08、森の中を動く型 ② の段 2。決定「胴と腹は頭の通った跡をなぞる。見た目はクライアントの描画で曲げる」)。
 *
 * <p>地形とぶつかるのは胴の芯だけで、長い首(前 5.4)と腹(後ろ 4.1)は芯の向きのまま突き出ていた(段 1 の弱いところ「隙間を抜ける間、
 * 首・腹が幹を突き抜けて見える」)。ここでは、歩いている間だけ
 * <ul>
 *   <li>首と頭を<b>これから通る経路</b>に沿わせ(頭が先に隙間へ入り、胴はその跡を通る)、</li>
 *   <li>腹を<b>芯が通ってきた跡</b>に沿わせる。</li>
 * </ul>
 * 骨の付け根(首 4 つ・腹 4 つ)ごとに「体に対する向き」をサーバーが求め、1 バイトずつ 1 つの long で送る。クライアントはそれで骨を横へ曲げ
 * ({@code client/FalcimnesBendRig})、叩ける部位もサーバー・クライアントの両方で同じ値で曲げる(照準と当たりが食い違わない)。
 * 技の最中・止まっている間・その場で回っている間は 0 へ戻す(技のアニメは体をまっすぐにして作ってある)。数値は全部【仮】。
 */
public final class FalcimnesBend {

    /** 経路の重み(OvergrownNodeEvaluator の長い体)【仮】: 幹から 2.0 より近いと 1 ブロックあたり 1.0、45 度を超える曲がりは 45 度ごとに 0.6(幹から 1.5 以内なら倍)。 */
    public static final OvergrownNodeEvaluator.LongBody LONG_BODY = new OvergrownNodeEvaluator.LongBody(2.0F, 1.0F, 0.6F, 1.5F);

    /** 首の骨の付け根(体格 1 のブロック、体の前へ): neck・bone2・bone3・head。bone(2.80)は neck と bone2 の間を補う。 */
    static final double[] FRONT = {1.474D, 3.280D, 4.453D, 5.428D};
    static final double FRONT_BONE = 2.798D;
    /** 腹の骨の付け根(体の後ろへ): bone21・bone22・bone23・bone24。bone21 は骨の pivot(1.294)でなく胸とのつなぎ目(0.328)で回す(描画も同じ。11-08 夜)。 */
    static final double[] BACK = {0.328D, 2.284D, 3.206D, 4.084D};
    /** 体に対する向きの上限(度)と、隣の骨との差の上限(度)【仮】。 */
    static final float MAX_ABS = 70.0F, MAX_JOINT = 35.0F;
    /** 1 tick に変わる角度の上限(度)・これより後ろを指す経路 / 跡は曲げない(度)・瞬間移動とみなす 1 tick の動き(ブロック)【仮】。11-08 夜。 */
    static final float MAX_STEP = 10.0F, BEHIND = 100.0F;
    static final double TELEPORT = 3.0D;
    /** 曲げの効きが歩き出し・止まりで変わる速さ(1 tick あたり)【仮】。 */
    static final float WEIGHT_RATE = 0.08F;
    /** 前へこれ以上進んだ tick を「歩いている」とする(ブロック / tick)【仮】。 */
    static final double WALK_MIN = 0.02D;
    /** 向きを見る弦の半分の長さ(ブロック)【仮】。経路の折れ目で向きが跳ねないように。 */
    static final double CHORD = 0.7D;
    /** 通った跡を残す間隔(ブロック)と数。 */
    static final double TRAIL_STEP = 0.25D;
    static final int TRAIL_MAX = 48;

    // --- サーバー ---
    private final double[] trailX = new double[TRAIL_MAX], trailZ = new double[TRAIL_MAX];
    private int trailCount;
    private float weight;
    private int stillTicks;
    private double lastX = Double.NaN, lastZ;

    // --- 両側: 今の角度(度。前 4・後 4)。クライアントは前の tick の値も持って補間する ---
    private final float[] now = new float[8];
    private final float[] prev = new float[8];
    private long lastPacked;

    private static final boolean DEV_HOOKS = Boolean.getBoolean("ov.devbridge");

    /** 計測用(サーバー)。 */
    public static volatile long serverNanos, serverCalls;

    // ------------------------------------------------------------------ サーバー

    /** 1 tick 進め、送る値を返す(aiStep の移動の後)。 */
    long serverTick(FalcimnesEntity e) {
        long t0 = System.nanoTime();
        double x = e.getX(), z = e.getZ();
        float yaw = e.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        double mx = Double.isNaN(this.lastX) ? 0.0D : x - this.lastX, mz = Double.isNaN(this.lastX) ? 0.0D : z - this.lastZ;
        double v = mx * fx + mz * fz;
        // 11-08 夜: 瞬間移動・大きく押し出された(1 tick に TELEPORT 以上)ときは、跡を捨ててまっすぐから(跡が遠くへ飛んだ線になるので)
        boolean jumped = mx * mx + mz * mz > TELEPORT * TELEPORT;
        this.lastX = x;
        this.lastZ = z;
        boolean free = e.getAction() == FalcimnesEntity.ACT_NONE && !e.isDeadOrDying() && !e.isPassenger() && !e.isInWater();
        boolean walking = free && v > WALK_MIN;
        this.stillTicks = walking ? 0 : this.stillTicks + 1;
        if (!free || this.stillTicks > 20 || jumped) {
            if (jumped) this.weight = 0.0F;
            this.trailCount = 0;   // 止まった・技を出した: 跡を捨てる(歩き出したら今の向きからまっすぐ後ろ)
        }
        // 跡
        if (this.trailCount == 0 || Math.hypot(x - this.trailX[0], z - this.trailZ[0]) >= TRAIL_STEP) {
            System.arraycopy(this.trailX, 0, this.trailX, 1, TRAIL_MAX - 1);
            System.arraycopy(this.trailZ, 0, this.trailZ, 1, TRAIL_MAX - 1);
            this.trailX[0] = x;
            this.trailZ[0] = z;
            this.trailCount = Math.min(TRAIL_MAX, this.trailCount + 1);
        }
        this.weight = Mth.clamp(this.weight + (walking ? WEIGHT_RATE : -WEIGHT_RATE), 0.0F, 1.0F);
        float[] a = new float[8];
        if (this.weight > 0.0F) {
            double s = e.getScale();
            // 前: これから通る経路(今の位置 → 経路の次のノードから先)
            Path path = e.getNavigation().getPath();
            int n = 1;
            double[] px = new double[10], pz = new double[10];
            px[0] = x;
            pz[0] = z;
            if (path != null && !path.isDone()) {
                for (int i = path.getNextNodeIndex(); i < path.getNodeCount() && n < px.length; i++) {
                    Vec3 p = path.getEntityPosAtNode(e, i);
                    px[n] = p.x;
                    pz[n] = p.z;
                    n++;
                }
            }
            for (int k = 0; k < 4; k++) {
                double[] d = chordDir(px, pz, n, FRONT[k] * s, fx, fz);
                a[k] = (float) Math.toDegrees(Math.atan2(d[0] * lx + d[1] * lz, d[0] * fx + d[1] * fz));
                // 経路がほぼ後ろを指す(引き直した直後・その場で回る途中)ときは、首をそちらへ曲げない(折り返しで横へ振り切れる)
                if (Math.abs(a[k]) > BEHIND) a[k] = 0.0F;
            }
            // 後ろ: 通ってきた跡(今の位置 → 古い順)。向きは「後ろへ向かう向き」で、左へ振れたら +
            double[] bx = new double[this.trailCount + 1], bz = new double[this.trailCount + 1];
            bx[0] = x;
            bz[0] = z;
            int m = 1;
            for (int i = 0; i < this.trailCount; i++) {
                if (Math.hypot(this.trailX[i] - x, this.trailZ[i] - z) < 1e-3) continue;
                bx[m] = this.trailX[i];
                bz[m] = this.trailZ[i];
                m++;
            }
            for (int k = 0; k < 4; k++) {
                double[] d = chordDir(bx, bz, m, BACK[k] * s, -fx, -fz);
                a[4 + k] = (float) Math.toDegrees(Math.atan2(d[0] * lx + d[1] * lz, -(d[0] * fx + d[1] * fz)));
                if (Math.abs(a[4 + k]) > BEHIND) a[4 + k] = 0.0F;   // 跡が体の前へ回り込んでいる(その場で回った)
            }
            limit(a, 0);
            limit(a, 4);
            for (int k = 0; k < 8; k++) a[k] *= this.weight;
        }
        if (DEV_HOOKS && e.hasCustomName() && e.getCustomName().getString().startsWith("bendtest")) {
            // 確かめ用(テストクライアントだけ): 首も腹も左へ 15・30・45・60 度
            a = new float[]{15, 30, 45, 60, 15, 30, 45, 60};
        }
        // 安全のため: 1 tick に変わる角度の上限(送った前の値から)
        for (int k = 0; k < 8; k++) a[k] = Mth.clamp(a[k], this.now[k] - MAX_STEP, this.now[k] + MAX_STEP);
        long packed = pack(a);
        set(packed);
        serverNanos += System.nanoTime() - t0;
        serverCalls++;
        return packed;
    }

    /**
     * 折れ線(点 0 から)の弧長 {@code at} あたりの向き(弦 at−CHORD → at+CHORD)。折れ線が足りなければ最後の向き(無ければ {@code dx, dz})で
     * まっすぐ伸ばす。
     */
    static double[] chordDir(double[] xs, double[] zs, int n, double at, double dx, double dz) {
        double[] a = pointAt(xs, zs, n, Math.max(0.0D, at - CHORD), dx, dz);
        double[] b = pointAt(xs, zs, n, at + CHORD, dx, dz);
        double ux = b[0] - a[0], uz = b[1] - a[1];
        double len = Math.sqrt(ux * ux + uz * uz);
        return len < 1e-6 ? new double[]{dx, dz} : new double[]{ux / len, uz / len};
    }

    static double[] pointAt(double[] xs, double[] zs, int n, double at, double dx, double dz) {
        double left = at;
        double ex = dx, ez = dz;
        for (int i = 0; i + 1 < n; i++) {
            double sx = xs[i + 1] - xs[i], sz = zs[i + 1] - zs[i];
            double len = Math.sqrt(sx * sx + sz * sz);
            if (len < 1e-6) continue;
            ex = sx / len;
            ez = sz / len;
            if (left <= len) return new double[]{xs[i] + ex * left, zs[i] + ez * left};
            left -= len;
        }
        double ox = n > 0 ? xs[n - 1] : 0.0D, oz = n > 0 ? zs[n - 1] : 0.0D;
        if (n <= 1) {
            ex = dx;
            ez = dz;
        }
        return new double[]{ox + ex * left, oz + ez * left};
    }

    /** 体に対する向きの上限と、付け根から順に隣との差の上限。 */
    static void limit(float[] a, int from) {
        float before = 0.0F;
        for (int k = from; k < from + 4; k++) {
            float v = Mth.clamp(a[k], -MAX_ABS, MAX_ABS);
            v = Mth.clamp(v, before - MAX_JOINT, before + MAX_JOINT);
            a[k] = v;
            before = v;
        }
    }

    public static long pack(float[] a) {
        long out = 0L;
        for (int k = 0; k < 8; k++) {
            int b = Mth.clamp(Math.round(a[k]), -127, 127) & 0xFF;
            out |= (long) b << (k * 8);
        }
        return out;
    }

    static void unpack(long packed, float[] out) {
        for (int k = 0; k < 8; k++) {
            out[k] = (byte) ((packed >> (k * 8)) & 0xFF);
        }
    }

    private void set(long packed) {
        System.arraycopy(this.now, 0, this.prev, 0, 8);
        unpack(packed, this.now);
        this.lastPacked = packed;
    }

    // ------------------------------------------------------------------ クライアント

    /** 同期された値を 1 tick 進める(クライアント)。 */
    public void clientTick(long packed) {
        set(packed);
    }

    /** 描画用: 補間した角度(度、前 4・後 4)。 */
    public void angles(float partialTick, float[] out) {
        for (int k = 0; k < 8; k++) out[k] = Mth.lerp(partialTick, this.prev[k], this.now[k]);
    }

    public long packed() {
        return this.lastPacked;
    }

    /** 前の tick に曲がっていたか(0 へ戻る補間の間も描く)。 */
    public boolean wasBent() {
        for (float v : this.prev) if (v != 0.0F) return true;
        return false;
    }

    /** 曲がっているか(どれかが 0 でない)。 */
    public boolean bent() {
        return this.lastPacked != 0L;
    }

    // ------------------------------------------------------------------ 叩ける部位(両側で同じ値)

    /** 部位が付いている骨の付け根: 前の首(0〜3)は FRONT、−1 = bone(FRONT_BONE)、後ろは 4〜7 = BACK、MIN = 曲げない(胴)。 */
    static int stationOf(String part) {
        return switch (part) {
            case "head" -> 3;
            case "neck_b" -> 1;
            case "neck_a" -> 0;
            case "arm_l", "arm_r", "blade_l", "blade_r" -> -1;
            case "abdomen_a" -> 4;
            case "abdomen_b" -> 6;
            default -> Integer.MIN_VALUE;
        };
    }

    private int[] partStations;

    /**
     * 部位の中心(体格 1、[前, 左, 上] × 部位)を曲げる。部位は付いている骨と一緒に、その骨の付け根を中心に回る
     * (骨は付け根から先へ、隣の骨の向きを足し合わせた折れ線の上にある)。
     */
    public void bendParts(double[] in, double[] out, String[] names) {
        if (this.partStations == null || this.partStations.length != names.length) {
            this.partStations = new int[names.length];
            for (int i = 0; i < names.length; i++) this.partStations[i] = stationOf(names[i]);
        }
        System.arraycopy(in, 0, out, 0, Math.min(in.length, out.length));
        if (this.lastPacked == 0L) return;
        // 骨の付け根の曲がった位置(体格 1 の [前, 左])と向き
        double[][] front = chain(this.now, 0, FRONT, true);
        double[][] back = chain(this.now, 4, BACK, false);
        for (int i = 0; i < names.length; i++) {
            int st = this.partStations[i];
            if (st == Integer.MIN_VALUE) continue;
            double pf, pl, deg, s0;
            if (st == -1) {
                // bone: neck と bone2 の間(向きは neck と bone2 の間を補う)
                double t = (FRONT_BONE - FRONT[0]) / (FRONT[1] - FRONT[0]);
                double th = Math.toRadians(this.now[0]);
                pf = front[0][0] + Math.cos(th) * (FRONT_BONE - FRONT[0]);
                pl = front[0][1] + Math.sin(th) * (FRONT_BONE - FRONT[0]);
                deg = Mth.lerp(t, this.now[0], this.now[1]);
                s0 = FRONT_BONE;
            } else if (st < 4) {
                pf = front[st][0];
                pl = front[st][1];
                deg = this.now[st];
                s0 = FRONT[st];
            } else {
                pf = back[st - 4][0];
                pl = back[st - 4][1];
                deg = this.now[st];
                s0 = -BACK[st - 4];
            }
            double phi = Math.toRadians(st >= 4 ? -deg : deg);
            double rf = in[i * 3] - s0, rl = in[i * 3 + 1];
            double c = Math.cos(phi), sn = Math.sin(phi);
            out[i * 3] = pf + rf * c - rl * sn;
            out[i * 3 + 1] = pl + rf * sn + rl * c;
        }
    }

    /** 付け根の曲がった位置 [前, 左](体格 1)。最初の付け根は体の上にまっすぐ。 */
    static double[][] chain(float[] a, int from, double[] st, boolean forward) {
        double[][] p = new double[4][];
        double f = forward ? st[0] : -st[0], l = 0.0D;
        p[0] = new double[]{f, l};
        for (int k = 1; k < 4; k++) {
            double th = Math.toRadians(a[from + k - 1]);
            double len = st[k] - st[k - 1];
            if (forward) {
                f += Math.cos(th) * len;
                l += Math.sin(th) * len;
            } else {
                f -= Math.cos(th) * len;
                l += Math.sin(th) * len;
            }
            p[k] = new double[]{f, l};
        }
        return p;
    }
}
