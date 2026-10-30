package com.yayuy.overgrownvegetation.swarm.goal;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.InvaderWaterLeapGoal;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmPaths;
import com.yayuy.overgrownvegetation.swarm.SwarmRole;
import com.yayuy.overgrownvegetation.swarm.SwarmTable;
import com.yayuy.overgrownvegetation.swarm.Trail;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * 地上の群れの<b>後続</b>が先導役の足跡をなぞる Goal(群れ v2、2026-09-25)。
 *
 * <p>ユーザー指定(2026-09-25): 後続は自分で標的を探さず、<b>経路も引かず</b>、うろつかない。
 * 毎 tick {@link Swarm#followerSteerPoint}(純追跡。カーソルは群れの側が持つ)で次の点をもらい、
 * {@code MoveControl.setWantedPosition} で直に向かう。点が無ければ(陣形の位置に着いている)何もしない =
 * 立ち止まる({@code MoveControl} は 1 tick で待機に戻る)。経路が無いので、段差・壁にぶつかったら自分で跳ぶ。
 *
 * <h2>いつ動くか(優先度 4、MOVE)</h2>
 * {@code canUse} = {@code canContinueToUse} = 「地上の群れの後続である」。群れにいる間ずっとではない ——
 * 先導役に選ばれたらその tick で止まる(同じ優先度の {@link SwarmLeadGoal} は割り込めないので、走り続けると
 * 新しい先導役が区間を引かず群れが止まる。swarm-api のレビュー指摘)。囲む・汚染を広げる(優先度 3)・
 * 種族の Goal(跳ぶ・ビーム、優先度 2)には普段どおり割り込まれる。
 *
 * <h2>立て直し(自分で経路を引く唯一の場面)</h2>
 * 先導役は動いている(直近 {@code recoveryNoProgressTicks} tick 以内)、または自分が遅れている({@link Swarm#isLagging}。
 * 先導役は遅れを待って止まるため。2026-09-25 のレビュー指摘)のに、自分は同じだけ次の点へ近づけていないときだけ、
 * {@link SwarmPaths#requestRecovery} で足跡のカーソルの点へ 1 本引く(ワールドの札を使う。札が無い・宙にいるなら次の tick に)。
 * 歩き終えたら経路を捨てて純追跡へ戻り、結果を {@link SwarmPaths#noteRecoveryOutcome} へ(3 回続けて失敗すると、
 * 自然の群れからは外れる / イベントの群れはカーソルを手前へ戻してやり直す)。
 *
 * <h2>そのほか</h2>
 * <ul>
 *     <li>点いている忌避灯の半径の中の点へは向かわない(立ち止まる。灯りから追い出す Goal と引っぱり合わない)。</li>
 *     <li>次の点が LEAP(先導役が川を跳び越えた着地点)で、自分が跳べる種(アルボラ)なら、水際で少し待つ ——
 *     跳ぶのは {@code InvaderWaterLeapGoal}(優先度 2)。{@value #LEAP_WAIT_TICKS} tick 待っても跳べなければ泳いで渡る。</li>
 *     <li>{@link #stop} は自分の立て直しの経路だけを止める。司令塔が引き継がせた区間(選び直しで先導役になった直後に
 *     {@link SwarmLeadGoal#start} が歩き始める)には触らない。</li>
 * </ul>
 */
public class SwarmFollowGoal extends Goal {

    /** 「近づいた」と数える縮み(ブロック)。小刻みな揺れを進捗に数えない。 */
    private static final double PROGRESS_STEP = 0.5D;
    /** 立て直しの経路で目的の点へ「着いた」距離(ブロック)。 */
    private static final double RECOVERY_ARRIVE = 2.0D;
    /** 立て直しの経路を歩いてよい上限(tick)。着けなければ失敗として数える。仮決め 2026-09-25。 */
    private static final int RECOVERY_MAX_TICKS = 200;
    /** 跳べる種が水際で跳び越えを待つ上限(tick)。仮決め 2026-09-25。 */
    private static final int LEAP_WAIT_TICKS = 60;
    /** 水際とみなす距離(次の点の向きに何マス先まで水を探すか)。 */
    private static final int LEAP_EDGE_RANGE = 2;
    /** 忌避灯の判定を引き直す間隔(tick)。灯りの表は軽いが、後続全員が毎 tick 引くほどではない。 */
    private static final int LAMP_CHECK_TICKS = 5;

    private final Mob mob;

    // 進み具合(立て直しの判断)
    private long progressCursor = Long.MIN_VALUE;
    private double bestDistance = Double.MAX_VALUE;
    private int noProgressTicks;

    // 立て直し
    private boolean recovering;
    @Nullable
    private Vec3 recoveryPoint;
    private long recoveryCursor;
    private int recoveryTicks;

    // 跳び越えを待つ
    private int leapWaitTicks;

    // 忌避灯
    @Nullable
    private Vec3 lampPoint;
    private boolean lampBlocked;
    private int lampCheckedAt = Integer.MIN_VALUE;

    public SwarmFollowGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return isFollowing(SwarmManager.get(this.mob));
    }

    /** {@link #canUse} と同じ式。先導役に選ばれたらその tick で止まる。 */
    @Override
    public boolean canContinueToUse() {
        return isFollowing(SwarmManager.get(this.mob));
    }

    private boolean isFollowing(@Nullable Swarm swarm) {
        return swarm != null && swarm.kind() == Swarm.Kind.GROUND && swarm.isFollower(this.mob);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        resetProgress();
        this.recovering = false;
        this.recoveryPoint = null;
        this.leapWaitTicks = 0;
        this.lampPoint = null;
    }

    @Override
    public void stop() {
        // 止めるのは自分の立て直しの経路だけ。それ以外の経路(選び直しで先導役になった直後に先導の Goal が
        // 歩き始める引き継ぎの区間など)には触らない。
        if (this.recovering) {
            this.mob.getNavigation().stop();
        }
        this.recovering = false;
        this.recoveryPoint = null;
    }

    @Override
    public void tick() {
        Swarm swarm = SwarmManager.get(this.mob);
        if (!isFollowing(swarm)) {
            // 先導役に選ばれた直後の tick(canContinueToUse は 2 tick に 1 回しか見られない。SwarmLeadGoal#tick と同じ)。
            // 引き継ぎの区間・立て直しの報告に触らず、次の canContinueToUse で止まる(レビュー指摘 2026-09-25)。
            return;
        }
        if (this.recovering) {
            tickRecovery(swarm);
            return;
        }
        Vec3 point = swarm.followerSteerPoint(this.mob);
        PathNavigation nav = this.mob.getNavigation();
        if (point == null || blockedByLamp(point)) {
            // 陣形の位置に着いている(または灯りの中の点)。立ち止まる = 行き先を渡さない。
            if (!nav.isDone()) {
                nav.stop();
            }
            resetProgress();
            this.leapWaitTicks = 0;
            return;
        }
        if (waitForLeap(swarm, point)) {
            if (!nav.isDone()) {
                nav.stop();
            }
            resetProgress();
            return;
        }
        if (!nav.isDone()) {
            // 経路は持たない(純追跡)。残っている経路は MoveControl を上書きするので捨てる。
            nav.stop();
        }
        this.mob.getMoveControl().setWantedPosition(point.x, point.y, point.z, swarm.followSpeed(this.mob));
        if (this.mob.horizontalCollision && this.mob.onGround()) {
            // 経路が無いので JumpControl を動かす者がいない。段差・壁にぶつかったら自分で跳ぶ。
            this.mob.getJumpControl().jump();
        }
        trackProgress(swarm, point);
    }

    // --- 進み具合と立て直し ---

    private void resetProgress() {
        this.progressCursor = Long.MIN_VALUE;
        this.bestDistance = Double.MAX_VALUE;
        this.noProgressTicks = 0;
    }

    /**
     * 次の点へ近づけているか。カーソルが進めば数え直す。先導役が動いているのに {@code recoveryNoProgressTicks} tick
     * 近づけなければ立て直す(先導役が止まっている間 —— 囲んでいる・汚染を広げている —— は、自分が遅れていなければ立て直さない。
     * 遅れを待って止まっている先導役のために、遅れている自分は立て直す)。
     */
    private void trackProgress(Swarm swarm, Vec3 point) {
        long cursor = swarm.followerCursor(this.mob);
        double distance = this.mob.position().distanceTo(point);
        if (cursor != this.progressCursor) {
            this.progressCursor = cursor;
            this.bestDistance = distance;
            this.noProgressTicks = 0;
            return;
        }
        if (distance < this.bestDistance - PROGRESS_STEP) {
            this.bestDistance = distance;
            this.noProgressTicks = 0;
            return;
        }
        SwarmTable table = DesignData.swarm();
        int limit = table.recoveryNoProgressTicks();
        if (this.noProgressTicks < limit) {
            this.noProgressTicks++;
            return;
        }
        if (swarm.ticksSinceVanguardMoved() >= limit && !swarm.isLagging(this.mob)) {
            // 先導役も止まっている(囲んでいる・汚染を広げている)。付いていけていないのではない。
            // ただし<b>自分が遅れている</b>なら立て直す(レビュー指摘 2026-09-25): 遅れた一員がいると先導役は止まって待つ
            // (shouldVanguardWait)ので、「先導役が動いている間だけ」にすると、詰まって遅れた後続は先導役が待ちを
            // 打ち切る 300 tick まで立て直せず、詰まるたびに群れが 15 秒止まっていた。
            return;
        }
        startRecovery(swarm);
    }

    private void startRecovery(Swarm swarm) {
        long cursor = swarm.followerCursor(this.mob);
        if (!swarm.trail().has(cursor)) {
            resetProgress();
            return;
        }
        Vec3 point = swarm.trail().point(cursor);
        SwarmPaths.LegResult result = SwarmPaths.requestRecovery(this.mob, swarm, point);
        switch (result.status()) {
            case OK -> {
                if (this.mob.getNavigation().moveTo(result.path(), swarm.followSpeed(this.mob))) {
                    this.recovering = true;
                    this.recoveryPoint = point;
                    this.recoveryCursor = cursor;
                    this.recoveryTicks = 0;
                } else {
                    report(swarm, false);
                }
            }
            case FAILED -> report(swarm, false);
            case NO_TOKEN, NOT_READY -> {
                // 札が無い・宙にいる。数えを据え置いて次の tick にやり直す(失敗には数えない)。
            }
            default -> resetProgress();
        }
    }

    /** 立て直しの経路を歩いている間。カーソルは純追跡と同じく進める(通り過ぎた点で成功とみなせるように)。 */
    private void tickRecovery(Swarm swarm) {
        Vec3 steer = swarm.followerSteerPoint(this.mob);
        this.recoveryTicks++;
        PathNavigation nav = this.mob.getNavigation();
        boolean passed = swarm.followerCursor(this.mob) > this.recoveryCursor;
        boolean arrived = this.recoveryPoint != null
                && this.mob.position().distanceTo(this.recoveryPoint) <= RECOVERY_ARRIVE;
        if (steer == null || passed || arrived) {
            nav.stop();
            this.recovering = false;
            report(swarm, true);
            return;
        }
        if (nav.isDone() || this.recoveryTicks > RECOVERY_MAX_TICKS) {
            nav.stop();
            this.recovering = false;
            report(swarm, false);
        }
    }

    private void report(Swarm swarm, boolean success) {
        this.recoveryPoint = null;
        SwarmPaths.noteRecoveryOutcome(this.mob, swarm, success);
        // EJECTED なら次の canContinueToUse で止まる。FALLBACK はカーソルが手前へ戻っている。
        resetProgress();
    }

    // --- 跳び越え・忌避灯 ---

    /**
     * 次の点が LEAP で、自分が跳べる種なら、水際で跳び越えの Goal を待つ(水へ歩いて入ると跳べなくなる)。
     * {@value #LEAP_WAIT_TICKS} tick 待っても跳ばなければ(届かない・着地できない)あきらめて歩く(泳ぐ)。
     *
     * @return 立ち止まって待つ
     */
    private boolean waitForLeap(Swarm swarm, Vec3 point) {
        if (swarm.followerNextKind(this.mob) != Trail.Kind.LEAP || !SwarmRole.isArbora(this.mob.getType())
                || !this.mob.onGround() || this.mob.isInWater()) {
            this.leapWaitTicks = 0;
            return false;
        }
        if (this.leapWaitTicks >= LEAP_WAIT_TICKS) {
            return false;
        }
        Vec3 dir = new Vec3(point.x - this.mob.getX(), 0.0D, point.z - this.mob.getZ());
        if (dir.lengthSqr() < 1.0E-4D
                || !InvaderWaterLeapGoal.waterAhead(this.mob, dir.normalize(), LEAP_EDGE_RANGE)) {
            // まだ水際に着いていない。歩いて寄る。
            return false;
        }
        this.leapWaitTicks++;
        return true;
    }

    /** その点が点いている忌避灯の半径の中か({@value #LAMP_CHECK_TICKS} tick ごとに引き直す)。 */
    private boolean blockedByLamp(Vec3 point) {
        if (this.lampPoint == null || this.mob.tickCount - this.lampCheckedAt >= LAMP_CHECK_TICKS
                || this.lampPoint.distanceToSqr(point) > 1.0D) {
            this.lampPoint = point;
            this.lampCheckedAt = this.mob.tickCount;
            this.lampBlocked = SwarmGoals.insideLamp(this.mob.level(), point);
        }
        return this.lampBlocked;
    }
}
