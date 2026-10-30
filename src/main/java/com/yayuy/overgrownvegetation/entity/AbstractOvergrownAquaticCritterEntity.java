package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.control.SmoothSwimmingLookControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
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
 * グループ2(原生生産種)の<b>水生</b> Mob 共通の土台 —— リゾドゥス・クセナカンサスなど。
 *
 * <p>{@link AbstractOvergrownCritterEntity}(陸生)の水中版。他の Mob を一切攻撃せず
 * (索敵/近接ゴールなし)、殴られたら {@link PanicGoal} で逃げ、対応する捕食者({@link Predation})が
 * 近づいたら {@link CritterAvoidPredatorGoal} で距離を取る。魚と同じ遊泳挙動
 * ({@link WaterBoundPathNavigation} + {@link SmoothSwimmingMoveControl} + 水中用の {@link #travel})。
 *
 * <p>{@link ParasitizedMob} 実装で「胞子感染」に一切かからず、他の過繁茂 Mob からも標的にされない。
 * 水中で窒息しないよう、実装 Mob はエンティティタイプタグ {@code #minecraft:can_breathe_under_water} に入れること。
 */
public abstract class AbstractOvergrownAquaticCritterEntity extends PathfinderMob
        implements GeoEntity, CritterAction.Holder, ParasitizedMob, AquaticOvergrownMob {

    /** 通常。 */
    public static final int STATE_IDLE = 0;
    /** 種族固有の特殊動作中。 */
    public static final int STATE_SPECIAL = 1;

    protected static final EntityDataAccessor<Integer> DATA_ACTION_STATE =
            SynchedEntityData.defineId(AbstractOvergrownAquaticCritterEntity.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    protected AbstractOvergrownAquaticCritterEntity(EntityType<? extends AbstractOvergrownAquaticCritterEntity> type,
                                                    Level level) {
        super(type, level);
        this.moveControl = new SmoothSwimmingMoveControl(this, 85, 10, 0.02F, 0.1F, true);
        this.lookControl = new SmoothSwimmingLookControl(this, 10);
        this.setPathfindingMalus(PathType.WATER, 0.0F);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new WaterBoundPathNavigation(this, level);
    }

    /** 今している行動(食べる・休む・驚いて逃げ出す など)。アニメの再生の口だけ(行動の Goal は後の段)。2026-10-22。 */
    private static final EntityDataAccessor<Byte> DATA_CRITTER_ACTION =
            SynchedEntityData.defineId(AbstractOvergrownAquaticCritterEntity.class, EntityDataSerializers.BYTE);

    @Override
    public CritterAction getCritterAction() {
        return CritterAction.byId(this.entityData.get(DATA_CRITTER_ACTION));
    }

    @Override
    public void setCritterAction(CritterAction action) {
        this.entityData.set(DATA_CRITTER_ACTION, (byte) action.ordinal());
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (getCritterAction() != CritterAction.NONE) {
            tag.putString(CritterAction.NBT, getCritterAction().name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(CritterAction.NBT)) {
            setCritterAction(CritterAction.byName(tag.getString(CritterAction.NBT)));
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION_STATE, STATE_IDLE);
        builder.define(DATA_CRITTER_ACTION, (byte) 0);
    }

    public int getActionState() {
        return this.entityData.get(DATA_ACTION_STATE);
    }

    public void setActionState(int state) {
        this.entityData.set(DATA_ACTION_STATE, state);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new PanicGoal(this, 1.4D));
        this.goalSelector.addGoal(1, new CritterAvoidPredatorGoal(this));
        registerSpeciesGoals();
        this.goalSelector.addGoal(5, new RandomSwimmingGoal(this, 1.0D, 40));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    /** 種族固有のゴール(優先度 2〜4 目安)。 */
    protected void registerSpeciesGoals() {
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
