package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud;
import com.yayuy.overgrownvegetation.registry.ModFlyers;
import com.yayuy.overgrownvegetation.registry.ModItems;
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
 * ヴェロフィルの胞子爆弾(2026-11-15、能力の段)。急降下の引き起こしで、翼の裏の胞子嚢から放す(`dive_recover` の 2 tick 目)。
 * 放した所から落ち、当たった所(地面・相手)で胞子の雲になり({@link G1SporeCloud}。中にいる相手の暴露量が増える)、その升に小さな降り積もりを残す
 * (能力の決定 11-14「胞子爆弾を落とす(胞子の雲 + 小さな汚れ)」)。作りはストロビラの球果と同じで、雲を大きくした。
 * <ul>
 *   <li>植物側(G1〜G10・寄生体・汎用の感染体)には当たらず素通りする({@link StrobilaCone#passesThrough})。</li>
 *   <li>【決定 11-15 ユーザー(案 B)】真下に当たった(相手に直に当たった)ときだけ小さな傷 {@link #DIRECT_DAMAGE} を付ける。雲と降り積もりはいつも。</li>
 *   <li>見た目は当面、胞子嚢のアイテムの絵(11-15 ユーザー)。叫び・爆弾の音は付けない(11-15 ユーザー「付ける予定はない」)。</li>
 * </ul>
 */
public class VelophyllBomb extends ThrowableItemProjectile {

    /** 雲の半径(ブロック)・漂う長さ(tick)・中にいる間の暴露量(%秒 / 秒)【仮・Claude 11-15】(球果 2.0・60 より大きい空からの爆弾)。 */
    public static final float CLOUD_RADIUS = 3.0F;
    public static final int CLOUD_TICKS = 80;
    public static final double CLOUD_DOSE_PER_SECOND = 100.0D;
    /** 落ちた升へ足す降り積もり(発芽のしきい値の %)【仮・Claude 11-15】(球果と同じ)。 */
    public static final double DEPOSIT_PERCENT = 15.0D;
    /** 直に当たったときの傷【仮・決定 11-15 ユーザー: 小さな傷】。植物側の攻撃なので、当たった相手は既存の仕組みで感染する。 */
    public static final float DIRECT_DAMAGE = 2.0F;
    /** 何にも当たらないまま落ち続けたら消す(tick)。 */
    static final int MAX_AGE = 200;

    public VelophyllBomb(EntityType<? extends VelophyllBomb> type, Level level) {
        super(type, level);
    }

    public VelophyllBomb(Level level, LivingEntity owner, Vec3 at) {
        super(ModFlyers.VELOPHYLL_BOMB.get(), owner, level);
        this.setPos(at);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return super.canHitEntity(target) && !StrobilaCone.passesThrough(target);
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.SPORE_SAC.get();
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && this.tickCount > MAX_AGE) this.discard();
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!(this.level() instanceof ServerLevel level) || this.isRemoved()) return;
        if (result instanceof net.minecraft.world.phys.EntityHitResult eh) {
            eh.getEntity().hurt(this.damageSources().mobProjectile(this, this.getOwner() instanceof LivingEntity o ? o : null), DIRECT_DAMAGE);
        }
        burst(level, result.getLocation(), this.getOwner());
        this.discard();
    }

    /** 弾けた所に雲と降り積もり(GameTest からも呼ぶ)。 */
    public static void burst(ServerLevel level, Vec3 at, Entity owner) {
        G1SporeCloud.spawn(level, at, CLOUD_RADIUS, CLOUD_TICKS, CLOUD_DOSE_PER_SECOND, -1, 0, false, owner, false);
        SporeFieldServer.depositPoint(level, BlockPos.containing(at), DesignData.sporeField().field().germThreshold * DEPOSIT_PERCENT / 100.0D);
    }
}
