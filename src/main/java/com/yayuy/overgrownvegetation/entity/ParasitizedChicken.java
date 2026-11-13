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
 * 寄生された鶏(G1 段階 0、2026-11-08 組み込み。仕様は Vault「G1 鶏(段階 0)」)。攻撃は首を引いてつつく。寄生の中心は胸の上(そ嚢)。
 * 羽ばたいてゆっくり落ちる性質は残さない(段差から石のように落ちる)。落下ダメージは受けない(11-05 ユーザー)。卵を産まない・種で寄ってこない(Monster)。
 * 体力は鶏と同じ 4、攻撃 2(旧 mobs.json)。鳴き声は「バニラの声を低く」(仕様書の【案】のまま、高さ 0.7 倍【仮】)。
 */
public class ParasitizedChicken extends AbstractParasitizedMonster {

    public ParasitizedChicken(EntityType<? extends ParasitizedChicken> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 4.0D)
                .add(Attributes.ATTACK_DAMAGE, 2.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 35.0D);
    }

    @Override
    public G1Species species() {
        return G1Species.CHICKEN;
    }

    /** 落下ダメージは受けない(11-05 ユーザー)。 */
    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.7F;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.CHICKEN_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.CHICKEN_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.CHICKEN_DEATH;
    }
}
