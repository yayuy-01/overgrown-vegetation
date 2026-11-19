package com.yayuy.overgrownvegetation.worldgen.fusion;

import javax.annotation.Nullable;

/**
 * 構造物と森の融合の設計値({@code design/organs.json} の {@code fusion}。2026-11-19 段 1)。数は全体の境目 18/40(確定)のほかは全部【仮】。
 * 正本の決定は Vault「構造物と森の融合(壊さずに取り込む)(11-19)」。
 */
public final class FusionTable {

    // --- Gson DTO ---
    public static final class Json {
        public Boolean enabled;
        public Integer pieceMargin, surfaceMargin;
        public Double giantClearRadius;
        public SizeJson size;
        public double[] structureWeight;
        public AnchorJson anchors;
        public Integer searchRadius, doorClear;
        public Double tilt, stalkLength, sacLength, sacRadius, coreRadius;
        public Integer vines, vineStepsMin, vineStepsMax, vineReach, veinCrawls, veinStepsMin, veinStepsMax;
        public Double vineFadeSeconds;
        public TreeJson tree;
        public TakeoverJson takeover;
    }

    /** 構造物の上の苗の木(段 2b)と、置き残しの構造物の木を置く速さ。 */
    public static final class TreeJson {
        public Boolean enabled;
        public Double bend, rootletStart, rootletEnd, saplingTrunkRadius, saplingRootStart, saplingRootEnd;
        public Integer blocksPerTick, saplingHeightMin, saplingHeightMax, saplingCrown, saplingRoots;
    }

    /** 乗っ取り(段 3、11-19 夜)。 */
    public static final class TakeoverJson {
        public Boolean enabled;
        public Integer scanTicks, radiusChunks, chunksPerScan, facesPerOrgan, spacingSmall, spacingBig, vinesSmall, vinesBig;
        public Double organChancePerVisit, bigChance, holeChance;
        public double[] smallLength, bigLength;
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
    /** 構造物の部品からこれより近い所では、ふつうの巨大樹(苗木の抽選・森の跡の苗)は育たない。大きい構造物の中庭の巨大樹は除く(段 2)。 */
    public final double giantClearRadius;
    /** 大きさの段の境目(長い辺 = 横の長い辺か高さの大きい方)。全体の 18/40 は 11-19 ユーザー決定で確定、部品の 12/24 と床面積 wholeLargeArea は【仮】。全体は床面積 wholeLargeArea 以上でも大。 */
    public final int pieceMediumSpan, pieceLargeSpan, wholeMediumSpan, wholeLargeSpan, wholeLargeArea;
    /** 司令塔が構造物のそばの升に足す点(全体の段 小・中・大)。0 なら好まない。 */
    public final double[] structureWeight;
    /** 根元の重み [段][壁・屋根・地面]。 */
    public final double[][] anchorWeights;
    public final int searchRadius, doorClear;
    public final double tilt, stalkLength, sacLength, sacRadius, coreRadius;
    public final int vines, vineStepsMin, vineStepsMax, vineReach, veinCrawls, veinStepsMin, veinStepsMax;
    public final double vineFadeSeconds;
    // --- 構造物の上・中に植わった苗の木(段 2b)。数は全部【仮】 ---
    public final boolean treeEnabled;
    /** 苗の木の幹のうねりの強さ・枝根の太さ(根元 → 先)・置き残しの木を 1 tick に置く数。 */
    public final double treeBend, treeRootletStart, treeRootletEnd;
    public final int treeBlocksPerTick;
    /** 構造物の上・中に植わった苗の木(段 2b): 高さ・幹の半径・樹冠・根の本数と太さ。幹が通らなければ育てない。 */
    public final int saplingHeightMin, saplingHeightMax, saplingCrown, saplingRoots;
    public final double saplingTrunkRadius, saplingRootStart, saplingRootEnd;

    // --- 乗っ取り(段 3、11-19 夜 ユーザー「植物に乗っ取られている感じ」)。数は全部【仮】 ---
    public final boolean takeoverEnabled;
    /** scanTicks ごとに、プレイヤーのまわり radiusChunks チャンクから chunksPerScan チャンクを見る。 */
    public final int takeoverScanTicks, takeoverRadiusChunks, takeoverChunksPerScan;
    /** 1 回見たチャンクで器官の芽を 1 つ出す確率。出すのは人工の露出面 facesPerOrgan 面につき 1 つまで(面の広さに比べる・上限なし)。 */
    public final double takeoverOrganChancePerVisit;
    public final int takeoverFacesPerOrgan;
    /** 大きい器官になる確率と、器官どうしの間(小・大)。 */
    public final double takeoverBigChance;
    public final int takeoverSpacingSmall, takeoverSpacingBig;
    /** 茎の長さ [最小, 最大](小・大)・小さい器官が根元の面を突き破る確率・根元から這う蔦の本数(小・大)。 */
    public final double[] takeoverSmallLength, takeoverBigLength;
    public final double takeoverHoleChance;
    public final int takeoverVinesSmall, takeoverVinesBig;

    public static final FusionTable DEFAULT = fromJson(null);

    private FusionTable(Json j) {
        SizeJson s = j.size != null ? j.size : new SizeJson();
        AnchorJson a = j.anchors != null ? j.anchors : new AnchorJson();
        enabled = j.enabled == null || j.enabled;
        pieceMargin = Math.max(0, or(j.pieceMargin, 1));
        surfaceMargin = Math.max(0, or(j.surfaceMargin, 3));
        giantClearRadius = Math.max(0.0D, or(j.giantClearRadius, 24.0D));
        pieceMediumSpan = Math.max(1, or(s.pieceMediumSpan, 12));
        pieceLargeSpan = Math.max(pieceMediumSpan, or(s.pieceLargeSpan, 24));
        wholeMediumSpan = Math.max(1, or(s.wholeMediumSpan, 18));
        wholeLargeSpan = Math.max(wholeMediumSpan, or(s.wholeLargeSpan, 40));
        wholeLargeArea = Math.max(1, or(s.wholeLargeArea, 2500));
        structureWeight = three(j.structureWeight, new double[]{0.5D, 0.8D, 1.2D}); // 段 2c で 0.3/0.5/0.8 から上げた(器官を構造物に集める)
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
        TreeJson tj = j.tree != null ? j.tree : new TreeJson();
        treeEnabled = tj.enabled == null || tj.enabled;
        treeBend = Math.max(0.0D, or(tj.bend, 0.6D));
        treeRootletStart = Math.max(0.5D, or(tj.rootletStart, 1.0D));
        treeRootletEnd = Math.max(0.5D, or(tj.rootletEnd, 0.5D));
        treeBlocksPerTick = Math.max(1, or(tj.blocksPerTick, 200));
        saplingHeightMin = Math.max(4, or(tj.saplingHeightMin, 10));
        saplingHeightMax = Math.max(saplingHeightMin, Math.min(40, or(tj.saplingHeightMax, 18)));
        saplingCrown = Math.max(2, Math.min(12, or(tj.saplingCrown, 4)));
        saplingRoots = Math.max(0, Math.min(12, or(tj.saplingRoots, 4)));
        saplingTrunkRadius = Math.max(0.5D, Math.min(3.0D, or(tj.saplingTrunkRadius, 1.0D)));
        saplingRootStart = Math.max(0.5D, or(tj.saplingRootStart, 1.0D));
        saplingRootEnd = Math.max(0.5D, or(tj.saplingRootEnd, 0.95D));
        TakeoverJson ko = j.takeover != null ? j.takeover : new TakeoverJson();
        takeoverEnabled = ko.enabled == null || ko.enabled;
        takeoverScanTicks = Math.max(20, or(ko.scanTicks, 200));
        takeoverRadiusChunks = Math.max(1, Math.min(16, or(ko.radiusChunks, 6)));
        takeoverChunksPerScan = Math.max(1, Math.min(64, or(ko.chunksPerScan, 4)));
        takeoverOrganChancePerVisit = Math.max(0.0D, Math.min(1.0D, or(ko.organChancePerVisit, 0.25D)));
        takeoverFacesPerOrgan = Math.max(1, or(ko.facesPerOrgan, 110));
        takeoverBigChance = Math.max(0.0D, Math.min(1.0D, or(ko.bigChance, 0.08D)));
        takeoverSpacingSmall = Math.max(1, or(ko.spacingSmall, 4));
        takeoverSpacingBig = Math.max(1, or(ko.spacingBig, 14));
        takeoverSmallLength = two(ko.smallLength, new double[]{3.0D, 9.0D});
        takeoverBigLength = two(ko.bigLength, new double[]{12.0D, 22.0D});
        takeoverHoleChance = Math.max(0.0D, Math.min(1.0D, or(ko.holeChance, 0.3D)));
        takeoverVinesSmall = Math.max(0, Math.min(16, or(ko.vinesSmall, 2)));
        takeoverVinesBig = Math.max(0, Math.min(32, or(ko.vinesBig, 6)));
    }

    public static FusionTable fromJson(@Nullable Json j) {
        return new FusionTable(j != null ? j : new Json());
    }

    private static double[] two(@Nullable double[] v, double[] d) {
        return v != null && v.length == 2 && v[0] <= v[1] ? v.clone() : d;
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
