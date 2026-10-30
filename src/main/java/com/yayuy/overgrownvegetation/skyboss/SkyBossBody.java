package com.yayuy.overgrownvegetation.skyboss;

/**
 * 空の巨大生物の体の寸法(モデルの単位。描画では {@link SkyBossTuning#BLOCKS_PER_UNIT} ブロック。{@code tools/g5-models/mobs/SkyGlaucus.java} と同じ値)。
 * 体の節は頭の先からの「道に沿った距離」で置く(頭の道をなぞる)。サーバー(当たり判定、次の段)とクライアント(描画)で共通。
 *
 * <h2>節の並び</h2>
 * 頭(head、首の輪 z = {@link #Z0} から前へ)→ b01〜b18(1 節 20)→ 尾 tail01〜16(1 節 16)。親子の鎖は body → b01 → b02 → … → b18 → tail01 → …、
 * 頭は b01 の子。
 * どの節も道に沿わせる(10-18 ユーザー「関節部がずっと曲がったまま」: 前は腕の付いた所 b02〜b11 を 1 本の弦にして曲げなかったので、道の曲がりが b02 と b12 の 2 か所に溜まって折れて見えた)。
 * 横の曲がりは扇を扇の面の中ですべらせるだけなので、重なった刃の上下は入れ替わりにくい。上下の曲がりは機首の上げ下げの速さを小さくして抑える。
 */
public final class SkyBossBody {

    /** 頭の先・首の輪(b01 の前)・節の長さ・節の数・胴の軸の高さ(モデルの単位)。 */
    public static final double HEAD_TIP_Z = -334.0;
    public static final double Z0 = -260.0;
    public static final double SEG_L = 20.0;
    public static final int SEGS = 18;
    public static final double TAIL_L = 16.0;
    public static final int TAIL = 16;
    public static final double Y0 = 160.0;

    /** 腕の付いた所の始まり(b02 の前の輪)と終わり(b11 の後ろの輪)の節の番号(b01 = 1)。 */
    public static final int ARM_FIRST = 2;
    public static final int ARM_LAST = 11;

    /** 頭の先から z の輪までの道の距離(ブロック。モデルの単位 × {@link SkyBossTuning#BLOCKS_PER_UNIT})。 */
    public static double along(double z) {
        return (z - HEAD_TIP_Z) * SkyBossTuning.BLOCKS_PER_UNIT;
    }

    /** b_i(1〜18)の前の輪の z。 */
    public static double segFrontZ(int i) {
        return Z0 + SEG_L * (i - 1);
    }

    /** 尾 k(1〜16)の前の輪の z。 */
    public static double tailFrontZ(int k) {
        return Z0 + SEG_L * SEGS + TAIL_L * (k - 1);
    }

    /** 腕の付いた所の真ん中までの道の距離(ブロック)。 */
    public static final double ARM_CENTER = (along(segFrontZ(ARM_FIRST)) + along(segFrontZ(ARM_LAST + 1))) / 2.0;
    /** 尾の先までの道の距離(全長、ブロック)。 */
    public static final double LENGTH = along(tailFrontZ(TAIL) + TAIL_L);

    private SkyBossBody() {
    }
}
