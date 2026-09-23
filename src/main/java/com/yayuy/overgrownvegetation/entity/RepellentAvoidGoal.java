package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 忌避灯({@code block/RepellentLampBlock})の半径に入ったグループ1〜4 の Mob を外へ追い出す Goal(2026-09-19)。
 * {@link GuidedHuntInjector} が仕込む(ヴェロフィルは除く — あちらは灯りを爆撃しに来る側)。
 *
 * <p>優先度は最上位(1)なので、標的を追っている最中でも灯りの半径に足を踏み入れた瞬間に外へ向かう。
 * 半径の縁で「入る → 追い出される → また入る」を繰り返す = 灯りのそばには寄れない、が狙いの挙動。
 * 遠距離攻撃(フィロセンスのビーム・シールダーの迫撃)は半径の外から届くので、灯りは万能ではない。
 */
public class RepellentAvoidGoal extends Goal {

    private static final int REPATH_INTERVAL = 10;

    private final PathfinderMob mob;
    private final double speedModifier;

    private BlockPos lamp;
    private int repathTimer;

    public RepellentAvoidGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Nullable
    private BlockPos lampNearby() {
        return RepellentLamps.nearestActive(this.mob.level(), this.mob.position(), DesignData.defense().lampRadius());
    }

    @Override
    public boolean canUse() {
        if (this.mob.level().isClientSide()) {
            return false;
        }
        this.lamp = lampNearby();
        return this.lamp != null;
    }

    @Override
    public boolean canContinueToUse() {
        this.lamp = lampNearby();
        return this.lamp != null;
    }

    @Override
    public void start() {
        this.repathTimer = 0;
        this.mob.setTarget(null); // 灯りのそばの相手は諦める(縁で睨み合わない)
    }

    @Override
    public void stop() {
        this.lamp = null;
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.lamp == null) {
            return;
        }
        if (--this.repathTimer > 0 && !this.mob.getNavigation().isDone()) {
            return;
        }
        this.repathTimer = REPATH_INTERVAL;
        Vec3 lampPos = Vec3.atCenterOf(this.lamp);
        Vec3 away = DefaultRandomPos.getPosAway(this.mob, 16, 7, lampPos);
        if (away == null) {
            // ランダム探索が外れたら、灯りと反対方向へまっすぐ。
            Vec3 dir = this.mob.position().subtract(lampPos);
            double len = dir.horizontalDistance();
            if (len < 1.0E-3D) {
                dir = new Vec3(1.0D, 0.0D, 0.0D);
                len = 1.0D;
            }
            double reach = DesignData.defense().lampRadius() + 4.0D;
            away = lampPos.add(dir.x / len * reach, 0.0D, dir.z / len * reach);
        }
        this.mob.getNavigation().moveTo(away.x, away.y, away.z, this.speedModifier);
    }
}
