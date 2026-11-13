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
 * 寄生された村人(G1 段階 0、2026-11-08 組み込み。仕様は Vault「G1 村人(段階 0)」)。腕を組まない、がっつりゾンビ型(11-05 ユーザー)。
 * 寄生の中心は背中。死体は腕を組んだまま、起き上がり(3.4 秒)で植物が組んだ腕を引きはがす。
 * 体力は村人と同じ 20、攻撃は持たないのでゾンビと同じ 3(共通の仕様書【仮】)。声はゾンビ村人のもの(旧と同じ)。
 * 生きている村人はこの G1 から逃げる({@code mixin/VillagerHostilesSensorMixin})。
 */
public class ParasitizedVillager extends AbstractParasitizedMonster {

    public ParasitizedVillager(EntityType<? extends ParasitizedVillager> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 35.0D);
    }

    @Override
    public G1Species species() {
        return G1Species.VILLAGER;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ZOMBIE_VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ZOMBIE_VILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ZOMBIE_VILLAGER_DEATH;
    }
}
