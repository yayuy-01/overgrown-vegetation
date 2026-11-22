package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * ファルキムネスの<b>飛ぶ斬撃</b>(通常技・遠、2026-11-05)。胸の高さを水平に飛ぶ三日月(横幅 {@code width}・厚み {@code height})。
 * 数値は {@code actions.json} の {@code falcimnes/wave}(仮: 10 ダメージ・毎秒 20 ブロック・16 ブロックで消える・横 3 × 厚み 0.5)。
 * <ul>
 *     <li>最初に当たった 1 体で消える。ブロックに当たっても消える(壁の陰に隠れる手が効く)。</li>
 *     <li>盾で防げる(ダメージの直接の出どころ = この斬撃の位置。正面から受ければ盾が効く)。防具も効く。</li>
 *     <li>重力・空気抵抗なし。保存しない(読み込み直したら消える。1 秒足らずの飛び道具なので)。</li>
 * </ul>
 * 見た目は {@code client/FalcimnesWaveRenderer}(自分で描く光る三日月。粒ではない —— 線と面がぼやけると「どこが危ないか」が読みにくい)。
 */
public class FalcimnesWaveEntity extends Entity {

    private static final EntityDataAccessor<Float> DATA_WIDTH =
            SynchedEntityData.defineId(FalcimnesWaveEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_LIFE =
            SynchedEntityData.defineId(FalcimnesWaveEntity.class, EntityDataSerializers.INT);

    @Nullable
    private UUID ownerId;
    @Nullable
    private Entity cachedOwner;
    private float damage = 10.0F;
    private float height = 0.5F;

    public FalcimnesWaveEntity(EntityType<? extends FalcimnesWaveEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * サーバーで出す。{@code dir} は水平の単位ベクトル、{@code speed} はブロック/tick、{@code range} はブロック。
     */
    public static FalcimnesWaveEntity shoot(ServerLevel level, LivingEntity owner, Vec3 pos, Vec3 dir, double speed, double range,
                                            float damage, float width, float height) {
        FalcimnesWaveEntity w = new FalcimnesWaveEntity(ModEntities.FALCIMNES_WAVE.get(), level);
        w.ownerId = owner.getUUID();
        w.cachedOwner = owner;
        w.damage = damage;
        w.height = height;
        w.entityData.set(DATA_WIDTH, width);
        w.entityData.set(DATA_LIFE, Math.max(1, (int) Math.ceil(range / Math.max(0.05D, speed))));
        w.moveTo(pos.x, pos.y, pos.z, (float) (Mth.atan2(dir.z, dir.x) * Mth.RAD_TO_DEG) - 90.0F, 0.0F);
        w.setDeltaMovement(dir.scale(speed));
        level.addFreshEntity(w);
        return w;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_WIDTH, 3.0F);
        builder.define(DATA_LIFE, 16);
    }

    /** 三日月の横幅(ブロック)。 */
    public float width() {
        return this.entityData.get(DATA_WIDTH);
    }

    /** 飛ぶ tick の数(これで消える)。 */
    public int life() {
        return this.entityData.get(DATA_LIFE);
    }

    @Nullable
    public Entity getOwner() {
        if (this.cachedOwner != null && !this.cachedOwner.isRemoved()) {
            return this.cachedOwner;
        }
        if (this.ownerId != null && this.level() instanceof ServerLevel server) {
            this.cachedOwner = server.getEntity(this.ownerId);
        }
        return this.cachedOwner;
    }

    @Override
    public void tick() {
        super.tick();
        Vec3 from = this.position();
        Vec3 v = this.getDeltaMovement();
        Vec3 to = from.add(v);
        if (!this.level().isClientSide()) {
            if (this.tickCount > life()) {
                this.discard();
                return;
            }
            // ブロック: 真ん中の線で見る(三日月の端が角をかすめるだけでは消えない)。
            HitResult block = this.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            double reach = block.getType() == HitResult.Type.MISS ? v.length() : block.getLocation().distanceTo(from);
            LivingEntity hit = firstHit(from, v, reach);
            if (hit != null) {
                Entity owner = getOwner();
                hit.hurt(ModDamageTypes.falcimnesWave((ServerLevel) this.level(), this, owner), this.damage);
                this.discard();
                return;
            }
            if (block.getType() != HitResult.Type.MISS) {
                ((ServerLevel) this.level()).sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT,
                        block.getLocation().x, block.getLocation().y, block.getLocation().z, 6, 0.3D, 0.2D, 0.3D, 0.1D);
                this.discard();
                return;
            }
        }
        this.setPos(to.x, to.y, to.z);
    }

    /** この tick に三日月が通る帯(進む向きに {@code reach}、横に幅、縦に厚み)に、最初に入る相手(近い順)。 */
    @Nullable
    private LivingEntity firstHit(Vec3 from, Vec3 v, double reach) {
        double len = v.length();
        if (len < 1.0E-6D) return null;
        Vec3 dir = v.scale(1.0D / len);
        Vec3 side = new Vec3(-dir.z, 0.0D, dir.x);
        double half = width() * 0.5D;
        AABB box = new AABB(from, from.add(dir.scale(reach))).inflate(half + 1.0D, this.height + 1.0D, half + 1.0D);
        Entity owner = getOwner();
        LivingEntity best = null;
        double bestAlong = Double.MAX_VALUE;
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != owner)) {
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            if (owner instanceof FalcimnesEntity f ? !f.isFoeForWave(e) : !PlantFoes.isFoe(null, e)) continue;   // 持ち主が消えても植物側には当てない(①9)
            Vec3 c = e.getBoundingBox().getCenter().subtract(from);
            double er = e.getBbWidth() * 0.5D;
            double along = c.dot(dir);
            if (along < -er - 0.25D || along > reach + er + 0.25D) continue;
            if (Math.abs(c.dot(side)) > half + er) continue;
            // 縦: 三日月の厚み(中心 ± height/2)と相手の箱が重なるか。
            double y0 = this.getY() - this.height * 0.5D, y1 = this.getY() + this.height * 0.5D;
            if (e.getBoundingBox().maxY < y0 || e.getBoundingBox().minY > y1) continue;
            if (along < bestAlong) {
                bestAlong = along;
                best = e;
            }
        }
        return best;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 96.0D * 96.0D;
    }
}
