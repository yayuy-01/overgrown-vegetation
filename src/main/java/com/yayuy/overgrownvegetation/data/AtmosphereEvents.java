package com.yayuy.overgrownvegetation.data;

/**
 * 過繁茂の密林の環境ギミック G4「胞子の風」と G5「光る胞子の夜」の数値(節 {@code gale}・{@code glowNight}、2026-11-08。雲と組にした版)。
 * 読むのはクライアントの描画だけ({@code client/atmosphere/SporeGale}・{@code GlowNight}・{@code CloudShadows}・{@code CrepuscularRays})。
 * 中身はユーザーが選んだ(11-01・11-08)が、<b>数値はすべて仮決め</b>。
 */
public final class AtmosphereEvents {

    private AtmosphereEvents() {
    }

    /**
     * 節 {@code gale}(G4 胞子の風)。突風の前線({@code GustField})のうち {@code chance} の割合を「胞子の風」にする(カメラが過繁茂の近くで、雨でない日)。
     * <ul>
     *     <li>{@code strengthBonus}: 胞子の風の突風に足す強さ(葉と蔓がふつうの突風より大きく揺れる)。</li>
     *     <li>粒: {@code counts}(細かい胞子の点、高・中・低)・{@code puffCounts}(ぼけた胞子の雲)・{@code size}・{@code puffSize}・{@code alpha}・
     *     {@code puffAlpha}・{@code color}(黄緑)・{@code boxHalf}(カメラの横の広がり)・{@code heightBelow}・{@code heightAbove}(カメラの上下)・
     *     {@code lag}(前線に対して粒が遅れていく速さ、ブロック/秒)。</li>
     *     <li>光線の中で光る: {@code glow}(加算の明るさ)・{@code glowColor}。薄明光線({@code CrepuscularRays})の帯の中の粒だけ。</li>
     *     <li>霧: {@code fogDensity}(前線がカメラに来たときに霞に足す 1 ブロックあたりの濃さ)。</li>
     *     <li>前触れ(雲の影が走る): {@code omenLeadSeconds}(前線が来る何秒前に影がカメラを通るか)・{@code omenSpeed}(影の走る速さ、ブロック/秒)・
     *     {@code omenWidth}(影の帯の前後の幅)・{@code omenStrength}(影の濃さ 0〜1)。雲の影の地図に足すだけで、雲そのものは動かさない。</li>
     *     <li>森の中(林冠の下)の胞子の雲: {@code insidePuffAlpha}(濃さの倍率)・{@code insidePuffCount}(数の倍率)。11-08 ユーザー決定「半分くらいに・前線の縁が見えて景色が残る」。</li>
     * </ul>
     */
    public static final class GaleJson {
        public Boolean enabled;
        public Double chance, strengthBonus, size, puffSize, alpha, puffAlpha, boxHalf, heightBelow, heightAbove, lag, glow, fogDensity,
                omenLeadSeconds, omenSpeed, omenWidth, omenStrength, insidePuffAlpha, insidePuffCount;
        public int[] counts, puffCounts;
        public double[] color, glowColor;
    }

    public record GaleParams(boolean enabled, float chance, float strengthBonus, int[] counts, int[] puffCounts, float size, float puffSize,
                             float alpha, float puffAlpha, float[] color, float boxHalf, float heightBelow, float heightAbove, float lag,
                             float glow, float[] glowColor, float fogDensity, float omenLeadSeconds, float omenSpeed, float omenWidth,
                             float omenStrength, float insidePuffAlpha, float insidePuffCount) {
        static GaleParams of(GaleJson j) {
            GaleJson v = j != null ? j : new GaleJson();
            return new GaleParams(v.enabled == null || v.enabled, c(v.chance, 0.2, 0.0, 1.0), c(v.strengthBonus, 0.25, 0.0, 1.0),
                    tier(v.counts, new int[]{700, 420, 160}, 3000), tier(v.puffCounts, new int[]{160, 100, 40}, 600),
                    c(v.size, 0.08, 0.01, 0.5), c(v.puffSize, 4.5, 0.2, 12.0), c(v.alpha, 0.9, 0.0, 1.0), c(v.puffAlpha, 0.32, 0.0, 1.0),
                    rgb(v.color, 0.86, 0.96, 0.32), c(v.boxHalf, 36.0, 8.0, 96.0), c(v.heightBelow, 6.0, 0.0, 64.0),
                    c(v.heightAbove, 14.0, 0.0, 64.0), c(v.lag, 0.8, 0.0, 6.0), c(v.glow, 0.9, 0.0, 4.0), rgb(v.glowColor, 1.0, 0.96, 0.62),
                    c(v.fogDensity, 0.012, 0.0, 0.2), c(v.omenLeadSeconds, 4.0, 0.0, 30.0), c(v.omenSpeed, 16.0, 2.0, 80.0),
                    c(v.omenWidth, 40.0, 8.0, 200.0), c(v.omenStrength, 0.75, 0.0, 1.0),
                    c(v.insidePuffAlpha, 0.6, 0.0, 1.0), c(v.insidePuffCount, 0.8, 0.0, 1.0));
        }
    }

    /**
     * 節 {@code glowNight}(G5 光る胞子の夜)。雨上がり(雨が止んでから {@code wetMinutes} 分以内に夜を迎えた)で、雲に切れ目のある夜
     * (雲の量 {@code gapMin}〜{@code gapMax})に、夜ごとのくじ {@code chance} で起きる。起きた夜は明け方まで続く(また雨が降れば止む)。
     * <ul>
     *     <li>月の光の柱: 雲の影の地図を月の向きでも作り、薄明光線と同じ探し方で切れ目から柱を下ろす。{@code rays}(明るさ)・{@code rayColor}・
     *     {@code forestKeep}(森の中で残す割合。昼の光線は 1 割)・{@code moonShadow}(地形に落とす月の雲の影の濃さ)。</li>
     *     <li>降る光る胞子: {@code counts}(カメラのまわりに林冠から降る粒、高・中・低)・{@code pillarCounts}(月の柱 1 本の中の粒)・{@code maxPillars}・
     *     {@code size}・{@code haloSize}・{@code fallSpeed}(ブロック/秒)・{@code boxHalf}・{@code heightBelow}・{@code heightAbove}・
     *     {@code brightness}・{@code color}・{@code color2}(粒ごとに混ぜる。植物のエネルギーの青緑寄り)。</li>
     *     <li>{@code duskBoost}: 夕方の光の粒({@code DuskMotes})をこの夜は一晩中この割合で出す。</li>
     *     <li>{@code fadeSeconds}: 出入りの時定数。</li>
     * </ul>
     */
    public static final class GlowNightJson {
        public Boolean enabled;
        public Double chance, wetMinutes, gapMin, gapMax, rays, forestKeep, moonShadow, size, haloSize, fallSpeed, boxHalf, heightBelow,
                heightAbove, brightness, duskBoost, fadeSeconds;
        public Integer maxPillars;
        public int[] counts, pillarCounts;
        public double[] rayColor, color, color2;
    }

    public record GlowNightParams(boolean enabled, float chance, float wetMinutes, float gapMin, float gapMax, float rays, float[] rayColor,
                                  float forestKeep, float moonShadow, int[] counts, int[] pillarCounts, int maxPillars, float size,
                                  float haloSize, float fallSpeed, float boxHalf, float heightBelow, float heightAbove, float brightness,
                                  float[] color, float[] color2, float duskBoost, float fadeSeconds) {
        static GlowNightParams of(GlowNightJson j) {
            GlowNightJson v = j != null ? j : new GlowNightJson();
            float gapMin = c(v.gapMin, 0.2, 0.0, 1.0);
            return new GlowNightParams(v.enabled == null || v.enabled, c(v.chance, 0.3, 0.0, 1.0), c(v.wetMinutes, 8.0, 0.0, 60.0), gapMin,
                    Math.max(gapMin + 0.01F, c(v.gapMax, 0.88, 0.0, 1.0)), c(v.rays, 4.0, 0.0, 10.0), rgb(v.rayColor, 0.66, 0.84, 1.0),
                    c(v.forestKeep, 0.4, 0.0, 1.0), c(v.moonShadow, 0.35, 0.0, 1.0), tier(v.counts, new int[]{360, 220, 90}, 2000),
                    tier(v.pillarCounts, new int[]{70, 40, 0}, 400), v.maxPillars != null ? Math.max(0, Math.min(16, v.maxPillars)) : 6,
                    c(v.size, 0.09, 0.01, 0.4), c(v.haloSize, 0.6, 0.0, 3.0), c(v.fallSpeed, 0.45, 0.02, 4.0), c(v.boxHalf, 26.0, 6.0, 64.0),
                    c(v.heightBelow, 6.0, 0.0, 48.0), c(v.heightAbove, 22.0, 2.0, 64.0), c(v.brightness, 1.0, 0.0, 4.0),
                    rgb(v.color, 0.55, 1.0, 0.78), rgb(v.color2, 0.72, 1.0, 0.52), c(v.duskBoost, 0.7, 0.0, 1.0), c(v.fadeSeconds, 6.0, 0.5, 60.0));
        }
    }

    private static float c(Double v, double def, double min, double max) {
        double x = v != null && Double.isFinite(v) ? v : def;
        return (float) Math.max(min, Math.min(max, x));
    }

    private static int[] tier(int[] v, int[] def, int max) {
        int[] out = def.clone();
        if (v != null) {
            for (int i = 0; i < Math.min(3, v.length); i++) {
                out[i] = Math.max(0, Math.min(max, v[i]));
            }
        }
        return out;
    }

    private static float[] rgb(double[] v, double r, double g, double b) {
        double[] s = v != null && v.length >= 3 ? v : new double[]{r, g, b};
        return new float[]{c(s[0], r, 0.0, 2.0), c(s[1], g, 0.0, 2.0), c(s[2], b, 0.0, 2.0)};
    }
}
