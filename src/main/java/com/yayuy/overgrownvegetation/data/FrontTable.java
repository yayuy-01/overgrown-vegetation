package com.yayuy.overgrownvegetation.data;

/**
 * 前線と部隊の設計値({@code front.json}、2026-11-21)。正本は Vault「前線と部隊(地図の上の森の軍勢)(11-21)」。
 * ユーザーが決めたのは仕組みと「1 部隊 10〜50 体・フェーズで上限が上がる」「襲う・狩るは歩きの半分」「山は半分の速さ」。ほかの数は【仮】。
 */
public final class FrontTable {

    public static final FrontTable DEFAULT = new FrontTable(null);

    public static final class Json {
        public Boolean enabled;
        public Double unitSpeed, mountainSpeedFactor;
        public Integer unitMin;
        public int[] unitMaxByPhase;
        public Double producePerFrontPerSecond, reserveCapShare;
        public double[] plantShareByPhase;
        public Double defendShareQuiet, defendShareAttacked, expandShareQuiet;
        public Double attackedLossShare, attackDecayDays;
        public Integer postRadiusChunks, expandSearchChunks;
        public Double mergeBlocks, assignIntervalSeconds, expandIntervalSeconds;
        public int[] huntMaxByPhase;
        public Double huntRangeBlocks, huntCooldownSeconds, huntLostSeconds, huntSearchSeconds;
        public Boolean raidsFromFronts;
        public Double raidShareSmall, raidShareMedium, raidShareLarge, raidGatherBlocks, raidGatherMaxDays, raidRangeBlocks, repelGrowth, repelMaxFactor, raidLossRepelShare;
    }

    /** 切ると前線と部隊を動かさない(古い仕組みのまま)。 */
    public final boolean enabled;
    /** 部隊の地図の上の速さ(ブロック / tick。歩きの半分ほど)・山の升の倍率。 */
    public final double unitSpeed, mountainSpeedFactor;
    /** 1 部隊の体数の下限(これに届いたら持ち場・目的へ出す)と、フェーズごとの上限(F0〜F10)。 */
    public final int unitMin;
    private final int[] unitMaxByPhase;
    /** 前線 1 つが 1 秒に作れる体数の上限・作るのは予算が上限のこの割合より多い分だけ(残りは器官・巨大樹・進化に)。 */
    public final double producePerFrontPerSecond, reserveCapShare;
    /** 作る中の G2 以降(眷属種・植物種)の割合(F0〜F10。残りは段階 0 の感染 Mob)。 */
    private final double[] plantShareByPhase;
    /** 守りに置く割合(静かな前線・攻められた前線)・静かな前線で広げるに出す割合(残りは襲う = 区切り 3)。 */
    public final double defendShareQuiet, defendShareAttacked, expandShareQuiet;
    /** 攻められた: 最近倒された値段が前線の値段のこの割合を超えた。最近 = 1 日にこの日数で半分に減る。 */
    public final double attackedLossShare, attackDecayDays;
    /** 守りの持ち場を置く前線のまわり(チャンク)・広げる升を探す前線のまわり(チャンク)。 */
    public final int postRadiusChunks, expandSearchChunks;
    /** 同じ目的の部隊が合わさる距離・割り振りを見直す間(秒)・広げる部隊が升を食べる間(秒)。 */
    public final double mergeBlocks, assignIntervalSeconds, expandIntervalSeconds;
    /** 狩り(区切り 3): フェーズごとの上限の体数(F0〜F10。ユーザー了承の仮 F1 5〜F7 60)・部隊を探す広さ・同じプレイヤーにあける秒・見失ってから探しに行くまで・最後に見た所で探す秒。 */
    private final int[] huntMaxByPhase;
    public final double huntRangeBlocks, huntCooldownSeconds, huntLostSeconds, huntSearchSeconds;
    /** 襲撃(区切り 3): 前線から襲う部隊を出す(切ると古い湧き)。出せる頭数のうち 小・中・大 で出す割合・集まる所の拠点からの距離・集まって待つ最長の日数・部隊を探す広さ・撃退 1 回で次を大きくする割合・その上限の倍率・撃退とみなす倒され方(出した頭数の割合)。 */
    public final boolean raidsFromFronts;
    public final double raidShareSmall, raidShareMedium, raidShareLarge, raidGatherBlocks, raidGatherMaxDays, raidRangeBlocks, repelGrowth, repelMaxFactor, raidLossRepelShare;

    private FrontTable(Json j) {
        Json s = j != null ? j : new Json();
        enabled = s.enabled == null || s.enabled;
        unitSpeed = nn(s.unitSpeed, 0.06D);
        mountainSpeedFactor = Math.min(1.0D, nn(s.mountainSpeedFactor, 0.5D));
        unitMin = Math.max(1, s.unitMin != null ? s.unitMin : 10);
        unitMaxByPhase = s.unitMaxByPhase != null && s.unitMaxByPhase.length > 0 ? s.unitMaxByPhase.clone()
                : new int[]{10, 10, 15, 20, 25, 30, 40, 50, 50, 50, 50};
        producePerFrontPerSecond = nn(s.producePerFrontPerSecond, 0.5D);
        reserveCapShare = Math.min(1.0D, nn(s.reserveCapShare, 0.2D));
        plantShareByPhase = s.plantShareByPhase != null && s.plantShareByPhase.length > 0 ? s.plantShareByPhase.clone()
                : new double[]{0.0D, 0.05D, 0.1D, 0.2D, 0.3D, 0.4D, 0.5D, 0.6D, 0.6D, 0.6D, 0.6D};
        defendShareQuiet = Math.min(1.0D, nn(s.defendShareQuiet, 0.4D));
        defendShareAttacked = Math.min(1.0D, nn(s.defendShareAttacked, 0.9D));
        expandShareQuiet = Math.min(1.0D, nn(s.expandShareQuiet, 0.4D));
        attackedLossShare = nn(s.attackedLossShare, 0.15D);
        attackDecayDays = Math.max(0.01D, nn(s.attackDecayDays, 0.5D));
        postRadiusChunks = Math.max(0, s.postRadiusChunks != null ? s.postRadiusChunks : 8);
        expandSearchChunks = Math.max(1, s.expandSearchChunks != null ? s.expandSearchChunks : 24);
        mergeBlocks = nn(s.mergeBlocks, 24.0D);
        assignIntervalSeconds = Math.max(1.0D, nn(s.assignIntervalSeconds, 10.0D));
        expandIntervalSeconds = Math.max(1.0D, nn(s.expandIntervalSeconds, 20.0D));
        huntMaxByPhase = s.huntMaxByPhase != null && s.huntMaxByPhase.length > 0 ? s.huntMaxByPhase.clone() : new int[]{0, 5, 10, 15, 20, 30, 45, 60, 60, 60, 60};
        huntRangeBlocks = nn(s.huntRangeBlocks, 384.0D);
        huntCooldownSeconds = nn(s.huntCooldownSeconds, 180.0D);
        huntLostSeconds = nn(s.huntLostSeconds, 30.0D);
        huntSearchSeconds = nn(s.huntSearchSeconds, 30.0D);
        raidsFromFronts = s.raidsFromFronts == null || s.raidsFromFronts;
        raidShareSmall = Math.min(1.0D, nn(s.raidShareSmall, 0.3D));
        raidShareMedium = Math.min(1.0D, nn(s.raidShareMedium, 0.7D));
        raidShareLarge = Math.min(1.0D, nn(s.raidShareLarge, 1.0D));
        raidGatherBlocks = nn(s.raidGatherBlocks, 96.0D);
        raidGatherMaxDays = nn(s.raidGatherMaxDays, 1.0D);
        raidRangeBlocks = nn(s.raidRangeBlocks, 2048.0D);
        repelGrowth = nn(s.repelGrowth, 0.25D);
        repelMaxFactor = Math.max(1.0D, nn(s.repelMaxFactor, 3.0D));
        raidLossRepelShare = Math.min(1.0D, nn(s.raidLossRepelShare, 0.6D));
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static FrontTable fromJson(Json j) {
        return j == null ? DEFAULT : new FrontTable(j);
    }

    /** フェーズ p の 1 部隊の上限(下限より小さくしない)。 */
    public int unitMax(int phase) {
        int v = unitMaxByPhase[Math.max(0, Math.min(unitMaxByPhase.length - 1, phase))];
        return Math.max(unitMin, Math.min(com.yayuy.overgrownvegetation.horde.VirtualHordes.MAX_SWARM, v));
    }

    /** フェーズ p の狩りの上限の体数。 */
    public int huntMax(int phase) {
        return Math.max(0, huntMaxByPhase[Math.max(0, Math.min(huntMaxByPhase.length - 1, phase))]);
    }

    /** フェーズ p で作る中の G2 以降の割合。 */
    public double plantShare(int phase) {
        return Math.max(0.0D, Math.min(1.0D, plantShareByPhase[Math.max(0, Math.min(plantShareByPhase.length - 1, phase))]));
    }
}
