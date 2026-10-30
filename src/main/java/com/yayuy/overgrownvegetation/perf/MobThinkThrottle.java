package com.yayuy.overgrownvegetation.perf;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * <b>遠くの Mob の思考の間引き</b>(2026-10-06、ユーザー指定の ⑤。遠くの汚染の広がりが遅くなるのは了承済み)。
 *
 * <h2>何を間引くか</h2>
 * <p>バニラの Mob は 2 tick に 1 回、持っている全部の行動(Goal)に「今始められるか」を聞き直す({@code GoalSelector#tick})。
 * 標的探し・逃げ先探しのような重い判定はここで走る(2026-10-06 の計測で、グループ1・胞子感染の「汚染から逃げる」だけで
 * サーバースレッドの 13%)。プレイヤーから {@code mobThinkThrottleDistance}(既定 48)ブロックより遠い Mob は、
 * この聞き直しを {@code mobThinkThrottleInterval}(既定 8)tick に 1 回にする。
 *
 * <p>間引かないもの:
 * <ul>
 *     <li>今している行動の続き(歩く・追う・殴る)。毎 tick 動く行動は毎 tick、それ以外もバニラの奇数 tick と同じ扱いで続く。
 *     移動・当たり判定・経路の追従も毎 tick({@code serverAiStep} の残り)</li>
 *     <li>標的のいる Mob(戦闘中)。遠くの戦い(基地の守備兵とグループ1など)の結果を変えないため</li>
 *     <li>この mod のグループ 1〜10 に入っていない Mob(バニラ・他 MOD の Mob の行動は変えない)。ただし、この mod が全 Mob に
 *     足している「胞子感染で汚染から逃げる」行動({@code FleeCorruptionGoal})だけは、その行動の中で同じ間引きを掛ける</li>
 * </ul>
 *
 * <p>シミュレーション距離(既定 12 チャンク = 192 ブロック)より外の Mob はバニラがそもそも動かさないので、効くのは 48〜192 ブロックの帯。
 */
public final class MobThinkThrottle {

    /** 直近の tick で間引いた数(表示用。サーバースレッドだけが書く)。 */
    private static int heldThisTick;
    private static int heldLastTick;
    private static int countedTick = Integer.MIN_VALUE;

    private MobThinkThrottle() {
    }

    /**
     * その Mob のこの tick の「行動の選び直し」を飛ばすか({@code Mob#serverAiStep} の Mixin から毎 tick 呼ぶ)。
     * バニラが選び直す tick(偶数側)でだけ意味を持つので、奇数側は真っ先に false で返す。
     */
    public static boolean holdThinking(Mob mob) {
        int phase = mob.tickCount + mob.getId();
        if (phase % 2 != 0 || mob.tickCount <= 1) {
            return false; // バニラが選び直さない tick
        }
        if (MobGroup.of(mob) == null || mob.getTarget() != null) {
            return false;
        }
        return holdBack(mob, phase);
    }

    /**
     * mod が他の Mob に足した重い行動({@code FleeCorruptionGoal})の「始められるか」の判定を、遠くではこの tick 飛ばすか。
     * 標的の有無もグループも見ない(その行動自身の判定だけを間引く)。
     */
    public static boolean holdCostlyCheck(Mob mob) {
        return holdBack(mob, mob.tickCount + mob.getId());
    }

    private static boolean holdBack(Mob mob, int phase) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.mobThinkThrottleEnabled.get() || !(mob.level() instanceof ServerLevel level)
                || level.getServer() instanceof GameTestServer) {
            // GameTest のサーバーには本物のプレイヤーがいない(偽プレイヤーは ServerPlayer でない)ので、間引くと全部の Mob が
            // 「遠い」扱いになり、反応の速さを見るテストが揺れる。プレイヤーが誰もいない普通のサーバーでは間引く(湧き場所のチャンクの Mob など)。
            return false;
        }
        int interval = Math.max(2, cfg.mobThinkThrottleInterval.get());
        if (Math.floorMod(phase, interval) < 2) {
            return false; // 間引いた中でも選び直す番の tick
        }
        double range = cfg.mobThinkThrottleDistance.get();
        double rangeSq = range * range;
        for (Player player : level.players()) {
            if (player.distanceToSqr(mob) <= rangeSq) {
                return false;
            }
        }
        count(level);
        return true;
    }

    private static void count(ServerLevel level) {
        int tick = level.getServer().getTickCount();
        if (tick != countedTick) {
            heldLastTick = countedTick == tick - 1 ? heldThisTick : 0;
            heldThisTick = 0;
            countedTick = tick;
        }
        heldThisTick++;
    }

    /** {@code /ov perf} の表示用: 直前の tick に選び直しを飛ばした回数。 */
    public static int heldLastTick() {
        return heldLastTick;
    }
}
