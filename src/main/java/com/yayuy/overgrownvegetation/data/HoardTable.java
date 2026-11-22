package com.yayuy.overgrownvegetation.data;

/**
 * 司令塔の「貯めて一気に使う」の設計値({@code director.json} の {@code hoard}、2026-11-14 第 3 段 区切り 3)。
 *
 * <p>ユーザーが決めたのは: 貯める間は襲わず遠巻きにうろつく(H1)・新しい演出は足さない(H2)・定着と最盛だけで起こし導入では起こさない(11-14)。
 * 襲撃は ① 送り込み ② 貯めた襲撃(これ)③ 大きな襲撃(いちばん強い。同じ仕組み)の 3 段(11-14 ユーザー)。<b>数は全部【仮】</b>。
 */
public final class HoardTable {

    public static final HoardTable DEFAULT = new HoardTable(null);

    public static final class Json {
        public Double chancePerDay;
        public Double minBudgetFraction;
        public Double minDays;
        public Double maxDays;
        public Double capFactor;
        public Double cooldownDays;
        public Integer burstMax;
        public Double burstSearchBlocks;
        public Double holdMinBlocks;
        public Double holdMaxBlocks;
    }

    /** 1 日に貯め始める確率(予算が上限の minBudgetFraction 以上のとき)。 */
    public final double chancePerDay;
    public final double minBudgetFraction;
    /** 貯める長さ(日)。 */
    public final double minDays;
    public final double maxDays;
    /** 貯める間だけ、予算の上限をこの倍に。 */
    public final double capFactor;
    /** 使い終えてから、次に貯め始めるまで(日)。 */
    public final double cooldownDays;
    /** 使うとき一斉に進化させる上限の数と、段階 0 を探す半径(プレイヤー・認識した拠点から)。 */
    public final int burstMax;
    public final double burstSearchBlocks;
    /** 貯める間の G1 が保つ、プレイヤーからの距離(ブロック)。 */
    public final double holdMinBlocks;
    public final double holdMaxBlocks;

    private HoardTable(Json j) {
        Json s = j != null ? j : new Json();
        this.chancePerDay = Math.min(1.0D, nn(s.chancePerDay, 0.2D));
        this.minBudgetFraction = Math.min(1.0D, nn(s.minBudgetFraction, 0.5D));
        this.minDays = Math.max(0.01D, nn(s.minDays, 1.5D));
        this.maxDays = Math.max(this.minDays, nn(s.maxDays, 3.0D));
        this.capFactor = Math.max(1.0D, nn(s.capFactor, 2.0D));
        this.cooldownDays = nn(s.cooldownDays, 1.0D);
        this.burstMax = Math.max(0, Math.min(32, s.burstMax != null ? s.burstMax : 8));
        this.burstSearchBlocks = Math.max(16.0D, nn(s.burstSearchBlocks, 128.0D));
        this.holdMinBlocks = nn(s.holdMinBlocks, 24.0D);
        this.holdMaxBlocks = Math.max(this.holdMinBlocks + 4.0D, nn(s.holdMaxBlocks, 40.0D));
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static HoardTable fromJson(Json j) {
        return j == null ? DEFAULT : new HoardTable(j);
    }
}
