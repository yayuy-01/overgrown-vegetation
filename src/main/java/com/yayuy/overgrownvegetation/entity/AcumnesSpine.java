package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModFlyers;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * アクムネス({@link AcumnesEntity})が撃つ棘(2026-11-15 能力の段。旧版 parked の AcumnesSpine を元に書き直した)。
 * 重力なしでまっすぐ飛び(撃った瞬間に狙った所へ。先読みはしない = 射線を切れば当たらない)、最初に当たった相手で消える。ブロックに当たっても消える。
 * <ul>
 *   <li>{@link #SNIPE} 狙撃: 防具を貫く({@link ModDamageTypes#ACUMNES_SPINE})・盾は効く。</li>
 *   <li>{@link #PIN} 縫い止め: 防具を貫く。盾で受けなければ {@link ModMobEffects#ACUMNES_PINNED} で動けなくする。</li>
 *   <li>{@link #SCATTER} 散弾の小さな棘: バニラの mobProjectile(防具が効く)。</li>
 * </ul>
 * 植物側({@link AcumnesEntity#isPrey} の外)には当たらない。保存しない。見た目は【仮】でバニラの矢の絵 + 黄緑の尾。
 */
public class AcumnesSpine extends ThrowableProjectile {

    public static final int SNIPE = 0, PIN = 1, SCATTER = 2;
    /** 飛んでいられる時間(tick)。 */
    private static final int LIFETIME = 60;   // 4 ブロック/tick で 240 ブロック(射程 60 の 4 倍)
    /** 尾の色(黄緑)。 */
    private static final DustParticleOptions TRAIL = new DustParticleOptions(new Vector3f(0.73F, 1.0F, 0.0F), 0.9F);
    private static final EntityDataAccessor<Byte> DATA_KIND = SynchedEntityData.defineId(AcumnesSpine.class, EntityDataSerializers.BYTE);

    private float damage = 12.0F;
    private int pinTicks;

    public AcumnesSpine(EntityType<? extends AcumnesSpine> type, Level level) {
        super(type, level);
    }

    public AcumnesSpine(Level level, LivingEntity owner, int kind, Vec3 pos, Vec3 velocity, float damage, int pinTicks) {
        super(ModFlyers.ACUMNES_SPINE.get(), level);
        this.setOwner(owner);
        this.entityData.set(DATA_KIND, (byte) kind);
        this.setPos(pos.x, pos.y, pos.z);
        this.setDeltaMovement(velocity);
        this.damage = damage;
        this.pinTicks = pinTicks;
        double h = velocity.horizontalDistance();
        this.setYRot((float) (Mth.atan2(velocity.x, velocity.z) * Mth.RAD_TO_DEG));
        this.setXRot((float) (Mth.atan2(velocity.y, h) * Mth.RAD_TO_DEG));
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_KIND, (byte) SNIPE);
    }

    public int kind() {
        return this.entityData.get(DATA_KIND);
    }

    @Override
    protected double getDefaultGravity() {
        return 0.0D;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            Vec3 v = this.getDeltaMovement();
            int n = kind() == SCATTER ? 1 : 3;
            for (int i = 0; i < n; i++) {
                double f = i / (double) n;
                this.level().addParticle(TRAIL, this.getX() - v.x * f, this.getY() - v.y * f, this.getZ() - v.z * f, 0.0D, 0.0D, 0.0D);
            }
        } else if (this.tickCount > LIFETIME) {
            this.discard();
        }
    }

    /** 撃った本人・植物側・創造/観戦のプレイヤーには当たらない。 */
    @Override
    protected boolean canHitEntity(Entity target) {
        if (!super.canHitEntity(target) || target == this.getOwner()) return false;
        if (target instanceof Player p && (p.isSpectator() || p.isCreative())) return false;
        // 叩ける部位(翅など)は持ち主で見る: 自分の部位・植物側の部位には当たらない
        if (target instanceof net.minecraftforge.entity.PartEntity<?> part) {
            Entity parent = part.getParent();
            return parent != this.getOwner() && !(parent instanceof LivingEntity pl && !AcumnesEntity.isPrey(null, pl));
        }
        if (target instanceof LivingEntity le) return AcumnesEntity.isPrey(this.getOwner() instanceof LivingEntity o ? o : null, le);
        return false;   // 生き物でない物(額縁・絵・ボートなど)は壊さず素通り(11-16 見直しの疑い)
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (!(this.level() instanceof ServerLevel server)) return;
        Entity target = result.getEntity();
        int k = kind();
        DamageSource src = k == SCATTER ? server.damageSources().mobProjectile(this, this.getOwner() instanceof LivingEntity o ? o : null)
                : ModDamageTypes.acumnesSpine(server, this, this.getOwner());
        boolean blocked = target instanceof LivingEntity le && le.isDamageSourceBlocked(src);
        // 散弾は 1 tick おきに当たるので、無敵時間で 2 発目以降が消えないように(3 × 5 発。決定 11-15)
        // 前の弾の無敵時間だけ消す(直前の傷がほかの攻撃なら消さない = 剣や溶岩の無敵時間を散弾が消して重ねさせない。11-16 見直しの疑い)
        if (k == SCATTER && target instanceof LivingEntity le && le.getLastDamageSource() != null && le.getLastDamageSource().getDirectEntity() instanceof AcumnesSpine) le.invulnerableTime = 0;
        target.hurt(src, this.damage);
        if (k == PIN && !blocked && target instanceof LivingEntity le && le.isAlive()) {
            // 地面に縫い止める: 動けず跳べない(属性を 0 に)。今の勢いも止める
            le.addEffect(new MobEffectInstance(ModMobEffects.ACUMNES_PINNED, this.pinTicks, 0, false, false, false), this.getOwner());
            le.setDeltaMovement(0.0D, Math.min(0.0D, le.getDeltaMovement().y), 0.0D);
            le.hurtMarked = true;
            server.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.TRIDENT_HIT_GROUND, SoundSource.HOSTILE, 1.4F, 0.5F);
            server.sendParticles(TRAIL, le.getX(), le.getY() + 0.1D, le.getZ(), 16, 0.3D, 0.05D, 0.3D, 0.0D);
        }
        server.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.TRIDENT_HIT, SoundSource.HOSTILE,
                k == SCATTER ? 0.6F : 1.2F, k == SCATTER ? 1.4F : 0.8F);
        this.discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (this.level() instanceof ServerLevel server) {
            Vec3 p = result.getLocation();
            server.playSound(null, p.x, p.y, p.z, SoundEvents.TRIDENT_HIT_GROUND, SoundSource.HOSTILE, kind() == SCATTER ? 0.5F : 1.0F, 0.8F);
            server.sendParticles(TRAIL, p.x, p.y, p.z, kind() == SCATTER ? 3 : 8, 0.15D, 0.15D, 0.15D, 0.0D);
            this.discard();
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 160.0D * 160.0D;   // 射程 60 の弾を遠くから見ても途中で消えない(追う距離は clientTrackingRange 8 区画 = 128)
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("Damage", this.damage);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.damage = tag.getFloat("Damage");
    }

    public float damage() {
        return this.damage;
    }
}
