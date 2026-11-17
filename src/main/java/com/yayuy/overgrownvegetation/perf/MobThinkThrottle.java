package com.yayuy.overgrownvegetation.perf;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PerfTable;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * <b>AI の段階化</b>(遠くの Mob の思考の間引き。2026-10-06 ユーザー指定の ⑤ → 2026-11-18 大群のための軽量化 B の ②a で距離の段に。
 * ユーザー決定「入れる。背中側の 2 倍は外す」。値は design の {@code perf.json} の aiTiers【仮】)。
 *
 * <h2>何を間引くか</h2>
 * <p>バニラの Mob は 2 tick に 1 回、持っている全部の行動(Goal)に「今始められるか」を聞き直す({@code GoalSelector#tick})。
 * 標的探し・逃げ先探しのような重い判定はここで走る。この MOD の Mob は、いちばん近いプレイヤーからの距離で間隔を変える:
 * {@code nearBlocks}(24)までは毎回(バニラのまま)、{@code midBlocks}(64)までは {@code midIntervalTicks}(4)、
 * それより遠くは {@code farIntervalTicks}(12)tick に 1 回。
 *
 * <p>間引かないもの:
 * <ul>
 *     <li>今している行動の続き(歩く・追う・殴る)のうち毎 tick 動くもの({@code requiresUpdateEveryTick})。それ以外の続きは選び直しと同じ間隔。
 *     移動・当たり判定・経路の追従は毎 tick({@code serverAiStep} の残り)</li>
 *     <li>標的のいる Mob・叩かれてから {@code hurtGraceTicks}(60)の間の Mob・動いている群れの一員({@code CrawlerPack#inMovingPack})</li>
 *     <li>この mod の Mob でないもの(バニラ・他 MOD の Mob の行動は変えない)</li>
 * </ul>
 *
 * <p>シミュレーション距離(既定 12 チャンク = 192 ブロック)より外の Mob はバニラがそもそも動かさない。11-18 の測り(F7 の前線・Mob 約 400 体)で
 * 選び直しはサーバースレッドの 7.3%・経路探索 2.1%。
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
        if (!HordeMeter.isMod(mob) || mob.getTarget() != null) {
            return false;
        }
        return holdBack(mob, phase);
    }

    /**
     * mod が他の Mob に足した重い行動の「始められるか」の判定を、遠くではこの tick 飛ばすか。
     * 標的の有無もグループも見ない(その行動自身の判定だけを間引く)。
     */
    public static boolean holdCostlyCheck(Mob mob) {
        return holdBack(mob, mob.tickCount + mob.getId());
    }

    /** 距離の 2 乗から、選び直す間隔(tick)。2 = バニラのまま。 */
    public static int intervalFor(double distanceSq, PerfTable t) {
        if (distanceSq <= t.nearBlocks * t.nearBlocks) return 2;
        if (distanceSq <= t.midBlocks * t.midBlocks) return t.midIntervalTicks;
        return t.farIntervalTicks;
    }

    private static boolean holdBack(Mob mob, int phase) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.mobThinkThrottleEnabled.get() || !(mob.level() instanceof ServerLevel level)
                || level.getServer() instanceof GameTestServer) {
            // GameTest のサーバーには本物のプレイヤーがいない(偽プレイヤーは ServerPlayer でない)ので、間引くと全部の Mob が
            // 「遠い」扱いになり、反応の速さを見るテストが揺れる。プレイヤーが誰もいない普通のサーバーでは間引く(湧き場所のチャンクの Mob など)。
            return false;
        }
        PerfTable t = DesignData.perf();
        int far = Math.max(t.midIntervalTicks, t.farIntervalTicks);
        if (Math.floorMod(phase, far) < 2) {
            return false; // どの段でも選び直す番の tick(ここで帰れば距離を測らずに済む)
        }
        if (mob.getLastHurtByMob() != null && mob.tickCount - mob.getLastHurtByMobTimestamp() < t.hurtGraceTicks) {
            return false; // 叩かれた直後
        }
        double best = Double.MAX_VALUE;
        for (Player player : level.players()) {
            best = Math.min(best, player.distanceToSqr(mob));
        }
        int interval = intervalFor(best, t);
        if (interval <= 2 || Math.floorMod(phase, interval) < 2) {
            return false;
        }
        if (com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack.inMovingPack(mob)) {
            return false; // 動いている群れ(先導役も後続も)は遠くでも間引かない
        }
        count(level);
        if (HordeMeter.on) HordeMeter.held();
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
