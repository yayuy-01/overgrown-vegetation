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
    }

    public record WetParams(float minutes, float highlight, float sharpness, float groundDarken, float rainHighlight, float faceOn,
                            float fresnelPower, float nearFrom, float nearTo) {
        static WetParams of(WetJson w) {
            WetJson v = w != null ? w : new WetJson();
            return new WetParams(c(v.minutes, 3.0, 0.0, 60.0), c(v.highlight, 0.6, 0.0, 2.0), c(v.sharpness, 60.0, 1.0, 256.0), c(v.groundDarken, 0.22, 0.0, 0.8),
                    c(v.rainHighlight, 0.6, 0.0, 2.0), c(v.faceOn, 0.15, 0.0, 1.0), c(v.fresnelPower, 3.0, 0.5, 8.0),
                    c(v.nearFrom, 2.0, 0.0, 16.0), c(v.nearTo, 6.0, 0.1, 32.0));
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
