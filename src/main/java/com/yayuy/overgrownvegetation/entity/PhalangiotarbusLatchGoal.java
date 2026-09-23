package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * ファランギオターブスが獲物に取り付いている間の処理。
 *
 * <p>{@link PhalangiotarbusAmbushGoal} の飛びかかりが命中して
 * {@link PhalangiotarbusEntity#setLatchTarget} が呼ばれると起動する。取り付いている間:
 * <ul>
 *     <li>毎tick、自分の位置を対象の背中(当たり判定の上側)へ固定し、自力移動を殺す。</li>
 *     <li>{@link #DAMAGE_INTERVAL} tick ごとに対象へ攻撃力ぶんの継続ダメージを与える。</li>
 *     <li>対象が死ぬ・自分が死ぬまで離れない(ユーザー要望)。対象が死んだら地面へ降りて
 *         通常状態へ戻る。</li>
 * </ul>
 */
public class PhalangiotarbusLatchGoal extends Goal {

    /** 継続ダメージの間隔(tick)。 */
    private static final int DAMAGE_INTERVAL = 20;

    private final PhalangiotarbusEntity mob;
    private int damageCooldown;

    public PhalangiotarbusLatchGoal(PhalangiotarbusEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getLatchTarget();
        return t != null && t.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity t = mob.getLatchTarget();
        return mob.isAlive() && t != null && t.isAlive();
    }

    @Override
    public void start() {
        damageCooldown = DAMAGE_INTERVAL;
        mob.setActionState(PhalangiotarbusEntity.STATE_LATCH);
        mob.getNavigation().stop();
        mob.noPhysics = true;
    }

    @Override
    public void stop() {
        mob.noPhysics = false;
        mob.setLatchTarget(null);
        mob.setActionState(PhalangiotarbusEntity.STATE_NONE);
        mob.setTarget(null);
        // 獲物を仕留めたら(擬態環境なら)また原木へ登って隠れ直す。
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getLatchTarget();
        if (target == null) {
            return;
        }

        // 対象の背中へ位置を固定。
        Vec3 back = target.position().add(0.0D, target.getBbHeight() * 0.45D, 0.0D);
        mob.setPos(back.x, back.y, back.z);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.fallDistance = 0.0F;
        mob.setYRot(target.getYRot());
        mob.yBodyRot = target.getYRot();
        mob.yHeadRot = target.getYRot();
        mob.getLookControl().setLookAt(target, 40.0F, 40.0F);
        mob.hurtMarked = true;

        if (--damageCooldown <= 0) {
            damageCooldown = DAMAGE_INTERVAL;
            float dmg = (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE);
            if (target.invulnerableTime > 0) {
                target.invulnerableTime = 0;
            }
            target.hurt(mob.damageSources().mobAttack(mob), dmg);
            target.hurtMarked = true;
            mob.playSound(ModSounds.PHALANGIOTARBUS_LATCH.get(), 0.35F, 1.15F);
            if (mob.level() instanceof ServerLevel server) {
                server.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIMSON_SPORE,
                        target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                        6, 0.3D, 0.3D, 0.3D, 0.0D);
            }
        }
    }
}
