package com.yayuy.overgrownvegetation.data;

import static com.yayuy.overgrownvegetation.data.AtmosphereLife.c;
import static com.yayuy.overgrownvegetation.data.AtmosphereLife.tiers;

/**
 * 洞窟の小ネタ(2026-11-07 ユーザー採用)の設計値。{@code atmosphere.json} の節 {@code caveLife}
 * (glowworms = 天井の土ボタル・crickets = 洞窟の口のカマドウマ)の受け皿。
 * <b>ユーザー指定は「項目と向き」だけ</b>(設定: 洞窟の生き物も虫と爬虫類だけ。鳥は消え、コウモリは胞子に寄生されて死んだ = 11-07)。
 * 数値はすべて仮決め(11-07)。読むのはクライアントの描画だけ。
 */
public final class AtmosphereCaveLife {

    public static final class Json {
        public GlowJson glowworms;
        public CricketJson crickets;
    }

    /**
     * 土ボタル(<b>11-08 ユーザー: 保留</b> = 将来ネザーのバイオームで使う候補。洞窟には出さないので既定は切る。コードは残す): 出すか・探す半径(ブロック)・目の高さから上下に探す段・暗い所とみなす空の光の上限(15 段)・1 つの天井のマスに出る確率
     * (普通の洞窟 / 過繁茂の洞窟)・湿った所(近くに水)の倍率・点の数の上限(重さの段 高・中・低)・糸の本数の上限・糸の長さ(ブロック)・
     * 光の色・点の濃さ・明滅の周期(秒)・光を持って近づくと暗くなる距離・暗くなりきる秒・戻る秒・暗くなったときの濃さ。
     */
    public static final class GlowJson {
        public Boolean enabled;
        public Double radius, below, above, maxSky, chance, chanceOvergrown, wetBoost, threadLength, alpha, blinkSeconds, shyDistance, shyIn, shyOut, shyLevel;
        public Integer threads;
        public int[] counts;
        public double[] color;
    }

    public record GlowParams(boolean enabled, float radius, int below, int above, int maxSky, float chance, float chanceOvergrown, float wetBoost,
                             int[] counts, int threads, float threadLength, float r, float g, float b, float alpha, float blinkSeconds,
                             float shyDistance, float shyIn, float shyOut, float shyLevel) {
        static GlowParams of(GlowJson j) {
            GlowJson v = j != null ? j : new GlowJson();
            double[] col = v.color != null && v.color.length >= 3 ? v.color : new double[]{0.45, 0.95, 0.9};
            return new GlowParams(v.enabled != null && v.enabled, c(v.radius, 24.0, 6.0, 48.0), (int) c(v.below, 12.0, 0.0, 48.0),
                    (int) c(v.above, 24.0, 2.0, 64.0), (int) c(v.maxSky, 2.0, 0.0, 15.0), c(v.chance, 0.06, 0.0, 1.0),
                    c(v.chanceOvergrown, 0.18, 0.0, 1.0), c(v.wetBoost, 2.0, 1.0, 10.0), tiers(v.counts, new int[]{600, 350, 120}, 4000),
                    v.threads != null ? Math.max(0, Math.min(6, v.threads)) : 3, c(v.threadLength, 0.5, 0.05, 2.0), c(col[0], 0.45, 0.0, 1.0),
                    c(col[1], 0.95, 0.0, 1.0), c(col[2], 0.9, 0.0, 1.0), c(v.alpha, 1.0, 0.0, 2.0), c(v.blinkSeconds, 9.0, 1.0, 60.0),
                    c(v.shyDistance, 6.0, 0.0, 24.0), c(v.shyIn, 3.0, 0.1, 30.0), c(v.shyOut, 12.0, 0.1, 120.0), c(v.shyLevel, 0.15, 0.0, 1.0));
        }
    }

    /**
     * カマドウマ: 出すか・カメラのまわりの数の上限(重さの段 高・中・低)・探す半径・洞窟の口とみなす空の光の範囲(15 段)・1 か所に固まる数・
     * 体の長さ(触角と脚を除く、ブロック)・跳ねて逃げる距離・跳ねる長さ(秒)・跳ぶ距離(ブロック)・跳ねる回数の上限・逃げた後に出直す秒・
     * 過繁茂で緑へ寄せる割合。
     */
    public static final class CricketJson {
        public Boolean enabled;
        public Double radius, skyMin, skyMax, length, fleeDistance, hopSeconds, hopDistance, respawnSeconds, overgrownTint;
        public Integer group, hops;
        public int[] counts;
    }

    public record CricketParams(boolean enabled, int[] counts, float radius, int skyMin, int skyMax, int group, float length, float fleeDistance,
                                float hopSeconds, float hopDistance, int hops, float respawnSeconds, float overgrownTint) {
        static CricketParams of(CricketJson j) {
            CricketJson v = j != null ? j : new CricketJson();
            int sMin = (int) c(v.skyMin, 1.0, 0.0, 15.0);
            return new CricketParams(v.enabled == null || v.enabled, tiers(v.counts, new int[]{8, 5, 2}, 32), c(v.radius, 20.0, 6.0, 48.0), sMin,
                    Math.max(sMin, (int) c(v.skyMax, 10.0, 0.0, 15.0)), v.group != null ? Math.max(1, Math.min(8, v.group)) : 3,
                    c(v.length, 0.4, 0.1, 1.5), c(v.fleeDistance, 5.0, 1.0, 16.0), c(v.hopSeconds, 0.4, 0.1, 2.0), c(v.hopDistance, 2.5, 0.5, 8.0),
                    v.hops != null ? Math.max(1, Math.min(8, v.hops)) : 3, c(v.respawnSeconds, 20.0, 1.0, 600.0), c(v.overgrownTint, 0.35, 0.0, 1.0));
        }
    }

    public record Params(GlowParams glowworms, CricketParams crickets) {
        public static Params of(Json j) {
            return new Params(GlowParams.of(j != null ? j.glowworms : null), CricketParams.of(j != null ? j.crickets : null));
        }
    }

    private AtmosphereCaveLife() {
    }
}
