package com.yayuy.overgrownvegetation.siege;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * 砲台の<b>弾道計算</b>。サーバー(砲弾の飛行・自動照準)とクライアント(砲弾の描画・GUI の照準補助)が
 * 同じ式を使う。
 *
 * <h2>式</h2>
 * <p>空気抵抗なしの放物線: {@code p(t) = origin + velocity·t − (0, g·t²/2, 0)}。決定論的なので、
 * 発射位置・初速・発射時刻だけを配ればクライアントは座標同期なしに毎フレームの位置を出せる
 * ({@link com.yayuy.overgrownvegetation.network.ShellFiredPayload})。
 *
 * <h2>角度の規約</h2>
 * <ul>
 *   <li><b>方位(azimuth)</b> = 方角(0 = 北 = −Z、90 = 東 = +X、180 = 南、270 = 西)。コンパスと同じで
 *       プレイヤーに分かりやすい。Minecraft のヨー(0 = 南)とは違うので注意。</li>
 *   <li><b>仰角(elevation)</b> = 水平から上向きに正(度)。</li>
 * </ul>
 *
 * <h2>偏差射撃</h2>
 * <p>ボスは {@link SiegeBossMotion} の式で動くので、未来位置が正確に分かる。{@link #lead} は
 * 「飛翔時間 T を仮定 → T 秒後のボスの胴の中心を狙う解を求める → その解の飛翔時間で T を更新」を
 * 数回繰り返して収束させる(ボスは 0.08 ブロック/tick と遅いので 2〜3 回で十分)。
 */
public final class CannonBallistics {

    /** 自動照準が狙う体軸上の点(モデル単位、頭が負)。胴の中心の節(body6)の中心。 */
    public static final double AIM_Z_UNITS = -8.0D;
    /** 偏差射撃の反復回数。 */
    private static final int LEAD_ITERATIONS = 8;

    /**
     * 射撃解。
     *
     * @param elevationDeg 仰角(度)
     * @param azimuthDeg   方位(度、0 = 北)
     * @param flightTicks  飛翔時間(tick)
     * @param target       狙った点(ワールド座標)
     */
    public record Solution(double elevationDeg, double azimuthDeg, double flightTicks, Vec3 target) {
    }

    /** 仰角・方位から単位方向ベクトル。 */
    public static Vec3 direction(double elevationDeg, double azimuthDeg) {
        double el = Math.toRadians(elevationDeg);
        double az = Math.toRadians(azimuthDeg);
        double c = Math.cos(el);
        return new Vec3(Math.sin(az) * c, Math.sin(el), -Math.cos(az) * c);
    }

    /** 水平ベクトル {@code (dx, dz)} の方位(度、0 = 北、[0, 360))。 */
    public static double azimuthOf(double dx, double dz) {
        return normalizeAzimuth(Math.toDegrees(Math.atan2(dx, -dz)));
    }

    /** 方向ベクトルの仰角(度、上向きが正)。 */
    public static double elevationOf(Vec3 d) {
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        return Math.toDegrees(Math.atan2(d.y, horizontal));
    }

    /** 方位を [0, 360) に正規化。 */
    public static double normalizeAzimuth(double deg) {
        double v = deg % 360.0D;
        return v < 0.0D ? v + 360.0D : v;
    }

    /** 2 つの方位の差(−180〜180、{@code to − from})。 */
    public static double azimuthDelta(double from, double to) {
        return Mth.wrapDegrees(to - from);
    }

    /** 発射から {@code t} tick 後の砲弾の位置。 */
    public static Vec3 positionAt(Vec3 origin, Vec3 velocity, double gravity, double t) {
        return new Vec3(origin.x + velocity.x * t,
                origin.y + velocity.y * t - 0.5D * gravity * t * t,
                origin.z + velocity.z * t);
    }

    /** 発射から {@code t} tick 後の砲弾の速度。 */
    public static Vec3 velocityAt(Vec3 velocity, double gravity, double t) {
        return new Vec3(velocity.x, velocity.y - gravity * t, velocity.z);
    }

    /** 装薬 {@code charge} 個のときの初速(ブロック/tick)= 最大初速 × sqrt(装薬 / 最大装薬)。 */
    public static double speedForCharge(int charge) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int max = Math.max(1, cfg.cannonMaxCharge.get());
        int c = Mth.clamp(charge, 1, max);
        return cfg.cannonMuzzleVelocity.get() * Math.sqrt((double) c / max);
    }

    public static double gravity() {
        return OvergrownVegetationConfig.COMMON.cannonGravity.get();
    }

    /**
     * 静止した点 {@code target} を初速 {@code speed}・重力 {@code gravity} で撃つ解。
     * 射程外なら空。{@code highArc} = true で高い弾道(2 解のうち仰角が大きい方)。
     */
    public static Optional<Solution> solve(Vec3 from, Vec3 target, double speed, double gravity, boolean highArc) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        double dy = target.y - from.y;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double azimuth = azimuthOf(dx, dz);
        if (speed <= 1.0E-6D) {
            return Optional.empty();
        }
        if (gravity <= 1.0E-9D) {
            // 重力なし: 直線。
            double distance = Math.sqrt(horizontal * horizontal + dy * dy);
            if (distance < 1.0E-6D) {
                return Optional.empty();
            }
            double elevation = Math.toDegrees(Math.atan2(dy, horizontal));
            return Optional.of(new Solution(elevation, azimuth, distance / speed, target));
        }
        if (horizontal < 1.0E-6D) {
            // 真上・真下。上向きなら v² ≥ 2 g dy が必要。
            if (dy > 0.0D && speed * speed < 2.0D * gravity * dy) {
                return Optional.empty();
            }
            double elevation = dy >= 0.0D ? 90.0D : -90.0D;
            double t = dy >= 0.0D
                    ? (speed - Math.sqrt(speed * speed - 2.0D * gravity * dy)) / gravity
                    : (speed + Math.sqrt(speed * speed - 2.0D * gravity * dy)) / gravity;
            return Optional.of(new Solution(elevation, azimuth, t, target));
        }
        double v2 = speed * speed;
        double disc = v2 * v2 - gravity * (gravity * horizontal * horizontal + 2.0D * dy * v2);
        if (disc < 0.0D) {
            return Optional.empty();
        }
        double root = Math.sqrt(disc);
        double tanTheta = highArc ? (v2 + root) / (gravity * horizontal) : (v2 - root) / (gravity * horizontal);
        double theta = Math.atan(tanTheta);
        double flight = horizontal / (speed * Math.cos(theta));
        return Optional.of(new Solution(Math.toDegrees(theta), azimuth, flight, target));
    }

    /**
     * 攻城戦ボスへの<b>偏差射撃</b>の解。{@code elapsed} は現在の攻城戦の経過 tick。
     * 狙う点は飛翔時間ぶん未来の胴の中心({@link #AIM_Z_UNITS})。射程外なら空。
     */
    public static Optional<Solution> lead(Vec3 from, SiegeBossMotion motion, double elapsed, double scale,
                                          double speed, double gravity, boolean highArc) {
        double flight = 0.0D;
        Optional<Solution> solution = Optional.empty();
        for (int i = 0; i < LEAD_ITERATIONS; i++) {
            double t = elapsed + flight;
            Vec3 center = motion.positionAt(t);
            float yaw = motion.yawAt(t);
            float pitch = motion.pitchAt(t);
            Vec3 aim = TitanicaKinematics.bodyPoint(center, yaw, pitch, scale, AIM_Z_UNITS);
            solution = solve(from, aim, speed, gravity, highArc);
            if (solution.isEmpty()) {
                return Optional.empty();
            }
            double next = solution.get().flightTicks();
            if (Math.abs(next - flight) < 0.05D) {
                break;
            }
            flight = next;
        }
        return solution;
    }

    private CannonBallistics() {
    }
}
