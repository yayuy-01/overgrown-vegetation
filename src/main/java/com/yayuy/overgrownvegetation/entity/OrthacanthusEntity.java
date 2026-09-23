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
 * 「オルタカンタス」— 大型の古代サメ(グループ3)。過繁茂した水域の頂点捕食者。
 *
 * <ul>
 *     <li>{@code predation.json} の捕食対象を水陸問わず狩る({@link Predation#isPredatorOf})。
 *     既定ではメガラクネも捕食対象(ユーザー要望)。</li>
 *     <li>捕食対象でないグループ2の Mob や侵入者でも、<b>水中・水面にいる</b>間は襲う
 *     ({@link Predation#isWaterborneTarget})。海岸・川辺の陸上の相手は襲わない。</li>
 * </ul>
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code swim} / {@code attack}。
 */
public class OrthacanthusEntity extends AbstractOvergrownAquaticPredatorEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation SWIM_ANIM = RawAnimation.begin().thenLoop("swim");
    private static final RawAnimation ATTACK_ANIM = RawAnimation.begin().thenLoop("attack");

    public OrthacanthusEntity(EntityType<? extends OrthacanthusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("orthacanthus", 8);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "orthacanthus");
    }

    /** {@code attack} は 0.5 秒 = 10 tick(0.15 秒で頭を振り下ろしきる)。補間のぶん後ろへずらす。 */
    @Override
    protected int attackAnimationTicks() {
        return TRANSITION_TICKS + 10;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, state -> {
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
        return SoundEvents.DOLPHIN_AMBIENT_WATER;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.GUARDIAN_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.GUARDIAN_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.8F;
    }
}
