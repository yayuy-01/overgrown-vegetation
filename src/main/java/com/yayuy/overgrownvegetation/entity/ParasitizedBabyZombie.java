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
 * 寄生されたチビゾンビ(G1 段階 0、2026-11-08 組み込み。仕様は Vault「G1 チビゾンビ(段階 0)」)。
 * 速さはバニラの子ゾンビと同じ 0.345(11-05 ユーザー)。攻撃は脚へ飛びついて噛む(アニメ、3 段は共通)。寄生の中心は後頭部。
 * 子ゾンビの死体からだけ起き上がる(大人のゾンビとは別の死体)。
 */
public class ParasitizedBabyZombie extends AbstractParasitizedMonster {

    public ParasitizedBabyZombie(EntityType<? extends ParasitizedBabyZombie> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D)
                .add(Attributes.ARMOR, 2.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.345D)
                .add(Attributes.FOLLOW_RANGE, 35.0D);
    }

    @Override
    public G1Species species() {
        return G1Species.BABY_ZOMBIE;
    }

    private static final software.bernie.geckolib.animation.RawAnimation RIDE = software.bernie.geckolib.animation.RawAnimation.begin().thenLoop("ride");

    /** 1 段目の豚の背にまたがって座る(アニメ ride、2026-11-13)。 */
    @Override
    protected software.bernie.geckolib.animation.RawAnimation ridingAnimation() {
        return RIDE;
    }

    /** 声はバニラの子ゾンビと同じく高い。 */
    @Override
    public float getVoicePitch() {
        return (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.5F;
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
}
