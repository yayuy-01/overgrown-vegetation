package com.yayuy.overgrownvegetation.entity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * 「寄生されたエンダーマン」(G1、2026-10-09)。背を丸め、長い腕をだらりと下げた死体を植物が動かす。
 * ユーザー指定(10-09)「全員近接でそろえる」: テレポートしない、ブロックを持たない、目を合わせても怒らない(近くの生き物を追って殴る)。
 * 水に濡れても傷まない(植物に乗っ取られた体)。モデル・絵・アニメは生成器 {@code tools/g5-models/mobs/G1Enderman.java}。
 */
public class ParasitizedEnderman extends AbstractParasitizedMonster {

    public ParasitizedEnderman(EntityType<? extends ParasitizedEnderman> type, Level level) {
        super(type, level);
    }

    /** 体力・攻撃はエンダーマンと同じ 40・7(仮 10-09)。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.ATTACK_DAMAGE, 7.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
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
