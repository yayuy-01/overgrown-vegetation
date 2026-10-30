package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * アクムネス({@link AcumnesEntity})が撃つ棘(2026-09-28)。重力なしでまっすぐ飛び、最初に当たった相手に
 * <b>防具を貫通する</b>ダメージ({@link ModDamageTypes#acumnesSpine})を与えて消える。ブロックに当たっても消える。
 * 見た目は本体の針先の棘をそのまま写したモデル({@code geo/acumnes_spine.geo.json}、テクスチャは本体と同じ)+ 黄緑の尾。
 *
 * <p>味方(この MOD の Mob = グループのある種)には当たらない(ヴェロフィルの爆弾の {@code canHarm} と同じ考え)。保存しない。
 */
public class AcumnesSpine extends ThrowableProjectile implements GeoEntity {

    /** 飛んでいられる時間(tick)。3 ブロック/tick で 180 ブロック —— 撃つ距離(40)より十分長い。 */
    private static final int LIFETIME = 60;
    /** 尾の色(目と同じ黄緑 #BAFF00)。 */
    private static final DustParticleOptions TRAIL = new DustParticleOptions(new Vector3f(0.73F, 1.0F, 0.0F), 0.9F);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private float damage = 12.0F;

    public AcumnesSpine(EntityType<? extends AcumnesSpine> type, Level level) {
        super(type, level);
    }

    public AcumnesSpine(Level level, LivingEntity owner, Vec3 pos, Vec3 velocity, float damage) {
        super(ModEntities.ACUMNES_SPINE.get(), level);
        this.setOwner(owner);
        this.setPos(pos.x, pos.y, pos.z);
        this.setDeltaMovement(velocity);
        this.damage = damage;
        // 最初の tick から進む向きを向ける(描画は速度から向きを出すが、音や当たりの判定のため)
        double h = velocity.horizontalDistance();
        this.setYRot((float) (net.minecraft.util.Mth.atan2(velocity.x, velocity.z) * net.minecraft.util.Mth.RAD_TO_DEG));
        this.setXRot((float) (net.minecraft.util.Mth.atan2(velocity.y, h) * net.minecraft.util.Mth.RAD_TO_DEG));
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    /** まっすぐ飛ぶ(撃った瞬間に向いていた所へ飛ぶ。射線を切れば当たらない、がこの種の対処)。 */
    @Override
    protected double getDefaultGravity() {
        return 0.0D;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            Vec3 v = this.getDeltaMovement();
            for (int i = 0; i < 3; i++) {
                double f = i / 3.0D;
                this.level().addParticle(TRAIL, this.getX() - v.x * f, this.getY() - v.y * f, this.getZ() - v.z * f,
                        0.0D, 0.0D, 0.0D);
            }
        } else if (this.tickCount > LIFETIME) {
            this.discard();
        }
    }

    /** 撃った本人と、この MOD の Mob(グループのある種)には当たらない。観戦者・クリエイティブは素通り。 */
    @Override
    protected boolean canHitEntity(Entity target) {
        if (!super.canHitEntity(target)) {
            return false;
        }
        if (target == this.getOwner()) {
            return false;
        }
        if (target instanceof Player p) {
            return !p.isSpectator() && !p.isCreative();
        }
        return MobGroup.of(target.getType()) == null;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (this.level() instanceof ServerLevel server) {
            Entity target = result.getEntity();
            target.hurt(ModDamageTypes.acumnesSpine(server, this, this.getOwner()), this.damage);
            server.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.TRIDENT_HIT,
                    SoundSource.HOSTILE, 1.2F, 0.8F);
            this.discard();
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (this.level() instanceof ServerLevel server) {
            Vec3 p = result.getLocation();
            server.playSound(null, p.x, p.y, p.z, SoundEvents.TRIDENT_HIT_GROUND, SoundSource.HOSTILE, 1.0F, 0.8F);
            server.sendParticles(TRAIL, p.x, p.y, p.z, 8, 0.15D, 0.15D, 0.15D, 0.0D);
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

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
