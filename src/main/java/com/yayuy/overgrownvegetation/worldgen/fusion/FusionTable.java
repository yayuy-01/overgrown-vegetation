package com.yayuy.overgrownvegetation.worldgen.fusion;

import javax.annotation.Nullable;

/**
 * 構造物と森の融合の設計値({@code design/organs.json} の {@code fusion}。2026-11-19 段 1)。数は全部【仮】。
 * 正本の決定は Vault「構造物と森の融合(壊さずに取り込む)(11-19)」。
 */
public final class FusionTable {

    // --- Gson DTO ---
    public static final class Json {
        public Boolean enabled;
        public Integer pieceMargin, surfaceMargin;
        public Double giantStructureAvoid;
        public SizeJson size;
        public double[] structureWeight;
        public AnchorJson anchors;
        public Integer searchRadius, doorClear;
        public Double tilt, stalkLength, sacLength, sacRadius, coreRadius;
        public Integer vines, vineStepsMin, vineStepsMax, vineReach, veinCrawls, veinStepsMin, veinStepsMax;
        public Double vineFadeSeconds;
    }

    public static final class SizeJson {
        public Integer pieceMediumSpan, pieceLargeSpan, wholeMediumSpan, wholeLargeSpan, wholeLargeArea;
    }

    /** 根元の選ばれやすさ(壁の側面・屋根の上・そばの地面)を、根元を付ける部品の大きさの段ごとに。 */
    public static final class AnchorJson {
        public double[] small, medium, large;
    }

    public final boolean enabled;
    /** 部品の箱をこれだけ広げた範囲も守る。 */
    public final int pieceMargin;
    /** 部品の箱の上端が地表よりこれ以上下なら、地下の部品として守らない・融合しない。 */
    public final int surfaceMargin;
    /** 構造物の部品からこれより近い所では巨大樹が育たない(段 2 の巨大樹の融合まで)。 */
    public final double giantStructureAvoid;
    /** 大きさの段の境目(長い辺 = 横の長い辺か高さの大きい方)。全体は床面積 wholeLargeArea 以上でも大。 */
    public final int pieceMediumSpan, pieceLargeSpan, wholeMediumSpan, wholeLargeSpan, wholeLargeArea;
    /** 司令塔が構造物のそばの升に足す点(全体の段 小・中・大)。0 なら好まない。 */
    public final double[] structureWeight;
    /** 根元の重み [段][壁・屋根・地面]。 */
    public final double[][] anchorWeights;
    public final int searchRadius, doorClear;
    public final double tilt, stalkLength, sacLength, sacRadius, coreRadius;
    public final int vines, vineStepsMin, vineStepsMax, vineReach, veinCrawls, veinStepsMin, veinStepsMax;
    public final double vineFadeSeconds;

    public static final FusionTable DEFAULT = fromJson(null);

    private FusionTable(Json j) {
        SizeJson s = j.size != null ? j.size : new SizeJson();
        AnchorJson a = j.anchors != null ? j.anchors : new AnchorJson();
        enabled = j.enabled == null || j.enabled;
        pieceMargin = Math.max(0, or(j.pieceMargin, 1));
        surfaceMargin = Math.max(0, or(j.surfaceMargin, 3));
        giantStructureAvoid = Math.max(0.0D, or(j.giantStructureAvoid, 60.0D));
        pieceMediumSpan = Math.max(1, or(s.pieceMediumSpan, 12));
        pieceLargeSpan = Math.max(pieceMediumSpan, or(s.pieceLargeSpan, 24));
        wholeMediumSpan = Math.max(1, or(s.wholeMediumSpan, 18));
        wholeLargeSpan = Math.max(wholeMediumSpan, or(s.wholeLargeSpan, 40));
        wholeLargeArea = Math.max(1, or(s.wholeLargeArea, 2500));
        structureWeight = three(j.structureWeight, new double[]{0.3D, 0.5D, 0.8D});
        anchorWeights = new double[][]{three(a.small, new double[]{0.4D, 0.2D, 0.4D}), three(a.medium, new double[]{0.5D, 0.3D, 0.2D}),
                three(a.large, new double[]{0.5D, 0.3D, 0.2D})};
        searchRadius = Math.max(4, Math.min(100, or(j.searchRadius, 24)));
        doorClear = Math.max(0, or(j.doorClear, 5));
        tilt = Math.max(0.0D, Math.min(90.0D, or(j.tilt, 35.0D)));
        stalkLength = Math.max(1.0D, or(j.stalkLength, 4.5D));
        sacLength = Math.max(1.0D, or(j.sacLength, 4.3D));
        sacRadius = Math.max(1.0D, or(j.sacRadius, 3.3D));
        coreRadius = Math.max(0.5D, or(j.coreRadius, 1.6D));
        vines = Math.max(0, or(j.vines, 5));
        vineStepsMin = Math.max(1, or(j.vineStepsMin, 45));
        vineStepsMax = Math.max(vineStepsMin, or(j.vineStepsMax, 90));
        vineReach = Math.max(2, Math.min(40, or(j.vineReach, 10)));
        veinCrawls = Math.max(0, or(j.veinCrawls, 9));
        veinStepsMin = Math.max(1, or(j.veinStepsMin, 14));
        veinStepsMax = Math.max(veinStepsMin, or(j.veinStepsMax, 24));
        vineFadeSeconds = Math.max(0.0D, or(j.vineFadeSeconds, 60.0D));
    }

    public static FusionTable fromJson(@Nullable Json j) {
        return new FusionTable(j != null ? j : new Json());
    }

    private static double[] three(@Nullable double[] v, double[] d) {
        return v != null && v.length == 3 ? v.clone() : d;
    }

    private static int or(@Nullable Integer v, int d) {
        return v != null ? v : d;
    }

    private static double or(@Nullable Double v, double d) {
        return v != null ? v : d;
    }
}
