package com.yayuy.overgrownvegetation.data;

/**
 * 同盟軍の道の数値(順 2 の着手順 7、2026-10-08)。{@code defense.json} の {@code alliedBase.road}。
 *
 * <p><b>ユーザー指定(10-08)</b>: 道はアスファルト幅 3・柱の上の妨害灯を 24 ブロックごとに左右交互(半径 12 なので道の上に切れ目ができない)/
 * 各基地から近い順に 2 本まで / 工事は離れていても進む。<b>ほかの数値は Claude 仮決め</b>。
 */
public final class BaseRoadConfig {

    /** {@code alliedBase.road} の json。書いていない項目は既定値。 */
    public static final class Json {
        /** アスファルトの幅(ユーザー指定 3)。 */
        public Integer width;
        /** 妨害灯の間隔(道の真ん中の線に沿ったブロック数。左右交互。ユーザー指定 24)。 */
        public Integer lampSpacing;
        /** 各基地から引く道の数(近い順。ユーザー指定 2)。 */
        public Integer maxLinks;
        /** 道を引く相手までの直線の距離の上限(ブロック)。 */
        public Integer maxDistance;
        /** 1 つの基地が 1 日に進める長さ(道の真ん中の線のブロック数。両方の基地から進む)。 */
        public Double blocksPerDay;
        /** 1 ブロック進むごとの貯蓄(アスファルト幅ぶん + 妨害灯の割り当て)。 */
        public Double costPerBlock;
        /** 貯蓄が上限のこの割合以上ある間だけ工事が進む(補充と整備を食いつぶさない)。 */
        public Double minStockFraction;
        /** 坂: 高さ 1 を上るのに要る長さ(4 = 4 ブロックで 1 段。ハーフで半段ずつ)。 */
        public Integer slopeRun;
        /** 道筋を探すとき、隣の点(8 ブロック先)との高さの差の上限(これより急な所は崖・山として避ける)。 */
        public Integer maxStepRise;
        /** 盛り土の深さの上限(これより深い窪みは橋脚で渡す)。 */
        public Integer maxFill;
        /** 橋で渡せる水の幅の上限(ブロック)。 */
        public Integer maxBridge;
        /** 道筋が見つからなかった組を探し直すまでの日数。 */
        public Integer retryDays;
        /** 道の上を払う高さ(木・草・土)。 */
        public Integer headroom;
    }

    public final int width;
    public final int lampSpacing;
    public final int maxLinks;
    public final int maxDistance;
    public final double blocksPerDay;
    public final double costPerBlock;
    public final double minStockFraction;
    public final int slopeRun;
    public final int maxStepRise;
    public final int maxFill;
    public final int maxBridge;
    public final int retryDays;
    public final int headroom;

    private BaseRoadConfig(Json j) {
        this.width = j.width != null ? Math.max(1, Math.min(7, j.width)) : 3;
        this.lampSpacing = j.lampSpacing != null ? Math.max(4, j.lampSpacing) : 24;
        this.maxLinks = j.maxLinks != null ? Math.max(0, Math.min(8, j.maxLinks)) : 2;
        this.maxDistance = j.maxDistance != null ? Math.max(64, j.maxDistance) : 1500;
        this.blocksPerDay = j.blocksPerDay != null ? Math.max(0.0D, j.blocksPerDay) : 96.0D;
        this.costPerBlock = j.costPerBlock != null ? Math.max(0.0D, j.costPerBlock) : 4.0D;
        this.minStockFraction = j.minStockFraction != null ? Math.max(0.0D, Math.min(1.0D, j.minStockFraction)) : 0.5D;
        this.slopeRun = j.slopeRun != null ? Math.max(1, j.slopeRun) : 4;
        this.maxStepRise = j.maxStepRise != null ? Math.max(1, j.maxStepRise) : 5;
        this.maxFill = j.maxFill != null ? Math.max(0, j.maxFill) : 6;
        this.maxBridge = j.maxBridge != null ? Math.max(0, j.maxBridge) : 16;
        this.retryDays = j.retryDays != null ? Math.max(1, j.retryDays) : 7;
        this.headroom = j.headroom != null ? Math.max(2, Math.min(8, j.headroom)) : 4;
    }

    public static BaseRoadConfig of(Json j) {
        return new BaseRoadConfig(j != null ? j : new Json());
    }

    /** 既定値(json に何も書いていないとき)。 */
    public static final BaseRoadConfig DEFAULT = new BaseRoadConfig(new Json());

    /** 同じ値か(同梱の json と既定値の見比べ)。 */
    public boolean sameAs(BaseRoadConfig o) {
        return width == o.width && lampSpacing == o.lampSpacing && maxLinks == o.maxLinks && maxDistance == o.maxDistance
                && blocksPerDay == o.blocksPerDay && costPerBlock == o.costPerBlock && minStockFraction == o.minStockFraction
                && slopeRun == o.slopeRun && maxStepRise == o.maxStepRise && maxFill == o.maxFill && maxBridge == o.maxBridge
                && retryDays == o.retryDays && headroom == o.headroom;
    }
}
