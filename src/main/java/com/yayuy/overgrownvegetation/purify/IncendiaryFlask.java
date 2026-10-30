package com.yayuy.overgrownvegetation.purify;

import com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;

/**
 * 焼夷瓶(2026-10-16、⑧)。小さな火は吸われて消えるので、一度に吸いきれない熱を浴びせる瓶。燃料は石炭(植物の祖先の亡骸)。
 * 当たった所から半径 {@link #RADIUS} のマスに、火炎放射器と同じふつうの火({@link Burning#ignite})を置き、中の生き物を燃やす
 * (投げた本人と、火炎放射器が燃やさない相手は除く)。ハイヴノードの近くでは火が広がらず残らない(火の決まりのまま = 「範囲の外で効く」)。
 * 仮決め: 半径 2 / 生き物は 5 秒燃えて 2 のダメージ(火炎放射器と同じ)/ 空き瓶 + 石炭 + 繊維質で作る / 実験的要素の切り替えには入れない。
 */
public class IncendiaryFlask extends ThrowableItemProjectile {

    public static final int RADIUS = 2;

    public IncendiaryFlask(EntityType<? extends IncendiaryFlask> type, Level level) {
        super(type, level);
    }

    public IncendiaryFlask(Level level, LivingEntity thrower) {
        super(ModEntities.INCENDIARY_FLASK.get(), thrower, level);
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.INCENDIARY_FLASK.get();
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level() instanceof ServerLevel server) {
            burst(server, BlockPos.containing(result.getLocation()), getOwner() instanceof LivingEntity owner ? owner : null);
            discard();
        }
    }

    /** {@code center} のまわりに火を付け、生き物を燃やす。付けた火の数を返す。 */
    public static int burst(ServerLevel level, BlockPos center, LivingEntity thrower) {
        int[] lit = new int[1];
        CorruptionDropSuppressor.run(() -> {
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS, -1, -RADIUS), center.offset(RADIUS, 1, RADIUS))) {
                if (pos.distSqr(center) <= (RADIUS + 0.5D) * (RADIUS + 0.5D) && Burning.ignite(level, pos.immutable())) {
                    lit[0]++;
                }
            }
        });
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, new AABB(center).inflate(RADIUS + 0.5D))) {
            if (target == thrower || (thrower != null && !Burning.canBurn(thrower, target))) {
                continue;
            }
            target.igniteForSeconds(5.0F);
            target.hurt(level.damageSources().inFire(), 2.0F);
        }
        level.sendParticles(ParticleTypes.FLAME, center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D, 40, 1.2D, 0.4D, 1.2D, 0.05D);
        level.playSound(null, center, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 1.0F);
        level.playSound(null, center, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.0F, 0.8F);
        return lit[0];
    }
}
