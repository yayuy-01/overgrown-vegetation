package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * <b>胞子弾</b>(焼いた胞子を付けた矢)の効き目(2026-11-17 決定 17。効き目の中身は【仮】)。
 * <ul>
 *   <li>当たった相手は燃える({@code igniteSeconds})。当たった所で燻り、まわり {@code smolderRadius} の<b>植物側</b>の Mob が燃える({@code smolderSeconds})。</li>
 *   <li>胞子弾の弓({@link SporeBowItem})で撃つと、燃える時間・燻る半径が伸び、植物側へのダメージが {@code bowPlantDamageMultiplier} 倍になる。</li>
 *   <li>燻りで火のブロックは置かない(森ごと燃えて止まらなくなるので。押し返す道具は別に作る、決定 16)。</li>
 * </ul>
 * 矢の印は持続データ({@code getPersistentData})に置く(バニラの矢をそのまま使い、描画を増やさない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeShots {

    static final String TAG_SPORE = "ov_spore_shot";
    static final String TAG_BOW = "ov_spore_bow";
    static final String TAG_DONE = "ov_spore_done";

    private SporeShots() {
    }

    public static void markSpore(Entity arrow) {
        arrow.getPersistentData().putBoolean(TAG_SPORE, true);
    }

    public static void markBow(Entity arrow) {
        arrow.getPersistentData().putBoolean(TAG_BOW, true);
    }

    public static boolean isSpore(Entity arrow) {
        return arrow.getPersistentData().getBoolean(TAG_SPORE);
    }

    public static boolean fromBow(Entity arrow) {
        return arrow.getPersistentData().getBoolean(TAG_BOW);
    }

    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent event) {
        Projectile p = event.getProjectile();
        if (!(p instanceof AbstractArrow arrow) || !(p.level() instanceof ServerLevel level) || !isSpore(arrow)) return;
        HitResult hit = event.getRayTraceResult();
        if (hit.getType() == HitResult.Type.MISS) return;
        Entity target = hit instanceof EntityHitResult eh ? eh.getEntity() : null;
        impact(level, arrow, hit.getLocation(), target);
    }

    /**
     * 当たったときの燃え・燻り(GameTest からも)。矢 1 本につき燻りは 1 回(跳ね返って 2 度当たっても)。燃やした植物側の数を返す(当たった相手を除く)。
     */
    public static int impact(ServerLevel level, Entity arrow, Vec3 at, Entity target) {
        GearTierTable.SporeShot s = DesignData.gearTiers().sporeShot;
        boolean bow = fromBow(arrow);
        // 部位の当たり判定(ファルキムネスなどの HitPart)に当たったら本体を燃やす
        if (target instanceof net.neoforged.neoforge.entity.PartEntity<?> part) target = part.getParent();
        final Entity hitTarget = target;
        Entity owner = arrow instanceof Projectile pr ? pr.getOwner() : null;
        if (target instanceof LivingEntity living && target != owner) {
            living.igniteForSeconds(bow ? s.bowIgniteSeconds : s.igniteSeconds);
        }
        if (arrow.getPersistentData().getBoolean(TAG_DONE)) return 0;
        arrow.getPersistentData().putBoolean(TAG_DONE, true);
        double r = bow ? s.bowSmolderRadius : s.smolderRadius;
        int seconds = bow ? s.bowSmolderSeconds : s.smolderSeconds;
        int n = 0;
        if (r > 0.0D && seconds > 0) {
            // 距離は体の箱まで(大きな Mob の高い所に当たっても足元までの距離で外さない)
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(r),
                    e -> e != hitTarget && e.isAlive() && PlantSideRules.isPlantSide(e) && boxDistSqr(e.getBoundingBox(), at) <= r * r)) {
                e.igniteForSeconds(seconds);
                n++;
            }
        }
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x, at.y + 0.1D, at.z, 6, r * 0.3D, 0.1D, r * 0.3D, 0.01D);
        level.sendParticles(ParticleTypes.SMALL_FLAME, at.x, at.y + 0.1D, at.z, 8, r * 0.3D, 0.1D, r * 0.3D, 0.01D);
        return n;
    }

    static double boxDistSqr(AABB box, Vec3 p) {
        double dx = Math.max(0.0D, Math.max(box.minX - p.x, p.x - box.maxX));
        double dy = Math.max(0.0D, Math.max(box.minY - p.y, p.y - box.maxY));
        double dz = Math.max(0.0D, Math.max(box.minZ - p.z, p.z - box.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    /** 胞子弾の弓の矢は植物側に余計に効く。 */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        Entity direct = event.getSource().getDirectEntity();
        if (direct instanceof AbstractArrow arrow && isSpore(arrow) && fromBow(arrow) && PlantSideRules.isPlantSide(event.getEntity())) {
            event.setAmount((float) (event.getAmount() * DesignData.gearTiers().sporeShot.bowPlantDamageMultiplier));
        }
    }
}
