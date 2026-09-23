package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 女王の<b>地面を叩く衝撃波</b>(2026-09-20)。
 *
 * <p>叩いた瞬間から輪が外へ広がり、輪に触れた者を殴り飛ばす。
 * <b>地形は動かさない</b>(ユーザー指定 2026-09-19)—— 波打って見えるのは床のブロックの粒子だけ。
 * 巣の中で地形を動かすと、掘った坑道が壊れて巣が崩れてしまう。
 */
public class QueenShockwaveGoal extends Goal {

    /**
     * 溜めの長さ(tick)。この tick に床を叩く。{@code shockwave} アニメは <b>0.95 秒で床を叩く</b>ように作る
     * (補間 {@link ArboraQueenEntity#TRANSITION_TICKS} のぶん後ろへずらしてある)。
     * いまの {@code shockwave} は仮で {@code attack} の写し(0.6 秒の繰り返し)。2 回目の叩きつけ(1.0 秒)が
     * 輪の出る 1 tick あとに来るのでほぼ合っているが、その前に 0.4 秒の空振りが 1 回見える。
     */
    private static final int WINDUP = ArboraQueenEntity.TRANSITION_TICKS + 19;
    /** 輪が広がる速さ(1 tick に何ブロック)。 */
    private static final double RING_SPEED = 0.7D;
    /** 輪が届く距離。 */
    private static final double RANGE = 9.0D;
    /** 当たったときのダメージ。 */
    private static final float DAMAGE = 7.0F;
    /** 次に使えるようになるまで(tick)。 */
    private static final int COOLDOWN = 200;

    private final ArboraQueenEntity queen;
    private int cooldown;
    private int ticks;
    private boolean slammed;

    public QueenShockwaveGoal(ArboraQueenEntity queen) {
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
        if (target == null || !target.isAlive() || !queen.onGround()) {
            return false;
        }
        double d = queen.distanceToSqr(target);
        return d <= RANGE * RANGE && d >= 4.0D;      // 密着しているときは通常攻撃に任せる
    }

    @Override
    public boolean canContinueToUse() {
        return ticks < WINDUP + (int) (RANGE / RING_SPEED) + 4;
    }

    @Override
    public void start() {
        ticks = 0;
        slammed = false;
        queen.setActionState(ArboraQueenEntity.STATE_SHOCKWAVE);
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
        if (target != null) {
            queen.getLookControl().setLookAt(target, 20.0F, 20.0F);
        }
        if (ticks < WINDUP) {
            return;
        }
        if (!slammed) {
            slammed = true;
            queen.level().playSound(null, queen.blockPosition(), SoundEvents.RAVAGER_ATTACK,
                    SoundSource.HOSTILE, 1.6F, 0.6F);
        }
        double radius = (ticks - WINDUP) * RING_SPEED;
        if (radius > RANGE) {
            return;
        }
        ripple(radius);
        hit(radius);
    }

    /** 床のブロックの粒子で輪を描く。ブロック自体は動かさない。 */
    private void ripple(double radius) {
        if (!(queen.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos floor = queen.blockPosition().below();
        BlockState state = level.getBlockState(floor);
        if (state.isAir()) {
            return;
        }
        BlockParticleOption particle = new BlockParticleOption(ParticleTypes.BLOCK, state);
        int points = (int) Math.max(8, radius * 8);
        for (int i = 0; i < points; i++) {
            double a = i * Math.PI * 2.0D / points;
            double x = queen.getX() + Math.cos(a) * radius;
            double z = queen.getZ() + Math.sin(a) * radius;
            level.sendParticles(particle, x, queen.getY() + 0.1D, z, 1, 0.0D, 0.15D, 0.0D, 0.06D);
        }
    }

    /** その瞬間に輪が通ったところに居る者を殴り飛ばす。 */
    private void hit(double radius) {
        double inner = Math.max(0.0D, radius - RING_SPEED);
        AABB box = queen.getBoundingBox().inflate(radius + 1.0D, 3.0D, radius + 1.0D);
        for (LivingEntity victim : queen.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (victim == queen || !queen.canAttack(victim)) {
                continue;
            }
            double dx = victim.getX() - queen.getX();
            double dz = victim.getZ() - queen.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < inner || d > radius || Math.abs(victim.getY() - queen.getY()) > 3.0D) {
                continue;
            }
            victim.hurt(queen.damageSources().mobAttack(queen), DAMAGE);
            Vec3 push = new Vec3(dx, 0.0D, dz).normalize().scale(0.9D).add(0.0D, 0.45D, 0.0D);
            victim.push(push.x, push.y, push.z);
            victim.hurtMarked = true;
        }
    }
}
