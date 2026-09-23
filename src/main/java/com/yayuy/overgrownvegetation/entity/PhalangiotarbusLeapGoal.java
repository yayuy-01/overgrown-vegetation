package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 通常(狩り)モードの「大きく前方へ跳んで追跡中の相手に飛びつく」ゴール。
 *
 * <p>地上で対象を追っている最中、対象と {@link #LEAP_MIN}〜{@link #LEAP_MAX} ブロック離れていて
 * 視線が通っているとき、{@link #COOLDOWN} tick ごとに 1 回、対象へ向けて大きく跳躍する
 * ({@code pounce} アニメ、{@link PhalangiotarbusEntity#STATE_POUNCE})。空中で軽く追尾し、
 * 接触したら取り付いて({@link PhalangiotarbusEntity#STATE_LATCH})継続ダメージへ移行する
 * ({@link PhalangiotarbusLatchGoal})。外したら着地して通常追跡へ戻り、クールダウンを置く。
 *
 * <p>擬態(待ち伏せ)の飛びかかりは {@link PhalangiotarbusAmbushGoal} が担当する別物。
 * こちらは擬態していない/擬態が破られたあとの開けた戦闘用。
 */
public class PhalangiotarbusLeapGoal extends Goal {

    private static final double LEAP_MIN = 3.0D;
    private static final double LEAP_MAX = 9.0D;
    /** 跳躍の水平初速。擬態の飛びかかり(0.62)より大きい。 */
    private static final double LEAP_SPEED = 0.85D;
    /** 跳躍の垂直初速。 */
    private static final double LEAP_LIFT = 0.52D;
    /** 対象との高低差がこれを超えると跳ばない。 */
    private static final double MAX_VERTICAL = 4.0D;
    private static final int LEAP_MAX_TICKS = 28;
    private static final int COOLDOWN = 90;
    private static final double CONTACT_INFLATE = 0.4D;

    private final PhalangiotarbusEntity mob;

    private int ticks;
    private int nextLeapTime;
    private LivingEntity victim;

    public PhalangiotarbusLeapGoal(PhalangiotarbusEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.isBusy() || mob.isClimbing() || mob.isOnCeiling() || mob.isWallLocked()) {
            return false;
        }
        if (mob.tickCount < nextLeapTime || !mob.onGround() || mob.isInWater()) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive() || !mob.isHuntable(target)) {
            return false;
        }
        if (Math.abs(target.getY() - mob.getY()) > MAX_VERTICAL) {
            return false;
        }
        double dist = mob.distanceTo(target);
        return dist >= LEAP_MIN && dist <= LEAP_MAX && mob.hasLineOfSight(target);
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isAlive() && mob.getActionState() == PhalangiotarbusEntity.STATE_POUNCE;
    }

    @Override
    public void start() {
        this.victim = mob.getTarget();
        this.ticks = 0;
        mob.getNavigation().stop();
        mob.setActionState(PhalangiotarbusEntity.STATE_POUNCE);
        // 跳ぶのは踏み切りの姿勢が画面に出る tick(PhalangiotarbusEntity.POUNCE_LAUNCH_TICK)。それまでは tick() で構える。
    }

    /** 相手へ向けて跳び出す。構えている間に相手が動くので、向きはここで測り直す。 */
    private void launch() {
        double dx = victim.getX() - mob.getX();
        double dz = victim.getZ() - mob.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        Vec3 dir = len > 1.0E-4D ? new Vec3(dx / len, 0.0D, dz / len) : mob.getLookAngle();
        mob.setDeltaMovement(dir.x * LEAP_SPEED, LEAP_LIFT, dir.z * LEAP_SPEED);
        mob.hasImpulse = true;

        float yaw = yawTo(dx, dz);
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.playSound(ModSounds.PHALANGIOTARBUS_POUNCE.get(), 0.5F, 0.95F);
        if (mob.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.CRIMSON_SPORE,
                    mob.getX(), mob.getY() + 0.3D, mob.getZ(), 10, 0.3D, 0.2D, 0.3D, 0.02D);
        }
    }

    @Override
    public void stop() {
        if (mob.getActionState() == PhalangiotarbusEntity.STATE_POUNCE) {
            mob.setActionState(PhalangiotarbusEntity.STATE_NONE);
        }
        this.victim = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        int t = ticks++;     // 状態を切り替えた tick が 0
        if (t <= PhalangiotarbusEntity.POUNCE_LAUNCH_TICK && (victim == null || !victim.isAlive())) {
            // 跳ぶ前に相手がいなくなった。
            mob.setActionState(PhalangiotarbusEntity.STATE_NONE);
            nextLeapTime = mob.tickCount + COOLDOWN;
            return;
        }
        if (t < PhalangiotarbusEntity.POUNCE_LAUNCH_TICK) {
            // 踏み切りの構え。足を止めて相手を向く。
            Vec3 v = mob.getDeltaMovement();
            mob.setDeltaMovement(0.0D, v.y, 0.0D);
            mob.getLookControl().setLookAt(victim, 30.0F, 30.0F);
            return;
        }
        if (t == PhalangiotarbusEntity.POUNCE_LAUNCH_TICK) {
            launch();
        }
        int flight = t - PhalangiotarbusEntity.POUNCE_LAUNCH_TICK;     // 跳んだ tick が 0

        if (victim != null && victim.isAlive()) {
            double dx = victim.getX() - mob.getX();
            double dz = victim.getZ() - mob.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0E-4D) {
                Vec3 v = mob.getDeltaMovement();
                mob.setDeltaMovement(v.x + (dx / len) * 0.045D, v.y, v.z + (dz / len) * 0.045D);
            }
            mob.getLookControl().setLookAt(victim, 30.0F, 30.0F);

            if (mob.getBoundingBox().inflate(CONTACT_INFLATE).intersects(victim.getBoundingBox())
                    && mob.isHuntable(victim)) {
                boolean hit = mob.doHurtTarget(victim);
                if (!hit) {
                    victim.hurt(mob.damageSources().mobAttack(mob),
                            (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE));
                }
                mob.setLatchTarget(victim);
                mob.setTarget(victim);
                mob.setActionState(PhalangiotarbusEntity.STATE_LATCH);
                mob.playSound(ModSounds.PHALANGIOTARBUS_LATCH.get(), 0.5F, 0.9F);
                nextLeapTime = mob.tickCount + COOLDOWN;
                return;
            }
        }

        boolean landed = mob.onGround() && flight > 2;
        if (landed || flight >= LEAP_MAX_TICKS || victim == null || !victim.isAlive()) {
            mob.setActionState(PhalangiotarbusEntity.STATE_NONE);
            nextLeapTime = mob.tickCount + COOLDOWN;
        }
    }

    private static float yawTo(double dx, double dz) {
        return (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
    }
}
