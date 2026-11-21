package com.yayuy.overgrownvegetation.data;

/**
 * 司令塔の「大きな襲撃」の設計値({@code director.json} の {@code largeRaid}、2026-11-14 第 4 段)。
 * 司令塔の襲撃の大・中・小 = 送り込み(小)・貯めた襲撃(中)・大きな襲撃(大)の「大」。
 *
 * <p>ユーザーが決めたのは(11-14): F7 の定着か最盛で起こる・前触れは約 2 日(植物側の湧きが止まる。偵察は続ける・すでにいる個体はそのまま)・
 * 勝ちも負けも無い「大量の Mob に襲われるイベント」・ボスバーや遠景の群れは無し・「溜めたバイオマスの分だけ際限なく常に湧かせる」・
 * 前触れの間の予算の上限 4 倍(【仮】の 4)・昔の前触れの演出は戻さない。<b>ほかの数は【仮】</b>。
 * 本当の超大規模の襲撃(1 万の桁)は F7 より後の別のイベントで、ここの範囲の外。
 */
public final class LargeRaidTable {

    public static final LargeRaidTable DEFAULT = new LargeRaidTable(null);

    public static final class Json {
        public Double chancePerDay;
        public Double cooldownDays;
        public Double omenMinDays;
        public Double omenMaxDays;
        public Double capFactor;
        public Integer maxAlive;
        public Integer spawnIntervalTicks;
        public Integer spawnPerStep;
        public Double developedShare;
        public Double plantShare;
        public Double costStageZero;
        public Double spawnMinBlocks;
        public Double spawnMaxBlocks;
        public Double spawnSpreadDeg;
        public Double playerMinBlocks;
        public Integer spawnTries;
        public Double countRadius;
    }

    /** 1 日に起こる割合(条件がそろったとき)と、終わってから次まで(日)。 */
    public final double chancePerDay;
    public final double cooldownDays;
    /** 前触れの長さ(日)。ユーザー「約 2 日」= 1.8〜2.2。 */
    public final double omenMinDays;
    public final double omenMaxDays;
    /** 前触れ・本番の間の予算の上限の倍率(ユーザー: 4【仮】)。 */
    public final double capFactor;
    /** 同時にいる攻め手の上限(重さのための安全弁。上限に当たっている間も、倒された分はすぐ補う)。 */
    public final int maxAlive;
    /** 湧かせる間(tick)と、1 回に湧かせる数の上限。予算が残っている限り、ずっと続ける(波で休まない)。 */
    public final int spawnIntervalTicks;
    public final int spawnPerStep;
    /** 湧かせる攻め手のうち 1 段目の割合(値段は進化の値段)。段階 0 の値段は costStageZero。 */
    public final double developedShare;
    /** 湧かせる攻め手のうち G2〜G4 の割合(11-14 夕: 5%)。G2 以降が退避中のうちは送る口が無いので、その分は段階 0 を湧かせる。 */
    public final double plantShare;
    public final double costStageZero;
    /** 湧かせる所: 拠点の真ん中から森の方角・弱い方角へこの幅。向きの振れ(度)。プレイヤーからこれより遠く、見通せない所。試す数。 */
    public final double spawnMinBlocks;
    public final double spawnMaxBlocks;
    public final double spawnSpreadDeg;
    public final double playerMinBlocks;
    public final int spawnTries;
    /** 攻め手を数える半径(拠点の真ん中から)。 */
    public final double countRadius;

    private LargeRaidTable(Json j) {
        Json s = j != null ? j : new Json();
        this.chancePerDay = Math.min(1.0D, nn(s.chancePerDay, 0.2D));
        this.cooldownDays = nn(s.cooldownDays, 3.0D);
        this.omenMinDays = Math.max(0.01D, nn(s.omenMinDays, 1.8D));
        this.omenMaxDays = Math.max(this.omenMinDays, nn(s.omenMaxDays, 2.2D));
        this.capFactor = Math.max(1.0D, nn(s.capFactor, 4.0D));
        this.maxAlive = Math.max(1, Math.min(2000, s.maxAlive != null ? s.maxAlive : 150));
        this.spawnIntervalTicks = Math.max(1, s.spawnIntervalTicks != null ? s.spawnIntervalTicks : 10);
        this.spawnPerStep = Math.max(1, Math.min(64, s.spawnPerStep != null ? s.spawnPerStep : 3));
        this.developedShare = Math.min(1.0D, nn(s.developedShare, 0.1D));
        this.plantShare = Math.min(1.0D - this.developedShare, nn(s.plantShare, 0.05D));
        this.costStageZero = Math.max(0.01D, nn(s.costStageZero, 100.0D));
        this.spawnMinBlocks = Math.max(8.0D, nn(s.spawnMinBlocks, 48.0D));
        this.spawnMaxBlocks = Math.max(this.spawnMinBlocks, nn(s.spawnMaxBlocks, 96.0D));
        this.spawnSpreadDeg = Math.min(180.0D, nn(s.spawnSpreadDeg, 45.0D));
        this.playerMinBlocks = nn(s.playerMinBlocks, 32.0D);
        this.spawnTries = Math.max(1, Math.min(32, s.spawnTries != null ? s.spawnTries : 8));
        this.countRadius = Math.max(16.0D, nn(s.countRadius, 192.0D));
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static LargeRaidTable fromJson(Json j) {
        return j == null ? DEFAULT : new LargeRaidTable(j);
    }
}
