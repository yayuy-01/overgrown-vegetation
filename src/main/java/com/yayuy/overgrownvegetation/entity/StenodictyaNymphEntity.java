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
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「梢の巨大な幼虫」— ステノディクティアの幼虫(グループ2、2026-10-22)。翅の芽を背負った成虫の小さな姿(不完全変態。
 * ユーザー了承 10-22)。梢の葉に食べ跡を残す。育つとステノディクティアに羽化する(羽化と食べ跡は後の段)。
 *
 * <h2>アニメーション名(GeckoLib)</h2>
 * {@code idle} / {@code walk} / {@code eat} / {@code rest} / {@code flee}。
 */
public class StenodictyaNymphEntity extends AbstractOvergrownCritterEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");

    /** walk のアニメで足が滑らない速さ(体格 1、ブロック/tick。生成器 G23NAnims の幼虫)。 */
    private static final double WALK_ANIM_REF = 0.07D;

    public StenodictyaNymphEntity(EntityType<? extends StenodictyaNymphEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "stenodictya_nymph");
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 3, state -> {
            RawAnimation critterAct = CritterAction.animation(this);
            if (critterAct != null) {
                state.getController().setAnimationSpeed(1.0D);
                return state.setAndContinue(critterAct);
            }
            state.getController().setAnimationSpeed(1.0D);
            if (MobAnimation.isWalking(state)) {
                state.getController().setAnimationSpeed(MobAnimation.walkSpeedFor(this, WALK_ANIM_REF));
                return state.setAndContinue(WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return null;
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
        return 0.35F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SILVERFISH_STEP, 0.08F, 1.2F);
    }
}
