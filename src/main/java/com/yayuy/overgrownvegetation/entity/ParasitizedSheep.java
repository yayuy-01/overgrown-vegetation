package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;

/**
 * 寄生された羊(G1 段階 0、2026-11-08 組み込み。仕様は Vault「G1 羊(段階 0)」)。攻撃は体当たり(少し押し返す)。寄生の中心は羊毛の中。
 * 毛は元の羊の色(16 色)を引き継ぎ、苔でくすませる(描画は毛の層に色を掛けて重ねる、{@code client/G1WoolLayer})。
 * 毛は刈れない(皮と根で癒着している。Monster なので鋏は効かない)。倒したときだけ覆われた羊毛が出る(ルート表)。
 */
public class ParasitizedSheep extends AbstractParasitizedMonster {

    private static final EntityDataAccessor<Byte> COLOR =
            SynchedEntityData.defineId(ParasitizedSheep.class, EntityDataSerializers.BYTE);

    public ParasitizedSheep(EntityType<? extends ParasitizedSheep> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 8.0D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D)
                .add(Attributes.ATTACK_KNOCKBACK, 0.5D)
                .add(Attributes.MOVEMENT_SPEED, 0.23D)
                .add(Attributes.FOLLOW_RANGE, 35.0D);
    }

    @Override
    public G1Species species() {
        return G1Species.SHEEP;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(COLOR, (byte) 0);
    }

    public DyeColor getColor() {
        return DyeColor.byId(this.entityData.get(COLOR) & 15);
    }

    public void setColor(DyeColor color) {
        this.entityData.set(COLOR, (byte) color.getId());
    }

    @Override
    public void inheritCorpse(CorruptedCorpseBlockEntity corpse) {
        if (corpse.woolColor() >= 0) {
            setColor(DyeColor.byId(corpse.woolColor()));
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Color", (byte) getColor().getId());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setColor(DyeColor.byId(tag.getByte("Color")));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SHEEP_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SHEEP_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SHEEP_DEATH;
    }
}
