package com.yayuy.overgrownvegetation.swarm.goal;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmPaths;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * 地上の群れの<b>先導役</b>が歩く Goal(群れ v2、2026-09-25。ユーザー指定「1 匹が先導」)。
 *
 * <p>先導役だけが経路を引く。{@link Swarm#leadGoal()}(狩りなら標的、攻城なら塊、暇なら外への行き先)へ向けて、
 * {@code legMaxLength}(24)ブロックまでの<b>区間</b>を {@link SwarmPaths#requestLeg} で引き、{@link SwarmPaths#followLeg} で歩く
 * (区間には群れの体の包絡が掛かり、通ったノードが足跡になる。{@code navigation.moveTo} を直に呼ぶと足跡が区間から取れない)。
 *
 * <h2>いつ動くか(優先度 4、MOVE)</h2>
 * {@code canUse} と {@code canContinueToUse} は<b>同じ式</b>(毎 tick 群れを引き直す):
 * 地上の群れの先導役で、向かう点があり({@code leadGoal != null}。汚染を広げている間・壁を壊している間は null)、交戦中でない。
 * 後続の Goal({@link SwarmFollowGoal})と同じ優先度・同じ MOVE なので、実行中の片方はもう片方に割り込まれない
 * ({@code WrappedGoal#canBeReplacedBy} は優先度の数字が厳密に小さいときだけ)。だから選び直しで役が替わったら
 * <b>自分で止まる</b>必要がある —— 止まらないと、新しい先導役で後続の Goal が走り続けて区間を引かず、群れが止まる。
 *
 * <h2>1 tick にやること</h2>
 * <ol>
 *     <li>遅れた一員を待つ間({@link Swarm#shouldVanguardWait}、ユーザー指定「勝手に飛び出さず味方を連れてくる」)は止まる。</li>
 *     <li>区間を歩き終えたら、進んだかを {@link SwarmPaths#noteLegOutcome} へ(進まなければ待ち・暇なら向き変え)。
 *     歩いている途中で行き先が {@value #REPLAN_DISTANCE} ブロック以上変わったら(標的が出た・大きく動いた)、報告せずに引き直す。</li>
 *     <li>攻城で区間が続けて塞がれていれば、TD に壊す壁を選ばせる({@link SwarmSiegeHooks.BreachPicker})。</li>
 *     <li>次の区間を頼む。札が無い({@code NO_TOKEN})・体勢が悪い({@code NOT_READY})ときは次の tick にやり直す。</li>
 * </ol>
 * 選び直しで先導役になったときは、前の先導役の区間の写しから歩き始める({@link SwarmPaths#resumeHandedOverLeg})。
 */
public class SwarmLeadGoal extends Goal {

    /** 区間を歩く速さの倍率(群れの歩く速さは司令塔が先導役の移動速度へ掛ける)。 */
    private static final double SPEED = 1.0D;
    /**
     * 区間の行き先にこれだけ近ければ「着いている」とみなし、司令塔が次に考えるまで区間を頼まない(ブロック、水平)。
     * 着いたのに毎 tick 長さ 0 の区間を頼んで、ワールドの札(1 tick 4 本)を食い潰さないため。仮決め 2026-09-25。
     */
    private static final double ARRIVED = 2.0D;
    /** 着いたあと、次に区間を頼むまでの間(tick)= 司令塔が考える間隔ぶん。 */
    private static final int ARRIVED_HOLD_TICKS = 10;
    /**
     * 区間を頼んだときの行き先から、今の行き先がこれだけ(水平、ブロック)ずれたら、歩きかけの区間を捨てて引き直す
     * (暇に歩いている途中で標的が出た・標的が大きく動いた。区間は最大 24 ブロックなので、捨てないと逆向きへ歩き切ってしまう)。
     * 仮決め 2026-09-25。
     */
    private static final double REPLAN_DISTANCE = 8.0D;

    private final Mob mob;

    /** {@link SwarmPaths#followLeg} で区間を歩いている(歩き終えたら結果を報告する)。 */
    private boolean walking;
    /** 歩いている区間を頼んだときの行き先({@link Swarm#leadGoal})。 */
    @Nullable
    private Vec3 requestedGoal;
    /** 次に区間を頼んでよい {@code tickCount}(着いた直後の間)。 */
    private int holdUntil;
    /** 次に壊す壁を選ばせてよい {@code tickCount}。 */
    private int breachCheckAt;

    public SwarmLeadGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return shouldLead(SwarmManager.get(this.mob));
    }

    /** {@link #canUse} と同じ式(API の約束。降ろされた・交戦に入った・行き先が消えたらその tick で止まる)。 */
    @Override
    public boolean canContinueToUse() {
        return shouldLead(SwarmManager.get(this.mob));
    }

    private boolean shouldLead(@Nullable Swarm swarm) {
        return swarm != null && swarm.kind() == Swarm.Kind.GROUND && swarm.isVanguard(this.mob)
                && swarm.leadGoal() != null && !swarm.isVanguardEngaged();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.walking = false;
        this.holdUntil = 0;
        this.requestedGoal = null;
        Swarm swarm = SwarmManager.get(this.mob);
        if (swarm != null && SwarmPaths.resumeHandedOverLeg(this.mob, swarm, SPEED)) {
            this.walking = true;
            // 引き継いだ区間は前の先導役が頼んだもの。今の行き先を控えにする(大きく変わったら引き直す)。
            this.requestedGoal = swarm.leadGoal();
        }
    }

    @Override
    public void stop() {
        // 区間の途中で止めた(交戦・汚染・降ろされた・ほかの Goal に取られた)。結果は報告しない
        // (失敗と数えると、跳び越え・交戦のあとに待ちが付いてしまう)。
        this.walking = false;
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        Swarm swarm = SwarmManager.get(this.mob);
        if (!shouldLead(swarm)) {
            // canContinueToUse は 2 tick に 1 回しか見られない(Mob#serverAiStep は 1 tick おきに tickRunningGoals(false) で、
            // そのときは毎 tick 更新の Goal の tick だけが走る)。選び直し(SwarmController#handOver は LevelTickEvent.Post で
            // 前の先導役のナビを止める)・残党の合流・交戦に入った直後の tick に、降ろされた先導役が止められた区間を
            // 「進まなかった区間」として新しい先導役の群れへ報告し(待ち・向き変え・小規模レイドの「届かない」)、
            // 攻城の壁選び・余計な区間の札まで使っていた(レビュー指摘 2026-09-25)。止まるのは次の canContinueToUse。
            this.walking = false;
            return;
        }
        PathNavigation nav = this.mob.getNavigation();
        if (swarm.shouldVanguardWait()) {
            // 遅れた一員を待つ。区間は捨てる(待ったあとは今の位置から引き直す)。報告はしない。
            if (!nav.isDone()) {
                nav.stop();
            }
            this.walking = false;
            return;
        }
        if (this.walking) {
            Vec3 now = swarm.leadGoal();
            boolean replan = now != null && this.requestedGoal != null
                    && SwarmGoals.horizontalSq(now, this.requestedGoal) > REPLAN_DISTANCE * REPLAN_DISTANCE;
            if (!nav.isDone() && !replan) {
                return;
            }
            this.walking = false;
            if (!replan) {
                SwarmPaths.noteLegOutcome(swarm, SwarmPaths.legMadeProgress(this.mob, swarm));
            }
            // 行き先が大きく変わったときは報告しない(失敗ではない。待ちを付けずにすぐ引き直す)。
        }
        if (this.mob.tickCount < this.holdUntil) {
            return;
        }
        if (tryBreach(swarm)) {
            nav.stop();
            return;
        }
        Vec3 goal = swarm.leadGoal();
        if (goal == null) {
            return;
        }
        if (swarm.mode() == Swarm.Mode.WANDER
                && SwarmGoals.horizontalSq(this.mob.position(), goal) <= ARRIVED * ARRIVED) {
            // 暇な行き先に着いた。次の行き先は司令塔が次に考えるときに選ぶ。
            this.holdUntil = this.mob.tickCount + ARRIVED_HOLD_TICKS;
            return;
        }
        SwarmPaths.LegResult result = SwarmPaths.requestLeg(this.mob, swarm, goal, DesignData.swarm().legMaxLength());
        switch (result.status()) {
            case OK -> {
                this.walking = SwarmPaths.followLeg(this.mob, swarm, result.path(), SPEED);
                this.requestedGoal = goal;
                if (!this.walking) {
                    if (SwarmGoals.horizontalSq(this.mob.position(), goal) <= ARRIVED * ARRIVED) {
                        // 引けた区間がもう歩き終わっている(行き先の上に立っている)。失敗ではない。
                        this.holdUntil = this.mob.tickCount + ARRIVED_HOLD_TICKS;
                    } else {
                        SwarmPaths.noteLegOutcome(swarm, false);
                    }
                }
            }
            default -> {
                // NO_TOKEN / NOT_READY: 次の tick にやり直す。BACKOFF: 待つ。FAILED: requestLeg が待ちを付けた。
                // NOT_IN_SWARM: 降ろされた(次の canContinueToUse で止まる)。
            }
        }
    }

    /**
     * 攻城で標的の塊への区間が続けて塞がれたら、TD に壊す壁を選ばせる(td-design の BREACH の判断)。
     * 選べたら群れは BREACH に入り({@code leadGoal} が null になる)、この Goal は次の tick で止まって
     * 囲む Goal の塊の輪が壁を叩く。
     *
     * @return 壁を壊し始めた
     */
    private boolean tryBreach(Swarm swarm) {
        if (swarm.mode() != Swarm.Mode.SIEGE
                || swarm.consecutiveBlockedLegs() < SwarmSiegeHooks.BREACH_MIN_BLOCKED_LEGS
                || this.mob.tickCount < this.breachCheckAt) {
            return false;
        }
        SwarmSiegeHooks.BreachPicker picker = SwarmSiegeHooks.breachPicker();
        if (picker == null) {
            return false;
        }
        this.breachCheckAt = this.mob.tickCount + SwarmSiegeHooks.BREACH_CHECK_INTERVAL_TICKS;
        BlockPos wall = picker.pick(this.mob, swarm);
        if (wall == null) {
            return false;
        }
        swarm.setBreach(wall);
        this.walking = false;
        return true;
    }
}
