package com.yayuy.overgrownvegetation.data;

/**
 * 棘の生垣(森の防衛設備。{@code director.json} の {@code hedge}、2026-11-20)。ユーザーが決めたのは: 防衛設備はいったん棘の生垣だけ・
 * 器官の周りなどに置く・司令塔がバイオマスで置き、壊されたらまたバイオマスで置き直す・予算なしで自分でも少しずつ伸びる・密集させない・
 * 有刺鉄線のような物(植物側でない者が触れると傷と鈍り)・植物側の Mob は傷つかない・押し返す道具の効く所には置かない・伸びない。
 * <b>数はどれも【仮】</b>(植物側の Mob が通れるかも【仮】: 通れる = 森の側の道)。
 */
public final class HedgeTable {

    public static final HedgeTable DEFAULT = new HedgeTable(null);

    public static final class Json {
        public Boolean enabled;
        public Double cost;
        public Integer intervalSeconds, placePerCheck;
        public Double playerBlocks;
        public Double radiusBig, radiusSmall;
        public Integer segmentsBig, segmentsSmall, segmentLength;
        public Double entranceGapDeg;
        public Double damage, slow;
        public Integer damageIntervalTicks;
        public Double growChance;
        public Integer growNearRadius, growMaxNear;
        public Double growOrganPad;
        public Boolean plantSidePasses;
    }

    public final boolean enabled;
    /** 1 マス置く(置き直す)値段。 */
    public final double cost;
    /** 器官ごとに見直す間(秒。プレイヤーが {@code playerBlocks} 以内にいるときだけ)・1 回に置く数の上限。 */
    public final int intervalSeconds, placePerCheck;
    public final double playerBlocks;
    /** 器官の真ん中からの輪の半径(大きい版・小さい版)。 */
    public final double radiusBig, radiusSmall;
    /** 輪に置く切れ目のある列の数(大きい版・小さい版)と、1 列の長さ。列と列の間は空ける(密集させない・通り道)。 */
    public final int segmentsBig, segmentsSmall, segmentLength;
    /** 器官の入口の向きの ± この角度には列を置かない(芯を壊しに行く道を塞ぎ切らない)。 */
    public final double entranceGapDeg;
    /** 触れた者への傷(damageIntervalTicks ごと)と鈍り(横の速さの倍率)。 */
    public final double damage, slow;
    public final int damageIntervalTicks;
    /** 自分で伸びる: ランダム tick 1 回あたりの確率・まわり growNearRadius に growMaxNear 本以上あれば伸びない・器官の輪から growOrganPad より外へは伸びない。 */
    public final double growChance;
    public final int growNearRadius, growMaxNear;
    public final double growOrganPad;
    /** 植物側の Mob は鈍らずに通り抜ける(森の側の道)。false なら鈍る(傷は受けない)。 */
    public final boolean plantSidePasses;

    private HedgeTable(Json j) {
        Json s = j != null ? j : new Json();
        enabled = s.enabled == null || s.enabled;
        cost = nn(s.cost, 2.0D);
        intervalSeconds = Math.max(1, s.intervalSeconds != null ? s.intervalSeconds : 60);
        placePerCheck = Math.max(1, s.placePerCheck != null ? s.placePerCheck : 4);
        playerBlocks = nn(s.playerBlocks, 96.0D);
        radiusBig = Math.max(2.0D, nn(s.radiusBig, 20.0D));
        radiusSmall = Math.max(2.0D, nn(s.radiusSmall, 7.0D));
        segmentsBig = Math.max(1, s.segmentsBig != null ? s.segmentsBig : 8);
        segmentsSmall = Math.max(1, s.segmentsSmall != null ? s.segmentsSmall : 4);
        segmentLength = Math.max(1, s.segmentLength != null ? s.segmentLength : 3);
        entranceGapDeg = Math.min(180.0D, nn(s.entranceGapDeg, 30.0D));
        damage = nn(s.damage, 1.0D);
        slow = Math.min(1.0D, nn(s.slow, 0.5D));
        damageIntervalTicks = Math.max(1, s.damageIntervalTicks != null ? s.damageIntervalTicks : 10);
        growChance = Math.min(1.0D, nn(s.growChance, 0.05D));
        growNearRadius = Math.max(1, s.growNearRadius != null ? s.growNearRadius : 2);
        growMaxNear = Math.max(1, s.growMaxNear != null ? s.growMaxNear : 5);
        growOrganPad = nn(s.growOrganPad, 4.0D);
        plantSidePasses = s.plantSidePasses == null || s.plantSidePasses;
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static HedgeTable fromJson(Json j) {
        return j == null ? DEFAULT : new HedgeTable(j);
    }
}
