package com.yayuy.overgrownvegetation.data;

/**
 * 「見え方の段」(2026-10-31)の設計値。{@code atmosphere.json} の節 wet・steam・ripples・gnats・creak の受け皿と値。
 * 数値はすべて仮決め(ユーザーが採用したのは項目と向きだけ)。読むのはクライアントの描画だけ。
 */
public final class AtmosphereLife {

    /** 節 {@code wet}(38 濡れた照り): 雨が止んでから乾くまでの分(10-31 ユーザー「3 分程度」)・照りの強さ・鋭さ・地面を暗くする割合・雨の最中の照りの倍率(空の明るさの照り)。
     * 10-31 雨上がりの足元が白い(ユーザー報告): 真上から見た面に残す照りの割合 faceOn・フレネルの冪 fresnelPower・近くで弱める距離 nearFrom〜nearTo。 */
    public static final class WetJson {
        public Double minutes, highlight, sharpness, groundDarken, rainHighlight, faceOn, fresnelPower, nearFrom, nearTo;
        /**
         * 11-06 バニラ地域の光 5(雨で地面が濡れる。値はすべて仮決め): 過繁茂の外は、真上が空の上向きの面だけが雨で濡れる。
         * rainWetDarken = 濡れた地面の明るさの倍率(0.85 = 15% 暗く)・rainWetSpecular = 照りの強さ・rainWetSaturate = 足す彩度・
         * rainWetIn = 降り始めから濡れきるまでの秒・rainWetOut = 止んでから乾ききるまでの秒・rainWetSkyFrom〜rainWetSkyTo = 濡れる面の空の光(15 段)。
         * 過繁茂の中は前の規則(minutes 分かけて乾く・空の光 4〜11 の全部の面)のまま。カメラの過繁茂の度合いで混ぜる。
         */
        public Double rainWetDarken, rainWetSpecular, rainWetSaturate, rainWetIn, rainWetOut, rainWetSkyFrom, rainWetSkyTo;
    }

    public record WetParams(float minutes, float highlight, float sharpness, float groundDarken, float rainHighlight, float faceOn,
                            float fresnelPower, float nearFrom, float nearTo,
                            float rainWetDarken, float rainWetSpecular, float rainWetSaturate, float rainWetIn, float rainWetOut,
                            float rainWetSkyFrom, float rainWetSkyTo) {
        static WetParams of(WetJson w) {
            WetJson v = w != null ? w : new WetJson();
            float skyFrom = c(v.rainWetSkyFrom, 14.6, 0.0, 15.0);
            return new WetParams(c(v.minutes, 3.0, 0.0, 60.0), c(v.highlight, 0.6, 0.0, 2.0), c(v.sharpness, 60.0, 1.0, 256.0), c(v.groundDarken, 0.22, 0.0, 0.8),
                    c(v.rainHighlight, 0.6, 0.0, 2.0), c(v.faceOn, 0.15, 0.0, 1.0), c(v.fresnelPower, 3.0, 0.5, 8.0),
                    c(v.nearFrom, 2.0, 0.0, 16.0), c(v.nearTo, 6.0, 0.1, 32.0),
                    c(v.rainWetDarken, 0.85, 0.2, 1.0), c(v.rainWetSpecular, 0.2, 0.0, 2.0), c(v.rainWetSaturate, 0.15, 0.0, 1.0),
                    c(v.rainWetIn, 20.0, 0.5, 600.0), c(v.rainWetOut, 60.0, 0.5, 1800.0), skyFrom,
                    Math.max(skyFrom + 0.1F, c(v.rainWetSkyTo, 15.0, 0.0, 15.5)));
        }
    }

    /**
     * 節 {@code waterGlint}(11-06 バニラ地域の光 4、水面の太陽と月。値はすべて仮決め): sunSpecular・moonSpecular = 太陽・月の照りの強さ
     * (月は月相で弱まる)・shininess = 鋭さ・ripple = 波の傾き(ゆっくりの波 4 つ)・rainRipple = 雨の強さ 1 で波の傾きに掛ける倍率・
     * rainDim = 雨の強さ 1 で照りに掛ける倍率(雲で日が隠れる)・moonColor = 月の照りの色。夕方の太陽の色は時刻の色(light.skyTint*)。
     */
    public static final class WaterGlintJson {
        public Double sunSpecular, moonSpecular, shininess, ripple, rainRipple, rainDim;
        public double[] moonColor;
    }

    /**
     * 節 {@code rainLook}(2026-11-06 雨の見え方)。ユーザー指定: 傾きは最も強い風で約 {@code windTilt} 30°・突風で揺れる・雪も流す・色は半透明の白 + その時刻の
     * 空の色を少し・水滴は全部白。ほかは仮決め: snowTiltScale・snowTiltMax = 雪の傾きの倍率と上限(度)・snowSway = 雪の柱のゆらぎ(ブロック)・
     * skyTintShare = 時刻の色を混ぜる割合・alpha = 雨と滴の不透明さの倍率・sporeTint = 胞子の空気 100% で黄緑へ寄せる割合・sporeColor = その色・
     * puddleRadius = 幹の根元の水たまりの半径(ブロック)・puddleDarken = 水たまりの暗さ・puddleSheen = 水たまりに映る空の明るさ・
     * puddleIn = 雨で広がりきるまでの秒・puddleOut = 雨上がりに乾ききるまでの秒。
     */
    public static final class RainLookJson {
        /** false = 雨・雪・水滴・水たまりをバニラのまま(撮り比べ用)。 */
        public Boolean enabled;
        public Double windTilt, snowTiltScale, snowTiltMax, snowSway, skyTintShare, alpha, sporeTint, puddleRadius, puddleDarken, puddleSheen,
                puddleIn, puddleOut, rainPuddleSpacing;
        /** 雨でできる水たまり(11-06 夜、仮決め): カメラのまわりの数の上限・1 tick に見る列の数。間隔は rainPuddleSpacing(ブロック)。 */
        public Integer rainPuddleMax, rainPuddleTries;
        /** 巨大樹の傘からの雨だれ(11-06 夜、仮決め): 雨上がりに落ち続ける秒・雨の間と雨上がりの 1 点あたり毎秒の滴・縁の点の上限・1 tick に見る列・
         *  縁とみなす傘の高さ(下面から地面まで)・輪の水たまりの上限と間隔。 */
        public Double dripSeconds, dripRainRate, dripAfterRate, dripMinHeight, dripPuddleSpacing;
        public Integer dripMaxPoints, dripTries, dripPuddleMax;
        public double[] sporeColor;
    }

    public record RainLookParams(boolean enabled, float windTilt, float snowTiltScale, float snowTiltMax, float snowSway, float skyTintShare, float alpha,
                                 float sporeTint, float[] sporeColor, float puddleRadius, float puddleDarken, float puddleSheen, float puddleIn,
                                 float puddleOut, int rainPuddleMax, int rainPuddleTries, float rainPuddleSpacing,
                                 float dripSeconds, float dripRainRate, float dripAfterRate, float dripMinHeight, float dripPuddleSpacing,
                                 int dripMaxPoints, int dripTries, int dripPuddleMax) {
        static RainLookParams of(RainLookJson w) {
            RainLookJson v = w != null ? w : new RainLookJson();
            double[] s = v.sporeColor != null && v.sporeColor.length >= 3 ? v.sporeColor : new double[]{0.80, 0.92, 0.55};
            return new RainLookParams(v.enabled == null || v.enabled, c(v.windTilt, 30.0, 0.0, 80.0), c(v.snowTiltScale, 1.5, 0.0, 4.0), c(v.snowTiltMax, 45.0, 0.0, 80.0),
                    c(v.snowSway, 0.3, 0.0, 2.0), c(v.skyTintShare, 0.5, 0.0, 1.0), c(v.alpha, 0.58, 0.0, 2.0), c(v.sporeTint, 0.25, 0.0, 1.0),
                    new float[]{c(s[0], 0.80, 0.0, 2.0), c(s[1], 0.92, 0.0, 2.0), c(s[2], 0.55, 0.0, 2.0)}, c(v.puddleRadius, 1.3, 0.1, 4.0),
                    c(v.puddleDarken, 0.6, 0.0, 1.0), c(v.puddleSheen, 0.5, 0.0, 1.0), c(v.puddleIn, 30.0, 0.5, 600.0), c(v.puddleOut, 60.0, 0.5, 1800.0),
                    v.rainPuddleMax != null ? Math.max(0, Math.min(200, v.rainPuddleMax)) : 20, v.rainPuddleTries != null ? Math.max(0, Math.min(64, v.rainPuddleTries)) : 6,
                    c(v.rainPuddleSpacing, 3.0, 0.5, 32.0),
                    c(v.dripSeconds, 150.0, 1.0, 1800.0), c(v.dripRainRate, 4.0, 0.0, 40.0), c(v.dripAfterRate, 1.5, 0.0, 40.0), c(v.dripMinHeight, 12.0, 2.0, 128.0),
                    c(v.dripPuddleSpacing, 2.0, 0.5, 16.0), v.dripMaxPoints != null ? Math.max(0, Math.min(400, v.dripMaxPoints)) : 60,
                    v.dripTries != null ? Math.max(0, Math.min(128, v.dripTries)) : 12, v.dripPuddleMax != null ? Math.max(0, Math.min(200, v.dripPuddleMax)) : 30);
        }
    }

    public record WaterGlintParams(float sunSpecular, float moonSpecular, float shininess, float ripple, float rainRipple, float rainDim,
                                   float[] moonColor) {
        static WaterGlintParams of(WaterGlintJson w) {
            WaterGlintJson v = w != null ? w : new WaterGlintJson();
            double[] m = v.moonColor != null && v.moonColor.length >= 3 ? v.moonColor : new double[]{0.75, 0.82, 1.0};
            return new WaterGlintParams(c(v.sunSpecular, 1.0, 0.0, 4.0), c(v.moonSpecular, 0.35, 0.0, 4.0), c(v.shininess, 300.0, 1.0, 2048.0),
                    c(v.ripple, 0.15, 0.0, 1.0), c(v.rainRipple, 2.5, 1.0, 10.0), c(v.rainDim, 0.3, 0.0, 1.0),
                    new float[]{c(m[0], 0.75, 0.0, 2.0), c(m[1], 0.82, 0.0, 2.0), c(m[2], 1.0, 0.0, 2.0)});
        }
    }

    /**
     * 節 {@code steam}(37 雨上がりの蒸気): 雨が上がってから立つ分・粒の数(重さの段 高・中・低)・カメラのまわりの半径・森の度合いの窓
     * (林冠の穴の下 = 中くらいの所)・立ちのぼる速さ(ブロック/秒)・高さ・粒の大きさ・濃さ。
     */
    public static final class SteamJson {
        public Double minutes, radius, presenceFrom, presenceTo, riseSpeed, height, size, alpha;
        public int[] counts;
    }

    public record SteamParams(float minutes, int[] counts, float radius, float presenceFrom, float presenceTo, float riseSpeed,
                              float height, float size, float alpha) {
        static SteamParams of(SteamJson s) {
            SteamJson v = s != null ? s : new SteamJson();
            float from = c(v.presenceFrom, 0.2, 0.0, 1.0);
            return new SteamParams(c(v.minutes, 3.0, 0.5, 60.0), tiers(v.counts, new int[]{20, 12, 5}, 256), c(v.radius, 14.0, 4.0, 32.0),
                    from, Math.max(from + 0.05F, c(v.presenceTo, 1.0, 0.0, 1.0)), c(v.riseSpeed, 0.35, 0.02, 3.0),
                    c(v.height, 2.6, 0.5, 10.0), c(v.size, 0.8, 0.05, 3.0), c(v.alpha, 0.14, 0.0, 1.0));
        }
    }

    /**
     * 節 {@code ripples}(39 水面の輪と泡): どこにいつ出すか。探す半径・何も落ちないときの 1 分あたりの大きな波紋の数・泡の数・
     * 雨の強さ 1 のときの 1 秒あたりの雨の波紋の数とそれを探す半径(雨の波紋は近くだけ)。形は節 dropRipple・rainRipple({@link Ripples}。10-31 作り直し)。
     * 葉が水面に浮いて流れる(40)の秒と速さもここ。
     */
    public static final class RipplesJson {
        public Double radius, ringsPerMinute, bubblesPerMinute, floatSecondsMin, floatSecondsMax, floatSpeed, rainRingsPerSecond, rainRadius;
    }

    public record RipplesParams(float radius, float ringsPerMinute, float bubblesPerMinute, float floatSecondsMin, float floatSecondsMax,
                                float floatSpeed, float rainRingsPerSecond, float rainRadius) {
        static RipplesParams of(RipplesJson r) {
            RipplesJson v = r != null ? r : new RipplesJson();
            float fMin = c(v.floatSecondsMin, 10.0, 0.0, 120.0);
            return new RipplesParams(c(v.radius, 16.0, 4.0, 48.0), c(v.ringsPerMinute, 0.5, 0.0, 120.0), c(v.bubblesPerMinute, 2.0, 0.0, 120.0), fMin,
                    Math.max(fMin, c(v.floatSecondsMax, 20.0, 0.0, 180.0)), c(v.floatSpeed, 0.35, 0.0, 3.0),
                    c(v.rainRingsPerSecond, 160.0, 0.0, 400.0), c(v.rainRadius, 8.0, 2.0, 24.0));
        }
    }

    /**
     * 節 {@code dropRipple}(大きな滴の波紋。葉から落ちた滴・水の生き物の泡・何も落ちないときの気配): 同心の帯の数・いちばん外の帯の広がる速さ
     * (ブロック/秒)・消えるまでの秒・明るい山の濃さ・暗い谷の濃さ・帯の太さ(広がった半径に掛ける)・大きさの倍率。
     */
    public static final class DropRippleJson {
        public Integer rings;
        public Double speed, seconds, alpha, darkAlpha, widthGrow, scale;
    }

    public record DropRippleParams(int rings, float speed, float seconds, float alpha, float darkAlpha, float widthGrow, float scale) {
        static DropRippleParams of(DropRippleJson d) {
            DropRippleJson v = d != null ? d : new DropRippleJson();
            return new DropRippleParams(v.rings != null ? Math.max(1, Math.min(6, v.rings)) : 3, c(v.speed, 0.45, 0.05, 3.0),
                    c(v.seconds, 1.8, 0.3, 10.0), c(v.alpha, 0.6, 0.0, 1.0), c(v.darkAlpha, 0.4, 0.0, 1.0), c(v.widthGrow, 0.18, 0.0, 1.0),
                    c(v.scale, 1.0, 0.1, 5.0));
        }
    }

    /**
     * 節 {@code rainRipple}(雨の波紋。小さな滴が大量に降るとき。水たまりにも使い回す部品): 直径の下と上(ブロック)・寿命の秒の下と上・
     * 山の濃さ・谷の濃さ・真ん中の跳ねの点の濃さ。
     */
    public static final class RainRippleJson {
        public Double sizeMin, sizeMax, secondsMin, secondsMax, alpha, darkAlpha, splash;
    }

    public record RainRippleParams(float sizeMin, float sizeMax, float secondsMin, float secondsMax, float alpha, float darkAlpha, float splash) {
        static RainRippleParams of(RainRippleJson r) {
            RainRippleJson v = r != null ? r : new RainRippleJson();
            float sMin = c(v.sizeMin, 0.2, 0.02, 2.0);
            float tMin = c(v.secondsMin, 0.3, 0.05, 5.0);
            return new RainRippleParams(sMin, Math.max(sMin, c(v.sizeMax, 0.4, 0.02, 2.0)), tMin, Math.max(tMin, c(v.secondsMax, 0.5, 0.05, 5.0)),
                    c(v.alpha, 0.4, 0.0, 1.0), c(v.darkAlpha, 0.35, 0.0, 1.0), c(v.splash, 0.6, 0.0, 1.0));
        }
    }

    /** 節 {@code gnats}(42 昼の羽虫の柱): 時刻・柱の数・1 本の虫の数(重さの段 高・中・低)・探す半径・柱の高さと太さ・点の大きさ・動きの速さの倍率(10-31 ユーザー「遅く」)・揺れの大きさの倍率。 */
    public static final class GnatsJson {
        public Double fromHour, toHour, radius, height, width, size, speed, wobble;
        public Integer columns;
        public int[] counts;
    }

    public record GnatsParams(float fromHour, float toHour, int columns, int[] counts, float radius, float height, float width, float size,
                              float speed, float wobble) {
        static GnatsParams of(GnatsJson g) {
            GnatsJson v = g != null ? g : new GnatsJson();
            return new GnatsParams(c(v.fromHour, 9.0, 0.0, 24.0), c(v.toHour, 16.0, 0.0, 24.0), v.columns != null ? Math.max(0, Math.min(8, v.columns)) : 3,
                    tiers(v.counts, new int[]{36, 24, 12}, 128), c(v.radius, 20.0, 4.0, 48.0), c(v.height, 2.2, 0.5, 8.0),
                    c(v.width, 0.6, 0.1, 3.0), c(v.size, 0.025, 0.005, 0.2), c(v.speed, 0.4, 0.05, 2.0), c(v.wobble, 0.5, 0.0, 2.0));
        }
    }

    /**
     * 節 {@code algae}(18 夜の光る藻、10-31 ユーザー決定「水中の弱い粒で作る」): 夜だけ、水面の下に弱い光の粒がまばらに漂う。
     * 粒の数(重さの段 高・中・低)・カメラのまわりの半径・水面からの深さ・粒の大きさ・濃さ・漂う速さ(ブロック/秒)・1 粒の寿命(秒)・色。
     */
    public static final class AlgaeJson {
        public Double radius, depthMin, depthMax, size, alpha, drift, lifeMin, lifeMax;
        public int[] counts;
        public double[] color;
    }

    public record AlgaeParams(int[] counts, float radius, float depthMin, float depthMax, float size, float alpha, float drift, float lifeMin,
                              float lifeMax, float r, float g, float b) {
        static AlgaeParams of(AlgaeJson a) {
            AlgaeJson v = a != null ? a : new AlgaeJson();
            float dMin = c(v.depthMin, 0.15, 0.0, 4.0);
            float lMin = c(v.lifeMin, 20.0, 1.0, 300.0);
            double[] col = v.color != null && v.color.length >= 3 ? v.color : new double[]{0.675, 0.89, 0.0};
            return new AlgaeParams(tiers(v.counts, new int[]{40, 24, 10}, 256), c(v.radius, 16.0, 4.0, 48.0), dMin,
                    Math.max(dMin, c(v.depthMax, 1.0, 0.0, 6.0)), c(v.size, 0.07, 0.01, 0.5), c(v.alpha, 0.35, 0.0, 1.0),
                    c(v.drift, 0.03, 0.0, 1.0), lMin, Math.max(lMin, c(v.lifeMax, 40.0, 1.0, 600.0)), c(col[0], 0.675, 0.0, 1.0),
                    c(col[1], 0.89, 0.0, 1.0), c(col[2], 0.0, 0.0, 1.0));
        }
    }

    /** 節 {@code creak}(44 遠くの軋みに続く落下): 間隔(秒)・距離・音から落下までの秒・大きさ・落ちる葉と小枝の数。 */
    public static final class CreakJson {
        public Boolean enabled;
        public Double intervalMin, intervalMax, distanceMin, distanceMax, delay, volume;
        public Integer pieces;
    }

    public record CreakParams(boolean enabled, float intervalMin, float intervalMax, float distanceMin, float distanceMax, float delay,
                              float volume, int pieces) {
        static CreakParams of(CreakJson k) {
            CreakJson v = k != null ? k : new CreakJson();
            float iMin = c(v.intervalMin, 600.0, 10.0, 7200.0);
            float dMin = c(v.distanceMin, 24.0, 4.0, 96.0);
            return new CreakParams(v.enabled != null && v.enabled, iMin, Math.max(iMin, c(v.intervalMax, 1200.0, 10.0, 7200.0)), dMin,
                    Math.max(dMin, c(v.distanceMax, 48.0, 4.0, 128.0)), c(v.delay, 3.0, 0.0, 20.0), c(v.volume, 0.5, 0.0, 2.0),
                    v.pieces != null ? Math.max(0, Math.min(200, v.pieces)) : 40);
        }
    }

    static float c(Double v, double def, double min, double max) {
        double x = v != null && Double.isFinite(v) ? v : def;
        return (float) Math.max(min, Math.min(max, x));
    }

    static int[] tiers(int[] v, int[] def, int max) {
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            out[i] = Math.max(0, Math.min(max, v != null && v.length > i ? v[i] : def[i]));
        }
        return out;
    }

    private AtmosphereLife() {
    }
}
