package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModSounds;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * ゾンビの 1 段目の (c)「肩の蔦で絡める」(2026-11-12。仕様 Vault「G1 ゾンビ(1 段目)」3 節【決定 11-09 ユーザー「良いです」・数は【仮】】)。
 * 左肩の蔦を伸ばして標的の脚に巻き付け、引き寄せる。巻かれている間は遅くなる。
 *
 * <ul>
 *   <li>使う間合い: 標的が {@value #MIN_RANGE}〜{@value #REACH} ブロック・見えている(仕様「(c) は間合い 3〜5 ブロックのときだけ」)。</li>
 *   <li>段: vine_windup 16(肩の蔦が持ち上がってうねる。12 tick までは向きを追い、そこから止める)→ vine_lash 4(伸びる。終わりの tick に当たり)
 *   → vine_recover 10(引く)。</li>
 *   <li>当たり: 止めた向きの ±{@value #CONE_DEG}°・届く距離 {@value #REACH} + 0.5 の中(蔦はまっすぐ伸びる = 横へ出ればよけられる)。
 *   盾を構えて正面で受けたら弾く。</li>
 *   <li>巻かれたら({@link G1Tether}): 最大 {@value #HOLD_TICKS} tick・鈍足 III(−45%。仕様の −40% に近い段)・毎 tick {@value #PULL} ブロック引く。
 *   ゾンビを殴れば切れる・{@value #BREAK_DISTANCE} ブロックより離れれば振りほどける。</li>
 *   <li>冷却 {@value #COOLDOWN} tick(8 秒)。</li>
 * </ul>
 * 数値はすべて【仮】(このクラスの定数。設計データ・config には出していない)。
 */
public class G1VineLashGoal extends G1TechniqueGoal {

    static final double MIN_RANGE = 3.0D;
    static final double REACH = 5.0D;
    static final float CONE_DEG = 25.0F;
    static final int HOLD_TICKS = 40;
    static final double PULL = 0.12D;
    static final int SLOW_AMPLIFIER = 2;
    static final double BREAK_DISTANCE = 7.0D;
    static final int COOLDOWN = 160;

    public G1VineLashGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.VINE_WINDUP, G1Move.VINE_LASH, G1Move.VINE_RECOVER}, new int[]{16, 4, 10});
    }

    @Override
    protected boolean ready() {
        if (target == null || !target.isAlive() || !mob.canAttack(target) || mob.tether() != null) return false;
        double d = mob.distanceTo(target);
        return d >= MIN_RANGE && d <= REACH && mob.getSensing().hasLineOfSight(target);
    }

    @Override
    protected int aimUntil() {
        return 12;
    }

    @Override
    protected int cooldown() {
        return COOLDOWN;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (stageIndex == 0 && tickInStage == 1) {
            mob.level().playSound(null, mob.getX(), mob.getY() + 1.5D, mob.getZ(), ModSounds.G1_VINE_LASH.get(), SoundSource.HOSTILE, 1.0F, 0.6F);
        }
        if (stageIndex == 1 && tickInStage == 4) {
            lash();
        }
    }

    private void lash() {
        LivingEntity t = target;
        mob.level().playSound(null, mob.getX(), mob.getY() + 1.5D, mob.getZ(), ModSounds.G1_VINE_LASH.get(), SoundSource.HOSTILE, 1.0F, 1.2F);
        if (t == null || !t.isAlive() || !inLine(t)) return;
        if (t.isBlocking() && facing(t)) {
            mob.level().playSound(null, t.getX(), t.getY() + 1.0D, t.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0F, 1.0F);
            return;
        }
        mob.startTether(new G1Tether(mob, t, HOLD_TICKS, PULL, SLOW_AMPLIFIER, BREAK_DISTANCE));
    }

    /** 止めた向きのまっすぐな線の上(±CONE_DEG°・REACH + 0.5)にいて、見えているか。 */
    boolean inLine(LivingEntity t) {
        double dx = t.getX() - mob.getX();
        double dz = t.getZ() - mob.getZ();
        if (dx * dx + dz * dz > (REACH + 0.5D) * (REACH + 0.5D)) return false;
        float to = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        return Math.abs(Mth.wrapDegrees(to - lockedYaw)) <= CONE_DEG && mob.getSensing().hasLineOfSight(t);
    }

    /** 盾を構えた相手がゾンビの方を向いているか(盾は正面だけ受ける)。 */
    private boolean facing(LivingEntity t) {
        Vec3 look = t.getViewVector(1.0F).multiply(1.0D, 0.0D, 1.0D).normalize();
        Vec3 toMob = mob.position().subtract(t.position()).multiply(1.0D, 0.0D, 1.0D).normalize();
        return look.dot(toMob) > 0.0D;
    }
}
