package com.yayuy.overgrownvegetation.data;

/**
 * <b>ネザーの汚染の骨組み</b>の設計値({@code nether_spread.json}、2026-11-11)。使うのは {@code corruption/nether/}。
 *
 * <p>正本は Vault「ネザーの環境の作り直し 2」の「骨組みの実装仕様」。<b>ユーザー決定</b>: 根で広がり溶岩へ速い・段階 5 つ・
 * 侵入から 1 日(遅くとも 2 日)で湖・溶岩の海は上 2 段を水・下 1 段を殻・底に熱の結晶・最初の根は一番近い溶岩の海へ・
 * 水の底はソウルサンドにしない。<b>仮決め</b>: ここにある数値はすべて(1 日 = 24000 tick)。
 */
public final class NetherSpreadTable {

    public static final NetherSpreadTable DEFAULT = new NetherSpreadTable(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public Boolean enabled;
        /** 溶岩の海を含むチャンクへ前線が 1 チャンク進む時間(仮 1200 = 約 1 分)。 */
        public Integer seaTicksPerChunk;
        /** 陸だけのチャンクへ(仮 2400 = 海の 2 倍)。 */
        public Integer landTicksPerChunk;
        /** 段階 1〜5 が始まる時刻(前線が届いてからの日数。仮 0 / 0.25 / 0.5 / 0.75 / 1.5)。 */
        public double[] stageStartDays;
        /** 飛び地: 始まる段階(仮 4)・見直す間隔(tick)・1 回の確率・距離(チャンク)。 */
        public Integer leapFromStage;
        public Integer leapCheckTicks;
        public Double leapChance;
        public Integer leapMinChunks;
        public Integer leapMaxChunks;
        /** 出口からの広がりの上限(チャンク。0 = 上限なし)。 */
        public Integer maxRadiusChunks;
        /** 最初の根: 溶岩の海を探す半径(チャンク)と、1 チャンク進む時間。 */
        public Integer firstRootSearchChunks;
        public Integer firstRootTicksPerChunk;
        /** 面の奥を何段まで変えるか(まだらに min〜max)。2026-11-12 に面の奥を変えるのをやめたので使っていない(写しにあるので残す)。 */
        public Integer depthMin;
        public Integer depthMax;
        /** 床の搾られた岩のうち泥炭になる割合(段階 3 / 段階 5)。 */
        public Double peatStage3;
        public Double peatStage5;
        /** 湖の底の殻のうち熱の結晶を含む殻の割合(仮 3%)。 */
        public Double crystalChance;
        /** 殻・水が岸から沖へ 1 マス進む間隔(tick)。 */
        public Integer ringTicks;
        /** 1 tick にブロックを置き換える上限(全チャンクの合計)。 */
        public Integer blocksPerTick;
        /** 1 tick に面を読むセクション(16×16×16)の上限。 */
        public Integer sectionsPerTick;
        /** 天を支える樹の差し込み口: 湖の水面がこのマス数を超えたら(仮 256 = 1 チャンク分)。 */
        public Integer skyPillarLakeCells;
        /** くぼみの池(段階 3): くぼみに水を張る確率と、くぼみとみなす広さの上限(マス)。 */
        public Double pondChance;
        public Integer pondMaxCells;
        /** 台帳の広がりを見直す間隔(tick)。 */
        public Integer ledgerTickInterval;
        /** 胞子の仕組み(寒暖差の風、2026-11-11): 温度の下地・溶岩の割合の効き・測っていない海/陸の熱さ・段階ごとの冷え(添字 0 = 森でない〜5)。 */
        public Double thermalBase, thermalLavaGain, thermalUnknownSea, thermalUnknownLand;
        public double[] thermalCool;
        /** 発芽しやすさ = germBase + germHeatGain × 熱さ(熱い所ほど発芽しやすい)。林床の広がり: 溶岩の多いチャンク × spreadLavaMul。 */
        public Double germBase, germHeatGain, spreadLavaMul;
        /** 湖のチャンク(段階 4 以上で水面がある)の放出の倍率(湖の一押し)。 */
        public Double lakeEmitMul;
        public Integer lakeBoostMaxChunks;
        /** 覆い: 段階 1 の割合・段階 3 からの割合(表のブロックを覆われたブロックにする割合。11-12 ユーザー決定 1.0 = 全部)・泥炭の上掛けの割合・段階 1 の広がりを見直す間隔(tick)・起点から広がりきるまでの距離(ブロック)。2026-11-12 から段階 1 の間に起点から広がる。 */
        public Double coverStage1, coverStage3, coverPeat;
        public Integer coverSweepTicks, coverFrontBlocks;
        /** 起点のまわりを必ず覆う半径(ブロック。2026-11-12 ユーザー決定、仮 2)。 */
        public Double coverOriginRadius;
        /** ネザーの胞子の場の値(spore_field.json の field の写しに上書きする。名前は SporeField.Params の名前)。 */
        public java.util.Map<String, Double> spores;
        /** 湖と地面の境目のまだら: 段階を決めるチャンクを座標の揺らぎでずらす幅(ブロック。0 = ずらさない)と揺らぎの粒の大きさ(ブロック)。 */
        public Integer edgeJitterBlocks, edgeJitterScale;
        /**
         * 先駆けの植物(2026-11-12。corruption/NetherPioneerFlora。値はすべて仮): 枝の根の長さ・アーチで渡る谷の幅・浮き草の沖への広がりとまだら・
         * 太い根の出る所のまだら・搾り殺しと浮き草の見回りの間隔(tick)・1 回の呼び出しで置く上限・岩の下の海へ潜る深さの上限と傾き(下 1 に横いくつ)。
         */
        public Integer floraBranchMin, floraBranchMax, floraArchMaxGap, floraMatMin, floraMatMax, floraRaisedScale, floraHeavyTicks, floraPlaceCap, floraDiveMaxDepth;
        public Double floraMatShare, floraRaisedShare, floraDiveSlope;
        /** 段階 3 から、表に無い立方体の面のうち汎用の過繁茂ブロック(nether_overgrown_block)になる割合(2026-11-12 ユーザー決定「3 割」。残りは覆い)。 */
        public Double netherOvergrownBlockShare;
        public Integer tubeMiddleMin, tubeMiddleMax, tubeSideMin, tubeSideMax, tubeCell, tubeSidesMin, tubeSidesMax, tubeGroveScale, tubeRetractRadius, tubeFullTrees;
        public Double tubeDensityLava, tubeDensityDry, tubeGroveFrom;
    }

    public final boolean enabled;
    public final int seaTicksPerChunk;
    public final int landTicksPerChunk;
    /** 段階 1〜5 の始まり(tick)。添字 0 が段階 1。 */
    public final long[] stageStartTicks;
    public final int leapFromStage;
    public final int leapCheckTicks;
    public final double leapChance;
    public final int leapMinChunks;
    public final int leapMaxChunks;
    public final int maxRadiusChunks;
    public final int firstRootSearchChunks;
    public final int firstRootTicksPerChunk;
    public final int depthMin;
    public final int depthMax;
    public final double peatStage3;
    public final double peatStage5;
    public final double crystalChance;
    public final int ringTicks;
    public final int blocksPerTick;
    public final int sectionsPerTick;
    public final int skyPillarLakeCells;
    public final double pondChance;
    public final int pondMaxCells;
    public final int ledgerTickInterval;
    public final double thermalBase, thermalLavaGain, thermalUnknownSea, thermalUnknownLand;
    public final double[] thermalCool;
    public final double germBase, germHeatGain, spreadLavaMul;
    public final double lakeEmitMul;
    public final int lakeBoostMaxChunks;
    public final double coverStage1, coverStage3, coverPeat;
    public final int coverSweepTicks, coverFrontBlocks;
    public final double coverOriginRadius;
    public final java.util.Map<String, Double> spores;
    public final int edgeJitterBlocks, edgeJitterScale;
    public final int floraBranchMin, floraBranchMax, floraArchMaxGap, floraMatMin, floraMatMax, floraRaisedScale, floraHeavyTicks, floraPlaceCap, floraDiveMaxDepth;
    public final double floraMatShare, floraRaisedShare, floraDiveSlope;
    public final double netherOvergrownBlockShare;
    /** 管の樹(2026-11-12。corruption/NetherTubeTrees)。高さ 4〜9・3〜8 はユーザー決定(上限 9 は仮)、ほかは仮。 */
    public final int tubeMiddleMin, tubeMiddleMax, tubeSideMin, tubeSideMax, tubeCell, tubeSidesMin, tubeSidesMax, tubeGroveScale, tubeRetractRadius, tubeFullTrees;
    public final double tubeDensityLava, tubeDensityDry, tubeGroveFrom;

    private NetherSpreadTable(Json j) {
        Json s = j != null ? j : new Json();
        enabled = s.enabled == null || s.enabled;
        seaTicksPerChunk = clamp(s.seaTicksPerChunk, 1200, 1, 1_000_000);
        landTicksPerChunk = clamp(s.landTicksPerChunk, 2400, 1, 1_000_000);
        double[] days = s.stageStartDays != null && s.stageStartDays.length == 5
                ? s.stageStartDays : new double[]{0.0, 0.25, 0.5, 0.75, 1.5};
        stageStartTicks = new long[5];
        long prev = 0;
        for (int i = 0; i < 5; i++) {
            long t = Math.max(prev, Math.round(Math.max(0.0, days[i]) * 24000.0));
            stageStartTicks[i] = i == 0 ? 0 : t;
            prev = stageStartTicks[i];
        }
        leapFromStage = clamp(s.leapFromStage, 4, 1, 6);
        leapCheckTicks = clamp(s.leapCheckTicks, 6000, 20, 10_000_000);
        leapChance = s.leapChance != null ? Math.max(0, Math.min(1, s.leapChance)) : 0.0;
        leapMinChunks = clamp(s.leapMinChunks, 2, 1, 32);
        leapMaxChunks = Math.max(leapMinChunks, clamp(s.leapMaxChunks, 4, 1, 32));
        maxRadiusChunks = clamp(s.maxRadiusChunks, 128, 0, 100_000);
        firstRootSearchChunks = clamp(s.firstRootSearchChunks, 16, 0, 64);
        firstRootTicksPerChunk = clamp(s.firstRootTicksPerChunk, 1200, 1, 1_000_000);
        depthMin = clamp(s.depthMin, 1, 1, 8);
        depthMax = Math.max(depthMin, clamp(s.depthMax, 3, 1, 8));
        peatStage3 = s.peatStage3 != null ? Math.max(0, Math.min(1, s.peatStage3)) : 0.15;
        peatStage5 = s.peatStage5 != null ? Math.max(peatStage3, Math.min(1, s.peatStage5)) : 0.6;
        crystalChance = s.crystalChance != null ? Math.max(0, Math.min(1, s.crystalChance)) : 0.03;
        ringTicks = clamp(s.ringTicks, 40, 1, 24000);
        blocksPerTick = clamp(s.blocksPerTick, 512, 1, 65536);
        sectionsPerTick = clamp(s.sectionsPerTick, 2, 1, 64);
        skyPillarLakeCells = clamp(s.skyPillarLakeCells, 256, 1, 1_000_000);
        pondChance = s.pondChance != null ? Math.max(0, Math.min(1, s.pondChance)) : 0.5;
        pondMaxCells = clamp(s.pondMaxCells, 24, 1, 1024);
        ledgerTickInterval = clamp(s.ledgerTickInterval, 20, 1, 24000);
        thermalBase = d(s.thermalBase, 0.25);
        thermalLavaGain = d(s.thermalLavaGain, 2.0);
        thermalUnknownSea = d(s.thermalUnknownSea, 0.8);
        thermalUnknownLand = d(s.thermalUnknownLand, 0.3);
        thermalCool = s.thermalCool != null && s.thermalCool.length == 6 ? s.thermalCool.clone() : new double[]{1.0, 0.5, 0.35, 0.2, 0.1, 0.0};
        germBase = d(s.germBase, 0.6);
        germHeatGain = d(s.germHeatGain, 0.8);
        spreadLavaMul = d(s.spreadLavaMul, 1.5);
        lakeEmitMul = d(s.lakeEmitMul, 5.0);
        lakeBoostMaxChunks = clamp(s.lakeBoostMaxChunks, 25, 0, 100_000);
        coverStage1 = Math.min(1.0, d(s.coverStage1, 0.7));
        coverStage3 = Math.min(1.0, d(s.coverStage3, 1.0));
        coverPeat = Math.min(1.0, d(s.coverPeat, 0.3));
        coverSweepTicks = clamp(s.coverSweepTicks, 300, 20, 24000);
        coverFrontBlocks = clamp(s.coverFrontBlocks, 8, 1, 64);
        coverOriginRadius = Math.min(16.0, d(s.coverOriginRadius, 2.0));
        spores = s.spores != null ? java.util.Map.copyOf(s.spores) : java.util.Map.of();
        edgeJitterBlocks = clamp(s.edgeJitterBlocks, 6, 0, 15);
        edgeJitterScale = clamp(s.edgeJitterScale, 12, 2, 64);
        floraBranchMin = clamp(s.floraBranchMin, 4, 1, 32);
        floraBranchMax = Math.max(floraBranchMin, clamp(s.floraBranchMax, 10, 1, 32));
        floraArchMaxGap = clamp(s.floraArchMaxGap, 6, 0, 16);
        floraMatMin = clamp(s.floraMatMin, 4, 1, 16);
        floraMatMax = Math.max(floraMatMin, clamp(s.floraMatMax, 6, 1, 16));
        floraRaisedScale = clamp(s.floraRaisedScale, 3, 1, 32);
        floraHeavyTicks = clamp(s.floraHeavyTicks, 200, 1, 24000);
        floraPlaceCap = clamp(s.floraPlaceCap, 256, 1, 65536);
        floraDiveMaxDepth = clamp(s.floraDiveMaxDepth, 96, 0, 256);
        floraMatShare = Math.min(1.0, d(s.floraMatShare, 0.6));
        floraRaisedShare = Math.min(1.0, d(s.floraRaisedShare, 0.5));
        floraDiveSlope = Math.min(4.0, d(s.floraDiveSlope, 0.6));
        netherOvergrownBlockShare = Math.min(1.0, d(s.netherOvergrownBlockShare, 0.3));
        tubeMiddleMin = clamp(s.tubeMiddleMin, 4, 1, 24);
        tubeMiddleMax = Math.max(tubeMiddleMin, clamp(s.tubeMiddleMax, 9, 1, 24));
        tubeSideMin = clamp(s.tubeSideMin, 3, 1, 24);
        tubeSideMax = Math.max(tubeSideMin, clamp(s.tubeSideMax, 8, 1, 24));
        tubeCell = clamp(s.tubeCell, 3, 2, 8);
        tubeSidesMin = clamp(s.tubeSidesMin, 2, 0, 8);
        tubeSidesMax = Math.max(tubeSidesMin, clamp(s.tubeSidesMax, 5, 0, 8));
        tubeGroveScale = clamp(s.tubeGroveScale, 12, 1, 128);
        tubeRetractRadius = clamp(s.tubeRetractRadius, 4, 0, 16);
        tubeFullTrees = clamp(s.tubeFullTrees, 6, 1, 256);
        tubeDensityLava = Math.min(1.0, d(s.tubeDensityLava, 0.8));
        tubeDensityDry = Math.min(1.0, d(s.tubeDensityDry, 0.3));
        tubeGroveFrom = Math.min(0.99, d(s.tubeGroveFrom, 0.3));
    }

    private static double d(Double v, double def) {
        return v == null ? def : Math.max(0.0, v);
    }

    private static int clamp(Integer v, int def, int lo, int hi) {
        return v == null ? def : Math.max(lo, Math.min(hi, v));
    }

    public static NetherSpreadTable fromJson(Json j) {
        return j == null ? DEFAULT : new NetherSpreadTable(j);
    }

    /** 前線が届いてから {@code age} tick 経ったチャンクの段階(1〜5)。 */
    public int stageAt(long age) {
        int stage = 1;
        for (int i = 1; i < 5; i++) {
            if (age >= stageStartTicks[i]) stage = i + 1;
        }
        return stage;
    }
}
