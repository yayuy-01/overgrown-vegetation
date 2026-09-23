package com.yayuy.overgrownvegetation.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 陸生捕食者({@link AbstractOvergrownPredatorEntity})が、追跡中の獲物へ短く前方へ跳びついて
 * 詰めるゴール。素早い小型爬虫類の狩りらしさを出すための汎用の飛びかかり。
 *
 * <p>地上で対象と {@link #MIN}〜{@link #MAX} ブロック離れていて視線が通っているとき、
 * {@link #cooldownTicks} tick ごとに 1 回、対象方向へ跳ぶ。接触したら 1 回近接ヒットを与えて終了。
 */
public class PredatorPounceGoal extends Goal {

    private static final double MIN = 2.5D;
    private static final double MAX = 6.5D;
    private static final double MAX_VERTICAL = 3.0D;
    private static final int MAX_TICKS = 20;

    private final AbstractOvergrownPredatorEntity mob;
    private final double horizontalSpeed;
    private final double lift;
    private final int cooldownTicks;

    private int ticks;
    private int nextTime;
    private LivingEntity victim;
    private boolean dealt;

    public PredatorPounceGoal(AbstractOvergrownPredatorEntity mob, double horizontalSpeed, double lift, int cooldownTicks) {
        this.mob = mob;
        this.horizontalSpeed = horizontalSpeed;
        this.lift = lift;
        this.cooldownTicks = cooldownTicks;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.mob.getActionState() != AbstractOvergrownPredatorEntity.STATE_IDLE) {
            return false;
        }
        if (this.mob.tickCount < this.nextTime || !this.mob.onGround() || this.mob.isInWater()) {
            return false;
        }
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (Math.abs(target.getY() - this.mob.getY()) > MAX_VERTICAL) {
            return false;
        }
        double dist = this.mob.distanceTo(target);
        return dist >= MIN && dist <= MAX && this.mob.hasLineOfSight(target);
    }

    @Override
    public boolean canContinueToUse() {
        if (this.victim == null || this.dealt || this.ticks > MAX_TICKS) {
            return false;
        }
        // 跳んだ直後の数tickは接地判定が残るので終了しない。
        return this.ticks <= 3 || !this.mob.onGround();
    }

    @Override
    public void start() {
        this.victim = this.mob.getTarget();
        this.ticks = 0;
        this.dealt = false;
        this.mob.getNavigation().stop();
        this.mob.setActionState(AbstractOvergrownPredatorEntity.STATE_SPECIAL);

        double dx = this.victim.getX() - this.mob.getX();
        double dz = this.victim.getZ() - this.mob.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        Vec3 dir = len > 1.0E-4D ? new Vec3(dx / len, 0.0D, dz / len) : this.mob.getLookAngle();
        this.mob.setDeltaMovement(dir.x * this.horizontalSpeed, this.lift, dir.z * this.horizontalSpeed);
        this.mob.hasImpulse = true;

        float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        this.mob.setYRot(yaw);
        this.mob.yBodyRot = yaw;
    }

    @Override
    public void stop() {
        this.victim = null;
        this.nextTime = this.mob.tickCount + this.cooldownTicks;
        if (this.mob.getActionState() == AbstractOvergrownPredatorEntity.STATE_SPECIAL) {
            this.mob.setActionState(AbstractOvergrownPredatorEntity.STATE_IDLE);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.ticks++;
        if (this.victim == null || !this.victim.isAlive()) {
            return;
        }
        this.mob.getLookControl().setLookAt(this.victim, 30.0F, 30.0F);
        if (this.mob.getBoundingBox().inflate(0.3D).intersects(this.victim.getBoundingBox())) {
            this.mob.doHurtTarget(this.victim);
            this.dealt = true;
        }
    }
}
