package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「ヒベルトプテルス」— 皿のような頭胸甲の大型ウミサソリ(グループ2、2026-10-22)。湖底を歩き、前の 3 対の熊手で底を掃いて小さな物を濾し取る。
 * 他の Mob を攻撃しない。メガラクネ(G3)と同じく水底を歩いて陸にも上がれる(歩く足は陸でも水底でも同じ)。
 *
 * <h2>アニメーション名(GeckoLib)</h2>
 * {@code idle} / {@code walk}(水底・陸)/ {@code swim}(底を離れて漕ぐ)/ {@code eat}(熊手で掃く)/ {@code rest} / {@code flee}。
 */
public class HibbertopterusEntity extends AbstractOvergrownAquaticCritterEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation SWIM_ANIM = RawAnimation.begin().thenLoop("swim");

    /** walk のアニメで足が滑らない速さ(体格 1、ブロック/tick。生成器 G23NAnims のヒベルトプテルス)。 */
    private static final double WALK_ANIM_REF = 0.06D;

    public HibbertopterusEntity(EntityType<? extends HibbertopterusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("hibbertopterus", 4);
        this.setPathfindingMalus(PathType.WATER_BORDER, 0.0F);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "hibbertopterus");
    }

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
            if (MobAnimation.isWalking(state)) {
                if (this.isInWater() && !this.onGround()) {
                    return state.setAndContinue(SWIM_ANIM);
                }
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
        this.playSound(SoundEvents.SPIDER_STEP, 0.1F, 0.7F);
    }
}
