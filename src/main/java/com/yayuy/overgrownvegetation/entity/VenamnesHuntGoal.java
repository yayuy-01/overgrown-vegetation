package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.entity.action.ActionDef;
import com.yayuy.overgrownvegetation.entity.runner.ChasePathBudget;
import com.yayuy.overgrownvegetation.entity.runner.ChaseSteer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * ヴェナムネスが標的を追い、技を選ぶ Goal(2026-11-15 段 A)。技の最中は何もしない(技は {@link VenamnesEntity#tickAction} が進める)。
 * MOVE と LOOK を握る(溜めの最中にうろつき・見回しが割り込まない)。JUMP は握らない(水で浮く Goal に追い出されるため。記憶 huntgoal-jump-flag)。
 *
 * <h2>追い方【仮・Claude 11-15】</h2>
 * <ul>
 *   <li><b>まっすぐの舵</b>: 標的が見えていて、体の幅の帯に段差・幹・水が無い({@link ChaseSteer}、{@link #STEER_LOOK} ブロック先まで、
 *       {@link #STEER_EVERY} tick おきに見る)間は、経路を持たずに標的へ直に舵を取る。経路の点をまたぐたびの左右の振れ(段 1b の
 *       「走りが 2〜3 秒ごとに歩きへ落ちる」)が出ない。{@link #STUCK_TICKS} tick で 0.5 ブロックも進めなければ {@link #STUCK_BAN} tick は経路に任せる。</li>
 *   <li><b>経路</b>: それ以外は {@link #REPATH} tick おきに引き直す。引き直しは {@link ChasePathBudget}(サーバー全体 2 ms/tick)に通し、
 *       探す数を {@link #NODE_MULT} 倍に絞る(1 回の探索の重さを抑える)。届かなかったのと同じマス同士は {@link PathRetryMemo} で省く。</li>
 * </ul>
 * 確かめ用: 名前に {@code plainchase} を含む個体は、舵・予算・探す数の絞りを使わない(直す前の重さを同じ場面で測る)。
 */
final class VenamnesHuntGoal extends Goal {

    /** 引き直しの間隔(tick)【仮、ファルキムネスと同じ】。 */
    static final int REPATH = 8;
    /** まっすぐ走れるかを見る間隔(tick)と、見る長さ(ブロック)【仮】。 */
    static final int STEER_EVERY = 5;
    static final double STEER_LOOK = 24.0D;
    /** 標的との高さの差がこれ以内のときだけまっすぐ走る(ブロック)【仮】。 */
    static final double STEER_DY = 1.5D;
    /** まっすぐ走っていて詰まったとみる長さ(tick)と、その後に経路へ任せる長さ(tick)【仮】。 */
    static final int STUCK_TICKS = 20, STUCK_BAN = 60;
    /**
     * 追うときの探す数の倍率(追う距離 48 なら 768 → 230 マス)【仮】。11-15 の計測: 0.5(384 マス)でも 1 回が最悪 12 ms
     * (長い体の重みの付いた探索は 1 マス 30 µs ほど)だったので 0.3 に。遠い標的は途中まで引ければよい(近づけばまた引く)。
     */
    static final float NODE_MULT = 0.3F;
    /** 標的がこれより遠い(ブロック)ときの引き直しの間隔(tick)【仮】。遠いほど経路の終わりのずれは小さく見える。 */
    static final double FAR = 16.0D;
    static final int REPATH_FAR = 16;

    private final VenamnesEntity mob;
    private final PathRetryMemo memo = new PathRetryMemo();
    private int repath;
    private int steerCheck;
    private boolean straight;
    private int stuckTicks, steerBan;
    private double stuckX, stuckZ;

    VenamnesHuntGoal(VenamnesEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = this.mob.getTarget();
        return this.mob.isActing() || (t != null && t.isAlive());
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.repath = 0;
        this.steerCheck = 0;
        this.straight = false;
    }

    @Override
    public void stop() {
        this.mob.setProwlMode(VenamnesEntity.PROWL_NONE);
        this.mob.getNavigation().stop();
        this.mob.setSteering(false);
        this.straight = false;
    }

    @Override
    public void tick() {
        if (this.mob.isActing()) {
            this.mob.setSteering(false);
            return;
        }
        LivingEntity t = this.mob.getTarget();
        if (t == null || !t.isAlive()) {
            this.mob.setSteering(false);
            return;
        }
        trackTarget(t);
        double d = Math.sqrt(this.mob.distanceToSqr(t.getX(), this.mob.getY(), t.getZ()));
        double dy = t.getY() - this.mob.getY();
        boolean sees = this.mob.getSensing().hasLineOfSight(t);
        if (this.mob.isHuntSprinting()) {
            sprint(t, d, dy, sees);
            return;
        }
        float off = Math.abs(this.mob.offFront(t));
        double s = this.mob.getScale();
        double biteStart = VenamnesEntity.BITE_START * s + t.getBbWidth() * 0.5D;
        double speed = Math.sqrt(sq(this.mob.getX() - this.mob.xo) + sq(this.mob.getZ() - this.mob.zo));
        double tvx = this.tvx, tvz = this.tvz, tSpeed = Math.sqrt(tvx * tvx + tvz * tvz);
        // 相手が離れていく速さ(自分から相手への向きへの、相手の速さの成分)
        double away = d > 1.0E-3D ? (tvx * (t.getX() - this.mob.getX()) + tvz * (t.getZ() - this.mob.getZ())) / d : 0.0D;
        boolean nipping = this.mob.getNip() != VenamnesEntity.NIP_NONE;
        if (prowl(t, d, dy, sees, away)) return;
        this.mob.setProwlMode(VenamnesEntity.PROWL_NONE);

        ActionDef picked = this.mob.picker().pick(VenamnesEntity.normalDefs(), d, s, biteStart, a -> switch (a.id()) {
            case "bite" -> off <= VenamnesEntity.BITE_START_CONE && Math.abs(dy) < VenamnesEntity.BITE_DY;
            case "pounce" -> sees && this.mob.onGround() && off <= VenamnesEntity.POUNCE_START_CONE && Math.abs(dy) < VenamnesEntity.POUNCE_DY
                    && ChaseSteer.clear(this.mob.level(), this.mob.position(), t.position(), this.mob.getBbWidth() * 0.5D, d);
            // 足噛み: 走っている間・相手が首の横の届く所にいる間だけ(走りは止めない)
            case "nip" -> !nipping && speed >= VenamnesEntity.NIP_MIN_SPEED && this.mob.inNipZone(t);
            // 狩りの疾走: 見えている相手が背を向けて離れていく(離れる速さ fleeSpeed 以上)とき
            case "hunt" -> sees && this.mob.onGround() && away >= a.get("fleeSpeed", 0.12D) && Math.abs(dy) < 2.0D;
            default -> false;
        }, this.mob.getRandom());
        if (picked != null) {
            if (!picked.id().equals("nip")) this.mob.setSteering(false);
            this.mob.startSkill(picked, t);
            if (!picked.id().equals("nip")) return;
        }

        // 逃げる相手には、真後ろでなく斜め後ろ(首の横に相手が来る所)へ並びかける = 足噛みの間合い
        boolean fleeing = tSpeed >= FLEE_SPEED && d <= ALONGSIDE_RANGE * s;
        if (fleeing) {
            double ux = tvx / tSpeed, uz = tvz / tSpeed;
            double side = (this.mob.getX() - t.getX()) * -uz + (this.mob.getZ() - t.getZ()) * ux >= 0.0D ? 1.0D : -1.0D;   // 相手の進む線のどちら側にいるか
            Vec3 aim = new Vec3(t.getX() - ux * ALONGSIDE_BACK * s - uz * side * ALONGSIDE_SIDE * s, t.getY(),
                    t.getZ() - uz * ALONGSIDE_BACK * s + ux * side * ALONGSIDE_SIDE * s);
            chase(t, aim.add(ux * tSpeed * 4.0D, 0.0D, uz * tSpeed * 4.0D), dy, sees, 1.0D);   // 4 tick 先へ(並んだまま走る)
            return;
        }
        if (d <= biteStart * 0.9D) {
            // 噛める距離: 止まって向き直る(噛みつきは正面 25 度に入ってから)
            this.mob.getNavigation().stop();
            this.mob.setSteering(false);
            this.mob.wantFacing(this.mob.yawTo(t), VenamnesEntity.TURN_HUNT);
            return;
        }
        chase(t, t.position(), dy, sees, 1.0D);
    }

    // --- 段 B(11-15)【仮・Claude】 ---
    /** 逃げているとみる相手の速さ(ブロック/tick)と、並びかける距離(ブロック。体の真ん中から)。 */
    static final double FLEE_SPEED = 0.1D, ALONGSIDE_RANGE = 12.0D;
    /** 並びかける所: 相手の進む向きの後ろへ・横へ(ブロック)。相手が首の横(前へ 2.5・横へ 1.8)に来る。 */
    static final double ALONGSIDE_BACK = 2.5D, ALONGSIDE_SIDE = 1.8D;
    /** 疾走で相手の先回りをする長さの上限(tick。相手の速さ × 着くまでの tick、これで切る)。 */
    static final double LEAD_MAX = 20.0D;

    /** 相手の速さ(10 tick 前からの位置の差。サーバーではプレイヤーの deltaMovement が当てにならない)。 */
    private double tvx, tvz;
    private final double[] hx = new double[10], hz = new double[10];
    private int hn;
    @org.jetbrains.annotations.Nullable
    private LivingEntity tracked;

    private void trackTarget(LivingEntity t) {
        if (t != this.tracked) {
            this.tracked = t;
            this.hn = 0;
        }
        int i = this.hn % hx.length;
        if (this.hn >= hx.length) {
            this.tvx = (t.getX() - hx[i]) / hx.length;
            this.tvz = (t.getZ() - hz[i]) / hx.length;
        } else {
            this.tvx = this.tvz = 0.0D;
        }
        hx[i] = t.getX();
        hz[i] = t.getZ();
        this.hn++;
    }

    // --- 段 C(11-15): 慎重な個体のつけ回し ---
    /** 待った tick・攻めに行く残りの tick。 */
    private int patience, commit;

    /**
     * 慎重な個体(慎重さ 0.5 より上)がプレイヤーを相手にしている間のつけ回し。戻り値 true ならこの tick はこれで終わり。
     * <ul>
     *   <li><b>背を向けて逃げた</b>(相手の向きと「相手から自分への向き」のずれが backCone 度より大きく、かつ離れる速さ fleeSpeed 以上)瞬間に
     *       狩りの疾走(クールダウンが明けていれば。間合いは見ない)。</li>
     *   <li><b>振り向いた</b>(ずれが watchCone 度以内で、相手から自分が見えている)間は止まって固まる(prowl_freeze)。</li>
     *   <li>それ以外は prowlNear〜prowlFar ブロックを保ってつけ回す(遠ければ prowlSpeed で寄る・近すぎれば止まって向き直る。低い構え prowl)。</li>
     *   <li>殴られた・patienceTicks × 慎重さの片側 待ちきれなくなった → commitTicks の間は大胆な個体と同じに攻めに行く。</li>
     * </ul>
     * 大胆な個体・プレイヤーでない相手には何もしない(false)。
     */
    private boolean prowl(LivingEntity t, double d, double dy, boolean sees, double away) {
        double wary = this.mob.wary();
        if (wary <= 0.0D || !(t instanceof net.minecraft.world.entity.player.Player)) {
            this.patience = 0;
            return false;
        }
        if (this.commit > 0) {
            this.commit--;
            return false;
        }
        int commitTicks = (int) VenamnesEntity.pv("commitTicks", 200.0D);
        boolean hurt = this.mob.getLastHurtByMob() == t && this.mob.tickCount - this.mob.getLastHurtByMobTimestamp() < 40;
        if (hurt || ++this.patience >= (int) Math.round(VenamnesEntity.pv("patienceTicks", 600.0D) * wary)) {
            this.patience = 0;
            this.commit = commitTicks;
            return false;
        }
        float look = lookOff(t);
        boolean backTurned = look > VenamnesEntity.pv("backCone", 110.0D);
        if (backTurned && away >= VenamnesEntity.def("hunt").get("fleeSpeed", 0.12D) && sees && this.mob.onGround()
                && this.mob.picker().ready("hunt")) {
            this.mob.setProwlMode(VenamnesEntity.PROWL_NONE);
            this.mob.setSteering(false);
            this.mob.startSkill(VenamnesEntity.def("hunt"), t);
            this.commit = commitTicks;   // 疾走の後は攻めに行く(息切れの後にすぐつけ回しへ戻らない)
            return true;
        }
        if (look <= VenamnesEntity.pv("watchCone", 35.0D) && t.hasLineOfSight(this.mob)) {
            // 振り向かれた: 止まって固まり、見据える
            hold(t);
            this.mob.setProwlMode(VenamnesEntity.PROWL_FREEZE);
            return true;
        }
        this.mob.setProwlMode(VenamnesEntity.PROWL_STALK);
        double s = this.mob.getScale();
        if (d > VenamnesEntity.pv("prowlFar", 24.0D) * s) {
            chase(t, t.position(), dy, sees, VenamnesEntity.pv("prowlSpeed", 0.72D));
        } else {
            hold(t);   // 近すぎる・ちょうどよい: 止まって向き直る(後ずさりはしない。前へだけ進む体)
        }
        return true;
    }

    private void hold(LivingEntity t) {
        this.mob.getNavigation().stop();
        this.mob.setSteering(false);
        this.mob.wantFacing(this.mob.yawTo(t), VenamnesEntity.TURN_HUNT);
    }

    /** 相手の向き(頭)と「相手から自分への向き」のずれ(度、0〜180)。0 = こちらを真っすぐ見ている、180 = 真後ろを向いている。 */
    float lookOff(LivingEntity t) {
        float toMe = (float) (Math.atan2(this.mob.getZ() - t.getZ(), this.mob.getX() - t.getX()) * (180.0D / Math.PI)) - 90.0F;
        return Math.abs(net.minecraft.util.Mth.wrapDegrees(toMe - t.getYHeadRot()));
    }

    /** 相手の速さ(GameTest 用)。 */
    double[] targetVelocity() {
        return new double[]{this.tvx, this.tvz};
    }

    /**
     * 狩りの疾走で走る(角を切る): 相手が今の速さで進む先(着くまでの tick 分、上限 {@link #LEAD_MAX})へ真っすぐ舵を取る。
     * 間に段差・幹があれば相手へ経路(予算に通す)。最後の飛びかかりへの切り替えは {@code VenamnesEntity#tickAction}。
     */
    private void sprint(LivingEntity t, double d, double dy, boolean sees) {
        double lead = Math.min(LEAD_MAX, d / VenamnesEntity.SPRINT_REF);
        Vec3 aim = new Vec3(t.getX() + this.tvx * lead, t.getY(), t.getZ() + this.tvz * lead);
        if (sees && Math.abs(dy) <= STEER_DY && ChaseSteer.clear(this.mob.level(), this.mob.position(), aim, this.mob.getBbWidth() * 0.5D, STEER_LOOK)) {
            this.mob.getNavigation().stop();
            this.mob.setSteering(true);
            this.mob.setSteerSpeed(1.0D);
            this.mob.getMoveControl().setWantedPosition(aim.x, aim.y, aim.z, 1.0D);
            return;
        }
        this.mob.setSteering(false);
        this.straight = false;
        chase(t, t.position(), dy, false, 1.0D);
    }

    private static double sq(double v) {
        return v * v;
    }

    private void chase(LivingEntity t, Vec3 aim, double dy, boolean sees, double speed) {
        boolean plain = this.mob.devPlainChase();
        if (this.steerBan > 0) this.steerBan--;
        if (!plain && --this.steerCheck <= 0) {
            this.steerCheck = STEER_EVERY;
            this.straight = this.steerBan <= 0 && sees && Math.abs(dy) <= STEER_DY
                    && ChaseSteer.clear(this.mob.level(), this.mob.position(), aim, this.mob.getBbWidth() * 0.5D, STEER_LOOK);
        }
        if (this.straight && !plain) {
            if (!this.mob.isSteering()) {
                this.stuckTicks = 0;
                this.stuckX = this.mob.getX();
                this.stuckZ = this.mob.getZ();
            }
            this.mob.getNavigation().stop();
            this.mob.setSteering(true);
            this.mob.setSteerSpeed(speed);
            this.mob.getMoveControl().setWantedPosition(aim.x, aim.y, aim.z, speed);
            if (++this.stuckTicks >= STUCK_TICKS) {
                double mx = this.mob.getX() - this.stuckX, mz = this.mob.getZ() - this.stuckZ;
                if (mx * mx + mz * mz < 0.25D) {
                    this.straight = false;
                    this.steerBan = STUCK_BAN;
                    this.mob.setSteering(false);
                }
                this.stuckTicks = 0;
                this.stuckX = this.mob.getX();
                this.stuckZ = this.mob.getZ();
            }
            return;
        }
        if (this.mob.isSteering()) {
            this.mob.setSteering(false);
            this.repath = 0;   // まっすぐから経路へ戻った: すぐ引く
        }
        if (--this.repath > 0) return;
        if (plain) {
            this.repath = REPATH;
            this.memo.moveTo(this.mob, t, speed);
            return;
        }
        ServerLevel sl = (ServerLevel) this.mob.level();
        if (!ChasePathBudget.tryStart(sl)) {
            this.repath = 1;   // この tick の予算を使い切った: 次の tick にもう一度聞く(今の経路はそのまま歩く)
            return;
        }
        this.repath = this.mob.distanceToSqr(t) > FAR * FAR ? REPATH_FAR : REPATH;
        long t0 = System.nanoTime();
        this.mob.getNavigation().setMaxVisitedNodesMultiplier(NODE_MULT);
        try {
            this.memo.moveTo(this.mob, t, speed);
        } finally {
            this.mob.getNavigation().resetMaxVisitedNodesMultiplier();
        }
        ChasePathBudget.spend(sl, System.nanoTime() - t0);
    }
}
