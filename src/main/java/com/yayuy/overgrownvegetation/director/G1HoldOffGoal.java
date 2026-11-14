package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.HoardTable;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 森が貯めている間の G1(2026-11-14、司令塔 第 3 段 区切り 3。11-14 ユーザー H1「襲わず、遠巻きにうろつく」)。
 * 段階 0 も 1 段目も持つ。近くのサバイバルのプレイヤーから {@code holdMinBlocks}〜{@code holdMaxBlocks} の所を保ち、
 * ときどき輪に沿って位置を変えながら、プレイヤーの方を見る。標的は外す。殴られたら殴り返す(この Goal が退く)。
 * 「見られている静けさ」= 大きな襲撃の前触れ(植物側の湧きが止まる静けさ)との見分け。
 */
public final class G1HoldOffGoal extends Goal {

    private static final int REPATH = 80;
    private static final double SPEED = 0.9D;

    private final AbstractParasitizedMonster mob;
    @Nullable
    private Player watched;
    private int repath;

    public G1HoldOffGoal(AbstractParasitizedMonster mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!ForestDirector.hoarding() || G1Fate.retaliating(mob) || mob.isRising() || mob.omen() != G1Fate.OMEN_NONE) return false;
        G1Fate f = mob.fate();
        if (f != null && (f.route() != ForestDirector.Route.NONE || f.scout().active())) return false;
        HoardTable t = DesignData.director().hoard;
        watched = mob.level().getNearestPlayer(mob.getX(), mob.getY(), mob.getZ(), t.holdMaxBlocks + 24.0D,
                e -> !e.isSpectator() && !((Player) e).isCreative());
        return watched != null;
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
        watched = null;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (watched == null) return;
        if (mob.getTarget() != null) mob.setTarget(null);
        mob.getLookControl().setLookAt(watched, 10.0F, 30.0F);
        HoardTable t = DesignData.director().hoard;
        Vec3 from = watched.position();
        Vec3 here = mob.position();
        double dx = here.x - from.x, dz = here.z - from.z, dist = Math.sqrt(dx * dx + dz * dz);
        boolean tooNear = dist < t.holdMinBlocks, tooFar = dist > t.holdMaxBlocks;
        if (--repath > 0 && !tooNear && !mob.getNavigation().isDone()) return;
        repath = REPATH + mob.getRandom().nextInt(40);
        double ring = (t.holdMinBlocks + t.holdMaxBlocks) / 2.0D;
        double ang = dist < 1.0E-6D ? mob.getRandom().nextDouble() * Math.PI * 2.0D : Math.atan2(dz, dx);
        if (!tooNear && !tooFar) {
            // 輪の上で少し位置を変える(遠巻きにうろつく)
            ang += (mob.getRandom().nextDouble() - 0.5D) * 0.8D;
        }
        Vec3 goal = new Vec3(from.x + Math.cos(ang) * ring, here.y, from.z + Math.sin(ang) * ring);
        Vec3 to = LandRandomPos.getPosTowards(mob, 8, 7, goal);
        if (to == null) to = goal;
        mob.getNavigation().moveTo(to.x, to.y, to.z, SPEED);
    }
}
