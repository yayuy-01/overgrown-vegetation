package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.siege.SiegeData;
import com.yayuy.overgrownvegetation.siege.TitanicaKinematics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * ドルガ=アーケロウス・ティタニカの<b>当たり判定の箱</b>。体に沿って
 * {@link TitanicaKinematics#partCount()} 個(頭 1 / 首 6 / 胴・尾 21)並ぶ、見えない独立エンティティ。
 *
 * <h2>なぜ独立したエンティティなのか — マルチパートをやめた理由</h2>
 * <p>最初はエンダードラゴンと同じ NeoForge のマルチパート({@code PartEntity})で作っていた。
 * サーバー側はそれで正しく動く({@code Level#getEntities} はパーツ一覧をセクション索引を使わず
 * 総当たりで見るので、距離に関係なく当たる)。<b>問題はクライアントだった</b> —
 * パーツは親が {@code ChunkMap} に追跡されて初めてクライアントへ登録されるのに、その追跡距離は
 * 「プレイヤーと<b>親の位置</b>の水平距離 &le; min(clientTrackingRange, 描画距離) × 16」で頭打ちになる。
 * 全長 615 ブロックのボスでは親(胴の中心)から数百ブロック離れた頭や尾の側にプレイヤーが立つと
 * 親ごと届かず、<b>そこには当たり判定が存在しない</b>。これが「しっぽの付け根にしか当たらない」の正体。
 *
 * <p>親をプレイヤーの近くへ寄せる回避策も試したが、寄せられるのは 1 人ぶんで、しかも
 * 「当たり判定が体のどこにあるのか」が親の位置しだいで変わる — 体のどこを撃っても当たる、という
 * 保証にならない。そこで<b>箱そのものを普通のエンティティにした</b>。こうすると追跡も当たり判定も
 * バニラの Mob とまったく同じ規則で回る: <b>箱ごとに</b>近くのプレイヤーへ届き、読み込まれている
 * 範囲にある箱は常にそこに在る。マルチでも全員ぶん効く。
 *
 * <h2>位置</h2>
 * <p>サーバーでは親({@link DorgaArchelausTitanicaEntity#tick})が毎 tick まとめて押し込む —
 * 親は必ずプレイヤーの近く(= ロード済みのチャンク)に居るので、体の遠い端の箱が
 * 未ロードでティックされずに取り残される、ということが起きない。
 * クライアントでは箱が自分で {@link com.yayuy.overgrownvegetation.client.ClientSiegeBoss}
 * (遠距離時とまったく同じ軌道の式)から計算する。どちらも同じ式なので位置パケットに頼らない。
 *
 * <h2>体力は持たない</h2>
 * <p>被弾は {@link DorgaArchelausTitanicaEntity#hurtFromHitbox} へ丸ごと転送し、そこから
 * {@link SiegeData} に集約される。箱自身は壊れも死にもしない。
 */
public class TitanicaHitboxEntity extends Entity {

    /** 体のどの箱か(0 = 頭、1〜6 = 首の付け根→先端、7〜 = 胴・尾)。大きさをクライアントで決めるため同期する。 */
    private static final EntityDataAccessor<Integer> DATA_PART_INDEX =
            SynchedEntityData.defineId(TitanicaHitboxEntity.class, EntityDataSerializers.INT);

    public TitanicaHitboxEntity(EntityType<? extends TitanicaHitboxEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvisible(true);
        this.setSilent(true);
    }

    /** {@link DorgaArchelausTitanicaEntity} が 1 体ずつ湧かせるときに使う。 */
    public static TitanicaHitboxEntity create(ServerLevel level, int partIndex) {
        TitanicaHitboxEntity entity = ModEntities.TITANICA_HITBOX.get().create(level);
        if (entity != null) {
            entity.setPartIndex(partIndex);
        }
        return entity;
    }

    /** 親(体力と描画を持つ本体)。被弾の転送先。 */
    @javax.annotation.Nullable
    private java.util.UUID ownerId;

    public void setOwner(DorgaArchelausTitanicaEntity owner) {
        this.ownerId = owner.getUUID();
    }

    public int partIndex() {
        return this.entityData.get(DATA_PART_INDEX);
    }

    private void setPartIndex(int index) {
        this.entityData.set(DATA_PART_INDEX, index);
        this.refreshDimensions();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_PART_INDEX, 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_PART_INDEX.equals(key)) {
            this.refreshDimensions();
        }
    }

    /** 箱の大きさはモデル単位の一辺({@link TitanicaKinematics#partSizes})。立方体。 */
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        double[] sizes = TitanicaKinematics.partSizes(OvergrownVegetationConfig.COMMON.siegeScale.get());
        // コンストラクタから呼ばれる経路では entityData がまだ組み上がっていない。
        int index = this.entityData == null ? 0 : Mth.clamp(this.partIndex(), 0, sizes.length - 1);
        float size = (float) sizes[index];
        return EntityDimensions.scalable(size, size);
    }

    /** 箱の<b>中心</b>を {@code center} へ置く(エンティティの位置は箱の底なので半分下げる)。 */
    public void placeAt(Vec3 center) {
        this.setPos(center.x, center.y - this.getBbHeight() * 0.5D, center.z);
    }

    /**
     * バニラの {@code Entity#tick}(炎・窒息・水流・ポータル…)は一切要らないので呼ばない。
     * サーバーでは親が位置を押し込むのでここは後始末だけ、クライアントでは自分で位置を計算する。
     */
    @Override
    public void tick() {
        if (this.level() instanceof ServerLevel serverLevel) {
            if (!SiegeData.get(serverLevel).isActive()) {
                this.discard();
            }
            return;
        }
        Vec3[] centers = DorgaArchelausTitanicaEntity.currentPartCenters(this.level());
        if (centers != null) {
            this.placeAt(centers[Mth.clamp(this.partIndex(), 0, centers.length - 1)]);
        }
    }

    /** 位置は両サイドで同じ式から作るので、サーバーから届く補間指示は捨てる。 */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.setPos(x, y, z);
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        // 親が居ないのに箱だけ残っている(親のチャンクが落ちた等)場合は攻城戦の状態へ直接入れる。
        if (this.ownerId != null && serverLevel.getEntity(this.ownerId) instanceof DorgaArchelausTitanicaEntity boss
                && boss.isAlive()) {
            return boss.hurtFromHitbox(source, amount);
        }
        SiegeData.get(serverLevel).hurt(serverLevel, amount, source);
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

    /** 一辺 20 ブロック超の箱を毎 tick 走査させない(親と同じ理由)。 */
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
