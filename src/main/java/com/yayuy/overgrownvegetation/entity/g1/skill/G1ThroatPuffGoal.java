package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * 豚の 1 段目の (c)「喉袋の胞子噴き」(2026-11-12。仕様 Vault「G1 豚(1 段目)」3 節【決定 11-09・数は仮】)。
 * 喉袋を縮めて、前へ短い胞子の噴流を吐く({@link G1SporeCone})。
 *
 * <ul>
 *   <li>使う間合い: 標的が {@value #MIN_RANGE}〜{@value #MAX_RANGE} ブロックで正面(±{@value #FRONT_DEG}°)(仕様「2〜3 ブロックで正面なら (c)」)。</li>
 *   <li>段: puff_windup 15(首を下げ、喉袋が 2 回脈打ち、えずくように縮む。11 tick まで向きを追う)→ puff 8(1 tick 目に吐く)→ puff_recover 12。</li>
 *   <li>円錐: 長さ {@value #LENGTH}・幅 60°(±{@value #HALF_ANGLE}°)。中にいれば暴露量 +{@value #DOSE}。先の升へ少し降り積もる({@value #DEPOSIT})。</li>
 *   <li>冷却 {@value #COOLDOWN} tick(8 秒)。</li>
 * </ul>
 * 数値はすべて【仮】(このクラスの定数)。
 */
public class G1ThroatPuffGoal extends G1TechniqueGoal {

    static final double MIN_RANGE = 1.8D;
    static final double MAX_RANGE = 3.2D;
    static final float FRONT_DEG = 45.0F;
    static final double LENGTH = 3.0D;
    static final double HALF_ANGLE = 30.0D;
    static final double DOSE = 250.0D;
    /** 降り積もり(場の単位。発芽のしきい値 0.05 の 1/10)。 */
    static final double DEPOSIT = 0.005D;
    static final int COOLDOWN = 160;

    public G1ThroatPuffGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.PUFF_WINDUP, G1Move.PUFF, G1Move.PUFF_RECOVER}, new int[]{15, 8, 12});
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
        return 11;
    }

    @Override
    protected int cooldown() {
        return COOLDOWN;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (stageIndex == 0 && (tickInStage == 3 || tickInStage == 9)) {
            mob.level().playSound(null, mob.getX(), mob.getY() + 0.6D, mob.getZ(), SoundEvents.PIG_HURT, SoundSource.HOSTILE, 0.5F, 0.45F);
        }
        if (stageIndex == 1 && tickInStage == 1 && mob.level() instanceof ServerLevel level) {
            G1SporeCone.puff(level, mob, lockedYaw, LENGTH, HALF_ANGLE, DOSE, DEPOSIT);
        }
    }
}
