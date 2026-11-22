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

/**
 * 寄生された牛(G1 段階 0、2026-11-08 組み込み。仕様は Vault「G1 牛(段階 0)」)。攻撃は頭突き(少し押し返す。前触れは頭を下げて首を引くだけ)。
 * 寄生の中心は背中。バケツで乳を搾れない・繁殖しない(Monster)。体力は牛と同じ 10、攻撃 3(共通【仮】)、押し返し 0.5【仮】。
 */
public class ParasitizedCow extends AbstractParasitizedMonster {

    public ParasitizedCow(EntityType<? extends ParasitizedCow> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 10.0D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D)
                .add(Attributes.ATTACK_KNOCKBACK, 0.5D)
                .add(Attributes.MOVEMENT_SPEED, 0.2D)
                .add(Attributes.FOLLOW_RANGE, 35.0D);
    }

    @Override
    public G1Species species() {
        return G1Species.COW;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.COW_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.COW_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.COW_DEATH;
    }
}
