package com.yayuy.overgrownvegetation.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 天気の見た目の残り(2026-11-16)の設計値: {@code atmosphere.json} の節 {@code stormExtras}(雷雨の強化の残り: 地平線の遠い雷・スプライト・
 * 雷雨の後の胞子の雲・セントエルモの火・アーク雲)・{@code calmLook}(凪)・{@code galeLook}(強風の日)。数はすべて仮決め(絵を見て詰める)。
 *
 * <p>節は「名前 → 数」か「名前 → 色(3 つの数)」の平らな並び。既定値・下限・上限はここの表にあり、json に書いていない項目は既定値。
 * 読むのはクライアントの描画だけ({@code client/atmosphere/WeatherLook} とその部品)。試験用クライアントの命令 {@code wx set} は
 * {@link Params#with} で 1 つだけ差し替えた写しを作る(起動したまま値を詰めるため)。
 */
public final class WeatherLookEye {

    private WeatherLookEye() {
    }

    /** 1 つの節の値。 */
    public static final class Params {
        private final Map<String, float[]> values = new LinkedHashMap<>();

        private Params() {
        }

        /** 数 1 つ。表に無い名前は書き間違いなので例外。 */
        public float f(String name) {
            float[] v = values.get(name);
            if (v == null) {
                throw new IllegalArgumentException("天気の見た目の値に無い名前: " + name);
            }
            return v[0];
        }

        /** 色(長さ 3)。 */
        public float[] rgb(String name) {
            float[] v = values.get(name);
            if (v == null || v.length < 3) {
                throw new IllegalArgumentException("天気の見た目の色に無い名前: " + name);
            }
            return v;
        }

        public Set<String> names() {
            return values.keySet();
        }

        public boolean has(String name) {
            return values.containsKey(name);
        }

        /** {@code name} だけ差し替えた写し(確かめ用)。 */
        public Params with(String name, float... v) {
            Params p = new Params();
            p.values.putAll(values);
            float[] old = values.get(name);
            if (old != null && v.length >= old.length) {
                p.values.put(name, v.clone());
            }
            return p;
        }

        public String describe() {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, float[]> e : values.entrySet()) {
                b.append(e.getKey()).append('=');
                float[] v = e.getValue();
                for (int i = 0; i < v.length; i++) {
                    b.append(i > 0 ? "," : "").append(v[i]);
                }
                b.append(' ');
            }
            return b.toString().trim();
        }
    }

    /** 表の 1 行: 名前・既定値・下限・上限(色は既定の 3 つで、0〜1)。 */
    private record Row(String name, double[] def, double min, double max) {
    }

    private static Row n(String name, double def, double min, double max) {
        return new Row(name, new double[]{def}, min, max);
    }

    private static Row c(String name, double r, double g, double b) {
        return new Row(name, new double[]{r, g, b}, 0.0, 1.0);
    }

    /** 節 stormExtras(雷雨の強化の残り)。 */
    private static final Row[] STORM = {
            // 地平線の遠い雷: どこかの遠い積乱雲が光る間隔の平均(秒)・相手にする距離(ブロック)・光の強さ・同時に光る数の上限
            n("farEvery", 2.5, 0.2, 600.0), n("farMin", 1500.0, 200.0, 8000.0), n("farMax", 7000.0, 500.0, 12000.0),
            n("farStrength", 1.3, 0.0, 4.0), n("farMaxAlive", 3.0, 0.0, 3.0),
            // 前触れ(preStorm)・雷雨の後(stormAfter)の間の出やすさ(雷雨の間 = 1)
            n("farPre", 0.7, 0.0, 1.0), n("farAfter", 0.5, 0.0, 1.0),
            // スプライト: 遠い雷 1 回あたりの出る割合(夜)・雲の天辺からの高さ・横の大きさ・光っている秒・明るさ・色(上と下)
            n("spriteChance", 0.08, 0.0, 1.0), n("spriteAbove", 900.0, 0.0, 4000.0), n("spriteSize", 1100.0, 50.0, 4000.0),
            n("spriteLife", 0.4, 0.05, 3.0), n("spriteAlpha", 0.7, 0.0, 1.0), n("spriteMinDistance", 1200.0, 0.0, 8000.0),
            c("spriteColor", 1.0, 0.16, 0.10), c("spriteTailColor", 0.55, 0.20, 0.75),
            // 雷雨の後の胞子の雲: 筋の数・濃さ・高さ(下と上)・届く距離・筋の長さと幅・色・夜の明るさ
            n("driftCount", 30.0, 0.0, 128.0), n("driftAlpha", 0.42, 0.0, 1.0), n("driftLow", 190.0, 64.0, 600.0),
            n("driftHigh", 285.0, 64.0, 800.0), n("driftRange", 1300.0, 200.0, 6000.0), n("driftLength", 380.0, 40.0, 2000.0),
            n("driftWidth", 110.0, 10.0, 800.0), c("driftColor", 0.66, 0.84, 0.26), n("driftNight", 0.16, 0.0, 1.0),
            // セントエルモの火: 出始める雷雨の強さ・木の先の数・探す半径・光の大きさ・明るさ・昼の明るさの倍率・色
            n("elmoFrom", 0.3, 0.0, 1.0), n("elmoCount", 14.0, 0.0, 64.0), n("elmoRange", 56.0, 8.0, 128.0),
            n("elmoSize", 0.16, 0.02, 2.0), n("elmoAlpha", 0.8, 0.0, 1.0), n("elmoDay", 0.6, 0.0, 1.0),
            n("elmoGiantRange", 420.0, 0.0, 2000.0), n("elmoGiantSize", 1.6, 0.1, 12.0), c("elmoColor", 0.58, 0.74, 1.0),
            // アーク雲と突風: 棚の雲の底の高さ・厚み・奥行き・横の長さ・来始める距離・頭上を越える preStorm・濃さ・色(昼)・突風を吹かせるか
            n("arcusBase", 125.0, 64.0, 400.0), n("arcusThick", 110.0, 8.0, 300.0), n("arcusDepth", 520.0, 40.0, 2000.0),
            n("arcusLength", 2600.0, 200.0, 12000.0), n("arcusFrom", 1500.0, 200.0, 8000.0), n("arcusOverhead", 0.85, 0.1, 1.0),
            n("arcusAlpha", 1.0, 0.0, 1.0), c("arcusColor", 0.17, 0.20, 0.26), c("arcusLitColor", 0.44, 0.50, 0.60),
            n("arcusGust", 1.0, 0.0, 1.0),
    };

    /** 節 calmLook(凪)。 */
    private static final Row[] CALM = {
            // 風: 突風を止める割合・凪の空気の動きの倍率
            n("gustStop", 1.0, 0.0, 1.0), n("breathScale", 0.2, 0.0, 1.0),
            // 霞: 遠くの霞(空気遠近)を薄める割合・森の中の霞を濃くする割合
            n("farClear", 0.6, 0.0, 1.0), n("forestThicken", 0.35, 0.0, 2.0),
            // 霞の層: 枚数(0〜3)・一番下の層の高さ(海面から)・層の間・層の厚み・濃さ(1 ブロックあたり)・うねりの高さ・見え始めと見えきる距離・色(森のそば・離れた所)・夜の明るさ
            n("layers", 3.0, 0.0, 3.0), n("layerBase", 7.0, -32.0, 128.0), n("layerGap", 13.0, 2.0, 64.0),
            n("layerThick", 3.0, 0.5, 24.0), n("layerDensity", 0.022, 0.0, 0.5), n("layerWave", 1.5, 0.0, 12.0),
            n("layerNear", 10.0, 0.0, 128.0), n("layerFar", 60.0, 1.0, 512.0), n("layerMax", 0.62, 0.0, 1.0),
            c("layerForestColor", 0.74, 0.80, 0.52), c("layerOpenColor", 0.84, 0.86, 0.84), n("layerNight", 0.12, 0.0, 1.0),
    };

    /** 節 galeLook(強風の日)。 */
    private static final Row[] GALE = {
            // 風: 突風の間隔の倍率・突風に足す強さ・突風の合間の風の強さ(0.22 以上で風の音が鳴り続ける)・葉の揺れに足す倍率
            n("gustInterval", 0.22, 0.05, 1.0), n("gustBonus", 0.25, 0.0, 1.0), n("between", 0.2, 0.0, 1.0),
            // 霞: 森の中の霞を薄める割合
            n("forestClear", 0.25, 0.0, 1.0),
            // 胞子の綿: 数・出す半径・速さ(ブロック/秒、風の強さ 1 のとき)・大きさ・濃さ・色・森の外でも出す割合
            n("fluffCount", 110.0, 0.0, 400.0), n("fluffRange", 26.0, 6.0, 64.0), n("fluffSpeed", 9.0, 0.5, 40.0),
            n("fluffSize", 0.12, 0.01, 0.6), n("fluffAlpha", 0.7, 0.0, 1.0), c("fluffColor", 0.86, 0.90, 0.66),
            n("fluffOpen", 0.5, 0.0, 1.0),
    };

    public static final Params STORM_DEFAULT = of(STORM, null);
    public static final Params CALM_DEFAULT = of(CALM, null);
    public static final Params GALE_DEFAULT = of(GALE, null);

    public static Params storm(Map<String, Object> json) {
        return of(STORM, json);
    }

    public static Params calm(Map<String, Object> json) {
        return of(CALM, json);
    }

    public static Params gale(Map<String, Object> json) {
        return of(GALE, json);
    }

    private static Params of(Row[] rows, Map<String, Object> json) {
        Params p = new Params();
        for (Row row : rows) {
            float[] out = new float[row.def.length];
            Object v = json != null ? json.get(row.name) : null;
            for (int i = 0; i < out.length; i++) {
                double x = row.def[i];
                if (out.length == 1 && v instanceof Number num && Double.isFinite(num.doubleValue())) {
                    x = num.doubleValue();
                } else if (out.length > 1 && v instanceof List<?> list && list.size() >= out.length && list.get(i) instanceof Number num
                        && Double.isFinite(num.doubleValue())) {
                    x = num.doubleValue();
                }
                out[i] = (float) Math.max(row.min, Math.min(row.max, x));
            }
            p.values.put(row.name, out);
        }
        return p;
    }
}
