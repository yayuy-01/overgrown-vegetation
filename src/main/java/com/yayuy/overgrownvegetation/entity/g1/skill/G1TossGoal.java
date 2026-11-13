package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 豚の 1 段目の (b) の後半「乗り手を前へ放り出す」(2026-11-12。仕様 Vault「G1 豚(1 段目)」3 節【決定 11-09「チビゾンビなど小さな G1 を乗せて運ぶ」・中身は【案】】)。
 * 乗せ方は {@link G1CarryGoal}。
 *
 * <ul>
 *   <li>使う間合い: 背に乗り手がいて、標的が {@value #MIN_RANGE}〜{@value #MAX_RANGE} ブロック・見えている(仕様「4〜6 ブロックで放り出す」)。</li>
 *   <li>段: toss_windup 10(走りながら頭を下げて急に止まり、背を跳ね上げる構え)→ toss 10(2 tick 目に放る)。</li>
 *   <li>放る: 乗り手を降ろし、標的へ向けて弧で投げる(届く距離は標的までの距離、{@value #MAX_THROW} ブロックまで)。
 *   1 段目のチビゾンビは着地して標的が近ければそのまま取り付く({@link DevelopedParasitized#setTossedAt})。段階 0 のチビゾンビは標的を噛みに行く。</li>
 *   <li>冷却 {@value #COOLDOWN} tick(次に乗せるまでの間でもある)。</li>
 * </ul>
 * 数値はすべて【仮】(このクラスの定数)。
 */
public class G1TossGoal extends G1TechniqueGoal {

    static final double MIN_RANGE = 3.5D;
    static final double MAX_RANGE = 6.5D;
    static final double MAX_THROW = 6.0D;
    /** 上向きの速さ(ブロック/tick)。弧の高さ約 1.1 ブロック・滞空約 12 tick。【仮】 */
    static final double THROW_UP = 0.42D;
    /** 空中の横の減り(1 tick に 0.91)を 12 tick 足した倍率。速さ = 距離 ÷ これ。 */
    static final double AIR_TRAVEL = 7.8D;
    static final int COOLDOWN = 100;

    public G1TossGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.TOSS_WINDUP, G1Move.TOSS}, new int[]{10, 10});
    }

    @Override
    protected boolean ready() {
        if (!(mob.getFirstPassenger() instanceof AbstractParasitizedMonster)) return false;
        if (target == null || !target.isAlive() || !mob.canAttack(target) || !mob.getSensing().hasLineOfSight(target)) return false;
        double d = mob.distanceTo(target);
        return d >= MIN_RANGE && d <= MAX_RANGE;
    }

    @Override
    protected int aimUntil() {
        return 8;
    }

    @Override
    protected int cooldown() {
        return COOLDOWN;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (stageIndex == 1 && tickInStage == 2) {
            toss();
        }
    }

    private void toss() {
        Entity rider = mob.getFirstPassenger();
        if (!(rider instanceof AbstractParasitizedMonster small)) return;
        LivingEntity t = target;
        small.stopRiding();
        Vec3 dir;
        double dist;
        if (t != null && t.isAlive()) {
            Vec3 to = t.position().subtract(mob.position()).multiply(1.0D, 0.0D, 1.0D);
            dist = Mth.clamp(to.length() - 0.6D, 1.0D, MAX_THROW);
            dir = to.normalize();
        } else {
            dir = Vec3.directionFromRotation(0.0F, lockedYaw);
            dist = 4.0D;
        }
        double speed = dist / AIR_TRAVEL;
        small.setPos(mob.getX() + dir.x * 0.6D, mob.getY() + mob.getBbHeight(), mob.getZ() + dir.z * 0.6D);
        small.setDeltaMovement(dir.x * speed, THROW_UP, dir.z * speed);
        small.hurtMarked = true;
        small.setYRot((float) (Mth.atan2(dir.z, dir.x) * Mth.RAD_TO_DEG) - 90.0F);
        if (t != null) {
            small.setTarget(t);
            if (small instanceof DevelopedParasitized dev) {
                dev.setTossedAt(t);
            }
        }
        mob.level().playSound(null, mob.getX(), mob.getY() + 1.0D, mob.getZ(), SoundEvents.PIG_SADDLE, SoundSource.HOSTILE, 0.6F, 0.5F);
        mob.level().playSound(null, mob.getX(), mob.getY() + 1.0D, mob.getZ(), SoundEvents.HOGLIN_ATTACK, SoundSource.HOSTILE, 0.5F, 1.3F);
    }
}
