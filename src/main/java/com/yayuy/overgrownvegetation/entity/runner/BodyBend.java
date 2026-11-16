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

    /** 作業場所(個体ごと。前は毎 tick 配列を 5 つ new していた。11-16 見直し ①3)。 */
    private final double[] px = new double[10], pz = new double[10], bx = new double[TRAIL_MAX + 1], bz = new double[TRAIL_MAX + 1];
    private final double[] pa = new double[2], pb = new double[2], dir = new double[2];
    private final float[] angles = new float[8];
    /** 近くにプレイヤーがいるか(20 tick ごとに見直す)。いなければ曲げを計算しない(見た目だけの値)。 */
    private boolean watched = true;
    private int watchCheck;

    /** 1 tick 進め、送る値を返す(サーバー、aiStep の移動の後)。{@code free} = 技の最中・死・乗り物・水の中でない。 */
    public long serverTick(Mob e, boolean free) {
        long t0 = DEV_HOOKS ? System.nanoTime() : 0L;
        double x = e.getX(), z = e.getZ();
        if (--this.watchCheck <= 0) {
            this.watchCheck = 20;
            this.watched = com.yayuy.overgrownvegetation.entity.Watched.near(e, com.yayuy.overgrownvegetation.entity.Watched.RANGE);
        }
        if (!this.watched) {
            // 誰も見ていない: 跡も曲げも持たない(見え始めたらまっすぐから)
            this.lastX = x;
            this.lastZ = z;
            this.trailCount = 0;
            this.weight = 0.0F;
            if (this.lastPacked != 0L) set(0L);
            return 0L;
        }
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
        if (this.trailCount == 0 || sq(x - this.trailX[0]) + sq(z - this.trailZ[0]) >= TRAIL_STEP * TRAIL_STEP) {
            System.arraycopy(this.trailX, 0, this.trailX, 1, TRAIL_MAX - 1);
            System.arraycopy(this.trailZ, 0, this.trailZ, 1, TRAIL_MAX - 1);
            this.trailX[0] = x;
            this.trailZ[0] = z;
            this.trailCount = Math.min(TRAIL_MAX, this.trailCount + 1);
        }
        this.weight = Mth.clamp(this.weight + (walking ? WEIGHT_RATE : -WEIGHT_RATE), 0.0F, 1.0F);
        float[] a = this.angles;
        java.util.Arrays.fill(a, 0.0F);
        if (this.weight > 0.0F) {
            double s = e.getScale();
            Path path = e.getNavigation().getPath();
            int n = 1;
            this.px[0] = x;
            this.pz[0] = z;
            if (path != null && !path.isDone()) {
                for (int i = path.getNextNodeIndex(); i < path.getNodeCount() && n < this.px.length; i++) {
                    net.minecraft.world.level.pathfinder.Node node = path.getNode(i);
                    // getEntityPosAtNode と同じ(ノードの真ん中 + 体の幅の半分の切り上げ)を Vec3 を作らずに
                    double w = (int) (e.getBbWidth() + 1.0F) * 0.5D;
                    this.px[n] = node.x + w;
                    this.pz[n] = node.z + w;
                    n++;
                }
            }
            for (int k = 0; k < 4; k++) {
                chordDir(this.px, this.pz, n, this.front[k] * s, fx, fz, this.pa, this.pb, this.dir);
                a[k] = (float) Math.toDegrees(Math.atan2(this.dir[0] * lx + this.dir[1] * lz, this.dir[0] * fx + this.dir[1] * fz));
                if (Math.abs(a[k]) > BEHIND) a[k] = 0.0F;
            }
            this.bx[0] = x;
            this.bz[0] = z;
            int m = 1;
            for (int i = 0; i < this.trailCount; i++) {
                if (sq(this.trailX[i] - x) + sq(this.trailZ[i] - z) < 1e-6) continue;
                this.bx[m] = this.trailX[i];
                this.bz[m] = this.trailZ[i];
                m++;
            }
            for (int k = 0; k < 4; k++) {
                chordDir(this.bx, this.bz, m, this.back[k] * s, -fx, -fz, this.pa, this.pb, this.dir);
                a[4 + k] = (float) Math.toDegrees(Math.atan2(this.dir[0] * lx + this.dir[1] * lz, -(this.dir[0] * fx + this.dir[1] * fz)));
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
        if (DEV_HOOKS) {
            serverNanos += System.nanoTime() - t0;
            serverCalls++;
        }
        return packed;
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * 折れ線(点 0 から)の弧長 {@code at} あたりの向き(弦 at−CHORD → at+CHORD)を {@code out} に書く。{@code a}・{@code b} は作業場所。
     * 折れ線が足りなければ最後の向き(無ければ {@code dx, dz})でまっすぐ伸ばす。ファルキムネスの写しも使う。
     */
    public static void chordDir(double[] xs, double[] zs, int n, double at, double dx, double dz, double[] a, double[] b, double[] out) {
        pointAt(xs, zs, n, Math.max(0.0D, at - CHORD), dx, dz, a);
        pointAt(xs, zs, n, at + CHORD, dx, dz, b);
        double ux = b[0] - a[0], uz = b[1] - a[1];
        double len2 = ux * ux + uz * uz;
        if (len2 < 1e-12) {
            out[0] = dx;
            out[1] = dz;
        } else {
            double len = Math.sqrt(len2);
            out[0] = ux / len;
            out[1] = uz / len;
        }
    }

    static void pointAt(double[] xs, double[] zs, int n, double at, double dx, double dz, double[] out) {
        double left = at;
        double ex = dx, ez = dz;
        for (int i = 0; i + 1 < n; i++) {
            double sx = xs[i + 1] - xs[i], sz = zs[i + 1] - zs[i];
            double len2 = sx * sx + sz * sz;
            if (len2 < 1e-12) continue;
            double len = Math.sqrt(len2);
            ex = sx / len;
            ez = sz / len;
            if (left <= len) {
                out[0] = xs[i] + ex * left;
                out[1] = zs[i] + ez * left;
                return;
            }
            left -= len;
        }
        double ox = n > 0 ? xs[n - 1] : 0.0D, oz = n > 0 ? zs[n - 1] : 0.0D;
        if (n <= 1) {
            ex = dx;
            ez = dz;
        }
        out[0] = ox + ex * left;
        out[1] = oz + ez * left;
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
