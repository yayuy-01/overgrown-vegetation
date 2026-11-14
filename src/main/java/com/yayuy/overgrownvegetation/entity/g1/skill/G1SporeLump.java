package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModSounds;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
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
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * G1 が投げる胞子の塊(2026-11-13、能力の第 1 回。共通の部品 —— 村人の 1 段目の「胞子を配る」のほか、後の回の種(スケルトンの植物の弓など)も使える)。
 * 見た目は胞子嚢のアイテムを投げたもの(バニラの雪玉と同じ描き方)。当たると湿った音と胞子の粒を出して消える。
 *
 * <ul>
 *   <li>生きた動物・村人など(植物側以外の Mob)に当たる → 暴露量 +{@code dose}({@link G1SporeDose}。体は傷つけない)。プレイヤーにも同じ量。</li>
 *   <li>G1(植物側の寄生体)に当たる → 体力 +{@code heal}。</li>
 * </ul>
 * 量は投げる側が {@link #setLoad} で決める(各種の【仮】の定数)。
 */
public class G1SporeLump extends ThrowableItemProjectile {

    private double dose;
    private float heal;

    public G1SporeLump(EntityType<? extends G1SporeLump> type, Level level) {
        super(type, level);
    }

    public G1SporeLump(Level level, LivingEntity thrower) {
        super(ModG1.SPORE_LUMP.get(), thrower, level);
    }

    /** 当たったときの量(暴露量 %秒・G1 の回復)。 */
    public G1SporeLump setLoad(double dose, float heal) {
        this.dose = dose;
        this.heal = heal;
        return this;
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.SPORE_SAC.get();
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        Entity e = result.getEntity();
        if (!(e instanceof LivingEntity living) || e == getOwner()) return;
        if (living instanceof AbstractParasitizedMonster || SporeExposureTracker.isPlantSide(living)) {
            if (heal > 0.0F) living.heal(heal);
        } else if (living instanceof Player || SporeExposureTracker.isMeasuredMob(living)) {
            G1SporeDose.add(living, dose);
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level() instanceof ServerLevel level) {
            level.sendParticles(ModParticles.SPORE_JET.get(), getX(), getY(), getZ(), 14, 0.2D, 0.2D, 0.2D, 0.03D);
            level.playSound(null, getX(), getY(), getZ(), ModSounds.G1_SAC_LUMP_HIT.get(), SoundSource.HOSTILE, 0.8F, 0.7F);
            discard();
        }
    }
}
