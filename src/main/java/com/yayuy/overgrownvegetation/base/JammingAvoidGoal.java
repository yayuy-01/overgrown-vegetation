package com.yayuy.overgrownvegetation.base;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.RepellentAvoidGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;

import javax.annotation.Nullable;

/**
 * 妨害灯の半径からグループ5〜6 を追い出す Goal(2026-10-01)。追い出し方は忌避灯の {@link RepellentAvoidGoal} と同じで、
 * 見る台帳だけが {@link JammingLamps}(グループ1〜4 は忌避灯の Goal が妨害灯も見るので、こちらは足さない)。
 */
public class JammingAvoidGoal extends RepellentAvoidGoal {

    private final PathfinderMob jammed;

    public JammingAvoidGoal(PathfinderMob mob, double speedModifier) {
        super(mob, speedModifier);
        this.jammed = mob;
    }

    @Nullable
    @Override
    protected BlockPos lampNearby() {
        return JammingLamps.nearestActive(this.jammed.level(), this.jammed.position(), DesignData.defense().lampRadius());
    }
}
