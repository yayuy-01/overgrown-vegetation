package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModCrawlers;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * ストロビラが尾の先から山なりに投げる球果(2026-11-15、段 2)。落ちた所(当たった所)で胞子の煙になり({@link G1SporeCloud}。中にいる相手の暴露量が増える)、
 * その升に小さな降り積もりを残す(能力の決定 11-14「球果は落ちた所で胞子の雲になり、小さな胞子の汚れを残す」)。
 *
 * <ul>
 *   <li>植物側(G1〜G10・寄生体・汎用の感染体)には当たらず素通りする(群れの後ろから前の味方越しに投げるため)。</li>
 *   <li>相手に直に当たると傷 {@link #DIRECT_DAMAGE}(11-18 夜。前は傷なし)。地面に落ちたときは雲だけ。</li>
 *   <li>見た目は【仮】: 胞子嚢のアイテムの絵(G1 の胞子の塊と同じ描き方)。球果の絵はまだ無い。</li>
 *   <li>音は無い(音は設定の確認が要るので、能力の段では入れない)。</li>
 * </ul>
 */
public class StrobilaCone extends ThrowableItemProjectile {

    /** 煙の半径(ブロック)・漂う長さ(tick)・中にいる間の暴露量(%秒 / 秒)【仮・Claude 11-15】(G1 の袋の煙と同じ量)。 */
    public static final float CLOUD_RADIUS = 2.0F;
    public static final int CLOUD_TICKS = 60;
    public static final double CLOUD_DOSE_PER_SECOND = 100.0D;
    /** 落ちた升へ足す降り積もり(発芽のしきい値の %)【仮・Claude 11-15】(G1 の綿毛と同じ)。 */
    public static final double DEPOSIT_PERCENT = 15.0D;

    public StrobilaCone(EntityType<? extends StrobilaCone> type, Level level) {
        super(type, level);
    }

    public StrobilaCone(Level level, LivingEntity thrower) {
        super(ModCrawlers.STROBILA_CONE.get(), thrower, level);
    }

    /** 素通りする相手か(植物側)。GameTest からも読む。 */
    public static boolean passesThrough(Entity e) {
        return e instanceof ParasitizedMob || (e instanceof LivingEntity l && SporeExposureTracker.isPlantSide(l));
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return super.canHitEntity(target) && !passesThrough(target);
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.SPORE_SAC.get();
    }

    /** 直撃の傷(11-18 夜 ユーザー決定「傷の無い飛び道具は、攻撃の値を直撃の傷にする」: ストロビラの攻撃 23。actions.json の strobila/cone で直せる)。 */
    public static final float DIRECT_DAMAGE = 23.0F;

    public static float directDamage() {
        return (float) DesignData.actions().num("strobila", "cone", "damage", DIRECT_DAMAGE);
    }

    @Override
    protected void onHitEntity(net.minecraft.world.phys.EntityHitResult result) {
        super.onHitEntity(result);
        if (this.level().isClientSide()) return;
        Entity owner = this.getOwner();
        result.getEntity().hurt(this.damageSources().mobProjectile(this, owner instanceof LivingEntity o ? o : null), directDamage());
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!(this.level() instanceof ServerLevel level) || this.isRemoved()) return;
        Vec3 at = result.getLocation();
        G1SporeCloud.spawn(level, at, CLOUD_RADIUS, CLOUD_TICKS, CLOUD_DOSE_PER_SECOND, -1, 0, false, this.getOwner(), false);
        SporeFieldServer.depositPoint(level, BlockPos.containing(at), DesignData.sporeField().field().germThreshold * DEPOSIT_PERCENT / 100.0D);
        this.discard();
    }
}
