package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * パチンコの弾(2026-10-16、⑯ ⑰)。弾の品で効き目が変わる(仮決めの数値):
 * 小石 = 引いた強さ × 4 のダメージ / 炸裂弾 = 当たった所で小さく爆ぜる(ブロックは壊さない)/ 粘り弾 = 3 秒ほど足が鈍る。
 */
public class SlingshotShot extends ThrowableItemProjectile {

    private float power = 1.0F;

    public SlingshotShot(EntityType<? extends SlingshotShot> type, Level level) {
        super(type, level);
    }

    public SlingshotShot(Level level, LivingEntity shooter) {
        super(ModEntities.SLINGSHOT_SHOT.get(), shooter, level);
    }

    public void setPower(float power) {
        this.power = Math.max(0.1F, Math.min(1.0F, power));
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.PEBBLE.get();
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (!(level() instanceof ServerLevel)) {
            return;
        }
        ItemStack ammo = getItem();
        float damage = ammo.is(ModItems.PEBBLE.get()) ? 4.0F * power : 1.0F;
        result.getEntity().hurt(damageSources().thrown(this, getOwner()), damage);
        if (ammo.is(ModItems.STICKY_SHOT.get()) && result.getEntity() instanceof LivingEntity living) {
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 3), this);
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level() instanceof ServerLevel server) {
            ItemStack ammo = getItem();
            if (ammo.is(ModItems.BURST_SHOT.get())) {
                server.explode(this, getX(), getY(), getZ(), 1.2F, Level.ExplosionInteraction.NONE);
            } else {
                server.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, ammo), getX(), getY(), getZ(), 6, 0.1D, 0.1D, 0.1D, 0.05D);
            }
            discard();
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("Power", power);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        power = tag.contains("Power") ? tag.getFloat("Power") : 1.0F;
    }
}
