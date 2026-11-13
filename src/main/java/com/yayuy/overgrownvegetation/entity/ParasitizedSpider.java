package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.corpse.G1Species;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 寄生されたクモ(G1 段階 0、2026-11-08 組み込み。仕様は Vault「G1 クモ(段階 0)」)。攻撃は立って噛む(跳びつかない)。壁を登らない(Monster)。
 * 寄生の中心は腹、右後ろの 2 本を引きずる。速さはバニラの 0.3(11-05 ユーザー)。
 * 元の性質で残すのは「毒が効かない」「クモの巣で遅くならない」の 2 つだけ(11-05 ユーザー)。目は光らせない(発光の層を持たない)。
 */
public class ParasitizedSpider extends AbstractParasitizedMonster {

    public ParasitizedSpider(EntityType<? extends ParasitizedSpider> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 16.0D)
                .add(Attributes.ATTACK_DAMAGE, 2.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.FOLLOW_RANGE, 35.0D);
    }

    @Override
    public G1Species species() {
        return G1Species.SPIDER;
    }

    /** 毒が効かない(バニラのクモと同じ)。 */
    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        return !effect.is(MobEffects.POISON) && super.canBeAffected(effect);
    }

    /** クモの巣で遅くならない(バニラのクモと同じ)。 */
    @Override
    public void makeStuckInBlock(BlockState state, Vec3 motionMultiplier) {
        // 根糸の巣(1 段目のクモが張る、2026-11-13)でも遅くならない
        if (!state.is(Blocks.COBWEB) && !state.is(com.yayuy.overgrownvegetation.registry.ModG1.ROOTSILK_WEB.get())) {
            super.makeStuckInBlock(state, motionMultiplier);
        }
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SPIDER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SPIDER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SPIDER_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.SPIDER_STEP, 0.15F, 1.0F);
    }
}
