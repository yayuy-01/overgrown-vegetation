package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 「巣の蔦」({@code hive_vine})。{@link HiveNodeEntity ハイヴノード}を広間の殻から吊っている
 * 1 本ぶんの蔦。ブロックではなく<b>エンティティ</b>なので、プレイヤーが 1 本ずつ切り落とせる。
 *
 * <h2>形</h2>
 * <p>ノード側の端({@link #getOrigin})と殻側の端({@link #getAnchor})の 2 点を持ち、
 * その間を垂れ下がった曲線で結んだものが見た目({@link com.yayuy.overgrownvegetation.client.HiveVineRenderer})。
 * エンティティ自身の座標は<b>曲線のちょうど真ん中</b>に置いてあり、当たり判定もそこだけなので、
 * 蔦を切るには「垂れ下がった弧の真ん中あたり」を殴ることになる。
 * 曲線の制御点はこの 3 点から復元できる(2×中点 −(始点+終点)/2)ので、たわみ具合を別途
 * 同期する必要はない。
 *
 * <h2>切ると落ちる</h2>
 * <p>どのノードに属するかを {@link #getNodeId} に持っていて、ノード側が定期的に生き残りを数える。
 * {@code hiveNodeFallThreshold}(既定 0.7)を超えて切られるとノードが落下し、残った蔦も
 * ノードによって引きちぎられる({@link HiveNodeEntity} 参照)。
 */
public class HiveVineEntity extends PathfinderMob {

    private static final EntityDataAccessor<BlockPos> DATA_ANCHOR =
            SynchedEntityData.defineId(HiveVineEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<BlockPos> DATA_ORIGIN =
            SynchedEntityData.defineId(HiveVineEntity.class, EntityDataSerializers.BLOCK_POS);

    /**
     * 曲線に沿って並べる当たり判定の部位数。蔦の長さは 10〜20 ブロックほどなので、
     * この数だけ等間隔に置けば隙間なく覆える({@link HiveVinePart})。
     */
    private static final int PARTS = 14;

    /** 蔦のどこを殴っても切れるようにするための、曲線に沿った当たり判定({@link HiveVinePart})。 */
    private final HiveVinePart[] parts;

    /** 吊っているハイヴノードの UUID。ノード側が生き残りを数えるときの目印。 */
    private UUID nodeId;

    public HiveVineEntity(EntityType<? extends HiveVineEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setNoGravity(true);
        this.parts = new HiveVinePart[PARTS];
        for (int i = 0; i < PARTS; i++) {
            this.parts[i] = new HiveVinePart(this, 1.1F, 1.1F);
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public net.neoforged.neoforge.entity.PartEntity<?>[] getParts() {
        return this.parts;
    }

    /** エンダードラゴンと同じく、部位にも本体に続く id を割り当てる(クライアントとの対応付け用)。 */
    @Override
    public void setId(int id) {
        super.setId(id);
        for (int i = 0; i < this.parts.length; i++) {
            this.parts[i].setId(id + i + 1);
        }
    }

    /**
     * 当たり判定の部位を曲線に沿って並べ直す。始点・終点・中点はすべて同期されているので、
     * サーバーでもクライアントでも同じ位置になる。
     */
    private void positionParts() {
        Vec3 start = getOrigin();
        Vec3 end = getAnchor();
        if (start.lengthSqr() == 0.0D || end.lengthSqr() == 0.0D) {
            return;
        }
        Vec3 through = new Vec3(this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ());
        Vec3 control = through.scale(2.0D).subtract(start.add(end).scale(0.5D));
        // 部位の間隔に合わせて太らせ、長い蔦でも隙間ができないようにする。
        float partSize = (float) Math.max(1.1D, start.distanceTo(end) / PARTS * 1.4D);
        for (int i = 0; i < PARTS; i++) {
            this.parts[i].setSize(partSize);
            double t = (i + 0.5D) / PARTS;
            double it = 1.0D - t;
            double px = it * it * start.x + 2 * it * t * control.x + t * t * end.x;
            double py = it * it * start.y + 2 * it * t * control.y + t * t * end.y;
            double pz = it * it * start.z + 2 * it * t * control.z + t * t * end.z;
            this.parts[i].setPos(px - this.parts[i].getBbWidth() * 0.5D,
                    py - this.parts[i].getBbHeight() * 0.5D,
                    pz - this.parts[i].getBbWidth() * 0.5D);
        }
    }

    @Override
    public void tick() {
        super.tick();
        positionParts();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "hive_vine");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ANCHOR, BlockPos.ZERO);
        builder.define(DATA_ORIGIN, BlockPos.ZERO);
    }

    @Override
    protected void registerGoals() {
        // 張られたまま動かないので AI ゴールは持たせない。
    }

    /**
     * 蔦を張る。{@code origin} はノード側の端、{@code anchor} は殻側の端(どちらもワールド座標)。
     * エンティティ自身は曲線の中点(= たわみを含んだ実際の見た目の真ん中)に置く。
     */
    public void stretch(Vec3 origin, Vec3 anchor, double sag, UUID nodeId) {
        this.nodeId = nodeId;
        this.entityData.set(DATA_ORIGIN, BlockPos.containing(origin));
        this.entityData.set(DATA_ANCHOR, BlockPos.containing(anchor));
        Vec3 mid = origin.add(anchor).scale(0.5D).subtract(0.0D, sag * 0.5D, 0.0D);
        this.moveTo(mid.x, mid.y - this.getBbHeight() * 0.5D, mid.z, 0.0F, 0.0F);
        positionParts();
    }

    /** ノード側の端(ワールド座標)。 */
    public Vec3 getOrigin() {
        return Vec3.atCenterOf(this.entityData.get(DATA_ORIGIN));
    }

    /** 殻側の端(ワールド座標)。 */
    public Vec3 getAnchor() {
        return Vec3.atCenterOf(this.entityData.get(DATA_ANCHOR));
    }

    public UUID getNodeId() {
        return nodeId;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("anchor", net.minecraft.nbt.NbtUtils.writeBlockPos(this.entityData.get(DATA_ANCHOR)));
        tag.put("origin", net.minecraft.nbt.NbtUtils.writeBlockPos(this.entityData.get(DATA_ORIGIN)));
        if (nodeId != null) {
            tag.putUUID("nodeId", nodeId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        net.minecraft.nbt.NbtUtils.readBlockPos(tag, "anchor").ifPresent(p -> this.entityData.set(DATA_ANCHOR, p));
        net.minecraft.nbt.NbtUtils.readBlockPos(tag, "origin").ifPresent(p -> this.entityData.set(DATA_ORIGIN, p));
        if (tag.hasUUID("nodeId")) {
            nodeId = tag.getUUID("nodeId");
        }
    }

    /** 引きちぎられる(ノードが落下したときに残りの蔦をまとめて切る)。 */
    public void snap(ServerLevel level) {
        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.VINE_BREAK, this.getSoundSource(), 1.0F, 0.7F);
        this.discard();
    }

    /**
     * 見た目の曲線はエンティティの座標(曲線の中点)から遠く離れたところまで伸びているので、
     * カリング用の箱は両端を含む大きさにしておく。既定(当たり判定そのまま)だと、
     * 中点が画面外に出た瞬間に蔦ごと消えてしまう。
     */
    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        return new net.minecraft.world.phys.AABB(getOrigin(), getAnchor()).inflate(2.0D);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        return false;
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return false;
    }

    /** 環境ダメージ(窒息・押し潰し・落下)では切れない。切れるのは攻撃だけ。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.CRAMMING) || source.is(DamageTypes.FALL)) {
            return false;
        }
        return super.hurt(source, amount);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.VINE_STEP;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.VINE_BREAK;
    }
}
