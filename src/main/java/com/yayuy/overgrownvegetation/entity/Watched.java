package com.yayuy.overgrownvegetation.entity;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.world.entity.Entity;

/**
 * 見た目だけの計算(体の曲げ・地面の傾きなど)を、近くにプレイヤーがいない個体で飛ばすための判定(2026-11-16、コードの見直し 第 3 便)。
 * 1 万の桁の群れのほとんどは誰にも見えていないので、その分の計算を省く。
 *
 * <p>GameTest のサーバーにはプレイヤーがいないので、テストの間は「見えている」とみなす(曲げ・傾きを試すテストのため)。
 * 重さの計測({@code gametest/MobPerfBench})だけは {@link #benchFar} で遊ぶときと同じ判定にする。</p>
 */
public final class Watched {

    /** 見た目の計算をする距離(ブロック)【仮】。追跡の距離(10 チャンク前後)より短くてよい: 遠くの体の曲げは見分けられない。 */
    public static final double RANGE = 64.0D;
    /** 重さの計測の間だけ true(GameTest でもプレイヤーの近さで決める)。 */
    public static volatile boolean benchFar;

    private Watched() {
    }

    /** 近く({@code range} ブロック以内)に生きているプレイヤーがいるか。 */
    public static boolean near(Entity e, double range) {
        if (!benchFar && e.level().getServer() instanceof GameTestServer) return true;
        return e.level().hasNearbyAlivePlayer(e.getX(), e.getY(), e.getZ(), range);
    }
}
