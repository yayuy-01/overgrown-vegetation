package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.corpse.G1Species;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * 寄生されたエンダーマン(G1 段階 0、2026-11-08 組み込み。仕様は Vault「G1 エンダーマン(段階 0)」)。攻撃は長い腕の横薙ぎ(対処は下がる・盾)。
 * 目を合わせても何も起きない・水で傷まない・テレポートしない・ブロックを持たない(Monster から作るので、どれもバニラの動きが無い)。
 * 寄生の中心は頭の後ろ〜首(顎が外れて口が開いたまま)。体力 40・攻撃 7 はバニラのまま。間合いは腕の長さで +{@value #REACH_EXTRA}【仮】。
 */
public class ParasitizedEnderman extends AbstractParasitizedMonster {

    static final double REACH_EXTRA = 0.5D;

    public ParasitizedEnderman(EntityType<? extends ParasitizedEnderman> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)
                .add(Attributes.ATTACK_DAMAGE, 7.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.FOLLOW_RANGE, 35.0D);
    }

    @Override
    public G1Species species() {
        return G1Species.ENDERMAN;
    }

    @Override
    protected AABB getAttackBoundingBox() {
        return super.getAttackBoundingBox().inflate(REACH_EXTRA, 0.0D, REACH_EXTRA);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ENDERMAN_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ENDERMAN_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENDERMAN_DEATH;
    }
}
