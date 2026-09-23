package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 「プロトファスマ」が鈴虫のように時々その場で立ち止まって鳴くゴール。
 * 特に変哲のない Mob なので、これ以外に固有の挙動は持たない。
 */
public class ProtophasmaSingGoal extends Goal {

    private static final int MIN_SING_TICKS = 60;
    private static final int MAX_SING_TICKS = 140;
    private static final int MIN_COOLDOWN = 200;
    private static final int MAX_COOLDOWN = 500;
    /** 鳴き 1 回の長さ。アニメ {@code sing} の 1 周(0.9 秒)と同じ。 */
    private static final int CHIRP_INTERVAL = 18;
    /**
     * 最初に鳴く tick。{@code sing} は 0.15 秒で羽を立て終えて擦り始めるので、そこに合わせる
     * (画面ではアニメが補間 {@link ProtophasmaEntity#TRANSITION_TICKS} のぶん遅れて始まるので足してある)。
     * 2026-09-23 までは残り tick の割り算で鳴らしていて、鳴く瞬間と羽の動きの位相がでたらめだった。
     */
    private static final int CHIRP_FIRST = ProtophasmaEntity.TRANSITION_TICKS + 3;

    private final ProtophasmaEntity mob;
    private int singTicksLeft;
    /** 鳴き始めてからの tick。 */
    private int singTicks;
    private int nextSingTime;

    public ProtophasmaSingGoal(ProtophasmaEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (this.mob.tickCount < this.nextSingTime) {
            return false;
        }
        if (!this.mob.onGround() || this.mob.isInWaterOrBubble()) {
            return false;
        }
        if (this.mob.getLastHurtByMob() != null || this.mob.getTarget() != null) {
            return false;
        }
        if (!this.mob.getNavigation().isDone()) {
            return false;
        }
        return this.mob.getRandom().nextInt(120) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        return this.singTicksLeft > 0 && this.mob.getLastHurtByMob() == null;
    }

    @Override
    public void start() {
        this.singTicksLeft = MIN_SING_TICKS + this.mob.getRandom().nextInt(MAX_SING_TICKS - MIN_SING_TICKS + 1);
        this.singTicks = 0;
        this.mob.getNavigation().stop();
        this.mob.setActionState(AbstractOvergrownCritterEntity.STATE_SPECIAL);
    }

    @Override
    public void stop() {
        this.mob.setActionState(AbstractOvergrownCritterEntity.STATE_IDLE);
        this.nextSingTime = this.mob.tickCount + MIN_COOLDOWN
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
        if (this.singTicks >= CHIRP_FIRST && (this.singTicks - CHIRP_FIRST) % CHIRP_INTERVAL == 0) {
            this.mob.level().playSound(null, this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                    ModSounds.PHALANGIOTARBUS_IDLE.get(), SoundSource.NEUTRAL,
                    0.5F, 1.5F + this.mob.getRandom().nextFloat() * 0.3F);
        }
        this.singTicks++;
        this.singTicksLeft--;
    }
}
