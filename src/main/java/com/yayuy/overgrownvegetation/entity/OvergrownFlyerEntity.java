package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * トンボ系の飛行 Mob(グループ2の {@link StenodictyaLobataEntity}、グループ3の {@link MeganeuraEntity})の
 * 共通の土台。{@code Bee} と同じ方式で {@link FlyingMoveControl}(上下を比例制御にした
 * {@link SmoothFlyingMoveControl})+ {@link FlyingPathNavigation} を使い、
 * 落下ダメージを受けない。
 *
 * <p>{@link #isStationary()} の間は自力移動しない(枝に止まっている/休んでいる)。
 */
public abstract class OvergrownFlyerEntity extends PathfinderMob implements GeoEntity, ParasitizedMob {

    /** 飛行中(移動状態で fly / hover を切替)。 */
    public static final int STATE_FLY = 0;
    /** 木の枝などに止まっている。 */
    public static final int STATE_PERCH = 1;
    /** その場でホバリング(ステノデクティアの「止まっている」状態)。 */
    public static final int STATE_HOVER = 2;
    /** 実を食べている。 */
    public static final int STATE_EAT = 3;
    /** 獲物へ突進して追いかけている(メガネウラ)。羽ばたきだけで体は揺らさない。 */
    public static final int STATE_ATTACK = 4;
    /** 枝の上に降りて獲物を咀嚼している(メガネウラ)。 */
    public static final int STATE_FEED = 5;
    /** 枝の上で休息している(メガネウラ)。 */
    public static final int STATE_REST = 6;
    /** 噛みついた瞬間だけの短い状態(メガネウラ)。頭を振り下ろす。 */
    public static final int STATE_BITE = 7;

    /**
     * 動作を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>(4.7.6 の {@code AnimationController#process})。
     * だから画面の上では、どのアニメも状態を切り替えた tick より 3 tick 遅れて始まる。
     * アニメの時刻に合わせる tick は「これ + アニメの秒 × 20」で数える。
     * メガネウラの噛みつきの出入りだけは補間 0({@link MeganeuraEntity#registerControllers})。
     */
    static final int TRANSITION_TICKS = 3;

    protected static final EntityDataAccessor<Integer> DATA_ACTION_STATE =
            SynchedEntityData.defineId(OvergrownFlyerEntity.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    protected OvergrownFlyerEntity(EntityType<? extends OvergrownFlyerEntity> type, Level level) {
        super(type, level);
        // 上下の推力を比例制御にした FlyingMoveControl(SmoothFlyingMoveControl 参照)。
        // バニラのままだと目標高度を跨ぐたびに全開で押し戻して体が上下に揺れる。
        this.moveControl = new SmoothFlyingMoveControl(this, 20, true);
        this.setPathfindingMalus(PathType.DANGER_FIRE, -1.0F);
        this.setPathfindingMalus(PathType.WATER, -1.0F);
        this.setPathfindingMalus(PathType.WATER_BORDER, 16.0F);
        this.setPathfindingMalus(PathType.COCOA, -1.0F);
        this.setPathfindingMalus(PathType.FENCE, -1.0F);
        this.xpReward = 3;
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation navigation = new FlyingPathNavigation(this, level) {
            @Override
            public boolean isStableDestination(BlockPos pos) {
                return true;
            }

            /**
             * 経路上の次の点の高さをそのまま使う。
             *
             * <p>{@code PathNavigation} の既定実装は「足元のブロックが空気でなければ床の高さ」を
             * 返すので、地面や葉の上を飛んでいる間は目標高度が床まで引き下げられてしまい、
             * 経路に沿って飛ぶ tick と、ゴール側が直接指定する tick とで目標高度が食い違って
             * 体が上下に揺れる。飛行 Mob に床へ吸い寄せられる必要はない。
             */
            @Override
            protected double getGroundY(net.minecraft.world.phys.Vec3 vec) {
                return vec.y;
            }
        };
        navigation.setCanOpenDoors(false);
        navigation.setCanFloat(false);
        navigation.setCanPassDoors(true);
        return navigation;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION_STATE, STATE_FLY);
    }

    public int getActionState() {
        return this.entityData.get(DATA_ACTION_STATE);
    }

    public void setActionState(int state) {
        this.entityData.set(DATA_ACTION_STATE, state);
    }

    /**
     * 行動状態が変わったら当たり判定を作り直す。止まっている間だけ判定を縮める Mob
     * (メガネウラ)がいるため、サーバー・クライアントの両方で追従させる必要がある。
     */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_ACTION_STATE.equals(key)) {
            refreshDimensions();
        }
    }

    public boolean isPerching() {
        return getActionState() == STATE_PERCH;
    }

    /**
     * 枝に固定されて自力移動しない状態か。{@link #STATE_PERCH}(枝の下にぶら下がる)に加えて、
     * メガネウラが枝の上で使う {@link #STATE_FEED} / {@link #STATE_REST} も含む。
     */
    public boolean isStationary() {
        int state = getActionState();
        return state == STATE_PERCH || state == STATE_FEED || state == STATE_REST;
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, net.minecraft.world.level.block.state.BlockState state, BlockPos pos) {
        // 飛行 Mob なので落下判定はしない。
    }

    @Override
    public boolean isFlapping() {
        return !this.onGround();
    }

    /**
     * バニラの {@code LivingEntity.getFlyingSpeed()} は {@code 0.02F} 固定で、{@code FLYING_SPEED} 属性は
     * {@code FlyingMoveControl} 内でしか使われず {@code travel()} の水平移動には効かない。
     *
     * <p>ここで {@code getSpeed()}(＝{@code FlyingMoveControl} が「ゴールの速度倍率 × {@code FLYING_SPEED}」を
     * 入れた値)を基準にすることで、{@code mobs.json} の {@code flyingSpeed} と各ゴールの速度倍率の
     * 両方が実際の飛行速度に反映されるようにする。係数 {@code 0.02} は「{@code flyingSpeed} 1.0 ×
     * 速度倍率 1.0 でおおよそバニラのミツバチ相当(≈0.22 ブロック/tick)」を狙った値。
     * 下限は失速して止まらないための保険。もっと速くしたい場合は {@code mobs.json} の
     * {@code flyingSpeed} を上げる(既定: ステノデクティア 0.8 / メガネウラ 1.8)。
     */
    @Override
    protected float getFlyingSpeed() {
        return Math.max(this.getSpeed(), 0.4F) * 0.02F;
    }

    @Override
    public void travel(net.minecraft.world.phys.Vec3 travelVector) {
        if (isStationary()) {
            super.travel(net.minecraft.world.phys.Vec3.ZERO);
        } else {
            super.travel(travelVector);
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
