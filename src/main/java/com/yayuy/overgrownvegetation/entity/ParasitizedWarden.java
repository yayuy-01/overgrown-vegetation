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
 * 「寄生されたウォーデン」(G1、2026-10-10)。ユーザー指定(10-10): <b>自然には湧かず、胞子感染で死んだウォーデンからだけ</b>現れる
 * (死体の姿勢へ崩れ落ちる → 死体ブロック → 起き上がる)。
 * 仮決め: 既存の G1 と同じく近接だけ(ソニックブーム・暗闇・振動を聞いて探す・地面に潜る は無い。目で見て追う)。
 * モデル・絵・アニメは生成器 {@code tools/g5-models/mobs/G1Warden.java}。
 */
public class ParasitizedWarden extends AbstractParasitizedMonster {

    public ParasitizedWarden(EntityType<? extends ParasitizedWarden> type, Level level) {
        super(type, level);
    }

    /** 体力・攻撃力・踏ん張り・ノックバックはウォーデンと同じ 500・30・1.0・1.5(仮決め)。足は元より +0.05。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 500.0)
                .add(Attributes.ATTACK_DAMAGE, 30.0)
                .add(Attributes.MOVEMENT_SPEED, 0.35)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.ATTACK_KNOCKBACK, 1.5)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.WARDEN_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.WARDEN_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.WARDEN_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.WARDEN_STEP, 10.0F, 1.0F);
    }
}
