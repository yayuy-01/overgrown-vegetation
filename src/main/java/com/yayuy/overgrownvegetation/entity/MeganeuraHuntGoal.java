package com.yayuy.overgrownvegetation.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 「メガネウラ」が獲物(ステノデクティア)へ空中を直進して噛みつくゴール。飛行ナビでの追跡と、
 * 経路が引けないときの直進(ビーライン)を併用する。
 */
public class MeganeuraHuntGoal extends Goal {

    private static final int ATTACK_COOLDOWN = 14;
    /**
     * 噛みつきモーションを見せる長さ({@code bite} の 0.25 秒)。噛みつきの出入りは補間 0
     * ({@link MeganeuraEntity#registerControllers})なので、状態を切り替えた tick から bite がそのまま流れる。
     * ダメージも同じ tick で、頭を振り下ろしきる 0.083 秒(2 tick 弱)より少しだけ早い。
     */
    private static final int BITE_TICKS = 5;
    private static final double GIVE_UP_DISTANCE = 40.0D;

    private final MeganeuraEntity mob;
    private int attackCooldown;
    private int biteTicksLeft;
    private int repathCooldown;
    /** 届かない相手(葉の下・家の中)へ、同じマス同士の飛行の経路探索を 10 tick ごとにやり直さない(2026-09-24)。 */
    private final PathRetryMemo pathMemo = new PathRetryMemo();

    public MeganeuraHuntGoal(MeganeuraEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = this.mob.getTarget();
        return target != null && target.isAlive()
                && !this.mob.isStationary()
                && this.mob.distanceTo(target) <= GIVE_UP_DISTANCE;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        this.mob.setActionState(OvergrownFlyerEntity.STATE_ATTACK);
        this.repathCooldown = 0;
        this.biteTicksLeft = 0;
    }

    @Override
    public void stop() {
        this.mob.setActionState(OvergrownFlyerEntity.STATE_FLY);
        this.biteTicksLeft = 0;
        this.mob.getNavigation().stop();
        if (this.mob.getTarget() != null && !this.mob.getTarget().isAlive()) {
            this.mob.setTarget(null);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) {
            return;
        }
        Vec3 aim = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ());
        this.mob.getLookControl().setLookAt(aim.x, aim.y, aim.z);

        if (this.mob.hasLineOfSight(target)) {
            // 相手が見えているなら経路探索は挟まず一直線に追う。
            // ナビの経路点とここで指定する狙い点とで目標高度が食い違うと、
            // 追いかけている間ずっと体が上下に揺れてしまうため。
            this.mob.getNavigation().stop();
            this.repathCooldown = 0;
            this.mob.getMoveControl().setWantedPosition(aim.x, aim.y, aim.z, 1.6D);
        } else {
            // 木や地形に遮られたときだけ経路探索に頼る。
            if (this.repathCooldown > 0) {
                this.repathCooldown--;
            } else {
                this.repathCooldown = 10;
                // 前回届かなかったのと同じマス同士なら探索を省く(下の直進はそのまま効く)。
                this.pathMemo.moveTo(this.mob, target, 1.4D);
            }
            if (this.mob.getNavigation().isDone()) {
                this.mob.getMoveControl().setWantedPosition(aim.x, aim.y, aim.z, 1.6D);
            }
        }
        faceMotion();

        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }
        if (this.biteTicksLeft > 0 && --this.biteTicksLeft == 0) {
            // 一撃ぶん振り終わったら、体を揺らさない追跡モーションへ戻す。
            this.mob.setActionState(OvergrownFlyerEntity.STATE_ATTACK);
        }
        double reach = this.mob.getBbWidth() * 0.5D + target.getBbWidth() * 0.5D + 0.6D;
        if (this.mob.distanceTo(target) <= reach && this.attackCooldown <= 0) {
            this.attackCooldown = ATTACK_COOLDOWN;
            // 頭を振り下ろすモーションは「実際に噛みついた瞬間」だけ。追跡中ずっと再生すると
            // 頭と胴体が上下に揺れっぱなしになる。
            this.biteTicksLeft = BITE_TICKS;
            this.mob.setActionState(OvergrownFlyerEntity.STATE_BITE);
            this.mob.swing(InteractionHand.MAIN_HAND);
            this.mob.doHurtTarget(target);
        }
    }

    private void faceMotion() {
        Vec3 v = this.mob.getDeltaMovement();
        if (v.horizontalDistanceSqr() > 1.0E-4D) {
            float yaw = (float) (Mth.atan2(v.z, v.x) * (180.0D / Math.PI)) - 90.0F;
            this.mob.setYRot(yaw);
            this.mob.yBodyRot = yaw;
        }
    }
}
