package com.yayuy.overgrownvegetation.proto;

import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 粘菌の絨毯の試作の本体(核)。床を這い回る。<b>跳ばない</b>: 段差(ハーフ・階段・柵・壁)は面に張り付いてゆっくり登り、
 * 降りるときも横に面があれば張り付いてゆっくり下がる(ユーザー 2026-10-08「ジャンプなどはせず段差を乗り越える時は面に密着して上る」)。
 * 段差の自動の乗り上げ(STEP_HEIGHT)は 0 —— ハーフ 1 枚でも登って越える。
 * クライアントでは動いた向きと、張り付いている壁の向き(核の傾き)をなめらかにして持つ。
 */
public class MoldCarpetProtoEntity extends PathfinderMob {

    private static final EntityDataAccessor<Boolean> CLIMBING = SynchedEntityData.defineId(MoldCarpetProtoEntity.class, EntityDataSerializers.BOOLEAN);
    /** 面を登る速さ(1 tick あたりのブロック。素の登りは 0.2 で速すぎる)。 */
    static final double CLIMB_SPEED = 0.06;

    /** 動いた向き(1 tick あたりのブロック。クライアントだけ、なめらかにしたもの)。 */
    public double flowX;
    public double flowZ;
    public double flowXOld;
    public double flowZOld;
    /** 張り付いている壁の向き(クライアントだけ)と、核の傾き 0(床)〜1(壁)。 */
    public Direction wall = Direction.NORTH;
    public float tilt;
    public float tiltOld;

    public MoldCarpetProtoEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.12)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.STEP_HEIGHT, 0.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(CLIMBING, false);
    }

    @Override
    protected void registerGoals() {
        // 試作なので動きを見やすく: よく歩き出す
        this.goalSelector.addGoal(1, new RandomStrollGoal(this, 1.0, 30));
    }

    /** 壁を登る経路も引く(クモと同じ)。 */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new WallClimberNavigation(this, level);
    }

    public boolean isClimbing() {
        return this.entityData.get(CLIMBING);
    }

    @Override
    public boolean onClimbable() {
        return this.isClimbing();
    }

    /** 跳ばない(段差は {@link #onClimbable()} で面を登る)。 */
    @Override
    public void jumpFromGround() {
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            // 横に当たっている間と、宙にいて横に面がある間(段差を降りる途中)は面に張り付く
            this.entityData.set(CLIMBING, this.horizontalCollision || (!this.onGround() && this.wallBeside() != null));
            if (this.isClimbing()) {
                Vec3 v = this.getDeltaMovement();
                if (v.y > CLIMB_SPEED) {
                    this.setDeltaMovement(v.x, CLIMB_SPEED, v.z);
                }
            }
        } else {
            this.flowXOld = this.flowX;
            this.flowZOld = this.flowZ;
            double vx = this.getX() - this.xo;
            double vz = this.getZ() - this.zo;
            this.flowX += (vx - this.flowX) * 0.06;
            this.flowZ += (vz - this.flowZ) * 0.06;

            this.tiltOld = this.tilt;
            Direction w = this.isClimbing() && !this.onGround() ? this.wallBeside() : null;
            if (w != null) {
                this.wall = w;
            }
            this.tilt += ((w != null ? 1.0F : 0.0F) - this.tilt) * 0.3F;
        }
    }

    /** 横(東西南北)に触れている面の向き。無ければ null。 */
    public Direction wallBeside() {
        AABB bb = this.getBoundingBox();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            AABB probe = bb.expandTowards(d.getStepX() * 0.08, 0, d.getStepZ() * 0.08).deflate(0, 0.01, 0);
            if (!this.level().noCollision(this, probe)) {
                return d;
            }
        }
        return null;
    }

    /** 網は当たり判定の外まで描くので、画面外の判定の箱を網の届く所まで広げる。 */
    @Override
    public AABB getBoundingBoxForCulling() {
        return this.getBoundingBox().inflate(2.5, 2.5, 2.5);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }
}
