package com.yayuy.overgrownvegetation.skyboss;

/**
 * GameTest 用のつまみ(ふだんは触らない)。GameTest のサーバーには本物のプレイヤーが居ないので、近くにプレイヤーが居なくても姿勢を出して当たり判定を回す。
 */
public final class SkyBossTestHooks {

    public static boolean forcePose;

    private SkyBossTestHooks() {
    }
}
