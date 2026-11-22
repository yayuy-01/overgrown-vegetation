package com.yayuy.overgrownvegetation.entity.leap;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 1 回の跳び: 出る所 {@code from} から着く所 {@code to} へ、頂点 {@code peakY} を通る放物線を {@code ticks} tick で飛ぶ。
 *
 * <p>弧の式(s = 0〜1 は宙にいる間の割合、横は s に比例して進む): y(s) = from.y + (d + k)·s − k·s²、d = to.y − from.y。
 * 縦の加速度は −2k / ticks² で、これが {@link LeapSpec#gravity} に等しくなるよう ticks を決める(= 重力どおりの弧に見える)。
 *
 * @param yaw 跳ぶ向き(度、Minecraft の yRot)
 */
public record LeapPlan(Vec3 from, Vec3 to, double k, int ticks, float yaw) {

    /** 頂点の高さ(絶対の y)。 */
    public double peakY() {
        double d = this.to.y - this.from.y;
        double sStar = Mth.clamp((d + this.k) / (2.0D * this.k), 0.0D, 1.0D);
        return heightAt(sStar);
    }

    /** 水平距離(ブロック)。 */
    public double distance() {
        return Math.sqrt(sq(this.to.x - this.from.x) + sq(this.to.z - this.from.z));
    }

    /** 宙にいる割合 s(0〜1)での体の芯の位置。 */
    public Vec3 pos(double s) {
        return new Vec3(Mth.lerp(s, this.from.x, this.to.x), heightAt(s), Mth.lerp(s, this.from.z, this.to.z));
    }

    /** s での高さ。s の外は出る所・着く所の高さのまま(体の前後の点を弧に沿わせるときに使う)。 */
    public double heightAt(double s) {
        if (s <= 0.0D) return this.from.y;
        if (s >= 1.0D) return this.to.y;
        double d = this.to.y - this.from.y;
        return this.from.y + (d + this.k) * s - this.k * s * s;
    }

    /**
     * 出る所・着く所と頂点の上げ(高いほうの端からの高さ {@code lift})から弧を作る。
     * 頂点 P(出る所から)= max(0, d) + lift。(d + k)² = 4Pk の大きいほうの根を取る(頂点が 0〜1 の間に来る)。
     */
    public static LeapPlan of(Vec3 from, Vec3 to, double lift, double gravity) {
        double d = to.y - from.y;
        double p = Math.max(0.0D, d) + Math.max(lift, 0.05D);
        double b = 4.0D * p - 2.0D * d;
        double k = (b + Math.sqrt(Math.max(0.0D, b * b - 4.0D * d * d))) / 2.0D;
        int ticks = Math.max(6, (int) Math.round(Math.sqrt(2.0D * k / gravity)));
        float yaw = (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90.0F;
        return new LeapPlan(from, to, k, ticks, yaw);
    }

    private static double sq(double v) {
        return v * v;
    }
}
