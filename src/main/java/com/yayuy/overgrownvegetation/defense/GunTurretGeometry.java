package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.siege.CannonBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 機関砲台のモデル {@code assets/overgrown_vegetation/geo/gun_turret.geo.json} の<b>寸法</b>。
 * 銃口のワールド座標はサーバー(命中判定)とクライアント(発砲炎・曳光)の両方が要るので、
 * 描画専用にせずここに置いて双方が同じ式を使う({@link com.yayuy.overgrownvegetation.siege.SiegeCannonGeometry}
 * と同じ考え方)。
 *
 * <h2>モデルの座標系</h2>
 * <p>Bedrock(Blockbench)形式の geo は Y が上で、この砲台は<b>銃口が −Z 方向</b>(砲身の先端が
 * z = −21.6)を向いている。GeckoLib は読み込み時に X を反転して描くので、モデル空間では
 * x の符号だけが geo と逆になる(Z はそのまま)。ここでは分かりやすさのため、旋回軸(= ブロックの
 * 底面中央)から見た<b>前(銃口の向き)・上・右</b>の 3 つに直して持つ({@link #offset})。
 *
 * <h2>ボーン</h2>
 * <ul>
 *   <li>{@code base} — 台座ごと水平に旋回する。pivot は geo の時点で (0, 0, 0) = ブロックの底面中央。</li>
 *   <li>{@code gun} — 銃身。{@link #TRUNNION_X}, {@link #TRUNNION_Y}, {@link #TRUNNION_Z} の
 *       砲耳で俯仰する(可動域は {@code defense.json} の {@code gunTurret.minElevation} 〜
 *       {@code maxElevation} = 既定 −10 〜 +85 度)。</li>
 *   <li>{@code zyuukou} — 銃口。立方体を持たない「印」のボーンで、弾の出る点として pivot だけを使う。</li>
 * </ul>
 */
public final class GunTurretGeometry {

    // --- geo のモデル単位(16 = 1 ブロック)。X は GeckoLib の反転後(= geo の値の符号を返した値) ---

    /** 砲耳(gun ボーンの pivot)。geo では x = −0.5。 */
    public static final double TRUNNION_X = 0.5D;
    public static final double TRUNNION_Y = 13.2D;
    public static final double TRUNNION_Z = 3.65D;
    /** 銃口(zyuukou ボーンの pivot)。geo では x = −1.5。 */
    public static final double MUZZLE_X = 1.5D;
    public static final double MUZZLE_Y = 12.9D;
    public static final double MUZZLE_Z = -21.6D;

    // --- 旋回軸(ブロックの底面中央)から見た「前(銃口の向き = −Z)・上・右(+X)」(ブロック) ---

    public static final double TRUNNION_FORWARD = -TRUNNION_Z / 16.0D;
    public static final double TRUNNION_UP = TRUNNION_Y / 16.0D;
    public static final double TRUNNION_RIGHT = TRUNNION_X / 16.0D;

    // --- 砲耳から見た銃口(俯仰で回る前の、銃身に貼り付いた座標) ---

    public static final double MUZZLE_FORWARD = (TRUNNION_Z - MUZZLE_Z) / 16.0D;
    public static final double MUZZLE_UP = (MUZZLE_Y - TRUNNION_Y) / 16.0D;
    public static final double MUZZLE_RIGHT = (MUZZLE_X - TRUNNION_X) / 16.0D;

    /**
     * モデルの倍率({@code defense.json} の {@code gunTurret.modelScale}、既定 1.5)。
     * ここを変えると描画({@code client/GunTurretRenderer})と銃口・砲耳の位置がまとめて追従する。
     */
    public static double scale() {
        return DesignData.defense().turretModelScale();
    }

    /**
     * 旋回軸から見た点のワールドオフセット。距離は{@link #scale() モデルの倍率}が掛かる。
     *
     * @param forward    銃口の向きへの距離(ブロック、倍率を掛ける前)
     * @param up         上への距離(ブロック、倍率を掛ける前)
     * @param right      銃口を向いて右への距離(ブロック、倍率を掛ける前)
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

    /**
     * 銃口のワールド座標(弾が出る点)。砲耳に貼り付いた {@link #MUZZLE_FORWARD} 等を仰角で回してから
     * 方位で回す。
     */
    public static Vec3 muzzle(BlockPos pos, double elevationDeg, double azimuthDeg) {
        double el = Math.toRadians(elevationDeg);
        double cos = Math.cos(el);
        double sin = Math.sin(el);
        double forward = MUZZLE_FORWARD * cos - MUZZLE_UP * sin;
        double up = MUZZLE_FORWARD * sin + MUZZLE_UP * cos;
        return trunnion(pos, azimuthDeg).add(offset(forward, up, MUZZLE_RIGHT, azimuthDeg));
    }

    /**
     * 目標を狙うのに必要な向き。{@code from} は {@link #trunnion}(銃口ではなく回転中心)を渡すこと ——
     * 銃口は俯仰で動くので、銃口から角度を出すと自分で自分を追いかけてしまう。
     *
     * @return {@code [仰角, 方位]}(度)
     */
    public static double[] aimAt(Vec3 from, Vec3 target) {
        Vec3 d = target.subtract(from);
        return new double[]{CannonBallistics.elevationOf(d), CannonBallistics.azimuthOf(d.x, d.z)};
    }

    // --- 可動域(design/defense.json) ---

    public static double minElevation() {
        return Math.min(DesignData.defense().turretMinElevation(), DesignData.defense().turretMaxElevation());
    }

    public static double maxElevation() {
        return Math.max(DesignData.defense().turretMinElevation(), DesignData.defense().turretMaxElevation());
    }

    public static double clampElevation(double degrees) {
        return Mth.clamp(degrees, minElevation(), maxElevation());
    }

    /** 仰俯角の可動域に入っているか(入っていない相手は撃てない)。 */
    public static boolean elevationInRange(double degrees) {
        return degrees >= minElevation() - 1.0E-6D && degrees <= maxElevation() + 1.0E-6D;
    }

    private GunTurretGeometry() {
    }
}
