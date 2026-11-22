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

/**
 * 寄生されたアイアンゴーレム(G1 段階 0、2026-11-09 組み込み。仕様は Vault「G1 アイアンゴーレム(段階 0)」)。
 * 胞子は関節の継ぎ目・ひびに積もり、根が殻の中を満たして操り人形のように動かす(11-09 ユーザー)。攻撃は両腕の叩きつけ(対処は横へ・盾)。
 * 強さは元のまま(体力 100・攻撃 15)。ひびは絵に描いたまま(体力でひびの層を重ねない)。自然には湧かず、感染した死体からだけ起き上がる。
 *
 * <p>仕様書の【案】のまま: 押し返し 1.0・鉄で直せない(Monster なので右クリックで直らない)・ポピーを渡さない・声と足音はゴーレム。
 * バニラのゴーレムと同じく落下ダメージを受けず、押されない(ノックバック耐性 1)。
 */
public class ParasitizedIronGolem extends AbstractParasitizedMonster {

    public ParasitizedIronGolem(EntityType<? extends ParasitizedIronGolem> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 100.0D)
                .add(Attributes.ATTACK_DAMAGE, 15.0D)
                .add(Attributes.ATTACK_KNOCKBACK, 1.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 35.0D)
                .add(Attributes.STEP_HEIGHT, 1.0D);
    }

    @Override
    public G1Species species() {
        return G1Species.IRON_GOLEM;
    }

    /** バニラのゴーレムと同じく落下ダメージを受けない。 */
    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
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
        playSound(SoundEvents.IRON_GOLEM_STEP, 1.0F, 1.0F);
    }

    @Override
    public void playAttackSound() {
        playSound(SoundEvents.IRON_GOLEM_ATTACK, 1.0F, 1.0F);
    }
}
