package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.block.SiegeCannonBlockEntity;
import com.yayuy.overgrownvegetation.network.CannonControlPayload;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.siege.SiegeCannonGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 砲台({@link SiegeCannonBlockEntity})の<b>座席</b>。プレイヤーが砲台に乗ると操作盤
 * ({@link SiegeCannonGeometry#seat})に湧き、降りる・砲台が壊れる・乗り手が居なくなると消える。
 *
 * <p>台座ごと旋回するモデルなので、座席の位置も方位に合わせて動く。その位置は
 * {@link SiegeCannonGeometry#seat} の式からサーバー・クライアントの<b>両方で毎 tick 計算</b>する
 * ({@link #lerpTo} を無効にして、サーバーからの座標同期より自前の計算を優先させる)。
 * 乗っているクライアントでは砲の方位が自分の視線から出るので、旋回に一切の遅延がない。
 *
 * <p>このエンティティ自体は見えない({@code NoopRenderer})・当たらない・保存しない。
 */
public class SiegeCannonSeatEntity extends Entity {

    private static final EntityDataAccessor<BlockPos> DATA_CANNON =
            SynchedEntityData.defineId(SiegeCannonSeatEntity.class, EntityDataSerializers.BLOCK_POS);

    public SiegeCannonSeatEntity(EntityType<? extends SiegeCannonSeatEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
        setNoGravity(true);
    }

    /**
     * {@code player} を {@code pos} の砲台に乗せる。既に誰か乗っていれば false。
     * 乗る瞬間にプレイヤーの視線を砲の現在の向きへ合わせる(乗った途端に砲身が跳ねないように)。
     */
    public static boolean mount(ServerLevel level, BlockPos pos, SiegeCannonBlockEntity cannon, Player player) {
        if (cannon.isManned()) {
            return false;
        }
        double azimuth = cannon.aimAzimuth(1.0F);
        double elevation = cannon.aimElevation(1.0F);
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.teleport(player.getX(), player.getY(), player.getZ(),
                    SiegeCannonGeometry.yawFromAzimuth(azimuth), SiegeCannonGeometry.pitchFromElevation(elevation));
        } else {
            player.setYRot(SiegeCannonGeometry.yawFromAzimuth(azimuth));
            player.setXRot(SiegeCannonGeometry.pitchFromElevation(elevation));
        }

        SiegeCannonSeatEntity seat = new SiegeCannonSeatEntity(ModEntities.SIEGE_CANNON_SEAT.get(), level);
        seat.setCannonPos(pos);
        Vec3 at = SiegeCannonGeometry.seat(pos, azimuth);
        seat.setPos(at.x, at.y, at.z);
        level.addFreshEntity(seat);
        // force = true: バニラの降車クールダウン(60 tick)を無視する。装填のために降りて
        // すぐ乗り直せるようにするため。
        if (!player.startRiding(seat, true)) {
            seat.discard();
            return false;
        }
        cannon.setRider(player);
        return true;
    }

    /**
     * 騎乗中の操作({@link CannonControlPayload})。対象の砲台は「そのプレイヤーが乗っている座席」から
     * 引くので、座標を信用する必要がない。
     */
    public static void control(ServerPlayer player, int action, int value) {
        if (!(player.getVehicle() instanceof SiegeCannonSeatEntity seat) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        SiegeCannonBlockEntity cannon = seat.cannon();
        if (cannon == null) {
            return;
        }
        switch (action) {
            case CannonControlPayload.ACTION_FIRE -> cannon.tryFire(level, player);
            case CannonControlPayload.ACTION_CHARGE -> cannon.adjustCharge(Mth.clamp(value, -1, 1));
            default -> {
            }
        }
    }

    public BlockPos cannonPos() {
        return entityData.get(DATA_CANNON);
    }

    public void setCannonPos(BlockPos pos) {
        entityData.set(DATA_CANNON, pos.immutable());
    }

    /** この座席が属する砲台(消えていれば null)。 */
    @Nullable
    public SiegeCannonBlockEntity cannon() {
        return level().getBlockEntity(cannonPos()) instanceof SiegeCannonBlockEntity cannon ? cannon : null;
    }

    @Override
    public void tick() {
        super.tick();
        SiegeCannonBlockEntity cannon = cannon();
        if (cannon == null) {
            if (!level().isClientSide) {
                discard();
            }
            return;
        }
        Entity gunner = getFirstPassenger();
        if (gunner == null) {
            if (!level().isClientSide) {
                discard();
            }
            return;
        }
        cannon.setRider(gunner);
        Vec3 at = SiegeCannonGeometry.seat(cannonPos(), cannon.aimAzimuth(1.0F));
        setPos(at.x, at.y, at.z);
    }

    @Override
    public void remove(RemovalReason reason) {
        SiegeCannonBlockEntity cannon = cannon();
        if (cannon != null) {
            cannon.clearRider();
        }
        super.remove(reason);
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide) {
            discard();
        }
    }

    /** 乗り手の足元がそのまま座席の位置。 */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity entity, EntityDimensions dimensions, float partialTick) {
        return Vec3.ZERO;
    }

    /** 位置は両側で {@link SiegeCannonGeometry#seat} から計算するので、サーバーの座標同期は使わない。 */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty();
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
        builder.define(DATA_CANNON, BlockPos.ZERO);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("CannonX")) {
            setCannonPos(new BlockPos(tag.getInt("CannonX"), tag.getInt("CannonY"), tag.getInt("CannonZ")));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        BlockPos pos = cannonPos();
        tag.putInt("CannonX", pos.getX());
        tag.putInt("CannonY", pos.getY());
        tag.putInt("CannonZ", pos.getZ());
    }
}
