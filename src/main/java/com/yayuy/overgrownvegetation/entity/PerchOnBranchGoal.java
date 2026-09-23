package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * トンボが時々、近くの木の枝(葉ブロック/原木)の下面に止まって休むゴール。
 *
 * <p>{@link ModTags#DRAGONFLY_PERCH} のブロックの真下に空きがある位置を探し、そこへ飛んで
 * {@link OvergrownFlyerEntity#STATE_PERCH} で一定時間静止する(無重力固定)。
 */
public class PerchOnBranchGoal extends Goal {

    private static final int SEARCH_RADIUS = 8;
    private static final int MIN_PERCH = 100;
    private static final int MAX_PERCH = 400;
    private static final int MIN_COOLDOWN = 300;
    private static final int MAX_COOLDOWN = 900;

    private final OvergrownFlyerEntity mob;
    private final double approachSpeed;
    private BlockPos perchPos;
    private int perchTicksLeft;
    private int nextPerchTime;
    private boolean settled;

    public PerchOnBranchGoal(OvergrownFlyerEntity mob, double approachSpeed) {
        this.mob = mob;
        this.approachSpeed = approachSpeed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (this.mob.tickCount < this.nextPerchTime || this.mob.getTarget() != null) {
            return false;
        }
        if (this.mob.getLastHurtByMob() != null || this.mob.getRandom().nextInt(160) != 0) {
            return false;
        }
        this.perchPos = findPerch();
        return this.perchPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.perchPos == null || this.mob.getLastHurtByMob() != null || this.mob.getTarget() != null) {
            return false;
        }
        if (!isStillBranch(this.perchPos)) {
            return false;
        }
        return this.settled ? this.perchTicksLeft > 0 : !this.mob.getNavigation().isDone() || this.mob.tickCount % 20 != 0;
    }

    @Override
    public void start() {
        this.settled = false;
        this.perchTicksLeft = MIN_PERCH + this.mob.getRandom().nextInt(MAX_PERCH - MIN_PERCH + 1);
        this.mob.getMoveControl().setWantedPosition(
                this.perchPos.getX() + 0.5D, this.perchPos.getY() + 0.1D, this.perchPos.getZ() + 0.5D, this.approachSpeed);
    }

    @Override
    public void stop() {
        this.mob.setActionState(OvergrownFlyerEntity.STATE_FLY);
        this.mob.setNoGravity(false);
        this.settled = false;
        this.perchPos = null;
        this.nextPerchTime = this.mob.tickCount + MIN_COOLDOWN
                + this.mob.getRandom().nextInt(MAX_COOLDOWN - MIN_COOLDOWN + 1);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Vec3 spot = new Vec3(this.perchPos.getX() + 0.5D, this.perchPos.getY() + 0.1D, this.perchPos.getZ() + 0.5D);
        double distSq = this.mob.position().distanceToSqr(spot);
        if (!this.settled) {
            if (distSq < 0.5D) {
                this.settled = true;
                this.mob.setActionState(OvergrownFlyerEntity.STATE_PERCH);
                this.mob.setNoGravity(true);
                this.mob.getNavigation().stop();
                this.mob.setPos(spot.x, spot.y, spot.z);
                this.mob.setDeltaMovement(Vec3.ZERO);
            } else {
                this.mob.getMoveControl().setWantedPosition(spot.x, spot.y, spot.z, this.approachSpeed);
            }
            return;
        }
        this.mob.setDeltaMovement(Vec3.ZERO);
        this.mob.setPos(spot.x, spot.y, spot.z);
        this.perchTicksLeft--;
    }

    private BlockPos findPerch() {
        BlockPos origin = this.mob.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        // 17×11×17 ≒ 3200 マス × 最大 3 読み。チャンクキャッシュ付きの読み(結果は Level 経由と同じ)。
        ChunkCachedReader reader = new ChunkCachedReader(this.mob.level());
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -2; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    int x = origin.getX() + dx;
                    int y = origin.getY() + dy;
                    int z = origin.getZ() + dz;
                    if (!isStillBranch(reader, x, y, z)) {
                        continue;
                    }
                    double d = origin.distToLowCornerSqr(x, y, z);
                    if (d < bestSq) {
                        bestSq = d;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    /** {@code pos} が「枝の真下の止まれる空き」か(頭上が枝ブロック、その位置は通行可能)。 */
    private boolean isStillBranch(BlockPos pos) {
        if (this.mob.level().getBlockState(pos).blocksMotion()
                || !this.mob.level().getFluidState(pos).isEmpty()) {
            return false;
        }
        return this.mob.level().getBlockState(pos.relative(Direction.UP)).is(ModTags.DRAGONFLY_PERCH);
    }

    /** {@link #isStillBranch(BlockPos)} と同じ判定を、走査用のキャッシュ付き読みで行う。 */
    private static boolean isStillBranch(ChunkCachedReader reader, int x, int y, int z) {
        if (reader.getBlockState(x, y, z).blocksMotion()
                || !reader.getFluidState(x, y, z).isEmpty()) {
            return false;
        }
        return reader.getBlockState(x, y + 1, z).is(ModTags.DRAGONFLY_PERCH);
    }
}
