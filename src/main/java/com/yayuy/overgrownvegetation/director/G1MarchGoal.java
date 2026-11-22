package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 拠点へ攻めて行く歩き(2026-11-14、司令塔 第 2 段 区切り 3)。{@link G1March} を持つ段階 0・1 段目が、拠点の真ん中へ歩く。
 * 獲物がいる間は退く(1 撃・種の技が先)。遠くまでの経路は探さない(24 ブロック先を目あてに、歩ける所を近くから選ぶ)。
 */
public final class G1MarchGoal extends Goal {

    private static final int REPATH = 60;
    private static final double SPEED = 1.0D;

    private final AbstractParasitizedMonster mob;
    private int repath;

    public G1MarchGoal(AbstractParasitizedMonster mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!mob.march().active()) return false;
        if (mob.getTarget() != null && mob.getTarget().isAlive()) return false;
        G1Fate f = mob.fate();
        // 司令塔が根付き・苗床・進化に決めた個体はそちらが先
        return f == null || (f.route() == ForestDirector.Route.NONE && !f.scout().active());
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        G1March m = mob.march();
        if (!m.active()) {
            mob.getNavigation().stop();
            return;
        }
        if (--repath > 0 && !mob.getNavigation().isDone()) return;
        repath = REPATH;
        Vec3 here = mob.position();
        Vec3 goal = m.target(mob.getY());
        Vec3 d = goal.subtract(here);
        double dist = Math.sqrt(d.x * d.x + d.z * d.z);
        Vec3 to;
        if (dist <= 20.0D) {
            to = goal;
        } else {
            to = LandRandomPos.getPosTowards(mob, 14, 7, here.add(d.scale(24.0D / dist)));
            if (to == null) to = LandRandomPos.getPos(mob, 10, 7);
        }
        if (to != null) mob.getNavigation().moveTo(to.x, to.y, to.z, SPEED);
    }
}
