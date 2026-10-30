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
 * 「寄生されたストライダー」(G1・ネザーの媒介種、2026-10-09)。ネザーにだけ湧く(タグ {@code nether_native})。
 * ユーザー指定(10-09): 既存の G1 と同じく近接で、全種燃える(耐火を持たない)。胞子感染で死んだ元の Mob は死体の姿勢へ崩れ落ちて死体ブロックになり、死体から起き上がる。
 * モデル・絵・アニメは生成器 {@code tools/g5-models/mobs/G1Strider.java}。
 */
public class ParasitizedStrider extends AbstractParasitizedMonster {

    public ParasitizedStrider(EntityType<? extends ParasitizedStrider> type, Level level) {
        super(type, level);
    }

    /** 体力はストライダーと同じ 20、攻撃 3(元は攻撃しない、仮 10-09)。足は元より +0.05。溶岩の上は歩かない(全種燃える、ユーザー指定)。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.MOVEMENT_SPEED, 0.225)
                .add(Attributes.FOLLOW_RANGE, 35.0);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.STRIDER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.STRIDER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.STRIDER_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.STRIDER_STEP, 0.15F, 1.0F);
    }
}
