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
 * 「寄生された村人」(G1、2026-10-09)。胞子に乗っ取られた村人の死体が、植物に動かされて歩く。
 * 村人は脳(Brain)で動く Mob で G1 の Goal を差し込めないので、{@link AbstractParasitizedMonster}(Monster)から作る。
 * 戦い方は既存の G1 と同じ近接。声はゾンビ村人のもの。
 * モデル・絵・アニメは生成器 {@code tools/g5-models/mobs/G1Villager.java}(絵はユーザーがシダを塗り直したもの)。
 */
public class ParasitizedVillager extends AbstractParasitizedMonster {

    public ParasitizedVillager(EntityType<? extends ParasitizedVillager> type, Level level) {
        super(type, level);
    }

    /** 体力は村人と同じ 20、攻撃はゾンビと同じ 3(仮、10-09)。足の速さは mobs.json。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 35.0);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ZOMBIE_VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ZOMBIE_VILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ZOMBIE_VILLAGER_DEATH;
    }
}
