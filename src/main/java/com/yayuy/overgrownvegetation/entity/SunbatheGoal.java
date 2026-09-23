package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * イアンタサウルスが帆で日光を浴びて体温を上げるゴール。獲物がおらず、空が見えて明るい場所に
 * いるとき、時々その場で立ち止まって日光浴し({@link AbstractOvergrownPredatorEntity#STATE_SPECIAL}、
 * {@code bask} アニメ)、その間ゆっくり体力を回復する。
 */
public class SunbatheGoal extends Goal {

    private static final int MIN_BASK = 100;
    private static final int MAX_BASK = 300;
    private static final int MIN_COOLDOWN = 300;
    private static final int MAX_COOLDOWN = 800;
    private static final int HEAL_INTERVAL = 40;

    private final AbstractOvergrownPredatorEntity mob;
    private int baskTicksLeft;
    private int nextBaskTime;

    public SunbatheGoal(AbstractOvergrownPredatorEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (this.mob.tickCount < this.nextBaskTime || this.mob.getTarget() != null
                || this.mob.getLastHurtByMob() != null) {
            return false;
        }
        if (!this.mob.onGround() || !this.mob.getNavigation().isDone()) {
            return false;
        }
        if (this.mob.getRandom().nextInt(140) != 0) {
            return false;
        }
        return isSunlit();
    }

    @Override
    public boolean canContinueToUse() {
        return this.baskTicksLeft > 0 && this.mob.getTarget() == null
                && this.mob.getLastHurtByMob() == null && isSunlit();
    }

    @Override
    public void start() {
        this.baskTicksLeft = MIN_BASK + this.mob.getRandom().nextInt(MAX_BASK - MIN_BASK + 1);
        this.mob.getNavigation().stop();
        this.mob.setActionState(AbstractOvergrownPredatorEntity.STATE_SPECIAL);
    }

    @Override
    public void stop() {
        this.mob.setActionState(AbstractOvergrownPredatorEntity.STATE_IDLE);
        this.nextBaskTime = this.mob.tickCount + MIN_COOLDOWN
                + this.mob.getRandom().nextInt(MAX_COOLDOWN - MIN_COOLDOWN + 1);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.mob.getNavigation().stop();
        this.mob.setDeltaMovement(0.0D, this.mob.getDeltaMovement().y, 0.0D);
        if (this.baskTicksLeft % HEAL_INTERVAL == 0 && this.mob.getHealth() < this.mob.getMaxHealth()) {
            this.mob.heal(1.0F);
        }
        this.baskTicksLeft--;
    }

    private boolean isSunlit() {
        if (!this.mob.level().isDay()) {
            return false;
        }
        BlockPos pos = this.mob.blockPosition();
        return this.mob.level().canSeeSky(pos)
                && this.mob.level().getBrightness(net.minecraft.world.level.LightLayer.SKY, pos) >= 12;
    }
}
