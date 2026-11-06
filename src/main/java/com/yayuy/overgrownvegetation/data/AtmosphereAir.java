package com.yayuy.overgrownvegetation.data;

/**
 * 胞子の空気(節 sporeAir、2026-11-06)。風下の平原に流れる薄い黄緑の霞と、視線の先の濁り。読むのはクライアントの描画だけ
 * ({@code client/atmosphere/SporeAir}・{@code MistView}・{@code MistFog}・{@code AmbientSpores}・{@code RainLook})。
 *
 * <p>ユーザー決定(11-06「勧めの通りで」): 浮遊だけで霞む(林床は入れない)・森の縁から 128 ブロックで「ほんのり分かる」・色は森の胞子の霧より淡い黄緑
 * (地平線と画面の消し色が少し黄緑に寄るのは可)・雨は場に従って澄む・夜は黄緑がほぼ見えなくてよい・{@code /ov corruption false} の世界では出さない・
 * 森の外の胞子の粒は森の中の半分以下。数はすべて仮決め:
 * <ul>
 *     <li>{@code from}〜{@code full}: 浮遊の濃度 %。平方根の物差しで 0 → 1 = 胞子の空気の度合い(縁の外 128 で約 0.06、64 で約 0.4、40% で 1)。</li>
 *     <li>{@code hazeDensity}: 度合い 1 のときの 1 ブロックあたりの霞(森の霞 forestFogDensity 0.03 の 5 分の 1)。カメラの所の分はバニラの霧の印の k に足し、
 *     視線の先の分(カメラより濁った所を通る分)は地形シェーダー({@code ov_fog.glsl} の ovAirFog)が足す。森の中は森の霞に譲る(× (1 − 森の霧の度合い))。</li>
 *     <li>{@code colorMix}: 霧の色を霞の色 {@code color}(明るさは霧の色にそろえる)へ寄せる割合 × 度合い。</li>
 *     <li>{@code night}: 夜の霞の濃さの倍率。</li>
 *     <li>{@code spores}・{@code sporeCeiling}: 森の外の升の漂う胞子の濃さ(度合い 1 で、森の中を 1 として)と、粒の天井(その升の一番上 + ブロック)。</li>
 *     <li>{@code smoothSeconds}: カメラの所の度合いを寄せる時定数(秒)。</li>
 * </ul>
 */
public final class AtmosphereAir {

    private AtmosphereAir() {
    }

    public static final class SporeAirJson {
        /** false = 胞子の空気を切る(撮り比べ用)。 */
        public Boolean enabled;
        public Double from, full, hazeDensity, colorMix, night, spores, sporeCeiling, smoothSeconds;
        public double[] color;
    }

    public record SporeAirParams(boolean enabled, float from, float full, float hazeDensity, float colorMix, float[] color, float night,
                                 float spores, float sporeCeiling, float smoothSeconds) {
        static SporeAirParams of(SporeAirJson j) {
            SporeAirJson v = j != null ? j : new SporeAirJson();
            double[] c = v.color != null && v.color.length >= 3 ? v.color : new double[]{0.80, 0.88, 0.62};
            float from = c(v.from, 1.0, 0.0, 400.0);
            return new SporeAirParams(v.enabled == null || v.enabled, from, Math.max(from + 0.01F, c(v.full, 40.0, 0.0, 400.0)),
                    c(v.hazeDensity, 0.006, 0.0, 0.1), c(v.colorMix, 0.5, 0.0, 1.0),
                    new float[]{c(c[0], 0.80, 0.0, 1.0), c(c[1], 0.88, 0.0, 1.0), c(c[2], 0.62, 0.0, 1.0)},
                    c(v.night, 0.5, 0.0, 2.0), c(v.spores, 0.5, 0.0, 1.0), c(v.sporeCeiling, 12.0, 0.0, 64.0), c(v.smoothSeconds, 4.0, 0.0, 60.0));
        }

        /** 濃度 % → 度合い 0〜1(平方根の物差しで from〜full)。 */
        public float level(float percent) {
            if (percent <= from) {
                return 0.0F;
            }
            double a = Math.sqrt(from);
            double t = (Math.sqrt(percent) - a) / (Math.sqrt(full) - a);
            return (float) Math.max(0.0, Math.min(1.0, t));
        }
    }

    private static float c(Double v, double def, double min, double max) {
        double x = v != null && Double.isFinite(v) ? v : def;
        return (float) Math.max(min, Math.min(max, x));
    }
}
