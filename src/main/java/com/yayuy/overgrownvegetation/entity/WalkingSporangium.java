package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.G1Table;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.g1.SporeBursts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * <b>歩く胞子嚢</b>(寄生された牛の進化 2 段目、<b>G4</b>。G1 の作り直し 第 1 段、2026-10-22)。根の脚に巨大な胞子嚢を載せた姿、残すのは角。
 * 標的へ<b>突進</b>し({@code sporangiumChargeSpeed})、<b>傷が深い(体力 {@code sporangiumBurstBelowHealth} 以下)か死ぬと破裂</b>して、
 * その升の浮遊と降り積もりを増やし、萌芽体を数体落とす。G4 の土台({@link AbstractOvergrownInvaderEntity})の上で動く
 * (群れ・うずくまり・死んだときの汚染の球はそのまま)。モデルは生成器 tools/g5-models/mobs/G1Stage2Final(10-23、頭を捨てた首の嚢の房 + 後ろは根の脚、名残は右の角)。数値は仮決め。
 */
public class WalkingSporangium extends AbstractOvergrownInvaderEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    // 2026-10-23 の仕上げ(生成器 G1Stage2Final): 突進は "attack" のコントローラから一度、破裂は死んだ姿の 20 tick に流して最後の形で止める
    private static final RawAnimation CHARGE_ANIM = RawAnimation.begin().thenPlay("charge");
    private static final RawAnimation BURST_ANIM = RawAnimation.begin().thenPlayAndHold("burst");

    private int chargeCooldown = 40;
    private int charging;
    private boolean burst;

    public WalkingSporangium(EntityType<? extends WalkingSporangium> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.MOVEMENT_SPEED, 0.23)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.6)
                .add(Attributes.FOLLOW_RANGE, 32.0), "walking_sporangium");
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        G1Table g = DesignData.g1();
        LivingEntity target = getTarget();
        if (charging > 0) {
            charging--;
            if (target != null && distanceTo(target) < 2.0D) {
                doHurtTarget(target);
                charging = 0;
            }
            return;
        }
        if (chargeCooldown > 0) {
            chargeCooldown--;
            return;
        }
        if (target != null && target.isAlive() && onGround() && hasLineOfSight(target)) {
            double dist = distanceTo(target);
            if (dist > 4.0D && dist < 12.0D) {
                Vec3 dir = target.position().subtract(position()).multiply(1, 0, 1).normalize();
                setDeltaMovement(dir.x * g.sporangiumChargeSpeed * 0.5D, 0.2D, dir.z * g.sporangiumChargeSpeed * 0.5D);
                hasImpulse = true;
                charging = 20;
                chargeCooldown = g.sporangiumChargeCooldownTicks;
                playSound(SoundEvents.RAVAGER_ROAR, 0.8F, 1.4F);
                triggerAnim("attack", "charge");
            }
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && !burst && isAlive() && getHealth() <= getMaxHealth() * DesignData.g1().sporangiumBurstBelowHealth
                && level() instanceof ServerLevel server) {
            burst(server);
            kill();
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        if (!burst && level() instanceof ServerLevel server) {
            burst(server);
        }
        super.die(source);
    }

    private void burst(ServerLevel level) {
        burst = true;
        G1Table g = DesignData.g1();
        SporeBursts.burst(level, position(), g.sporangiumBurstAir, g.sporangiumBurstDeposit, g.sporangiumBurstFerns);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.COW_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SLIME_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.COW_DEATH;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, state -> {
            if (isDeadOrDying()) {
                return state.setAndContinue(BURST_ANIM);
            }
            return state.setAndContinue(MobAnimation.isWalking(state) ? WALK_ANIM : IDLE_ANIM);
        }));
        controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP).triggerableAnim("charge", CHARGE_ANIM));
    }
}
