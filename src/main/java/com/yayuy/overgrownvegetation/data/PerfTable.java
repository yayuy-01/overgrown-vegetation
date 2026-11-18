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
    }

    public static final class FlowFieldJson {
        public Integer radiusBlocks;
        public Integer rebuildTicks;
        public Integer dropUnusedTicks;
        public Integer stuckTicks;
        public Integer fallbackTicks;
        public Integer lookaheadCells;
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
    }

    public static PerfTable fromJson(Json json) {
        return json == null ? DEFAULT : new PerfTable(json);
    }
}
