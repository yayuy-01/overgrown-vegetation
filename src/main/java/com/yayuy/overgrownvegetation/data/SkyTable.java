package com.yayuy.overgrownvegetation.data;

/**
 * 空と雲の設計値({@code sky.json}、2026-11-08 雲の段階 1「土台」。正本は Vault「ボクセル調の雲・積乱雲・雲の中の巨大生物」)。
 * 10-30 に退避した雲(parked の U7)の設定項目(COMMON の {@code voxelClouds}・{@code cloud*}・{@code crepuscularRays}・{@code highSky*} など)を、
 * 今の作法(design の json + mod マネージャー)へ移したもの。サーバー(雲の風の積分 {@code sky.CloudDrift})とクライアント(描画)の両方が読む。
 *
 * <p>ユーザー指定の値: 雲の層 y 300〜350(これは {@code CloudField} の定数のまま)・高空の晴天 300〜350。ほかは全部仮決め。
 * オン/オフはクライアント設定 {@code OvergrownClientConfig#clouds}、重さの段は {@code atmosphereQuality}(高・中・低)。
 */
public final class SkyTable {

    public static final SkyTable DEFAULT = new SkyTable(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public CloudsJson clouds;
        public WindJson wind;
        public ShadowsJson shadows;
        public RaysJson rays;
        public HighSkyJson highSky;
    }

    public static final class CloudsJson {
        /** 雲の量を固定する(−1 = 日ごとに変わる)。 */
        public Double coverage;
        /** 雲の並び方(1 = 群れ・2 = 谷のある雲の海・3 = 壁と橋)。10-17 の既定 2。 */
        public Integer style;
        /** 雲の見た目(0〜1): セル画の陰影・輪郭のやわらかさ・荒天の青灰・風の筋・遠くの青い霞・質感・夜の雲の明るさ・雲の海の丸み・雲の上の遠景。 */
        public Double celShading;
        public Double softEdges;
        public Double roughWeather;
        public Double windStreaks;
        public Double blueHaze;
        public Double texture;
        public Double nightClouds;
        public Double curvature;
        public Double farClouds;
        /** 谷のある雲の海(並び方 2・3)の厚さの上限(ブロック。y 300 から)。ユーザー指定 11-08「下の層 y 300〜350 を守る」で 50。空のボス(G10)が戻るときに 252(前の 1.3 倍の厚さ)へ上げ直せるように値にした。 */
        public Double floorThickness;
        /** 雲の海の上面の平らさ(0 = 丘のまま 〜 1 = ほぼ平ら)。11-08 ユーザーの見本(ナウシカ 11〜23 秒「なめらかな薄紫の床」)と、雲の床を水面のように使う決定から。仮決め 0.75。 */
        public Double floorFlatness;
        /** 時間帯ごとの雲の色(日の当たる側・影の側に掛ける色 [r, g, b])。昼・夕方・夜。11-08 仮決め(色はユーザーと風景を見ながら決める)。 */
        public double[] litDay, litDusk, litNight, shadeDay, shadeDusk, shadeNight;
    }

    public static final class WindJson {
        /** 卓越風の強さ 1(ふだんの午後)のときに雲が流れる速さ(ブロック/tick)。前の雲の風の平均 0.05 に合わせた。 */
        public Double blocksPerTick;
        /** 雲の高さの風の強さの下限(地上の夜の凪でも、雲の高さでは止まらない)。 */
        public Double minStrength;
        /** 強さの上限(嵐 3 のとき雲が速すぎないように)。 */
        public Double maxStrength;
    }

    public static final class ShadowsJson {
        public Double strength;
    }

    public static final class RaysJson {
        public Double strength;
    }

    public static final class HighSkyJson {
        public Double clarity;
        public Double clearFrom;
        public Double clearTo;
    }

    public final double coverage;
    public final int style;
    public final float celShading, softEdges, roughWeather, windStreaks, blueHaze, texture, nightClouds, curvature, farClouds;
    public final float floorThickness;
    public final float floorFlatness;
    public final float[] litDay, litDusk, litNight, shadeDay, shadeDusk, shadeNight;
    public final double windBlocksPerTick, windMinStrength, windMaxStrength;
    public final float shadows;
    public final float rays;
    public final float highSkyClarity, highSkyClearFrom, highSkyClearTo;

    private SkyTable(Json j) {
        CloudsJson c = j == null || j.clouds == null ? new CloudsJson() : j.clouds;
        WindJson w = j == null || j.wind == null ? new WindJson() : j.wind;
        ShadowsJson s = j == null || j.shadows == null ? new ShadowsJson() : j.shadows;
        RaysJson r = j == null || j.rays == null ? new RaysJson() : j.rays;
        HighSkyJson h = j == null || j.highSky == null ? new HighSkyJson() : j.highSky;
        coverage = clamp(c.coverage, -1.0, -1.0, 1.0);
        style = c.style == null ? 2 : Math.max(1, Math.min(3, c.style));
        celShading = f(c.celShading, 1.0);
        softEdges = f(c.softEdges, 1.0);
        roughWeather = f(c.roughWeather, 1.0);
        windStreaks = f(c.windStreaks, 1.0);
        blueHaze = f(c.blueHaze, 1.0);
        texture = f(c.texture, 1.0);
        nightClouds = f(c.nightClouds, 1.0);
        curvature = f(c.curvature, 1.0);
        farClouds = f(c.farClouds, 1.0);
        floorThickness = (float) clamp(c.floorThickness, 50.0, 8.0, 600.0);
        floorFlatness = f(c.floorFlatness, 0.75);
        litDay = rgb(c.litDay, 1.0F, 1.0F, 1.0F);
        litDusk = rgb(c.litDusk, 1.0F, 0.84F, 0.74F);
        litNight = rgb(c.litNight, 1.0F, 1.0F, 1.0F);
        shadeDay = rgb(c.shadeDay, 1.0F, 1.0F, 1.0F);
        shadeDusk = rgb(c.shadeDusk, 0.86F, 0.82F, 1.0F);
        shadeNight = rgb(c.shadeNight, 1.0F, 1.0F, 1.0F);
        windBlocksPerTick = clamp(w.blocksPerTick, 0.05, 0.0, 1.0);
        windMinStrength = clamp(w.minStrength, 0.5, 0.0, 3.0);
        windMaxStrength = Math.max(windMinStrength, clamp(w.maxStrength, 2.0, 0.0, 10.0));
        shadows = f(s.strength, 1.0);
        rays = f(r.strength, 1.0);
        highSkyClarity = f(h.clarity, 1.0);
        highSkyClearFrom = (float) clamp(h.clearFrom, 300.0, 64.0, 2000.0);
        highSkyClearTo = (float) Math.max(highSkyClearFrom + 1.0, clamp(h.clearTo, 350.0, 65.0, 2100.0));
    }

    public static SkyTable fromJson(Json j) {
        return new SkyTable(j);
    }

    private static float[] rgb(double[] v, float r, float g, float b) {
        if (v == null || v.length < 3) {
            return new float[]{r, g, b};
        }
        return new float[]{(float) Math.max(0.0, Math.min(2.0, v[0])), (float) Math.max(0.0, Math.min(2.0, v[1])), (float) Math.max(0.0, Math.min(2.0, v[2]))};
    }

    private static float f(Double v, double def) {
        return (float) clamp(v, def, 0.0, 1.0);
    }

    private static double clamp(Double v, double def, double lo, double hi) {
        if (v == null || v.isNaN()) {
            return def;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
