package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.entity.leap.LeapMotion;
import com.yayuy.overgrownvegetation.entity.leap.LeapPlan;
import com.yayuy.overgrownvegetation.entity.leap.LeapPlanner;
import com.yayuy.overgrownvegetation.entity.leap.LeapSpec;
import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * サルティムネスが標的を襲う Goal(2026-11-15 段 3 = 能力。「G4〜G6 の能力の決定(11-14)」のサルティムネスの節)。
 * 技の段はここで進め、描画へは {@link SaltimnesEntity#setAct} で送る。MOVE と LOOK を握る(JUMP は握らない: 水で浮く Goal に追い出される)。
 *
 * <h2>技【時刻はアニメと対・数値は仮】</h2>
 * <ul>
 *   <li><b>跳び抱え</b>(通常 ①): 前 ±{@link #FRONT_DEG} 度・鼻先から {@link #POUNCE_REACH} ブロック以内の相手へ。{@code pounce_windup}(補間 3 + 16 tick。
 *       脚の葉脈と T 字の口が光りだす)→ 相手の手前(首の下が相手に来る所)へ跳ぶ → 宙か着いた所で相手が首の下にいれば押さえ込み({@code pin} 40 tick、
 *       噛み 4 回)→ {@code pin_recover}。外れたら {@code pounce_recover}(24 tick の隙)。</li>
 *   <li><b>後ろ足の蹴り</b>(通常 ③): 体の後ろ 4.4〜5.6 ブロック(± 少し)にいる相手だけ。{@code kick_windup}(補間 3 + 8)→ {@code kick} の 2 tick 目に当たり、
 *       後ろへ弾き飛ばす → {@code kick_recover}。光らない。</li>
 *   <li><b>連続跳躍</b>(特殊): {@link SpecialPacing}(交戦 6 秒 + 通常 2 回で解禁、35%)で、通常攻撃の代わりに。{@code zigzag_windup}(補間 3 + 20、光が脈打つ)
 *       → 相手の左右へ交互に 2 回跳ぶ(着くたび {@code zigzag_touch} 6 tick)→ 3 回目は跳び抱えで押さえ込む。</li>
 *   <li><b>跳び越え</b>(通常 ②): 近づく道が柵・塀・段差で切れていれば、相手の方へ移動の跳び(光らない)。遠い相手へも跳んで詰める。</li>
 *   <li><b>化けて待つ</b>: 伏せている(化けている)間に標的ができても、鼻先から {@link #POUNCE_REACH} に入るまで {@link #PATIENCE} tick は動かない。
 *       殴られたら化けを解く。</li>
 * </ul>
 */
final class SaltimnesHuntGoal extends Goal {

    /** 跳び抱えの届く所: 前 ± 度・鼻先から(ブロック)【仮。決定「6 ブロック以内」】。 */
    static final float FRONT_DEG = 40.0F;
    static final double POUNCE_REACH = 6.0D;
    /** 押さえ込んだ相手を置く所(体の中心から前へ、ブロック。決定「首の下 前 1.8〜2.55」)と、捕まえたとみる箱(前・左右・上下)。 */
    static final double PIN_FORWARD = 2.2D;
    static final double PIN_F_MIN = 0.6D, PIN_F_MAX = 3.8D, PIN_LAT = 1.8D, PIN_DY_MIN = -1.5D, PIN_DY_MAX = 2.4D;
    /** 蹴りの届く所(体の後ろ、ブロック。決定「4.4〜5.6」に箱の幅を足した)と左右(決定 ±2.8)。 */
    static final double KICK_B_MIN = 3.6D, KICK_B_MAX = 6.4D, KICK_LAT = 3.0D;
    /** 時刻(tick)。アニメの長さと対(補間 3 込み)。 */
    static final int LEAD = 3, POUNCE_WINDUP = LEAD + 16, PIN = 40, PIN_RECOVER = 18, POUNCE_RECOVER = 24,
            KICK_WINDUP = LEAD + 8, KICK = 5, KICK_HIT = 2, KICK_RECOVER = 14, ZIG_WINDUP = LEAD + 20, ZIG_TOUCH = 6;
    /** 噛みの時刻(pin の頭から)と 1 回の傷【仮】。蹴りの傷は攻撃の属性。 */
    static final int[] BITES = {10, 19, 28, 37};
    static final float BITE_DAMAGE = 3.0F;
    /** 押さえ込みの間にこれだけ傷を受けたら放す【仮】(反撃で逃れられる)。 */
    static final float PIN_BREAK = 6.0F;
    /** 蹴りの弾き(横・上)【仮】。 */
    static final double KICK_PUSH = 1.6D, KICK_LIFT = 0.45D;
    /** 待ち(tick)【仮】: 跳び抱え・蹴りの後・移動の跳びの間・化けて待つ長さ。 */
    static final int POUNCE_COOLDOWN = 40, KICK_COOLDOWN = 40, MOVE_LEAP_COOLDOWN = 60, PATIENCE = 400;
    /** 連続跳躍: 横へ跳ぶ角度(度)・距離(ブロック)・使う相手の距離(体の中心から、ブロック)【仮】。 */
    static final float ZIG_DEG = 40.0F;
    static final double ZIG_STEP = 4.5D, ZIG_MIN = 6.0D, ZIG_MAX = 13.0D;
    /** 追う速さ(移動の速さに掛ける)と、遠い相手へ跳んで詰める距離・跳んだ後の残り(ブロック)【仮】。 */
    static final double CHASE_SPEED = 0.9D, LEAP_CLOSE_FROM = 12.0D, LEAP_CLOSE_LEAVE = 6.5D;
    /**
     * 1 つの技の段の時間切れ(tick)【仮】(2026-11-16 コードの見直し ①2)。跳び抱え・連続跳躍の宙は跳びの終わりを待つので、
     * 跳びが終わらないと技のまま固まる。一番長い押さえ込み(40)の何倍もあるので普段は効かない。
     */
    static final int ACT_GIVE_UP = 300;

    /**
     * 跳び抱えの跳び: 近い相手にも跳ぶ(下限 0.3)。弧は低め(頂点の上げ 1〜4)。身を沈める・着地の長さは技ごとに渡す。
     */
    static final LeapSpec POUNCE = new LeapSpec(0.3D, 9.0D, 3.0D, 6.0D, 1.0D, 4.0D,
            SaltimnesEntity.LEAP.bodyFront(), SaltimnesEntity.LEAP.bodyBack(), SaltimnesEntity.LEAP.bodyHalfWidth(), SaltimnesEntity.LEAP.gravity(), 0, 0);
    /** 連続跳躍の横への跳び。 */
    static final LeapSpec ZIG = new LeapSpec(2.0D, 7.0D, 2.0D, 4.0D, 1.0D, 3.0D,
            SaltimnesEntity.LEAP.bodyFront(), SaltimnesEntity.LEAP.bodyBack(), SaltimnesEntity.LEAP.bodyHalfWidth(), SaltimnesEntity.LEAP.gravity(), 0, 0);

    private final SaltimnesEntity mob;
    private final SpecialPacing pacing = SpecialPacing.standard();
    private int act = SaltimnesEntity.ACT_NONE, actTicks;
    private int pounceCd, kickCd, leapCd, patience = PATIENCE, chaseTicks, repath;
    /** 跳び抱えの最中に相手を首の下に捕らえたか・連続跳躍の残りの横跳び・押さえ込みの間に受けた傷。 */
    private boolean caught, zigSide, launched;
    private int zigLeft;
    float pinDamage;
    /** テスト用: 次の判断で連続跳躍を出す。 */
    boolean forceZig;
    @Nullable
    private LivingEntity victim;

    SaltimnesHuntGoal(SaltimnesEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = this.mob.getTarget();
        return t != null && t.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return this.act != SaltimnesEntity.ACT_NONE || canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        if (this.act != SaltimnesEntity.ACT_NONE) endAct();
        this.mob.getNavigation().stop();
    }

    int act() {
        return this.act;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        LivingEntity t = this.mob.getTarget();
        this.pacing.tick(t);
        if (this.pounceCd > 0) this.pounceCd--;
        if (this.kickCd > 0) this.kickCd--;
        if (this.leapCd > 0) this.leapCd--;
        if (this.act != SaltimnesEntity.ACT_NONE) {
            this.actTicks++;
            if (this.actTicks > ACT_GIVE_UP) {
                this.mob.leapMotion().cancel();
                endAct();
                this.pounceCd = POUNCE_COOLDOWN;
                return;
            }
            tickAct(t);
            return;
        }
        if (this.mob.leapMotion().active()) return;   // 移動の跳びの最中
        if (t == null || !t.isAlive()) return;
        decide(t);
    }

    /** 体から見た相手の位置 {前, 右, 上, 水平の距離}(ブロック)。 */
    private double[] local(LivingEntity t) {
        float r = this.mob.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(r), fz = Mth.cos(r), rx = -Mth.cos(r), rz = -Mth.sin(r);
        double dx = t.getX() - this.mob.getX(), dz = t.getZ() - this.mob.getZ();
        return new double[]{dx * fx + dz * fz, dx * rx + dz * rz, t.getY() - this.mob.getY(), Math.sqrt(dx * dx + dz * dz)};
    }

    private float yawTo(Vec3 p) {
        return (float) (Mth.atan2(p.z - this.mob.getZ(), p.x - this.mob.getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }

    private void decide(LivingEntity t) {
        double[] l = local(t);
        double front = this.mob.frontReach();
        double nose = l[3] - front;   // 鼻先からの距離(前にいるとき)
        float off = Math.abs(Mth.wrapDegrees(yawTo(t.position()) - this.mob.getYRot()));
        boolean inFront = off <= FRONT_DEG;
        // 化けて待つ: 鼻先から届く所に入るまで動かない(殴られたら解く)
        if (this.mob.isHidden() && this.patience > 0 && !(inFront && nose <= POUNCE_REACH) && this.mob.getLastHurtByMob() != t) {
            this.patience--;
            return;
        }
        this.mob.breakCover();
        // 後ろの相手は蹴る
        if (this.kickCd == 0 && -l[0] >= KICK_B_MIN && -l[0] <= KICK_B_MAX && Math.abs(l[1]) <= KICK_LAT && Math.abs(l[2]) < 2.0D) {
            startAct(SaltimnesEntity.ACT_KICK_WINDUP);
            this.pacing.onNormalAttack();
            return;
        }
        boolean zigRoom = l[3] >= ZIG_MIN && l[3] <= ZIG_MAX;
        if (this.forceZig && inFront) {
            this.forceZig = false;
            if (startZig(t)) return;
        }
        if (inFront && this.pounceCd == 0 && nose <= POUNCE_REACH) {
            if (zigRoom && this.pacing.roll(this.mob.getRandom()) && startZig(t)) return;
            if (startPounce(t, POUNCE_WINDUP, SaltimnesEntity.ACT_POUNCE)) {
                this.pacing.onNormalAttack();
                return;
            }
            this.pounceCd = 20;   // 跳べる所が無い: しばらく寄る
        }
        if (inFront && this.pounceCd == 0 && zigRoom && this.pacing.ready() && this.pacing.roll(this.mob.getRandom()) && startZig(t)) return;
        approach(t, l);
    }

    /** 寄る: 向きを相手へ。遠い・道が切れているなら跳んで詰める(光らない移動の跳び)。そうでなければ這って寄る。 */
    private void approach(LivingEntity t, double[] l) {
        this.mob.wantFacing(yawTo(t.position()), LongBodyRunner.TURN_HUNT);
        if (++this.chaseTicks % 100 == 0) this.pacing.onNormalAttack();   // 届かない相手を追い続けても大技が永遠に出ないことの無いよう
        if (this.leapCd == 0 && l[3] > LEAP_CLOSE_FROM && this.mob.onGround()) {
            this.leapCd = MOVE_LEAP_COOLDOWN;
            Vec3 dir = new Vec3(t.getX() - this.mob.getX(), 0.0D, t.getZ() - this.mob.getZ()).normalize();
            double go = Math.min(l[3] - LEAP_CLOSE_LEAVE, SaltimnesEntity.LEAP.maxDist());
            if (go >= SaltimnesEntity.LEAP.minDist() && this.mob.leapToward(this.mob.position().add(dir.scale(go)).with(net.minecraft.core.Direction.Axis.Y, t.getY()))) return;
        }
        if (--this.repath <= 0) {
            this.repath = 10;
            boolean ok = this.mob.getNavigation().moveTo(t, CHASE_SPEED);
            Path p = this.mob.getNavigation().getPath();
            if ((!ok || p == null || !p.canReach()) && this.leapCd == 0 && this.mob.onGround()) {
                // 柵・塀・段差の向こう: 相手の方へ跳び越える
                this.leapCd = MOVE_LEAP_COOLDOWN;
                Vec3 dir = new Vec3(t.getX() - this.mob.getX(), 0.0D, t.getZ() - this.mob.getZ()).normalize();
                for (double d : new double[]{Math.min(l[3], 9.0D), 7.0D, 5.0D}) {
                    if (d < SaltimnesEntity.LEAP.minDist()) continue;
                    if (this.mob.leapToward(this.mob.position().add(dir.scale(d)).with(net.minecraft.core.Direction.Axis.Y, t.getY()))) return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ 技を始める

    private void startAct(int act) {
        this.act = act;
        this.actTicks = 0;
        this.mob.getNavigation().stop();
        this.mob.setAct(act);
    }

    private void endAct() {
        if (this.mob.isPinning()) this.mob.releasePin();
        this.act = SaltimnesEntity.ACT_NONE;
        this.actTicks = 0;
        this.victim = null;
        this.caught = false;
        this.zigLeft = 0;
        this.mob.setAct(SaltimnesEntity.ACT_NONE);
    }

    /** 跳び抱えを始める(相手の手前 = 首の下が相手に来る所へ)。跳べなければ false。 */
    private boolean startPounce(LivingEntity t, int crouch, int act) {
        if (!this.mob.onGround() || this.mob.isInWater()) return false;   // 浮いていては蹴れない(構えたまま時間切れを繰り返さない。①2)
        Vec3 dir = new Vec3(t.getX() - this.mob.getX(), 0.0D, t.getZ() - this.mob.getZ());
        double d = dir.length();
        dir = d < 1.0E-3D ? new Vec3(0, 0, 1) : dir.scale(1.0D / d);
        double back = PIN_FORWARD * this.mob.getScale();
        Vec3 aim = new Vec3(t.getX() - dir.x * back, t.getY(), t.getZ() - dir.z * back);
        LeapPlan p = LeapPlanner.plan(this.mob, aim, POUNCE);
        if (p == null) return false;
        this.victim = t;
        this.caught = false;
        this.launched = false;
        this.mob.leapMotion().start(this.mob, p, crouch, 0, act == SaltimnesEntity.ACT_POUNCE ? 12.0F : 45.0F);
        startAct(act);
        return true;
    }

    private boolean startZig(LivingEntity t) {
        this.victim = t;
        this.zigLeft = 2;
        this.zigSide = this.mob.getRandom().nextBoolean();
        startAct(SaltimnesEntity.ACT_ZIG_WINDUP);
        this.pacing.onSpecial();
        return true;
    }

    /** 連続跳躍の次の横跳び(相手の方から左右へ交互に {@link #ZIG_DEG} 度)。跳べなければ false。 */
    private boolean zigLeap(LivingEntity t) {
        float toT = yawTo(t.position());
        for (int k = 0; k < 2; k++) {
            float yaw = toT + (this.zigSide ? ZIG_DEG : -ZIG_DEG);
            this.zigSide = !this.zigSide;
            float r = yaw * Mth.DEG_TO_RAD;
            double dist = Math.min(ZIG_STEP, Math.max(ZIG.minDist(), Math.sqrt(this.mob.distanceToSqr(t)) * 0.5D));
            Vec3 aim = this.mob.position().add(-Mth.sin(r) * dist, 0.0D, Mth.cos(r) * dist);
            LeapPlan p = LeapPlanner.plan(this.mob, aim, ZIG);
            if (p != null) {
                this.mob.leapMotion().start(this.mob, p, 1, 0, 60.0F);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 技を進める

    private void tickAct(@Nullable LivingEntity t) {
        LeapMotion lm = this.mob.leapMotion();
        switch (this.act) {
            case SaltimnesEntity.ACT_POUNCE -> {
                if (this.victim != null && lm.phase() == LeapMotion.CROUCH) this.mob.wantFacing(lm.yaw(), LongBodyRunner.TURN_ACTION);
                if (lm.airborne()) this.launched = true;
                if (!this.caught && this.victim != null && lm.airborne() && inPinZone(this.victim)) this.caught = true;
                boolean landed = lm.consumeLanded();
                if (landed || !lm.active()) {
                    if (!landed && !this.launched) {
                        endAct();   // 向きが揃わず・接地できず跳ばなかった
                        this.pounceCd = POUNCE_COOLDOWN;   // すぐ構え直して構えたままに見えないよう(①2)
                        return;
                    }
                    if ((this.caught || this.victim != null && inPinZone(this.victim)) && this.victim != null && this.victim.isAlive()
                            && this.mob.pin(this.victim)) {
                        this.pinDamage = 0.0F;
                        startAct(SaltimnesEntity.ACT_PIN);
                    } else {
                        startAct(SaltimnesEntity.ACT_POUNCE_RECOVER);
                    }
                }
            }
            case SaltimnesEntity.ACT_PIN -> {
                LivingEntity v = this.mob.pinned();
                if (v == null || !v.isAlive() || this.pinDamage >= PIN_BREAK) {
                    this.mob.releasePin();
                    startAct(SaltimnesEntity.ACT_PIN_RECOVER);
                    this.pounceCd = POUNCE_COOLDOWN;
                    return;
                }
                for (int b : BITES) {
                    if (this.actTicks == b) this.mob.bite(v, BITE_DAMAGE);
                }
                if (this.actTicks >= PIN) {
                    this.mob.releasePin();
                    startAct(SaltimnesEntity.ACT_PIN_RECOVER);
                    this.pounceCd = POUNCE_COOLDOWN;
                }
            }
            case SaltimnesEntity.ACT_PIN_RECOVER -> {
                if (this.actTicks >= PIN_RECOVER) endAct();
            }
            case SaltimnesEntity.ACT_POUNCE_RECOVER -> {
                if (this.actTicks >= POUNCE_RECOVER) {
                    endAct();
                    this.pounceCd = POUNCE_COOLDOWN;
                }
            }
            case SaltimnesEntity.ACT_KICK_WINDUP -> {
                if (this.actTicks >= KICK_WINDUP) startAct(SaltimnesEntity.ACT_KICK);
            }
            case SaltimnesEntity.ACT_KICK -> {
                if (this.actTicks == KICK_HIT) this.mob.kickBehind(KICK_B_MIN, KICK_B_MAX, KICK_LAT, KICK_PUSH, KICK_LIFT);
                if (this.actTicks >= KICK) startAct(SaltimnesEntity.ACT_KICK_RECOVER);
            }
            case SaltimnesEntity.ACT_KICK_RECOVER -> {
                if (this.actTicks >= KICK_RECOVER) {
                    endAct();
                    this.kickCd = KICK_COOLDOWN;
                }
            }
            case SaltimnesEntity.ACT_ZIG_WINDUP -> {
                if (this.victim != null) this.mob.wantFacing(yawTo(this.victim.position()), LongBodyRunner.TURN_ACTION);
                if (this.actTicks >= ZIG_WINDUP) nextZig();
            }
            case SaltimnesEntity.ACT_ZIG_AIR -> {
                if (lm.consumeLanded() || !lm.active()) startAct(SaltimnesEntity.ACT_ZIG_TOUCH);
            }
            case SaltimnesEntity.ACT_ZIG_TOUCH -> {
                if (this.victim != null) this.mob.wantFacing(yawTo(this.victim.position()), LongBodyRunner.TURN_ACTION);
                if (this.actTicks >= ZIG_TOUCH) nextZig();
            }
            default -> endAct();
        }
    }

    /** 連続跳躍の次: 横跳びが残っていれば跳び、尽きたら(跳べなければ)跳び抱えで押さえ込みに行く。 */
    private void nextZig() {
        LivingEntity v = this.victim;
        if (v == null || !v.isAlive()) {
            endAct();
            return;
        }
        if (this.zigLeft > 0) {
            this.zigLeft--;
            if (zigLeap(v)) {
                startAct(SaltimnesEntity.ACT_ZIG_AIR);
                return;
            }
        }
        this.zigLeft = 0;
        LivingEntity keep = this.victim;
        if (!startPounce(keep, 1, SaltimnesEntity.ACT_ZIG_POUNCE)) {
            startAct(SaltimnesEntity.ACT_POUNCE_RECOVER);
            return;
        }
        this.act = SaltimnesEntity.ACT_POUNCE;   // 宙から先は跳び抱えと同じ(描画は ACT_ZIG_POUNCE = 溜めの姿を飛ばして pounce から)
    }

    /** 相手が首の下(押さえ込める所)にいるか。 */
    private boolean inPinZone(LivingEntity t) {
        double[] l = local(t);
        double sc = this.mob.getScale();
        return l[0] >= PIN_F_MIN * sc && l[0] <= PIN_F_MAX * sc && Math.abs(l[1]) <= PIN_LAT * sc && l[2] >= PIN_DY_MIN && l[2] <= PIN_DY_MAX;
    }
}
