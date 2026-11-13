package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * 寄生されたスケルトン(G1 段階 0、2026-11-08 組み込み。仕様は Vault「G1 スケルトン(段階 0)」)。撃たない。弓は根で右手に固められて持ったまま、
 * 棍棒として横に薙ぐ(対処は下がる)。寄生の中心は肋骨のかごの中。日に焼けない(Monster)。
 *
 * <p>弓はバニラの手持ちアイテム(右手のスロット)で、描画は骨 {@code right_item} に描く({@code client/G1HeldItemLayer})。
 * 死体から起き上がるときは、元のスケルトンが持っていた物をそのまま持つ(死体ブロックが覚える)。スポーンエッグ・コマンドで湧かせたら弓を持たせる。
 * 弓を落とす確率はバニラのまま(8.5%)。体力 20・攻撃 2 はバニラのスケルトンのまま(近接の素の攻撃)。
 */
public class ParasitizedSkeleton extends AbstractParasitizedMonster {

    public ParasitizedSkeleton(EntityType<? extends ParasitizedSkeleton> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.ATTACK_DAMAGE, 2.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 35.0D);
    }

    @Override
    public G1Species species() {
        return G1Species.SKELETON;
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType,
                                        @Nullable SpawnGroupData data) {
        SpawnGroupData out = super.finalizeSpawn(level, difficulty, spawnType, data);
        if (getMainHandItem().isEmpty()) {
            setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        }
        return out;
    }

    /** 起き上がる前に死体の記録を引き継ぐ: 持っていた物(空なら空のまま)。 */
    @Override
    public void inheritCorpse(CorruptedCorpseBlockEntity corpse) {
        setItemSlot(EquipmentSlot.MAINHAND, corpse.heldItem().copy());
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SKELETON_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SKELETON_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SKELETON_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.SKELETON_STEP, 0.15F, 1.0F);
    }
}
