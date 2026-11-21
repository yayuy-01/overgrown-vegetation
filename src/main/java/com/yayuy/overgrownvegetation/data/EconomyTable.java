package com.yayuy.overgrownvegetation.data;

/**
 * 森の資源(バイオマス)の収支の設計値({@code director.json} の {@code economy}、2026-11-20)。
 * ユーザーが決めたのは仕組みだけ(Vault「森の知性(司令塔)」の 11-20 の節): 稼ぎは毎秒少しずつ = 過繁茂のチャンクの数 + 井戸の器官 + 巨大樹、
 * 感染体への変化でまとまって入る、自然湧きも払う、森の中で倒された植物側の Mob は払った分の一部が戻る、器官の役(井戸 = 稼ぎ・嚢 = 上限・
 * 塔 = 払って胞子を遠くへ・フェーズ = 払って点・苗床 = 値段を軽く)。<b>数はどれも【仮】</b>。
 *
 * <p>11-20 夜 ユーザー決定「フェーズの基本の物差しをやめる」: 稼ぎは「1 つあたり何ぼ」。1 日の稼ぎ =
 * (11-20 夜の形。11-21 に毎秒・広さに比例へ置き換えた: 毎秒の稼ぎ = Σ前線(その前線の過繁茂のチャンク数 × chunkPerSecond × (1 + leyFrontBonus × その前線の井戸))+ treePerSecond × 巨大樹。物差しは段階 0 の感染 Mob の生産 = 100)。
 * 森は大きくなるほど自分を養うのに使い、余りは伸びる縁からだけ出る(縁の長さは広さの √)。incomeBase と面積の割合は稼ぎに使わない。
 */
public final class EconomyTable {

    public static final EconomyTable DEFAULT = new EconomyTable(null);

    public static final class Json {
        public Double chunkPerSecond, leyFrontBonus, treePerSecond;
        public Double upkeepPerSecond, upkeepOutsideFactor, upkeepIncomeShare;
        public Double infectBase, infectPerHealth, infectMax;
        public Double naturalCostFactor, refundShare, despawnRefund;
        public Double nurseryOrganDiscount, nurseryMinFactor, nurseryLocalFactor;
        public Double phaseSpendPerDay, phaseSpendPointsFactor;
        public Double towerFlightIntervalDays, towerFlightChance, towerFlightCost, towerFlightMassDays, towerFlightMinBudgetShare;
        public Double driftCost, driftPassengerFactor;
        public Double waveBudgetShare, hoardBudgetShare, waveIncomeDays, hoardIncomeDays;
    }

    /** 1 日の秒数(24000 tick = 1200 秒)。稼ぎ・維持費は毎秒で持ち、古い「1 日あたり」の口はこれを掛けて答える。 */
    public static final double SECONDS_PER_DAY = 1200.0D;

    /** 稼ぎ(11-21 ユーザー決定「毎秒・広さに比例」): 過繁茂 1 チャンク(毎秒)・井戸の器官 1 本が自分の前線の稼ぎに足す割合(11-21 案 c。小さい版は small.strength 倍・足し算)・巨大樹 1 本(毎秒)。 */
    public final double chunkPerSecond, leyFrontBonus, treePerSecond;
    /** 維持費(11-21): 植物側の 1 体が毎秒払う = 値段 × upkeepPerSecond。森(過繁茂)の外は × upkeepOutsideFactor。司令塔は維持費の合計が稼ぎの upkeepIncomeShare を超えないように出す。 */
    public final double upkeepPerSecond, upkeepOutsideFactor, upkeepIncomeShare;
    /** 感染体への変化 1 回で入る量 = infectBase + 元の生き物の最大体力 × infectPerHealth(infectMax で頭打ち)。 */
    public final double infectBase, infectPerHealth, infectMax;
    /** 自然湧き(縁・地下・霧の中の本物)が払う割合(値段 × これ)。払えなければ湧かない。 */
    public final double naturalCostFactor;
    /** 森(過繁茂のチャンク)の中で倒されたとき戻る割合・自然湧きが消えた(デスポーン)とき戻る割合。 */
    public final double refundShare, despawnRefund;
    /** 苗床の群体 1 本(大きい版)で全体の値段を軽くする割合・その下限・Mob から苗床になった物が孵す Mob の値段の倍率。 */
    public final double nurseryOrganDiscount, nurseryMinFactor, nurseryLocalFactor;
    /** フェーズを進める器官 1 本(大きい版。小さい版は small.strength 倍)が 1 日に使う決まった量と、使い切ったときに今の点の何倍を足すか。 */
    public final double phaseSpendPerDay, phaseSpendPointsFactor;
    /** 胞子の塔がわざと長距離の飛翔を起こす: 見直す間(日)・起こす確率・値段(決まった量。小さい版は small.strength 倍)・飛ばす量(塔の 1 日の放出の何日分)・予算が上限のこの割合より多いときだけ。 */
    public final double towerFlightIntervalDays, towerFlightChance, towerFlightCost, towerFlightMassDays, towerFlightMinBudgetShare;
    /** 後で(漂流する擬態 Mob): 出す値段と、乗せる Mob の値段に掛ける倍率。今は使わない(値段の表の行だけ)。 */
    public final double driftCost, driftPassengerFactor;
    /** 11-20 夜「襲撃の大きさは貯めた予算だけで決める」: 送り込み 1 回・貯めた襲撃の最初の 1 回が使う予算の割合(使える予算 × これ ÷ 顔ぶれの平均の値段 = 体数)。 */
    public final double waveBudgetShare, hoardBudgetShare;
    /** 1 回に使ってよいのは 1 日の稼ぎのこの日数分まで(送り込み 1 回・貯めた襲撃の合計は財布 + これ)。上限が巨大樹(ハイヴノード)で何日分にも膨らんでも、1 回が跳ねないように【仮】。 */
    public final double waveIncomeDays, hoardIncomeDays;

    private EconomyTable(Json j) {
        Json s = j != null ? j : new Json();
        chunkPerSecond = nn(s.chunkPerSecond, 0.008D);
        leyFrontBonus = nn(s.leyFrontBonus, 0.10D);
        treePerSecond = nn(s.treePerSecond, 800.0D / SECONDS_PER_DAY);
        upkeepPerSecond = nn(s.upkeepPerSecond, 0.00004D);
        upkeepOutsideFactor = nn(s.upkeepOutsideFactor, 1.5D);
        upkeepIncomeShare = Math.min(1.0D, nn(s.upkeepIncomeShare, 0.8D));
        infectBase = nn(s.infectBase, 200.0D);
        infectPerHealth = nn(s.infectPerHealth, 10.0D);
        infectMax = Math.max(infectBase, nn(s.infectMax, 1500.0D));
        naturalCostFactor = nn(s.naturalCostFactor, 0.05D);
        refundShare = Math.min(1.0D, nn(s.refundShare, 0.5D));
        despawnRefund = Math.min(1.0D, nn(s.despawnRefund, 1.0D));
        nurseryOrganDiscount = Math.min(1.0D, nn(s.nurseryOrganDiscount, 0.05D));
        nurseryMinFactor = Math.max(0.05D, Math.min(1.0D, nn(s.nurseryMinFactor, 0.7D)));
        nurseryLocalFactor = Math.max(0.05D, Math.min(1.0D, nn(s.nurseryLocalFactor, 0.75D)));
        phaseSpendPerDay = nn(s.phaseSpendPerDay, 1800.0D);
        phaseSpendPointsFactor = nn(s.phaseSpendPointsFactor, 1.0D);
        towerFlightIntervalDays = Math.max(0.05D, nn(s.towerFlightIntervalDays, 1.0D));
        towerFlightChance = Math.min(1.0D, nn(s.towerFlightChance, 0.5D));
        towerFlightCost = nn(s.towerFlightCost, 3600.0D);
        towerFlightMassDays = nn(s.towerFlightMassDays, 0.5D);
        towerFlightMinBudgetShare = Math.min(1.0D, nn(s.towerFlightMinBudgetShare, 0.5D));
        driftCost = nn(s.driftCost, 1200.0D);
        driftPassengerFactor = nn(s.driftPassengerFactor, 1.0D);
        waveBudgetShare = Math.min(1.0D, nn(s.waveBudgetShare, 0.75D));
        hoardBudgetShare = Math.min(1.0D, nn(s.hoardBudgetShare, 1.0D));
        waveIncomeDays = nn(s.waveIncomeDays, 0.65D);
        hoardIncomeDays = nn(s.hoardIncomeDays, 1.2D);
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static EconomyTable fromJson(Json j) {
        return j == null ? DEFAULT : new EconomyTable(j);
    }

    /** 井戸の器官(大 leyBig 本・小 leySmall 本)が 1 つの前線の稼ぎに足す割合(純粋な計算。小さい版は器官の small.strength 倍・足し算)。 */
    public double leyBonus(int leyBig, int leySmall) {
        return leyFrontBonus * (Math.max(0, leyBig) + DesignData.organs().strength(true) * Math.max(0, leySmall));
    }

    /**
     * 毎秒の稼ぎ(純粋な計算・前線が 1 つで井戸が全部そこにある形): 過繁茂のチャンク × chunkPerSecond × (1 + 井戸の上乗せ)+ 巨大樹。
     * 本物は前線ごとに数える(ForestEconomy#sources)。
     */
    public double incomePerSecond(int overgrownChunks, int leyBig, int leySmall, int giantTrees) {
        return chunkPerSecond * Math.max(0, overgrownChunks) * (1.0D + leyBonus(leyBig, leySmall)) + treePerSecond * Math.max(0, giantTrees);
    }

    /** 1 日の稼ぎ(毎秒 × 1200。古い口)。 */
    public double incomePerDay(int overgrownChunks, int leyBig, int leySmall, int giantTrees) {
        return incomePerSecond(overgrownChunks, leyBig, leySmall, giantTrees) * SECONDS_PER_DAY;
    }

    /** 値段の合計(森の中・外)から毎秒の維持費(純粋な計算)。 */
    public double upkeepPerSecond(double priceInForest, double priceOutside) {
        return (Math.max(0.0D, priceInForest) + Math.max(0.0D, priceOutside) * upkeepOutsideFactor) * upkeepPerSecond;
    }

    /** 感染体への変化 1 回で入る量(純粋な計算)。 */
    public double infectGain(double victimMaxHealth) {
        return Math.min(infectMax, infectBase + infectPerHealth * Math.max(0.0D, victimMaxHealth));
    }

    /** 苗床の群体の本数(大きい版に直した数)から、全体の値段の倍率(純粋な計算)。 */
    public double nurseryFactor(double nurseryOrgans) {
        return Math.max(nurseryMinFactor, 1.0D - nurseryOrganDiscount * Math.max(0.0D, nurseryOrgans));
    }
}
