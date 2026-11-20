package com.yayuy.overgrownvegetation.data;

/**
 * 森の資源(バイオマス)の収支の設計値({@code director.json} の {@code economy}、2026-11-20)。
 * ユーザーが決めたのは仕組みだけ(Vault「森の知性(司令塔)」の 11-20 の節): 稼ぎは毎秒少しずつ = 過繁茂のチャンクの数 + 井戸の器官 + 巨大樹、
 * 感染体への変化でまとまって入る、自然湧きも払う、森の中で倒された植物側の Mob は払った分の一部が戻る、器官の役(井戸 = 稼ぎ・嚢 = 上限・
 * 塔 = 払って胞子を遠くへ・フェーズ = 払って点・苗床 = 値段を軽く)。<b>数はどれも【仮】</b>。
 *
 * <p>稼ぎの物差しはフェーズの基本 {@code incomeBase[フェーズ]}(今までの 1 日の収入)のまま。1 日の稼ぎ =
 * {@code incomeBase × (chunkShare × 面積の割合 + leyShare × 井戸の数 + min(treeMax, treeShare × 巨大樹の数))}。
 * ふつうの森(面積の割合 1・井戸 1・巨大樹 7)で 0.985 倍 + 感染の稼ぎ − 自然湧き・器官の出費 = 今の襲撃の規模から大きくずらさない(試算は報告と director.json)。
 */
public final class EconomyTable {

    public static final EconomyTable DEFAULT = new EconomyTable(null);

    public static final class Json {
        public Double chunkShare, leyShare, treeShare, treeMax;
        public Double infectBase, infectPerHealth, infectMax;
        public Double naturalCostFactor, refundShare, despawnRefund;
        public Double nurseryOrganDiscount, nurseryMinFactor, nurseryLocalFactor;
        public Double phaseSpendShare, phaseSpendPointsFactor;
        public Double towerFlightIntervalDays, towerFlightChance, towerFlightCostShare, towerFlightMassDays, towerFlightMinBudgetShare;
        public Double driftCost, driftPassengerFactor;
    }

    /** 稼ぎの割り振り: 面積(過繁茂のチャンク)の分・井戸の器官 1 本(大きい版)の分・巨大樹 1 本の分とその頭打ち(どれも incomeBase に掛ける)。 */
    public final double chunkShare, leyShare, treeShare, treeMax;
    /** 感染体への変化 1 回で入る量 = infectBase + 元の生き物の最大体力 × infectPerHealth(infectMax で頭打ち)。 */
    public final double infectBase, infectPerHealth, infectMax;
    /** 自然湧き(縁・地下・霧の中の本物)が払う割合(値段 × これ)。払えなければ湧かない。 */
    public final double naturalCostFactor;
    /** 森(過繁茂のチャンク)の中で倒されたとき戻る割合・自然湧きが消えた(デスポーン)とき戻る割合。 */
    public final double refundShare, despawnRefund;
    /** 苗床の群体 1 本(大きい版)で全体の値段を軽くする割合・その下限・Mob から苗床になった物が孵す Mob の値段の倍率。 */
    public final double nurseryOrganDiscount, nurseryMinFactor, nurseryLocalFactor;
    /** フェーズを進める器官 1 本(大きい版)が 1 日に使う量(incomeBase に掛ける)と、使い切ったときに今の点の何倍を足すか。 */
    public final double phaseSpendShare, phaseSpendPointsFactor;
    /** 胞子の塔がわざと長距離の飛翔を起こす: 見直す間(日)・起こす確率・値段(incomeBase に掛ける)・飛ばす量(塔の 1 日の放出の何日分)・予算が上限のこの割合より多いときだけ。 */
    public final double towerFlightIntervalDays, towerFlightChance, towerFlightCostShare, towerFlightMassDays, towerFlightMinBudgetShare;
    /** 後で(漂流する擬態 Mob): 出す値段と、乗せる Mob の値段に掛ける倍率。今は使わない(値段の表の行だけ)。 */
    public final double driftCost, driftPassengerFactor;

    private EconomyTable(Json j) {
        Json s = j != null ? j : new Json();
        chunkShare = nn(s.chunkShare, 0.8D);
        leyShare = nn(s.leyShare, 0.08D);
        treeShare = nn(s.treeShare, 0.015D);
        treeMax = nn(s.treeMax, 0.15D);
        infectBase = nn(s.infectBase, 2.0D);
        infectPerHealth = nn(s.infectPerHealth, 0.1D);
        infectMax = Math.max(infectBase, nn(s.infectMax, 15.0D));
        naturalCostFactor = nn(s.naturalCostFactor, 0.1D);
        refundShare = Math.min(1.0D, nn(s.refundShare, 0.5D));
        despawnRefund = Math.min(1.0D, nn(s.despawnRefund, 1.0D));
        nurseryOrganDiscount = Math.min(1.0D, nn(s.nurseryOrganDiscount, 0.05D));
        nurseryMinFactor = Math.max(0.05D, Math.min(1.0D, nn(s.nurseryMinFactor, 0.7D)));
        nurseryLocalFactor = Math.max(0.05D, Math.min(1.0D, nn(s.nurseryLocalFactor, 0.75D)));
        phaseSpendShare = nn(s.phaseSpendShare, 0.04D);
        phaseSpendPointsFactor = nn(s.phaseSpendPointsFactor, 1.0D);
        towerFlightIntervalDays = Math.max(0.05D, nn(s.towerFlightIntervalDays, 1.0D));
        towerFlightChance = Math.min(1.0D, nn(s.towerFlightChance, 0.5D));
        towerFlightCostShare = nn(s.towerFlightCostShare, 0.08D);
        towerFlightMassDays = nn(s.towerFlightMassDays, 0.5D);
        towerFlightMinBudgetShare = Math.min(1.0D, nn(s.towerFlightMinBudgetShare, 0.5D));
        driftCost = nn(s.driftCost, 12.0D);
        driftPassengerFactor = nn(s.driftPassengerFactor, 1.0D);
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static EconomyTable fromJson(Json j) {
        return j == null ? DEFAULT : new EconomyTable(j);
    }

    /** 稼ぎの倍率(incomeBase に掛ける。純粋な計算)。面積の割合は呼ぶ側で幅に収める。{@code leyOrgans} は大きい版に直した本数。 */
    public double incomeFactor(double areaRatio, double leyOrgans, int giantTrees) {
        return chunkShare * Math.max(0.0D, areaRatio) + leyShare * Math.max(0.0D, leyOrgans) + Math.min(treeMax, treeShare * Math.max(0, giantTrees));
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
