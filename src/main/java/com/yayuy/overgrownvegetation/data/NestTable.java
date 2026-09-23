package com.yayuy.overgrownvegetation.data;

/**
 * {@code nest.json} — アルボラの巣(地下ダンジョン)の設計値(2026-09-19)。
 *
 * <p>既定値は {@code tools/nest-preview/nest-preview.html} でユーザーが決めたもの。
 * あのプレビューはここと同じ手順を JavaScript で再現してあるので、つまみの値をそのまま写せば
 * ゲーム内も同じ形になる。{@code /ov design reload} で即反映(掘り終わった巣は変わらない)。
 *
 * <h2>掘り方</h2>
 * <p>掘削点(digger)を 1 歩ずつ歩かせて掘る。1 歩ごとに「向きを少し曲げる → 進む → 球状に掘る →
 * 分岐 / 側室を抽選」。入口の坑道(幹)が斜めに下りながら {@code trunkBranches} 本の枝を
 * <b>1 本ずつ順番に</b>出し、枝はさらに孫枝を出して、道の脇と行き止まりに部屋ができる。
 * 枝は生えるときに「下る / 水平 / 上る」を選ぶので縦にも広がるが、傾きは
 * {@code maxTunnelSlope} までなので歩いて下れる坂に収まる。
 *
 * <p><b>{@code blockBudget} は必ず効かせること</b> —— 上限を外すと 10 万マスを超えて掘り、
 * 巨大樹と同じ「サーバーが固まる」事故になる(2026-09-19 にプレビューで実測)。
 */
public final class NestTable {

    public static final class Json {
        public Shape shape;
        public Digging digging;
        public Decoration decoration;
        public Contents contents;
        public Formation formation;
        /** 1 tick に設置してよいブロック数(設置は {@code nest/NestGrowthQueue} が分割して行う)。 */
        public Integer blocksPerTick;
    }

    public static final class Shape {
        /** 入口から最下段までの深さ(ブロック)。 */
        public Integer shaftDepth;
        /** 入口の坑道(幹)から出す枝の本数。幹に沿って 1 本ずつ順に出す。 */
        public Integer trunkBranches;
        /** 枝が巣の中心から広がってよい半径(ブロック)。 */
        public Integer spread;
        /** 最下段よりさらに下へ枝が潜ってよい幅(ブロック、全幅)。 */
        public Integer depthBand;
        /**
         * 坑道を掘る球の半径(ブロック)。プレイヤーが通れる太さにすること。
         * <b>1.74(=√3)を下回らせない</b> —— それより細いと断面が十字になり、
         * 床が 1×1 の飛び石になって Mob が 1 歩も進めなくなる(2026-09-20)。
         * 設計値が細くても {@code NestLayout.WALKABLE_RADIUS} が下限として効く。
         */
        public Double tunnelRadius;
        /**
         * 入口の坑道の傾き(1 マス進むごとに何マス下がるか)。小さいほど緩い坂。
         * 0.5 でおよそ 27 度。縦穴ではなく「斜めに下っていく坑道」にするための値(ユーザー指定 2026-09-19)。
         */
        public Double entranceSlope;
        /** 入口の坑道が深くなるにつれて急になる度合い。最下段でこの傾きになる。 */
        public Double deepSlope;
        /** 地表に作る塚の半径(ブロック)。0 で塚を作らない。 */
        public Integer moundRadius;
        /** 地表に作る塚の高さ(ブロック)。 */
        public Integer moundHeight;
        /**
         * 坑道の傾きの上限(1 マス進むごとに何マス上下してよいか)。
         * 0.5 でおよそ 27 度。<b>坑道が垂直にならないようにするための上限</b>(ユーザー指定 2026-09-19)。
         */
        public Double maxTunnelSlope;
        /** 坑道どうしが近づいてよい距離(ブロック)。これより近づくと掘削点が横へ逃げる。 */
        public Integer tunnelSpacing;
        /**
         * <b>外殻を張らない</b>のは坑口と噴出口からこのマス数まで(2026-09-19 にユーザー指定で
         * 「地表より深いところだけ」から変更)。巣は地中でも屋外でも外殻で囲むので、崖や水辺に
         * 掘り抜けても坑道は途切れない。ここを広げすぎると入口の周りが穴だらけになる。
         */
        public Integer openingRadius;
    }

    public static final class Digging {
        /** 1 歩ごとに向きを曲げる強さ。大きいほどくねる。 */
        public Double wander;
        /** 1 歩ごとに横へ分岐する確率。 */
        public Double branchChance;
        /** 枝が 1 歩ごとに下り坂へ切り替わる確率。立体感はここで決まる。 */
        public Double descendChance;
        /** 幹から出た枝 1 本が歩く歩数。孫枝はこの 6 割ずつ短くなる。 */
        public Integer galleryBudget;
        /** <b>坑道の途中</b>にできる側室の数(噴気室を含む)。行き止まりの部屋はこれとは別枠。 */
        public Integer chamberTotal;
        /**
         * <b>坑道の行き止まりに置く部屋の数</b>(2026-09-20 のユーザー指定
         * 「坑道の最奥に必ず卵室か貯蔵室ができるように」)。
         *
         * <p>掘削点の数もこの値で頭打ちにするので、<b>行き止まりの数とちょうど同じだけ部屋が置かれる</b> ——
         * つまり「どの坑道を最後まで行っても必ず部屋がある」。増やすと枝分かれも部屋も増える。
         */
        public Integer deadEndChambers;
        /** 行き止まりの部屋のうち孵化室にする数(奥から順)。残りは貯蔵室になる。 */
        public Integer deadEndHatcheries;
        /**
         * 側室どうし(と中心から)の最低間隔(ブロック)。小さいと部屋が中心に固まる。
         * <b>両方の半径に足して</b>測るので、大きい部屋どうしは自動的に遠くなる(2026-09-20)。
         */
        public Integer chamberGap;
        /**
         * 部屋のまわりに必ず残す岩の厚み(ブロック)。<b>坑道はここを掘れない</b>(2026-09-20)。
         *
         * <p>部屋は「ここから {@code chamberClearance} マスぶんが岩」の場所にしか置かず、
         * 置いたあとはその岩を押さえて坑道が掘れないようにする。大きくすると巣が横に広がるかわりに、
         * 部屋と坑道がひと続きの大空間になることが無くなる(ユーザー報告への対処)。
         * 部屋へ入る通路は別に 1 本掘るので、0 にしないかぎり部屋が孤立することはない。
         */
        public Integer chamberClearance;
        /** 掘ってよいマス数の上限。ここに達した時点で掘削点が止まる。 */
        public Integer blockBudget;
        /**
         * <b>アルボラ 1 体が 1 マス掘るのに掛ける tick</b>(ユーザー指定 2026-09-20:
         * 「一瞬で生成するのではなく、アルボラが 1 マスずつ掘って作っていくように」)。
         *
         * <p>掘る速さは<b>切羽に居るアルボラの数 ÷ この値</b>(マス/tick)。
         * 8 体で 8 なら 1 マス/tick なので、1 万マスの巣におよそ 8 分かかる。
         * 小さくすると速くなるが、そのぶん「掘っている」感が薄れる。
         */
        public Integer digTicksPerBlock;
    }

    /** 巣の中に生える植物。過繫茂バイオームの洞窟と同じ雰囲気にする。 */
    public static final class Decoration {
        /** 床 1 マスに下草が生える確率。 */
        public Double floorPlantChance;
        /** 天井・壁 1 マスに洞窟植物(胞子嚢・苔の塊)が付く確率。 */
        public Double wallPlantChance;
        /** 床の下草のうち、立体モデルのシダになる割合。重いので低めに(下草は FPS の主因)。 */
        public Double geoFernShare;
    }

    /** 側室の中身(2026-09-20)。掘り終わった巣へ {@code nest/NestContents} が置く。 */
    public static final class Contents {
        /** 孵化室 1 室に置くアルボラの卵の数。 */
        public Integer eggsPerHatchery;
        /** 卵が孵るまでの間隔(tick)。ランダムティックではなくブロックエンティティの時計で数える。 */
        public Integer eggHatchIntervalTicks;
        /** 卵の周りにこの数だけアルボラが居たら孵らない(湧きすぎ防止)。 */
        public Integer eggMaxNearby;
        /** 衛兵室に置くシールダーの数。 */
        public Integer guardShielders;
        /** 衛兵室に置くアルボラの数。 */
        public Integer guardArbora;
        /** 衛兵室の個体の能力倍率(体力・防御・攻撃)。 */
        public Double guardBuff;
        /** 貯蔵室 1 室に置く貯蔵胞の数。 */
        public Integer storagePodsPerRoom;
        /** 噴出孔が 1% 伸びる / 次の 1 本が生えるまでの間隔(ゲーム内時間の tick)。 */
        public Integer ventGrowthIntervalTicks;
        /** 1 つの巣から地上へ出る噴出孔の最大数。 */
        public Integer ventMaxCount;
        /** 噴出孔の効果半径(ブロック)。胞子濃度の上乗せもデバフもこの範囲。 */
        public Integer ventRadius;
        /** 噴出孔 1 本が最後まで育ったときの上乗せ(%)。 */
        public Integer ventBonusPerVentPercent;
        /** 胞子 node 1 個あたりの汚染速度の上乗せ(倍率に足す)。 */
        public Double nodeCorruptionPerNode;
        /** 汚染速度の上乗せで数える胞子 node の上限。これ以上置いても速くならない。 */
        public Integer nodeCorruptionMaxNodes;
        /** 巣の核の強化の抽選: 体力 / 攻撃力 / 防御力。合計 1 になるようにする。 */
        public Double coreHealthChance;
        public Double coreAttackChance;
        public Double coreArmorChance;
        /** 巣の核の強化の量。 */
        public Double coreHealthBonus;
        public Double coreAttackBonus;
        public Double coreArmorBonus;
        /** 女王が戦闘中に産むアルボラの数(1 回あたり)。 */
        public Integer queenBroodCount;
        /** 女王が産むまでの間隔(tick)。 */
        public Integer queenBroodIntervalTicks;
        /** 巣の住人が狙ってよい範囲(入口からの水平距離)。外の敵に釣られて巣を空にしないための綱。 */
        public Integer guardLeashRadius;
        /** アルボラが落ちているアイテムを拾いに行く半径。 */
        public Integer haulSearchRadius;
        /** 巣がこの距離以内にあれば、拾ったものを運び込む。 */
        public Integer haulNestRadius;
        /** 建設グループを組むのに要るアルボラの数。 */
        public Integer builderRequired;
        /**
         * <b>建設地で掘り始めるのに要る到着数</b>(ユーザー指定 2026-09-20)。
         * 道中で欠けるので全員は待たない。名簿がこれより少なければ全員で始める。
         */
        public Integer builderStartCount;
        /** 建設グループを組もうとする確率(判定 1 回あたり)。 */
        public Double builderFormChance;
        /** 建設グループを組むときにアルボラを集める半径(複数の群れから引き抜く)。 */
        public Integer builderGatherRadius;
        /** 建設グループが巣を作りに行く距離の下限・上限。 */
        public Integer builderMinDistance;
        public Integer builderMaxDistance;
        /** 建設グループの判定を行う間隔(tick)。 */
        public Integer builderCheckIntervalTicks;
        /** 小規模レイドの増援を出す巣を探す半径。 */
        public Integer reinforceRange;
        /** 1 つの巣から出す増援の最大数。 */
        public Integer reinforceCount;
    }

    public static final class Formation {
        /** 営巣が始まるのに必要な、1 か所に集まったアルボラの数。 */
        public Integer arboraRequired;
        /** アルボラを数える半径(ブロック)。 */
        public Double gatherRadius;
        /** 既にある巣からこの距離以内には新しい巣を作らない(ブロック)。 */
        public Integer minDistanceBetweenNests;
        /** プレイヤーの建築物を巻き込まないよう、プレイヤーからこの距離以内には作らない(ブロック)。 */
        public Integer minDistanceFromPlayer;
        /** 1 つのワールドに同時に存在できる巣の数。 */
        public Integer maxNests;
        /** 営巣の判定を行う間隔(tick)。 */
        public Integer checkIntervalTicks;
    }

    // --- 解決済みの値 ---

    private final int shaftDepth;
    private final int trunkBranches;
    private final int spread;
    private final int depthBand;
    private final double tunnelRadius;
    private final double entranceSlope;
    private final double deepSlope;
    private final int moundRadius;
    private final int moundHeight;
    private final double maxTunnelSlope;
    private final int tunnelSpacing;
    private final int openingRadius;
    private final double floorPlantChance;
    private final double wallPlantChance;
    private final double geoFernShare;
    private final double wander;
    private final double branchChance;
    private final double descendChance;
    private final int galleryBudget;
    private final int chamberTotal;
    private final int deadEndChambers;
    private final int deadEndHatcheries;
    private final int chamberGap;
    private final int chamberClearance;
    private final int blockBudget;
    private final int digTicksPerBlock;
    private final int arboraRequired;
    private final double gatherRadius;
    private final int minDistanceBetweenNests;
    private final int minDistanceFromPlayer;
    private final int maxNests;
    private final int checkIntervalTicks;
    private final int blocksPerTick;
    private final int eggsPerHatchery;
    private final int eggHatchIntervalTicks;
    private final int eggMaxNearby;
    private final int guardShielders;
    private final int guardArbora;
    private final double guardBuff;
    private final int storagePodsPerRoom;
    private final int ventGrowthIntervalTicks;
    private final int ventMaxCount;
    private final int ventRadius;
    private final int ventBonusPerVentPercent;
    private final double nodeCorruptionPerNode;
    private final int nodeCorruptionMaxNodes;
    private final double coreHealthChance;
    private final double coreAttackChance;
    private final double coreArmorChance;
    private final double coreHealthBonus;
    private final double coreAttackBonus;
    private final double coreArmorBonus;
    private final int queenBroodCount;
    private final int queenBroodIntervalTicks;
    private final int guardLeashRadius;
    private final int haulSearchRadius;
    private final int haulNestRadius;
    private final int builderRequired;
    private final int builderStartCount;
    private final double builderFormChance;
    private final int builderGatherRadius;
    private final int builderMinDistance;
    private final int builderMaxDistance;
    private final int builderCheckIntervalTicks;
    private final int reinforceRange;
    private final int reinforceCount;

    private NestTable(Json j) {
        Json src = j != null ? j : new Json();
        Shape s = src.shape != null ? src.shape : new Shape();
        Digging d = src.digging != null ? src.digging : new Digging();
        Decoration dec = src.decoration != null ? src.decoration : new Decoration();
        Contents c = src.contents != null ? src.contents : new Contents();
        Formation f = src.formation != null ? src.formation : new Formation();

        this.shaftDepth = clampI(s.shaftDepth, 26, 8, 120);
        this.trunkBranches = clampI(s.trunkBranches, 4, 1, 24);
        this.spread = clampI(s.spread, 34, 8, 96);
        this.depthBand = clampI(s.depthBand, 18, 2, 64);
        this.tunnelRadius = clampD(s.tunnelRadius, 1.8D, 0.8D, 4.0D);
        this.entranceSlope = clampD(s.entranceSlope, 0.45D, 0.1D, 4.0D);
        this.deepSlope = clampD(s.deepSlope, 0.75D, 0.1D, 6.0D);
        this.moundRadius = clampI(s.moundRadius, 5, 0, 24);
        this.moundHeight = clampI(s.moundHeight, 3, 0, 16);
        this.maxTunnelSlope = clampD(s.maxTunnelSlope, 0.5D, 0.05D, 3.0D);
        this.tunnelSpacing = clampI(s.tunnelSpacing, 7, 0, 32);
        this.openingRadius = clampI(s.openingRadius, 4, 0, 32);
        this.floorPlantChance = clampD(dec.floorPlantChance, 0.35D, 0.0D, 1.0D);
        this.wallPlantChance = clampD(dec.wallPlantChance, 0.10D, 0.0D, 1.0D);
        this.geoFernShare = clampD(dec.geoFernShare, 0.06D, 0.0D, 1.0D);

        this.wander = clampD(d.wander, 0.40D, 0.0D, 1.5D);
        this.branchChance = clampD(d.branchChance, 0.020D, 0.0D, 0.5D);
        this.descendChance = clampD(d.descendChance, 0.030D, 0.0D, 0.5D);
        this.galleryBudget = clampI(d.galleryBudget, 60, 10, 400);
        this.chamberTotal = clampI(d.chamberTotal, 7, 1, 64);
        this.deadEndChambers = clampI(d.deadEndChambers, 5, 1, 32);
        this.deadEndHatcheries = clampI(d.deadEndHatcheries, 2, 0, 32);
        this.chamberGap = clampI(d.chamberGap, 10, 2, 48);
        this.chamberClearance = clampI(d.chamberClearance, 3, 0, 16);
        this.blockBudget = clampI(d.blockBudget, 13_000, 500, 120_000);
        this.digTicksPerBlock = clampI(d.digTicksPerBlock, 8, 1, 400);

        this.arboraRequired = clampI(f.arboraRequired, 15, 2, 128);
        this.gatherRadius = clampD(f.gatherRadius, 16.0D, 4.0D, 96.0D);
        this.minDistanceBetweenNests = clampI(f.minDistanceBetweenNests, 192, 16, 4096);
        this.minDistanceFromPlayer = clampI(f.minDistanceFromPlayer, 48, 0, 512);
        this.maxNests = clampI(f.maxNests, 12, 1, 256);
        this.checkIntervalTicks = clampI(f.checkIntervalTicks, 200, 20, 12_000);

        this.blocksPerTick = clampI(src.blocksPerTick, 240, 8, 8_000);

        this.eggsPerHatchery = clampI(c.eggsPerHatchery, 7, 0, 64);
        this.eggHatchIntervalTicks = clampI(c.eggHatchIntervalTicks, 1_200, 20, 240_000);
        this.eggMaxNearby = clampI(c.eggMaxNearby, 6, 0, 64);
        this.guardShielders = clampI(c.guardShielders, 1, 0, 8);
        this.guardArbora = clampI(c.guardArbora, 4, 0, 16);
        this.guardBuff = clampD(c.guardBuff, 1.3D, 1.0D, 4.0D);
        this.storagePodsPerRoom = clampI(c.storagePodsPerRoom, 3, 0, 32);
        this.ventGrowthIntervalTicks = clampI(c.ventGrowthIntervalTicks, 1_200, 20, 240_000);
        this.ventMaxCount = clampI(c.ventMaxCount, 3, 0, 8);
        this.ventRadius = clampI(c.ventRadius, 16, 1, 128);
        this.ventBonusPerVentPercent = clampI(c.ventBonusPerVentPercent, 10, 0, 100);
        this.nodeCorruptionPerNode = clampD(c.nodeCorruptionPerNode, 0.05D, 0.0D, 1.0D);
        this.nodeCorruptionMaxNodes = clampI(c.nodeCorruptionMaxNodes, 10, 0, 256);
        this.coreHealthChance = clampD(c.coreHealthChance, 0.70D, 0.0D, 1.0D);
        this.coreAttackChance = clampD(c.coreAttackChance, 0.20D, 0.0D, 1.0D);
        this.coreArmorChance = clampD(c.coreArmorChance, 0.10D, 0.0D, 1.0D);
        this.coreHealthBonus = clampD(c.coreHealthBonus, 2.0D, 0.0D, 100.0D);
        this.coreAttackBonus = clampD(c.coreAttackBonus, 4.0D, 0.0D, 100.0D);
        this.coreArmorBonus = clampD(c.coreArmorBonus, 2.0D, 0.0D, 100.0D);
        this.queenBroodCount = clampI(c.queenBroodCount, 3, 0, 16);
        this.queenBroodIntervalTicks = clampI(c.queenBroodIntervalTicks, 300, 20, 24_000);
        this.guardLeashRadius = clampI(c.guardLeashRadius, 48, 4, 512);
        this.haulSearchRadius = clampI(c.haulSearchRadius, 24, 2, 128);
        this.haulNestRadius = clampI(c.haulNestRadius, 160, 8, 1024);
        this.builderRequired = clampI(c.builderRequired, 15, 2, 128);
        this.builderStartCount = clampI(c.builderStartCount, 8, 1, 128);
        this.builderFormChance = clampD(c.builderFormChance, 0.15D, 0.0D, 1.0D);
        this.builderGatherRadius = clampI(c.builderGatherRadius, 48, 8, 256);
        this.builderMinDistance = clampI(c.builderMinDistance, 96, 16, 2048);
        this.builderMaxDistance = clampI(c.builderMaxDistance, 256, 32, 4096);
        this.builderCheckIntervalTicks = clampI(c.builderCheckIntervalTicks, 400, 20, 24_000);
        this.reinforceRange = clampI(c.reinforceRange, 192, 16, 2048);
        this.reinforceCount = clampI(c.reinforceCount, 6, 0, 64);
    }

    private static int clampI(Integer v, int def, int min, int max) {
        return v != null ? Math.max(min, Math.min(max, v)) : def;
    }

    private static double clampD(Double v, double def, double min, double max) {
        return v != null ? Math.max(min, Math.min(max, v)) : def;
    }

    public int shaftDepth() {
        return shaftDepth;
    }

    public int trunkBranches() {
        return trunkBranches;
    }

    public int spread() {
        return spread;
    }

    public int depthBand() {
        return depthBand;
    }

    public double tunnelRadius() {
        return tunnelRadius;
    }

    public double entranceSlope() {
        return entranceSlope;
    }

    public double deepSlope() {
        return deepSlope;
    }

    public int moundRadius() {
        return moundRadius;
    }

    public int moundHeight() {
        return moundHeight;
    }

    public double maxTunnelSlope() {
        return maxTunnelSlope;
    }

    public int tunnelSpacing() {
        return tunnelSpacing;
    }

    public int openingRadius() {
        return openingRadius;
    }

    public double floorPlantChance() {
        return floorPlantChance;
    }

    public double wallPlantChance() {
        return wallPlantChance;
    }

    public double geoFernShare() {
        return geoFernShare;
    }

    public double wander() {
        return wander;
    }

    public double branchChance() {
        return branchChance;
    }

    public double descendChance() {
        return descendChance;
    }

    public int galleryBudget() {
        return galleryBudget;
    }

    public int chamberTotal() {
        return chamberTotal;
    }

    /** 坑道の行き止まりに置く部屋の数。掘削点の数の上限でもある。 */
    public int deadEndChambers() {
        return deadEndChambers;
    }

    /** 行き止まりの部屋のうち孵化室にする数(奥から順)。残りは貯蔵室。 */
    public int deadEndHatcheries() {
        return deadEndHatcheries;
    }

    public int chamberGap() {
        return chamberGap;
    }

    /** 部屋のまわりに必ず残す岩の厚み(ブロック)。坑道はここを掘れない。 */
    public int chamberClearance() {
        return chamberClearance;
    }

    public int blockBudget() {
        return blockBudget;
    }

    /** アルボラ 1 体が 1 マス掘るのに掛ける tick。掘る速さは「切羽の人数 ÷ この値」マス/tick。 */
    public int digTicksPerBlock() {
        return digTicksPerBlock;
    }

    public int arboraRequired() {
        return arboraRequired;
    }

    public double gatherRadius() {
        return gatherRadius;
    }

    public int minDistanceBetweenNests() {
        return minDistanceBetweenNests;
    }

    public int minDistanceFromPlayer() {
        return minDistanceFromPlayer;
    }

    public int maxNests() {
        return maxNests;
    }

    public int checkIntervalTicks() {
        return checkIntervalTicks;
    }

    public int blocksPerTick() {
        return blocksPerTick;
    }

    public int eggsPerHatchery() {
        return eggsPerHatchery;
    }

    public int eggHatchIntervalTicks() {
        return eggHatchIntervalTicks;
    }

    public int eggMaxNearby() {
        return eggMaxNearby;
    }

    public int guardShielders() {
        return guardShielders;
    }

    public int guardArbora() {
        return guardArbora;
    }

    public double guardBuff() {
        return guardBuff;
    }

    public int storagePodsPerRoom() {
        return storagePodsPerRoom;
    }

    public int ventGrowthIntervalTicks() {
        return ventGrowthIntervalTicks;
    }

    public int ventMaxCount() {
        return ventMaxCount;
    }

    public int ventRadius() {
        return ventRadius;
    }

    public int ventBonusPerVentPercent() {
        return ventBonusPerVentPercent;
    }

    public double nodeCorruptionPerNode() {
        return nodeCorruptionPerNode;
    }

    public int nodeCorruptionMaxNodes() {
        return nodeCorruptionMaxNodes;
    }

    public double coreHealthChance() {
        return coreHealthChance;
    }

    public double coreAttackChance() {
        return coreAttackChance;
    }

    public double coreArmorChance() {
        return coreArmorChance;
    }

    public double coreHealthBonus() {
        return coreHealthBonus;
    }

    public double coreAttackBonus() {
        return coreAttackBonus;
    }

    public double coreArmorBonus() {
        return coreArmorBonus;
    }

    public int queenBroodCount() {
        return queenBroodCount;
    }

    public int queenBroodIntervalTicks() {
        return queenBroodIntervalTicks;
    }

    public int guardLeashRadius() {
        return guardLeashRadius;
    }

    public int haulSearchRadius() {
        return haulSearchRadius;
    }

    public int haulNestRadius() {
        return haulNestRadius;
    }

    public int builderRequired() {
        return builderRequired;
    }

    /** 建設地で掘り始めるのに要る到着数。名簿がこれより少なければ全員で始める。 */
    public int builderStartCount() {
        return builderStartCount;
    }

    public double builderFormChance() {
        return builderFormChance;
    }

    public int builderGatherRadius() {
        return builderGatherRadius;
    }

    public int builderMinDistance() {
        return builderMinDistance;
    }

    public int builderMaxDistance() {
        return builderMaxDistance;
    }

    public int builderCheckIntervalTicks() {
        return builderCheckIntervalTicks;
    }

    public int reinforceRange() {
        return reinforceRange;
    }

    public int reinforceCount() {
        return reinforceCount;
    }

    static NestTable fromJson(Json j) {
        return new NestTable(j);
    }

    public static final NestTable DEFAULT = new NestTable(null);
}
