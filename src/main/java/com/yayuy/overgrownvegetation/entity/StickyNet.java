package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;

/**
 * 粘りの投網(2026-10-16、⑳)。投げて当たった所の周り {@link #RADIUS} の生き物を数秒絡め取る(仮決め: 4 秒ほぼ動けない)。投げた本人は絡まない。
 */
public class StickyNet extends ThrowableItemProjectile {

    public static final double RADIUS = 2.0D;
    public static final int ROOT_TICKS = 80;

    public StickyNet(EntityType<? extends StickyNet> type, Level level) {
        super(type, level);
    }

    public StickyNet(Level level, LivingEntity thrower) {
        super(ModEntities.STICKY_NET.get(), thrower, level);
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.STICKY_NET.get();
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level() instanceof ServerLevel server) {
            entangle(server, this, result.getLocation().x, result.getLocation().y, result.getLocation().z);
            discard();
        }
    }

    /** {@code (x, y, z)} の周りの生き物を絡め取る(投げた本人を除く)。絡めた数を返す。 */
    public static int entangle(ServerLevel level, ThrowableItemProjectile net, double x, double y, double z) {
        int n = 0;
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, new AABB(x, y, z, x, y, z).inflate(RADIUS))) {
            if (living == net.getOwner()) {
                continue;
            }
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ROOT_TICKS, 6), net);
            living.addEffect(new MobEffectInstance(MobEffects.JUMP, ROOT_TICKS, 128), net); // 跳べないように(大きすぎる値は跳べない)
            n++;
        }
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, net.getItem()), x, y, z, 20, 0.6D, 0.3D, 0.6D, 0.05D);
        return n;
    }
}
