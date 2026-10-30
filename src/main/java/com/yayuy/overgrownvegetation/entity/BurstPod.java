package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.G1Table;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.defense.GunTurrets;
import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import com.yayuy.overgrownvegetation.g1.SporeBursts;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * <b>破裂莢</b>(寄生されたクリーパーの進化 2 段目、<b>G4</b>。G1 の作り直し 第 1 段、2026-10-22)。根の脚で歩く種の莢、残すのは顔の模様。
 * 近く({@code burstPodSearchRadius})の<b>動いている機関砲台・点いている忌避灯</b>を目指して歩き、着いたら破裂して
 * 今の胞子爆弾と同じように砲台・忌避灯を覆って止め、浮遊を放ち、萌芽体を残す。設備が無ければ標的に組み付いて破裂する。
 * 「記憶の設備・壁」(司令塔の記憶)は後 —— 今は周りを探すだけ。G4 の土台の上で動く。ラフモデル(生成器 G1BurstPod)。数値は仮決め。
 */
public class BurstPod extends AbstractOvergrownInvaderEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");

    @Nullable
    private BlockPos device;
    private boolean burst;

    public BurstPod(EntityType<? extends BurstPod> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 24.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 32.0), "burst_pod");
    }

    @Override
    protected void registerSpeciesGoals() {
        this.goalSelector.addGoal(2, new SeekDeviceGoal());
    }

    /** いちばん近い動いている砲台・点いている忌避灯(半径 burstPodSearchRadius)。 */
    @Nullable
    private BlockPos findDevice() {
        double r = DesignData.g1().burstPodSearchRadius;
        BlockPos turret = GunTurrets.nearestWorking(level(), position(), r);
        BlockPos lamp = RepellentLamps.nearestActive(level(), position(), r);
        if (turret == null) return lamp;
        if (lamp == null) return turret;
        return turret.distToCenterSqr(position()) <= lamp.distToCenterSqr(position()) ? turret : lamp;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (burst || !(level() instanceof ServerLevel server)) {
            return;
        }
        if ((tickCount + getId()) % 20 == 0) {
            device = findDevice();
        }
        if (device != null && device.distToCenterSqr(position()) < 2.5D * 2.5D) {
            burst(server);
            return;
        }
        LivingEntity target = getTarget();
        if (device == null && target != null && target.isAlive() && distanceTo(target) < 1.8D) {
            burst(server);
        }
    }

    private void burst(ServerLevel level) {
        if (burst) return;
        burst = true;
        G1Table g = DesignData.g1();
        Vec3 center = position();
        RepellentLamps.coverNear(level, center, DesignData.defense().lampCoverRadius());
        GunTurrets.corruptNear(level, center, DesignData.defense().turretCorruptRadius(), 1.0D, level.random);
        SporeBursts.burst(level, center, g.burstPodBurst, 0.0D, g.burstPodFerns);
        kill();
    }

    @Override
    public void die(DamageSource source) {
        burst = true;
        super.die(source);
    }

    /** 設備へ歩く(群れ・近接より先に)。 */
    private final class SeekDeviceGoal extends Goal {
        private int repath;

        SeekDeviceGoal() {
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return device != null && !burst;
        }

        @Override
        public void tick() {
            if (device != null && --repath <= 0) {
                repath = 20;
                getNavigation().moveTo(device.getX() + 0.5D, device.getY(), device.getZ() + 0.5D, 1.15D);
            }
        }
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.CREEPER_HURT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.CREEPER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.CREEPER_DEATH;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, state ->
                state.setAndContinue(MobAnimation.isWalking(state) ? WALK_ANIM : IDLE_ANIM)));
    }
}
