package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * クモの 1 段目の (c)「根糸を吐く」(2026-11-13、能力の第 4 回。仕様 Vault「G1 クモ(1 段目)」3 節【決定 11-09・数は仮】)。
 * 腹(糸いぼのあった所)を持ち上げて相手へ向け、袋が膨らみ(前触れ {@value #WINDUP} tick)、根の繊維の束 {@link G1RootsilkBolt} を 1 本吐く。
 * 当たると絡まって遅くなり跳べない({@link G1Tangle})+ 暴露量。外れると着いた所に根糸の巣({@code RootsilkWebBlock})。
 *
 * <ul>
 *   <li>使う条件: 見えている標的が {@value #MIN_RANGE}〜{@value #MAX_RANGE} ブロック(仕様「6〜10 ブロックなら (c) で止めて詰める」)。間 {@value #COOLDOWN} tick。</li>
 *   <li>段: spit_windup {@value #WINDUP}({@value #AIM_UNTIL} tick まで向きを追う)→ spit 3(1 tick 目に吐く)→ spit_recover 12。</li>
 *   <li>吐く向きは<b>止めた体の向き</b>。弾はほぼまっすぐ・速さ {@value #SPEED}。横へ動けばよけられる。盾で受ければ絡まらない。</li>
 * </ul>
 */
public class G1SpitGoal extends G1TechniqueGoal {

    public static final double MIN_RANGE = 6.0D;
    public static final double MAX_RANGE = 10.0D;
    public static final int WINDUP = 15;
    static final int AIM_UNTIL = 12;
    public static final int COOLDOWN = 160;
    public static final float SPEED = 1.2F;
    /** ばらつき(バニラの shoot の inaccuracy)。 */
    static final float SPREAD = 1.0F;
    public static final int SLOW_TICKS = 40;
    public static final double DOSE = 300.0D;

    public G1SpitGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.SPIT_WINDUP, G1Move.SPIT, G1Move.SPIT_RECOVER}, new int[]{WINDUP, 3, 12});
    }

    @Override
    protected boolean ready() {
        if (target == null || !target.isAlive() || !mob.canAttack(target) || !mob.getSensing().hasLineOfSight(target)) return false;
        double d = mob.distanceTo(target);
        return d >= MIN_RANGE && d <= MAX_RANGE;
    }

    @Override
    protected int aimUntil() {
        return AIM_UNTIL;
    }

    @Override
    protected int cooldown() {
        return COOLDOWN;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (stageIndex == 0 && tickInStage == 3) {
            // 腹の袋が膨らむ
            level.playSound(null, mob.getX(), mob.getY() + 0.8D, mob.getZ(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.HOSTILE, 0.7F, 0.6F);
        } else if (stageIndex == 1 && tickInStage == 1) {
            spit(level);
        }
    }

    private void spit(ServerLevel level) {
        Vec3 face = Vec3.directionFromRotation(0.0F, lockedYaw);
        // 持ち上げた腹の先から
        Vec3 from = mob.position().add(face.x * 0.4D, mob.getBbHeight() * 0.95D, face.z * 0.4D);
        // 向きは止めた体の向き。距離と高さは標的から(いなければ 8 ブロック先の同じ高さ)
        double dist = 8.0D, dy = 0.0D;
        if (target != null && target.isAlive()) {
            double tx = target.getX() - from.x, tz = target.getZ() - from.z;
            dist = Math.max(1.0D, Math.sqrt(tx * tx + tz * tz));
            dy = target.getY() + target.getBbHeight() * 0.5D - from.y;
        }
        G1RootsilkBolt bolt = new G1RootsilkBolt(level, mob).setLoad(DOSE, SLOW_TICKS);
        bolt.setPos(from);
        bolt.shoot(face.x * dist, dy + dist * G1RootsilkBolt.DROP_PER_BLOCK, face.z * dist, SPEED, SPREAD);
        level.addFreshEntity(bolt);
        mob.countRootsilkShot(bolt);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.LLAMA_SPIT, SoundSource.HOSTILE, 0.9F, 0.6F);
    }
}
