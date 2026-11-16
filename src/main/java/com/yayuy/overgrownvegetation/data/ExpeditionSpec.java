package com.yayuy.overgrownvegetation.data;

/**
 * 遠征の見返りと手当ての設計値({@code spore_field.json} の {@code expedition}、2026-11-16 サバイバルの背骨 段 4)。
 * 仕様の正本は Vault「サバイバルの背骨(籠城と遠征)」の決定 38〜41。読むのは {@code survival/expedition/} と {@code survival/vent/AirFilters}。
 *
 * <h2>ユーザー指定(11-16 夜)</h2>
 * <ul>
 *     <li>濃い所だけの素材は、採取(過繁茂の森にだけ生える植物・菌)と G4・G5 のドロップの両方(決定 39)。</li>
 *     <li>寝袋: どこでも横になれ、時刻は飛ばさず、少しずつ疲労が戻る(昼の休みと同じ)。帰りの地点は変えない(決定 40)。
 *     8 ブロック以内に敵がいると横になれない(11-16 夜、案 a)。</li>
 *     <li>上位のフィルター(菌糸のフィルター)は容量 3 倍だけ(11-16 夜、案 a)。</li>
 *     <li>薬を飲むと疲労が +10% たまる(11-16 夜。数は【仮】)。</li>
 * </ul>
 *
 * <h2>仮決め(数はすべて【仮】)</h2>
 * 胞子喰い茸: 200 tick ごとにプレイヤー 1 人のまわり 32 の中で 4 回列を選び、過繁茂のバイオーム・地面の上の空気・空の光 13 以下(葉の下)・
 * まわり 6 に 3 本未満なら 1 本置く。硬い樹皮片: G5 を倒すと 30%(ドロップ増加 1 段で +5%)。
 */
public record ExpeditionSpec(
        double superiorFilterCapacity,
        int mushroomSpreadIntervalTicks,
        int mushroomSpreadRadius,
        int mushroomSpreadTries,
        int mushroomCrowdRadius,
        int mushroomCrowdMax,
        int mushroomMaxSkyLight,
        double barkDropChance,
        double barkLootingBonus,
        double cureDropChance,
        double cureLootingBonus,
        double cureFatigue,
        boolean sleepingBagNeedsSafety,
        double sleepingBagMonsterRadius) {

    /** Gson の受け皿(null は既定値)。 */
    public static final class Json {
        public String _comment;
        /** 菌糸のフィルターの容量(ふつうのフィルターの何倍か)。 */
        public Double superiorFilterCapacity;
        /** 胞子喰い茸が増えるのを試す間隔(tick)。 */
        public Integer mushroomSpreadIntervalTicks;
        /** プレイヤーのまわりの何ブロックの中で試すか。 */
        public Integer mushroomSpreadRadius;
        /** 1 回に試す列の数(0 で増えない)。 */
        public Integer mushroomSpreadTries;
        /** 混み具合を数える半径。 */
        public Integer mushroomCrowdRadius;
        /** 混み具合の上限(半径の中にこの本数あれば置かない)。 */
        public Integer mushroomCrowdMax;
        /** 生える所の空の光の上限(15 = 空の下でも生える。13 = 葉の下くらい)。 */
        public Integer mushroomMaxSkyLight;
        /** G5 を倒したときに硬い樹皮片を落とす確率。 */
        public Double barkDropChance;
        /** ドロップ増加 1 段ごとに足す確率。 */
        public Double barkLootingBonus;
        /** G4 を倒したときに G4 の素材を落とす確率(素材は 11-16 夜に答え待ち)。 */
        public Double cureDropChance;
        /** ドロップ増加 1 段ごとに足す確率。 */
        public Double cureLootingBonus;
        /** 薬を 1 本飲むとたまる疲労(%)。 */
        public Double cureFatigue;
        /** 寝袋: まわりに敵がいると横になれないか。 */
        public Boolean sleepingBagNeedsSafety;
        /** 寝袋: 敵を数える横の半径(上下は 5)。 */
        public Double sleepingBagMonsterRadius;
    }

    public static final ExpeditionSpec DEFAULT = from(null);

    public static ExpeditionSpec from(Json j) {
        Json s = j != null ? j : new Json();
        return new ExpeditionSpec(
                d(s.superiorFilterCapacity, 3.0D, 0.1D, 100.0D),
                i(s.mushroomSpreadIntervalTicks, 200, 1, 1_000_000),
                i(s.mushroomSpreadRadius, 32, 1, 128),
                i(s.mushroomSpreadTries, 4, 0, 1000),
                i(s.mushroomCrowdRadius, 6, 0, 32),
                i(s.mushroomCrowdMax, 3, 1, 1000),
                i(s.mushroomMaxSkyLight, 13, 0, 15),
                d(s.barkDropChance, 0.3D, 0.0D, 1.0D),
                d(s.barkLootingBonus, 0.05D, 0.0D, 1.0D),
                d(s.cureDropChance, 0.25D, 0.0D, 1.0D),
                d(s.cureLootingBonus, 0.05D, 0.0D, 1.0D),
                d(s.cureFatigue, 10.0D, 0.0D, 100.0D),
                s.sleepingBagNeedsSafety == null || s.sleepingBagNeedsSafety,
                d(s.sleepingBagMonsterRadius, 8.0D, 0.0D, 64.0D));
    }

    private static int i(Integer v, int def, int lo, int hi) {
        return v == null ? def : Math.max(lo, Math.min(hi, v));
    }

    private static double d(Double v, double def, double lo, double hi) {
        return v == null || !Double.isFinite(v) ? def : Math.max(lo, Math.min(hi, v));
    }
}
