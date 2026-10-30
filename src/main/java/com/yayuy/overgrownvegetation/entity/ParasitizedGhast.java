package com.yayuy.overgrownvegetation.entity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * 「寄生されたガスト」(G1・ネザーの媒介種、2026-10-09)。死んだガストの体を胞子嚢が無理やり浮かせている。
 * 火の玉の代わりに胞子爆弾を撃つ(数値は mobs.json の {@code parasitized_ghast.bomb}。バニラの火の玉に近い威力 + 毒)。
 * 共通部分は {@link AbstractParasitizedFlyer}。モデル・絵・アニメは生成器 {@code tools/g5-models/mobs/G1Ghast.java}。
 */
public class ParasitizedGhast extends AbstractParasitizedFlyer {

    public ParasitizedGhast(EntityType<? extends ParasitizedGhast> type, Level level) {
        super(type, level);
        this.xpReward = 5;
    }

    /** 体力はガストと同じ 10。索敵 64(撃つ射程と同じ。バニラの 100 は全種を探すには広すぎる)。速さは行き先へ足す加速(バニラのガストは 0.1)。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 10.0)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.MOVEMENT_SPEED, 0.1);
    }

    @Override
    protected String statsId() {
        return "parasitized_ghast";
    }

    /** バニラのガストと同じ: 見えてから 20 tick で撃つ(10 tick で警告の声)。アニメ charge は 1.0 秒。 */
    @Override
    protected int chargeTicks() {
        return 20;
    }

    /** 口はため終わりの姿勢で体の正面の面の真ん中、前へ 2.49・足元から 1.41(生成器 G1Ghast の MOUTH を 4.5 倍、ブロック)。 */
    @Override
    protected double mouthForward() {
        return 2.5;
    }

    @Override
    protected double mouthUp() {
        return 1.4;
    }

    /** 胞子嚢は足元から 8.8(アニメで 10.1)まで、触手は足元の 3.8 下まで(当たり判定は 4 × 4)。 */
    @Override
    protected double drawnAbove() {
        return 6.5;
    }

    @Override
    protected double drawnBelow() {
        return 4.0;
    }

    @Override
    protected double keepMin() {
        return 12.0;
    }

    @Override
    protected double keepMax() {
        return 32.0;
    }

    @Override
    protected double hoverMin() {
        return 4.0;
    }

    @Override
    protected double hoverMax() {
        return 10.0;
    }

    @Override
    protected int spawnLift() {
        return 8;
    }

    @Override
    protected SoundEvent shootSound() {
        return SoundEvents.GHAST_SHOOT;
    }

    @Override
    protected SoundEvent warnSound() {
        return SoundEvents.GHAST_WARN;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.GHAST_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.GHAST_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.GHAST_DEATH;
    }

    /** バニラのガストと同じく遠くまで聞こえる。 */
    @Override
    protected float getSoundVolume() {
        return 5.0F;
    }

    /** 死体の喉で鳴るので低い。 */
    @Override
    public float getVoicePitch() {
        return 0.7F + (this.random.nextFloat() - 0.5F) * 0.1F;
    }
}
