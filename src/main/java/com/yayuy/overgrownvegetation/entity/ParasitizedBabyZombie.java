package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 「寄生されたチビゾンビ」(G1、2026-10-09)。子どものゾンビの死体を植物が動かす。小さく速い。
 * {@link ParasitizedZombie} は子どもにならない作り(追跡速度の逆算が大人の速さ前提。クラス javadoc)なので、別の種類にした。
 * 体はバニラの子ゾンビと同じ半分の大きさ(当たり判定も)、見た目は子どもの比率の専用モデル(生成器 {@code tools/g5-models/mobs/G1BabyZombie.java})。
 */
public class ParasitizedBabyZombie extends AbstractParasitizedMonster {

    public ParasitizedBabyZombie(EntityType<? extends ParasitizedBabyZombie> type, Level level) {
        super(type, level);
    }

    /** 体力 20・攻撃 3 はゾンビと同じ、足はバニラの子ゾンビ(+50%)に合わせて速い(仮 10-09)。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.MOVEMENT_SPEED, 0.38)
                .add(Attributes.FOLLOW_RANGE, 35.0);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ZOMBIE_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ZOMBIE_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ZOMBIE_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.ZOMBIE_STEP, 0.15F, 1.4F);
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 1.4F;
    }
}
