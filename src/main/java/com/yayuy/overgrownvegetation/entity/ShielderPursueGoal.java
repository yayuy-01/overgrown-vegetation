package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

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
 */
public class ShielderPursueGoal extends Goal {

    private static final double SPEED = 1.0D;
    private static final int ATTACK_COOLDOWN = 20;
    /** これ以上離れた対象は追わない（HurtByTarget 由来で保持され続ける対象の暴走防止）。 */
    private static final double GIVE_UP_DISTANCE = 40.0D;

    private final OvergrownShielderEntity mob;

    private int repathCooldown;
    private int attackCooldown;
    private boolean beeline;

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
            // 経路が引ければそれで追う。引けなければビーラインに切替。
            beeline = !mob.getNavigation().moveTo(target, SPEED);
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

    private double attackReachSqr(LivingEntity target) {
        double w = mob.getBbWidth() * 2.0D;
        return w * w + target.getBbWidth();
    }
}
