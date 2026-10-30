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
 * 「寄生されたホグリン」(G1・ネザーの媒介種、2026-10-09)。ネザーにだけ湧く(タグ {@code nether_native})。
 * ユーザー指定(10-09): 既存の G1 と同じく近接で、全種燃える(耐火を持たない)。胞子感染で死んだ元の Mob は死体の姿勢へ崩れ落ちて死体ブロックになり、死体から起き上がる。
 * モデル・絵・アニメは生成器 {@code tools/g5-models/mobs/G1Hoglin.java}。
 */
public class ParasitizedHoglin extends AbstractParasitizedMonster {

    public ParasitizedHoglin(EntityType<? extends ParasitizedHoglin> type, Level level) {
        super(type, level);
    }

    /** 体力・攻撃力・踏ん張りはホグリンと同じ 40・6・0.6。足は元より +0.05。突き上げ・ゆがんだ菌への怯えは無い。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.MOVEMENT_SPEED, 0.35)
                .add(Attributes.FOLLOW_RANGE, 35.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.6)
                .add(Attributes.ATTACK_KNOCKBACK, 1.0);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.HOGLIN_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.HOGLIN_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.HOGLIN_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.HOGLIN_STEP, 0.15F, 1.0F);
    }
}
