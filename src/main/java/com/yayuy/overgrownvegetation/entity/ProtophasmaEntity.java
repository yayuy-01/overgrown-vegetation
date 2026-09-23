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
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「プロトファスマ」— 鈴虫に似たグループ2の Mob。時々立ち止まって鳴く({@link ProtophasmaSingGoal})
 * ほかは特段の挙動を持たない、変哲のない虫。他の Mob を攻撃せず、スカトゥリウム・ファランギオターブスに
 * 捕食される({@link Predation})。
 *
 * <p>グループ3の捕食者に追われている時は、まず近くの茂みに潜り込んでうずくまり、やり過ごそうとする
 * ({@link ProtophasmaHideInBushGoal})。茂みが無ければ普通に距離を取って逃げる
 * ({@link CritterAvoidPredatorGoal})。隠れても実際に捕食者のターゲットが切れるわけではない。
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code walk} / {@code sing} / {@code hide}。
 */
public class ProtophasmaEntity extends AbstractOvergrownCritterEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation SING_ANIM = RawAnimation.begin().thenLoop("sing");
    private static final RawAnimation HIDE_ANIM = RawAnimation.begin().thenLoop("hide");

    /**
     * 動作を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>(4.7.6 の {@code AnimationController#process})。
     * だから画面の上では、鳴き・隠れのアニメは状態を切り替えた tick より 3 tick 遅れて始まる。
     * アニメに合わせる tick(鳴く瞬間)は「これ + アニメの秒 × 20」で数える。
     */
    static final int TRANSITION_TICKS = 3;

    public ProtophasmaEntity(EntityType<? extends ProtophasmaEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "protophasma");
    }

    @Override
    protected void registerSpeciesGoals() {
        // PanicGoal(優先度1)と同格 — 到達できる茂みがあればただ逃げる(優先度2)より隠れる方を選ぶ。
        this.goalSelector.addGoal(1, new ProtophasmaHideInBushGoal(this));
        this.goalSelector.addGoal(4, new ProtophasmaSingGoal(this));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, state -> {
            state.getController().setAnimationSpeed(1.0D);
            if (getActionState() == STATE_HIDE) {
                return state.setAndContinue(HIDE_ANIM);
            }
            if (getActionState() == STATE_SPECIAL) {
                return state.setAndContinue(SING_ANIM);
            }
            if (MobAnimation.isWalking(state)) {
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
        return SoundEvents.SILVERFISH_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.35F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SPIDER_STEP, 0.08F, 1.4F);
    }
}
