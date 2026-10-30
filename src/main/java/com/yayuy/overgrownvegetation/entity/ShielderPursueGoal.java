package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * 「過繁茂したスカトゥリウム」の接近＋近接攻撃ゴール（{@link net.minecraft.world.entity.ai.goal.MeleeAttackGoal}
 * の置き換え）。
 *
 * <p>普通に経路探索で追いかけるが、<b>経路が見つからない／途中で行き止まった</b>ときは、
 * ナビゲーションを諦めて {@link net.minecraft.world.entity.ai.control.MoveControl} で対象へ直進する
 * （ビーライン）。スカトゥリウムは当たり判定が大きく、草木の茂った汚染地形では約3マス幅の通路が
 * 見つからず経路探索が失敗しがちで、その場で回転して追ってこなくなる問題への対処。
 * ブロックは一切壊さない。1ブロックの段差は MoveControl の自動ジャンプ＋
 * {@link OvergrownShielderEntity#aiStep()} 側の補助ジャンプで越える。
 *
 * <p><b>走らない</b>（ユーザー要望 2026-09-15）。以前は突進のクールダウン中に対象と 6 ブロック以上
 * 離れると 2 秒だけ 1.5 倍速で走って距離を詰めていたが、それだと<b>歩きで簡単に間合いを潰してしまい</b>、
 * 背中のポッドからの迫撃砲（{@link ShielderMortarGoal}）がほとんど出なかった。
 * 引き離された相手への手は<b>突進か迫撃砲の 2 つだけ</b>にして、このゴールは常に等速で追う。
 *
 * <p><b>届かなかった経路探索を同じ条件で引き直さない</b>(2026-09-24、軽量化)。ビーライン中は毎 tick
 * ナビゲーションを止めるので、バニラの「引いた経路の使い回し」が効かず、5〜10 tick ごとに
 * 同じマスから同じマスへの探索(体が大きいので 1 回が重い)を丸ごとやり直していた。
 * 前回の探索が届かなかったときは、自分と相手のブロック座標と時刻を控えておき、
 * どちらも同じマスのまま {@value #PATH_RETRY_TICKS} tick 経っていなければ引き直さずに
 * いまのビーライン/途中までの経路を続ける。控えに期限があるのは、汚染で地形が変わって道が開くこともあるから。
 */
public class ShielderPursueGoal extends Goal {

    private static final double SPEED = 1.0D;
    private static final int ATTACK_COOLDOWN = 20;
    /** これ以上離れた対象は追わない（HurtByTarget 由来で保持され続ける対象の暴走防止）。 */
    private static final double GIVE_UP_DISTANCE = 40.0D;
    /** 届かなかった探索と同じマス同士なら、この tick 数が経つまで引き直さない(仮決め 2026-09-24)。 */
    private static final int PATH_RETRY_TICKS = 40;

    private final OvergrownShielderEntity mob;

    private int repathCooldown;
    private int attackCooldown;
    private boolean beeline;
    /** 最後に届かなかった探索の自分／相手のブロック座標と、そのゲーム時刻。無ければ {@code null}。 */
    @Nullable
    private BlockPos failedFrom;
    @Nullable
    private BlockPos failedTo;
    private long failedAt;

    public ShielderPursueGoal(OvergrownShielderEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.isBusy()) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && mob.distanceTo(target) <= GIVE_UP_DISTANCE;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.isBusy()) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && mob.distanceTo(target) <= GIVE_UP_DISTANCE;
    }

    @Override
    public void start() {
        repathCooldown = 0;
        attackCooldown = 0;
        beeline = false;
        failedFrom = null;
        failedTo = null;
        mob.setAggressive(true);
    }

    @Override
    public void stop() {
        mob.setAggressive(false);
        mob.getNavigation().stop();
        if (mob.getTarget() != null && !mob.getTarget().isAlive()) {
            mob.setTarget(null);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) {
            return;
        }
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        double distSqr = mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        double reachSqr = attackReachSqr(target);

        if (repathCooldown > 0) {
            repathCooldown--;
        } else {
            repathCooldown = 5 + mob.getRandom().nextInt(6);
            // 前回届かなかったのと同じマス同士なら、引き直しても同じ結果 —— いまの動きを続ける。
            if (!isSameAsFailedAttempt(target)) {
                // 経路が引ければそれで追う。引けなければビーラインに切替。
                Path before = mob.getNavigation().getPath();
                boolean moving = mob.getNavigation().moveTo(target, SPEED);
                beeline = !moving;
                rememberAttempt(target, moving, before);
            }
        }

        if (beeline || mob.getNavigation().isDone()) {
            mob.getNavigation().stop();
            mob.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), SPEED);
        }

        if (attackCooldown > 0) {
            attackCooldown--;
        }
        if (distSqr <= reachSqr && attackCooldown <= 0) {
            attackCooldown = ATTACK_COOLDOWN;
            mob.swing(InteractionHand.MAIN_HAND);
            mob.doHurtTarget(target);
        }
    }

    /** 前回届かなかった探索と同じマス同士で、まだ {@link #PATH_RETRY_TICKS} tick 経っていないか。 */
    private boolean isSameAsFailedAttempt(LivingEntity target) {
        return failedFrom != null
                && mob.level().getGameTime() - failedAt < PATH_RETRY_TICKS
                && failedFrom.equals(mob.blockPosition())
                && target.blockPosition().equals(failedTo);
    }

    /**
     * 引き直しの結果を控える。経路が引けても<b>相手まで届かない</b>(途中まで)なら控え、届くなら控えを消す。
     * 経路そのものが無い(宙に浮いている・相手のチャンクが読み込まれていない)ときは探索をしていないので、
     * 控えは触らない —— 着地した直後の探索まで省いてしまわないように。
     * バニラの {@code moveTo(Entity, double)} は経路が引けないとナビゲーションの経路を<b>消さずに</b> false を返すので、
     * {@code getPath()} には前の経路が残っている。そこで「呼ぶ前と同じ経路のまま false」も探索なしとみなす。
     */
    private void rememberAttempt(LivingEntity target, boolean moving, @Nullable Path before) {
        Path path = mob.getNavigation().getPath();
        if (path == null || (!moving && path == before)) {
            return;
        }
        if (!moving || !path.canReach()) {
            failedFrom = mob.blockPosition();
            failedTo = target.blockPosition();
            failedAt = mob.level().getGameTime();
        } else {
            failedFrom = null;
            failedTo = null;
        }
    }

    private double attackReachSqr(LivingEntity target) {
        double w = mob.getBbWidth() * 2.0D;
        return w * w + target.getBbWidth();
    }
}
