package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * {@code vine2}・{@code vine3} の近接防御。本体は回転させず、この2本の蔦だけを振って
 * 薙ぎ払う({@code OvergrownNexusModel} が {@code DATA_SWEEP_STATE}/{@code DATA_SWEEP_PROGRESS} を見て
 * 2本を左右対称にひねる)。プレイヤー、またはグループ1〜10いずれにも属さない Mob
 * ({@link OvergrownNexusEntity#isHostileTarget})が {@code raidNexusSweepRange}(既定8)まで
 * 近づくと発動し、{@code raidNexusSweepHitRange}(既定8)以内の全対象へ大ダメージ+非常に大きい
 * ノックバックで本体から引き剥がす。振り抜いた後は {@code SWEEP_RETURN} 状態で
 * なめらかにレストポーズへ戻る({@link #updateReturn}）。
 */
public class NexusSweepGoal extends Goal {

    private static final int WINDUP_TICKS = 8;
    private static final int SWING_TICKS = 10;
    private static final int TOTAL_TICKS = WINDUP_TICKS + SWING_TICKS;
    private static final int RETURN_TICKS = 10;
    /**
     * 実際に判定を取る tick(振り始めからの経過)。{@code OvergrownNexusModel#applySweep} は
     * 振り(SWING)の間 vine2・vine3 を +35°→−35°→(略) と振り抜くが、以前はここが
     * {@code WINDUP_TICKS}(振りかぶりの終わり=まだ+35°で静止した瞬間)になっていて、
     * 見た目が実際に振り抜くより先にダメージ・ノックバックが発生してしまっていた。
     * 振りの約4割(モデル側の角度がちょうど0°付近、=対象の方へ向いた瞬間)まで進めてから
     * 判定を取るよう遅らせ、見た目とタイミングを合わせた。
     */
    private static final int HIT_TICK = WINDUP_TICKS + 4;

    private final OvergrownNexusEntity nexus;
    private int left;
    private boolean hit;
    private int returnLeft;
    private int nextTime;

    public NexusSweepGoal(OvergrownNexusEntity nexus) {
        this.nexus = nexus;
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    @Override
    public boolean canUse() {
        if (nexus.isSweepBusy() || nexus.tickCount < nextTime) {
            return false;
        }
        double range = OvergrownVegetationConfig.COMMON.raidNexusSweepRange.get();
        return !nexus.level().getEntitiesOfClass(LivingEntity.class, nexus.getBoundingBox().inflate(range),
                nexus::isHostileTarget).isEmpty();
    }

    @Override
    public boolean canContinueToUse() {
        return nexus.isAlive() && nexus.isSweepBusy();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        left = TOTAL_TICKS;
        hit = false;
        nexus.setSweepState(OvergrownNexusEntity.SWEEP_WINDUP);
        nexus.setSweepProgress(0.0F);
        nexus.playSound(SoundEvents.RAVAGER_ROAR, 1.4F, 0.7F);
    }

    @Override
    public void stop() {
        nexus.setSweepState(OvergrownNexusEntity.SWEEP_IDLE);
        nexus.setSweepProgress(0.0F);
    }

    @Override
    public void tick() {
        int elapsed = TOTAL_TICKS - left;
        if (elapsed >= WINDUP_TICKS && nexus.getSweepState() == OvergrownNexusEntity.SWEEP_WINDUP) {
            nexus.setSweepState(OvergrownNexusEntity.SWEEP_SWING);
        }
        if (nexus.getSweepState() == OvergrownNexusEntity.SWEEP_RETURN) {
            updateReturn();
            if (--returnLeft <= 0) {
                nexus.setSweepState(OvergrownNexusEntity.SWEEP_IDLE);
                nexus.setSweepProgress(0.0F);
                nextTime = nexus.tickCount + OvergrownVegetationConfig.COMMON.raidNexusSweepCooldownTicks.get();
            }
            return;
        }

        int phaseLen = nexus.getSweepState() == OvergrownNexusEntity.SWEEP_WINDUP
                ? WINDUP_TICKS : SWING_TICKS;
        int phaseElapsed = nexus.getSweepState() == OvergrownNexusEntity.SWEEP_WINDUP
                ? elapsed : elapsed - WINDUP_TICKS;
        nexus.setSweepProgress(phaseLen <= 0 ? 1.0F : (float) phaseElapsed / phaseLen);

        if (!hit && elapsed >= HIT_TICK) {
            hit = true;
            doSweep();
        }
        if (--left <= 0) {
            // 振り抜き終わり(OvergrownNexusModel#applySweep の SWING 終端、-55°)から一気に
            // 0へ戻すと不自然なので、専用のイージング区間(SWEEP_RETURN)を挟む。
            nexus.setSweepState(OvergrownNexusEntity.SWEEP_RETURN);
            nexus.setSweepProgress(0.0F);
            returnLeft = RETURN_TICKS;
        }
    }

    /** SWING 終端の角度(-55°、{@code OvergrownNexusModel#applySweep} 参照)から0へなめらかに戻す。 */
    private void updateReturn() {
        float progress = Mth.clamp(1.0F - (float) returnLeft / RETURN_TICKS, 0.0F, 1.0F);
        nexus.setSweepProgress(progress);
    }

    private void doSweep() {
        if (!(nexus.level() instanceof ServerLevel level)) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double hitRange = cfg.raidNexusSweepHitRange.get();
        float damage = (float) (double) cfg.raidNexusSweepDamage.get();
        double knockback = cfg.raidNexusSweepKnockback.get();
        Vec3 center = nexus.position().add(0.0D, 1.0D, 0.0D);

        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, nexus.getBoundingBox().inflate(hitRange),
                nexus::isHostileTarget)) {
            Vec3 to = victim.position().subtract(center);
            if (to.horizontalDistance() > hitRange) {
                continue;
            }
            if (victim.hurt(level.damageSources().mobAttack(nexus), damage)) {
                Vec3 push = to.horizontalDistance() > 1.0E-4D
                        ? new Vec3(to.x, 0.0D, to.z).normalize()
                        : new Vec3(1.0D, 0.0D, 0.0D);
                victim.push(push.x * knockback, 0.6D, push.z * knockback);
                victim.hurtMarked = true;
            }
        }

        nexus.playSound(SoundEvents.RAVAGER_ATTACK, 1.4F, 0.7F);
        level.playSound(null, nexus.blockPosition(), SoundEvents.WEEPING_VINES_BREAK, SoundSource.HOSTILE, 2.0F, 0.5F);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, nexus.getX(), nexus.getY() + 1.5D, nexus.getZ(),
                8, 3.0D, 0.8D, 3.0D, 0.0D);
        level.sendParticles(ParticleTypes.CRIT, nexus.getX(), nexus.getY() + 1.0D, nexus.getZ(),
                40, 3.5D, 1.0D, 3.5D, 0.3D);
    }
}
