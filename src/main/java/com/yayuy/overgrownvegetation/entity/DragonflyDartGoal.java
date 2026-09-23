package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 「ステノデクティア」のトンボ特有の移動: <b>止まる → 直線的に素早く動く → 止まる</b> を繰り返す。
 *
 * <p>{@link OvergrownFlyerEntity#STATE_HOVER} でその場に静止 → 短い間 → {@link OvergrownFlyerEntity#STATE_FLY}
 * で近くの空中の1点へ一気に直進 → 到達したらまた静止、を数サイクル繰り返す。
 */
public class DragonflyDartGoal extends Goal {

    private static final int MIN_HOVER = 12;
    private static final int MAX_HOVER = 40;
    private static final int DART_TIMEOUT = 25;
    private static final double DART_SPEED = 1.8D;

    private final OvergrownFlyerEntity mob;
    private Vec3 dartTarget;
    private int hoverTicksLeft;
    private int dartTicksLeft;
    private boolean darting;
    private int cyclesLeft;

    public DragonflyDartGoal(OvergrownFlyerEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.mob.isPerching() || this.mob.getActionState() == OvergrownFlyerEntity.STATE_EAT) {
            return false;
        }
        return this.mob.getNavigation().isDone() && this.mob.getRandom().nextInt(6) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        return this.cyclesLeft > 0 && !this.mob.isPerching()
                && this.mob.getActionState() != OvergrownFlyerEntity.STATE_EAT;
    }

    @Override
    public void start() {
        this.cyclesLeft = 2 + this.mob.getRandom().nextInt(4);
        beginHover();
    }

    @Override
    public void stop() {
        this.dartTarget = null;
        this.darting = false;
        this.mob.setActionState(OvergrownFlyerEntity.STATE_FLY);
        this.mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private void beginHover() {
        this.darting = false;
        this.hoverTicksLeft = MIN_HOVER + this.mob.getRandom().nextInt(MAX_HOVER - MIN_HOVER + 1);
        this.mob.setActionState(OvergrownFlyerEntity.STATE_HOVER);
        this.mob.getNavigation().stop();
    }

    private void beginDart() {
        Vec3 base = this.mob.position();
        double angle = this.mob.getRandom().nextDouble() * Math.PI * 2.0D;
        double horiz = 3.0D + this.mob.getRandom().nextDouble() * 6.0D;
        double dy = (this.mob.getRandom().nextDouble() - 0.4D) * 4.0D;
        Vec3 candidate = base.add(Math.cos(angle) * horiz, dy, Math.sin(angle) * horiz);
        // 目的地が固体ブロックの中なら少し手前に寄せる。
        if (this.mob.level().getBlockState(BlockPos.containing(candidate)).blocksMotion()) {
            candidate = base.add(Math.cos(angle) * (horiz * 0.4D), dy * 0.4D, Math.sin(angle) * (horiz * 0.4D));
        }
        this.dartTarget = candidate;
        this.dartTicksLeft = DART_TIMEOUT;
        this.darting = true;
        this.mob.setActionState(OvergrownFlyerEntity.STATE_FLY);
        this.mob.getMoveControl().setWantedPosition(candidate.x, candidate.y, candidate.z, DART_SPEED);
    }

    @Override
    public void tick() {
        if (this.darting) {
            if (this.dartTarget == null || --this.dartTicksLeft <= 0
                    || this.mob.position().distanceToSqr(this.dartTarget) < 1.4D) {
                this.cyclesLeft--;
                if (this.cyclesLeft > 0) {
                    beginHover();
                } else {
                    this.darting = false;
                }
                return;
            }
            this.mob.getMoveControl().setWantedPosition(this.dartTarget.x, this.dartTarget.y, this.dartTarget.z, DART_SPEED);
            faceMotion();
        } else {
            // ホバリング: その場で速度を殺して静止。
            this.mob.getNavigation().stop();
            Vec3 v = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(v.x * 0.6D, v.y * 0.6D + 0.005D, v.z * 0.6D);
            if (--this.hoverTicksLeft <= 0) {
                beginDart();
            }
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
