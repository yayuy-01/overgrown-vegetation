package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModFlyers;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * セミナリアが腹の松かさの鱗の間から落とす種(2026-11-16、能力の段。能力の決定 11-14「種を落とす。落ちた種は小さな汚染の元になり、飛び地を作る」)。
 * 落ちた所(ブロックに当たった所)の升へ胞子の降り積もりを足す({@link #DEPOSIT_PERCENT})。同じ升に何粒か落ちて発芽のしきい値を越えると、
 * 胞子の場の発芽(湿りがそろった日)で芽が出て、前線から離れた飛び地になる。
 * <ul>
 *   <li>生き物には当たらず素通りする(攻撃しない種。傷も雲も無い)。</li>
 *   <li>見た目は【仮】: 胞子嚢のアイテムの絵を小さく。種の絵はまだ無い。音は無い。</li>
 * </ul>
 */
public class SeminariaSeed extends ThrowableItemProjectile {

    /**
     * 落ちた升へ足す降り積もり(発芽のしきい値の %)【仮・Claude 11-16】。1 粒でちょうどしきい値(= 1 粒でも湿った日に芽が出ることがある
     * 「小さな汚染の元」)。群れ(3〜5 体)が同じ升に落とすと 300〜500% = 感染した Mob の飛び火(300%)と同じくらいで、ほぼ確実に飛び地になる。
     * 降り積もりは 1 日に 4 割ほど減るので、射落として粒を減らすほど芽が出にくい。
     */
    public static final double DEPOSIT_PERCENT = 100.0D;
    /** 何にも当たらないまま落ち続けたら消す(tick)。 */
    static final int MAX_AGE = 200;

    /** 計測・テスト用: 落ちて降り積もりを足した種の数(サーバー全体)。 */
    public static volatile int landed;

    public SeminariaSeed(EntityType<? extends SeminariaSeed> type, Level level) {
        super(type, level);
    }

    public SeminariaSeed(Level level, LivingEntity owner, Vec3 at) {
        super(ModFlyers.SEMINARIA_SEED.get(), owner, level);
        this.setPos(at);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return false;
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
        land(level, result.getLocation());
        this.discard();
    }

    /** 落ちた所の升へ降り積もりを足す(GameTest からも呼ぶ)。 */
    public static void land(ServerLevel level, Vec3 at) {
        SporeFieldServer.depositPoint(level, BlockPos.containing(at), DesignData.sporeField().field().germThreshold * DEPOSIT_PERCENT / 100.0D);
        landed++;
        if (Boolean.getBoolean("ov.devbridge")) com.mojang.logging.LogUtils.getLogger().info("SEMI_LAND 種が落ちた: {} {} {} 升 {} {} 台帳 {}", String.format("%.1f", at.x), String.format("%.1f", at.y),
                String.format("%.1f", at.z), Mth.floor(at.x) >> 4, Mth.floor(at.z) >> 4,
                com.yayuy.overgrownvegetation.phase.PhaseData.get(level).isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(Mth.floor(at.x) >> 4, Mth.floor(at.z) >> 4)));
    }
}
