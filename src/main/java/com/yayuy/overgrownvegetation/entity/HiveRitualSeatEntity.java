package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * 儀式の<b>座席</b>。プレイヤーはこれに乗る。見えない・当たらない・保存しない。
 *
 * <p>位置は毎 tick、儀式の経過時間から<b>両側で同じ式</b>で計算する({@link #pathPos})。
 * サーバーの座標同期は使わない({@link #lerpTo} を無効)。プレイヤーの実際の位置がアニメーション(PAL の体)と
 * 同じ道筋で動くので、触れた場所・倒れた場所・起き上がった場所がすべて見た目と一致し、
 * 儀式が終わって降りたときに跳ばない。
 * <ul>
 *   <li>導入(首をつかまれるまで): 右クリックした場所({@link #DATA_START})から、
 *   ノード中心の 3.5 ブロック手前(ファイル z=-56)まで忍び足で歩き、おそるおそる触れる。歩き始めがプレイヤーごとに違うので、アニメから焼いた「進み具合」で補間する。
 *   PAL 側の body の x/z は消してある(y は残す)。</li>
 *   <li>以降: アニメの body 骨の位置を焼いた {@link HiveRitualPath} をそのまま使う。</li>
 * </ul>
 * 時間の管理もここ({@link #tick}): 刺される瞬間の報酬、終わりで降ろす、降りたら中断。
 * 蔦とカメラは別のエンティティ({@link HiveRitualEntity}、広間に固定)。
 */
public class HiveRitualSeatEntity extends Entity {

    /** 蔦・カメラのエンティティ({@link HiveRitualEntity})の ID。 */
    private static final EntityDataAccessor<Integer> DATA_ANCHOR =
            SynchedEntityData.defineId(HiveRitualSeatEntity.class, EntityDataSerializers.INT);
    /** アニメの原点(ワールド座標)。 */
    private static final EntityDataAccessor<Vector3f> DATA_ORIGIN =
            SynchedEntityData.defineId(HiveRitualSeatEntity.class, EntityDataSerializers.VECTOR3);
    /** 右クリックしたときのプレイヤーの足元(導入の歩き始め)。 */
    private static final EntityDataAccessor<Vector3f> DATA_START =
            SynchedEntityData.defineId(HiveRitualSeatEntity.class, EntityDataSerializers.VECTOR3);
    /** 儀式の向き(プレイヤーがノードを見る yaw、度)。 */
    private static final EntityDataAccessor<Float> DATA_YAW =
            SynchedEntityData.defineId(HiveRitualSeatEntity.class, EntityDataSerializers.FLOAT);

    /** 立ち位置 = 原点の 1 ブロック後ろ(ファイル z=+16、PAL テンプレのプレイヤー位置)。 */
    private static final double STAND_Z = 16.0;

    private boolean rewarded;

    public HiveRitualSeatEntity(EntityType<? extends HiveRitualSeatEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
        setNoGravity(true);
    }

    public void setup(HiveRitualEntity anchor, Vec3 start) {
        this.entityData.set(DATA_ANCHOR, anchor.getId());
        this.entityData.set(DATA_ORIGIN, anchor.position().toVector3f());
        this.entityData.set(DATA_START, start.toVector3f());
        this.entityData.set(DATA_YAW, anchor.getYRot());
        setYRot(anchor.getYRot());
        this.yRotO = getYRot();
        Vec3 p = pathPos(0.0);
        setPos(p.x, p.y, p.z);
    }

    public int anchorId() {
        return this.entityData.get(DATA_ANCHOR);
    }

    public float ritualYaw() {
        return this.entityData.get(DATA_YAW);
    }

    /** 演出の経過(秒)。 */
    public float seconds(float partialTick) {
        return (this.tickCount + partialTick) / 20.0F;
    }

    /** 導入(歩み寄って触れる)の最中か。この間は視点をカメラ骨に固定しない(普通の三人称で背中を追う)。 */
    public boolean inIntro() {
        return this.tickCount < HiveRitualPath.get().introSeconds() * 20.0;
    }

    /**
     * ファイル座標(px、原点基準。+X = プレイヤーの左、+Z = 後ろ)→ ワールド座標。
     * GeckoLib と同じ写像(180-yaw の Y 回転 + x 反転)。
     */
    private Vec3 fileToWorld(double x, double z) {
        Vector3f o = this.entityData.get(DATA_ORIGIN);
        double th = Math.toRadians(180.0 - ritualYaw());
        double lx = -x / 16.0, lz = z / 16.0;
        double wx = lx * Math.cos(th) + lz * Math.sin(th);
        double wz = -lx * Math.sin(th) + lz * Math.cos(th);
        return new Vec3(o.x + wx, o.y, o.z + wz);
    }

    /**
     * 時刻 {@code t}(秒)の座席の位置。導入はアニメから焼いた「進み具合」で、歩き始めの場所(右クリックした所)→
     * 触れる位置 → 立ち位置(後ずさり)を補間する。以降はアニメの body の位置そのもの。
     */
    private Vec3 pathPos(double t) {
        HiveRitualPath path = HiveRitualPath.get();
        if (t < path.introSeconds()) {
            Vector3f s = this.entityData.get(DATA_START);
            Vec3 start = new Vec3(s.x, s.y, s.z);
            Vec3 touch = fileToWorld(0, STAND_Z - path.touchPx());
            return start.lerp(touch, path.progress(t));
        }
        return fileToWorld(path.x(t), STAND_Z + path.z(t));
    }

    @Override
    public void tick() {
        super.tick();
        Vec3 p = pathPos(this.tickCount / 20.0);
        setPos(p.x, p.y, p.z);
        setYRot(ritualYaw());
        this.yRotO = getYRot();
        if (level().isClientSide) {
            return;
        }
        Entity rider = getFirstPassenger();
        if (rider == null) {
            discard(); // 降りてしまった(Shift)か、居なくなった = 中断。
            return;
        }
        if (this.tickCount >= HiveRitualEntity.strikeTick() && !rewarded && rider instanceof ServerPlayer player) {
            rewarded = true;
            reward(player);
        }
        if (this.tickCount >= HiveRitualEntity.endTick()) {
            ejectPassengers();
            discard();
        }
    }

    /** 胸を刺された瞬間の報酬(仮)。装備が実装されたらここでアイテムを渡す/装着する。 */
    private void reward(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("message.overgrown_vegetation.hive_ritual.received"), false);
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                    player.getX(), player.getY() + 0.6D, player.getZ(), 80, 0.6D, 0.4D, 0.6D, 0.03D);
            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.HOSTILE, 2.0F, 0.6F);
        }
        // 寄生植物装備を装着する(胸の装備を置き換える。以後は外せない)。
        com.yayuy.overgrownvegetation.item.PlantArmor.grant(player);
    }

    /** 乗り手の足元 = 座席の位置そのもの(バニラの「座った姿勢ぶん 0.6 下げる」は使わない)。 */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity entity, EntityDimensions dimensions, float partialTick) {
        return Vec3.ZERO;
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction callback) {
        callback.accept(passenger, getX(), getY(), getZ());
        clampRotation(passenger);
    }

    @Override
    public void onPassengerTurned(Entity entityToUpdate) {
        clampRotation(entityToUpdate);
    }

    /** 乗り手の向きを儀式の向きに固定する(マウスで回せない)。頭も体も同じ向き、俯仰は 0。 */
    private void clampRotation(Entity passenger) {
        float yaw = getYRot();
        passenger.setYBodyRot(yaw);
        float d = Mth.wrapDegrees(passenger.getYRot() - yaw);
        passenger.yRotO -= d;
        passenger.setYRot(passenger.getYRot() - d);
        passenger.setYHeadRot(yaw);
        float p = passenger.getXRot();
        passenger.xRotO -= p;
        passenger.setXRot(0.0F);
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide) {
            discard();
        }
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty() && passenger instanceof Player;
    }

    /** 位置は両側で {@link #pathPos} から計算するので、サーバーの座標同期は使わない。 */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ANCHOR, -1);
        builder.define(DATA_ORIGIN, new Vector3f());
        builder.define(DATA_START, new Vector3f());
        builder.define(DATA_YAW, 0.0F);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
