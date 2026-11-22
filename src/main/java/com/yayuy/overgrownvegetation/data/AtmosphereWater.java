package com.yayuy.overgrownvegetation.data;

/**
 * 「水中の光」(2026-10-31 夜)の設計値。{@code atmosphere.json} の節 caustics・underwaterShafts の受け皿と値。
 * 数値はすべて仮決め(ユーザーが決めたのは「網目は上から見ても潜っても出す」「水中の筋は地上の流用でなく水中用に作る」だけ)。読むのはクライアントの描画だけ。
 */
public final class AtmosphereWater {

    /**
     * 節 {@code caustics}(水底の網目の光): 強さ・上から見たときの倍率・網目の細かさ(1 ブロックあたりの網の目の数、0.25 刻みに丸める)・
     * 線の太さ・揺らぎの速さ(600 秒あたりの回数、整数に丸める)・深さの減り(e 分の 1 になる深さ)・消える深さ・林冠の地図が無いとき葉の下の強さ・色。
     */
    public static final class CausticsJson {
        public Double strength, fromAbove, scale, width, speed, falloff, maxDepth, underLeaves;
        public double[] color;
    }

    public record CausticsParams(float strength, float fromAbove, float scale, float width, int cycles, float falloff, float maxDepth,
                                 float underLeaves, float r, float g, float b) {
        static CausticsParams of(CausticsJson c) {
            CausticsJson v = c != null ? c : new CausticsJson();
            double[] col = v.color != null && v.color.length >= 3 ? v.color : new double[]{1.0, 0.97, 0.82};
            float scale = Math.max(0.25F, Math.round(AtmosphereLife.c(v.scale, 1.5, 0.25, 6.0) * 4.0F) / 4.0F);
            return new CausticsParams(AtmosphereLife.c(v.strength, 1.0, 0.0, 3.0), AtmosphereLife.c(v.fromAbove, 1.0, 0.0, 2.0), scale,
                    AtmosphereLife.c(v.width, 0.08, 0.01, 0.5), Math.max(1, Math.round(AtmosphereLife.c(v.speed, 40.0, 1.0, 400.0))),
                    AtmosphereLife.c(v.falloff, 2.5, 0.1, 16.0), AtmosphereLife.c(v.maxDepth, 5.0, 0.5, 24.0),
                    AtmosphereLife.c(v.underLeaves, 0.3, 0.0, 1.0), AtmosphereLife.c(col[0], 1.0, 0.0, 2.0),
                    AtmosphereLife.c(col[1], 0.97, 0.0, 2.0), AtmosphereLife.c(col[2], 0.82, 0.0, 2.0));
        }
    }

    /**
     * 節 {@code underwaterShafts}(潜ったときの光の筋): 本数(重さの段 高・中・低)・カメラのまわりの半径・長さ・幅・濃さ・1 本の寿命(秒)・
     * 横の揺れ(ブロック)・色・水中の霧の色へ寄せる割合。
     * 過繁茂の海・川・森の池の濁った水の中(11-01 夕方 ユーザー「水面からの光の筋がはっきり」)では本数 × murkyCount・濃さ × murkyAlpha・
     * 霧で薄れる距離 × murkyFogReach(霧が 14〜24 ブロックと短くても筋が見えるように)。
     */
    public static final class ShaftsJson {
        public Double radius, length, widthMin, widthMax, alpha, lifeMin, lifeMax, sway, fogMix, murkyCount, murkyAlpha, murkyFogReach;
        public int[] counts;
        public double[] color;
    }

    public record ShaftsParams(int[] counts, float radius, float length, float widthMin, float widthMax, float alpha, float lifeMin,
                               float lifeMax, float sway, float r, float g, float b, float fogMix, float murkyCount, float murkyAlpha,
                               float murkyFogReach) {
        static ShaftsParams of(ShaftsJson s) {
            ShaftsJson v = s != null ? s : new ShaftsJson();
            double[] col = v.color != null && v.color.length >= 3 ? v.color : new double[]{0.9, 1.0, 0.85};
            float wMin = AtmosphereLife.c(v.widthMin, 0.2, 0.02, 4.0);
            float lMin = AtmosphereLife.c(v.lifeMin, 5.0, 0.5, 120.0);
            return new ShaftsParams(AtmosphereLife.tiers(v.counts, new int[]{28, 18, 8}, 128), AtmosphereLife.c(v.radius, 12.0, 2.0, 48.0),
                    AtmosphereLife.c(v.length, 9.0, 1.0, 48.0), wMin, Math.max(wMin, AtmosphereLife.c(v.widthMax, 0.8, 0.02, 6.0)),
                    AtmosphereLife.c(v.alpha, 0.22, 0.0, 1.0), lMin, Math.max(lMin, AtmosphereLife.c(v.lifeMax, 10.0, 0.5, 240.0)),
                    AtmosphereLife.c(v.sway, 0.15, 0.0, 2.0), AtmosphereLife.c(col[0], 0.9, 0.0, 2.0), AtmosphereLife.c(col[1], 1.0, 0.0, 2.0),
                    AtmosphereLife.c(col[2], 0.85, 0.0, 2.0), AtmosphereLife.c(v.fogMix, 0.5, 0.0, 1.0),
                    AtmosphereLife.c(v.murkyCount, 1.5, 0.0, 4.0), AtmosphereLife.c(v.murkyAlpha, 1.3, 0.0, 4.0),
                    AtmosphereLife.c(v.murkyFogReach, 1.8, 1.0, 8.0));
        }
    }

    private AtmosphereWater() {
    }
}
