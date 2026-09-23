package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.control.SmoothSwimmingLookControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomSwimmingGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * グループ3(原生消費種)の<b>水生</b>捕食 Mob 共通の土台 —— オルタカンタス・メガラクネ。
 *
 * <p>索敵対象は3つ:
 * <ol>
 *     <li>捕食対象 — {@code predation.json}({@link Predation#isPredatorOf})に載っている被食者を
 *     水陸問わず一方的に狩る。</li>
 *     <li>水中・水面の相手 — {@link Predation#isWaterborneTarget}: 捕食対象でないグループ2の Mob や
 *     侵入者でも、<b>水中・水面にいる</b>間は襲う。海岸・川辺の陸上にいる相手は襲わない
 *     (ユーザー要望 2026-09-08)。</li>
 *     <li>反撃 — 殴られたら反撃する({@link OvergrownHurtByTargetGoal})。</li>
 * </ol>
 *
 * <p>倒すたびに攻撃力・体力が上昇({@link PredatorMob}、NBT {@code "PreyKills"})。
 * {@link ParasitizedMob} 実装で「胞子感染」に一切かからない。魚と同じ遊泳挙動。
 */
public abstract class AbstractOvergrownAquaticPredatorEntity extends PathfinderMob
        implements GeoEntity, ParasitizedMob, PredatorMob, AquaticOvergrownMob {

    public static final int STATE_IDLE = 0;
    public static final int STATE_SPECIAL = 1;
    /** 噛みつき(攻撃アニメ)を見せている最中。当てたときだけ入る({@link #doHurtTarget})。 */
    public static final int STATE_ATTACK = 2;

    /**
     * 動作を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>(4.7.6 の {@code AnimationController#process})。
     * だから画面の上では、噛みつきのアニメは当てた tick より 4 tick 遅れて始まる。
     * アニメの長さに合わせる tick は「これ + アニメの秒 × 20」で数える。
     */
    protected static final int TRANSITION_TICKS = 4;

    protected static final EntityDataAccessor<Integer> DATA_ACTION_STATE =
            SynchedEntityData.defineId(AbstractOvergrownAquaticPredatorEntity.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    private int preyKillCount;
    /** {@link #STATE_ATTACK} の残り tick(サーバーだけ)。 */
    private int attackAnimTicks;

    protected AbstractOvergrownAquaticPredatorEntity(EntityType<? extends AbstractOvergrownAquaticPredatorEntity> type,
                                                     Level level) {
        super(type, level);
        this.moveControl = new SmoothSwimmingMoveControl(this, 85, 12, 0.03F, 0.12F, true);
        this.lookControl = new SmoothSwimmingLookControl(this, 12);
        this.setPathfindingMalus(PathType.WATER, 0.0F);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new WaterBoundPathNavigation(this, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION_STATE, STATE_IDLE);
    }

    public int getActionState() {
        return this.entityData.get(DATA_ACTION_STATE);
    }

    public void setActionState(int state) {
        this.entityData.set(DATA_ACTION_STATE, state);
    }

    @Override
    protected void registerGoals() {
        registerSpeciesGoals();
        this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.3D, true));
        this.goalSelector.addGoal(6, new RandomSwimmingGoal(this, 1.0D, 40));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new OvergrownHunterTargetGoal(this, Predation::isPredatorOf));
        // 水中・水面にいる相手だけを狙い、陸に出たら見失う。
        this.targetSelector.addGoal(3, new OvergrownWaterborneTargetGoal(this));
    }

    /** 種族固有のゴール(優先度 1〜3 目安)。 */
    protected void registerSpeciesGoals() {
    }

    // --- 攻撃モーション ---

    /**
     * 攻撃アニメーション({@code attack})を何 tick 見せるか。「{@link #TRANSITION_TICKS} + アニメの秒 × 20」にする。
     *
     * <p>2026-09-23 まではアニメを {@code swinging} で選んでいたが、振りの時間を進める
     * {@code updateSwingTime} を呼ぶのはバニラでは {@code Monster} と {@code Player} だけで、
     * {@code PathfinderMob} のこの mob では<b>一度噛むと {@code swinging} が true のまま降りず、
     * 以後ずっと {@code attack} が流れっぱなし</b>だった(泳いでいても)。いまは同期した状態で選ぶ。
     */
    protected abstract int attackAnimationTicks();

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && !this.level().isClientSide()) {
            this.attackAnimTicks = attackAnimationTicks();
            setActionState(STATE_ATTACK);
        }
        return hit;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (this.attackAnimTicks > 0 && --this.attackAnimTicks == 0 && getActionState() == STATE_ATTACK) {
            setActionState(STATE_IDLE);
        }
    }

    // --- 捕食数(PredatorMob) ---

    @Override
    public int getPreyKillCount() {
        return this.preyKillCount;
    }

    @Override
    public void setPreyKillCount(int count) {
        this.preyKillCount = count;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("PreyKills", this.preyKillCount);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.preyKillCount = tag.getInt("PreyKills");
        PredatorMob.applyPreyKillBonus(this, this.preyKillCount);
    }

    // --- 遊泳 ---

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (this.isControlledByLocalInstance() && this.isInWater()) {
            this.moveRelative(this.getSpeed(), travelVector);
            this.move(MoverType.SELF, this.getDeltaMovement());
            this.setDeltaMovement(this.getDeltaMovement().scale(0.9D));
            if (this.getTarget() == null) {
                this.setDeltaMovement(this.getDeltaMovement().add(0.0D, -0.005D, 0.0D));
            }
        } else {
            super.travel(travelVector);
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
