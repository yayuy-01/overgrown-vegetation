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
 * 「ディプロカウルス」— ブーメラン形の頭を持つ平たい両生類(グループ2、2026-10-22)。浅瀬を群れで泳ぎ、水底の小さな物を食べる。
 * 他の Mob を攻撃しない。行動(Goal)は後の段 —— 今は水生の G2 共通の泳ぎ・逃げだけ。
 *
 * <h2>アニメーション名(GeckoLib)</h2>
 * {@code idle} / {@code swim} / {@code walk}(水底や陸を這う)/ {@code eat} / {@code rest} / {@code flee}。
 */
public class DiplocaulusEntity extends AbstractOvergrownAquaticCritterEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation SWIM_ANIM = RawAnimation.begin().thenLoop("swim");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");

    public DiplocaulusEntity(EntityType<? extends DiplocaulusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("diplocaulus", 2);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "diplocaulus");
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, state -> {
            // 今している行動(食べる・休む・驚いて逃げ出す など)があればそのアニメ(CritterAction)
            RawAnimation critterAct = CritterAction.animation(this);
            if (critterAct != null) {
                state.getController().setAnimationSpeed(1.0D);
                return state.setAndContinue(critterAct);
            }
            state.getController().setAnimationSpeed(1.0D);
            if (MobAnimation.isWalking(state)) {
                return state.setAndContinue(this.isInWater() && !this.onGround() ? SWIM_ANIM : WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.FROG_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.FROG_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.FROG_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.4F;
    }
}
