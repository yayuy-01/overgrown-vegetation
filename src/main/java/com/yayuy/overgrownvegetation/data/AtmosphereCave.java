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
        public Integer maxLights;
        public double[] fogColor, ambientColor;
    }

    public record CaveParams(float fogStart, float fogEnd, float r, float g, float b, int maxLights, float lightGain, float entityGain,
                             float radius, float skyFrom, float skyTo, float glowThreshold, float ambient, float clipKnee, float lightWhiten,
                             float[] ambientColor, float ambientDesaturate, float clipDesaturate, float roofFrom, float roofTo) {
        static CaveParams of(CaveJson c) {
            CaveJson v = c != null ? c : new CaveJson();
            double[] col = v.fogColor != null && v.fogColor.length >= 3 ? v.fogColor : new double[]{0.047, 0.063, 0.039};
            float start = AtmosphereLife.c(v.fogStart, 20.0, 0.0, 256.0);
            float skyTo = AtmosphereLife.c(v.skyTo, 4.0, 0.0, 15.0);
            float roofFrom = AtmosphereLife.c(v.roofFrom, 0.5, 0.0, 32.0);
            return new CaveParams(start, Math.max(start + 0.5F, AtmosphereLife.c(v.fogEnd, 28.0, 1.0, 256.0)),
                    AtmosphereLife.c(col[0], 0.047, 0.0, 1.0), AtmosphereLife.c(col[1], 0.063, 0.0, 1.0), AtmosphereLife.c(col[2], 0.039, 0.0, 1.0),
                    v.maxLights != null ? Math.max(0, Math.min(24, v.maxLights)) : 24, AtmosphereLife.c(v.lightGain, 1.0, 0.0, 8.0),
                    AtmosphereLife.c(v.entityGain, 1.0, 0.0, 8.0), AtmosphereLife.c(v.radius, 48.0, 8.0, 96.0),
                    Math.max(skyTo + 0.5F, AtmosphereLife.c(v.skyFrom, 12.0, 0.0, 15.5)), skyTo,
                    AtmosphereLife.c(v.glowThreshold, 0.35, 0.0, 1.0), AtmosphereLife.c(v.ambient, 0.06, 0.0, 0.5),
                    AtmosphereLife.c(v.clipKnee, 0.7, 0.1, 1.0), AtmosphereLife.c(v.lightWhiten, 0.25, 0.0, 1.0),
                    color(v.ambientColor, 0.82, 0.95, 0.80), AtmosphereLife.c(v.ambientDesaturate, 0.6, 0.0, 1.0),
                    AtmosphereLife.c(v.clipDesaturate, 0.5, 0.0, 1.0), roofFrom, Math.max(roofFrom + 0.25F, AtmosphereLife.c(v.roofTo, 3.0, 0.0, 32.0)));
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
