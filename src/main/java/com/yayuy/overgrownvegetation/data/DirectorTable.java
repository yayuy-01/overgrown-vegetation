package com.yayuy.overgrownvegetation.data;

/**
 * 森の知性(司令塔)の設計値({@code director.json}、2026-11-13 第 1 段)。仕様の正本は Vault「森の知性(司令塔)」の
 * 「【決定】0.2.0 の司令塔(11-13)」。
 *
 * <p><b>ここの数はどれも【仮】</b>(ユーザーが決めたのは仕組みだけ: 予算は汚染の広さで貯まり、ハイヴノードで上限が上がる・
 * 見に来る時刻は起き上がりから数分〜数日・進化も根付きも苗床化も、なるときに予算を払う(11-13 ユーザー
 * 「根付きや苗床化、進化はすべてコストを支払うようにしよう」)。値段の大小(進化 10 > 苗床 8 > 根付き 6)も【仮】)。
 *
 * <ul>
 *   <li>予算(11-14 ユーザー決定の収支。数は【仮】): 1 日に {@code incomeBase[フェーズ] × 面積の割合}(0.3〜1.5)貯まり、
 *   {@code 1 日の収入 × (capDays + capPerHiveNodeDays × ハイヴノードの数)} で頭打ち。</li>
 *   <li>見に来る時刻: 最初は {@code firstVisitMinTicks}〜{@code firstVisitMaxTicks} の間(対数で一様 = 数分の個体も数日の個体もいる)、
 *   何もしなかったら {@code revisitMinTicks}〜{@code revisitMaxTicks} 後にまた。</li>
 *   <li>点: 何もしない = {@code nothingBase}。進化 = {@code evolveBase} + 近くのプレイヤー + 起き上がってからの日数。
 *   どれも ±{@code jitter} の揺らぎを掛けて、いちばん高い道。予算が値段に足りない道は選べない。</li>
 * </ul>
 */
public final class DirectorTable {

    public static final DirectorTable DEFAULT = new DirectorTable(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public Boolean enabled;
        public java.util.List<Double> incomeBase;
        public Double areaRatioMin;
        public Double areaRatioMax;
        public Double capDays;
        public Double capPerHiveNodeDays;
        public Double costG5;
        public Double costG6;
        public Double costEvolve;
        public Double costRooted;
        public Double costNursery;
        public Integer firstVisitMinTicks;
        public Integer firstVisitMaxTicks;
        public Integer revisitMinTicks;
        public Integer revisitMaxTicks;
        public Integer evolveOmenTicks;
        public Double nothingBase;
        public Double evolveBase;
        public Double evolvePlayerNear;
        public Double playerNearBlocks;
        public Double evolveAge;
        public Double ageRefDays;
        public Double jitter;
        public Integer rootAwayMinBlocks;
        public Integer rootAwayMaxBlocks;
        public Integer walkTimeoutTicks;
        public Integer rootOmenTicks;
        public Integer forestSearchChunks;
        public Double rootedBase;
        public Double rootedAge;
        public Double nurseryBase;
        public Double nurseryAge;
        public Double costSpawn;
        public Double nurseryFar;
        public Double nurseryFarBlocks;
        /** 第 2 段: 拠点の記憶({@link BaseMemoryTable})。 */
        public BaseMemoryTable.Json base;
        /** フェーズで開く口(DirectorGates)。 */
        public GatesJson gates;
        /** 第 3 段: 学ぶ(KillMemoryTable)。 */
        public KillMemoryTable.Json kills;
        /** 第 3 段: 貯めて一気に使う(HoardTable)。 */
        public HoardTable.Json hoard;
        /** F5 の襲撃(AssaultTable。11-16)。 */
        public AssaultTable.Json assault;
        /** 第 4 段: 大きな襲撃(LargeRaidTable)。 */
        public LargeRaidTable.Json largeRaid;
        /** 監視塔(WatcherTable)。 */
        public WatcherTable.Json watcher;
        /** 第 3 段 区切り 4: 苗床が枯れるまでに生む萌芽体の数。 */
        public Integer nurseryLifeSprouts;
        /** 11-17: 苗床が枯れるまでに孵す回数・孵してよいか司令塔へ聞く間(tick)。 */
        public Integer nurseryLifeHatches, nurseryHatchAskTicks;
        /** 11-17 深夜: 升(4×4 チャンク)ごとの根付きの上限。 */
        public Integer rootedPerCell;
    }

    /** フェーズで開く口の json。 */
    public static final class GatesJson {
        public Integer nursery, rooted, evolve, scout, recognize, raid, spawner, waveG4, waveG5, waveMixed, learn, learnWave, largeRaid, watcher;
    }

    /** false なら司令塔は見に来ない(予算も貯まらない)。 */
    public final boolean enabled;
    /** フェーズごとの 1 日の収入の基本(F0〜F10。11-14 ユーザー: F1 20 / F2 35 / F3 60 / F4 125 / F5 240 / F6 360 / F7 420。11-14 夕の決め直し【仮】。11-16 ユーザー: F5 450・F6 500・F7〜F10 550 に上げた【仮】= F5 の襲撃の予算・フェーズで下がらない)。 */
    public final double[] incomeBase;
    /** 面積の割合(今の面積 ÷ そのフェーズのふつうの広さ)を収める幅(0.3〜1.5【仮】)。 */
    public final double areaRatioMin;
    public final double areaRatioMax;
    /** 上限 = 1 日の収入 × (capDays + capPerHiveNodeDays × ハイヴノード)。1 日分 + ハイヴノード 1 つで 0.5 日分【仮】。 */
    public final double capDays;
    public final double capPerHiveNodeDays;
    /** G5・G6 を孵す値段(G4 は costSpawn)。今は使われない(G4 以降が退避中)【仮】。 */
    public final double costG5;
    public final double costG6;
    public final double costEvolve;
    public final double costRooted;
    public final double costNursery;
    public final int firstVisitMinTicks;
    public final int firstVisitMaxTicks;
    public final int revisitMinTicks;
    public final int revisitMaxTicks;
    /** 進化の前兆(立ち止まって震え、植物が膨らむ)の長さ(tick)。この間に倒せば止まる。 */
    public final int evolveOmenTicks;
    public final double nothingBase;
    public final double evolveBase;
    public final double evolvePlayerNear;
    public final double playerNearBlocks;
    public final double evolveAge;
    public final double ageRefDays;
    public final double jitter;
    // ---- 区切り 2: 森から離れて根付く(どれも【仮】)。walkTimeoutTicks = 着けないとき歩くのをやめる長さ(D-15 の 5 分)、
    // rootOmenTicks = 根を下ろす前兆の長さ、forestSearchChunks = 森の縁を探す半径(チャンク)
    public final int rootAwayMinBlocks; // 根付き・苗床になる前に森の縁から離れる長さ(ブロック)。この幅の中から個体ごとに 1 つ決める(D-15)
    public final int rootAwayMaxBlocks;
    public final int walkTimeoutTicks;
    public final int rootOmenTicks;
    public final int forestSearchChunks;
    public final double rootedBase;
    public final double rootedAge;
    // ---- 区切り 6: 苗床(どれも【仮】)。nurseryBase・nurseryAge = 苗床の点、costSpawn = 苗床から G4 を 1 体出すときに司令塔が払う値段(11-14: G4 の値段 12。G5・G6 は costG5・costG6)
    public final double nurseryBase;
    public final double nurseryAge;
    public final double costSpawn;
    /** 苗床の点に足す「森から離れているほど」の上がり幅と、頭打ちの距離(ブロック)。11-14 ユーザー決定 R6-3(数は【仮】)。 */
    public final double nurseryFar;
    public final double nurseryFarBlocks;
    /** 第 2 段(11-14): 拠点の記憶。 */
    public final BaseMemoryTable base;
    /** 11-14: フェーズで開く口(割り当てはユーザー・全体管理の決定)。 */
    public final Gates gates;
    /** 第 3 段(11-14): 学ぶ。 */
    public final KillMemoryTable kills;
    /** 第 3 段(11-14): 貯めて一気に使う。 */
    public final HoardTable hoard;
    /** F5 の襲撃(11-16): 最初の襲撃・1 回の上限・拠点を汚す・壁や扉を壊す・貯めた襲撃の倍率。 */
    public final AssaultTable assault;
    /** 第 4 段(11-14): 大きな襲撃(司令塔の襲撃の大・中・小の「大」)。 */
    public final LargeRaidTable largeRaid;
    /** 11-14: 司令塔が植える監視塔(フィロセンス)。 */
    public final WatcherTable watcher;
    /** 苗床は萌芽体をこれだけ生むか、G4 以降を 1 回孵したら枯れる(11-14 ユーザー N1。数 6 は【仮】)。 */
    public final int nurseryLifeSprouts;
    /** 苗床が枯れるまでに G4 以降を孵す回数(11-17 ユーザー「1 つにつき 1 回」をやめる。4 は【仮】)・司令塔へ聞く間(tick。前 1200、600 は【仮】)。 */
    public final int nurseryLifeHatches, nurseryHatchAskTicks;
    /** 升(4×4 チャンク)ごとの根付きの上限(11-17 深夜 ユーザー決定。根付きは枯れないので、満ちた升では進化・苗床へ回す。3 は【仮】。0 = 上限なし)。 */
    public final int rootedPerCell;

    /**
     * 司令塔が使うものが開くフェーズ(2026-11-14 決定。Vault「フェーズの仕組み」の作り直し): 苗床 1・根付き 2・進化 3・偵察 4・認識 5・送り込み 5・
     * 苗床のスポナー 4・送り込みの G4 4・G5〜 5・G4〜G6 の混ざった群れ 6。
     */
    public static final class Gates {
        public final int nursery, rooted, evolve, scout, recognize, raid, spawner, waveG4, waveG5, waveMixed, learn, learnWave, largeRaid, watcher;

        Gates(GatesJson j) {
            GatesJson s = j != null ? j : new GatesJson();
            nursery = ph(s.nursery, 2);
            rooted = ph(s.rooted, 2);
            evolve = ph(s.evolve, 3);
            scout = ph(s.scout, 4);
            recognize = ph(s.recognize, 5);
            raid = ph(s.raid, 5);
            spawner = ph(s.spawner, 4);
            waveG4 = ph(s.waveG4, 4);
            waveG5 = ph(s.waveG5, 5);
            waveMixed = ph(s.waveMixed, 6);
            learn = ph(s.learn, 3);
            learnWave = ph(s.learnWave, 5);
            largeRaid = ph(s.largeRaid, 7);
            watcher = ph(s.watcher, 4);
        }

        private static int ph(Integer v, int fallback) {
            return v == null ? fallback : Math.max(0, v);
        }
    }

    private DirectorTable(Json j) {
        Json s = j != null ? j : new Json();
        this.enabled = s.enabled == null || s.enabled;
        double[] base = {0, 20, 35, 60, 125, 450, 500, 550, 550, 550, 550};
        if (s.incomeBase != null) {
            for (int i = 0; i < Math.min(base.length, s.incomeBase.size()); i++) base[i] = nonNeg(s.incomeBase.get(i), base[i]);
        }
        this.incomeBase = base;
        this.areaRatioMin = nonNeg(s.areaRatioMin, 0.3D);
        this.areaRatioMax = Math.max(this.areaRatioMin, nonNeg(s.areaRatioMax, 1.5D));
        this.capDays = nonNeg(s.capDays, 1.0D);
        this.capPerHiveNodeDays = nonNeg(s.capPerHiveNodeDays, 0.5D);
        this.costG5 = nonNeg(s.costG5, 20.0D);
        this.costG6 = nonNeg(s.costG6, 30.0D);
        this.costEvolve = nonNeg(s.costEvolve, 10.0D);
        this.costRooted = nonNeg(s.costRooted, 6.0D);
        this.costNursery = nonNeg(s.costNursery, 8.0D);
        this.firstVisitMinTicks = Math.max(1, s.firstVisitMinTicks != null ? s.firstVisitMinTicks : 2400);
        this.firstVisitMaxTicks = Math.max(this.firstVisitMinTicks, s.firstVisitMaxTicks != null ? s.firstVisitMaxTicks : 72000);
        this.revisitMinTicks = Math.max(1, s.revisitMinTicks != null ? s.revisitMinTicks : 6000);
        this.revisitMaxTicks = Math.max(this.revisitMinTicks, s.revisitMaxTicks != null ? s.revisitMaxTicks : 24000);
        this.evolveOmenTicks = Math.max(1, s.evolveOmenTicks != null ? s.evolveOmenTicks : 60);
        this.nothingBase = nonNeg(s.nothingBase, 1.0D);
        this.evolveBase = nonNeg(s.evolveBase, 0.8D);
        this.evolvePlayerNear = nonNeg(s.evolvePlayerNear, 0.4D);
        this.playerNearBlocks = Math.max(1.0D, s.playerNearBlocks != null ? s.playerNearBlocks : 48.0D);
        this.evolveAge = nonNeg(s.evolveAge, 0.3D);
        this.ageRefDays = Math.max(0.01D, s.ageRefDays != null ? s.ageRefDays : 3.0D);
        this.jitter = Math.min(0.9D, nonNeg(s.jitter, 0.2D));
        this.rootAwayMinBlocks = Math.max(0, s.rootAwayMinBlocks != null ? s.rootAwayMinBlocks : 48);
        this.rootAwayMaxBlocks = Math.max(this.rootAwayMinBlocks, s.rootAwayMaxBlocks != null ? s.rootAwayMaxBlocks : 96);
        this.walkTimeoutTicks = Math.max(1, s.walkTimeoutTicks != null ? s.walkTimeoutTicks : 6000);
        this.rootOmenTicks = Math.max(1, s.rootOmenTicks != null ? s.rootOmenTicks : 40);
        this.forestSearchChunks = Math.max(1, Math.min(16, s.forestSearchChunks != null ? s.forestSearchChunks : 8));
        this.rootedBase = nonNeg(s.rootedBase, 0.7D);
        this.rootedAge = nonNeg(s.rootedAge, 0.2D);
        this.nurseryBase = nonNeg(s.nurseryBase, 0.5D);
        this.nurseryAge = nonNeg(s.nurseryAge, 0.2D);
        this.costSpawn = nonNeg(s.costSpawn, 8.0D);
        this.nurseryFar = nonNeg(s.nurseryFar, 0.4D);
        this.nurseryFarBlocks = Math.max(1.0D, s.nurseryFarBlocks != null ? s.nurseryFarBlocks : 96.0D);
        this.base = BaseMemoryTable.fromJson(s.base);
        this.gates = new Gates(s.gates);
        this.kills = KillMemoryTable.fromJson(s.kills);
        this.hoard = HoardTable.fromJson(s.hoard);
        this.assault = AssaultTable.fromJson(s.assault);
        this.largeRaid = LargeRaidTable.fromJson(s.largeRaid);
        this.watcher = WatcherTable.fromJson(s.watcher);
        this.nurseryLifeSprouts = Math.max(1, s.nurseryLifeSprouts != null ? s.nurseryLifeSprouts : 6);
        this.nurseryLifeHatches = Math.max(1, s.nurseryLifeHatches != null ? s.nurseryLifeHatches : 4);
        this.nurseryHatchAskTicks = Math.max(20, s.nurseryHatchAskTicks != null ? s.nurseryHatchAskTicks : 600);
        this.rootedPerCell = Math.max(0, s.rootedPerCell != null ? s.rootedPerCell : 3);
    }

    private static double nonNeg(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static DirectorTable fromJson(Json j) {
        return j == null ? DEFAULT : new DirectorTable(j);
    }

    /** 1 日に貯まる量 = フェーズの基本 × 面積の割合(割合は幅に収める)。 */
    public double income(int phase, double areaRatio) {
        double r = Math.max(areaRatioMin, Math.min(areaRatioMax, areaRatio));
        return incomeBase[Math.max(0, Math.min(incomeBase.length - 1, phase))] * r;
    }

    /** 貯蓄の上限 = 1 日の収入 × (1 日分 + ハイヴノードごとに 0.5 日分)。 */
    public double cap(double incomePerDay, int hiveNodes) {
        return incomePerDay * (capDays + capPerHiveNodeDays * Math.max(0, hiveNodes));
    }

    /** 孵す Mob のグループの値段(G5・G6。ほかは G4 の値段 costSpawn)。 */
    public double spawnCost(int group) {
        return group >= 6 ? costG6 : group == 5 ? costG5 : costSpawn;
    }
}
