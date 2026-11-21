package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.registry.ModLizards;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
 * ピリムネス({@link PilimnesEntity})が葉枕(水圧の関節)で弾き飛ばす棘(2026-11-16 能力の段)。
 * <ul>
 *   <li>{@link #FLICK} 棘の弾き: 重力なしでまっすぐ(撃った瞬間に足元を狙う。先読みしない)。盾で受けなければ足が鈍る(鈍足)。</li>
 *   <li>{@link #FAN} 扇の散らし撃ち: 重力なしでまっすぐ。傷だけ。</li>
 *   <li>{@link #RAIN} 棘の雨: 空へ放たれ、約 1.5 秒({@link PilimnesHuntGoal#RAIN_FALL_TICKS})後に決めた所へ落ちてくる弧。落ちる所の印は出さない(決定 11-15)。</li>
 * </ul>
 * 傷はバニラの mobProjectile(防具・盾が効く。撃った本人が攻撃した者になるので、感染の仕組みはほかの G3 と同じに働く)。植物側・撃った本人・
 * 創造/観戦のプレイヤーには当たらない。ブロックに当たると消える(物陰で受けられる)。保存しない。見た目は【仮】でバニラの矢の絵 + 黄緑の尾。
 */
public class PilimnesSpine extends ThrowableProjectile {

    public static final int FLICK = 0, FAN = 1, RAIN = 2;
    /** 飛んでいられる時間(tick)。 */
    private static final int LIFETIME = 80;
    /** 棘の雨の重力(ブロック/tick²)【仮】。 */
    public static final double RAIN_GRAVITY = 0.06D;
    private static final DustParticleOptions TRAIL = new DustParticleOptions(new Vector3f(0.73F, 1.0F, 0.0F), 0.8F);
    private static final EntityDataAccessor<Byte> DATA_KIND = SynchedEntityData.defineId(PilimnesSpine.class, EntityDataSerializers.BYTE);

    private float damage = 4.0F;
    private int slowTicks, slowLevel;

    public PilimnesSpine(EntityType<? extends PilimnesSpine> type, Level level) {
        super(type, level);
    }

    public PilimnesSpine(Level level, LivingEntity owner, int kind, Vec3 pos, Vec3 velocity, float damage) {
        super(ModLizards.PILIMNES_SPINE.get(), level);
        this.setOwner(owner);
        this.entityData.set(DATA_KIND, (byte) kind);
        this.setPos(pos.x, pos.y, pos.z);
        this.setDeltaMovement(velocity);
        this.damage = damage;
        faceVelocity();
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    /** 当たったら足が鈍る(鈍足の長さ・強さ)。棘の弾きだけ。 */
    public PilimnesSpine slow(int ticks, int level) {
        this.slowTicks = ticks;
        this.slowLevel = level;
        return this;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_KIND, (byte) FLICK);
    }

    public int kind() {
        return this.entityData.get(DATA_KIND);
    }

    @Override
    protected double getDefaultGravity() {
        return kind() == RAIN ? RAIN_GRAVITY : 0.0D;
    }

    /**
     * 棘の雨: {@code from} から {@code ticks} tick 後に {@code to} へ落ちる初速(重力 {@link #RAIN_GRAVITY} と空気の抵抗 0.99/tick を
     * 1 tick ずつなぞって、位置が初速に比例することを使って解く)。
     */
    public static Vec3 rainVelocity(Vec3 from, Vec3 to, int ticks) {
        // 初速 0 で落としたときの位置と、初速 1 のときの増え方(縦横とも初速に比例)
        // バニラの ThrowableProjectile#tick の順: 位置 += 速さ → 速さ = 速さ × 0.99 − 重力
        double sum = 0.0D, gravityDrop = 0.0D, v = 1.0D, vg = 0.0D;
        for (int k = 0; k < ticks; k++) {
            sum += v;
            gravityDrop += vg;
            v *= 0.99D;
            vg = vg * 0.99D - RAIN_GRAVITY;
        }
        Vec3 d = to.subtract(from);
        return new Vec3(d.x / sum, (d.y - gravityDrop) / sum, d.z / sum);
    }

    private void faceVelocity() {
        Vec3 v = this.getDeltaMovement();
        double h = v.horizontalDistance();
        if (v.lengthSqr() < 1.0E-8D) return;
        this.setYRot((float) (Mth.atan2(v.x, v.z) * Mth.RAD_TO_DEG));
        this.setXRot((float) (Mth.atan2(v.y, h) * Mth.RAD_TO_DEG));
    }

    @Override
    public void tick() {
        super.tick();   // 向きはバニラの updateRotation が速さに合わせる(雨の弧の上り → 下りも)
        if (this.level().isClientSide()) {
            Vec3 v = this.getDeltaMovement();
            for (int i = 0; i < 2; i++) {
                double f = i / 2.0D;
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
        if (target instanceof net.neoforged.neoforge.entity.PartEntity<?> part) {
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
        DamageSource src = server.damageSources().mobProjectile(this, this.getOwner() instanceof LivingEntity o ? o : null);
        boolean blocked = target instanceof LivingEntity le && le.isDamageSourceBlocked(src);
        // 扇・雨の棘が続けて当たっても無敵時間で消えない。消すのは前の棘の無敵時間だけ(直前の傷がほかの攻撃なら消さない。11-16 見直しの疑い)
        if (kind() != FLICK && target instanceof LivingEntity le && le.getLastDamageSource() != null && le.getLastDamageSource().getDirectEntity() instanceof PilimnesSpine) le.invulnerableTime = 0;
        boolean hurt = target.hurt(src, this.damage);
        if (this.slowTicks > 0 && hurt && !blocked && target instanceof LivingEntity le && le.isAlive()) {
            le.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, this.slowTicks, this.slowLevel), this.getOwner());
        }
        server.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.TRIDENT_HIT, SoundSource.HOSTILE, 0.9F, 1.2F);
        this.discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (this.level() instanceof ServerLevel server) {
            Vec3 p = result.getLocation();
            server.playSound(null, p.x, p.y, p.z, SoundEvents.TRIDENT_HIT_GROUND, SoundSource.HOSTILE, 0.8F, 1.1F);
            server.sendParticles(TRAIL, p.x, p.y, p.z, 5, 0.15D, 0.15D, 0.15D, 0.0D);
            this.discard();
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 96.0D * 96.0D;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    public float damage() {
        return this.damage;
    }
}
