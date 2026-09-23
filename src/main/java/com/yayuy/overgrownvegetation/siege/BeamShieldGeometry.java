package com.yayuy.overgrownvegetation.siege;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 遮蔽盾({@code beam_shield})のモデル {@code geo/beam_shield.geo.json} の<b>寸法</b>と、
 * 「この線分は盾に遮られるか」の判定。サーバー(ビームのダメージ判定)とクライアント(描画)の
 * 両方が同じ値を使う。
 *
 * <h2>モデルの座標系</h2>
 * <p>砲台({@link SiegeCannonGeometry})と同じ規約: geo では<b>盾の表(ビームが来る側)が −X</b>。
 * GeckoLib は読み込み時に X を反転するので、モデル空間では表が +X になり、方位 A へ向けるには
 * {@code base.setRotY(radians(90 − A))}。
 *
 * <h2>展開</h2>
 * <p>盾は 3 段の入れ子({@code panel1/2/3})で、たたんだ状態では全部が最下段に収まっている。
 * 展開すると 2 段目・3 段目が {@link #STAGE_RISE} ずつせり上がる(ボーンの {@code posY})。
 * ビームを遮るのは<b>完全に展開しきったときだけ</b>。
 */
public final class BeamShieldGeometry {

    // --- geo のモデル単位(16 = 1 ブロック) ---

    /** 盾の幅(= geo の z 方向、48 単位 = 3 ブロック)。 */
    public static final double PANEL_WIDTH_UNITS = 48.0D;
    /** 最下段の下端(基部の高さ)。 */
    public static final double PANEL_BOTTOM_UNITS = 6.0D;
    /** 1 段の高さ。 */
    public static final double STAGE_HEIGHT_UNITS = 30.0D;
    /** 展開時に 1 段ぶんせり上がる量。 */
    public static final double STAGE_RISE = 26.0D;
    /** 展開しきったときの上端 = 6 + 30 + 26 + 26 = 88 単位(5.5 ブロック)。 */
    public static final double PANEL_TOP_UNITS = PANEL_BOTTOM_UNITS + STAGE_HEIGHT_UNITS + STAGE_RISE * 2.0D;

    /** 盾の幅(ブロック)。 */
    public static final double PANEL_WIDTH = PANEL_WIDTH_UNITS / 16.0D;
    /** 遮蔽面の下端・上端(ブロックの底面からの高さ)。 */
    public static final double PANEL_BOTTOM = PANEL_BOTTOM_UNITS / 16.0D;
    public static final double PANEL_TOP = PANEL_TOP_UNITS / 16.0D;

    /** モデルの拡大率(config)。遮蔽面の大きさも同じ倍率で変わる。 */
    public static double scale() {
        return OvergrownVegetationConfig.COMMON.shieldScale.get();
    }

    /** 盾の表が向いている単位ベクトル(水平)。 */
    public static Vec3 normal(double azimuthDeg) {
        return CannonBallistics.direction(0.0D, azimuthDeg);
    }

    /** 盾の面に沿った「右」の単位ベクトル(水平)。 */
    public static Vec3 right(double azimuthDeg) {
        return CannonBallistics.direction(0.0D, azimuthDeg + 90.0D);
    }

    /** 遮蔽面の中心(下端)のワールド座標。 */
    public static Vec3 panelBase(BlockPos pos, double azimuthDeg) {
        return Vec3.atBottomCenterOf(pos).add(0.0D, PANEL_BOTTOM * scale(), 0.0D);
    }

    /**
     * 線分 {@code from → to} が、{@code pos} に置かれ方位 {@code azimuthDeg} を向いた
     * 展開済みの盾に遮られるか。
     *
     * <p>盾は厚みの無い長方形として扱う: 線分が面の平面を横切る点を求め、その点が
     * 幅 {@link #PANEL_WIDTH}・高さ {@link #PANEL_BOTTOM}〜{@link #PANEL_TOP} の中に
     * 入っていれば遮られている。裏表は問わない(どちら向きから来ても遮る)。
     */
    public static boolean blocks(BlockPos pos, double azimuthDeg, Vec3 from, Vec3 to) {
        double s = scale();
        Vec3 base = panelBase(pos, azimuthDeg);
        Vec3 normal = normal(azimuthDeg);
        double d0 = from.subtract(base).dot(normal);
        double d1 = to.subtract(base).dot(normal);
        if (d0 == d1 || (d0 > 0.0D) == (d1 > 0.0D)) {
            // 平面を横切らない(同じ側にいる)。
            return false;
        }
        double t = d0 / (d0 - d1);
        if (t < 0.0D || t > 1.0D) {
            return false;
        }
        Vec3 hit = from.add(to.subtract(from).scale(t));
        Vec3 offset = hit.subtract(base);
        double across = offset.dot(right(azimuthDeg));
        if (Math.abs(across) > PANEL_WIDTH * s * 0.5D) {
            return false;
        }
        double height = hit.y - Vec3.atBottomCenterOf(pos).y;
        return height >= PANEL_BOTTOM * s && height <= PANEL_TOP * s;
    }

    // --- 当たり判定 ---

    /**
     * ハンドル(台の後ろ左の輪と柱)に当たったとみなす範囲。前・上・右(ブロック)。
     * 柱・軸・輻(スポーク)をまとめて覆う。当たり判定は 0.25 ブロック刻みなので、余裕をもたせてある。
     */
    public static final SiegeStructureShape.Box HANDLE_ZONE =
            new SiegeStructureShape.Box(-2.0D, -0.85D, 0.40D, 1.5D, -1.7D, -0.3D);
    /** ボタン(台の後ろ右の押しボタンとその台座)に当たったとみなす範囲。 */
    public static final SiegeStructureShape.Box BUTTON_ZONE =
            new SiegeStructureShape.Box(-2.0D, -0.85D, 0.40D, 1.1D, 0.3D, 1.1D);

    /**
     * いまの展開の進み { progress}（0〜1）での当たり判定の塊。
     * 盾板は 3 段の入れ子なので、たたんでいるうちは低く、展開すると上へ伸びる。
     */
    public static java.util.List<SiegeStructureShape.Box> collisionBoxes(float progress) {
        double panelTop = (PANEL_BOTTOM_UNITS + STAGE_HEIGHT_UNITS + STAGE_RISE * 2.0D * progress) / 16.0D;
        return java.util.List.of(
                // 台 geo x 0..30, y 0..6, z -26..26
                new SiegeStructureShape.Box(-1.875D, 0.0D, 0.0D, 0.375D, -1.625D, 1.625D),
                // 前枠 geo x -5..0, y 0..11
                new SiegeStructureShape.Box(0.0D, 0.3125D, 0.0D, 0.6875D, -1.625D, 1.625D),
                // 側枠（左右）geo z -26..-22 / 22..26, x -5..30, y 0..12
                new SiegeStructureShape.Box(-1.875D, 0.3125D, 0.0D, 0.75D, -1.625D, -1.375D),
                new SiegeStructureShape.Box(-1.875D, 0.3125D, 0.0D, 0.75D, 1.375D, 1.625D),
                // ハンドル柱 geo x 19..26, y 6..15, z -14..-7
                new SiegeStructureShape.Box(-1.625D, -1.1875D, 0.375D, 0.9375D, -0.875D, -0.4375D),
                // ボタン台 geo x 19..26, y 6..13, z 7..14
                new SiegeStructureShape.Box(-1.625D, -1.1875D, 0.375D, 0.8125D, 0.4375D, 0.875D),
                // 盾板（せり上がる）geo x -3..3, z -24..24
                new SiegeStructureShape.Box(-0.1875D, 0.1875D, PANEL_BOTTOM, panelTop, -1.5D, 1.5D));
    }

    /** 部品ブロックを敷くマス。展開しきった状態で見て、上まで場所を確保しておく。 */
    public static java.util.List<net.minecraft.core.BlockPos> template() {
        return SiegeStructureShape.template(collisionBoxes(1.0F), scale(),
                com.yayuy.overgrownvegetation.block.SiegeStructurePartBlock.MAX_HORIZONTAL,
                com.yayuy.overgrownvegetation.block.SiegeStructurePartBlock.MAX_UP + 1);
    }

    private BeamShieldGeometry() {
    }
}
