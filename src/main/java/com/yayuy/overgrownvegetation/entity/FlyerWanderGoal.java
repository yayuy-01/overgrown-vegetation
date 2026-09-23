package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.AirAndWaterRandomPos;
import net.minecraft.world.entity.ai.util.HoverRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * トンボが空を飛び回るゴール({@code Bee.BeeWanderGoal} 相当)。メガネウラの通常移動に使う
 * (止まらず滑らかに飛び続ける)。
 */
public class FlyerWanderGoal extends Goal {

    private final OvergrownFlyerEntity mob;
    private final double speed;

    public FlyerWanderGoal(OvergrownFlyerEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return this.mob.getNavigation().isDone()
                && !this.mob.isPerching()
                && this.mob.getRandom().nextInt(10) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.getNavigation().isInProgress() && !this.mob.isPerching();
    }

    @Override
    public void start() {
        Vec3 target = findTarget();
        if (target != null) {
            this.mob.getNavigation().moveTo(
                    this.mob.getNavigation().createPath(net.minecraft.core.BlockPos.containing(target), 1), this.speed);
        }
    }

    private Vec3 findTarget() {
        Vec3 look = this.mob.getViewVector(0.0F);
        Vec3 hover = HoverRandomPos.getPos(this.mob, 8, 7, look.x, look.z, ((float) Math.PI / 2F), 3, 1);
        if (hover != null) {
            return hover;
        }
        return AirAndWaterRandomPos.getPos(this.mob, 8, 4, -2, look.x, look.z, (double) ((float) Math.PI / 2F));
    }
}
