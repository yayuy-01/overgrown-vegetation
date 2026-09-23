package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * グループ2の Mob が、対応するグループ3の捕食者({@link Predation})が近づいてきたら反対方向へ
 * 逃げるゴール。グループ2の Mob は反撃しない代わりに、見つかったら距離を取ろうとする。
 */
public class CritterAvoidPredatorGoal extends Goal {

    private static final double SEARCH_RADIUS = 12.0D;
    private static final float FLEE_SPEED = 1.5F;

    private final PathfinderMob mob;
    private final PathNavigation navigation;
    private LivingEntity predator;
    private Path fleePath;

    public CritterAvoidPredatorGoal(PathfinderMob mob) {
        this.mob = mob;
        this.navigation = mob.getNavigation();
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        List<Mob> predators = this.mob.level().getEntitiesOfClass(Mob.class,
                this.mob.getBoundingBox().inflate(SEARCH_RADIUS),
                candidate -> candidate.isAlive() && Predation.isPredatorOf(candidate, this.mob));
        if (predators.isEmpty()) {
            return false;
        }
        // 一番近い捕食者(同距離なら先に見つかった方)。stream().min() と同じ選び方を、
        // 毎 tick 呼ばれる場所なので Comparator/Optional の生成なしで行う。
        Mob nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (Mob candidate : predators) {
            double distSq = this.mob.distanceToSqr(candidate);
            if (distSq < nearestSq) {
                nearestSq = distSq;
                nearest = candidate;
            }
        }
        this.predator = nearest;
        if (this.predator == null) {
            return false;
        }
        Vec3 away = DefaultRandomPos.getPosAway(this.mob, 14, 7, this.predator.position());
        if (away == null) {
            away = DefaultRandomPos.getPosAway(this.mob, 10, 7, this.predator.position());
        }
        if (away == null || this.predator.distanceToSqr(away.x, away.y, away.z)
                < this.predator.distanceToSqr(this.mob)) {
            return false;
        }
        this.fleePath = this.navigation.createPath(away.x, away.y, away.z, 0);
        return this.fleePath != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.navigation.isDone()
                && this.predator != null
                && this.predator.isAlive()
                && this.mob.distanceToSqr(this.predator) < SEARCH_RADIUS * SEARCH_RADIUS * 1.6D;
    }

    @Override
    public void start() {
        this.navigation.moveTo(this.fleePath, FLEE_SPEED);
    }

    @Override
    public void stop() {
        this.predator = null;
        this.fleePath = null;
    }
}
