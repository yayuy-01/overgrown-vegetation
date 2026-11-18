package com.yayuy.overgrownvegetation.data;

/**
 * 重さを軽くする仕組みの設計値({@code perf.json}、2026-11-18 大群のための軽量化 B)。数はどれも【仮】。
 */
public final class PerfTable {

    public static final PerfTable DEFAULT = new PerfTable(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public AiTiersJson aiTiers;
        public PushJson push;
        public FlowFieldJson flowField;
        public VirtualHordeJson virtualHorde;
    }

    public static final class VirtualHordeJson {
        public Double speed;
        public Integer moveIntervalTicks;
        public Integer materializeIntervalTicks;
        public Integer dematerializeIntervalTicks;
        public Double materializeBlocks;
        public Double dematerializeBlocks;
        public Integer realCap;
        public Integer spawnPerRun;
        public Integer lifeAfterArriveTicks;
        public Integer virtualCap;
        public Integer raidIntervalTicks;
        public Double raidShare;
        public Integer raidSwarmMin;
        public Integer raidSwarmMax;
        public Double raidSpawnMin;
        public Double raidSpawnMax;
        public Double raidReserveShare;
    }

    public static final class FlowFieldJson {
        public Integer radiusBlocks;
        public Integer rebuildTicks;
        public Integer dropUnusedTicks;
        public Integer stuckTicks;
        public Integer fallbackTicks;
        public Integer lookaheadCells;
        public Double laneSpreadBlocks;
        public Double chaseNearBlocks;
        public Double chaseMoveBlocks;
        public Integer chaseRebuildTicks;
        public java.util.List<String> chaseSpecies;
    }

    public static final class AiTiersJson {
        public Double nearBlocks;
        public Double midBlocks;
        public Integer midIntervalTicks;
        public Integer farIntervalTicks;
        public Integer hurtGraceTicks;
    }

    public static final class PushJson {
        public Integer intervalTicks;
    }

    /** AI の段階化({@code perf/MobThinkThrottle}): 毎回選び直す距離・中の段の距離(ブロック)・中と遠くの間隔(tick)・叩かれた後に間引かない間(tick)。 */
    public final double nearBlocks, midBlocks;
    public final int midIntervalTicks, farIntervalTicks, hurtGraceTicks;
    /** 植物側の押し合いを探す間隔({@code perf/PlantPushThin}。試し)。 */
    public final int pushIntervalTicks;
    /**
     * 流れの地図({@code perf/FlowFields}。11-18 大群の軽量化 A): 行き先のまわりの地図の半径(ブロック)・作り直す間隔・使われなければ捨てるまで(tick)・
     * この間ほとんど進まなければ詰まりとみなす・詰まった Mob が普通の経路探索に戻る間(tick)・先読みする升の数。
     */
    public final int flowRadiusBlocks, flowRebuildTicks, flowDropUnusedTicks, flowStuckTicks, flowFallbackTicks, flowLookaheadCells;
    /** 流れの地図の列の幅: Mob ごとに進む向きの横へずらす最大(ブロック。0 = ずらさない)。11-18 ユーザー「列が細過ぎたら横にずらす」。 */
    public final double flowLaneSpreadBlocks;
    /**
     * 追跡の寄り(11-18 ユーザー「遠くから追いかけるときだけ地図を使う」): 標的のプレイヤーからこの距離より遠い間だけ地図を下り、近づいたら種ごとの狩りに戻る・
     * プレイヤーがこれだけ動いたら地図を作り直す(ブロック)・作り直しの最短の間隔(tick)・使う種(まず 1〜2 種で試す)。
     */
    public final double flowChaseNearBlocks, flowChaseMoveBlocks;
    public final int flowChaseRebuildTicks;
    public final java.util.Set<net.minecraft.resources.ResourceLocation> flowChaseSpecies;
    /**
     * 仮想の群れ({@code horde/VirtualHordes}。11-18 大群の軽量化 A): 進む速さ(ブロック/tick)・進める/本物にする/戻す間隔(tick)・
     * 本物にする距離・戻す距離(ブロック)・本物の総数の上限・1 回に本物にする数・行き先に着いてから消えるまで(tick)。
     */
    public final double virtualSpeed, virtualMaterializeBlocks, virtualDematerializeBlocks;
    public final int virtualMoveIntervalTicks, virtualMaterializeIntervalTicks, virtualDematerializeIntervalTicks, virtualRealCap, virtualSpawnPerRun,
            virtualLifeAfterArriveTicks;
    /** 仮想の頭数の上限・大規模レイドから仮想の群れを出す間隔(tick)と割合・群れの頭数・拠点からの距離(11-18 ユーザー決定。数は【仮】)。 */
    public final int virtualCap, virtualRaidIntervalTicks, virtualRaidSwarmMin, virtualRaidSwarmMax;
    public final double virtualRaidShare, virtualRaidSpawnMin, virtualRaidSpawnMax;
    /** 大規模レイドの maxAlive のうち仮想の群れのために空けておく割合(11-18 ユーザー決定 案 B。半分【仮】)。 */
    public final double virtualRaidReserveShare;

    private PerfTable(Json json) {
        AiTiersJson a = json == null || json.aiTiers == null ? new AiTiersJson() : json.aiTiers;
        this.nearBlocks = a.nearBlocks != null ? Math.max(0.0D, a.nearBlocks) : 24.0D;
        this.midBlocks = Math.max(this.nearBlocks, a.midBlocks != null ? a.midBlocks : 64.0D);
        this.midIntervalTicks = Math.max(2, a.midIntervalTicks != null ? a.midIntervalTicks : 4);
        this.farIntervalTicks = Math.max(2, a.farIntervalTicks != null ? a.farIntervalTicks : 12);
        this.hurtGraceTicks = Math.max(0, a.hurtGraceTicks != null ? a.hurtGraceTicks : 60);
        this.pushIntervalTicks = Math.max(1, json != null && json.push != null && json.push.intervalTicks != null ? json.push.intervalTicks : 4);
        FlowFieldJson f = json == null || json.flowField == null ? new FlowFieldJson() : json.flowField;
        this.flowRadiusBlocks = Math.max(32, Math.min(256, f.radiusBlocks != null ? f.radiusBlocks : 160));
        this.flowRebuildTicks = Math.max(20, f.rebuildTicks != null ? f.rebuildTicks : 200);
        this.flowDropUnusedTicks = Math.max(20, f.dropUnusedTicks != null ? f.dropUnusedTicks : 600);
        this.flowStuckTicks = Math.max(10, f.stuckTicks != null ? f.stuckTicks : 40);
        this.flowFallbackTicks = Math.max(0, f.fallbackTicks != null ? f.fallbackTicks : 100);
        this.flowLookaheadCells = Math.max(1, Math.min(8, f.lookaheadCells != null ? f.lookaheadCells : 4));
        this.flowLaneSpreadBlocks = Math.max(0.0D, Math.min(8.0D, f.laneSpreadBlocks != null ? f.laneSpreadBlocks : 0.0D));
        this.flowChaseNearBlocks = Math.max(4.0D, f.chaseNearBlocks != null ? f.chaseNearBlocks : 20.0D);
        this.flowChaseMoveBlocks = Math.max(1.0D, f.chaseMoveBlocks != null ? f.chaseMoveBlocks : 8.0D);
        this.flowChaseRebuildTicks = Math.max(10, f.chaseRebuildTicks != null ? f.chaseRebuildTicks : 40);
        java.util.Set<net.minecraft.resources.ResourceLocation> cs = new java.util.HashSet<>();
        for (String s : f.chaseSpecies != null ? f.chaseSpecies : java.util.List.<String>of()) {
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(s.contains(":") ? s : "overgrown_vegetation:" + s);
            if (id != null) cs.add(id);
        }
        this.flowChaseSpecies = java.util.Set.copyOf(cs);
        VirtualHordeJson v = json == null || json.virtualHorde == null ? new VirtualHordeJson() : json.virtualHorde;
        this.virtualSpeed = Math.max(0.01D, Math.min(1.0D, v.speed != null ? v.speed : 0.12D));
        this.virtualMoveIntervalTicks = Math.max(1, v.moveIntervalTicks != null ? v.moveIntervalTicks : 5);
        this.virtualMaterializeIntervalTicks = Math.max(1, v.materializeIntervalTicks != null ? v.materializeIntervalTicks : 5);
        this.virtualDematerializeIntervalTicks = Math.max(1, v.dematerializeIntervalTicks != null ? v.dematerializeIntervalTicks : 40);
        this.virtualMaterializeBlocks = Math.max(16.0D, v.materializeBlocks != null ? v.materializeBlocks : 64.0D);
        this.virtualDematerializeBlocks = Math.max(this.virtualMaterializeBlocks + 16.0D, v.dematerializeBlocks != null ? v.dematerializeBlocks : 128.0D);
        this.virtualRealCap = Math.max(0, v.realCap != null ? v.realCap : 300);
        this.virtualSpawnPerRun = Math.max(1, v.spawnPerRun != null ? v.spawnPerRun : 6);
        this.virtualLifeAfterArriveTicks = Math.max(20, v.lifeAfterArriveTicks != null ? v.lifeAfterArriveTicks : 24000);
        this.virtualCap = Math.max(0, v.virtualCap != null ? v.virtualCap : 2000);
        this.virtualRaidIntervalTicks = Math.max(1, v.raidIntervalTicks != null ? v.raidIntervalTicks : 200);
        this.virtualRaidShare = Math.max(0.0D, Math.min(1.0D, v.raidShare != null ? v.raidShare : 0.5D));
        this.virtualRaidSwarmMin = Math.max(1, v.raidSwarmMin != null ? v.raidSwarmMin : 32);
        this.virtualRaidSwarmMax = Math.max(this.virtualRaidSwarmMin, v.raidSwarmMax != null ? v.raidSwarmMax : 64);
        this.virtualRaidSpawnMin = Math.max(64.0D, v.raidSpawnMin != null ? v.raidSpawnMin : 200.0D);
        this.virtualRaidSpawnMax = Math.max(this.virtualRaidSpawnMin, v.raidSpawnMax != null ? v.raidSpawnMax : 320.0D);
        this.virtualRaidReserveShare = Math.max(0.0D, Math.min(1.0D, v.raidReserveShare != null ? v.raidReserveShare : 0.5D));
    }

    public static PerfTable fromJson(Json json) {
        return json == null ? DEFAULT : new PerfTable(json);
    }
}
