package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「肺魚」— 川底の泥に潜み、雨の日には陸へ這い出す肉鰭類(グループ2、2026-10-22)。他の Mob を攻撃しない。
 * 陸でも息ができる(陸に上がっても弱らない)。雨の日に陸へ這い出す行動(Goal)は後の段 —— 今は陸に居れば這うアニメを流すだけ
 * (ユーザー指定 10-22「雨の日は陸へ這い出す(陸の這いのアニメも作る)」)。
 *
 * <h2>アニメーション名(GeckoLib)</h2>
 * {@code idle} / {@code swim} / {@code crawl}(陸を這う)/ {@code land_idle}(陸で止まっている)/ {@code eat} / {@code rest} / {@code flee}。
 */
public class LungfishEntity extends AbstractOvergrownAquaticCritterEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation SWIM_ANIM = RawAnimation.begin().thenLoop("swim");
    private static final RawAnimation CRAWL_ANIM = RawAnimation.begin().thenLoop("crawl");
    private static final RawAnimation LAND_IDLE_ANIM = RawAnimation.begin().thenLoop("land_idle");

    /** crawl のアニメで体が滑らない速さ(体格 1、ブロック/tick。生成器 G23NAnims の肺魚)。 */
    private static final double CRAWL_ANIM_REF = 0.04D;

    public LungfishEntity(EntityType<? extends LungfishEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("lungfish", 2);
        this.setPathfindingMalus(PathType.WATER_BORDER, 0.0F);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "lungfish");
    }

    /** 陸へも道を引ける(陸に上がった個体が水へ戻れるように)。 */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new AmphibiousPathNavigation(this, level);
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
            boolean moving = MobAnimation.isWalking(state);
            if (!this.isInWater()) {
                if (moving) {
                    state.getController().setAnimationSpeed(MobAnimation.walkSpeedFor(this, CRAWL_ANIM_REF));
                    return state.setAndContinue(CRAWL_ANIM);
                }
                return state.setAndContinue(LAND_IDLE_ANIM);
            }
            return state.setAndContinue(moving ? SWIM_ANIM : IDLE_ANIM);
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SALMON_AMBIENT;
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
        return 0.4F;
    }
}
