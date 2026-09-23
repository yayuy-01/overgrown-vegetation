package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「ステノデクティア」— トンボに似たグループ2の飛行 Mob。
 *
 * <ul>
 *     <li>「止まってから直線的に素早く動き、また止まる」を繰り返すトンボ特有の移動
 *     ({@link DragonflyDartGoal})。</li>
 *     <li>蔦の実・熟した垂れ草の光る果実を食べる({@link FlyerEatFoliageGoal})。</li>
 *     <li>時々木の枝に止まる({@link PerchOnBranchGoal})。</li>
 *     <li>体力が低い。他の Mob を攻撃しない。メガネウラに捕食される({@link Predation})。</li>
 * </ul>
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code fly} / {@code hover} / {@code perch} / {@code eat}。
 */
public class StenodictyaLobataEntity extends OvergrownFlyerEntity {

    private static final RawAnimation FLY_ANIM = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation HOVER_ANIM = RawAnimation.begin().thenLoop("hover");
    private static final RawAnimation PERCH_ANIM = RawAnimation.begin().thenLoop("perch");
    private static final RawAnimation EAT_ANIM = RawAnimation.begin().thenLoop("eat");

    public StenodictyaLobataEntity(EntityType<? extends StenodictyaLobataEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("stenodictya_lobata", this.xpReward);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "stenodictya_lobata");
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new PanicGoal(this, 1.6D));
        this.goalSelector.addGoal(2, new CritterAvoidPredatorGoal(this));
        this.goalSelector.addGoal(3, new PerchOnBranchGoal(this, 1.0D));
        this.goalSelector.addGoal(4, new FlyerEatFoliageGoal(this, 1.0D));
        this.goalSelector.addGoal(5, new DragonflyDartGoal(this));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6.0F));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, state -> {
            state.getController().setAnimationSpeed(1.0D);
            switch (getActionState()) {
                case STATE_PERCH:
                    return state.setAndContinue(PERCH_ANIM);
                case STATE_EAT:
                    return state.setAndContinue(EAT_ANIM);
                case STATE_HOVER:
                    return state.setAndContinue(HOVER_ANIM);
                default:
                    return state.setAndContinue(FLY_ANIM);
            }
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.BEE_LOOP;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.BEE_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.BEE_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.3F;
    }
}
