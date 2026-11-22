package com.yayuy.overgrownvegetation.data;

/**
 * 巨大樹の演出(節 giantTree、2026-11-06 巨大樹の改善案 ① 胞子の柱・③ 夜の地脈の光。どちらもユーザー採用、<b>数はすべて仮決め</b>)。
 * 読むのはクライアントの描画だけ({@code client/atmosphere/SporeColumns}・{@code LeyPulses})。
 *
 * <h2>① 胞子の柱 {@code sporeColumn}</h2>
 * 梢の真上から胞子が煙のように立ち昇り、卓越風の風下へたなびく。濃さはサーバーの胞子の場と同じ規則(梢の 1 日の放出 = 育ち具合 + 朝 6〜9 時の上乗せ)
 * を成木の朝の放出で割った度合い × 天気の倍率(場の沈着の速さ: (depDay ÷ 今の沈着)^{@code weatherPower}。雨・夜は速く沈む = 薄い)。
 * <ul>
 *     <li>{@code riseMin}〜{@code riseMax}: 梢の上へ昇る高さ(度合い 0 → 1、ブロック)。その後で風下へ曲がる。</li>
 *     <li>{@code trail}: 風下へたなびく長さ(ブロック。終わりで消える)。{@code windTrail}: 卓越風の強さ 1 ごとに足す長さ。</li>
 *     <li>{@code widthBase}・{@code widthEnd}: 梢の上の太さ(傘の半径に掛ける)と、たなびきの終わりの太さ(ブロック)。</li>
 *     <li>{@code alpha}: 度合い 1 の不透明さ(真ん中の筋)。{@code color}: 色(明るさは空の明るさで落とす)。</li>
 *     <li>{@code farFade}: 描画距離の何倍まで描くか(その手前の 4 割で薄れて消える。上限 4 = 遠くの描画の切れ目)。</li>
 *     <li>{@code drift}: 煙の粒の流れる速さ(ブロック / 秒、卓越風の強さ 1 のとき)。</li>
 * </ul>
 *
 * <h2>③ 夜の地脈の光 {@code leyPulse}</h2>
 * 夜、幹の表面を光の脈が根元から傘へ昇る。ハイヴノードの地脈の貯蓄(今は仮の物差し: 育ち具合 × 地脈の線への近さ。{@code GiantTreeSync})が多いほど強く速く。
 * <ul>
 *     <li>{@code color}: 地脈の色(#ace300)。{@code alpha}: 貯蓄 1 の脈の明るさ(足し算の光)。{@code trace}: 脈の通り道のかすかな光(脈の明るさに掛ける)。</li>
 *     <li>{@code periodSlow}〜{@code periodFast}: 根元から幹の先まで昇る秒(貯蓄 0 → 1)。{@code length}: 脈の長さ(ブロック)。{@code width}: 筋の幅(ブロック)。</li>
 *     <li>{@code veins}: 幹のまわりの筋の数(1〜8)。{@code duskStart}〜{@code duskFull}・{@code dawnStart}〜{@code dawnEnd}: 灯り始め〜灯りきる・消え始め〜消える時刻(dayTime)。</li>
 *     <li>{@code distance}: 描く遠さ(ブロック。終わりの 3 割で薄れる)。</li>
 * </ul>
 */
public final class AtmosphereGiantTree {

    private AtmosphereGiantTree() {
    }

    public static final class Json {
        public SporeColumnJson sporeColumn;
        public LeyPulseJson leyPulse;
    }

    public static final class SporeColumnJson {
        public Boolean enabled;
        public Double riseMin, riseMax, trail, windTrail, widthBase, widthEnd, alpha, farFade, drift, weatherPower;
        public double[] color;
    }

    public static final class LeyPulseJson {
        public Boolean enabled;
        public Double alpha, trace, periodSlow, periodFast, length, width, veins, duskStart, duskFull, dawnStart, dawnEnd, distance;
        public double[] color;
    }

    public record SporeColumnParams(boolean enabled, float riseMin, float riseMax, float trail, float windTrail, float widthBase, float widthEnd,
                                    float alpha, float farFade, float drift, float weatherPower, float[] color) {
        static SporeColumnParams of(SporeColumnJson j) {
            SporeColumnJson v = j != null ? j : new SporeColumnJson();
            return new SporeColumnParams(v.enabled == null || v.enabled, c(v.riseMin, 30.0, 0.0, 200.0), c(v.riseMax, 60.0, 0.0, 300.0),
                    c(v.trail, 320.0, 0.0, 2000.0), c(v.windTrail, 80.0, 0.0, 1000.0), c(v.widthBase, 0.45, 0.05, 2.0), c(v.widthEnd, 110.0, 1.0, 600.0),
                    c(v.alpha, 1.3, 0.0, 3.0), c(v.farFade, 4.0, 1.0, 4.0), c(v.drift, 4.0, 0.0, 50.0), c(v.weatherPower, 0.5, 0.0, 2.0),
                    rgb(v.color, 0.74, 0.85, 0.36));
        }
    }

    public record LeyPulseParams(boolean enabled, float alpha, float trace, float periodSlow, float periodFast, float length, float width, int veins,
                                 float duskStart, float duskFull, float dawnStart, float dawnEnd, float distance, float[] color) {
        static LeyPulseParams of(LeyPulseJson j) {
            LeyPulseJson v = j != null ? j : new LeyPulseJson();
            return new LeyPulseParams(v.enabled == null || v.enabled, c(v.alpha, 0.85, 0.0, 4.0), c(v.trace, 0.08, 0.0, 1.0),
                    c(v.periodSlow, 14.0, 1.0, 120.0), c(v.periodFast, 5.0, 0.5, 120.0), c(v.length, 14.0, 1.0, 120.0), c(v.width, 0.9, 0.1, 4.0),
                    (int) c(v.veins, 5.0, 1.0, 8.0), c(v.duskStart, 12000.0, 0.0, 24000.0), c(v.duskFull, 13500.0, 0.0, 24000.0),
                    c(v.dawnStart, 22500.0, 0.0, 24000.0), c(v.dawnEnd, 23700.0, 0.0, 24000.0), c(v.distance, 512.0, 32.0, 4096.0),
                    rgb(v.color, 0.675, 0.89, 0.0));
        }

        /** 夜の度合い 0〜1(dayTime を 1 日で割った余りで)。 */
        public float night(long dayTime) {
            float t = Math.floorMod(dayTime, 24000L);
            if (t < duskStart || t >= dawnEnd) {
                // 真夜中を越えた後(0〜dawnStart)はつながっていないので、ここは昼
                return 0.0F;
            }
            if (t < duskFull) {
                return (t - duskStart) / Math.max(1.0F, duskFull - duskStart);
            }
            if (t >= dawnStart) {
                return 1.0F - (t - dawnStart) / Math.max(1.0F, dawnEnd - dawnStart);
            }
            return 1.0F;
        }
    }

    public record Params(SporeColumnParams sporeColumn, LeyPulseParams leyPulse) {
        static Params of(Json j) {
            Json v = j != null ? j : new Json();
            return new Params(SporeColumnParams.of(v.sporeColumn), LeyPulseParams.of(v.leyPulse));
        }
    }

    private static float[] rgb(double[] c, double r, double g, double b) {
        double[] v = c != null && c.length >= 3 ? c : new double[]{r, g, b};
        return new float[]{c(v[0], r, 0.0, 1.0), c(v[1], g, 0.0, 1.0), c(v[2], b, 0.0, 1.0)};
    }

    private static float c(Double v, double def, double min, double max) {
        double x = v != null && Double.isFinite(v) ? v : def;
        return (float) Math.max(min, Math.min(max, x));
    }
}
