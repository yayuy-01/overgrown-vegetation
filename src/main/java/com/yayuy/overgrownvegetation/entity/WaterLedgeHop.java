package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.effect.WaterRotTracker;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 水に浮いている陸の Mob が<b>水面より高い岸</b>へ上がる手助け(2026-09-25、ユーザー報告「周囲が湿地であると、水の中を突っ切ろうとして
 * 水中に入るも、その後陸地に上がろうとして失敗し続け、スタックする」)。
 *
 * <h2>なぜ上がれなかったか</h2>
 * バニラの陸の Mob は、浮いている間は段差を登れない(段の上がりは足が地面にあるときだけ。{@code Entity#collide})。水の中のジャンプは
 * 泳ぎ上がるだけで、岸へ押し付けたときの持ち上げ({@code LivingEntity#travel} の +0.3)は「体を 0.6 上げた箱」に塊も水も無いときだけ ——
 * 岸の上面が水の塊の上面と同じ高さの岸にしか上がれない。湿地の水たまりの岸は水面より 1 段高いことが多く、群れの一員・護衛・
 * 水から上がる Goal({@link EscapeWaterGoal})が岸へ押し付けたまま抜けられなかった(アルボラも水の中では壁を登らない)。
 *
 * <h2>やること</h2>
 * 水に浸かって横にぶつかっている(= 岸へ押し付けている)陸の Mob の体を、向いている向きへ {@value #PROBE} ずらして持ち上げた箱が、
 * 持ち上げ {@link #LIFTS} のどれかで<b>塊にも水にも当たらない</b>(= 岸の上へ出られる)なら、上向きの速さ(持ち上げに合わせて
 * 0.4〜0.75)と前への押しを与えて跳ね上げる。{@value #HOP_INTERVAL} tick に 1 回まで(個体ごとに位相をずらす)。
 * 水面から 2 ブロックより高い岸・体が入らない岸へは跳ばない(岸を選ぶ側 {@link EscapeWaterGoal} も上がれる岸だけを選ぶ)。
 * 対象は {@link EscapeWaterInjector} と同じ(グループ 1〜10 の陸生 Mob = 水ぐされにかかる種)。数値は仮決め 2026-09-25。
 *
 * <h2>跳ね上げは岸の上面を越えるまで続ける(2026-09-27)</h2>
 * 1 回速さを与えるだけだと、<b>水の底のほうで跳ねたとき</b>に効かなかった。次の tick がまだ水の中なので、バニラの水中の
 * {@code LivingEntity#travel} が上向きを ×0.8 したうえ「横にぶつかっていて少し上が空いていれば上向き 0.3」に<b>置き換え</b>、
 * 前への押しも岸の側面にぶつかって 0 になる —— 水面 +1.1 の岸に 0.2〜0.3 届かずに落ちてくる。跳ねるのは 8 tick おきで
 * 浮き沈みとは無関係なので、底で跳ね続けて上がれない個体が出た(同じ水たまりで 5% ほど。GameTest
 * {@code landMobHopsOutOfPoolOntoHighBank} の揺れ)。ユーザー承認のうえで、跳ねた 1 回ぶん({@link Hop})を最大
 * {@value #HOLD_TICKS} tick 覚えておき、足が見つけた高さ(岸の上に出られる高さ)に届くまで毎 tick 上向きの速さを付け直し、
 * 届いたら前へ押して岸に乗せるようにした。乗った・水へ落ち直した・時間切れで終わる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class WaterLedgeHop {

    /** 試す持ち上げ(ブロック、小さい順)。 */
    static final double[] LIFTS = {0.6D, 0.9D, 1.2D, 1.6D, 2.0D};
    /** 岸の上に出られるかを見るとき、体を前へずらす量(ブロック)。 */
    static final double PROBE = 0.3D;
    /** 前への押し(ブロック / tick)。 */
    static final double PUSH = 0.12D;
    /** 跳ね上げは個体ごとにこの tick に 1 回まで。 */
    static final int HOP_INTERVAL = 8;
    /**
     * 1 回の跳ね上げを続ける長さの上限(tick)。持ち上げ 2.0 を上向き 0.75 で上がりきり、前へ出て岸に下りるまで 6 tick ほど。
     * 仮決め 2026-09-27。
     */
    static final int HOLD_TICKS = 12;
    /** 岸の上面を越えてからの上向きの速さの上限(越えたら弧を描いて岸へ下りる。上がり続けて飛び越さないように)。 */
    static final double OVER_RISE = 0.1D;

    /** 跳ね上げの途中の個体(サーバーの tick だけで読み書きする。消えた個体は GC に任せる)。 */
    private static final Map<PathfinderMob, Hop> HOPPING = new WeakHashMap<>();

    /** 跳ね上げ 1 回ぶん。 */
    private static final class Hop {
        /** 足がこの高さに届けば、前へずらした体が岸の上に出られる({@link WaterLedgeHop#tryHop} で空いていると確かめた高さ)。 */
        final double clearY;
        final double fx;
        final double fz;
        /** 上がる間に保つ上向きの速さ。 */
        final double up;
        int ticksLeft = HOLD_TICKS;
        /** 足が {@link #clearY} に届いた(= 前へ押して岸に乗せる段)。 */
        boolean cleared;

        Hop(double clearY, double fx, double fz, double up) {
            this.clearY = clearY;
            this.fx = fx;
            this.fz = fz;
            this.up = up;
        }
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof PathfinderMob mob) || mob.level().isClientSide()) {
            return;
        }
        if (!HOPPING.isEmpty()) {
            Hop hop = HOPPING.get(mob);
            if (hop != null) {
                if (!continueHop(mob, hop)) {
                    HOPPING.remove(mob);
                }
                return;
            }
        }
        if (!mob.isInWater() || !mob.horizontalCollision || !mob.isAlive() || mob.isNoAi()) {
            return;
        }
        if (Math.floorMod(mob.tickCount + mob.getId(), HOP_INTERVAL) != 0) {
            return;
        }
        if (MobGroup.of(mob) == null || WaterRotTracker.isWaterRotImmune(mob)) {
            return;
        }
        tryHop(mob);
    }

    /**
     * 向いている向きの岸の上へ出られるなら跳ね上げる(以後 {@value #HOLD_TICKS} tick まで {@link #continueHop} が続ける)。
     * GameTest からも呼ぶ(跳ねる時機を選んで確かめるため)。
     *
     * @return 跳ね上げた
     */
    public static boolean tryHop(PathfinderMob mob) {
        float yaw = mob.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw);
        double fz = Mth.cos(yaw);
        AABB box = mob.getBoundingBox();
        for (double lift : LIFTS) {
            AABB raised = box.move(fx * PROBE, lift, fz * PROBE);
            if (mob.level().noCollision(mob, raised) && !mob.level().containsAnyLiquid(raised)) {
                Vec3 v = mob.getDeltaMovement();
                double up = Math.min(0.75D, 0.25D + 0.25D * lift);
                mob.setDeltaMovement(fx * PUSH, Math.max(v.y, up), fz * PUSH);
                mob.hasImpulse = true;
                HOPPING.put(mob, new Hop(box.minY + lift, fx, fz, up));
                return true;
            }
        }
        return false;
    }

    /**
     * 跳ね上げの続き(毎 tick、バニラの移動の後)。足が {@link Hop#clearY} に届くまでは上向きの速さを付け直し
     * (水の中ではバニラが ×0.8・0.3 への置き換えで削るので)、届いたら前へ押して岸に乗せる。
     *
     * @return まだ続ける
     */
    private static boolean continueHop(PathfinderMob mob, Hop hop) {
        if (!mob.isAlive() || mob.isNoAi() || hop.ticksLeft-- <= 0) {
            return false;
        }
        Vec3 v = mob.getDeltaMovement();
        if (!hop.cleared) {
            if (mob.getY() < hop.clearY) {
                mob.setDeltaMovement(hop.fx * PUSH, Math.max(v.y, hop.up), hop.fz * PUSH);
                mob.hasImpulse = true;
                return true;
            }
            hop.cleared = true;
        }
        if (mob.onGround() || mob.isInWater()) {
            // 岸に乗った(または前が塞がっていて水へ落ち直した。次の跳ね上げはいつもどおり 8 tick おき)。
            return false;
        }
        mob.setDeltaMovement(hop.fx * PUSH, Math.min(v.y, OVER_RISE), hop.fz * PUSH);
        mob.hasImpulse = true;
        return true;
    }

    private WaterLedgeHop() {
    }
}
