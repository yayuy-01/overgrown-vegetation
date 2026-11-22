package com.yayuy.overgrownvegetation.data;

/**
 * 森の視界(戦場の霧)と偵察の設計値({@code director.json} の {@code vision}、2026-11-20)。ユーザーが決めたのは:
 * 森(過繁茂)の縁から 64・偵察の周り 32・地脈の線の地下・フィロセンスの周りだけが見える / 見た所のプレイヤーと拠点しか覚えない・狙わない /
 * 視界の外で倒された Mob は知らない・偵察が殺されたらその方向へ偵察を増やす / 手がかり A 夜の明かり・B 持ち帰りの汚れ・D 広がる探し方。
 * <b>ほかの数はどれも【仮】</b>。
 */
public final class VisionTable {

    public static final VisionTable DEFAULT = new VisionTable(null);

    public static final class Json {
        public Boolean enabled;
        public Double forestEdgeBlocks, scoutBlocks, watcherBlocks, leyLineBlocks, leyReachBlocks;
        public Integer leyDepthBlocks;
        public Double exploreIntervalDays;
        public Integer maxExplorers, explorersOnKill;
        public Double explorerBlocksPerDay, explorerWatchDays, materializeBlocks, dematerializeBlocks, spotHours, exploreCost;
        public Double clueDays, killClueWeight, lightClueWeight, trailClueWeight, clueFuzzBlocks;
        public Double lightSeeBlocks, lightSampleRadius;
        public Integer lightSamples, lightMinLevel, lightNeeded;
        public Double trailSenseBlocks, trailDays, trailChance, trailDirtFull;
        public Integer sweepSectors;
        public Double sweepStepBlocks, sweepMaxBlocks;
    }

    /** false なら視界を見ない(前の全部見える形)。 */
    public final boolean enabled;
    /** 森の縁から見える距離(ユーザー 64)・偵察の周り(ユーザー 32)・フィロセンスの周り【仮】。 */
    public final double forestEdgeBlocks, scoutBlocks, watcherBlocks;
    /** 地脈の線の地下: 線からこの距離・森の縁からこの距離まで・地表からこの深さより下【仮】。 */
    public final double leyLineBlocks, leyReachBlocks;
    public final int leyDepthBlocks;
    /** 探りに出す偵察: 見直す間(日)・同時の数・殺されたときに足す数【仮】。 */
    public final double exploreIntervalDays;
    public final int maxExplorers, explorersOnKill;
    /** 偵察(司令塔の側で進む)の速さ(ブロック/日)・着いてから眺める長さ(日)・プレイヤーがこの距離に来たら体を出す・離れたら体を戻す・見つけたとき足す点(時間)・出す値段【仮】。 */
    public final double explorerBlocksPerDay, explorerWatchDays, materializeBlocks, dematerializeBlocks, spotHours, exploreCost;
    /** 手がかり: 覚えている日数・重み(殺された > 明かり > 汚れ)・場所のぼかし【仮】。 */
    public final double clueDays, killClueWeight, lightClueWeight, trailClueWeight, clueFuzzBlocks;
    /** A 夜の明かり: 森の縁からこの距離までの明かりが見える・抜き取る半径・数・明るさの下限・明るい点がいくつで手がかりにするか【仮】。 */
    public final double lightSeeBlocks, lightSampleRadius;
    public final int lightSamples, lightMinLevel, lightNeeded;
    /** B 持ち帰りの汚れ: 森の縁からこの距離まで感じる・汚染地域を出てからこの日数で消える・1 回の見張りで手がかりにする確率(汚れ 100% のとき)・装備の汚れの合計がこれで 100%【仮】。 */
    public final double trailSenseBlocks, trailDays, trailChance, trailDirtFull;
    /** D 広がる探し方: 方角の区切りの数・1 回で伸ばす距離・ここまで伸ばしたら最初から【仮】。 */
    public final int sweepSectors;
    public final double sweepStepBlocks, sweepMaxBlocks;

    private VisionTable(Json j) {
        Json s = j != null ? j : new Json();
        enabled = s.enabled == null || s.enabled;
        forestEdgeBlocks = nn(s.forestEdgeBlocks, 64.0D);
        scoutBlocks = nn(s.scoutBlocks, 32.0D);
        watcherBlocks = nn(s.watcherBlocks, 48.0D);
        leyLineBlocks = nn(s.leyLineBlocks, 12.0D);
        leyReachBlocks = nn(s.leyReachBlocks, 256.0D);
        leyDepthBlocks = Math.max(0, s.leyDepthBlocks != null ? s.leyDepthBlocks : 8);
        exploreIntervalDays = Math.max(0.01D, nn(s.exploreIntervalDays, 0.25D));
        maxExplorers = Math.max(0, s.maxExplorers != null ? s.maxExplorers : 3);
        explorersOnKill = Math.max(0, s.explorersOnKill != null ? s.explorersOnKill : 2);
        explorerBlocksPerDay = Math.max(1.0D, nn(s.explorerBlocksPerDay, 2400.0D));
        explorerWatchDays = nn(s.explorerWatchDays, 0.05D);
        materializeBlocks = nn(s.materializeBlocks, 96.0D);
        dematerializeBlocks = Math.max(materializeBlocks + 16.0D, nn(s.dematerializeBlocks, 144.0D));
        spotHours = nn(s.spotHours, 8.0D);
        exploreCost = nn(s.exploreCost, 300.0D);
        clueDays = Math.max(0.01D, nn(s.clueDays, 2.0D));
        killClueWeight = nn(s.killClueWeight, 3.0D);
        lightClueWeight = nn(s.lightClueWeight, 2.0D);
        trailClueWeight = nn(s.trailClueWeight, 1.0D);
        clueFuzzBlocks = nn(s.clueFuzzBlocks, 24.0D);
        lightSeeBlocks = nn(s.lightSeeBlocks, 192.0D);
        lightSampleRadius = nn(s.lightSampleRadius, 24.0D);
        lightSamples = Math.max(1, s.lightSamples != null ? s.lightSamples : 16);
        lightMinLevel = Math.max(1, Math.min(15, s.lightMinLevel != null ? s.lightMinLevel : 10));
        lightNeeded = Math.max(1, s.lightNeeded != null ? s.lightNeeded : 2);
        trailSenseBlocks = nn(s.trailSenseBlocks, 512.0D);
        trailDays = Math.max(0.01D, nn(s.trailDays, 1.0D));
        trailChance = Math.min(1.0D, nn(s.trailChance, 0.25D));
        trailDirtFull = Math.max(1.0D, nn(s.trailDirtFull, 2000.0D));
        sweepSectors = Math.max(4, Math.min(64, s.sweepSectors != null ? s.sweepSectors : 16));
        sweepStepBlocks = Math.max(16.0D, nn(s.sweepStepBlocks, 96.0D));
        sweepMaxBlocks = Math.max(sweepStepBlocks, nn(s.sweepMaxBlocks, 768.0D));
    }

    private static double nn(Double v, double fallback) {
        return v != null && Double.isFinite(v) ? Math.max(0.0D, v) : fallback;
    }

    public static VisionTable fromJson(Json j) {
        return j == null ? DEFAULT : new VisionTable(j);
    }
}
