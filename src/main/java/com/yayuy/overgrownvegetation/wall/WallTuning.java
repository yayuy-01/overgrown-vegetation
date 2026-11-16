package com.yayuy.overgrownvegetation.wall;

/**
 * 蠢く壁の調整値(2026-11-16 試作。数はどれも【仮】。正本は Vault「蠢く壁(洞窟を塞いで追ってくる森)(11-16)」の決定 その 2〜7)。
 * 手触りを見て決まったら config・設計データへ移す。今は static にして、GameTest とコマンドから書き換えられるようにしてある。
 */
public final class WallTuning {

    private WallTuning() {
    }

    /** 進む速さ(ブロック/秒)。決定 その 2「走る(ダッシュ)のと同じ」。バニラのダッシュは約 5.6。 */
    public static double speedBlocksPerSecond = 5.6D;
    /** 止まるまでの基本の長さ(tick)。決定 その 2・5「60 秒」【仮】。 */
    public static int baseDurationTicks = 60 * 20;
    /** 飲んだ Mob 1 体ごとに延びる長さ(tick)。決定 その 3「1 体で 1 秒」(ユーザー)。 */
    public static int ticksPerSwallow = 20;
    /** 置いたブロックを壊す時間 = 硬さ × これ(tick)。決定 その 7「硬さ × 2 秒」【仮】。 */
    public static double chewTicksPerHardness = 40.0D;
    /** 跡(壁・床・天井の汚染)を 1 tick に置く数の上限(全部の壁で分け合う)。決定 その 7「48」【仮】。 */
    public static int trailPerTick = 48;
    /** 跡は先頭が通り過ぎてからこれだけ待って置く(tick)。面の後ろで少しずつ。【仮】 */
    public static int trailDelayTicks = 40;
    /** 先頭の升を送るのは、プレイヤーからこの距離(ブロック)以内。決定 その 7「64」。 */
    public static int sendRadius = 64;
    /** 1 回の通信で送る升の上限(近い順)。決定 その 7「4096」。 */
    public static int sendCap = 4096;
    /** 先頭の升をまとめて送り直す間隔(tick。途中から近づいた人・止まっている先頭のため)。【仮】 */
    public static int snapshotIntervalTicks = 40;
    /** 通った区画の胞子の上乗せ(%)と、その長さ(tick)。決定 その 3「しばらく胞子が濃い」【仮】。 */
    public static double sporeTrailPercent = 60.0D;
    public static int sporeTrailTicks = 10 * 60 * 20;
    /** 覚えておく「プレイヤーが置いたブロック」の数の上限(世界ごと。古いものから忘れる)。【仮】 */
    public static int placedMemory = 100_000;
}
