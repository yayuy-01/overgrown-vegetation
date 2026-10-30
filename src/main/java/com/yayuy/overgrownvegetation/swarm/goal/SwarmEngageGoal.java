package com.yayuy.overgrownvegetation.swarm.goal;

import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.UUID;

/**
 * 群れの<b>前衛が囲んで殴る</b> Goal(群れ v2、2026-09-25)。優先度 3、MOVE + LOOK。
 *
 * <h2>相手を囲む(エンティティ)</h2>
 * 司令塔が渡した標的が {@code engageRadius}(12)+ 揺れよけ以内にある前衛(アルボラ。スカトゥリウムは自分の突進・
 * 迫撃砲・追跡の Goal)だけ({@link Swarm#engageTarget})。囲む位置({@link Swarm#engageSlot}、相手の<b>今の</b>位置の
 * まわりの輪。同じ相手を囲む前衛の中の番号で角度を割り振る)へ直に向かい、手が届けば {@code MeleeAttackGoal} と同じく
 * 20 tick に 1 回殴る。囲む位置へ {@value #STRAIGHT_AFTER_TICKS} tick 近づけなければ、しばらく相手へまっすぐ向かう
 * (輪の上の位置が壁の向こう・崖の上のとき)。経路は引かない(群れの経路の数えに入らない・札も使わない)。
 * まっすぐ向かっても壁で近づけなければ、アルボラは壁を登り、続けば先導役が坑道を掘る・回り道し、それでも駄目なら群れとして
 * その相手を諦める({@link #watchStraight}。2026-09-25 のレビュー指摘)。
 *
 * <h2>塊を囲む(攻城 TD の SIEGE / BREACH)</h2>
 * 司令塔が塊の輪の番号を振った前衛だけ({@link Swarm#blockEngageSlot}。先導役が塊に着いてから、塊の近くの前衛。
 * BREACH ではいつでも)。輪の上の位置へ向かい、手が届けば {@value SwarmSiegeHooks#STRIKE_INTERVAL_TICKS} tick に 1 回
 * TD のフック({@link SwarmSiegeHooks.BlockStrike})を呼ぶ。当てたと答えたら攻撃アニメ({@link SwarmAttacker})。
 * 壊す壁がもう無くなっていれば(誰かが壊した・TD が壊し終えた){@link Swarm#endBreach} で攻城へ戻す(保険)。
 *
 * <p>どちらも、点いている忌避灯の半径の中の位置へは向かわない(ユーザー指定「灯りのそばには寄れない」。
 * 灯りから追い出す Goal と毎 tick 引っぱり合わないように)。例外は TD の攻城で灯りを覆っている・灯りのそばの壁を壊している
 * 群れ({@link SwarmGoals#breachIgnoresLamps}、仮決め 2026-09-25。そのときは追い出す Goal も止まる)。
 */
public class SwarmEngageGoal extends Goal {

    /** 攻撃の間隔(tick)。{@code MeleeAttackGoal} と同じ。 */
    private static final int ATTACK_INTERVAL = 20;
    /** 囲む位置へこれだけ近づけなければ相手へまっすぐ向かう(tick)。swarm-api「30 ticks without progress」。 */
    private static final int STRAIGHT_AFTER_TICKS = 30;
    /** まっすぐ向かう長さ(tick)。そのあとまた輪の位置を試す。仮決め 2026-09-25。 */
    private static final int STRAIGHT_TICKS = 40;
    /** 「近づいた」と数える縮み(ブロック)。 */
    private static final double PROGRESS_STEP = 0.4D;
    /** 輪の位置に「着いた」距離(ブロック、水平)。 */
    private static final double SLOT_ARRIVE = 0.6D;
    /** 忌避灯の判定を引き直す間隔(tick)。 */
    private static final int LAMP_CHECK_TICKS = 5;
    /** 移動の速さの倍率。 */
    private static final double SPEED = 1.0D;
    /** まっすぐ向かう 1 回で相手へこれだけ(ブロック)近づけば「進んだ」。仮決め 2026-09-25。 */
    private static final double STRAIGHT_PROGRESS = 1.0D;
    /**
     * 壁に当たったまま終わったまっすぐの回がこれだけ続いたら、先導役に「詰まった」印を立てさせる(坑道・回り道。
     * {@link SwarmApproachHandler#noteSwarmApproachExhausted})。1 回目は壁を登る(張り付き)に任せる。仮決め 2026-09-25。
     */
    private static final int STUCK_AFTER_BLOCKED_CYCLES = 2;
    /**
     * 同じく、これだけ続いたら先導役は群れとしてその相手を諦める({@link Swarm#giveUpTarget}。群れの誰かが直近
     * {@value #HIT_GRACE_TICKS} tick に殴れていれば諦めない)。仮決め 2026-09-25。
     */
    private static final int GIVE_UP_AFTER_BLOCKED_CYCLES = 4;
    /** 「群れの誰かが殴れている」とみなす間(tick)。 */
    private static final int HIT_GRACE_TICKS = 100;
    /** 壁に当たった回の数えを覚えておく間(tick)。これより前の回とは続けて数えない(坑道・回り道で間が空いてもこの内なら続き)。 */
    private static final int BLOCKED_MEMORY_TICKS = 600;

    private final Mob mob;

    private int attackCooldown;
    private int strikeCooldown;
    private double bestDistance = Double.MAX_VALUE;
    private int noProgressTicks;
    private int straightTicks;

    // 相手へまっすぐ向かう回の見張り(壁で近づけないときの登る・掘る・諦める。レビュー指摘 2026-09-25)
    /** いまのまっすぐの回に入ってから数えた tick(張り付いている間は数えない)。0 = 回の外。 */
    private int straightElapsed;
    private double straightStartDistance;
    private boolean blockedThisCycle;
    private boolean hitThisCycle;
    private boolean clungThisCycle;
    /** 壁に当たったまま終わった回が続いた数(相手ごと。Goal が止まって戻ってきても続きから数える)。 */
    private int blockedCycles;
    @Nullable
    private UUID blockedTarget;
    private int lastBlockedCycleTick;

    @Nullable
    private Vec3 lampPoint;
    private boolean lampBlocked;
    /**
     * この tick は灯りの半径を気にしない(TD の攻城で灯りを覆っている・灯りのそばの壁を壊している群れ。
     * {@link SwarmGoals#breachIgnoresLamps}、仮決め 2026-09-25)。灯りを叩くには半径の中の輪の位置へ行くしかない。
     */
    private boolean ignoreLamps;
    private int lampCheckedAt = Integer.MIN_VALUE;

    public SwarmEngageGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return engaged(SwarmManager.get(this.mob));
    }

    @Override
    public boolean canContinueToUse() {
        return engaged(SwarmManager.get(this.mob));
    }

    private boolean engaged(@Nullable Swarm swarm) {
        return swarm != null && swarm.kind() == Swarm.Kind.GROUND
                && (swarm.engageTarget(this.mob) != null || swarm.blockEngageSlot(this.mob) != null);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.mob.getNavigation().stop();
        this.attackCooldown = 0;
        // 塊を叩く間隔は一員ごとにずらす(同じ tick にそろって叩かない)。
        this.strikeCooldown = Math.floorMod(this.mob.getId(), SwarmSiegeHooks.STRIKE_INTERVAL_TICKS);
        resetProgress();
        this.straightTicks = 0;
        this.straightElapsed = 0;
        this.lampPoint = null;
        this.mob.setAggressive(true);
    }

    @Override
    public void stop() {
        this.mob.setAggressive(false);
    }

    @Override
    public void tick() {
        Swarm swarm = SwarmManager.get(this.mob);
        if (swarm == null) {
            return;
        }
        PathNavigation nav = this.mob.getNavigation();
        if (!nav.isDone()) {
            // 直に動かす。残っている経路は MoveControl を上書きするので捨てる。
            nav.stop();
        }
        this.attackCooldown = Math.max(0, this.attackCooldown - 1);
        this.ignoreLamps = SwarmGoals.breachIgnoresLamps(swarm);
        LivingEntity target = swarm.engageTarget(this.mob);
        if (target != null) {
            tickEntity(swarm, target);
            return;
        }
        Vec3 slot = swarm.blockEngageSlot(this.mob);
        if (slot != null) {
            tickBlock(swarm, slot);
        }
    }

    // --- 相手を囲む ---

    private void tickEntity(Swarm swarm, LivingEntity target) {
        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        Vec3 slot = swarm.engageSlot(this.mob);
        boolean inReach = this.mob.isWithinMeleeAttackRange(target);
        Vec3 want;
        boolean straight = this.straightTicks > 0 || slot == null;
        if (straight) {
            // 輪の位置へ近づけなかった(壁の向こう・崖の上)。しばらく相手へまっすぐ。
            if (this.straightTicks > 0) {
                this.straightTicks--;
            }
            want = target.position();
        } else {
            want = slot;
        }
        // 手が届いていても輪の位置へは寄り続ける(寄らないと、先に着いた数体が同じ側に固まって囲めない)。
        steer(want);
        boolean hit = false;
        if (this.attackCooldown <= 0 && inReach && this.mob.getSensing().hasLineOfSight(target)) {
            // MeleeAttackGoal#checkAndPerformAttack と同じ(振って、当てる。攻撃アニメは doHurtTarget が付ける)。
            this.attackCooldown = adjustedTickDelay(ATTACK_INTERVAL);
            this.mob.swing(InteractionHand.MAIN_HAND);
            hit = this.mob.doHurtTarget(target);
            if (hit) {
                swarm.noteEngageHit(target);
            }
        }
        if (straight) {
            watchStraight(swarm, target, inReach, hit);
        } else if (this.straightElapsed > 0) {
            // まっすぐの回が終わった(時間いっぱい、または張り付いていて数えが途中)。ここで締める。
            closeStraightCycle(swarm, target);
        }
    }

    // --- 壁で近づけないとき(レビュー指摘 2026-09-25) ---

    /**
     * 相手へまっすぐ向かっている回の見張り。群れの前衛は経路を引かずに直に動かされるので、個体のときの
     * 「経路が引けない相手へ体を押し当てる → 壁に当たれば登る → 詰まった印で坑道・回り道 → 何度も詰まれば諦める」
     * (AbstractOvergrownInvaderEntity#tickStuckDetection)が一度も起きず、同じ高さの壁の向こう・家の中の相手に
     * 前衛全員が壁へ体を押し付けたまま止まっていた。ここでその代わりをする:
     * <ul>
     *     <li>壁に当たって近づけない tick → 一員へ伝える({@link SwarmApproachHandler#noteSwarmApproachBlocked}。
     *     アルボラは「回り道が無い」とみなして壁を登り始める。同じ高さの相手でも)。</li>
     *     <li>壁に当たったまま(進めず・殴れず)終わった回が {@value #STUCK_AFTER_BLOCKED_CYCLES} 回続いた → 先導役は
     *     詰まった印(坑道を掘る・回り道。後続は掘らない)。</li>
     *     <li>{@value #GIVE_UP_AFTER_BLOCKED_CYCLES} 回続いた → 先導役は群れとしてその相手を諦める(群れの誰かが直近に
     *     殴れていれば諦めない)。後続は諦めない(先導役が掘っている間に諦めると坑道が無駄になる)。</li>
     * </ul>
     * 張り付いて登っている間は数えない(登れれば進む)。張り付いた回は壁に当たった回に数えない。
     */
    private void watchStraight(Swarm swarm, LivingEntity target, boolean inReach, boolean hit) {
        if (!(this.mob instanceof SwarmApproachHandler handler)) {
            return;
        }
        if (this.straightElapsed == 0) {
            this.straightStartDistance = this.mob.distanceTo(target);
            this.blockedThisCycle = false;
            this.hitThisCycle = false;
            this.clungThisCycle = false;
        }
        this.hitThisCycle |= hit;
        if (handler.isClinging()) {
            this.clungThisCycle = true;
            return;
        }
        this.straightElapsed++;
        if (this.mob.horizontalCollision && !inReach) {
            this.blockedThisCycle = true;
            handler.noteSwarmApproachBlocked();
        }
        if (this.straightElapsed >= STRAIGHT_TICKS) {
            closeStraightCycle(swarm, target);
        }
    }

    /** まっすぐの回を 1 回ぶん締めて、壁に当たったまま終わった回を数える(上の説明)。 */
    private void closeStraightCycle(Swarm swarm, LivingEntity target) {
        this.straightElapsed = 0;
        if (!(this.mob instanceof SwarmApproachHandler handler)) {
            return;
        }
        if (this.clungThisCycle) {
            // 登ってみた回。登れなかったなら張り付きが自分で離す(次の回は少し休んでからなので、壁に当たった回になる)。
            return;
        }
        boolean progressed = this.mob.distanceTo(target) < this.straightStartDistance - STRAIGHT_PROGRESS;
        if (progressed || this.hitThisCycle || !this.blockedThisCycle) {
            this.blockedCycles = 0;
            return;
        }
        UUID id = target.getUUID();
        int now = this.mob.tickCount;
        if (!id.equals(this.blockedTarget) || now - this.lastBlockedCycleTick > BLOCKED_MEMORY_TICKS) {
            this.blockedCycles = 0;
            this.blockedTarget = id;
        }
        this.blockedCycles++;
        this.lastBlockedCycleTick = now;
        if (this.blockedCycles >= STUCK_AFTER_BLOCKED_CYCLES) {
            handler.noteSwarmApproachExhausted();
        }
        if (this.blockedCycles >= GIVE_UP_AFTER_BLOCKED_CYCLES && swarm.isVanguard(this.mob)
                && !swarm.engageHitRecently(target, HIT_GRACE_TICKS)) {
            swarm.giveUpTarget(target);
            this.blockedCycles = 0;
            this.blockedTarget = null;
        }
    }

    // --- 塊を囲む(TD) ---

    private void tickBlock(Swarm swarm, Vec3 slot) {
        boolean breach = swarm.mode() == Swarm.Mode.BREACH;
        BlockPos block = breach ? swarm.breachPos() : swarm.targetBlock();
        if (block == null) {
            return;
        }
        if (breach && this.mob.level().getBlockState(block).getCollisionShape(this.mob.level(), block).isEmpty()) {
            // 壊す壁がもう無い(誰かが壊した)。攻城へ戻して区間を引き直させる(TD の戻し忘れの保険)。
            swarm.endBreach();
            return;
        }
        Vec3 centre = Vec3.atCenterOf(block);
        this.mob.getLookControl().setLookAt(centre.x, centre.y, centre.z, 30.0F, 30.0F);
        // 上下にも届く(床に埋めた塊・1 段の台の上の塊・壊す壁の 2〜3 段目。Swarm#canReachBlock、レビュー指摘 2026-09-25)。
        boolean inReach = Swarm.canReachBlock(this.mob, block);
        Vec3 want;
        if (this.straightTicks > 0) {
            // 輪の位置へ近づけなかった。しばらく塊へまっすぐ。
            this.straightTicks--;
            want = Vec3.atBottomCenterOf(block);
        } else if (!inReach && SwarmGoals.horizontalSq(this.mob.position(), slot) <= SLOT_ARRIVE * SLOT_ARRIVE
                && clearToward(block)) {
            // 輪の位置には「着いた」(SLOT_ARRIVE の内)のに手が届かない: 輪の半径の余り(0.6)に着いた幅(0.6)を足すと
            // 横の手の届く余り(Swarm#BLOCK_REACH_SIDE 0.8)を超えるので、輪の外側の縁で止まると叩けないまま立ち続けた
            // (GameTest frontMemberStrikeDamagesTarget、2026-09-25)。届くまで塊の方へ寄る(届けば次の tick から輪の位置へ戻る)。
            // 間に壁があれば寄らない(押し付けると跳ぶ・アルボラは登り始め、足が地面を離れて TD の見張りが壁を選べなくなる。
            // GameTest targetBehindWallIsBreached)。
            want = Vec3.atBottomCenterOf(block);
        } else {
            want = slot;
        }
        steer(want);
        if (--this.strikeCooldown > 0 || !inReach) {
            return;
        }
        this.strikeCooldown = SwarmSiegeHooks.STRIKE_INTERVAL_TICKS;
        SwarmSiegeHooks.BlockStrike strike = SwarmSiegeHooks.blockStrike();
        if (strike != null && strike.strike(this.mob, swarm, block, breach) && this.mob instanceof SwarmAttacker attacker) {
            attacker.playAttackAnimation();
        }
    }

    /**
     * 塊の方へ {@value #NUDGE_PROBE} ブロック動かした体の箱が、どの塊にも当たらないか(手が届かないうちは塊そのものまでは
     * 0.8 より遠いので、当たるのは間にある壁・段差)。
     */
    private boolean clearToward(BlockPos block) {
        double dx = block.getX() + 0.5D - this.mob.getX();
        double dz = block.getZ() + 0.5D - this.mob.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) {
            return false; // 真上・真下。横へ寄っても届かない
        }
        AABB moved = this.mob.getBoundingBox().move(dx / len * NUDGE_PROBE, 0.0D, dz / len * NUDGE_PROBE);
        return this.mob.level().noBlockCollision(this.mob, moved);
    }

    /** 手が届かないときに塊へ寄ってよいかを見る、体の箱の先読み(ブロック)。仮決め 2026-09-25。 */
    private static final double NUDGE_PROBE = 0.3D;

    // --- 動かし方 ---

    /**
     * {@code want} へ直に向かう(着いていれば何もしない = 立ち止まる)。忌避灯の中なら立ち止まる。
     * {@value #STRAIGHT_AFTER_TICKS} tick 近づけなければ、しばらく相手(塊)へまっすぐ向かわせる。
     */
    private void steer(Vec3 want) {
        if (SwarmGoals.horizontalSq(this.mob.position(), want) <= SLOT_ARRIVE * SLOT_ARRIVE
                && Math.abs(this.mob.getY() - want.y) < 1.5D) {
            resetProgress();
            return;
        }
        if (blockedByLamp(want)) {
            resetProgress();
            return;
        }
        this.mob.getMoveControl().setWantedPosition(want.x, want.y, want.z, SPEED);
        if (this.mob.horizontalCollision && this.mob.onGround()) {
            this.mob.getJumpControl().jump();
        }
        double distance = this.mob.position().distanceTo(want);
        if (distance < this.bestDistance - PROGRESS_STEP || distance <= SLOT_ARRIVE) {
            this.bestDistance = distance;
            this.noProgressTicks = 0;
            return;
        }
        if (++this.noProgressTicks >= STRAIGHT_AFTER_TICKS && this.straightTicks == 0) {
            this.straightTicks = STRAIGHT_TICKS;
            resetProgress();
        }
    }

    private void resetProgress() {
        this.bestDistance = Double.MAX_VALUE;
        this.noProgressTicks = 0;
    }

    /** その点が点いている忌避灯の半径の中か({@value #LAMP_CHECK_TICKS} tick ごと、または点が 1 ブロック動いたら引き直す)。 */
    private boolean blockedByLamp(Vec3 point) {
        if (this.ignoreLamps) {
            return false;
        }
        if (this.lampPoint == null || this.mob.tickCount - this.lampCheckedAt >= LAMP_CHECK_TICKS
                || this.lampPoint.distanceToSqr(point) > 1.0D) {
            this.lampPoint = point;
            this.lampCheckedAt = this.mob.tickCount;
            this.lampBlocked = SwarmGoals.insideLamp(this.mob.level(), point);
        }
        return this.lampBlocked;
    }
}
