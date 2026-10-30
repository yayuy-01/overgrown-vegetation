package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * {@code vine}(本体)の防衛反応。次のいずれかが成立すると発動する:
 * <ul>
 *   <li>プレイヤーが {@code raidNexusSporeTriggerRange}(既定10)以内にいる。</li>
 *   <li>グループ1〜10いずれにも属さない Mob(まだ寄生されていない野生の Mob、
 *   {@link OvergrownNexusEntity#isHostileTarget}）が {@code raidNexusSporeMobTriggerRange}(既定15)
 *   以内にいる。</li>
 * </ul>
 *
 * <p>{@code housinou}(胞子嚢)が膨らみ(INFLATE)→頂点でばら撒き(burst)→しぼむ(DEFLATE)、という
 * 一連のアニメーションを {@code DATA_SPORE_STATE}/{@code DATA_SPORE_PROGRESS} 経由でモデル側
 * ({@code OvergrownNexusModel#applySpore}）に伝える。ばら撒き自体(パーティクル + 「胞子感染2」付与)は
 * 膨らみきった瞬間(INFLATE→DEFLATE の切り替わり)に1回だけ起こる。
 */
public class NexusSporeBurstGoal extends Goal {

    private static final int INFLATE_TICKS = 8;
    private static final int DEFLATE_TICKS = 12;

    private final OvergrownNexusEntity nexus;
    private int ticksInPhase;
    private int nextTime;

    public NexusSporeBurstGoal(OvergrownNexusEntity nexus) {
        this.nexus = nexus;
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    @Override
    public boolean canUse() {
        if (nexus.isSporeBusy() || nexus.tickCount < nextTime) {
            return false;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        return hasNearbyPlayer(cfg.raidNexusSporeTriggerRange.get())
                || hasNearbyUngroupedMob(cfg.raidNexusSporeMobTriggerRange.get());
    }

    @Override
    public boolean canContinueToUse() {
        return nexus.isAlive() && nexus.isSporeBusy();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        ticksInPhase = 0;
        nexus.setSporeState(OvergrownNexusEntity.SPORE_INFLATE);
        nexus.setSporeProgress(0.0F);
    }

    @Override
    public void stop() {
        nexus.setSporeState(OvergrownNexusEntity.SPORE_IDLE);
        nexus.setSporeProgress(0.0F);
    }

    @Override
    public void tick() {
        ticksInPhase++;
        if (nexus.getSporeState() == OvergrownNexusEntity.SPORE_INFLATE) {
            if (ticksInPhase >= INFLATE_TICKS) {
                nexus.setSporeProgress(1.0F);
                burst();
                nexus.setSporeState(OvergrownNexusEntity.SPORE_DEFLATE);
                ticksInPhase = 0;
            } else {
                nexus.setSporeProgress((float) ticksInPhase / INFLATE_TICKS);
            }
        } else {
            if (ticksInPhase >= DEFLATE_TICKS) {
                nexus.setSporeState(OvergrownNexusEntity.SPORE_IDLE);
                nexus.setSporeProgress(0.0F);
                nextTime = nexus.tickCount + OvergrownVegetationConfig.COMMON.raidNexusSporeBurstCooldownTicks.get();
            } else {
                nexus.setSporeProgress(1.0F - (float) ticksInPhase / DEFLATE_TICKS);
            }
        }
    }

    private boolean hasNearbyPlayer(double range) {
        AABB area = nexus.getBoundingBox().inflate(range);
        return !nexus.level().getEntitiesOfClass(Player.class, area,
                p -> nexus.isHostileTarget(p) && nexus.distanceToSqr(p) <= range * range).isEmpty();
    }

    private boolean hasNearbyUngroupedMob(double range) {
        AABB area = nexus.getBoundingBox().inflate(range);
        return !nexus.level().getEntitiesOfClass(LivingEntity.class, area,
                e -> !(e instanceof Player) && nexus.isHostileTarget(e)
                        && nexus.distanceToSqr(e) <= range * range).isEmpty();
    }

    private void burst() {
        if (!(nexus.level() instanceof ServerLevel level)) {
            return;
        }
        double radius = OvergrownVegetationConfig.COMMON.raidNexusSporeBurstRadius.get();
        Vec3 hana = nexus.hanaWorldPosition();

        // hana の位置から、まず密な噴出。
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, hana.x, hana.y, hana.z, 80,
                1.0D, 1.0D, 1.0D, 0.03D);
        level.playSound(null, nexus.blockPosition(), SoundEvents.MOSS_PLACE, SoundSource.HOSTILE, 3.0F, 0.6F);

        // hana を中心に、外側へ広がっていくリングを何段か送って「周囲に拡散する」見た目にする。
        int rings = 5;
        for (int ring = 1; ring <= rings; ring++) {
            double ringRadius = radius * ring / rings;
            int pointsOnRing = 10 + ring * 4;
            for (int i = 0; i < pointsOnRing; i++) {
                double angle = 2.0D * Math.PI * i / pointsOnRing;
                double px = hana.x + Math.cos(angle) * ringRadius;
                double pz = hana.z + Math.sin(angle) * ringRadius;
                double py = nexus.getY() + 0.2D + nexus.getRandom().nextDouble() * 1.5D;
                level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, px, py, pz, 1, 0.15D, 0.05D, 0.15D, 0.01D);
            }
        }

        AABB area = nexus.getBoundingBox().inflate(radius);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, area, nexus::isHostileTarget)) {
            if (victim.distanceToSqr(nexus) > radius * radius) {
                continue;
            }
            SporeInfectionEffects.plantAttack(victim, 1);
        }
    }
}
