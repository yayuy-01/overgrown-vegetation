package com.yayuy.overgrownvegetation.siege;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 砲台のモデル {@code assets/overgrown_vegetation/geo/siege_cannon.geo.json} の<b>寸法</b>。
 * 砲口・座席のワールド座標はサーバー(発射・騎乗)とクライアント(描画・照準器)の両方が要るので、
 * 描画専用にせずここに置いて双方が同じ式を使う。
 *
 * <h2>モデルの座標系</h2>
 * <p>Bedrock(Blockbench)形式の geo は Y が上、<b>砲口は −X 方向</b>(砲身は x = −100 が砲口、
 * x = +64 が尾栓)。GeckoLib は読み込み時に X を反転して描くので、モデル空間では砲口が +X になる。
 * ここでは分かりやすさのため、ボーン {@code dodai} の旋回中心({@link #TURN_AXIS_X})から見た
 * <b>前(砲口の向き)・上・右</b>の 3 つに直して持つ({@link #offset})。
 *
 * <h2>ボーン</h2>
 * <ul>
 *   <li>{@code dodai} — 台座ごと水平に旋回する。回転中心は geo の pivot ではなく
 *       {@link #TURN_AXIS_X}(底板の中心)へ差し替える({@code SiegeCannonGeoModel})。</li>
 *   <li>{@code housin} — 砲身。{@link #TRUNNION_X}, {@link #TRUNNION_Y} の砲耳で俯仰する
 *       (可動域は config の {@code cannonMinElevation}〜{@code cannonMaxElevation} = 既定 0〜40 度)。</li>
 *   <li>{@code sousaban} — 操作盤。ここに立って照準する({@link #SEAT_FORWARD} 他)。</li>
 * </ul>
 */
public final class SiegeCannonGeometry {

    // --- geo のモデル単位(16 = 1 ブロック)。Blockbench で測った値そのまま ---

    /** 旋回軸(底板 x = −33〜64 の中心)。geo の dodai の pivot はここではないので実行時に差し替える。 */
    public static final double TURN_AXIS_X = 15.5D;
    public static final double TURN_AXIS_Z = 0.0D;
    /** 砲耳(housin の pivot)。 */
    public static final double TRUNNION_X = 41.5D;
    public static final double TRUNNION_Y = 45.0D;
    /** 砲口の先端(砲身の最も −X 側)。 */
    public static final double MUZZLE_TIP_X = -100.0D;
    /** 操作盤(sousaban)の足元。甲板 y = 31 の上、砲身の左側。 */
    public static final double SEAT_X = -26.0D;
    public static final double SEAT_Y = 31.0D;
    public static final double SEAT_Z = -19.5D;

    // --- 旋回軸から見た「前(砲口の向き)・上・右」(ブロック、拡大率は掛ける前) ---

    public static final double TRUNNION_FORWARD = (TURN_AXIS_X - TRUNNION_X) / 16.0D;
    public static final double TRUNNION_UP = TRUNNION_Y / 16.0D;
    public static final double TRUNNION_RIGHT = 0.0D;
    /** 砲耳から砲口までの長さ。 */
    public static final double BARREL_LENGTH = (TRUNNION_X - MUZZLE_TIP_X) / 16.0D;
    public static final double SEAT_FORWARD = (TURN_AXIS_X - SEAT_X) / 16.0D;
    public static final double SEAT_UP = SEAT_Y / 16.0D;
    public static final double SEAT_RIGHT = (SEAT_Z - TURN_AXIS_Z) / 16.0D;

    /** モデルの拡大率(config)。ここを変えると描画・砲口・座席がまとめて追従する。 */
    public static double scale() {
        return OvergrownVegetationConfig.COMMON.cannonModelScale.get();
    }

    /**
     * 旋回軸から見た点のワールドオフセット。
     *
     * @param forward    砲口の向きへの距離(ブロック)
     * @param up         上への距離(ブロック)
     * @param right      砲口を向いて右への距離(ブロック)
     * @param azimuthDeg 方位(度、0 = 北)
     */
    public static Vec3 offset(double forward, double up, double right, double azimuthDeg) {
        double a = Math.toRadians(azimuthDeg);
        double sin = Math.sin(a);
        double cos = Math.cos(a);
        double s = scale();
        return new Vec3((forward * sin + right * cos) * s, up * s, (-forward * cos + right * sin) * s);
    }

    /** 旋回軸のワールド座標(ブロックの底面中央)。 */
    public static Vec3 center(BlockPos pos) {
        return Vec3.atBottomCenterOf(pos);
    }

    /** 砲耳(俯仰の回転中心)のワールド座標。 */
    public static Vec3 trunnion(BlockPos pos, double azimuthDeg) {
        return center(pos).add(offset(TRUNNION_FORWARD, TRUNNION_UP, TRUNNION_RIGHT, azimuthDeg));
    }

    /** 砲口のワールド座標(砲弾が出る点)。 */
    public static Vec3 muzzle(BlockPos pos, double elevationDeg, double azimuthDeg) {
        return trunnion(pos, azimuthDeg)
                .add(CannonBallistics.direction(elevationDeg, azimuthDeg).scale(BARREL_LENGTH * scale()));
    }

    /** 操作盤(座席)のワールド座標 = 騎乗したプレイヤーの足元。 */
    public static Vec3 seat(BlockPos pos, double azimuthDeg) {
        return center(pos).add(offset(SEAT_FORWARD, SEAT_UP, SEAT_RIGHT, azimuthDeg));
    }

    // --- 角度 ---

    public static double minElevation() {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        return Math.min(cfg.cannonMinElevation.get(), cfg.cannonMaxElevation.get());
    }

    public static double maxElevation() {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        return Math.max(cfg.cannonMinElevation.get(), cfg.cannonMaxElevation.get());
    }

    public static double clampElevation(double degrees) {
        return Mth.clamp(degrees, minElevation(), maxElevation());
    }

    /** プレイヤーのヨー(0 = 南)→ 方位(0 = 北)。 */
    public static double azimuthFromYaw(float yaw) {
        return CannonBallistics.normalizeAzimuth(yaw + 180.0F);
    }

    /** プレイヤーのピッチ(下が正)→ 仰角(上が正、可動域でクランプ)。 */
    public static double elevationFromPitch(float pitch) {
        return clampElevation(-pitch);
    }

    /** 方位 → プレイヤーのヨー(騎乗した瞬間に砲の向きへ視線を合わせる)。 */
    public static float yawFromAzimuth(double azimuthDeg) {
        return Mth.wrapDegrees((float) (azimuthDeg - 180.0D));
    }

    /** 仰角 → プレイヤーのピッチ。 */
    public static float pitchFromElevation(double elevationDeg) {
        return (float) -elevationDeg;
    }

    // --- 当たり判定（モデルの主な塊。砲身は 360 度ぶん掃引すると半径 9 ブロックになるので含めない） ---

    /**
     * 台座・車体・砲架・甲板の 4 つの直方体。geo の寸法をそのまま「前・上・右」へ直したもの。
     * <b>砲身は入れていない</b> — 旋回で半径 9 ブロックを掃くので、ブロックで表すと数百マスになる。
     */
    public static final java.util.List<SiegeStructureShape.Box> COLLISION_BOXES = java.util.List.of(
            // 底板 geo x -33..64, y 0..5, z -33..33
            new SiegeStructureShape.Box(-3.031D, 3.031D, 0.0D, 0.3125D, -2.0625D, 2.0625D),
            // 車体 geo x -33..24, y 5..29, z -12..12
            new SiegeStructureShape.Box(-0.531D, 3.031D, 0.3125D, 1.8125D, -0.75D, 0.75D),
            // 砲架 geo x 24..64, y 5..55, z -36..-12
            new SiegeStructureShape.Box(-3.031D, -0.531D, 0.3125D, 3.4375D, -2.25D, -0.75D),
            // 甲板 geo x -33..24, y 28..31, z -26..-12
            new SiegeStructureShape.Box(-0.531D, 3.031D, 1.75D, 1.9375D, -1.625D, -0.75D));

    /** 部品ブロックを敷くマス（本体からの相対座標）。拡大率が変わると変わるので、都度作る。 */
    public static java.util.List<net.minecraft.core.BlockPos> template() {
        return SiegeStructureShape.template(COLLISION_BOXES, scale(),
                com.yayuy.overgrownvegetation.block.SiegeStructurePartBlock.MAX_HORIZONTAL, 3);
    }

    private SiegeCannonGeometry() {
    }
}
