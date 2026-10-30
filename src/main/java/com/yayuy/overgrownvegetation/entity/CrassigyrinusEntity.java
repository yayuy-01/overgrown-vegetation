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
 * 「クラッシギリヌス」— 大きく深い頭のウナギ型の四肢動物(グループ3、2026-10-22)。濁った水で待ち伏せ、大口で吸い込むように噛みつく。
 * 水生の捕食者の共通の動き(追う・噛む)だけ。待ち伏せ(Goal)は後の段。
 *
 * <h2>アニメーション名(GeckoLib)</h2>
 * {@code idle} / {@code swim} / {@code attack}(大口の突進)/ {@code eat} / {@code rest} / {@code burst}。
 */
public class CrassigyrinusEntity extends AbstractOvergrownAquaticPredatorEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation SWIM_ANIM = RawAnimation.begin().thenLoop("swim");
    private static final RawAnimation ATTACK_ANIM = RawAnimation.begin().thenLoop("attack");

    public CrassigyrinusEntity(EntityType<? extends CrassigyrinusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("crassigyrinus", 7);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "crassigyrinus");
    }

    /** {@code attack} は 0.5 秒 = 10 tick。補間のぶん後ろへずらす。 */
    @Override
    protected int attackAnimationTicks() {
        return TRANSITION_TICKS + 10;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, state -> {
            RawAnimation critterAct = CritterAction.animation(this);
            if (critterAct != null) {
                state.getController().setAnimationSpeed(1.0D);
                return state.setAndContinue(critterAct);
            }
            state.getController().setAnimationSpeed(1.0D);
            // swinging と getTarget() では選ばない(前者は降りない、後者はクライアントでは常に null)。
            if (getActionState() == STATE_ATTACK) {
                return state.setAndContinue(ATTACK_ANIM);
            }
            return state.setAndContinue(MobAnimation.isWalking(state) ? SWIM_ANIM : IDLE_ANIM);
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.COD_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SALMON_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SALMON_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.6F;
    }
}
