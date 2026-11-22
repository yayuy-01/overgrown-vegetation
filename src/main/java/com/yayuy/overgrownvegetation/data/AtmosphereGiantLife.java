package com.yayuy.overgrownvegetation.data;

import static com.yayuy.overgrownvegetation.data.AtmosphereLife.c;
import static com.yayuy.overgrownvegetation.data.AtmosphereLife.tiers;

/**
 * 巨大樹のまわりの生き物と音(2026-11-06 ユーザー採用の改善案 ⑤)の設計値。{@code atmosphere.json} の節 {@code giantLife}
 * (gnats = 傘のまわりを回る羽虫の群れ・lizards = 幹に張り付く爬虫類・creak = 突風で幹が軋む音)の受け皿。
 * <b>ユーザー指定は「項目と向き」だけ</b>(羽虫の群れ・幹のトカゲ・突風の軋み、鳥は使わない)。数値はすべて仮決め(11-06)。
 * 読むのはクライアントの描画と音だけ。
 */
public final class AtmosphereGiantLife {

    public static final class Json {
        public GnatsJson gnats;
        public LizardsJson lizards;
        public CreakJson creak;
    }

    /**
     * 羽虫の群れ: 出る時刻(時)・夜の光る虫の濃さ nightGlow(0 = 夜は出さない。案)・1 本の木の群れの数・1 群れの虫の数(重さの段 高・中・低)・
     * 群れの広がりの半径と高さ(ブロック)・虫の見かけの大きさ(距離 1 ブロックあたりの半幅。遠くても同じ画素数に見える)・近くでの最小の半幅・
     * 回る速さの倍率・描く距離の上限(ブロック)。
     */
    public static final class GnatsJson {
        public Double fromHour, toHour, nightGlow, spread, depth, angular, minHalf, speed, maxDistance;
        public Integer swarms;
        public int[] counts;
    }

    public record GnatsParams(float fromHour, float toHour, float nightGlow, int swarms, int[] counts, float spread, float depth, float angular,
                              float minHalf, float speed, float maxDistance) {
        static GnatsParams of(GnatsJson g) {
            GnatsJson v = g != null ? g : new GnatsJson();
            return new GnatsParams(c(v.fromHour, 7.0, 0.0, 24.0), c(v.toHour, 19.0, 0.0, 24.0), c(v.nightGlow, 0.6, 0.0, 1.0),
                    v.swarms != null ? Math.max(0, Math.min(8, v.swarms)) : 5, tiers(v.counts, new int[]{100, 65, 28}, 256),
                    c(v.spread, 4.0, 0.5, 16.0), c(v.depth, 3.0, 0.5, 16.0), c(v.angular, 0.0022, 0.0002, 0.01), c(v.minHalf, 0.03, 0.005, 0.3),
                    c(v.speed, 0.25, 0.02, 2.0), c(v.maxDistance, 260.0, 32.0, 512.0));
        }
    }

    /**
     * 幹のトカゲ: 出すか・木 1 本あたりの数・出す距離(幹の軸からの水平距離、ブロック)・逃げ出す距離・登る速さ(ブロック/秒)・逃げる長さ(秒)・
     * 目の高さからの上下の幅・体の長さ(尾まで、ブロック)・逃げた後に別の所へ出るまでの秒。
     */
    public static final class LizardsJson {
        public Boolean enabled;
        public Integer perTree;
        public Double range, fleeDistance, climbSpeed, fleeSeconds, heightBelow, heightAbove, length, respawnSeconds;
    }

    public record LizardsParams(boolean enabled, int perTree, float range, float fleeDistance, float climbSpeed, float fleeSeconds,
                                float heightBelow, float heightAbove, float length, float respawnSeconds) {
        static LizardsParams of(LizardsJson l) {
            LizardsJson v = l != null ? l : new LizardsJson();
            return new LizardsParams(v.enabled == null || v.enabled, v.perTree != null ? Math.max(0, Math.min(8, v.perTree)) : 3,
                    c(v.range, 64.0, 8.0, 128.0), c(v.fleeDistance, 6.0, 1.0, 24.0), c(v.climbSpeed, 5.0, 0.5, 20.0), c(v.fleeSeconds, 2.5, 0.3, 10.0),
                    c(v.heightBelow, 1.0, 0.0, 16.0), c(v.heightAbove, 9.0, 0.0, 32.0), c(v.length, 1.2, 0.15, 2.0), c(v.respawnSeconds, 20.0, 1.0, 600.0));
        }
    }

    /**
     * 突風の軋み: 鳴らすか(<b>音はユーザーが見本を聞いて了承するまで false</b>。10-31 の軋み 44 が「動物の鳴き声に聞こえる」と作り直しになった続き)・
     * 鳴り出す突風の強さ(幹の所の {@code GustField} の強さ)・同じ木が次に鳴るまでの秒・聞こえる距離(線形に 0 になる、幹からの水平距離)・大きさ。
     */
    public static final class CreakJson {
        public Boolean enabled;
        public Double threshold, cooldown, range, volume;
    }

    public record CreakParams(boolean enabled, float threshold, float cooldown, int range, float volume) {
        static CreakParams of(CreakJson k) {
            CreakJson v = k != null ? k : new CreakJson();
            return new CreakParams(v.enabled != null && v.enabled, c(v.threshold, 0.55, 0.05, 2.0), c(v.cooldown, 14.0, 1.0, 600.0),
                    Math.round(c(v.range, 96.0, 16.0, 256.0)), c(v.volume, 0.8, 0.0, 2.0));
        }
    }

    public record Params(GnatsParams gnats, LizardsParams lizards, CreakParams creak) {
        public static Params of(Json j) {
            return new Params(GnatsParams.of(j != null ? j.gnats : null), LizardsParams.of(j != null ? j.lizards : null),
                    CreakParams.of(j != null ? j.creak : null));
        }
    }

    private AtmosphereGiantLife() {
    }
}
