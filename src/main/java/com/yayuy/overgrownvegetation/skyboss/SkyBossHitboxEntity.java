package com.yayuy.overgrownvegetation.skyboss;

import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;

/**
 * 空の巨大生物の<b>当たりの受け手</b>(2026-10-18 夕方)。見えない小さな箱で、ボスの形({@link SkyBossPose})に当たる所へだけ置く。
 *
 * <h2>なぜ受け手を置くのか</h2>
 * ボスは全長 約 2100 ブロックの描画だけの存在(エンティティではない)。バニラの飛び道具・剣は「エンティティの箱」にしか当たらないが、
 * 体じゅうを箱で覆うと、大きい箱はバニラのエンティティの探し方(区画ごと、はみ出しは 2 ブロックまでしか見ない)で見落とされ、小さい箱では数が多すぎる。
 * そこで形との当たりは自前で計算し、当たる所にだけこれを置いて、ダメージの計算(弓の力・エンチャント・クリティカル・矢が消える)はバニラに任せる。
 * <ul>
 *   <li>{@link #KIND_SHOT}: 次の tick に飛び道具が形に当たる所へ置く(サーバーだけ。2 tick で消える)。</li>
 *   <li>{@link #KIND_MELEE}: 近くのプレイヤー 1 人に 1 つ。視線と形の交わる所(届く距離の中)へ置き、剣で殴れるようにする。
 *       持ち主のクライアントでは自分で同じ計算をして置く(サーバーの位置の知らせは使わない)。</li>
 * </ul>
 * 受けたダメージは {@link SkyBossData#hurt} へ渡し、部位(苔・避雷針・蕾)の倍率を掛ける。受け手は壊れも死にもせず、保存もしない。
 */
public class SkyBossHitboxEntity extends Entity {

    public static final int KIND_SHOT = 0;
    public static final int KIND_MELEE = 1;

    private static final EntityDataAccessor<Integer> DATA_KIND = SynchedEntityData.defineId(SkyBossHitboxEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(SkyBossHitboxEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** クライアント側の手(クライアントのクラスをここから直に呼ばないため。{@code ClientSkyBoss} が起動時に入れる)。 */
    public interface ClientHooks {
        void placeMeleeProxy(SkyBossHitboxEntity proxy);

        boolean isLocalPlayer(Optional<UUID> owner);
    }

    public static ClientHooks client;

    /** 当たった部位({@link SkyBossPose#PART_MOSS} など)と、蕾ならその添字(ほかは -1)。サーバーだけ。 */
    private int part;
    private int index = -1;
    /** 今ボスの形の上に居るか(居ないと当たらない)。サーバーは計算で、持ち主のクライアントは自分の計算で決める。 */
    private boolean active;

    public SkyBossHitboxEntity(EntityType<? extends SkyBossHitboxEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvisible(true);
        this.setSilent(true);
    }

    public static SkyBossHitboxEntity create(ServerLevel level, int kind, UUID owner) {
        SkyBossHitboxEntity e = ModEntities.SKY_BOSS_HITBOX.get().create(level);
        if (e != null) {
            e.entityData.set(DATA_KIND, kind);
            e.entityData.set(DATA_OWNER, Optional.ofNullable(owner));
            e.refreshDimensions();
        }
        return e;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_KIND, KIND_SHOT);
        builder.define(DATA_OWNER, Optional.empty());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_KIND.equals(key)) {
            this.refreshDimensions();
        }
    }

    public int kind() {
        return this.entityData.get(DATA_KIND);
    }

    public Optional<UUID> owner() {
        return this.entityData.get(DATA_OWNER);
    }

    /** 箱の真ん中を (x, y, z) へ置き、部位と当たるかを決める。 */
    public void place(double x, double y, double z, int part, boolean active) {
        place(x, y, z, part, -1, active);
    }

    /** 箱の真ん中を (x, y, z) へ置き、部位(蕾ならその添字 {@code index})と当たるかを決める。 */
    public void place(double x, double y, double z, int part, int index, boolean active) {
        this.setPos(x, y - this.getBbHeight() * 0.5, z);
        this.part = part;
        this.index = index;
        this.active = active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        // 飛び道具の受け手は 1 ブロック、殴る受け手は 0.6(視線の先に小さく)
        boolean melee = this.entityData != null && this.kind() == KIND_MELEE;
        return EntityDimensions.fixed(melee ? 0.6F : 1.0F, melee ? 0.6F : 1.0F);
    }

    @Override
    public void tick() {
        if (this.level() instanceof ServerLevel server) {
            if (!SkyBossData.get(server).isActive() || (this.kind() == KIND_SHOT && this.tickCount >= 2)) {
                this.discard();
            }
            return;
        }
        if (this.kind() == KIND_MELEE && client != null) {
            client.placeMeleeProxy(this);
        }
    }

    /** 持ち主のクライアントは自分で置くので、サーバーからの位置の知らせは使わない(ほかの人の受け手はそのまま)。 */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        if (this.level().isClientSide && this.kind() == KIND_MELEE
                && client != null && client.isLocalPlayer(this.owner())) {
            return;
        }
        this.setPos(x, y, z);
    }

    @Override
    public boolean isPickable() {
        if (!this.active) {
            return false;
        }
        if (this.level().isClientSide) {
            return this.kind() == KIND_MELEE && client != null && client.isLocalPlayer(this.owner());
        }
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!(this.level() instanceof ServerLevel server) || !this.active) {
            return false;
        }
        // 当たった弾は苔に吸われる(true を返すと矢は消える)
        SkyBossData.get(server).hurt(server, source, amount, this.part, this.index, this.getX(), this.getY() + this.getBbHeight() * 0.5, this.getZ());
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    protected boolean updateInWaterStateAndDoFluidPushing() {
        return false;
    }

    @Override
    protected void checkInsideBlocks() {
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
