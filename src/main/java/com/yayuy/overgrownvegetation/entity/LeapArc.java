package com.yayuy.overgrownvegetation.entity;

/**
 * 「この勢いで跳んだらどこまで届くか」を、<b>バニラの空中の物理をそのまま 1 tick ずつ回して</b>求める。
 *
 * <p>{@link InvaderLeapGoal}(標的への飛びつき)と {@link InvaderWaterLeapGoal}(川などの跳び越え)で
 * 同じ式を使うため切り出したもの。定数を変えたときに「索敵範囲だけ広がって実際には届かない」
 * といったズレが起きないようにする狙い(2026-09-14)。
 */
public final class LeapArc {

    /** バニラの空中の物理(生き物の重力・縦の減速・横の減速)。 */
    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.98D;
    private static final double HORIZONTAL_DRAG = 0.91D;

    private LeapArc() {
    }

    /**
     * {@code horizontalSpeed} / {@code lift} で跳んだときの <b>{水平距離, 最高到達点}</b>(ブロック)。
     * 地形との衝突は見ていないので「何も無ければここまで飛ぶ」という上限値。
     *
     * @param maxTicks 空中にいるとみなす最大 tick 数
     */
    public static double[] reach(double horizontalSpeed, double lift, int maxTicks) {
        double vx = horizontalSpeed;
        double vy = lift;
        double x = 0.0D;
        double y = 0.0D;
        double peak = 0.0D;
        for (int t = 0; t < maxTicks; t++) {
            x += vx;
            y += vy;
            peak = Math.max(peak, y);
            vx *= HORIZONTAL_DRAG;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            if (y <= 0.0D && t > 0) {
                break;
            }
        }
        return new double[] { x, peak };
    }
    /**
     * その勢いで跳んだとき、<b>水平 {@code distance} 先・高さ {@code rise}(下向きなら負)の地点へ
     * 届くか</b>。放物線を 1 tick ずつ回し、水平距離に達した時点でまだその高さ以上にいれば届く。
     *
     * <p>{水平距離, 最高到達点} の上限だけで判断すると「遠くて高い」地点を取り違えるので、
     * 着地点の可否はこちらで見る。
     */
    public static boolean canReach(double horizontalSpeed, double lift, int maxTicks,
                                   double distance, double rise) {
        double vx = horizontalSpeed;
        double vy = lift;
        double x = 0.0D;
        double y = 0.0D;
        for (int t = 0; t < maxTicks; t++) {
            x += vx;
            y += vy;
            if (x >= distance) {
                return y >= rise;
            }
            vx *= HORIZONTAL_DRAG;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            if (y < rise - 1.0D && vy < 0.0D) {
                // もう着地点の高さより下まで落ちている。
                return false;
            }
        }
        return false;
    }
}
