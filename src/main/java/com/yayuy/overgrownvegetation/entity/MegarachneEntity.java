package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.TryFindWaterGoal;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「メガラクネ」— かつてクモの仲間と誤同定された大型のウミサソリ(広翼類、グループ3)。
 *
 * <ul>
 *     <li>水陸両生 —— 地上に上がっても窒息せず生きられるが、{@link TryFindWaterGoal} と
 *     {@link MegarachneReturnToWaterGoal} で<b>積極的に水へ戻ろうとする</b>(ユーザー要望)。
 *     移動は {@link AmphibiousPathNavigation}(水中・地上の両方を経路探索)。</li>
 *     <li>{@code predation.json} の捕食対象を水陸問わず狩る。</li>
 *     <li>捕食対象でないグループ2の Mob や侵入者でも、<b>水中・水面にいる</b>間は襲う
 *     ({@link Predation#isWaterborneTarget})。海岸・川辺の陸上の相手は襲わない。</li>
 * </ul>
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code swim} / {@code walk} / {@code attack}。
 */
public class MegarachneEntity extends AbstractOvergrownAquaticPredatorEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation SWIM_ANIM = RawAnimation.begin().thenLoop("swim");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation ATTACK_ANIM = RawAnimation.begin().thenLoop("attack");

    /**
     * 陸の walk のアニメで足が滑らない速さ(体格 1 あたり、ブロック/tick)。生成器 MegarachneAnims は個体差の平均の体(1.075 倍)で
     * 水へ戻るときの陸の速さ 0.26 ブロック/tick に合わせてある(2026-10-10)→ 体格 1 では 0.26 ÷ 1.075。
     */
    private static final double WALK_ANIM_REF = 0.26D / 1.075D;

    public MegarachneEntity(EntityType<? extends MegarachneEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("megarachne", 6);
        this.setPathfindingMalus(PathType.WATER, 0.0F);
        this.setPathfindingMalus(PathType.WATER_BORDER, 0.0F);
        this.moveControl = new net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl(
                this, 85, 12, 0.1F, 0.35F, true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "megarachne");
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new AmphibiousPathNavigation(this, level);
    }

    @Override
    protected void registerSpeciesGoals() {
        // 陸に上がったら積極的に水へ戻る。
        this.goalSelector.addGoal(1, new MegarachneReturnToWaterGoal(this, 1.1D));
        this.goalSelector.addGoal(2, new TryFindWaterGoal(this));
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    /** {@code attack} は 0.4 秒 = 8 tick(0.15 秒で鋏を振り切る)。補間のぶん後ろへずらす。 */
    @Override
    protected int attackAnimationTicks() {
        return TRANSITION_TICKS + 8;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 陸の歩きの再生速度は実際に進んだ量に合わせる(2026-10-10、モデル作り直し。追うときは水へ戻るときより 4 割速い)。
        // 攻撃・泳ぎは速さ 1 のまま(攻撃の当たりは attackAnimationTicks と結びついている)。
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", TRANSITION_TICKS, state -> {
            // 今している行動(食べる・休む・驚いて逃げ出す など)があればそのアニメ(CritterAction、2026-10-22)
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
            if (MobAnimation.isWalking(state)) {
                if (this.isInWater()) {
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
        return 0.7F;
    }
}
