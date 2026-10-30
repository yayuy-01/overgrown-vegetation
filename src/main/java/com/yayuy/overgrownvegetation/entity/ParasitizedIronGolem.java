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
 * 「寄生されたアイアンゴーレム」(G1、2026-10-10)。ユーザー指定(10-10): ゴーレムはアイアンゴーレムだけ、
 * <b>自然には湧かず、胞子感染で死んだアイアンゴーレムからだけ</b>現れる(死体の姿勢へ崩れ落ちる → 死体ブロック → 起き上がる)。
 * 既存の G1 と同じく近接だけ(投げ上げは無し)。モデル・絵・アニメは生成器 {@code tools/g5-models/mobs/G1IronGolem.java}。
 */
public class ParasitizedIronGolem extends AbstractParasitizedMonster {

    public ParasitizedIronGolem(EntityType<? extends ParasitizedIronGolem> type, Level level) {
        super(type, level);
    }

    /** 体力・攻撃力・踏ん張り・段差はアイアンゴーレムと同じ 100・15・1.0・1.0(仮決め)。足は元より +0.05。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 100.0)
                .add(Attributes.ATTACK_DAMAGE, 15.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.IRON_GOLEM_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.IRON_GOLEM_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.IRON_GOLEM_STEP, 1.0F, 1.0F);
    }
}
