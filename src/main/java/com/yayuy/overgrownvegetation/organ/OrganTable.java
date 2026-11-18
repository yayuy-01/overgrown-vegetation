package com.yayuy.overgrownvegetation.organ;

import javax.annotation.Nullable;

/**
 * 森の器官の設計値({@code design/organs.json}、2026-11-17)。数は全部【仮】(ユーザーが決めた所は organs.json の説明に書いた)。
 * 正本の決定は Vault「強くなる道(素材・装備・武器・探索)(11-17)」の決定 14〜16 と「森の器官の形」。
 */
public final class OrganTable {

    // --- Gson DTO ---
    public static final class Json {
        public Boolean enabled;
        public Integer minPhase;
        public Double planIntervalDays;
        public int[] maxOrgans;
        public Double price, growDays, minAgeDays;
        public Integer minDepthChunks, spacingChunks, baseClearChunks, samples, anchorMinChunks, anchorMaxChunks;
        public Double ruinAgeDays;
        public double[] kindWeights;
        public Integer blocksPerTick;
        public RolesJson roles;
        public WitherJson wither;
        public GuardJson guards;
        public SmallJson small;
        public TentacleJson tentacles;
        public ClearingJson clearing;
    }

    public static final class RolesJson {
        public Double phaseChunks, towerSporePerDay, sacHiveNodes;
        public Integer nurseryIntervalSeconds, nurseryMaxAlive, nurseryPlayerBlocks, nurseryMinBaseChunks, nurseryMaxBaseChunks;
        public Double leyLineBlocks, crystalRegrowDays;
        public Double leyHealthPercent, leyAttackPercent, leyArmor, leySpeedPercent, leyKnockbackResist;
        public Double sacRegrowDays;
    }

    public static final class WitherJson {
        public Double pointsDays, budgetCapShare, phaseOrganPointsFactor, sacBudgetFactor;
    }

    public static final class GuardJson {
        public Integer count, playerBlocks, cooldownSeconds;
    }

    public static final class SmallJson {
        public Boolean enabled;
        public Double price, growDays, minAgeDays, strength, sacHiveNodes, maxFactor;
        public Integer minDepthChunks, spacingChunks, guardCount;
    }

    public static final class ClearingJson {
        public Boolean enabled;
        public Integer marginBlocks, smallMarginBlocks, treeBlocksMax, leafReach;
        public Double giantAvoidBlocks, giantAvoidSmallBlocks, regrowDays;
        public Boolean regrow, regrowGiantSapling;
        public Integer regrowSpacing, regrowUndergrowthSpacing;
        public Double regrowUndergrowthShare, regrowTallShare;
    }

    public static final class TentacleJson {
        public Integer min, max, smallCount;
        public Double reach, knockback, health, reviveSeconds;
    }

    public final boolean enabled;
    public final int minPhase;
    public final double planIntervalDays;
    public final int[] maxOrgans;
    public final double price, growDays, minAgeDays;
    public final int minDepthChunks, spacingChunks, baseClearChunks, samples, anchorMinChunks, anchorMaxChunks;
    public final double ruinAgeDays;
    /** 種類ごとの選ばれやすさ({@link OrganKind} の順)。 */
    public final double[] kindWeights;
    public final int blocksPerTick;

    /** フェーズを進める器官(大きい版)1 本 = 過繁茂この数のチャンク分の面積の点(11-19 固定値)。 */
    public final double phaseChunks, towerSporePerDay, sacHiveNodes;
    public final int nurseryIntervalSeconds, nurseryMaxAlive, nurseryPlayerBlocks, nurseryMinBaseChunks, nurseryMaxBaseChunks;
    public final double leyLineBlocks, crystalRegrowDays;
    /** 地脈を吸う根 1 本(大きい版)の上げ幅({@link LeyBuffs.Stat} の順)。 */
    public final double[] leyStat;

    /** 枯れたときに引くポイント = フェーズを進める器官(大きい版)の witherPointsDays 日分(11-19 固定値)。 */
    public final double witherPointsDays, witherBudgetCapShare, phaseOrganPointsFactor, sacBudgetFactor;

    public final int guardCount, guardPlayerBlocks, guardCooldownSeconds;

    public final boolean smallEnabled;
    public final double smallPrice, smallGrowDays, smallMinAgeDays, smallStrength, smallSacHiveNodes, smallMaxFactor;
    public final int smallMinDepthChunks, smallSpacingChunks, smallGuardCount;

    public final int tentacleMin, tentacleMax, tentacleSmall;
    public final double tentacleReach, tentacleKnockback, tentacleHealth, tentacleReviveSeconds;
    /** 破裂した嚢が生え直すまでの日数(1 つあたりの平均)。 */
    public final double sacRegrowDays;

    /** 建つ所の木を払う(11-19。{@link OrganClearing})。 */
    public final boolean clearEnabled;
    public final int clearMargin, clearSmallMargin, clearTreeMax, clearLeafReach;
    /** 巨大樹の根元からこれより近い升には建てない(11-19 ユーザー「巨大樹は避けて」)。 */
    public final double giantAvoid, giantAvoidSmall;
    /**
     * 枯れた後、払った所が段階的に森へ戻る(11-19 ユーザー「生え直す方向で」→ 同日「15〜20 日かけて段階的に、下草 → 苗木」)。
     * 全部で regrowDays 日。初めの regrowUndergrowthShare の間は下草(間隔 regrowUndergrowthSpacing、うち regrowTallShare が 2 段のシダ・葦)、
     * 残りで苗木(間隔 regrowSpacing)。regrowGiantSapling なら最後に巨大樹の苗木を 1 本(既定 off【仮】)。
     */
    public final boolean regrowEnabled, regrowGiantSapling;
    public final double regrowDays, regrowUndergrowthShare, regrowTallShare;
    public final int regrowSpacing, regrowUndergrowthSpacing;

    public static final OrganTable DEFAULT = fromJson(null);

    private OrganTable(Json j) {
        RolesJson r = j.roles != null ? j.roles : new RolesJson();
        WitherJson w = j.wither != null ? j.wither : new WitherJson();
        GuardJson g = j.guards != null ? j.guards : new GuardJson();
        SmallJson s = j.small != null ? j.small : new SmallJson();
        TentacleJson tj = j.tentacles != null ? j.tentacles : new TentacleJson();
        ClearingJson cj = j.clearing != null ? j.clearing : new ClearingJson();
        enabled = j.enabled == null || j.enabled;
        minPhase = Math.max(0, or(j.minPhase, 3));
        planIntervalDays = Math.max(0.01D, or(j.planIntervalDays, 0.5D));
        maxOrgans = j.maxOrgans != null && j.maxOrgans.length > 0 ? j.maxOrgans.clone() : new int[]{0, 0, 0, 1, 2, 3, 5, 7, 9, 11, 13};
        price = Math.max(0.0D, or(j.price, 120.0D));
        growDays = Math.max(0.01D, or(j.growDays, 3.0D));
        minAgeDays = Math.max(0.0D, or(j.minAgeDays, 3.0D));
        minDepthChunks = Math.max(0, or(j.minDepthChunks, 4));
        spacingChunks = Math.max(1, or(j.spacingChunks, 12));
        baseClearChunks = Math.max(0, or(j.baseClearChunks, 6));
        samples = Math.max(1, or(j.samples, 48));
        anchorMinChunks = Math.max(0, or(j.anchorMinChunks, 6));
        anchorMaxChunks = Math.max(anchorMinChunks + 1, or(j.anchorMaxChunks, 40));
        ruinAgeDays = Math.max(0.01D, or(j.ruinAgeDays, 10.0D));
        kindWeights = j.kindWeights != null && j.kindWeights.length == OrganKind.values().length ? j.kindWeights.clone()
                : new double[]{1.0D, 1.2D, 1.0D, 0.8D, 0.8D};
        blocksPerTick = Math.max(1, or(j.blocksPerTick, 200));
        phaseChunks = Math.max(0.0D, or(r.phaseChunks, 150000.0D));
        towerSporePerDay = Math.max(0.0D, or(r.towerSporePerDay, 40.0D));
        sacHiveNodes = Math.max(0.0D, or(r.sacHiveNodes, 3.0D));
        nurseryIntervalSeconds = Math.max(1, or(r.nurseryIntervalSeconds, 60));
        nurseryMaxAlive = Math.max(0, or(r.nurseryMaxAlive, 6));
        nurseryPlayerBlocks = Math.max(1, or(r.nurseryPlayerBlocks, 64));
        nurseryMinBaseChunks = Math.max(0, or(r.nurseryMinBaseChunks, 8));
        nurseryMaxBaseChunks = Math.max(nurseryMinBaseChunks + 1, or(r.nurseryMaxBaseChunks, 24));
        leyLineBlocks = Math.max(1.0D, or(r.leyLineBlocks, 12.0D));
        crystalRegrowDays = Math.max(0.01D, or(r.crystalRegrowDays, 0.5D));
        leyStat = new double[]{Math.max(0.0D, or(r.leyHealthPercent, 10.0D)), Math.max(0.0D, or(r.leyAttackPercent, 10.0D)),
                Math.max(0.0D, or(r.leyArmor, 2.0D)), Math.max(0.0D, or(r.leySpeedPercent, 5.0D)), Math.max(0.0D, or(r.leyKnockbackResist, 0.1D))};
        witherPointsDays = Math.max(0.0D, or(w.pointsDays, 1.0D));
        witherBudgetCapShare = Math.max(0.0D, or(w.budgetCapShare, 0.5D));
        phaseOrganPointsFactor = Math.max(0.0D, or(w.phaseOrganPointsFactor, 2.0D));
        sacBudgetFactor = Math.max(0.0D, or(w.sacBudgetFactor, 2.0D));
        guardCount = Math.max(0, or(g.count, 4));
        guardPlayerBlocks = Math.max(1, or(g.playerBlocks, 48));
        guardCooldownSeconds = Math.max(1, or(g.cooldownSeconds, 120));
        smallEnabled = s.enabled == null || s.enabled;
        smallPrice = Math.max(0.0D, or(s.price, 30.0D));
        smallGrowDays = Math.max(0.01D, or(s.growDays, 1.0D));
        smallMinAgeDays = Math.max(0.0D, or(s.minAgeDays, 1.0D));
        smallStrength = Math.max(0.0D, or(s.strength, 0.2D));
        smallSacHiveNodes = Math.max(0.0D, or(s.sacHiveNodes, 0.2D));
        smallMaxFactor = Math.max(0.0D, or(s.maxFactor, 6.0D));
        smallMinDepthChunks = Math.max(0, or(s.minDepthChunks, 2));
        smallSpacingChunks = Math.max(1, or(s.spacingChunks, 4));
        smallGuardCount = Math.max(0, or(s.guardCount, 1));
        tentacleMin = Math.max(0, or(tj.min, 3));
        tentacleMax = Math.max(tentacleMin, or(tj.max, 5));
        tentacleSmall = Math.max(0, or(tj.smallCount, 1));
        tentacleReach = Math.max(1.0D, or(tj.reach, 6.0D));
        tentacleKnockback = Math.max(0.0D, or(tj.knockback, 1.2D));
        tentacleHealth = Math.max(1.0D, or(tj.health, 12.0D));
        tentacleReviveSeconds = Math.max(1.0D, or(tj.reviveSeconds, 90.0D));
        sacRegrowDays = Math.max(0.01D, or(r.sacRegrowDays, 3.0D));
        clearEnabled = cj.enabled == null || cj.enabled;
        clearMargin = Math.max(0, or(cj.marginBlocks, 4));
        clearSmallMargin = Math.max(0, or(cj.smallMarginBlocks, 2));
        clearTreeMax = Math.max(1, or(cj.treeBlocksMax, 4000));
        clearLeafReach = Math.max(0, or(cj.leafReach, 5));
        giantAvoid = Math.max(0.0D, or(cj.giantAvoidBlocks, 60.0D));
        giantAvoidSmall = Math.max(0.0D, or(cj.giantAvoidSmallBlocks, 40.0D));
        regrowEnabled = cj.regrow == null || cj.regrow;
        regrowDays = Math.max(0.01D, or(cj.regrowDays, 18.0D));
        regrowSpacing = Math.max(2, or(cj.regrowSpacing, 8));
        regrowUndergrowthShare = Math.max(0.0D, Math.min(0.95D, or(cj.regrowUndergrowthShare, 0.4D)));
        regrowUndergrowthSpacing = Math.max(1, or(cj.regrowUndergrowthSpacing, 3));
        regrowTallShare = Math.max(0.0D, Math.min(1.0D, or(cj.regrowTallShare, 0.3D)));
        regrowGiantSapling = cj.regrowGiantSapling != null && cj.regrowGiantSapling;
    }

    public static OrganTable fromJson(@Nullable Json j) {
        return new OrganTable(j != null ? j : new Json());
    }

    /** そのフェーズで生きていてよい器官の数(大きい版)。 */
    public int maxOrgans(int phase) {
        if (phase < 0) return 0;
        return maxOrgans[Math.min(phase, maxOrgans.length - 1)];
    }

    /** そのフェーズで生きていてよい小さい版の数。 */
    public int maxSmall(int phase) {
        return (int) Math.round(maxOrgans(phase) * smallMaxFactor);
    }

    /** 大きさごとの強さの倍率(大きい版 1、小さい版 smallStrength)。 */
    public double strength(boolean small) {
        return small ? smallStrength : 1.0D;
    }

    public double growDays(boolean small) {
        return small ? smallGrowDays : growDays;
    }

    public double price(boolean small) {
        return small ? smallPrice : price;
    }

    public double sacHiveNodes(boolean small) {
        return small ? smallSacHiveNodes : sacHiveNodes;
    }

    private static int or(@Nullable Integer v, int d) {
        return v != null ? v : d;
    }

    private static double or(@Nullable Double v, double d) {
        return v != null ? v : d;
    }
}
