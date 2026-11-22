package com.yayuy.overgrownvegetation.data;

/**
 * 司令塔が植える監視塔(フィロセンス)の設計値({@code director.json} の {@code watcher}、2026-11-14)。
 * ユーザーが決めたのは: F5 から・覚えた拠点の森の側 48〜96 ブロック・拠点 1 つにつき最大 2 本・約半日かけて芽から育つ(育ち切る前に刈れる)・
 * 値段 15【仮】・視線の印で認識が確定する・刈られると認識が遅れる / 忘れる・印の 1 分の間、半径 64【仮】の植物側の Mob を呼ぶ。<b>ほかは【仮】</b>。
 */
public final class WatcherTable {

    public static final WatcherTable DEFAULT = new WatcherTable(null);

    public static final class Json {
        public Integer maxPerBase;
        public Double cost;
        public Double minBlocks;
        public Double maxBlocks;
        public Double spreadDeg;
        public Double playerMinBlocks;
        public Integer tries;
        public Double plantIntervalDays;
        public Double replantDays;
        public Double growDays;
        public Double cutYoungDelayDays;
        public Double cutGrownDelayDays;
        public Boolean cutGrownSetsBack;
        public Double callRadius;
        public Integer callTicks;
    }

    /** 拠点 1 つにつき立てておく監視塔の数(ユーザー 2)。 */
    public final int maxPerBase;
    /** 植える値段(ユーザー 15【仮】。段の強さで割る)。 */
    public final double cost;
    /** 植える所: 拠点の真ん中から森の側へこの幅(ユーザー 48〜96)。向きの振れ(度)・プレイヤーから離す距離・試す数。 */
    public final double minBlocks;
    public final double maxBlocks;
    public final double spreadDeg;
    public final double playerMinBlocks;
    public final int tries;
    /** 1 本植えてから、次を植えてよいまで(日)と、刈られてから植え直してよいまで(日)。 */
    public final double plantIntervalDays;
    public final double replantDays;
    /** 芽から育ち切るまでの目安(日。ユーザー「約半日」。実際に育てるのは監視塔の側)。 */
    public final double growDays;
    /** 育ち切る前に刈られたとき・育った後に刈られたときに、認識を遅らせる日数。育った後に刈られたら 1 段戻すか。 */
    public final double cutYoungDelayDays;
    public final double cutGrownDelayDays;
    public final boolean cutGrownSetsBack;
    /** 視線の印で呼ぶ半径(ユーザー 64【仮】)と長さ(tick。ユーザー「1 分」)。 */
    public final double callRadius;
    public final int callTicks;

    private WatcherTable(Json j) {
        Json s = j != null ? j : new Json();
        this.maxPerBase = Math.max(0, Math.min(8, s.maxPerBase != null ? s.maxPerBase : 2));
        this.cost = nn(s.cost, 1500.0D);
        this.minBlocks = Math.max(8.0D, nn(s.minBlocks, 48.0D));
        this.maxBlocks = Math.max(this.minBlocks, nn(s.maxBlocks, 96.0D));
        this.spreadDeg = Math.min(180.0D, nn(s.spreadDeg, 45.0D));
        this.playerMinBlocks = nn(s.playerMinBlocks, 32.0D);
        this.tries = Math.max(1, Math.min(32, s.tries != null ? s.tries : 8));
        this.plantIntervalDays = nn(s.plantIntervalDays, 0.5D);
        this.replantDays = nn(s.replantDays, 1.0D);
        this.growDays = nn(s.growDays, 0.5D);
        this.cutYoungDelayDays = nn(s.cutYoungDelayDays, 0.5D);
        this.cutGrownDelayDays = nn(s.cutGrownDelayDays, 1.0D);
        this.cutGrownSetsBack = s.cutGrownSetsBack == null || s.cutGrownSetsBack;
        this.callRadius = Math.max(1.0D, nn(s.callRadius, 64.0D));
        this.callTicks = Math.max(1, s.callTicks != null ? s.callTicks : 1200);
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static WatcherTable fromJson(Json j) {
        return j == null ? DEFAULT : new WatcherTable(j);
    }
}
