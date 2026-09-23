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
 * 「アルボラの女王」({@code arbora_queen}、2026-09-20)。巣の女王室の主。
 *
 * <h2>決まり(ユーザー指定 2026-09-19)</h2>
 * <ul>
 *     <li>アルボラの<b>2 倍の大きさ</b>で、後部が膨らんだ姿。</li>
 *     <li><b>移動が遅く、とびかかりを持たない</b>。逃げれば振り切れるが、部屋が狭いので押し込まれる。</li>
 *     <li>戦闘中に<b>アルボラを 3 体産む</b>({@link QueenBroodGoal})。</li>
 *     <li><b>地面を叩く衝撃波</b>({@link QueenShockwaveGoal}) —— 周りのブロックが波打って見えるが、
 *     <b>地形は動かさない</b>。演出はブロックの粒子だけ。</li>
 *     <li>溜めてから振る<b>広範囲の鎌</b>({@link QueenScytheGoal})。</li>
 * </ul>
 *
 * <p>巣の外へは出ない —— うずくまって胞子をばらまく動き({@code useSporeCrouchGoal})も、
 * 遠ざかったら消える動き({@code removeWhenFarAway})も切ってある。
 *
 * <h2>モデルについて</h2>
 * <p><b>現状は仮の見た目</b>。アルボラのモデルとアニメーションをそのまま 2 倍にしたもので、
 * 「後部が膨らむ」形はまだ入っていない。{@code geo/arbora_queen.geo.json} /
 * {@code animations/arbora_queen.animation.json} / {@code textures/entity/arbora_queen.png} を
 * 差し替えればこのクラスを触らずに作り直せる。アニメーション名は
 * {@code idle} / {@code walk} / {@code attack} / {@code shockwave} / {@code scythe}。
 */
public class ArboraQueenEntity extends AbstractOvergrownInvaderEntity {

    /** 地面を叩いている最中。 */
    public static final int STATE_SHOCKWAVE = 5;
    /** 鎌を溜めている / 振っている最中。 */
    public static final int STATE_SCYTHE = 6;

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation ATTACK_ANIM = RawAnimation.begin().thenLoop("attack");
    private static final RawAnimation SHOCKWAVE_ANIM = RawAnimation.begin().thenLoop("shockwave");
    private static final RawAnimation SCYTHE_ANIM = RawAnimation.begin().thenLoop("scythe");

    public ArboraQueenEntity(EntityType<? extends ArboraQueenEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("arbora_queen", 60);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "arbora_queen");
    }

    @Override
    protected void registerSpeciesGoals() {
        this.goalSelector.addGoal(1, new QueenScytheGoal(this));
        this.goalSelector.addGoal(2, new QueenShockwaveGoal(this));
        this.goalSelector.addGoal(3, new QueenBroodGoal(this));
    }

    /** 巣から出ない。外でうずくまって汚染を広げる役ではない。 */
    @Override
    protected boolean useSporeCrouchGoal() {
        return false;
    }

    /** 巣の主なので、プレイヤーが離れても消えない。 */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    /** 壁は登らない(重い)。 */
    @Override
    protected boolean canClimbWalls() {
        return false;
    }

    /**
     * 動作を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>(4.7.6 の {@code AnimationController#process})。
     * アニメの時刻に合わせる tick(鎌を振る・床を叩く・動作の長さ)は「これ + アニメの秒 × 20」で数える。
     */
    static final int TRANSITION_TICKS = 5;

    /**
     * {@code attack} は 0.6 秒 = 12 tick。補間のぶん後ろへずらす。
     * (2026-09-23 まではコメントが「1.0 秒 = 20 tick」になっていて、振り終わったあと次の振りかぶりが少し見えていた。)
     */
    @Override
    protected int attackAnimationTicks() {
        return TRANSITION_TICKS + 12;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, state -> {
            switch (getActionState()) {
                case STATE_SHOCKWAVE -> {
                    return state.setAndContinue(SHOCKWAVE_ANIM);
                }
                case STATE_SCYTHE -> {
                    return state.setAndContinue(SCYTHE_ANIM);
                }
                case STATE_ATTACK -> {
                    return state.setAndContinue(ATTACK_ANIM);
                }
                default -> {
                }
            }
            if (MobAnimation.isWalking(state)) {
                return state.setAndContinue(WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.RAVAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.RAVAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.RAVAGER_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 1.0F;
    }
}
