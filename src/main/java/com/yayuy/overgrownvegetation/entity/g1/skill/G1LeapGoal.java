package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModSounds;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1StrikeGoal;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * クモの 1 段目の (b2)「跳びつき」(2026-11-13、能力の第 4 回。仕様 Vault「G1 クモ(1 段目)」3 節【決定 11-09・数は仮】)。
 * 体を沈めて杭の脚 2 本を地面に刺して踏ん張り(前触れ {@value #WINDUP} tick。(a) の「体の前が立つ」と逆の<b>低く沈む</b>前触れ)、
 * 低く跳んで鉤で抱え込む。バニラのクモの跳び(上へ 0.4)を、前触れ付きの 3 段に作り直したもの。
 *
 * <ul>
 *   <li>使う条件: 地面にいて、見えている標的が {@value #MIN_RANGE}〜{@value #MAX_RANGE} ブロック。間 {@value #COOLDOWN} tick。</li>
 *   <li>段: leap_windup {@value #WINDUP}({@value #AIM_UNTIL} tick まで向きを追う)→ leap {@value #FLIGHT} → leap_recover {@value #RECOVER}。</li>
 *   <li>跳ぶ向きは<b>止めた体の向き</b>(まっすぐ跳ぶ = 横へ出ればよけられる)。上へ {@value #UP}(バニラ)、水平は距離に合わせて
 *   {@value #H_MIN}〜{@value #H_MAX} ブロック / tick【仮】(バニラは 0.4 固定 = 約 2.7 ブロック。4 ブロック先へ届くように伸ばした)。</li>
 *   <li>跳んでいる間に体が標的に触れたら 1 回だけ当たる: 3 段の 1 撃と同じ当て方(攻撃 4 + 暴露量 +300。盾で防げる)。</li>
 * </ul>
 */
public class G1LeapGoal extends G1TechniqueGoal {

    public static final double MIN_RANGE = 2.0D;
    public static final double MAX_RANGE = 4.0D;
    public static final int WINDUP = G1SkillTiming.LEAP_WINDUP;
    public static final int FLIGHT = 10;
    public static final int RECOVER = 16;
    /** 向きを追う最後の tick = 跳ぶ tick(11-19 溜めを外す 2 便。元 8)。 */
    static final int AIM_UNTIL = WINDUP + 1;
    public static final int COOLDOWN = 120;
    static final double UP = 0.4D;
    static final double H_MIN = 0.4D, H_MAX = 0.62D;
    /** 水平の速さ = 距離 × これ(上の幅に収める)。 */
    static final double H_PER_BLOCK = 0.15D;
    /** 当たりの箱の広げ(ブロック)。 */
    static final double HIT_INFLATE = 0.3D;

    private boolean hit;
    private double launchDist;

    public G1LeapGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.LEAP_WINDUP, G1Move.LEAP, G1Move.LEAP_RECOVER}, new int[]{WINDUP, FLIGHT, RECOVER});
    }

    @Override
    protected boolean ready() {
        if (target == null || !target.isAlive() || !mob.canAttack(target) || !mob.onGround()) return false;
        double d = mob.distanceTo(target);
        return d >= MIN_RANGE && d <= MAX_RANGE && Math.abs(target.getY() - mob.getY()) <= 2.0D && mob.getSensing().hasLineOfSight(target);
    }

    @Override
    protected int aimUntil() {
        return AIM_UNTIL;
    }

    /** 当たる tick まで向き続けるが、1 tick に回れる角度に上限(回り込めばよけられる。11-19 溜めを外す 2 便)。 */
    @Override
    protected float turnRate() {
        return G1SkillTiming.AIM_TURN_DEG;
    }

    @Override
    protected int cooldown() {
        return COOLDOWN;
    }

    @Override
    public void start() {
        super.start();
        hit = false;
        launchDist = target != null ? mob.distanceTo(target) : MAX_RANGE;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (stageIndex == 0) {
            if (target != null && target.isAlive()) launchDist = mob.distanceTo(target);
            if (tickInStage == 2) {
                // 杭の脚 2 本を地面に刺して踏ん張る
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), ModSounds.G1_ROOT_BREAK.get(), SoundSource.HOSTILE, 0.6F, 1.0F);
            }
        } else if (stageIndex == 1) {
            if (tickInStage == 1) {
                Vec3 face = Vec3.directionFromRotation(0.0F, lockedYaw);
                double h = Mth.clamp(launchDist * H_PER_BLOCK, H_MIN, H_MAX);
                mob.setDeltaMovement(face.x * h, UP, face.z * h);
                mob.hasImpulse = true;
                mob.noFallDamageFor(40);
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SPIDER_STEP, SoundSource.HOSTILE, 1.0F, 0.5F);
            }
            if (!hit && target != null && target.isAlive() && mob.getBoundingBox().inflate(HIT_INFLATE).intersects(target.getBoundingBox())) {
                hit = true;
                mob.doHurtTarget(target); // 当たった Mob の感染は ParasitizedAttackInfectionHandler(11-15)
            }
        }
    }

    /** 当たったか(GameTest・確かめ用)。 */
    public boolean hitLast() {
        return hit;
    }
}
