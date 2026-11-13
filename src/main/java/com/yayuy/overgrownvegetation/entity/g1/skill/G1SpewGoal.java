package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * 鶏の 1 段目の (c)「そ嚢の胞子を吐く」(2026-11-13、能力の第 2 回。仕様 Vault「G1 鶏(1 段目)」3 節【決定 11-09・数は仮】)。
 * 胸のそ嚢を絞り、前へ短い胞子の雲を吐き続ける({@link G1SporeCone} を {@value #PULSE_TICKS} tick おきに)。
 *
 * <ul>
 *   <li>使う間合い: 標的が {@value #MIN_RANGE}〜{@value #MAX_RANGE} ブロックで正面(±{@value #FRONT_DEG}°)。</li>
 *   <li>段: spew_windup 20(立ち止まって首を下げ、そ嚢が 3 回脈打つ。15 tick まで向きを追う)→ spew 30(吐き続ける)→ spew_recover 14。</li>
 *   <li>円錐: 長さ {@value #LENGTH}・±{@value #HALF_ANGLE}°。中にいる間 暴露量 +{@value #DOSE_PER_SECOND} / 秒(仕様「中にいる間」= 吐いている 1.5 秒に 3 回、
 *   1 回 {@value #DOSE_PER_SECOND} × {@value #PULSE_TICKS} / 20)。横へ出れば残りは浴びない。</li>
 *   <li>その升の浮遊を +{@value #AIR_PERCENT}%(仕様「その升の浮遊を少し上げる」。吐き始めに 1 回)。</li>
 *   <li>冷却 {@value #COOLDOWN} tick(仕様「間隔 10 秒以上」)。</li>
 * </ul>
 * 数値はすべて【仮】(このクラスの定数)。
 */
public class G1SpewGoal extends G1TechniqueGoal {

    static final double MIN_RANGE = 1.0D;
    static final double MAX_RANGE = 3.2D;
    static final float FRONT_DEG = 45.0F;
    static final double LENGTH = 3.0D;
    static final double HALF_ANGLE = 30.0D;
    static final double DOSE_PER_SECOND = 150.0D;
    static final int PULSE_TICKS = 10;
    static final double AIR_PERCENT = 2.0D;
    static final int COOLDOWN = 200;

    public G1SpewGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.SPEW_WINDUP, G1Move.SPEW, G1Move.SPEW_RECOVER}, new int[]{20, 30, 14});
    }

    @Override
    protected boolean ready() {
        if (target == null || !target.isAlive() || !mob.canAttack(target) || !mob.getSensing().hasLineOfSight(target)) return false;
        double d = mob.distanceTo(target);
        if (d < MIN_RANGE || d > MAX_RANGE) return false;
        float to = (float) (Mth.atan2(target.getZ() - mob.getZ(), target.getX() - mob.getX()) * Mth.RAD_TO_DEG) - 90.0F;
        return Math.abs(Mth.wrapDegrees(to - mob.getYRot())) <= FRONT_DEG;
    }

    @Override
    protected int aimUntil() {
        return 15;
    }

    @Override
    protected int cooldown() {
        return COOLDOWN;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (stageIndex == 0 && (tickInStage == 3 || tickInStage == 9 || tickInStage == 15)) {
            // そ嚢の脈打ち(3 回)
            mob.level().playSound(null, mob.getX(), mob.getY() + 0.4D, mob.getZ(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.HOSTILE, 0.6F, 0.7F);
        }
        if (stageIndex == 1 && tickInStage % PULSE_TICKS == 1 && mob.level() instanceof ServerLevel level) {
            G1SporeCone.puff(level, mob, lockedYaw, LENGTH, HALF_ANGLE, DOSE_PER_SECOND * PULSE_TICKS / 20.0D, 0.0D);
            if (tickInStage == 1) {
                SporeFieldServer.emitPoint(level, mob.blockPosition(), AIR_PERCENT / 100.0D * DesignData.sporeField().fullAt());
                level.playSound(null, mob.getX(), mob.getY() + 0.6D, mob.getZ(), SoundEvents.CHICKEN_HURT, SoundSource.HOSTILE, 0.6F, 0.5F);
            }
        }
    }
}
