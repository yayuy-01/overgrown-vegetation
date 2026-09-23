package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「イアンタサウルス」— 背に小さな帆を持つエダフォサウルス類の単弓類(グループ3)。
 * 昆虫食の小型捕食者で、動きは遅め・体力はやや高め。獲物がいないときは日光を浴びて
 * 体温を上げる({@link SunbatheGoal}、その間ゆっくり回復)。
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code walk} / {@code bask}。
 */
public class IanthasaurusEntity extends AbstractOvergrownPredatorEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation BASK_ANIM = RawAnimation.begin().thenLoop("bask");

    public IanthasaurusEntity(EntityType<? extends IanthasaurusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("ianthasaurus", 4);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "ianthasaurus");
    }

    @Override
    protected void registerSpeciesGoals() {
        this.goalSelector.addGoal(3, new SunbatheGoal(this));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 3, state -> {
            state.getController().setAnimationSpeed(1.0D);
            if (getActionState() == STATE_SPECIAL) {
                return state.setAndContinue(BASK_ANIM);
            }
            if (MobAnimation.isWalking(state)) {
                return state.setAndContinue(WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SPIDER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SPIDER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SPIDER_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.7F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SPIDER_STEP, 0.15F, 0.8F);
    }
}
