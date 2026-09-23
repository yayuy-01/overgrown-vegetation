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
 * 「ヒロノムス」— 知られている限り最古の爬虫類。全長 20cm ほどの極小の昆虫食(グループ3)。
 * ちょこまかと素早く動き、獲物へ小さく跳びついて噛みつく({@link PredatorPounceGoal})。
 * 体力は最も低い。
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code walk} / {@code run} / {@code pounce}。
 */
public class HylonomusEntity extends AbstractOvergrownPredatorEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN_ANIM = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation POUNCE_ANIM = RawAnimation.begin().thenLoop("pounce");

    public HylonomusEntity(EntityType<? extends HylonomusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("hylonomus", 2);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "hylonomus");
    }

    @Override
    protected void registerSpeciesGoals() {
        this.goalSelector.addGoal(2, new PredatorPounceGoal(this, 0.6D, 0.4D, 55));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 3, state -> {
            state.getController().setAnimationSpeed(1.0D);
            if (getActionState() == STATE_SPECIAL) {
                return state.setAndContinue(POUNCE_ANIM);
            }
            if (MobAnimation.isWalking(state)) {
                return state.setAndContinue(
                        MobAnimation.isFasterThan(state, 0.14D) ? RUN_ANIM : WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SILVERFISH_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SILVERFISH_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SILVERFISH_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.4F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SPIDER_STEP, 0.06F, 1.5F);
    }
}
