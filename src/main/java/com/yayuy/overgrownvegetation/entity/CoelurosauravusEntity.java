package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「コエルロサウラヴス」— <b>滑空する爬虫類</b>(グループ3・原生消費種)。
 *
 * <h2>肋骨ではない骨で翼を張る</h2>
 * <p>ペルム紀の実在種。翼膜を支えていたのは肋骨ではなく<b>体壁に新しく生えた骨の棒</b>で、
 * これをやった脊椎動物は後にも先にもこの系統だけ。モデルの翼膜に放射状の筋が入っているのはそれ。
 *
 * <h2>飛ばない。滑る。</h2>
 * <p>メガネウラのような自由飛行はしない。<b>木を登り、飛び出し、降りるだけ</b>。
 * 滑空比は {@link #GLIDE_RATIO}(高さ 1 に対して 3 進む、ユーザー指定 2026-09-21)。
 * 滑空しながら下を探し、獲物を見つけたら<b>体を下に向けて急降下</b>する({@link #DIVE_RATIO})。
 *
 * <p>滑空は<b>片道</b>。降りたらまた木を探して登り直すしかないので、空振りすると
 * 地面を歩いて木へ戻る姿が見られる —— この種の「観察して面白い」はそこにある。
 *
 * <h2>プレイヤーは襲わない</h2>
 * <p>{@link AbstractOvergrownPredatorEntity} の既定では「縄張りに入った侵入者」として
 * プレイヤーにも先制攻撃するが、<b>この種はその目標を積まない</b>
 * (ユーザー指定 2026-09-21「グループ2〜3は環境生物として扱う」)。殴られれば反撃する。
 * 既存のグループ3 を一括で中立にする改修が入ったら、{@link #registerGoals()} の上書きは外せる。
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code walk} / {@code climb} / {@code glide} / {@code dive}。
 */
public class CoelurosauravusEntity extends AbstractOvergrownPredatorEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation CLIMB_ANIM = RawAnimation.begin().thenLoop("climb");
    private static final RawAnimation GLIDE_ANIM = RawAnimation.begin().thenLoop("glide");
    private static final RawAnimation DIVE_ANIM = RawAnimation.begin().thenLoop("dive");

    /** 地上。歩く・登る木を探す。 */
    public static final int STATE_GROUND = 0;
    /** 幹をよじ登っている。 */
    public static final int STATE_CLIMB = 1;
    /** 天蓋で飛び出す構えをしている。 */
    public static final int STATE_PERCH = 2;
    /** 滑空中。下を見ながら降りていく。 */
    public static final int STATE_GLIDE = 3;
    /** 獲物へ急降下中。 */
    public static final int STATE_DIVE = 4;

    /**
     * <b>滑空比</b>。高さ 1 ブロック下がるあいだに水平へ何ブロック進むか(ユーザー指定 3.0)。
     * 幹の高さが 31〜40 なので、獲物を見つけなければ 100 ブロック前後を滑ることになる。
     */
    public static final double GLIDE_RATIO = 3.0;
    /** 急降下の比。滑空より立てる = 速く落ちる。 */
    public static final double DIVE_RATIO = 0.8;
    /** 滑空の速さ(ブロック/tick、斜め方向の合計)。 */
    private static final double GLIDE_SPEED = 0.35;
    /** 急降下の速さ。 */
    private static final double DIVE_SPEED = 0.62;
    /** 向きを変えられる速さ(1 tick あたりの度)。滑空なので小回りは利かない。 */
    private static final double TURN_RATE = 4.0;
    /**
     * 獲物を探しているあいだの<b>旋回</b>の強さ(1 tick あたりの度、ユーザー指定 2026-09-21)。
     * 直進のままだと滑空ではなく「落ちている」ように見えるうえ、下を舐めるように探せない。
     */
    private static final double DRIFT_MAX = 1.6;
    /** 旋回の向きを振り直す間隔の下限・上限(tick)。弧が単調にならないように散らす。 */
    private static final int DRIFT_MIN_HOLD = 40;
    private static final int DRIFT_MAX_HOLD = 110;

    /** いまの旋回の強さ(度/tick)。正で右、負で左。 */
    private double drift;
    private int driftHold;

    private static final EntityDataAccessor<Integer> DATA_STATE =
            SynchedEntityData.defineId(CoelurosauravusEntity.class, EntityDataSerializers.INT);
    /** 描画用の体の傾き(度、下向きが正)。実際の速度から求めてクライアントへ送る。 */
    private static final EntityDataAccessor<Float> DATA_PITCH =
            SynchedEntityData.defineId(CoelurosauravusEntity.class, EntityDataSerializers.FLOAT);
    /** 描画用の<b>バンク</b>(度)。旋回した向きへ体を倒す。 */
    private static final EntityDataAccessor<Float> DATA_ROLL =
            SynchedEntityData.defineId(CoelurosauravusEntity.class, EntityDataSerializers.FLOAT);

    /** 1 tick の旋回 1 度あたり何度傾けるか。 */
    private static final double BANK_PER_TURN = 13.0;
    /** バンクの上限(度)。 */
    private static final double BANK_MAX = 38.0;

    public CoelurosauravusEntity(EntityType<? extends CoelurosauravusEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "coelurosauravus");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STATE, STATE_GROUND);
        builder.define(DATA_PITCH, 0.0F);
        builder.define(DATA_ROLL, 0.0F);
    }

    /**
     * {@link AbstractOvergrownPredatorEntity#registerGoals()} を<b>呼ばずに</b>組み直す。
     * 違いは 1 点だけ —— <b>侵入者(プレイヤー)を狙う目標を積まない</b>。
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new GlideHuntGoal(this));
        this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.0D, true));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.9D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new OvergrownLandTargetGoal(this, Predation::isPredatorOf));
    }

    public int getFlightState() {
        return this.entityData.get(DATA_STATE);
    }

    public void setFlightState(int state) {
        this.entityData.set(DATA_STATE, state);
    }

    /** 描画用の体の傾き(度)。下を向くほど大きい。 */
    public float getGlidePitch() {
        return this.entityData.get(DATA_PITCH);
    }

    /** 描画用のバンク(度)。旋回している向きへ倒れる。 */
    public float getGlideRoll() {
        return this.entityData.get(DATA_ROLL);
    }

    public boolean isAirborne() {
        int state = getFlightState();
        return state == STATE_GLIDE || state == STATE_DIVE;
    }

    /**
     * 滑空／急降下の 1 tick ぶんの動き。<b>推力を持たないので、速度は毎 tick 決め打ちにする</b> ——
     * 重力に任せると比が守れず、{@code FlyingMoveControl} を使うと羽ばたいて浮いてしまう。
     *
     * @param towards 進みたい方向(水平成分だけ見る)。{@code null} ならいまの向きのまま
     * @param diving  急降下か
     */
    public void glideTowards(Vec3 towards, boolean diving) {
        float yawBefore = this.getYRot();
        double ratio = diving ? DIVE_RATIO : GLIDE_RATIO;
        double speed = diving ? DIVE_SPEED : GLIDE_SPEED;
        // 比 r = 水平/垂直、速さ s = √(水平² + 垂直²) から各成分を出す。
        double vertical = speed / Math.sqrt(1.0 + ratio * ratio);
        double horizontal = vertical * ratio;

        if (towards == null) {
            // 獲物を追っていないときは<b>ゆるく旋回しながら</b>降りる。一定時間ごとに
            // 旋回の向きと強さを振り直すので、同じ弧をぐるぐる回り続けることはない。
            if (--this.driftHold <= 0) {
                this.driftHold = DRIFT_MIN_HOLD
                        + this.random.nextInt(DRIFT_MAX_HOLD - DRIFT_MIN_HOLD);
                this.drift = (this.random.nextDouble() * 2.0 - 1.0) * DRIFT_MAX;
            }
            this.setYRot((float) (this.getYRot() + this.drift));
        } else {
            this.drift = 0.0;
            this.driftHold = 0;
        }
        double yaw = Math.toRadians(this.getYRot());
        double dirX = -Math.sin(yaw);
        double dirZ = Math.cos(yaw);
        if (towards != null) {
            double dx = towards.x - this.getX();
            double dz = towards.z - this.getZ();
            double flat = Math.sqrt(dx * dx + dz * dz);
            if (flat > 0.001) {
                // 目標の方へ<b>少しずつ</b>向き直る。滑空に小回りは無い。
                float want = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
                this.setYRot(approachAngle(this.getYRot(), want, TURN_RATE));
                yaw = Math.toRadians(this.getYRot());
                dirX = -Math.sin(yaw);
                dirZ = Math.cos(yaw);
            }
        }
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
        this.setDeltaMovement(dirX * horizontal, -vertical, dirZ * horizontal);
        this.hasImpulse = true;
        this.entityData.set(DATA_PITCH, (float) Math.toDegrees(Math.atan2(vertical, horizontal)));
        // 旋回した向きへ体を倒す。<b>この tick に実際に回った角度</b>から出すので、
        // ゆるい旋回でも獲物を追う急な向き直りでも、同じ式でそれらしく傾く。
        double turned = net.minecraft.util.Mth.wrapDegrees(this.getYRot() - yawBefore);
        double bank = Math.max(-BANK_MAX, Math.min(BANK_MAX, turned * BANK_PER_TURN));
        this.entityData.set(DATA_ROLL, (float) bank);
    }

    /** 角度 {@code from} を {@code to} へ最大 {@code step} 度だけ寄せる。 */
    private static float approachAngle(float from, float to, double step) {
        float diff = net.minecraft.util.Mth.wrapDegrees(to - from);
        double move = Math.max(-step, Math.min(step, diff));
        return from + (float) move;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide() && !isAirborne()) {
            if (getGlidePitch() != 0.0F) {
                this.entityData.set(DATA_PITCH, 0.0F);
            }
            if (getGlideRoll() != 0.0F) {
                this.entityData.set(DATA_ROLL, 0.0F);
            }
        }
    }

    /** 滑空で降りるのが常なので、落下ではダメージを受けない。 */
    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    /** 登っている間だけ壁に張り付ける。 */
    @Override
    public boolean onClimbable() {
        return getFlightState() == STATE_CLIMB || super.onClimbable();
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 3, state -> {
            state.getController().setAnimationSpeed(1.0D);
            return switch (getFlightState()) {
                case STATE_DIVE -> state.setAndContinue(DIVE_ANIM);
                case STATE_GLIDE, STATE_PERCH -> state.setAndContinue(GLIDE_ANIM);
                case STATE_CLIMB -> state.setAndContinue(CLIMB_ANIM);
                default -> MobAnimation.isWalking(state)
                        ? state.setAndContinue(WALK_ANIM)
                        : state.setAndContinue(IDLE_ANIM);
            };
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.PARROT_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PARROT_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PARROT_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.5F;
    }
}
