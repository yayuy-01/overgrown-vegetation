package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.entity.action.ActionDef;
import com.yayuy.overgrownvegetation.entity.runner.ChasePathBudget;
import com.yayuy.overgrownvegetation.entity.runner.ChaseSteer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

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
        double d = Math.sqrt(this.mob.distanceToSqr(t.getX(), this.mob.getY(), t.getZ()));
        double dy = t.getY() - this.mob.getY();
        boolean sees = this.mob.getSensing().hasLineOfSight(t);
        float off = Math.abs(this.mob.offFront(t));
        double s = this.mob.getScale();
        double biteStart = VenamnesEntity.BITE_START * s + t.getBbWidth() * 0.5D;

        ActionDef picked = this.mob.picker().pick(VenamnesEntity.normalDefs(), d, s, biteStart, a -> switch (a.id()) {
            case "bite" -> off <= VenamnesEntity.BITE_START_CONE && Math.abs(dy) < VenamnesEntity.BITE_DY;
            case "pounce" -> sees && this.mob.onGround() && off <= VenamnesEntity.POUNCE_START_CONE && Math.abs(dy) < VenamnesEntity.POUNCE_DY
                    && ChaseSteer.clear(this.mob.level(), this.mob.position(), t.position(), this.mob.getBbWidth() * 0.5D, d);
            default -> false;
        }, this.mob.getRandom());
        if (picked != null) {
            this.mob.setSteering(false);
            this.mob.startSkill(picked, t);
            return;
        }

        if (d <= biteStart * 0.9D) {
            // 噛める距離: 止まって向き直る(噛みつきは正面 25 度に入ってから)
            this.mob.getNavigation().stop();
            this.mob.setSteering(false);
            this.mob.wantFacing(this.mob.yawTo(t), VenamnesEntity.TURN_HUNT);
            return;
        }
        chase(t, dy, sees);
    }

    private void chase(LivingEntity t, double dy, boolean sees) {
        boolean plain = this.mob.devPlainChase();
        if (this.steerBan > 0) this.steerBan--;
        if (!plain && --this.steerCheck <= 0) {
            this.steerCheck = STEER_EVERY;
            this.straight = this.steerBan <= 0 && sees && Math.abs(dy) <= STEER_DY
                    && ChaseSteer.clear(this.mob.level(), this.mob.position(), t.position(), this.mob.getBbWidth() * 0.5D, STEER_LOOK);
        }
        if (this.straight && !plain) {
            if (!this.mob.isSteering()) {
                this.stuckTicks = 0;
                this.stuckX = this.mob.getX();
                this.stuckZ = this.mob.getZ();
            }
            this.mob.getNavigation().stop();
            this.mob.setSteering(true);
            this.mob.getMoveControl().setWantedPosition(t.getX(), t.getY(), t.getZ(), 1.0D);
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
            this.memo.moveTo(this.mob, t, 1.0D);
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
            this.memo.moveTo(this.mob, t, 1.0D);
        } finally {
            this.mob.getNavigation().resetMaxVisitedNodesMultiplier();
        }
        ChasePathBudget.spend(sl, System.nanoTime() - t0);
    }
}
