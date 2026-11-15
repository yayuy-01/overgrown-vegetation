package com.yayuy.overgrownvegetation.entity.leap;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 手付けの歩き・待機のまま、体と脚を地面の起伏に合わせるための値(クライアント。2026-11-15、サルティムネスの段 2。種に依らない)。
 * 足の置き場を自分で決める {@code FootPlanter} と違い、足の運びはアニメのまま。ここは毎 tick 地面の高さを測り、
 * <b>体の前後・左右の傾きと上げ下げ</b>、<b>脚ごとの上げ下げ</b>(傾けた体の下で足が地面からずれる量)を出す。骨へ足すのは描画のモデル。
 *
 * <h2>測る所</h2>
 * 鼻先・腹の先(体の前後の傾き)と、足の置き場(左右の傾きと脚ごとのずれ)の真下の地面。高さは体の足元の 1.6 ブロック上から
 * 2.5 ブロック下までの、一番上の当たりの上面(無ければ下限)。値は毎 tick なめらかに寄せる。
 *
 * <h2>向き(モデルの中の値。+x = 体の右、+z = 体の後ろ)</h2>
 * {@link #pitch} は + で前が上がる、{@link #roll} は + で左が上がる、{@link #lift} は + で体が上がる(ブロック)、
 * {@link #footError} は + でその足の下の地面が体の傾きの面より高い(足を上げる)。
 */
public final class GroundFit {

    /** 傾き・上げ下げの上限(ラジアン・ブロック)とならしの割合(1 tick あたり)【仮】。 */
    static final double MAX_PITCH = Math.toRadians(35.0D), MAX_ROLL = Math.toRadians(25.0D), MAX_LIFT = 1.0D, MAX_FOOT = 1.2D, EASE = 0.35D;
    static final double PROBE_UP = 1.6D, PROBE_DOWN = 2.5D;

    private double front = 1.0D, back = 1.0D;
    private double[][] feet = new double[0][];
    private double pitch, roll, lift, pitchO, rollO, liftO;
    private double[] foot = new double[0], footO = new double[0];
    private boolean ready;

    /**
     * @param front 体の中心から鼻先まで(ブロック、体格 1)
     * @param back  体の中心から腹の先まで(ブロック、体格 1)
     * @param feet  足の置き場 {x(+ = 右), z(+ = 後ろ)}(ブロック、体格 1)。左右の傾きは x の符号で左右に分けて見る
     */
    public void configure(double front, double back, double[][] feet) {
        this.front = front;
        this.back = back;
        this.feet = feet;
        this.foot = new double[feet.length];
        this.footO = new double[feet.length];
        this.ready = true;
    }

    public boolean ready() {
        return this.ready;
    }

    /** 毎 tick(クライアント)。{@code active} が偽(宙・死)の間は 0 へ戻す。 */
    public void tick(Mob e, boolean active) {
        if (!this.ready) return;
        this.pitchO = this.pitch;
        this.rollO = this.roll;
        this.liftO = this.lift;
        System.arraycopy(this.foot, 0, this.footO, 0, this.foot.length);
        double tp = 0.0D, tr = 0.0D, tl = 0.0D;
        double[] tf = new double[this.feet.length];
        if (active) {
            double sc = e.getScale();
            float r = e.getYRot() * Mth.DEG_TO_RAD;
            double fx = -Mth.sin(r), fz = Mth.cos(r);   // 前
            double rx = -Mth.cos(r), rz = -Mth.sin(r);  // 右
            double y = e.getY();
            double gF = ground(e, e.getX() + fx * this.front * sc, y, e.getZ() + fz * this.front * sc);
            double gB = ground(e, e.getX() - fx * this.back * sc, y, e.getZ() - fz * this.back * sc);
            double[] g = new double[this.feet.length];
            double left = 0.0D, right = 0.0D, lx = 0.0D, rxSum = 0.0D;
            int nl = 0, nr = 0;
            for (int i = 0; i < this.feet.length; i++) {
                double x = this.feet[i][0] * sc, z = this.feet[i][1] * sc;
                g[i] = ground(e, e.getX() + rx * x - fx * z, y, e.getZ() + rz * x - fz * z);
                if (x < 0.0D) {
                    left += g[i];
                    lx += x;
                    nl++;
                } else {
                    right += g[i];
                    rxSum += x;
                    nr++;
                }
            }
            tp = Mth.clamp(Math.atan2(gF - gB, (this.front + this.back) * sc), -MAX_PITCH, MAX_PITCH);
            double sides = 0.0D;
            if (nl > 0 && nr > 0) {
                left /= nl;
                right /= nr;
                double width = rxSum / nr - lx / nl;
                tr = Mth.clamp(Math.atan2(left - right, Math.max(width, 0.1D)), -MAX_ROLL, MAX_ROLL);
                sides = (left + right) * 0.5D;
            } else {
                sides = (gF + gB) * 0.5D;
            }
            double alongMid = (gF * this.back + gB * this.front) / (this.front + this.back);   // 前後の線の、体の中心での高さ
            tl = Mth.clamp((alongMid + sides) * 0.5D - y, -MAX_LIFT, MAX_LIFT);
            double tanP = Math.tan(tp), tanR = Math.tan(tr);
            for (int i = 0; i < this.feet.length; i++) {
                double x = this.feet[i][0] * sc, z = this.feet[i][1] * sc;
                double plane = y + tl - z * tanP - x * tanR;
                tf[i] = Mth.clamp(g[i] - plane, -MAX_FOOT, MAX_FOOT);
            }
        }
        this.pitch += (tp - this.pitch) * EASE;
        this.roll += (tr - this.roll) * EASE;
        this.lift += (tl - this.lift) * EASE;
        for (int i = 0; i < tf.length; i++) this.foot[i] += (tf[i] - this.foot[i]) * EASE;
    }

    public double pitch(float pt) {
        return Mth.lerp(pt, this.pitchO, this.pitch);
    }

    public double roll(float pt) {
        return Mth.lerp(pt, this.rollO, this.roll);
    }

    public double lift(float pt) {
        return Mth.lerp(pt, this.liftO, this.lift);
    }

    public double footError(int i, float pt) {
        return i < this.foot.length ? Mth.lerp(pt, this.footO[i], this.foot[i]) : 0.0D;
    }

    /** 足元の真下の地面の高さ({@link #ground} と同じ探し方)。 */
    public static double groundBelow(Mob e) {
        return ground(e, e.getX(), e.getY(), e.getZ());
    }

    /** 列 (x, z) の地面の高さ: 足元 {@code y} の {@link #PROBE_UP} 上から {@link #PROBE_DOWN} 下までで一番上の当たりの上面(無ければ下限)。 */
    static double ground(Mob e, double x, double y, double z) {
        Level level = e.level();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bx = Mth.floor(x), bz = Mth.floor(z);
        double top = y + PROBE_UP, bottom = y - PROBE_DOWN;
        for (int by = Mth.floor(top); by >= Mth.floor(bottom); by--) {
            p.set(bx, by, bz);
            VoxelShape s = level.getBlockState(p).getCollisionShape(level, p, CollisionContext.empty());
            if (s.isEmpty()) continue;
            double h = by + s.max(net.minecraft.core.Direction.Axis.Y);
            if (h <= top + 1e-6) return Math.max(h, bottom);
        }
        return bottom;
    }
}
