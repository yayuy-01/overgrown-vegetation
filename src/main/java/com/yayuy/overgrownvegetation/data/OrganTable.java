package com.yayuy.overgrownvegetation.data;

/**
 * {@code organs.json} —— <b>器官</b>の設計値(2026-09-23)。
 *
 * <p>器官は極相({@code engulfed})の<b>地脈の上</b>に育ち、<b>世界そのものへ効く</b>。
 * 普通の設備と違って<b>効果に距離が無い</b> —— 遠くに住んでも届く。
 * 詳しくは {@code organ/OrganInfluence}。
 *
 * <h2>節と姿</h2>
 * <p>器官は必ず<b>節</b>({@code organ_node})から生える。台帳が覚えているのは節だけで、
 * 姿は節の位置から毎回計算する({@code organ/OrganShape})。
 * <b>姿は種類ごとに違う</b>(要望)ので、{@code sporeGland.tower} は
 * <b>胞子腺だけ</b>の形の値。次の器官は塔ですらない。
 *
 * <h2>胞子腺({@code sporeGland})</h2>
 * <ul>
 *     <li>{@code cellSizeChunks} —— 器官の間隔。世界をこの大きさの升目に切り、
 *     <b>升目の中の地脈が通っているチャンク</b>から 1 つだけ候補地を選ぶ
 *     (種と座標だけで決まるので、どこから開拓しても同じ場所に生える)。</li>
 *     <li>{@code maturityDays} —— 育ちきるまでの日数。効果は<b>育ち具合に比例</b>して上がる。</li>
 *     <li>{@code worldFloorPercent} / {@code worldFloorMaxPercent} —— 世界の胞子濃度の下限と、その頭打ち。</li>
 *     <li>{@code localPercent} / {@code localReachChunks} —— 器官のまわりだけに乗る上乗せ。
 *     <b>器官を探す唯一の手がかり</b>。</li>
 *     <li>{@code witherBlocksPerTick} —— 節を潰されたあと、1 tick に何マスずつ枯れるか。</li>
 *     <li>{@code tower} —— 三つ編みの塔の形。</li>
 *     <li>{@code glandPenalty} —— 腺を壊したときの罰。</li>
 * </ul>
 *
 * <p>{@code /ov design reload} で即反映。ただし<b>既に建っている器官は建て直らない</b> ——
 * {@code tower} を大きく動かすと、枯らすときの形が建っているものと食い違う。
 */
public final class OrganTable {

    public static final class Json {
        public Boolean enabled;
        public SporeGlandJson sporeGland;
    }

    /** {@code sporeGland} 節の生の形。 */
    public static final class SporeGlandJson {
        public Integer cellSizeChunks;
        public Double maturityDays;
        public Double worldFloorPercent;
        public Double worldFloorMaxPercent;
        public Double localPercent;
        public Integer localReachChunks;
        public Integer witherBlocksPerTick;
        public TowerJson tower;
        public GlandPenaltyJson glandPenalty;
    }

    /** {@code sporeGland.tower} 節の生の形。 */
    public static final class TowerJson {
        public Integer height;
        public Integer strands;
        public Double radius;
        public Double taper;
        public Double twistDegrees;
        public Double braidAmplitude;
        public Double braidPeriod;
        public Double weaveDegrees;
        public Double strandThickness;
        public Integer rootHeight;
        public Double rootRadius;
        public Double vineRatio;
        public Double glandChance;
    }

    /** {@code sporeGland.glandPenalty} 節の生の形。 */
    public static final class GlandPenaltyJson {
        public Integer effects;
        public Integer durationSeconds;
    }

    /**
     * 三つ編みの塔の形。<b>胞子腺だけ</b>の値。
     *
     * @param height          高さ(ブロック)
     * @param strands         編む筋の本数
     * @param radius          根元での軸から筋までの距離
     * @param taper           先端へ向けてどれだけ絞るか(0〜1。0.6 なら先端で 4 割の太さ)
     * @param twistDegrees    1 ブロック上がるごとに筋が回る角度
     * @param braidAmplitude  筋の半径を内外に揺らす幅。<b>0 にすると編み目が消えてただのねじれた柱になる</b>
     * @param braidPeriod     半径が一往復する高さ(ブロック)
     * @param weaveDegrees    筋の<b>角度</b>を左右に振る幅(度)。<b>これが無いと編めない</b> ——
     *                        角度が固定だと筋同士が一度も近づかず、独立した蔦が並ぶだけになる
     * @param strandThickness 筋 1 本の太さ(半径)
     * @param rootHeight      下端を塊にする高さ。節はこの中に埋まる
     * @param rootRadius      その塊の半径
     * @param vineRatio       器官の蔦の割合(残りは汚染ブロック)
     * @param glandChance     表面のマスが腺になる確率
     */
    public record Tower(int height, int strands, double radius, double taper, double twistDegrees,
                        double braidAmplitude, double braidPeriod, double weaveDegrees, double strandThickness,
                        int rootHeight, double rootRadius, double vineRatio, double glandChance) {
    }

    /**
     * 腺を壊したときの罰。
     *
     * @param effects         6 つの候補から<b>いくつ</b>掛けるか
     * @param durationSeconds 効果の長さ(秒)
     */
    public record GlandPenalty(int effects, int durationSeconds) {
    }

    /**
     * 胞子腺の設計値。
     *
     * @param cellSizeChunks       器官の間隔(升目 1 辺のチャンク数)
     * @param maturityTicks        育ちきるまでの時間(tick)
     * @param worldFloorPercent    育ちきった器官 1 つが作る世界の下限(%)
     * @param worldFloorMaxPercent 下限の頭打ち(%)
     * @param localPercent         器官の真上での上乗せ(%)
     * @param localReachChunks     上乗せが 0 になるまでの距離(チャンク)
     * @param witherBlocksPerTick  枯れる速さ(1 tick に消すマス数)
     */
    public record SporeGland(int cellSizeChunks, long maturityTicks,
                             double worldFloorPercent, double worldFloorMaxPercent,
                             double localPercent, int localReachChunks,
                             int witherBlocksPerTick, Tower tower, GlandPenalty glandPenalty) {

        /** 上乗せが 0 になるまでの距離(ブロック)。 */
        public double localReachBlocks() {
            return localReachChunks * 16.0D;
        }
    }

    private final boolean enabled;
    private final SporeGland sporeGland;

    private OrganTable(Json j) {
        Json src = j != null ? j : new Json();
        this.enabled = src.enabled == null || src.enabled;

        SporeGlandJson g = src.sporeGland != null ? src.sporeGland : new SporeGlandJson();
        TowerJson t = g.tower != null ? g.tower : new TowerJson();
        GlandPenaltyJson p = g.glandPenalty != null ? g.glandPenalty : new GlandPenaltyJson();
        double days = g.maturityDays != null ? Math.max(0.0D, g.maturityDays) : 7.0D;

        Tower tower = new Tower(
                t.height != null ? Math.max(3, Math.min(64, t.height)) : 38,
                t.strands != null ? Math.max(1, Math.min(8, t.strands)) : 3,
                t.radius != null ? Math.max(0.5D, Math.min(12.0D, t.radius)) : 5.8D,
                t.taper != null ? clamp01(t.taper, 0.8D) : 0.8D,
                t.twistDegrees != null ? Math.max(-90.0D, Math.min(90.0D, t.twistDegrees)) : 13.0D,
                t.braidAmplitude != null ? Math.max(0.0D, Math.min(6.0D, t.braidAmplitude)) : 2.5D,
                t.braidPeriod != null ? Math.max(1.0D, Math.min(32.0D, t.braidPeriod)) : 13.0D,
                t.weaveDegrees != null ? Math.max(0.0D, Math.min(90.0D, t.weaveDegrees)) : 50.0D,
                t.strandThickness != null ? Math.max(0.5D, Math.min(6.0D, t.strandThickness)) : 2.4D,
                t.rootHeight != null ? Math.max(1, Math.min(16, t.rootHeight)) : 3,
                t.rootRadius != null ? Math.max(0.5D, Math.min(12.0D, t.rootRadius)) : 6.0D,
                t.vineRatio != null ? clamp01(t.vineRatio, 0.45D) : 0.45D,
                t.glandChance != null ? clamp01(t.glandChance, 0.05D) : 0.05D);

        GlandPenalty penalty = new GlandPenalty(
                p.effects != null ? Math.max(0, Math.min(6, p.effects)) : 2,
                p.durationSeconds != null ? Math.max(1, Math.min(600, p.durationSeconds)) : 20);

        this.sporeGland = new SporeGland(
                g.cellSizeChunks != null ? Math.max(4, Math.min(512, g.cellSizeChunks)) : 24,
                (long) (days * 24000.0D),
                g.worldFloorPercent != null ? clampPercent(g.worldFloorPercent) : 18.0D,
                g.worldFloorMaxPercent != null ? clampPercent(g.worldFloorMaxPercent) : 45.0D,
                g.localPercent != null ? clampPercent(g.localPercent) : 40.0D,
                g.localReachChunks != null ? Math.max(1, Math.min(64, g.localReachChunks)) : 12,
                g.witherBlocksPerTick != null ? Math.max(1, Math.min(256, g.witherBlocksPerTick)) : 16,
                tower, penalty);
    }

    /** 器官を育てるか。false にすると新しい器官が生えなくなる(既に生えているものは残る)。 */
    public boolean enabled() {
        return enabled;
    }

    public SporeGland sporeGland() {
        return sporeGland;
    }

    private static double clampPercent(double value) {
        return Math.max(0.0D, Math.min(100.0D, value));
    }

    private static double clamp01(Double value, double fallback) {
        return value == null ? fallback : Math.max(0.0D, Math.min(1.0D, value));
    }

    static OrganTable fromJson(Json j) {
        return new OrganTable(j);
    }

    public static final OrganTable DEFAULT = new OrganTable(null);
}
