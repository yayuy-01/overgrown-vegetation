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
 * 「寄生されたスケルトン」(G1、2026-10-09)。肋骨の中で育った植物が骨を動かす。
 * ユーザー指定(10-09)「全員近接でそろえる」: 弓は捨てて殴る(バニラの AbstractSkeleton は武器に合わせて Goal を足し直すので継がない)。
 * モデル・絵・アニメは生成器 {@code tools/g5-models/mobs/G1Skeleton.java}。
 */
public class ParasitizedSkeleton extends AbstractParasitizedMonster {

    public ParasitizedSkeleton(EntityType<? extends ParasitizedSkeleton> type, Level level) {
        super(type, level);
    }

    /** 体力はスケルトンと同じ 20、攻撃 3(素手のスケルトン相当、仮 10-09)。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 35.0);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SKELETON_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SKELETON_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SKELETON_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SKELETON_STEP, 0.15F, 1.0F);
    }
}
