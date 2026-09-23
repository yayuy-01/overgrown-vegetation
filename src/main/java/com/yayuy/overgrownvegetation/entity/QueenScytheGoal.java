package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 女王の<b>溜めてからの広範囲の鎌</b>(2026-09-20)。
 *
 * <p>{@link #WINDUP} tick 溜めてから前方 {@link #ARC} 度・{@link #REACH} ブロックを薙ぐ。
 * 溜めのあいだ女王は足を止めて標的を向き続けるので、横か後ろへ回れば避けられる —— 部屋が狭いので、
 * 産まれたアルボラに道を塞がれていると避けきれない、という噛み合わせ。
 */
public class QueenScytheGoal extends Goal {

    /**
     * 溜め(tick)。この tick に薙ぐ。{@code scythe} アニメは <b>1.25 秒で鎌が正面を通る</b>ように作る
     * (補間 {@link ArboraQueenEntity#TRANSITION_TICKS} のぶん後ろへずらしてある)。
     * いまの {@code scythe} は仮で、{@code attack}(0.6 秒で叩きつける繰り返し)の写しなので合っていない ——
     * 画面では 0.4 秒・1.0 秒に空振りが 2 回見え、当たるのはその 0.25 秒あと(2026-09-23 の監査)。
     */
    private static final int WINDUP = ArboraQueenEntity.TRANSITION_TICKS + 25;
    /** 薙いでから構えを解くまで。{@code scythe} アニメの長さは 1.25 + 0.40 = 1.65 秒にする。 */
    private static final int SWING = 8;
    private static final double REACH = 6.0D;
    /** 薙ぐ角度(度、正面から左右に半分ずつ)。 */
    private static final double ARC = 150.0D;
    private static final float DAMAGE = 12.0F;
    private static final int COOLDOWN = 160;

    private final ArboraQueenEntity queen;
    private int cooldown;
    private int ticks;
    private boolean swung;

    public QueenScytheGoal(ArboraQueenEntity queen) {
        this.queen = queen;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        LivingEntity target = queen.getTarget();
        return target != null && target.isAlive() && queen.distanceToSqr(target) <= REACH * REACH;
    }

    @Override
    public boolean canContinueToUse() {
        return ticks < WINDUP + SWING;
    }

    @Override
    public void start() {
        ticks = 0;
        swung = false;
        queen.setActionState(ArboraQueenEntity.STATE_SCYTHE);
        queen.getNavigation().stop();
    }

    @Override
    public void stop() {
        queen.setActionState(AbstractOvergrownInvaderEntity.STATE_IDLE);
        cooldown = COOLDOWN;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        ticks++;
        LivingEntity target = queen.getTarget();
        if (target != null && ticks < WINDUP) {
            queen.getLookControl().setLookAt(target, 30.0F, 30.0F);      // 溜め中は向き直る
        }
        if (ticks == WINDUP - 10 && queen.level() instanceof ServerLevel level) {
            level.playSound(null, queen.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.2F, 1.3F);
        }
        if (ticks < WINDUP || swung) {
            return;
        }
        swung = true;
        sweep();
    }

    private void sweep() {
        Vec3 facing = Vec3.directionFromRotation(0.0F, queen.getYRot());
        double half = Math.cos(Math.toRadians(ARC / 2.0D));
        AABB box = queen.getBoundingBox().inflate(REACH, 2.0D, REACH);
        for (LivingEntity victim : queen.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (victim == queen || !queen.canAttack(victim)) {
                continue;
            }
            Vec3 to = victim.position().subtract(queen.position());
            double d = to.horizontalDistance();
            if (d > REACH || d < 1.0E-3D) {
                continue;
            }
            if (new Vec3(to.x, 0.0D, to.z).normalize().dot(facing) < half) {
                continue;                                             // 円弧の外(横や後ろ)
            }
            victim.hurt(queen.damageSources().mobAttack(queen), DAMAGE);
            victim.knockback(0.8D, -to.x, -to.z);
        }
        if (queen.level() instanceof ServerLevel level) {
            for (int i = 0; i <= 12; i++) {
                double a = Math.toRadians(-ARC / 2.0D + ARC * i / 12.0D);
                Vec3 dir = facing.yRot((float) a).scale(REACH * 0.8D);
                level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                        queen.getX() + dir.x, queen.getY() + 1.2D, queen.getZ() + dir.z,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
            level.playSound(null, queen.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP,
                    SoundSource.HOSTILE, 1.6F, 0.6F);
        }
    }
}
