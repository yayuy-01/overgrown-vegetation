package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「リゾドゥス」— 全長数メートルの巨大な肉鰭類(グループ2)。水中を悠然と泳ぐだけで他の Mob を
 * 攻撃しない。動きは遅いが体力が非常に高い。捕食者に狙われると距離を取ろうとする。
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code swim}。
 */
public class RhizodusEntity extends AbstractOvergrownAquaticCritterEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation SWIM_ANIM = RawAnimation.begin().thenLoop("swim");

    public RhizodusEntity(EntityType<? extends RhizodusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("rhizodus", 5);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "rhizodus");
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 5, state -> {
            state.getController().setAnimationSpeed(1.0D);
            return state.setAndContinue(MobAnimation.isWalking(state) ? SWIM_ANIM : IDLE_ANIM);
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.COD_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.COD_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.COD_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.8F;
    }
}
