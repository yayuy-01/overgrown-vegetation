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

    /** ちぎれ雲の底と厚みを散らす強さ(0 = 今のまま 〜 1)。つながった雲の海の日(量 0.5 以上)は効かない。 */
    public static volatile float scatter;

    /**
     * ちぎれ雲の柱 1 本の底と天辺(y 300 からの高さ)を、場所ごとの雑音でずらす(11-18 試し。ユーザー「雲同士の高低差がない」)。
     * 底は 4 段(y 260・308・356・404)、厚みは 0.5〜2.2 倍(天辺は y 450 まで)。数値は仮決め。
     */
    static void scatter(int[] bottom, int[] top, int k, double fx, double fz, float coverage) {
        float t = Math.min(Math.max((coverage - 0.30F) / 0.20F, 0.0F), 1.0F);
        float w = scatter * (1.0F - t * t * (3.0F - 2.0F * t));
        if (w <= 0.0F || bottom[k] >= top[k]) {
            return;
        }
        // 11-18: 高さは 4 段だけ(y 260・308・356・404)、雑音は大きく(1 段の広がりが数百ブロック)。細かい刻みと短い雑音では、1 つのかたまりの中が
        // 段々に割れて縦の破片に見えた。
        float a = noise(fx / 2600.0, fz / 2600.0, 11L);
        float b = noise(fx / 1700.0, fz / 1700.0, 29L);
        int level = Math.min(3, (int) (Math.min(Math.max((a - 0.2F) / 0.6F, 0.0F), 0.999F) * 4.0F));
        int off = Math.round((level * 48 - 40) * w / 8.0F) * 8;
        float f = 1.0F + w * (0.5F + 1.7F * b * b - 1.0F);
        int thick = Math.max(4, Math.round((top[k] - bottom[k]) * f / 4.0F) * 4);
        bottom[k] += off;
        top[k] = Math.max(bottom[k] + 4, Math.min(bottom[k] + thick, 150));
    }

    /** なめらかな値の雑音(0〜1)。 */
    private static float noise(double x, double z, long salt) {
        long ix = (long) Math.floor(x);
        long iz = (long) Math.floor(z);
        float u = (float) (x - ix);
        float v = (float) (z - iz);
        u = u * u * (3.0F - 2.0F * u);
        v = v * v * (3.0F - 2.0F * v);
        float a = lattice(ix, iz, salt);
        float b = lattice(ix + 1, iz, salt);
        float c = lattice(ix, iz + 1, salt);
        float d = lattice(ix + 1, iz + 1, salt);
        return (a + (b - a) * u) * (1.0F - v) + (c + (d - c) * u) * v;
    }

    private static float lattice(long x, long z, long salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 40) / (float) (1 << 24);
    }

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
            case "scatter" -> scatter = value;
            case "shift" -> midShift = value;
            default -> {
                return "WX sky: curve <半径>・haze <0〜1>・mid <高さ>・cirrus <量>";
            }
        }
        return "WX sky curve " + curveRadius + " haze " + horizonHaze + " mid " + midCirrus + " cirrus " + cirrusAmount + " far " + farLow + " shift " + midShift + " scatter " + scatter;
    }

    private SkyDev() {
    }
}
