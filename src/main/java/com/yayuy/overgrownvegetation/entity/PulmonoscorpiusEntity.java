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
 * 「プルモノスコルピウス」— 長さ 1 ブロックを超える巨大サソリ(グループ3、2026-10-22)。夜に倒木の下から出て小さな G2 を狩る。
 * 陸の捕食者の共通の動き(追う・刺す)だけ。夜だけ出る・倒木の下に潜む行動(Goal)は後の段。
 *
 * <h2>アニメーション名(GeckoLib)</h2>
 * {@code idle} / {@code walk} / {@code eat} / {@code rest} / {@code attack}(鋏で押さえて尾で刺す)/ {@code threat}(鋏を広げ尾を立てる)。
 */
public class PulmonoscorpiusEntity extends AbstractOvergrownPredatorEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");

    /** walk のアニメで足が滑らない速さ(体格 1、ブロック/tick。生成器 G23NAnims のサソリ)。 */
    private static final double WALK_ANIM_REF = 0.1D;

    public PulmonoscorpiusEntity(EntityType<? extends PulmonoscorpiusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("pulmonoscorpius", 5);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "pulmonoscorpius");
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
        return 0.5F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SPIDER_STEP, 0.1F, 1.2F);
    }
}
