package com.yayuy.overgrownvegetation.data;

/**
 * 司令塔の「拠点の記憶」の設計値({@code director.json} の {@code base}、2026-11-14 第 2 段 区切り 1)。
 * 仕様の正本は Vault「森の知性(司令塔)」D-7・D-12 と第 2 段の問いの答え(11-14)。
 *
 * <p>ユーザーが決めたのは仕組みと次の日数だけ: 点の元 = プレイヤーのいた時間 + ベッド・入れ物・換気機・洗い場・かまど類・作業台、
 * 閉じた部屋の中にいた時間は多めに数える(Q2 = 3)/ 1 日いないと減り始め、認識した拠点は 5 日、覚えただけの拠点は 2 日いなければ忘れる(Q6 = 1)。
 * <b>ほかの数はどれも【仮】</b>。
 *
 * <ul>
 *   <li>点の単位は「ゲーム内の時間」(1 = 1 時間 = 1000 tick いた)。{@code sampleTicks} ごとに、いる升へ {@code sampleTicks / 1000} を足す
 *   (閉じた部屋の中なら × {@code roomFactor})。1 つの升のいた時間は {@code presenceCapHours} で頭打ち。</li>
 *   <li>置いた物(ブロックのタグ {@code overgrown_vegetation:base_structure})1 つ = {@code structureHours} 時間ぶん。1 つの升で {@code structureCap} 個まで。
 *   数えるのはプレイヤーがいる升の、足元から上下 {@code scanHalfHeight} の中だけ。</li>
 *   <li>升どうしが {@code clusterChunks} 以内でつながっていれば 1 つの拠点と見る。点の合計が {@code rememberScore} を超えたら「覚えた」。</li>
 * </ul>
 */
public final class BaseMemoryTable {

    public static final BaseMemoryTable DEFAULT = new BaseMemoryTable(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public Boolean enabled;
        public Integer sampleTicks;
        public Double roomFactor;
        public Double presenceCapHours;
        public Double structureHours;
        public Integer structureCap;
        public Integer scanHalfHeight;
        public Integer clusterChunks;
        public Double rememberScore;
        public Double decayStartDays;
        public Double forgetRecognizedDays;
        public Double forgetRememberedDays;
        public Double matchBlocks;
        public Double scoutWatchMin;
        public Double scoutWatchMax;
        public Integer scoutWatchMinTicks;
        public Integer scoutWatchMaxTicks;
        public Integer scoutGoTimeoutTicks;
        public Integer scoutLeaveTicks;
        public Integer scoutLeaveBlocks;
        public Double scoutSearchBlocks;
        public Double scoutFromMinBlocks;
        public Double scoutIntervalMinDays;
        public Double scoutIntervalMaxDays;
        public Double scoutRetryDays;
        public Double scoutQuietIntervalMinDays;
        public Double scoutQuietIntervalMaxDays;
        public Double scoutFirstDays;
        public Double recognizeMinDays;
        public Double recognizeMaxDays;
        public Double killAdvanceDays;
        public Boolean scoutSpawnEnabled;
        public Double scoutSpawnMinBlocks;
        public Double scoutSpawnMaxBlocks;
        public Double scoutSpawnSpreadDeg;
        public Integer scoutSpawnTries;
        public Double scoutSpawnPlayerMinBlocks;
        public Double scoutSpawnCost;
        public Double marchArriveBlocks;
        public Integer marchTimeoutTicks;
        public Double raidSearchBlocks;
        public Double raidFromMinBlocks;
        public Double waveIntervalMinDays;
        public Double waveIntervalMaxDays;
        public java.util.List<WaveJson> waves;
        public Double evolveTowardBase;
        public Double evolveBaseBlocks;
        public Double rootTowardBase;
        public Double rootBaseBlocks;
        public Double rootBaseMinBlocks;
        public Double farNurserySkip;
        public Integer weakSectors;
        public Integer weakSamplesPerSector;
        public Double weakRingMin;
        public Double weakRingMax;
        public Double weakDangerWeight;
        public Double weakRefreshDays;
        public Double flankRadius;
        public Double flankArriveBlocks;
        public Double flankShare;
    }

    /** 送り込みの段 1 つ(json)。 */
    public static final class WaveJson {
        public Double fromDays;
        public Integer stageZero;
        public Integer developed;
        public Integer g2;
        public Integer g3plus;
    }

    public final boolean enabled;
    public final int sampleTicks;
    public final double roomFactor;
    public final double presenceCapHours;
    public final double structureHours;
    public final int structureCap;
    public final int scanHalfHeight;
    public final int clusterChunks;
    public final double rememberScore;
    /** いない日がこれを超えると点が減り始める(日)。11-14 ユーザー決定 = 1。 */
    public final double decayStartDays;
    /** 認識した拠点を忘れるまでの、いない日(日)。11-14 ユーザー決定 = 5。 */
    public final double forgetRecognizedDays;
    /** 覚えただけの拠点(と、拠点になっていない升)を忘れるまでの、いない日(日)。11-14 ユーザー決定 = 2。 */
    public final double forgetRememberedDays;
    /** 点を数え直したとき、前の拠点と同じものと見る真ん中どうしの距離(ブロック)。 */
    public final double matchBlocks;
    // ---- 区切り 2: 偵察(【仮】。ユーザーが決めたのは recognizeMin〜MaxDays = 1〜2 日と「倒すと認識が進む」の向きだけ)
    /** 眺める場所 = 拠点の真ん中からこの幅(ブロック)。 */
    public final double scoutWatchMin;
    public final double scoutWatchMax;
    /** 眺める長さ(tick)。 */
    public final int scoutWatchMinTicks;
    public final int scoutWatchMaxTicks;
    /** 眺める場所に着けないとき、諦めて帰るまで(tick)。 */
    public final int scoutGoTimeoutTicks;
    /** 去るとき、これだけ歩くか、拠点からこれだけ離れたら使いは終わり。 */
    public final int scoutLeaveTicks;
    public final int scoutLeaveBlocks;
    /** 偵察に出す段階 0 を探す半径と、拠点に近すぎて選ばない半径(ブロック。拠点の外から来させる)。 */
    public final double scoutSearchBlocks;
    public final double scoutFromMinBlocks;
    /** 偵察を出してから次の偵察までの間(日)。11-14 ユーザー「偵察の間隔は 10 分に一度くらい」= ゲーム内 0.5 日を真ん中に 0.4〜0.6。F5 から(覚えた・偵察済みの拠点)。 */
    public final double scoutIntervalMinDays;
    public final double scoutIntervalMaxDays;
    public final double scoutRetryDays;
    /** F4 の間(認識がまだ開いていない)の偵察の間(日)。11-14 ユーザー「f4 の時は 1 日に一回歩かないかくらい」= 1〜2 日(平均 1.5 日。来ない日もある)。 */
    public final double scoutQuietIntervalMinDays;
    public final double scoutQuietIntervalMaxDays;
    /** 覚えてから最初の偵察を出してよくなるまで(日)【仮】。 */
    public final double scoutFirstDays;
    /** 偵察済みから認識まで(日。拠点ごとにこの幅から 1 つ)。11-14 ユーザー決定「1〜2 日」。 */
    public final double recognizeMinDays;
    public final double recognizeMaxDays;
    /** 偵察を 1 体倒すごとに、認識が早まる日数(11-14 ユーザー決定「倒すと逆に認識が進む」。量は【仮】)。 */
    public final double killAdvanceDays;
    /** まわりに段階 0 がいないとき、森の方角から偵察を湧かせる(11-14 ユーザー決定 Q3 = 2「森のある方向から」。数は【仮】)。 */
    public final boolean scoutSpawnEnabled;
    /** 拠点の真ん中から森の方角へ、この幅の所に出す(ブロック)。 */
    public final double scoutSpawnMinBlocks;
    public final double scoutSpawnMaxBlocks;
    /** 森の方角から振る角度(度)と、試す数。 */
    public final double scoutSpawnSpreadDeg;
    public final int scoutSpawnTries;
    /** どのプレイヤーからもこれより遠い所にだけ出す(見通せる所にも出さない)。 */
    public final double scoutSpawnPlayerMinBlocks;
    /** 湧かせるときに森の予算から払う値段。 */
    public final double scoutSpawnCost;
    // ---- 区切り 3: 認識の後の送り込み(どれも【仮】。ユーザーが決めたのは「徐々に強い個体が攻めてくる」の向きと、昼も夜も向かうこと)
    /** 拠点の真ん中からこれだけまで来たら、歩きの使いは終わり(そこからはいつもの狙い方)。歩いても着かなければ marchTimeoutTicks で終わり。 */
    public final double marchArriveBlocks;
    public final int marchTimeoutTicks;
    /** 送り込む個体を探す半径と、拠点に近すぎて選ばない半径(ブロック)。 */
    public final double raidSearchBlocks;
    public final double raidFromMinBlocks;
    /** 送り込みの間(日)。 */
    public final double waveIntervalMinDays;
    public final double waveIntervalMaxDays;
    /** 送り込みの段(認識してからの日数の小さい順)。 */
    public final java.util.List<Wave> waves;
    /** 進化の点に足す「認識した拠点に近いほど」の上がり幅と、届く距離(D-8)。 */
    public final double evolveTowardBase;
    public final double evolveBaseBlocks;
    /** 根付き: 森から離れる向きに混ぜる「拠点へ寄る」の重みと、拠点を見る距離・拠点に近すぎて根付かない距離(D-16)。 */
    public final double rootTowardBase;
    public final double rootBaseBlocks;
    public final double rootBaseMinBlocks;
    /** 区切り 4: 認識した拠点があるとき、拠点から遠い苗床が孵すのを断る割合【仮】。 */
    public final double farNurserySkip;
    // ---- 第 3 段 区切り 2: 弱い所を突く(どれも【仮】。ユーザーが決めたのは「明るさ + 危ない所の方位で見る」「汚染そのものは伸ばさない」)
    /** 拠点のまわりを分ける方位の数と、1 方位で明るさを読む点の数。 */
    public final int weakSectors;
    public final int weakSamplesPerSector;
    /** 明るさを読む輪(拠点の真ん中から、ブロック)。 */
    public final double weakRingMin;
    public final double weakRingMax;
    /** 方位に危ない所があるとき、守りに足す量(明るさの平均は 0〜1)。 */
    public final double weakDangerWeight;
    /** 守りを見直す間(日)。 */
    public final double weakRefreshDays;
    /** 回り込むとき、まず向かう輪の上の点(拠点の真ん中から、ブロック)と、そこに着いたと見る距離。 */
    public final double flankRadius;
    public final double flankArriveBlocks;
    /** 回り込む割合(最盛で)。段の強さを掛ける(11-14 ユーザー)。 */
    public final double flankShare;

    /**
     * 送り込みの段 1 つ: 認識してから {@code fromDays} 日たったら、1 回の送り込みで 段階 0・進化体・G2・G3 以降をこの数ずつ送る。
     * 進化体が足りなければ、段階 0 を予算で進化させて送る。G2・G3 以降は退避中なので、今は出せない(口だけ)。
     */
    public record Wave(double fromDays, int stageZero, int developed, int g2, int g3plus) {
        /** 合わせた体数(11-20 夜)。 */
        public int total() {
            return stageZero + developed + g2 + g3plus;
        }
    }

    /** 認識してからの日数で、今の段。 */
    public Wave waveFor(double daysSinceRecognized) {
        Wave w = waves.get(0);
        for (Wave c : waves) {
            if (daysSinceRecognized + 1.0E-9D >= c.fromDays()) w = c;
        }
        return w;
    }

    /** 今の段の番号(1 から)。 */
    public int waveIndex(double daysSinceRecognized) {
        int i = 0;
        for (int k = 0; k < waves.size(); k++) {
            if (daysSinceRecognized + 1.0E-9D >= waves.get(k).fromDays()) i = k;
        }
        return i + 1;
    }

    private BaseMemoryTable(Json j) {
        Json s = j != null ? j : new Json();
        this.enabled = s.enabled == null || s.enabled;
        this.sampleTicks = Math.max(20, s.sampleTicks != null ? s.sampleTicks : 400);
        this.roomFactor = nonNeg(s.roomFactor, 2.0D);
        this.presenceCapHours = Math.max(1.0D, nonNeg(s.presenceCapHours, 48.0D));
        this.structureHours = nonNeg(s.structureHours, 2.0D);
        this.structureCap = Math.max(0, s.structureCap != null ? s.structureCap : 16);
        this.scanHalfHeight = Math.max(1, Math.min(32, s.scanHalfHeight != null ? s.scanHalfHeight : 8));
        this.clusterChunks = Math.max(0, Math.min(4, s.clusterChunks != null ? s.clusterChunks : 2));
        this.rememberScore = Math.max(0.1D, nonNeg(s.rememberScore, 16.0D));
        this.decayStartDays = nonNeg(s.decayStartDays, 1.0D);
        this.forgetRecognizedDays = Math.max(this.decayStartDays + 0.01D, nonNeg(s.forgetRecognizedDays, 5.0D));
        this.forgetRememberedDays = Math.max(this.decayStartDays + 0.01D, nonNeg(s.forgetRememberedDays, 2.0D));
        this.matchBlocks = Math.max(16.0D, nonNeg(s.matchBlocks, 64.0D));
        this.scoutWatchMin = Math.max(4.0D, nonNeg(s.scoutWatchMin, 16.0D));
        this.scoutWatchMax = Math.max(this.scoutWatchMin, nonNeg(s.scoutWatchMax, 32.0D));
        this.scoutWatchMinTicks = Math.max(1, s.scoutWatchMinTicks != null ? s.scoutWatchMinTicks : 400);
        this.scoutWatchMaxTicks = Math.max(this.scoutWatchMinTicks, s.scoutWatchMaxTicks != null ? s.scoutWatchMaxTicks : 800);
        this.scoutGoTimeoutTicks = Math.max(20, s.scoutGoTimeoutTicks != null ? s.scoutGoTimeoutTicks : 3600);
        this.scoutLeaveTicks = Math.max(20, s.scoutLeaveTicks != null ? s.scoutLeaveTicks : 600);
        this.scoutLeaveBlocks = Math.max(8, s.scoutLeaveBlocks != null ? s.scoutLeaveBlocks : 48);
        this.scoutSearchBlocks = Math.max(16.0D, nonNeg(s.scoutSearchBlocks, 128.0D));
        this.scoutFromMinBlocks = Math.min(this.scoutSearchBlocks, nonNeg(s.scoutFromMinBlocks, 40.0D));
        this.scoutIntervalMinDays = Math.max(0.01D, nonNeg(s.scoutIntervalMinDays, 0.4D));
        this.scoutIntervalMaxDays = Math.max(this.scoutIntervalMinDays, nonNeg(s.scoutIntervalMaxDays, 0.6D));
        this.scoutQuietIntervalMinDays = Math.max(0.01D, nonNeg(s.scoutQuietIntervalMinDays, 1.0D));
        this.scoutQuietIntervalMaxDays = Math.max(this.scoutQuietIntervalMinDays, nonNeg(s.scoutQuietIntervalMaxDays, 2.0D));
        this.scoutFirstDays = nonNeg(s.scoutFirstDays, 0.25D);
        this.scoutRetryDays = Math.max(0.005D, nonNeg(s.scoutRetryDays, 0.05D));
        this.recognizeMinDays = nonNeg(s.recognizeMinDays, 1.0D);
        this.recognizeMaxDays = Math.max(this.recognizeMinDays, nonNeg(s.recognizeMaxDays, 2.0D));
        this.killAdvanceDays = nonNeg(s.killAdvanceDays, 0.5D);
        this.scoutSpawnEnabled = s.scoutSpawnEnabled == null || s.scoutSpawnEnabled;
        this.scoutSpawnMinBlocks = Math.max(16.0D, nonNeg(s.scoutSpawnMinBlocks, 64.0D));
        this.scoutSpawnMaxBlocks = Math.max(this.scoutSpawnMinBlocks, nonNeg(s.scoutSpawnMaxBlocks, 96.0D));
        this.scoutSpawnSpreadDeg = Math.min(180.0D, nonNeg(s.scoutSpawnSpreadDeg, 30.0D));
        this.scoutSpawnTries = Math.max(1, Math.min(32, s.scoutSpawnTries != null ? s.scoutSpawnTries : 8));
        this.scoutSpawnPlayerMinBlocks = nonNeg(s.scoutSpawnPlayerMinBlocks, 48.0D);
        this.scoutSpawnCost = nonNeg(s.scoutSpawnCost, 300.0D);
        this.marchArriveBlocks = Math.max(2.0D, nonNeg(s.marchArriveBlocks, 10.0D));
        this.marchTimeoutTicks = Math.max(20, s.marchTimeoutTicks != null ? s.marchTimeoutTicks : 6000);
        this.raidSearchBlocks = Math.max(16.0D, nonNeg(s.raidSearchBlocks, 128.0D));
        this.raidFromMinBlocks = Math.min(this.raidSearchBlocks, nonNeg(s.raidFromMinBlocks, 24.0D));
        this.waveIntervalMinDays = Math.max(0.01D, nonNeg(s.waveIntervalMinDays, 1.0D));
        this.waveIntervalMaxDays = Math.max(this.waveIntervalMinDays, nonNeg(s.waveIntervalMaxDays, 2.0D));
        this.waves = readWaves(s.waves);
        this.evolveTowardBase = nonNeg(s.evolveTowardBase, 0.4D);
        this.evolveBaseBlocks = Math.max(1.0D, nonNeg(s.evolveBaseBlocks, 160.0D));
        this.rootTowardBase = nonNeg(s.rootTowardBase, 0.8D);
        this.rootBaseBlocks = Math.max(1.0D, nonNeg(s.rootBaseBlocks, 192.0D));
        this.rootBaseMinBlocks = nonNeg(s.rootBaseMinBlocks, 24.0D);
        this.farNurserySkip = Math.min(1.0D, nonNeg(s.farNurserySkip, 0.5D));
        this.weakSectors = Math.max(4, Math.min(16, s.weakSectors != null ? s.weakSectors : 8));
        this.weakSamplesPerSector = Math.max(1, Math.min(64, s.weakSamplesPerSector != null ? s.weakSamplesPerSector : 12));
        this.weakRingMin = nonNeg(s.weakRingMin, 24.0D);
        this.weakRingMax = Math.max(this.weakRingMin + 1.0D, nonNeg(s.weakRingMax, 64.0D));
        this.weakDangerWeight = nonNeg(s.weakDangerWeight, 1.0D);
        this.weakRefreshDays = Math.max(0.01D, nonNeg(s.weakRefreshDays, 0.25D));
        this.flankRadius = nonNeg(s.flankRadius, 40.0D);
        this.flankArriveBlocks = Math.max(2.0D, nonNeg(s.flankArriveBlocks, 8.0D));
        this.flankShare = Math.min(1.0D, nonNeg(s.flankShare, 1.0D));
    }

    /** 既定の段【仮】: 最初は段階 0 数体と G2、日がたつと進化体が混ざって増え、G2 以降も増える(11-14 ユーザー「徐々に強い個体が攻めてくる」)。 */
    private static java.util.List<Wave> readWaves(java.util.List<WaveJson> js) {
        java.util.List<Wave> out = new java.util.ArrayList<>();
        if (js != null) {
            for (WaveJson w : js) {
                if (w == null) continue;
                out.add(new Wave(nonNeg(w.fromDays, 0.0D), cnt(w.stageZero), cnt(w.developed), cnt(w.g2), cnt(w.g3plus)));
            }
        }
        if (out.isEmpty()) {
            // 11-16 ユーザー: 1 回 10 体から日がたつほど増えて最大 40 体。G1・G2 から始まり G3 が少しずつ混ざる(段の分け方は【仮】)
            out.add(new Wave(0.0D, 5, 1, 4, 0));
            out.add(new Wave(1.0D, 6, 2, 6, 1));
            out.add(new Wave(2.0D, 7, 3, 7, 3));
            out.add(new Wave(4.0D, 9, 4, 10, 7));
            out.add(new Wave(6.0D, 11, 5, 13, 11));
        }
        out.sort(java.util.Comparator.comparingDouble(Wave::fromDays));
        return java.util.List.copyOf(out);
    }

    private static int cnt(Integer v) {
        return v == null ? 0 : Math.max(0, Math.min(32, v));
    }

    private static double nonNeg(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static BaseMemoryTable fromJson(Json j) {
        return j == null ? DEFAULT : new BaseMemoryTable(j);
    }

    /**
     * いない日の長さから、点に掛ける倍率(1 → 0)。{@code decayStartDays} までは 1、そこから {@code forgetDays} で 0 へまっすぐ下がる。
     * 0 になったら忘れる。
     */
    public double keep(double absentDays, double forgetDays) {
        if (absentDays <= decayStartDays) return 1.0D;
        return Math.max(0.0D, 1.0D - (absentDays - decayStartDays) / Math.max(1.0E-6D, forgetDays - decayStartDays));
    }
}
