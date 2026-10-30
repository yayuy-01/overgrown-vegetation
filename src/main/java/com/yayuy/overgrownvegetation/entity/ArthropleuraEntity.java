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
 * 「アースロプレウラ」— 巨大なムカデに似たグループ2の Mob。
 *
 * <ul>
 *     <li>移動速度が遅い({@code MOVEMENT_SPEED} 0.17 — 過繁茂したスカトゥリウムの 0.2 より少し遅い)。</li>
 *     <li>防御力が非常に高い(硬い外骨格。{@code ARMOR} 16 / {@code ARMOR_TOUGHNESS} 8 /
 *     {@code KNOCKBACK_RESISTANCE} 0.8)。</li>
 *     <li>他の Mob を攻撃しない。スカトゥリウム・ファランギオターブスに捕食される({@link Predation})。</li>
 * </ul>
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code walk}。
 */
public class ArthropleuraEntity extends AbstractOvergrownCritterEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");

    public ArthropleuraEntity(EntityType<? extends ArthropleuraEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "arthropleura");
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 歩きの再生速度は実際に進んだ量に合わせる(逃げるときは散歩の約 2.8 倍で、そのままだと足が滑る。2026-10-10)。
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 4, state -> {
            // 今している行動(食べる・休む・驚いて逃げ出す など)があればそのアニメ(CritterAction、2026-10-22)
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

    /**
     * walk のアニメで足が滑らない速さ(体格 1 あたり、ブロック/tick)。生成器 ArthropleuraAnims は個体差の平均の体(1.2 倍)で
     * 散歩 0.0505 ブロック/tick に合わせてある(2026-10-10)→ 体格 1 では 0.0505 ÷ 1.2。
     */
    private static final double WALK_ANIM_REF = 0.0505D / 1.2D;

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
        return 0.7F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SPIDER_STEP, 0.3F, 0.6F);
    }
}
