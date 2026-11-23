package com.yayuy.overgrownvegetation.data;

/**
 * 司令塔の「大きな襲撃」の設計値({@code director.json} の {@code largeRaid}、2026-11-14 第 4 段)。
 * 司令塔の襲撃の大・中・小 = 送り込み(小)・貯めた襲撃(中)・大きな襲撃(大)の「大」。
 *
 * <p>ユーザーが決めたのは(11-14): F7 の定着か最盛で起こる・前触れは約 2 日(植物側の湧きが止まる。偵察は続ける・すでにいる個体はそのまま)・
 * 勝ちも負けも無い「大量の Mob に襲われるイベント」・ボスバーや遠景の群れは無し・「溜めたバイオマスの分だけ際限なく常に湧かせる」・
 * 前触れの間の予算の上限 4 倍(【仮】の 4)・昔の前触れの演出は戻さない。<b>ほかの数は【仮】</b>。
 * 本当の超大規模の襲撃(1 万の桁)は F7 より後の別のイベントで、ここの範囲の外。
 * <p>2026-11-23 片付け E: 本番で湧かせる値(間・数・顔ぶれ・向きと試す数)と、前の「1 日 2 割で起こす」値(chancePerDay・omenMin/MaxDays)は消した。
 */
public final class LargeRaidTable {

    public static final LargeRaidTable DEFAULT = new LargeRaidTable(null);

    public static final class Json {
        public Double cooldownDays;
        public Double capFactor;
        public Integer maxAlive;
        public Double costStageZero;
        public Double spawnMinBlocks;
        public Double spawnMaxBlocks;
        public Double countRadius;
    }

    /** 終わってから次まで(日)。 */
    public final double cooldownDays;
    /** 前触れ・本番の間の予算の上限の倍率(ユーザー: 4【仮】)。 */
    public final double capFactor;
    /** 同時にいる攻め手の上限(重さのための安全弁。上限に当たっている間も、倒された分はすぐ補う)。 */
    public final int maxAlive;
    /** 段階 0 の 1 体の生産の値段(前線の部隊・苗床・維持費が使う)。 */
    public final double costStageZero;
    /** 拠点の真ん中からの輪の幅(流れ木もどきの道が島とみなすかを見る輪。前は大きな襲撃が湧かせる所)。 */
    public final double spawnMinBlocks;
    public final double spawnMaxBlocks;
    /** 攻め手を数える半径(拠点の真ん中から)。 */
    public final double countRadius;

    private LargeRaidTable(Json j) {
        Json s = j != null ? j : new Json();
        this.cooldownDays = nn(s.cooldownDays, 3.0D);
        this.capFactor = Math.max(1.0D, nn(s.capFactor, 4.0D));
        this.maxAlive = Math.max(1, Math.min(2000, s.maxAlive != null ? s.maxAlive : 150));
        this.costStageZero = Math.max(0.01D, nn(s.costStageZero, 100.0D));
        this.spawnMinBlocks = Math.max(8.0D, nn(s.spawnMinBlocks, 48.0D));
        this.spawnMaxBlocks = Math.max(this.spawnMinBlocks, nn(s.spawnMaxBlocks, 96.0D));
        this.countRadius = Math.max(16.0D, nn(s.countRadius, 192.0D));
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static LargeRaidTable fromJson(Json j) {
        return j == null ? DEFAULT : new LargeRaidTable(j);
    }
}
