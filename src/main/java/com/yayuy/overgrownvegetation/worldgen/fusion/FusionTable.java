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
    }

    /** 構造物の木と中庭の巨大樹(段 2、11-19)。 */
    public static final class TreeJson {
        public Boolean enabled;
        public Double heightMul, heightAdd, crownBase, crownPerHeight, flare, bend, topPercentile;
        public Double rootStart, rootEnd, rootletStart, rootletEnd;
        public Integer heightMin, heightMax, trunkMin, crownMin, crownMax, roots, bendEvery, openMin;
        public int[] trunkRadius, trunkRadiusBelow;
        public Integer scanChunks, scanTicks, blocksPerTick;
        public Double organChance;
        public Double mediumRootStart, mediumRootEnd, mediumRootReach, saplingTrunkRadius, saplingRootStart, saplingRootEnd;
        public Integer mediumRootSteps, mediumRootTargets, mediumTreesPerHouses, mediumTreesMax, organsMax;
        public Integer saplingHeightMin, saplingHeightMax, saplingCrown, saplingRoots;
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

    // --- 構造物の木(小・中)と中庭の巨大樹(大)。数は全部【仮】(試作 第 7 版の値) ---
    public final boolean treeEnabled;
    /** 高さ = clamp(建物の高さ × heightMul + heightAdd, heightMin, heightMax)。幹は少なくとも trunkMin。 */
    public final double treeHeightMul, treeHeightAdd;
    public final int treeHeightMin, treeHeightMax, treeTrunkMin;
    /** 幹の半径: 全体の大きさが trunkRadiusBelow[i] 未満なら trunkRadius[i]、どれにも当たらなければ最後。 */
    public final int[] treeTrunkRadius, treeTrunkRadiusBelow;
    /** 裾の広がり = 幹の半径 × flare。 */
    public final double treeFlare;
    /** 樹冠の半径 = clamp(crownBase + 高さ × crownPerHeight, crownMin, crownMax)。 */
    public final double treeCrownBase, treeCrownPerHeight;
    public final int treeCrownMin, treeCrownMax;
    public final int treeRoots;
    /** 幹のうねり: bendEvery マスごとに向きを変える。強さは試作の bend 倍。 */
    public final double treeBend;
    public final int treeBendEvery;
    /** 「中から」を選ぶ: 空へ開いた列が覆われた列から openMin マス以上離れていること。 */
    public final int treeOpenMin;
    /** 「てっぺんから」の高さの目安(屋根の列の高さのこの分位。煙突を外す)。 */
    public final double treeTopPercentile;
    /** 根の太さ(根元 → 先)と枝根の太さ。 */
    public final double treeRootStart, treeRootEnd, treeRootletStart, treeRootletEnd;
    /** 生やすきっかけを見る: プレイヤーのまわり scanChunks チャンクを scanTicks ごと。置くのは 1 tick に blocksPerTick 個まで。 */
    public final int treeScanChunks, treeScanTicks, treeBlocksPerTick;
    /** 大きい構造物(段 2c、11-19 ユーザー「中くらいの木の根 + 器官が主役」。段 2b の A+B の巨大樹をやめた): 中くらいの木は家 mediumTreesPerHouses 軒に 1 本・mediumTreesMax 本まで。
     *  近くの建物 mediumRootTargets 軒(mediumRootReach 以内)へ太い根(mediumRootStart → mediumRootEnd・mediumRootSteps 歩)。器官は全部で organsMax 個まで(0 = 制限しない)。 */
    public final double mediumRootStart, mediumRootEnd, mediumRootReach;
    public final int mediumRootSteps, mediumRootTargets, mediumTreesPerHouses, mediumTreesMax, organsMax;
    /** 一番大きい建物のほかの建物(城壁など細長い部品も)に、1 つずつこの確率で小さい器官(段 2d、11-19 ユーザー「器官の数はランダムでいこう」)。 */
    public final double organChance;
    /** 構造物の上・中に植わった苗の木(段 2b): 高さ・幹の半径・樹冠・根の本数と太さ。幹が通らなければ育てない。 */
    public final int saplingHeightMin, saplingHeightMax, saplingCrown, saplingRoots;
    public final double saplingTrunkRadius, saplingRootStart, saplingRootEnd;

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
        treeHeightMul = Math.max(0.0D, or(tj.heightMul, 1.5D));
        treeHeightAdd = or(tj.heightAdd, 15.0D);
        treeHeightMin = Math.max(8, or(tj.heightMin, 35));
        treeHeightMax = Math.max(treeHeightMin, Math.min(120, or(tj.heightMax, 60)));
        treeTrunkMin = Math.max(4, or(tj.trunkMin, 20));
        treeTrunkRadius = tj.trunkRadius != null && tj.trunkRadius.length > 0 ? tj.trunkRadius.clone() : new int[]{2, 3, 4};
        treeTrunkRadiusBelow = tj.trunkRadiusBelow != null ? tj.trunkRadiusBelow.clone() : new int[]{12, 26};
        treeFlare = Math.max(0.0D, or(tj.flare, 0.6D));
        treeCrownBase = or(tj.crownBase, 6.0D);
        treeCrownPerHeight = Math.max(0.0D, or(tj.crownPerHeight, 0.2D));
        treeCrownMin = Math.max(3, or(tj.crownMin, 10));
        treeCrownMax = Math.max(treeCrownMin, Math.min(30, or(tj.crownMax, 18)));
        treeRoots = Math.max(0, Math.min(16, or(tj.roots, 5))); // 段 2d で 8 から(根が板に見えた)
        treeBend = Math.max(0.0D, or(tj.bend, 0.6D));
        treeBendEvery = Math.max(1, or(tj.bendEvery, 6));
        treeOpenMin = Math.max(1, or(tj.openMin, 2));
        treeTopPercentile = Math.max(0.0D, Math.min(1.0D, or(tj.topPercentile, 0.85D)));
        treeRootStart = Math.max(0.5D, or(tj.rootStart, 1.2D));
        treeRootEnd = Math.max(0.5D, or(tj.rootEnd, 0.95D));
        treeRootletStart = Math.max(0.5D, or(tj.rootletStart, 1.0D));
        treeRootletEnd = Math.max(0.5D, or(tj.rootletEnd, 0.5D));
        treeScanChunks = Math.max(1, Math.min(16, or(tj.scanChunks, 6)));
        treeScanTicks = Math.max(1, or(tj.scanTicks, 40));
        treeBlocksPerTick = Math.max(1, or(tj.blocksPerTick, 200));
        mediumRootStart = Math.max(0.5D, or(tj.mediumRootStart, 1.2D));
        mediumRootEnd = Math.max(0.5D, or(tj.mediumRootEnd, 0.9D));
        mediumRootReach = Math.max(4.0D, or(tj.mediumRootReach, 45.0D));
        mediumRootSteps = Math.max(10, Math.min(300, or(tj.mediumRootSteps, 60)));
        mediumRootTargets = Math.max(0, Math.min(8, or(tj.mediumRootTargets, 2)));
        mediumTreesPerHouses = Math.max(1, or(tj.mediumTreesPerHouses, 3));
        mediumTreesMax = Math.max(0, Math.min(8, or(tj.mediumTreesMax, 2)));
        organsMax = Math.max(0, or(tj.organsMax, 0)); // 0 = 制限しない(11-19 ユーザー「上限も無しに」。砂漠の村に 61 個付くのは仕様)
        organChance = Math.max(0.0D, Math.min(1.0D, or(tj.organChance, 0.4D)));
        saplingHeightMin = Math.max(4, or(tj.saplingHeightMin, 10));
        saplingHeightMax = Math.max(saplingHeightMin, Math.min(40, or(tj.saplingHeightMax, 18)));
        saplingCrown = Math.max(2, Math.min(12, or(tj.saplingCrown, 4)));
        saplingRoots = Math.max(0, Math.min(12, or(tj.saplingRoots, 4)));
        saplingTrunkRadius = Math.max(0.5D, Math.min(3.0D, or(tj.saplingTrunkRadius, 1.0D)));
        saplingRootStart = Math.max(0.5D, or(tj.saplingRootStart, 1.0D));
        saplingRootEnd = Math.max(0.5D, or(tj.saplingRootEnd, 0.95D));
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
