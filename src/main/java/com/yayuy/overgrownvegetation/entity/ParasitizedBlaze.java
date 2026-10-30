package com.yayuy.overgrownvegetation.entity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 「寄生されたブレイズ」(G1・ネザーの媒介種、2026-10-09)。火の消えたブレイズの体を胞子嚢が無理やり浮かせている。
 * 火の玉 3 連射の代わりに小さな胞子爆弾を 3 連射する(数値は mobs.json の {@code parasitized_blaze.bomb}。バニラの火の玉に近い威力 + 毒)。
 * 近ければ殴る(バニラのブレイズと同じ。毒が乗る)。火が消えているので水・雪玉では傷つかない(仮)。
 * 共通部分は {@link AbstractParasitizedFlyer}。モデル・絵・アニメは生成器 {@code tools/g5-models/mobs/G1Blaze.java}。
 */
public class ParasitizedBlaze extends AbstractParasitizedFlyer {

    /** 動きにつられて揺れる体と棒(クライアントだけ。描画は client.ParasitizedBlazeModel、10-09 夜)。 */
    @Nullable
    private BlazeRodSwing rodSwing;

    public ParasitizedBlaze(EntityType<? extends ParasitizedBlaze> type, Level level) {
        super(type, level);
        this.xpReward = 10;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            tickRodSwing();
        }
    }

    /**
     * 体と棒の揺れを 1 tick 進める。速さはこの tick に動いた量(クライアントの補間後の位置)を体の向き(描画と同じ yBodyRot)の前・右へ直したもの。
     * 1 tick に 2 ブロックより飛んだらテレポート(速さに数えない)。漂っている 15 秒は入力 0(目覚めから効く)。
     */
    private void tickRodSwing() {
        if (this.rodSwing == null) {
            this.rodSwing = new BlazeRodSwing();
        }
        double dx = this.getX() - this.xo;
        double dy = this.getY() - this.yo;
        double dz = this.getZ() - this.zo;
        if (this.dormantTicks() > WAKE_TICKS) {
            this.rodSwing.tick(0, 0, 0, 0);
            return;
        }
        if (dx * dx + dy * dy + dz * dz > 4.0D) {
            this.rodSwing.tickUnchanged();
            return;
        }
        float yaw = this.yBodyRot * Mth.DEG_TO_RAD;
        double sin = Mth.sin(yaw);
        double cos = Mth.cos(yaw);
        // バニラの向き: 前 = (−sin, cos)、右 = (−cos, −sin)
        double forward = -dx * sin + dz * cos;
        double right = -dx * cos - dz * sin;
        this.rodSwing.tick(forward, right, dy, Mth.wrapDegrees(this.yBodyRot - this.yBodyRotO));
    }

    /** 描画用(クライアント)。まだ 1 tick も進んでいなければ null。 */
    @Nullable
    public BlazeRodSwing rodSwing() {
        return this.rodSwing;
    }

    /** 待機の棒の揺れが大きいので、ためへの補間を長くする(4 tick だと棒が跳ねる。アニメは 2.6 秒で撃つ、生成器 G1BlazeAnims)。 */
    @Override
    protected int chargeTransitionTicks() {
        return 8;
    }

    /** 体力・攻撃力はブレイズと同じ 20・6。索敵 48(ブレイズと同じ)。速さは行き先へ足す加速(ガストより遅い)。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.MOVEMENT_SPEED, 0.06);
    }

    @Override
    protected String statsId() {
        return "parasitized_blaze";
    }

    /** バニラのブレイズと同じ: 3 秒ためてから 3 連射。アニメ charge は 3.0 秒。 */
    @Override
    protected int chargeTicks() {
        return 60;
    }

    /** 吐く所は頭の正面の面の真ん中、足元から 1.43(生成器 G1Blaze の MOUTH。頭の前の面は 0.24 前なので、少し前へ出す)。 */
    @Override
    protected double mouthForward() {
        return 0.35;
    }

    @Override
    protected double mouthUp() {
        return 1.43;
    }

    /** 頭の上の胞子嚢は足元から 2.6 まで(当たり判定は 1.8)。 */
    @Override
    protected double drawnAbove() {
        return 1.0;
    }

    @Override
    protected double drawnBelow() {
        return 0.0;
    }

    @Override
    protected double keepMin() {
        return 5.0;
    }

    @Override
    protected double keepMax() {
        return 12.0;
    }

    @Override
    protected double hoverMin() {
        return 1.5;
    }

    @Override
    protected double hoverMax() {
        return 4.0;
    }

    /** バニラのブレイズと同じく 2 ブロックより近ければ殴る。 */
    @Override
    protected double meleeRangeSqr() {
        return 4.0;
    }

    @Override
    protected int spawnLift() {
        return 3;
    }

    @Override
    protected SoundEvent shootSound() {
        return SoundEvents.BLAZE_SHOOT;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.BLAZE_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.BLAZE_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.BLAZE_DEATH;
    }

    /** 火の消えた体で鳴るので低い。 */
    @Override
    public float getVoicePitch() {
        return 0.65F + (this.random.nextFloat() - 0.5F) * 0.1F;
    }
}
