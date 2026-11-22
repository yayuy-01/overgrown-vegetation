package com.yayuy.overgrownvegetation.client.atmosphere;

/**
 * <b>霧の層の式</b>(2026-10-30 作り直しの第 3 段「霧」、方式 B の土台)。Minecraft の型を使わない(素の java で検算するため)。
 * シェーダー {@code ov_fog.glsl} と同じ式の CPU 版と、時刻・日ごとの濃さの形。
 *
 * <ul>
 *     <li><b>層</b>: てっぺん T(地図 {@link MistMap} が谷の底 + 厚さと梢 + 少しの高い方で決める)より下は濃さ一定 ρ0、上は
 *     ρ0 · exp(−k · (y − T))(柔らかい天井)。視線の上で「てっぺんからの高さ h」が直線に変わる区間なら、通る量は閉じた式
 *     {@link #segment} で出る(h の変化がほぼ 0 の所は真ん中の濃さ × 長さ)。</li>
 *     <li><b>朝</b>: 4 時から出て 5〜8 時がいちばん濃く、9 時で消える({@link AirHaze} の朝の湿り気と同じ形。5〜9 時は 10-16 了承)。</li>
 *     <li><b>日ごと</b>: 毎朝出るが、濃さと厚さはその日の番号(ワールドの日数)のハッシュで薄い朝〜濃い朝(10-30 夜 相談セッションの答え)。
 *     雨上がりはいつも濃い朝へ寄せる。</li>
 * </ul>
 * 数値は {@code atmosphere.json} の節 {@code fog}。
 */
public final class MistLayer {

    private MistLayer() {
    }

    /** 層のてっぺんからの高さ {@code h}(負 = 中)の濃さの割合(0〜1)。 */
    public static double density(double h, double k) {
        return h <= 0.0 ? 1.0 : Math.exp(-k * h);
    }

    /** {@link #density} を h で積んだもの(原始関数。h = 0 でつながる)。 */
    static double antiderivative(double h, double k) {
        return h <= 0.0 ? h - 1.0 / k : -Math.exp(-k * h) / k;
    }

    /**
     * 長さ {@code len} の区間で h が {@code h0} から {@code h1} へ直線に変わるとき、濃さの割合を積んだ量(ブロック)。
     * シェーダーの {@code ovFogSegment} と同じ。
     */
    public static double segment(double h0, double h1, double len, double k) {
        double dh = h1 - h0;
        if (Math.abs(dh) < 0.05) {
            return len * density(0.5 * (h0 + h1), k);
        }
        return len * (antiderivative(h1, k) - antiderivative(h0, k)) / dh;
    }

    /** カメラが層の中にいる度合い(0〜1)。てっぺんから {@code soft} ブロック下で 1。 */
    public static double inside(double h, double soft) {
        if (h >= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, -h / Math.max(0.01, soft));
    }

    /** 層の上にいるカメラが見る濃さの倍率(ある点のてっぺんよりカメラが {@code above} ブロック上)。ramp ブロック上で boost 倍。 */
    public static double aboveBoost(double above, double boost, double ramp) {
        double t = Math.max(0.0, Math.min(1.0, above / Math.max(0.01, ramp)));
        return 1.0 + (boost - 1.0) * t;
    }

    /**
     * 朝の形(0〜1)。{@code dayTime} はバニラの時刻(0 = 6 時)。4 時から出て 5〜8 時が 1、9 時で 0。
     * 間はなめらかに({@link AirHaze} と同じ)。
     */
    public static double morning(long dayTime) {
        long day = Math.floorMod(dayTime, 24000L);
        if (day >= 22000L) {
            return smooth(Math.min(1.0, (day - 22000L) / 1000.0));
        }
        if (day <= 2000L) {
            return 1.0;
        }
        return 1.0 - smooth(Math.min(1.0, (day - 2000L) / 1000.0));
    }

    /** その朝の番号(深夜 2 時で切り替わる。4〜9 時の朝霧の間は同じ番号)。 */
    public static long morningIndex(long dayTime) {
        return Math.floorDiv(dayTime + 4000L, 24000L);
    }

    /** その朝の濃さ(min〜1)。朝の番号のハッシュ。 */
    public static double morningAmount(long index, double min) {
        long z = index * 0x9E3779B97F4A7C15L ^ 0x6D6973742D6D6F72L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        double u = (z >>> 11) * 0x1.0p-53;
        return min + (1.0 - min) * u;
    }

    static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }
}
