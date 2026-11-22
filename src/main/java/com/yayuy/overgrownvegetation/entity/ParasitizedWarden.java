package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.corpse.G1Species;
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
import com.yayuy.overgrownvegetation.port.entity.PortAttributes;

/**
 * 寄生されたウォーデン(G1 段階 0、2026-11-09 組み込み。仕様は Vault「G1 ウォーデン(段階 0)」)。攻撃は片腕の振り下ろし(前触れで開いた胸の袋がふくらむ。対処は横へ)。
 * 強さは元の半分(体力 250・攻撃 20、11-09 ユーザー)。
 *
 * <p><b>感染の道は今は作らない</b>(11-09 ユーザー「今はモデルだけを作る」)。バニラのウォーデンは死んでも死体にならない({@link G1Species#infectable})。
 * 湧くのはスポーンエッグ・コマンドと、置いた死体ブロックからだけ。どこでどう感染させるかは地下の汚染(0.7.0)と一緒に決める。
 * 仕様書の【案】のまま: 心音は使わず袋は見た目だけ鼓動・潜って消えない・目が無くても共通の索敵(ソニックブーム・暗闇・振動で探す・投げ上げは無し)。
 * ノックバック耐性 1 はバニラのまま。
 */
public class ParasitizedWarden extends AbstractParasitizedMonster {

    public ParasitizedWarden(EntityType<? extends ParasitizedWarden> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 250.0D)
                .add(Attributes.ATTACK_DAMAGE, 20.0D)
                .add(Attributes.ATTACK_KNOCKBACK, 1.5D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.FOLLOW_RANGE, 35.0D)
                .add(PortAttributes.STEP_HEIGHT.get(), PortAttributes.stepHeightBase(1.0D));
    }

    @Override
    public G1Species species() {
        return G1Species.WARDEN;
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
        playSound(SoundEvents.WARDEN_STEP, 10.0F, 1.0F);
    }
}
