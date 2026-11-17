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

    private PerfTable(Json json) {
        AiTiersJson a = json == null || json.aiTiers == null ? new AiTiersJson() : json.aiTiers;
        this.nearBlocks = a.nearBlocks != null ? Math.max(0.0D, a.nearBlocks) : 24.0D;
        this.midBlocks = Math.max(this.nearBlocks, a.midBlocks != null ? a.midBlocks : 64.0D);
        this.midIntervalTicks = Math.max(2, a.midIntervalTicks != null ? a.midIntervalTicks : 4);
        this.farIntervalTicks = Math.max(2, a.farIntervalTicks != null ? a.farIntervalTicks : 12);
        this.hurtGraceTicks = Math.max(0, a.hurtGraceTicks != null ? a.hurtGraceTicks : 60);
        this.pushIntervalTicks = Math.max(1, json != null && json.push != null && json.push.intervalTicks != null ? json.push.intervalTicks : 4);
    }

    public static PerfTable fromJson(Json json) {
        return json == null ? DEFAULT : new PerfTable(json);
    }
}
