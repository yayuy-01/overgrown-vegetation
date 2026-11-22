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
 *   <li>植物側(G1〜G8・環境生物・寄生体・汎用の感染体)には当たらず素通りする({@link StrobilaCone#passesThrough})。</li>
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
    public static final float DIRECT_DAMAGE = 10.0F;   // 11-18 夜 ユーザー「ヴェロフィルの直撃は 10」(攻撃 4 → 10 を直撃の傷に)。actions.json の velophyll/bomb
    /** 確かめ用(開発用のログ): 当たったもの(爆弾ごと)。 */
    public String hitDesc = "";
    /** 狙った相手(測る用)。 */
    public LivingEntity aimed;
    /** 確かめ用: 直しの前の当たり方(部位を持ち主で見ない)。 */
    public boolean oldAim;
    private static final boolean DEV = Boolean.getBoolean("ov.devbridge");

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
        // 叩ける部位(翼など)は持ち主で見る(部位は植物側の印を持たないので、放した直後に自分の翼に当たって弾けていた。11-16)
        Entity body = target instanceof net.minecraftforge.entity.PartEntity<?> pe ? pe.getParent() : target;
        return super.canHitEntity(target) && !StrobilaCone.passesThrough(this.oldAim ? target : body);
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
        this.hitDesc = (result instanceof net.minecraft.world.phys.EntityHitResult eh0 ? "相手 " + eh0.getEntity().getType().toShortString() : "ブロック " + result.getType())
                + String.format(" (%.2f, %.2f, %.2f) 放してから %d tick", result.getLocation().x, result.getLocation().y, result.getLocation().z, this.tickCount);
        if (DEV && this.aimed != null) com.mojang.logging.LogUtils.getLogger().info("VELO_HIT {} 相手との水平の距離 {}", this.hitDesc,
                String.format("%.2f", Math.hypot(result.getLocation().x - this.aimed.getX(), result.getLocation().z - this.aimed.getZ())));
        if (result instanceof net.minecraft.world.phys.EntityHitResult eh) {
            eh.getEntity().hurt(this.damageSources().mobProjectile(this, this.getOwner() instanceof LivingEntity o ? o : null), (float) com.yayuy.overgrownvegetation.data.DesignData.actions().num("velophyll", "bomb", "damage", DIRECT_DAMAGE));
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
