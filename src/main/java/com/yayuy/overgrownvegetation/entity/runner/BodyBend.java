package com.yayuy.overgrownvegetation.entity.runner;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * 長い体の左右の曲げ(2026-11-14、ファルキムネスの {@code FalcimnesBend} を種に依らない形へ切り出した。ファルキムネスはまだ自分の写しを使う)。
 *
 * <p>地形とぶつかるのは胴の芯だけ。歩いている間だけ、前の 4 つの付け根(首と頭)を<b>これから通る経路</b>に、
 * 後ろの 4 つの付け根(尾・腹)を<b>芯が通ってきた跡</b>に沿わせる。付け根ごとに「体に対する向き」(度、左が +)をサーバーが求め、
 * 1 バイトずつ 1 つの long で送る。クライアントはそれで骨を横へ曲げる({@code client/runner/BodyBendRig})。
 * 技の最中・止まっている間・その場で回っている間は 0 へ戻す(呼ぶ側が {@code free} で知らせる)。数値は全部ファルキムネスと同じ【仮】。
 */
public final class BodyBend {

    /** 体に対する向きの上限(度)と、隣の付け根との差の上限(度)【仮】。 */
    static final float MAX_ABS = 70.0F, MAX_JOINT = 35.0F;
    /** 1 tick に変わる角度の上限(度)・これより後ろを指す経路 / 跡は曲げない(度)・瞬間移動とみなす 1 tick の動き(ブロック)【仮】。 */
    static final float MAX_STEP = 10.0F, BEHIND = 100.0F;
    static final double TELEPORT = 3.0D;
    /** 曲げの効きが歩き出し・止まりで変わる速さ(1 tick あたり)【仮】。 */
    static final float WEIGHT_RATE = 0.08F;
    /** 前へこれ以上進んだ tick を「歩いている」とする(ブロック / tick)【仮】。 */
    static final double WALK_MIN = 0.02D;
    /** 向きを見る弦の半分の長さ(ブロック)【仮】。 */
    static final double CHORD = 0.7D;
    /** 通った跡を残す間隔(ブロック)と数。 */
    static final double TRAIL_STEP = 0.25D;
    static final int TRAIL_MAX = 48;

    /** 前の付け根(体の前へ、体格 1 のブロック、4 つ)と後ろの付け根(体の後ろへ、4 つ)。 */
    private final double[] front, back;

    private final double[] trailX = new double[TRAIL_MAX], trailZ = new double[TRAIL_MAX];
    private int trailCount;
    private float weight;
    private int stillTicks;
    private double lastX = Double.NaN, lastZ;

    private final float[] now = new float[8];
    private final float[] prev = new float[8];
    private long lastPacked;

    private static final boolean DEV_HOOKS = Boolean.getBoolean("ov.devbridge");

    /** 計測用(サーバー、全個体)。 */
    public static volatile long serverNanos, serverCalls;

    public BodyBend(double[] front, double[] back) {
        if (front.length != 4 || back.length != 4) throw new IllegalArgumentException("付け根は前 4・後ろ 4");
        this.front = front;
        this.back = back;
    }

    /** 1 tick 進め、送る値を返す(サーバー、aiStep の移動の後)。{@code free} = 技の最中・死・乗り物・水の中でない。 */
    public long serverTick(Mob e, boolean free) {
        long t0 = System.nanoTime();
        double x = e.getX(), z = e.getZ();
        float yaw = e.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = Mth.cos(yaw), lz = Mth.sin(yaw);
        double mx = Double.isNaN(this.lastX) ? 0.0D : x - this.lastX, mz = Double.isNaN(this.lastX) ? 0.0D : z - this.lastZ;
        double v = mx * fx + mz * fz;
        boolean jumped = mx * mx + mz * mz > TELEPORT * TELEPORT;
        this.lastX = x;
        this.lastZ = z;
        boolean walking = free && v > WALK_MIN;
        this.stillTicks = walking ? 0 : this.stillTicks + 1;
        if (!free || this.stillTicks > 20 || jumped) {
            if (jumped) this.weight = 0.0F;
            this.trailCount = 0;
        }
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
                double[] d = chordDir(px, pz, n, this.front[k] * s, fx, fz);
                a[k] = (float) Math.toDegrees(Math.atan2(d[0] * lx + d[1] * lz, d[0] * fx + d[1] * fz));
                if (Math.abs(a[k]) > BEHIND) a[k] = 0.0F;
            }
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
                double[] d = chordDir(bx, bz, m, this.back[k] * s, -fx, -fz);
                a[4 + k] = (float) Math.toDegrees(Math.atan2(d[0] * lx + d[1] * lz, -(d[0] * fx + d[1] * fz)));
                if (Math.abs(a[4 + k]) > BEHIND) a[4 + k] = 0.0F;
            }
            limit(a, 0);
            limit(a, 4);
            for (int k = 0; k < 8; k++) a[k] *= this.weight;
        }
        if (DEV_HOOKS && e.hasCustomName() && e.getCustomName().getString().startsWith("bendtest")) {
            a = new float[]{15, 30, 45, 60, 15, 30, 45, 60};   // 確かめ用(テストクライアントだけ)
        }
        for (int k = 0; k < 8; k++) a[k] = Mth.clamp(a[k], this.now[k] - MAX_STEP, this.now[k] + MAX_STEP);
        long packed = pack(a);
        set(packed);
        serverNanos += System.nanoTime() - t0;
        serverCalls++;
        return packed;
    }

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
        for (int k = 0; k < 8; k++) out[k] = (byte) ((packed >> (k * 8)) & 0xFF);
    }

    private void set(long packed) {
        System.arraycopy(this.now, 0, this.prev, 0, 8);
        unpack(packed, this.now);
        this.lastPacked = packed;
    }

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

    public boolean wasBent() {
        for (float v : this.prev) if (v != 0.0F) return true;
        return false;
    }

    public boolean bent() {
        return this.lastPacked != 0L;
    }
}
