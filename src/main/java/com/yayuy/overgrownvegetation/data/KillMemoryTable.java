package com.yayuy.overgrownvegetation.data;

import java.util.List;

/**
 * 司令塔の「学ぶ」の設計値({@code director.json} の {@code kills}、2026-11-14 第 3 段 区切り 1)。
 *
 * <p>ユーザーが決めたのは: 半減期 1 日・プレイヤーに倒されたものだけ数える・「危ない所」は 10〜13 体倒されたら(11-14)/
 * 遠距離の根付き(スケルトン・エンダーマン)は危ない所から離れて根付き、ほかは危ない所でもある程度は根付く(11-14)。<b>ほかの数は【仮】</b>。
 */
public final class KillMemoryTable {

    public static final KillMemoryTable DEFAULT = new KillMemoryTable(null);

    public static final class Json {
        public Double halfLifeDays;
        public Integer dangerMinKills;
        public Integer dangerMaxKills;
        public Integer mergeChunks;
        public Double dangerBlocks;
        public Double evolveTowardDanger;
        public Integer dangerDeveloped;
        public Double dangerIntervalMinDays;
        public Double dangerIntervalMaxDays;
        public Double dangerSearchBlocks;
        public Double dangerFromMinBlocks;
        public Integer baseDangerExtraDeveloped;
        public Double rootedDangerFactor;
        public Double rangedKeepAwayBlocks;
        public List<String> rangedRooted;
    }

    /** 倒された数の半減期(日)。ユーザー決定 1。 */
    public final double halfLifeDays;
    /** 「危ない所」のしきい値の幅(まわり 3 × 3 升の数)。ユーザー決定 10〜13。升ごとに座標からこの幅の 1 つに決まる。 */
    public final int dangerMinKills;
    public final int dangerMaxKills;
    /** これだけの升(チャンク)の中の危ない所は 1 つにまとめる。 */
    public final int mergeChunks;
    /** 危ない所の近く(この距離で 0)では、段階 0 が進化しやすい: 進化の点に最大 {@code evolveTowardDanger} を足す。 */
    public final double dangerBlocks;
    public final double evolveTowardDanger;
    /** 危ない所へ 1 回に送る進化体の数と、送る間(日)。 */
    public final int dangerDeveloped;
    public final double dangerIntervalMinDays;
    public final double dangerIntervalMaxDays;
    /** 送る個体を探す半径と、近すぎて選ばない半径(ブロック。危ない所の真ん中から)。 */
    public final double dangerSearchBlocks;
    public final double dangerFromMinBlocks;
    /** 認識した拠点の中に危ない所があれば、送り込みの進化体の枠をこれだけ増やす。 */
    public final int baseDangerExtraDeveloped;
    /** 遠距離でない根付き: 危ない所の真ん中では根付きの点をこの割合だけ下げる(dangerBlocks で 0)。「ある程度は根付く」。 */
    public final double rootedDangerFactor;
    /** 遠距離の根付きは、危ない所の真ん中からこれより内には根付かない(離れる向きへ歩く)。 */
    public final double rangedKeepAwayBlocks;
    /** 遠距離の根付きの種(G1Species のキー)。ユーザーが挙げたのはスケルトン・エンダーマン。羊は【仮】。 */
    public final List<String> rangedRooted;

    private KillMemoryTable(Json j) {
        Json s = j != null ? j : new Json();
        this.halfLifeDays = Math.max(0.01D, nn(s.halfLifeDays, 1.0D));
        this.dangerMinKills = Math.max(1, s.dangerMinKills != null ? s.dangerMinKills : 10);
        this.dangerMaxKills = Math.max(this.dangerMinKills, s.dangerMaxKills != null ? s.dangerMaxKills : 13);
        this.mergeChunks = Math.max(0, Math.min(8, s.mergeChunks != null ? s.mergeChunks : 3));
        this.dangerBlocks = Math.max(1.0D, nn(s.dangerBlocks, 64.0D));
        this.evolveTowardDanger = nn(s.evolveTowardDanger, 0.4D);
        this.dangerDeveloped = Math.max(0, Math.min(16, s.dangerDeveloped != null ? s.dangerDeveloped : 2));
        this.dangerIntervalMinDays = Math.max(0.01D, nn(s.dangerIntervalMinDays, 0.25D));
        this.dangerIntervalMaxDays = Math.max(this.dangerIntervalMinDays, nn(s.dangerIntervalMaxDays, 0.5D));
        this.dangerSearchBlocks = Math.max(16.0D, nn(s.dangerSearchBlocks, 128.0D));
        this.dangerFromMinBlocks = Math.min(this.dangerSearchBlocks, nn(s.dangerFromMinBlocks, 16.0D));
        this.baseDangerExtraDeveloped = Math.max(0, s.baseDangerExtraDeveloped != null ? s.baseDangerExtraDeveloped : 1);
        this.rootedDangerFactor = Math.min(1.0D, nn(s.rootedDangerFactor, 0.5D));
        this.rangedKeepAwayBlocks = nn(s.rangedKeepAwayBlocks, 48.0D);
        this.rangedRooted = s.rangedRooted != null ? List.copyOf(s.rangedRooted) : List.of("skeleton", "enderman", "sheep");
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static KillMemoryTable fromJson(Json j) {
        return j == null ? DEFAULT : new KillMemoryTable(j);
    }

    public boolean isRanged(String speciesKey) {
        return rangedRooted.contains(speciesKey);
    }
}
