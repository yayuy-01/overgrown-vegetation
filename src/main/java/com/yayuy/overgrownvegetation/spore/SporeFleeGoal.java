package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobInfectionSpec;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 動物が汚染から逃げる(2026-11-14 ユーザー決定。{@link MobInfection})。向きは暴露量の判定のたびに {@link MobInfection#afterCheck} が決めて
 * 個体に持たせ、この Goal はその向きへ fleeDistance 先を目指して歩くだけ(ここでは濃度を読まない)。
 * 村人は脳で動くので、行き先を脳の WALK_TARGET に入れる(ほかの行動に上書きされることがある【仮】)。
 */
public final class SporeFleeGoal extends Goal {

    private final PathfinderMob mob;
    private Vec3 target;

    public SporeFleeGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.getRandom().nextInt(reducedTickDelay(10)) != 0) return false;
        if (!MobInfection.isFleer(mob) || !MobInfection.shouldFlee(mob, mob.level().getGameTime())) return false;
        target = pickTarget();
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!MobInfection.shouldFlee(mob, mob.level().getGameTime())) return false;
        if (mob instanceof Villager v) return v.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET);
        return !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        double speed = speed();
        if (mob instanceof Villager v) {
            v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new BlockPosTracker(BlockPos.containing(target)), (float) (speed * 0.5D), 1));
        } else {
            mob.getNavigation().moveTo(target.x, target.y, target.z, speed);
        }
    }

    @Override
    public void stop() {
        if (!(mob instanceof Villager)) mob.getNavigation().stop();
        target = null;
    }

    private double speed() {
        MobInfectionSpec s = DesignData.sporeField().mobInfection();
        return MobInfection.isInfected(mob) ? s.infectedFleeSpeed() : s.fleeSpeed();
    }

    /**
     * 逃げる向きへ fleeDistance 先(歩ける所が見つからなければその点そのもの)。感染した後は弱ってふらつく(11-14 ユーザー):
     * 向きを左右に wobbleDegrees まで乱し、1 回に infectedStepDistance だけ歩く(歩き直すまでの間が立ち止まりになる)。
     */
    private Vec3 pickTarget() {
        SporeExposure ex = mob.getData(ModAttachments.SPORE_EXPOSURE);
        MobInfectionSpec s = DesignData.sporeField().mobInfection();
        boolean infected = ex.infected;
        int dist = infected ? s.infectedStepDistance() : s.fleeDistance();
        double dx = ex.fleeX, dz = ex.fleeZ;
        if (infected && s.wobbleDegrees() > 0.0D) {
            double a = Math.toRadians((mob.getRandom().nextDouble() * 2.0D - 1.0D) * s.wobbleDegrees());
            double c = Math.cos(a), sn = Math.sin(a);
            double rx = dx * c - dz * sn, rz = dx * sn + dz * c;
            dx = rx;
            dz = rz;
        }
        Vec3 want = mob.position().add(dx * dist, 0.0D, dz * dist);
        Vec3 p = DefaultRandomPos.getPosTowards(mob, dist, 7, want, infected ? Math.PI / 6.0D : Math.PI / 3.0D);
        return p != null ? p : want;
    }
}
