package com.yayuy.overgrownvegetation.wall;

/**
 * 蠢く壁の調整値(2026-11-16 試作。数はどれも【仮】。正本は Vault「蠢く壁(洞窟を塞いで追ってくる森)(11-16)」の決定 その 2〜7)。
 * 手触りを見て決まったら config・設計データへ移す。今は static にして、GameTest とコマンドから書き換えられるようにしてある。
 */
public final class WallTuning {

    private WallTuning() {
    }

    /**
     * 進む速さ(ブロック/秒)。設定 {@code creepingWallSpeed}(common の gameplay)から読む。
     * 決定 その 2「ダッシュと同じ」(5.6)→ 11-16 ユーザー「ちょっとだけ今より遅く」で既定 5.0(ダッシュの 9 割)【仮】。
     * 設定がまだ読み込まれていない時(起動の途中)は既定の 5.0。
     */
    public static double speed() {
        try {
            return com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.creepingWallSpeed.get();
        } catch (IllegalStateException e) {
            return DEFAULT_SPEED;
        }
    }

    public static final double DEFAULT_SPEED = 5.0D;
    /** 止まるまでの基本の長さ(tick)。決定 その 2・5「60 秒」【仮】。 */
    public static int baseDurationTicks = 60 * 20;
    /** 飲んだ Mob 1 体ごとに延びる長さ(tick)。決定 その 3「1 体で 1 秒」(ユーザー)。 */
    public static int ticksPerSwallow = 20;
    /** 前触れの長さ(tick)。決定 その 1「逃げ始める数秒がある」【仮】5 秒。 */
    public static int omenTicks = 100;
    /** 攻撃でひるむ長さ・火(火属性の武器・松明・火打石を持って殴る)でひるむ長さ・続けてひるまない間(tick)。【仮】 */
    public static int flinchTicks = 8;
    public static int flinchFireTicks = 20;
    public static int flinchCooldownTicks = 30;
    /** 自然に起きる条件(決定 その 2「F3 から、超低確率」)。見る間隔(tick)・1 回の確率・起きた後の休み(tick)。【仮】 */
    public static int naturalMinPhase = 3;
    public static int naturalCheckTicks = 200;
    public static double naturalChance = 1.0D / 720.0D;
    public static int naturalCooldownTicks = 3 * 24000;
    /** 飲んだ Mob で延びる長さの上限(tick)。決定 その 8「最大 60 秒」。 */
    public static int maxExtensionTicks = 60 * 20;
    /**
     * プレイヤーからこれより遠い升は進めない(決定 その 8 の案 c「96 ブロックより先」)。0 以下で止めない(テスト用)。
     * 進めない枝は脇に置き、プレイヤーが近づいたらその時の距離から進み直す。
     */
    public static int activeRadius = 96;
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
