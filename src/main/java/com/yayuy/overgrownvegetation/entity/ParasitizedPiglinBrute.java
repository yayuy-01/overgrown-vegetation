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
 * 「寄生されたピグリンブルート」(G1・ネザーの媒介種、2026-10-09)。ネザーにだけ湧く(タグ {@code nether_native})。
 * ユーザー指定(10-09): 既存の G1 と同じく近接で、全種燃える(耐火を持たない)。胞子感染で死んだ元の Mob は死体の姿勢へ崩れ落ちて死体ブロックになり、死体から起き上がる。
 * モデル・絵・アニメは生成器 {@code tools/g5-models/mobs/G1PiglinBrute.java}。
 */
public class ParasitizedPiglinBrute extends AbstractParasitizedMonster {

    public ParasitizedPiglinBrute(EntityType<? extends ParasitizedPiglinBrute> type, Level level) {
        super(type, level);
    }

    /** 体力はピグリンブルートと同じ 50、攻撃 7(斧を持たない、仮 10-09)。足は元より +0.05。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 50.0)
                .add(Attributes.ATTACK_DAMAGE, 7.0)
                .add(Attributes.MOVEMENT_SPEED, 0.4)
                .add(Attributes.FOLLOW_RANGE, 35.0);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.PIGLIN_BRUTE_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PIGLIN_BRUTE_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PIGLIN_BRUTE_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.PIGLIN_BRUTE_STEP, 0.15F, 1.0F);
    }
}
