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
 *   <li>予算: 1 日(24000 tick)に {@code incomePerDay + incomePerChunkPerDay × 過繁茂のチャンク数} 貯まり、
 *   {@code baseCap + capPerHiveNode × ハイヴノードの数} で頭打ち。</li>
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
        public Double incomePerDay;
        public Double incomePerChunkPerDay;
        public Double baseCap;
        public Double capPerHiveNode;
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
    }

    /** フェーズで開く口の json。 */
    public static final class GatesJson {
        public Integer nursery, rooted, evolve, scout, recognize, raid, spawner, waveG4, waveG5, waveMixed, learn, learnWave;
    }

    /** false なら司令塔は見に来ない(予算も貯まらない)。 */
    public final boolean enabled;
    public final double incomePerDay;
    public final double incomePerChunkPerDay;
    public final double baseCap;
    public final double capPerHiveNode;
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
    // ---- 区切り 6: 苗床(どれも【仮】)。nurseryBase・nurseryAge = 苗床の点、costSpawn = 苗床から G4〜G8 を 1 体出すときに司令塔が払う値段
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

    /**
     * 司令塔が使うものが開くフェーズ(2026-11-14 決定。Vault「フェーズの仕組み」の作り直し): 苗床 1・根付き 2・進化 3・偵察 4・認識 5・送り込み 5・
     * 苗床のスポナー 4・送り込みの G4 4・G5〜 5・G4〜G6 の混ざった群れ 6。
     */
    public static final class Gates {
        public final int nursery, rooted, evolve, scout, recognize, raid, spawner, waveG4, waveG5, waveMixed, learn, learnWave;

        Gates(GatesJson j) {
            GatesJson s = j != null ? j : new GatesJson();
            nursery = ph(s.nursery, 1);
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
        }

        private static int ph(Integer v, int fallback) {
            return v == null ? fallback : Math.max(0, v);
        }
    }

    private DirectorTable(Json j) {
        Json s = j != null ? j : new Json();
        this.enabled = s.enabled == null || s.enabled;
        this.incomePerDay = nonNeg(s.incomePerDay, 0.0D);
        this.incomePerChunkPerDay = nonNeg(s.incomePerChunkPerDay, 0.2D);
        this.baseCap = nonNeg(s.baseCap, 20.0D);
        this.capPerHiveNode = nonNeg(s.capPerHiveNode, 40.0D);
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
        this.costSpawn = nonNeg(s.costSpawn, 12.0D);
        this.nurseryFar = nonNeg(s.nurseryFar, 0.4D);
        this.nurseryFarBlocks = Math.max(1.0D, s.nurseryFarBlocks != null ? s.nurseryFarBlocks : 96.0D);
        this.base = BaseMemoryTable.fromJson(s.base);
        this.gates = new Gates(s.gates);
        this.kills = KillMemoryTable.fromJson(s.kills);
        this.hoard = HoardTable.fromJson(s.hoard);
    }

    private static double nonNeg(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static DirectorTable fromJson(Json j) {
        return j == null ? DEFAULT : new DirectorTable(j);
    }

    /** 1 日に貯まる量(過繁茂のチャンク数から)。 */
    public double incomePerDay(int overgrownChunks) {
        return incomePerDay + incomePerChunkPerDay * Math.max(0, overgrownChunks);
    }

    /** 貯蓄の上限(ハイヴノードの数から)。 */
    public double cap(int hiveNodes) {
        return baseCap + capPerHiveNode * Math.max(0, hiveNodes);
    }
}
