package com.yayuy.overgrownvegetation.data;

/**
 * 「地下モード」(2026-11-01 試作。仕様は Vault の「地下モード(洞窟のシェーダーと光)」)の設計値。{@code atmosphere.json} の節 cave の受け皿と値。
 * ユーザー指定: 黒い霧で見通し 12〜16 ブロック・色は #050604 に近い黒・点光源は近い順に最大 24。ほかは仮決め。読むのはクライアントの描画だけ。
 */
public final class AtmosphereCave {

    /**
     * 節 {@code cave}: 霧の始まり・終わり(ブロック)・霧の色・点光源の上限(24 まで)・点光源の明るさの倍率(面の色に足す量)・
     * Mob の明るさの倍率(ライトマップへ直す量)・光源を集める半径・地下モードに入る空の光(この明るさから入り始め、sky 以下で全部)・
     * 光源の光る画素とみなす明るさ(霧で暗くしない)。
     * 11-01 夜(ユーザー「真っ暗すぎる」): 霧の既定を 20〜28・#0c100a に(仮決め)、ambient = 地下モードの間だけ面に足す最低の明るさ(ライトマップの床の持ち上げ)・
     * clipKnee = 点光源で明るくなった面の色の最大がこれを超えたら 1.0 を超えないよう柔らかく抑える(色相は保つ)。どちらも仮決め。
     */
    public static final class CaveJson {
        public Double fogStart, fogEnd, lightGain, entityGain, radius, skyFrom, skyTo, glowThreshold, ambient, clipKnee;
        /**
         * 11-01 夜 見え方の直し 3(ユーザー「概ねその通り」、値は仮決め): lightWhiten = 点光源の色を白へ寄せる割合(飽和した面が白っぽい緑になる)・
         * ambientColor = 床の持ち上げの色(灰緑)・ambientDesaturate = 持ち上げに使う絵の色の彩度を落とす割合・clipDesaturate = clipKnee を超えた分だけ
         * 彩度を落とす強さ(明るい所ほど白っぽく)。
         * 見え方の直し 4: roofFrom・roofTo = 断片の上の岩の厚み(ブロック)がこの間で地下 0 → 1(地下の地図 { CaveMap})。
         */
        public Double lightWhiten, ambientDesaturate, clipDesaturate, roofFrom, roofTo;
        /**
         * 11-04 地下の光の直し 4 件(値はすべて仮決め): falloff = 点光源の明るさが半分になる距離を半径の何倍にするか(1 = 前の減り方。小さいほど光源の近くと遠くの差が出る)・
         * mouthFrom・mouthTo = カメラが外にいるとき、洞窟の中の断片を口からの水平距離(ブロック)がこの間で黒へ沈める・
         * mouthLightSink = その沈みを点光源の光と光る画素にはこの割合だけ掛ける(1 未満なら光源のまわりが暗がりに残る)。
         */
        public Double falloff, mouthFrom, mouthTo, mouthLightSink;
        /**
         * 11-06 光の点検 10(値はすべて仮決め): waterDepthDarken = 洞窟の水の下の面(湖の底・水に沈んだ岸の壁)を水深でこの割合まで暗くする(0 = しない)・
         * waterDepthFold = その暗さが 1 − 1/e になる水深(ブロック)・waterSpecular = 洞窟の水面に近い点光源(先頭 2 個)を映す鏡面の強さ(0 = しない)・
         * waterShininess = 鏡面の鋭さ(Blinn の指数。大きいほど小さく鋭い光の点)。
         */
        public Double waterDepthDarken, waterDepthFold, waterSpecular, waterShininess;
        /**
         * 11-06 光源 6・7(値はすべて仮決め): surfaceLights = 地上の夜に渡す点光源の上限(近い順。地下は maxLights)・
         * surfaceTint = 地上の夜に面の色と向きを光源へ寄せる強さ(0 = しない。バニラの光量はそのまま、ブロックの光の分だけ寄せる)・
         * flameFlicker = 炎のゆらぎの強さ(光源の強さに 1 ± これを掛ける。松明・焚き火・溶岩・火・ジャック・オ・ランタン・ろうそく。0 = しない)。
         */
        public Double surfaceTint, flameFlicker;
        public Integer maxLights, surfaceLights;
        public double[] fogColor, ambientColor;
    }

    public record CaveParams(float fogStart, float fogEnd, float r, float g, float b, int maxLights, float lightGain, float entityGain,
                             float radius, float skyFrom, float skyTo, float glowThreshold, float ambient, float clipKnee, float lightWhiten,
                             float[] ambientColor, float ambientDesaturate, float clipDesaturate, float roofFrom, float roofTo,
                             float falloff, float mouthFrom, float mouthTo, float mouthLightSink,
                             float waterDepthDarken, float waterDepthFold, float waterSpecular, float waterShininess,
                             int surfaceLights, float surfaceTint, float flameFlicker) {
        static CaveParams of(CaveJson c) {
            CaveJson v = c != null ? c : new CaveJson();
            double[] col = v.fogColor != null && v.fogColor.length >= 3 ? v.fogColor : new double[]{0.047, 0.063, 0.039};
            float start = AtmosphereLife.c(v.fogStart, 20.0, 0.0, 256.0);
            float skyTo = AtmosphereLife.c(v.skyTo, 4.0, 0.0, 15.0);
            float roofFrom = AtmosphereLife.c(v.roofFrom, 0.5, 0.0, 32.0);
            float mouthFrom = AtmosphereLife.c(v.mouthFrom, 2.0, 0.0, 31.0);
            return new CaveParams(start, Math.max(start + 0.5F, AtmosphereLife.c(v.fogEnd, 28.0, 1.0, 256.0)),
                    AtmosphereLife.c(col[0], 0.047, 0.0, 1.0), AtmosphereLife.c(col[1], 0.063, 0.0, 1.0), AtmosphereLife.c(col[2], 0.039, 0.0, 1.0),
                    v.maxLights != null ? Math.max(0, Math.min(24, v.maxLights)) : 24, AtmosphereLife.c(v.lightGain, 1.0, 0.0, 8.0),
                    AtmosphereLife.c(v.entityGain, 1.0, 0.0, 8.0), AtmosphereLife.c(v.radius, 48.0, 8.0, 96.0),
                    Math.max(skyTo + 0.5F, AtmosphereLife.c(v.skyFrom, 12.0, 0.0, 15.5)), skyTo,
                    AtmosphereLife.c(v.glowThreshold, 0.35, 0.0, 1.0), AtmosphereLife.c(v.ambient, 0.12, 0.0, 0.5),
                    AtmosphereLife.c(v.clipKnee, 0.7, 0.1, 1.0), AtmosphereLife.c(v.lightWhiten, 0.25, 0.0, 1.0),
                    color(v.ambientColor, 0.82, 0.95, 0.80), AtmosphereLife.c(v.ambientDesaturate, 0.6, 0.0, 1.0),
                    AtmosphereLife.c(v.clipDesaturate, 0.5, 0.0, 1.0), roofFrom, Math.max(roofFrom + 0.25F, AtmosphereLife.c(v.roofTo, 3.0, 0.0, 32.0)),
                    AtmosphereLife.c(v.falloff, 0.65, 0.05, 1.0), mouthFrom, Math.max(mouthFrom + 0.5F, AtmosphereLife.c(v.mouthTo, 18.0, 1.0, 32.0)),
                    AtmosphereLife.c(v.mouthLightSink, 0.6, 0.0, 1.0),
                    AtmosphereLife.c(v.waterDepthDarken, 0.6, 0.0, 1.0), AtmosphereLife.c(v.waterDepthFold, 2.5, 0.1, 32.0),
                    AtmosphereLife.c(v.waterSpecular, 0.35, 0.0, 4.0), AtmosphereLife.c(v.waterShininess, 128.0, 1.0, 512.0),
                    v.surfaceLights != null ? Math.max(0, Math.min(24, v.surfaceLights)) : 12,
                    AtmosphereLife.c(v.surfaceTint, 1.0, 0.0, 1.0), AtmosphereLife.c(v.flameFlicker, 0.15, 0.0, 0.5));
        }
    }

    private static float[] color(double[] c, double r, double g, double b) {
        return c != null && c.length >= 3
                ? new float[]{AtmosphereLife.c(c[0], r, 0.0, 2.0), AtmosphereLife.c(c[1], g, 0.0, 2.0), AtmosphereLife.c(c[2], b, 0.0, 2.0)}
                : new float[]{(float) r, (float) g, (float) b};
    }

    private AtmosphereCave() {
    }
}
