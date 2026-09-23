package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

/**
 * メガラクネが陸に上がってしまったとき、周囲を広めに探して<b>いちばん近い水</b>へ向かうゴール。
 * バニラの {@link net.minecraft.world.entity.ai.goal.TryFindWaterGoal}(近距離・1マス水でも可)より
 * 探索半径が広く、追跡対象がいない限り水に浸かるまで諦めない。
 */
public class MegarachneReturnToWaterGoal extends Goal {

    private static final int SEARCH_RADIUS = 12;
    private static final int VERTICAL = 6;

    private final PathfinderMob mob;
    private final double speed;
    private BlockPos targetWater;

    public MegarachneReturnToWaterGoal(PathfinderMob mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.mob.isInWater() || this.mob.getTarget() != null) {
            return false;
        }
        this.targetWater = findNearestWater();
        return this.targetWater != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.mob.isInWater()
                && this.mob.getTarget() == null
                && this.targetWater != null
                && !this.mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        Path path = this.mob.getNavigation().createPath(this.targetWater, 0);
        this.mob.getNavigation().moveTo(path, this.speed);
    }

    @Override
    public void stop() {
        this.targetWater = null;
    }

    private BlockPos findNearestWater() {
        BlockPos origin = this.mob.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        // 陸に上がっている間は毎 tick 25×25×13 ≒ 8000 マスを読むので、チャンクキャッシュ付きの読みにする
        // (結果は Level#getFluidState と同じ)。
        ChunkCachedReader reader = new ChunkCachedReader(this.mob.level());
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -VERTICAL; dy <= VERTICAL; dy++) {
                    int x = origin.getX() + dx;
                    int y = origin.getY() + dy;
                    int z = origin.getZ() + dz;
                    if (!reader.getFluidState(x, y, z).is(FluidTags.WATER)) {
                        continue;
                    }
                    // 少しでも深い水を優先(上に水があるマス)。
                    double bias = reader.getFluidState(x, y + 1, z).is(FluidTags.WATER) ? 0.0D : 8.0D;
                    double distSq = origin.distToLowCornerSqr(x, y, z) + bias;
                    if (distSq < bestSq) {
                        bestSq = distSq;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
        }
        return best;
    }
}
