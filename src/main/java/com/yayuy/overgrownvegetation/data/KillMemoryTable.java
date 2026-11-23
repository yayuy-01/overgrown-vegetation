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
    }

    /** 倒された数の半減期(日)。ユーザー決定 1。 */
    public final double halfLifeDays;
    /** 「危ない所」のしきい値の幅(まわり 3 × 3 升の数)。ユーザー決定 10〜13。升ごとに座標からこの幅の 1 つに決まる。 */
    public final int dangerMinKills;
    public final int dangerMaxKills;
    /** これだけの升(チャンク)の中の危ない所は 1 つにまとめる。 */
    public final int mergeChunks;
    /** 危ない所の広さ(ブロック)。 */
    public final double dangerBlocks;

    private KillMemoryTable(Json j) {
        Json s = j != null ? j : new Json();
        this.halfLifeDays = Math.max(0.01D, nn(s.halfLifeDays, 1.0D));
        this.dangerMinKills = Math.max(1, s.dangerMinKills != null ? s.dangerMinKills : 10);
        this.dangerMaxKills = Math.max(this.dangerMinKills, s.dangerMaxKills != null ? s.dangerMaxKills : 13);
        this.mergeChunks = Math.max(0, Math.min(8, s.mergeChunks != null ? s.mergeChunks : 3));
        this.dangerBlocks = Math.max(1.0D, nn(s.dangerBlocks, 64.0D));
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static KillMemoryTable fromJson(Json j) {
        return j == null ? DEFAULT : new KillMemoryTable(j);
    }
}
