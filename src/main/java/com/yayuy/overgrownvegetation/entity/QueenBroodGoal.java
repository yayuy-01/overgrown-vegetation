package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/**
 * 女王が戦闘中にアルボラを産む(2026-09-20、ユーザー指定 2026-09-19)。
 *
 * <p>{@code queenBroodIntervalTicks} ごとに {@code queenBroodCount} 体。産むのは戦っているあいだだけで、
 * 周りに既に十分いるときは産まない —— 放っておくと部屋がアルボラで埋まって処理が重くなる。
 */
public class QueenBroodGoal extends Goal {

    /** これ以上アルボラが周りに居たら産まない。 */
    private static final int CROWD_LIMIT = 12;
    private static final double CROWD_RADIUS = 14.0D;
    /** 産む動作の長さ(tick)。 */
    private static final int LAY_TICKS = 20;

    private final ArboraQueenEntity queen;
    private int cooldown;
    private int ticks;

    public QueenBroodGoal(ArboraQueenEntity queen) {
        this.queen = queen;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        LivingEntity target = queen.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        AABB box = queen.getBoundingBox().inflate(CROWD_RADIUS);
        return queen.level().getEntitiesOfClass(ArboraEntity.class, box).size() < CROWD_LIMIT;
    }

    @Override
    public boolean canContinueToUse() {
        return ticks < LAY_TICKS;
    }

    @Override
    public void start() {
        ticks = 0;
        queen.getNavigation().stop();
    }

    @Override
    public void stop() {
        cooldown = DesignData.nest().queenBroodIntervalTicks();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        ticks++;
        if (ticks != LAY_TICKS || !(queen.level() instanceof ServerLevel level)) {
            return;
        }
        NestTable t = DesignData.nest();
        BlockPos at = queen.blockPosition();
        for (int i = 0; i < t.queenBroodCount(); i++) {
            ArboraEntity child = ModEntities.ARBORA.get().create(level);
            if (child == null) {
                continue;
            }
            double a = level.random.nextDouble() * Math.PI * 2.0D;
            child.moveTo(queen.getX() + Math.cos(a) * 1.8D, queen.getY(), queen.getZ() + Math.sin(a) * 1.8D,
                    (float) Math.toDegrees(a), 0.0F);
            child.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.MOB_SUMMONED, null);
            child.setTarget(queen.getTarget());
            level.addFreshEntity(child);
        }
        level.playSound(null, at, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.4F, 0.6F);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                queen.getX(), queen.getY() + 0.4D, queen.getZ(), 24, 0.8D, 0.2D, 0.8D, 0.02D);
    }
}
