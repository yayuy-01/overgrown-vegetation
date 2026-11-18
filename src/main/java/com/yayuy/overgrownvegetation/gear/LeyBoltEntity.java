package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * <b>地脈の杖の光の弾</b>(2026-11-18)。まっすぐ飛び(重力なし)、何かに当たるか {@code lifeTicks} で爆ぜる。
 * 爆ぜると半径 {@code radius} の相手(撃った本人と仲間を除く)に、中心ほど強いダメージ(端で半分)と外への吹き飛ばし。ブロックは壊さない。
 * 絵は【仮】で光る棘の先のアイテム。
 */
public class LeyBoltEntity extends ThrowableItemProjectile {

    private static final DustParticleOptions GLOW = new DustParticleOptions(new Vector3f(0.75F, 1.0F, 0.35F), 1.2F);

    public LeyBoltEntity(EntityType<? extends LeyBoltEntity> type, Level level) {
        super(type, level);
    }

    public LeyBoltEntity(Level level, LivingEntity owner) {
        super(ModGear.LEY_BOLT.get(), owner, level);
    }

    @Override
    protected Item getDefaultItem() {
        return ModMobDrops.GLOWING_SPINE_TIP.get();
    }

    @Override
    protected double getDefaultGravity() {
        return 0.0D;
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel server) {
            server.sendParticles(GLOW, getX(), getY(), getZ(), 2, 0.05D, 0.05D, 0.05D, 0.0D);
            if (tickCount > DesignData.gearTiers().leyStaff.lifeTicks) burst(server, position());
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level() instanceof ServerLevel server) burst(server, result.getLocation());
    }

    /** 爆ぜる(GameTest からも)。当てた数を返す。 */
    public int burst(ServerLevel level, Vec3 at) {
        if (isRemoved()) return 0;
        GearTierTable.LeyStaff s = DesignData.gearTiers().leyStaff;
        Entity owner = getOwner();
        int n = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(s.radius),
                e -> e.isAlive() && e != owner && !(owner != null && owner.isAlliedTo(e)) && !(e instanceof Player p && p.isSpectator()))) {
            double d = e.position().add(0, e.getBbHeight() * 0.5D, 0).distanceTo(at);
            if (d > s.radius + e.getBbWidth() * 0.5D) continue;
            double near = 1.0D - 0.5D * Math.min(1.0D, d / s.radius);
            if (e.hurt(level.damageSources().indirectMagic(this, owner), (float) (s.damage * near))) n++;
            Vec3 push = e.position().subtract(at).multiply(1, 0, 1);
            push = push.lengthSqr() < 1.0E-4D ? new Vec3(0, 0, 0) : push.normalize().scale(s.knockback * near);
            e.push(push.x, 0.35D * near, push.z);
            e.hurtMarked = true;
        }
        level.sendParticles(GLOW, at.x, at.y, at.z, 30, s.radius * 0.4D, s.radius * 0.4D, s.radius * 0.4D, 0.0D);
        level.sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 1.2F, 0.6F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.4F, 1.6F);
        discard();
        return n;
    }
}
