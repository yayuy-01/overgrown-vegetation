package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 偵察の歩き(2026-11-14、司令塔 第 2 段 区切り 2)。段の進みは {@link G1Scout} が決め、ここは体を動かすだけ。
 * 行く = 眺める場所へ / 眺める = 立ち止まって、近くのプレイヤー(いなければ拠点の真ん中)を見る / 去る = 拠点から離れる向きへ。
 * 遠くまでの経路は探さない(24 ブロック先を目あてに、歩ける所を近くから選ぶ。{@link G1WalkAwayGoal} と同じ)。
 * 偵察の間は標的を外す。殴られたら退き、3 段の 1 撃が殴り返す。
 */
public final class G1ScoutGoal extends Goal {

    private static final int REPATH = 60;
    private static final double SPEED = 1.0D;
    /** 眺めるとき、プレイヤーを探す半径(ブロック)。 */
    private static final double LOOK_RANGE = 64.0D;

    private final AbstractParasitizedMonster mob;
    private int repath;

    public G1ScoutGoal(AbstractParasitizedMonster mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        G1Fate fate = mob.fate();
        return fate != null && fate.scout().active() && !G1Fate.retaliating(mob);
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
        if (mob.getTarget() != null) mob.setTarget(null);
        G1Scout s = mob.fate().scout();
        if (s.phase() == G1Scout.Phase.WATCH) {
            mob.getNavigation().stop();
            Player p = mob.level().getNearestPlayer(mob.getX(), mob.getY(), mob.getZ(), LOOK_RANGE,
                    e -> !e.isSpectator() && !((Player) e).isCreative());
            Vec3 at = p != null ? p.getEyePosition() : s.baseCenter().add(0.0D, mob.getEyeHeight(), 0.0D);
            mob.getLookControl().setLookAt(at.x, at.y, at.z, 10.0F, 30.0F);
            return;
        }
        if (--repath > 0 && !mob.getNavigation().isDone()) return;
        repath = REPATH;
        Vec3 here = mob.position();
        Vec3 goal;
        if (s.phase() == G1Scout.Phase.GO) {
            goal = s.watchPoint();
        } else {
            Vec3 away = here.subtract(s.baseCenter());
            double len = Math.sqrt(away.x * away.x + away.z * away.z);
            away = len < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(away.x / len, 0.0D, away.z / len);
            goal = here.add(away.scale(24.0D));
        }
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
