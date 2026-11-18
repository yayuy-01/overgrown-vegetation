package com.yayuy.overgrownvegetation.client.sky;

/**
 * 空の試し用の値(2026-11-18。ユーザー「地平線がないから見てみたい風景が作れない」「高さの違う層に分けるのはあり」→ 前後を撮って決める)。
 * DevBridge の {@code wx sky <名前> <値>} で起動したまま替える。既定はどれも今の見た目のまま。
 */
public final class SkyDev {
    /** 雲の丸みの半径(0 = 今の {@link VoxelClouds#CURVE_RADIUS})。小さいほど遠くの雲が早く沈む。 */
    public static volatile float curveRadius;
    /** 地平線のかすみ(0〜1)。地平線の近くの空と遠くの雲を白っぽい霞へ溶かす。 */
    public static volatile float horizonHaze;
    /** 低めの段の筋雲の高さ(0 = 出さない)。 */
    public static volatile float midCirrus;
    /** 巻雲の量の固定(負 = 日ごと)。撮るとき用。 */
    public static volatile float cirrusAmount = -1.0F;
    /** 地上からも遠くの雲の輪を出す(0 = 今のまま y 410 以上だけ・1 = 7680 まで・2 = 外の輪 18432 まで)。 */
    public static volatile float farLow;
    /** 低めの段の筋雲の模様のずらし(場の座標)。 */
    public static volatile float midShift = 23000.0F;

    static float curveRadius() {
        return curveRadius > 0.0F ? curveRadius : VoxelClouds.CURVE_RADIUS;
    }

    public static String set(String name, float value) {
        switch (name) {
            case "curve" -> curveRadius = value;
            case "haze" -> horizonHaze = value;
            case "mid" -> midCirrus = value;
            case "cirrus" -> cirrusAmount = value;
            case "far" -> farLow = value;
            case "shift" -> midShift = value;
            default -> {
                return "WX sky: curve <半径>・haze <0〜1>・mid <高さ>・cirrus <量>";
            }
        }
        return "WX sky curve " + curveRadius + " haze " + horizonHaze + " mid " + midCirrus + " cirrus " + cirrusAmount + " far " + farLow + " shift " + midShift;
    }

    private SkyDev() {
    }
}
