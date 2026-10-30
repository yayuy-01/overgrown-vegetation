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
        // 歩き・走りの再生速度は実際に進んだ量に合わせる(2026-10-10、モデル作り直し)。短い脚の走りは 0.108 ブロック/tick までしか
        // 足が滑らずに回せず、散歩 0.16・追跡 0.19 ではそのままだと足が前へ滑る。跳びかかりは速さ 1 のまま(着地に合わせてある)。
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 3, state -> {
            // 今している行動(食べる・休む・驚いて逃げ出す など)があればそのアニメ(CritterAction、2026-10-22)
            RawAnimation critterAct = CritterAction.animation(this);
            if (critterAct != null) {
                state.getController().setAnimationSpeed(1.0D);
                return state.setAndContinue(critterAct);
            }
            state.getController().setAnimationSpeed(1.0D);
            if (getActionState() == STATE_SPECIAL) {
                return state.setAndContinue(POUNCE_ANIM);
            }
            if (MobAnimation.isWalking(state)) {
                boolean run = MobAnimation.isFasterThan(state, 0.14D);
                state.getController().setAnimationSpeed(MobAnimation.walkSpeedFor(this, run ? RUN_ANIM_REF : WALK_ANIM_REF));
                return state.setAndContinue(run ? RUN_ANIM : WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
    }

    /** walk・run のアニメで足が滑らない速さ(体格 1、ブロック/tick。生成器 HylonomusAnims、2026-10-10)。 */
    private static final double WALK_ANIM_REF = 0.061D;
    private static final double RUN_ANIM_REF = 0.108D;

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
