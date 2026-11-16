package com.yayuy.overgrownvegetation.entity.leap;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 「跳ぶ型」の 1 回の跳びの進み(サーバー。2026-11-14、サルティムネスの段 1。種に依らない)。
 * <b>身を沈める(CROUCH)→ 宙(AIR)→ 着地(LAND)</b>。宙で何かにぶつかったら弧をやめてふつうに落ち(FALL)、地面に着いたら着地。
 *
 * <h2>使い方(種のクラスから)</h2>
 * <ul>
 *   <li>{@link #start} で始める(経路は止める)。身を沈める間、種は {@link #yaw()} へ体を回す。向きが {@link #ALIGN_DEG} 度以内に揃ってから
 *       {@link LeapSpec#crouchTicks} 数えて蹴る。</li>
 *   <li>毎 tick、バニラの移動({@code aiStep} の中の {@code travel})の<b>前</b>に {@link #preMove}、<b>後</b>に {@link #postMove} を呼ぶ。</li>
 *   <li>宙の間は毎 tick、弧の次の点との差をそのまま速度に入れる(バニラの空気抵抗では遠くへ届かないため。重力は移動の後に引かれるので、
 *       その tick の動きには効かない = {@code NoGravity} を使わずに済み、途中で保存されても浮いたままにならない)。</li>
 *   <li>自分の跳びでは落下ダメージを受けない(宙・落ち・着地の間は落ちた距離を 0 に戻す)。</li>
 *   <li>描画へは {@link #encode} を同期の値で送る(段・宙の tick 数)。</li>
 * </ul>
 */
public final class LeapMotion {

    public static final int NONE = 0, CROUCH = 1, AIR = 2, FALL = 3, LAND = 4;
    /** 蹴る前に体の向きが揃ったとみなすずれ(度)。 */
    public static final float ALIGN_DEG = 8.0F;
    /** 弧からこれ(ブロック)以上ずれたら、ぶつかったとみなして弧をやめる。 */
    static final double ABORT_OFF = 0.3D;
    /** 向きが揃わないまま身を沈めていてよい上限(tick)。過ぎたら跳ばずにやめる。 */
    static final int ALIGN_GIVE_UP = 60;
    /**
     * 段ごとの時間切れ(tick)【仮】(2026-11-16 コードの見直し ①2)。身を沈める段は「蹴るまでの長さ + 向きを揃える上限 + これ」を、揃い・不揃いに関係なく通しで数える
     * (水に浮く・クモの巣・段差の途中で接地しないと、向きは揃っても蹴れず永遠に構えていた)。落ちる段はこれを過ぎたら着いたことにする
     * (クモの巣・つる・ほかの Mob の上で接地しないまま止まる)。
     */
    public static final int CROUCH_GIVE_UP = 20, FALL_GIVE_UP = 200;

    private final LeapSpec spec;
    private int phase = NONE;
    private int t;
    private int air;
    /** 今の段に入ってからの tick(時間切れを数える。身を沈める・落ちる段)。 */
    private int stageAge;
    @Nullable
    private LeapPlan plan;
    private Vec3 expected = Vec3.ZERO;
    /** 跳んだ回数・最後の着地がぶつかって落ちたものか(テスト・確かめ用)。 */
    private int leaps;
    private boolean lastAborted;

    public LeapMotion(LeapSpec spec) {
        this.spec = spec;
    }

    public LeapSpec spec() {
        return this.spec;
    }

    public int phase() {
        return this.phase;
    }

    public boolean active() {
        return this.phase != NONE;
    }

    /** 宙にいるか(弧を飛んでいる・ぶつかって落ちている)。 */
    public boolean airborne() {
        return this.phase == AIR || this.phase == FALL;
    }

    @Nullable
    public LeapPlan plan() {
        return this.plan;
    }

    /** 身を沈めている間に向きたい向き(度)。 */
    public float yaw() {
        return this.plan == null ? 0.0F : this.plan.yaw();
    }

    public int leaps() {
        return this.leaps;
    }

    public boolean lastAborted() {
        return this.lastAborted;
    }

    /** 跳びを始める(身を沈めるところから)。沈む・着地の長さと向きの揃えは {@link LeapSpec} のまま。 */
    public void start(Mob mob, LeapPlan plan) {
        start(mob, plan, this.spec.crouchTicks(), this.spec.landTicks(), ALIGN_DEG);
    }

    /**
     * 跳びを技ごとの長さで始める(2026-11-15、跳び抱え・連続跳躍のため)。
     *
     * @param crouchTicks 向きが揃ってから蹴るまで(0 なら揃った tick に蹴る)
     * @param landTicks   着地の段の長さ(0 なら着いた tick に終わる。続けて次の跳びへ移るとき)
     * @param alignDeg    蹴ってよい体の向きのずれ(度)。横へ跳ぶ連続跳躍は広く
     */
    public void start(Mob mob, LeapPlan plan, int crouchTicks, int landTicks, float alignDeg) {
        this.plan = plan;
        this.phase = CROUCH;
        this.t = 0;
        this.air = 0;
        this.stageAge = 0;
        this.crouchTicks = crouchTicks;
        this.landTicks = landTicks;
        this.alignDeg = alignDeg;
        this.landed = false;
        mob.getNavigation().stop();
    }

    private int crouchTicks, landTicks;
    private boolean landed;

    /** 着地した tick の後に 1 回だけ true(技が着地を受けて次へ移るため)。 */
    public boolean consumeLanded() {
        boolean l = this.landed;
        this.landed = false;
        return l;
    }
    private float alignDeg = ALIGN_DEG;

    /** 身を沈める段で、向きが揃って数え始めたか(描画へ沈む姿を送ってよいか)。 */
    public boolean crouchCounting() {
        return this.phase == CROUCH && this.t > 0;
    }

    /** 宙に出てからの tick(AIR の間)。 */
    public int airTicks() {
        return this.air;
    }

    /** 跳びをやめる(宙なら落ちるに任せる)。 */
    public void cancel() {
        if (this.phase == CROUCH || this.phase == LAND) this.phase = NONE;
        else if (this.phase == AIR) fall();
    }

    private void fall() {
        this.phase = FALL;
        this.stageAge = 0;
    }

    /** バニラの移動の前に呼ぶ。{@code currentYaw} は今の体の向き(度)。 */
    public void preMove(Mob mob, float currentYaw) {
        if (this.phase == NONE) return;
        if (!mob.isAlive() || mob.isPassenger()) {
            this.phase = NONE;
            return;
        }
        switch (this.phase) {
            case CROUCH -> {
                Vec3 v = mob.getDeltaMovement();
                mob.setDeltaMovement(0.0D, v.y, 0.0D);
                if (++this.stageAge > Math.max(1, this.crouchTicks) + ALIGN_GIVE_UP + CROUCH_GIVE_UP) {
                    this.phase = NONE;   // 接地しないまま蹴れない: 跳ばずにやめる
                    return;
                }
                if (Math.abs(Mth.wrapDegrees(yaw() - currentYaw)) <= this.alignDeg) {
                    this.t++;
                } else if (++this.air > ALIGN_GIVE_UP) {
                    this.phase = NONE;
                    return;
                }
                if (this.t >= Math.max(1, this.crouchTicks) && mob.onGround()) {
                    // 蹴る: 身を沈めている間にずれたぶん、出る所を今の位置に置き直す(弧の形はそのまま)
                    LeapPlan p = this.plan;
                    this.plan = new LeapPlan(mob.position(), p.to(), p.k(), p.ticks(), p.yaw());
                    this.phase = AIR;
                    this.air = 0;
                    this.leaps++;
                    this.lastAborted = false;
                    mob.getNavigation().stop();
                    airStep(mob);   // 蹴った tick から宙の 1 歩目を進める(後の postMove が弧の点と比べるため)
                }
            }
            case AIR -> airStep(mob);
            case FALL -> {
                mob.fallDistance = 0.0F;
                if (++this.stageAge > FALL_GIVE_UP) land(mob);   // 接地しないまま止まった: 着いたことにする
            }
            case LAND -> {
                Vec3 v = mob.getDeltaMovement();
                mob.setDeltaMovement(v.x * 0.3D, v.y, v.z * 0.3D);
                mob.fallDistance = 0.0F;
            }
            default -> {
            }
        }
    }

    private void airStep(Mob mob) {
        this.air++;
        double s = Math.min(1.0D, this.air / (double) this.plan.ticks());
        this.expected = this.plan.pos(s);
        mob.setDeltaMovement(this.expected.subtract(mob.position()));
        mob.fallDistance = 0.0F;
    }

    /** バニラの移動の後に呼ぶ。 */
    public void postMove(Mob mob) {
        switch (this.phase) {
            case AIR -> {
                mob.fallDistance = 0.0F;
                double s = this.air / (double) this.plan.ticks();
                double off = Math.sqrt(sq(mob.getX() - this.expected.x) + sq(mob.getZ() - this.expected.z));
                boolean blockedUp = mob.getY() < this.expected.y - ABORT_OFF;
                boolean groundEarly = mob.onGround() && this.air > 2 && mob.getY() > this.expected.y + 0.05D;
                if (mob.isInWater() || mob.isInLava()) {
                    fall();
                    this.lastAborted = true;
                } else if (s >= 1.0D || mob.onGround() && this.air > 2 && s > 0.6D) {
                    land(mob);
                } else if (off > ABORT_OFF || blockedUp || groundEarly) {
                    // ぶつかった: 弧をやめ、横の勢いを捨てて落ちる
                    fall();
                    this.lastAborted = true;
                    Vec3 v = mob.getDeltaMovement();
                    mob.setDeltaMovement(v.x * 0.2D, Math.min(0.0D, v.y), v.z * 0.2D);
                }
            }
            case FALL -> {
                mob.fallDistance = 0.0F;
                if (mob.onGround() || mob.isInWater() || mob.isInLava()) land(mob);
            }
            case LAND -> {
                if (++this.t >= this.landTicks) this.phase = NONE;
            }
            default -> {
            }
        }
    }

    private void land(Mob mob) {
        this.landed = true;
        this.phase = LAND;
        this.t = 0;
        mob.fallDistance = 0.0F;
        Vec3 v = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0D, Math.min(0.0D, v.y), 0.0D);
    }

    /** 描画へ送る値: 下 3 ビットが段、その上が宙の tick 数。 */
    public int encode() {
        return this.phase | (this.plan == null ? 0 : Math.min(this.plan.ticks(), 255) << 3);
    }

    public static int decodePhase(int v) {
        return v & 7;
    }

    public static int decodeTicks(int v) {
        return v >>> 3;
    }

    private static double sq(double v) {
        return v * v;
    }
}
