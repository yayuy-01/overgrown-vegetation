package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 「ステノデクティア」が近くの蔦の実・熟した垂れ草の光る果実({@link ModTags#STENODICTYA_FOOD})に
 * 近づいて食べる(ホバリングしながら {@link OvergrownFlyerEntity#STATE_EAT})ゴール。実体のブロックは
 * 変化させない演出のみ。
 */
public class FlyerEatFoliageGoal extends Goal {

    private static final int SEARCH_RADIUS = 10;
    /** {@code eat} の 1 周(0.5 秒)。 */
    private static final int EAT_CYCLE_TICKS = 10;
    /**
     * 1 周のうち頭を下げきる tick({@code eat} の 0.25 秒)。食べる音はここで鳴らす。画面ではアニメが補間
     * ({@link OvergrownFlyerEntity#TRANSITION_TICKS})のぶん遅れて始まるので足してある。
     */
    private static final int EAT_PECK_TICK = OvergrownFlyerEntity.TRANSITION_TICKS + 5;
    /** 食べている長さ。{@code eat} を 5 周ぶん + 補間。 */
    private static final int EAT_TICKS = OvergrownFlyerEntity.TRANSITION_TICKS + 5 * EAT_CYCLE_TICKS;
    private static final int MIN_COOLDOWN = 200;
    private static final int MAX_COOLDOWN = 600;

    private final OvergrownFlyerEntity mob;
    private final double approachSpeed;
    private BlockPos foodPos;
    private int eatTicksLeft;
    private int nextEatTime;
    private boolean eating;

    public FlyerEatFoliageGoal(OvergrownFlyerEntity mob, double approachSpeed) {
        this.mob = mob;
        this.approachSpeed = approachSpeed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.mob.tickCount < this.nextEatTime || this.mob.getTarget() != null
                || this.mob.isPerching() || this.mob.getLastHurtByMob() != null) {
            return false;
        }
        if (this.mob.getRandom().nextInt(100) != 0) {
            return false;
        }
        this.foodPos = findFood();
        return this.foodPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.foodPos == null || this.mob.getTarget() != null || this.mob.getLastHurtByMob() != null) {
            return false;
        }
        if (!this.mob.level().getBlockState(this.foodPos).is(ModTags.STENODICTYA_FOOD)) {
            return false;
        }
        return !this.eating || this.eatTicksLeft > 0;
    }

    @Override
    public void start() {
        this.eating = false;
    }

    @Override
    public void stop() {
        this.mob.setActionState(OvergrownFlyerEntity.STATE_FLY);
        this.eating = false;
        this.foodPos = null;
        this.nextEatTime = this.mob.tickCount + MIN_COOLDOWN
                + this.mob.getRandom().nextInt(MAX_COOLDOWN - MIN_COOLDOWN + 1);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Vec3 spot = Vec3.atCenterOf(this.foodPos).add(0.0D, -0.4D, 0.0D);
        this.mob.getLookControl().setLookAt(spot.x, spot.y, spot.z);
        double distSq = this.mob.position().distanceToSqr(spot);
        if (!this.eating) {
            if (distSq < 1.6D) {
                this.eating = true;
                this.eatTicksLeft = EAT_TICKS;
                this.mob.setActionState(OvergrownFlyerEntity.STATE_EAT);
                this.mob.getNavigation().stop();
            } else {
                this.mob.getMoveControl().setWantedPosition(spot.x, spot.y, spot.z, this.approachSpeed);
            }
            return;
        }
        Vec3 v = this.mob.getDeltaMovement();
        this.mob.setDeltaMovement(v.x * 0.5D, v.y * 0.5D + 0.003D, v.z * 0.5D);
        // 頭を下げきる瞬間に鳴らす。2026-09-23 までは補間を数えておらず、頭を上げ戻す途中(1 周の 0.4 秒)で鳴っていた。
        int elapsed = EAT_TICKS - this.eatTicksLeft + 1;     // 食べ始めた tick から数えて何 tick 目か
        if (elapsed >= EAT_PECK_TICK && (elapsed - EAT_PECK_TICK) % EAT_CYCLE_TICKS == 0) {
            this.mob.level().playSound(null, this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                    SoundEvents.BEE_POLLINATE, SoundSource.NEUTRAL, 0.4F, 1.4F);
        }
        this.eatTicksLeft--;
    }

    private BlockPos findFood() {
        BlockPos origin = this.mob.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        // 21³ ≒ 9000 マスの読み。チャンクキャッシュ付きの読み(結果は Level#getBlockState と同じ)。
        ChunkCachedReader reader = new ChunkCachedReader(this.mob.level());
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    int x = origin.getX() + dx;
                    int y = origin.getY() + dy;
                    int z = origin.getZ() + dz;
                    if (!reader.getBlockState(x, y, z).is(ModTags.STENODICTYA_FOOD)) {
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
}
