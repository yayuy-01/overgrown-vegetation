package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.corpse.G1Species;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * 寄生されたゾンビ(G1 段階 0、2026-11-08 に作り直し 2 回目のモデル r9 で組み込み)。共通の仕様そのもの(種のノートは無い。
 * Vault「G1 段階 0 の共通」の「ゾンビ」の節)。攻撃は右腕を棍棒のように振り下ろす(前触れ = 胸から引き起こされてのけぞり、右腕が頭の上の後ろまで)。
 *
 * <p>体力・攻撃・防御はバニラのゾンビのまま(20・3・2)、追跡範囲は旧の値 35、足の速さは mobs.json(旧 0.28)。
 * バニラのゾンビの能力(村人をゾンビ村人にする・扉を壊す・水でドラウンドになる・仲間を呼ぶ)は持たない(Monster から作る)。
 */
public class ParasitizedZombie extends AbstractParasitizedMonster {

    public ParasitizedZombie(EntityType<? extends ParasitizedZombie> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D)
                .add(Attributes.ARMOR, 2.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.23D)
                .add(Attributes.FOLLOW_RANGE, 35.0D);
    }

    @Override
    public G1Species species() {
        return G1Species.ZOMBIE;
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
    protected void playStepSound(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        playSound(SoundEvents.ZOMBIE_STEP, 0.15F, 1.0F);
    }
}
