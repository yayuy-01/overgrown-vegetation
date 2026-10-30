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
 * 「エリオプス」— 平たく幅広い大きな頭の大型両生類(グループ3、2026-10-22)。岸で待ち伏せ、水辺の G2 を襲う。
 * 陸の捕食者の共通の動き(追う・噛む)だけ。待ち伏せ(Goal)は後の段。
 *
 * <h2>アニメーション名(GeckoLib)</h2>
 * {@code idle} / {@code walk} / {@code eat} / {@code rest} / {@code attack}(口を開けて横から噛みつく)/ {@code bask}。
 */
public class EryopsEntity extends AbstractOvergrownPredatorEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");

    /** walk のアニメで足が滑らない速さ(体格 1、ブロック/tick。生成器 G23NAnims のエリオプス)。 */
    private static final double WALK_ANIM_REF = 0.07D;

    public EryopsEntity(EntityType<? extends EryopsEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("eryops", 6);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "eryops");
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 4, state -> {
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
    public float getVoicePitch() {
        return 0.55F;
    }

    @Override
    protected float getSoundVolume() {
        return 0.8F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.FROG_STEP, 0.3F, 0.6F);
    }
}
