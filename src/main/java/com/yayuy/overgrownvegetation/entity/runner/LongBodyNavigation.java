package com.yayuy.overgrownvegetation.entity.runner;

import com.yayuy.overgrownvegetation.entity.OvergrownNodeEvaluator;
import com.yayuy.overgrownvegetation.entity.forest.CoarseDetour;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;

import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 「② 大型の地上を走る型」の経路(2026-11-14、ヴェナムネスのためにファルキムネスの作りから種に依らない形へ切り出した)。
 *
 * <ul>
 *   <li>経路の重みに長い体の重み({@link OvergrownNodeEvaluator.LongBody}: 幹の近さ・狭い所の急な曲がり)を足す。</li>
 *   <li>普段の探索が届かない経路しか返さなかったときだけ、粗い下調べ({@link CoarseDetour}: 2D 格子・サーバー全体の予算 2 ms/tick)で
 *       回り道を見つけ、その途中(約 10 ブロック先)の「途中の目標」まで普段の探索でつなぐ。</li>
 * </ul>
 * 中身はファルキムネス({@code FalcimnesEntity#createNavigation} の {@code coarse})と同じ。ファルキムネスはまだ自分の写しを使っている
 * (切り替えは別の段。GameTest を流して確かめてから)。回り道の状態(次に下調べしてよい tick・途中の目標)はこの経路が個体ごとに持つ。
 * 数値はファルキムネスと同じ【仮】。
 */
public class LongBodyNavigation extends GroundPathNavigation {

    /** 予算を使い切っていたときに待つ tick・途中の目標を置く距離(普段の探す距離に掛ける・下限・上限ブロック)・途中の目標に「着ける」とみる距離の 2 乗【仮】。 */
    static final int DETOUR_WAIT_TICKS = 5;
    static final double DETOUR_REACH = 0.3D, DETOUR_REACH_MIN = 4.0D, DETOUR_REACH_MAX = 12.0D, DETOUR_NEAR_SQR = 4.0D;
    /** 途中の目標へ動き出した所からこれより離れたら、間隔を待たずに次を下調べしてよい(ブロックの 2 乗)。 */
    static final double DETOUR_MOVED_SQR = 4.0D;
    /** 途中の目標までの細かい探索の、調べる数の倍率(普段に掛ける)と下限(マス)【仮】。 */
    static final float DETOUR_FINE_NODES = 0.35F, DETOUR_FINE_MIN_NODES = 64.0F;
    /** 回り道を採る: 普段の経路よりこれだけ(ブロック)目標に近い所で終わるなら【仮】。 */
    static final double CLOSER = 2.0D;
    /** 次に下調べしてよいまで: 採れた / 採れなかった(tick)【仮】。 */
    static final int RETRY_TICKS = 60, GIVE_UP_TICKS = 200;

    /** 計測用(全個体): 下調べの回数・採れた回数・細かい探索の時間(ns)。 */
    public static volatile long detourCalls, detourFound, detourNanos;
    /** 計測用(全個体): 普段の経路づくりの時間の合計・1 回の最悪(ns)・回数。 */
    public static volatile long pathNanos, pathWorst, pathCalls;
    /** 確かめ用: 最後の回り道の中身(GameTest)。 */
    public static volatile boolean trace;
    public static volatile String lastTrace = "";

    private final Mob owner;
    private final OvergrownNodeEvaluator.LongBody weights;
    /** 葉を通れないものとして下調べするか(押し分けない個体)。 */
    private final BooleanSupplier avoidLeaves;

    private int retryTick;
    private BlockPos detourFrom, detourTo;

    /**
     * @param weights     長い体の重み(null なら足さない)
     * @param avoidLeaves 回り道の下調べで葉を「通れない」とするか(毎回読む)
     */
    public LongBodyNavigation(Mob mob, Level level, OvergrownNodeEvaluator.LongBody weights, BooleanSupplier avoidLeaves) {
        super(mob, level);
        this.owner = mob;
        this.weights = weights;
        this.avoidLeaves = avoidLeaves;
        if (this.nodeEvaluator instanceof OvergrownNodeEvaluator ev) {
            ev.setLongBody(weights);   // createPathFinder は super の構築子の中で呼ばれ、まだ weights を知らないので、ここで足す
        }
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        OvergrownNodeEvaluator evaluator = new OvergrownNodeEvaluator();
        this.nodeEvaluator = evaluator;
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    /** 長い体の重みを差し替える(性格などで個体ごとに変えるとき)。 */
    public void setWeights(OvergrownNodeEvaluator.LongBody w) {
        if (this.nodeEvaluator instanceof OvergrownNodeEvaluator ev) ev.setLongBody(w);
    }

    public OvergrownNodeEvaluator.LongBody baseWeights() {
        return this.weights;
    }

    private boolean detourMoved() {
        if (this.detourFrom == null) return false;
        BlockPos at = this.owner.blockPosition();
        // 経路のノードは体の足場の角、体の位置はその +1(バニラの大きい体)
        return at.distSqr(this.detourFrom) > DETOUR_MOVED_SQR || (this.detourTo != null && !this.detourTo.equals(this.detourFrom) && at.distSqr(this.detourTo) <= 3.0D);
    }

    @Override
    protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy, float followRange) {
        long t0 = System.nanoTime();
        Path p = super.createPath(targets, regionOffset, offsetUpward, accuracy, followRange);
        long ns = System.nanoTime() - t0;
        pathNanos += ns;
        pathWorst = Math.max(pathWorst, ns);
        pathCalls++;
        if ((p == null || !p.canReach()) && !targets.isEmpty() && this.owner.level() instanceof ServerLevel sl
                && (this.owner.tickCount >= this.retryTick || detourMoved())) {
            return coarse(sl, p, targets, regionOffset, offsetUpward, accuracy, followRange);
        }
        return p;
    }

    private Path coarse(ServerLevel sl, Path p, Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy, float followRange) {
        Mob f = this.owner;
        if (!CoarseDetour.budgetLeft(sl)) {
            this.retryTick = f.tickCount + DETOUR_WAIT_TICKS;
            return p;
        }
        BlockPos goal = null;
        for (BlockPos t : targets) if (goal == null || t.distSqr(f.blockPosition()) < goal.distSqr(f.blockPosition())) goal = t;
        int width = Mth.floor(f.getBbWidth() + 1.0F);
        BlockPos from = f.blockPosition();
        double reach = Math.max(DETOUR_REACH_MIN, Math.min(followRange * DETOUR_REACH, DETOUR_REACH_MAX));
        long call0 = System.nanoTime();
        var plan = CoarseDetour.plan(sl, from, goal, width, this.avoidLeaves.getAsBoolean(), reach);
        detourCalls++;
        double before = p == null || p.getEndNode() == null ? Double.MAX_VALUE : Math.sqrt(p.getEndNode().asBlockPos().distSqr(goal));
        Path q = null;
        if (plan != null && plan.gap() + CLOSER <= before) {
            long t0 = System.nanoTime();
            // 途中の目標は近く(reach 以内)で道も分かっているので、調べる数と距離を絞り、長い体の重みも外す(ファルキムネスと同じ)
            this.setMaxVisitedNodesMultiplier(Math.max(DETOUR_FINE_NODES, DETOUR_FINE_MIN_NODES / (followRange * 16.0F)));
            OvergrownNodeEvaluator ev = this.nodeEvaluator instanceof OvergrownNodeEvaluator o ? o : null;
            OvergrownNodeEvaluator.LongBody keep = ev != null ? ev.longBody() : null;
            if (ev != null) ev.setLongBody(null);
            try {
                q = super.createPath(Set.of(plan.waypoint()), regionOffset, offsetUpward, accuracy, (float) (reach + 4.0D));
            } finally {
                this.resetMaxVisitedNodesMultiplier();
                if (ev != null) ev.setLongBody(keep);
            }
            long ns = System.nanoTime() - t0;
            detourNanos += ns;
            CoarseDetour.spend(sl, ns);
        }
        CoarseDetour.noteCall(System.nanoTime() - call0);
        boolean ok = q != null && q.getEndNode() != null && (q.getEndNode().asBlockPos().distSqr(plan.waypoint()) <= DETOUR_NEAR_SQR
                || Math.sqrt(q.getEndNode().asBlockPos().distSqr(plan.waypoint())) + 1.0D <= Math.sqrt(from.distSqr(plan.waypoint())));
        this.retryTick = f.tickCount + (ok ? RETRY_TICKS : GIVE_UP_TICKS);
        this.detourFrom = ok ? from : null;
        this.detourTo = ok ? q.getEndNode().asBlockPos() : null;
        if (trace) {
            lastTrace = f.getType().getDescriptionId() + " 位置 " + from + " 目標 " + goal + " 下調べ " + (plan == null ? "なし" : plan.waypoint() + " 残り " + plan.gap())
                    + " 普段の残り " + before + " 細かい終わり " + (q == null || q.getEndNode() == null ? "-" : q.getEndNode().asBlockPos()) + " 採る " + ok;
        }
        if (ok) {
            detourFound++;
            return q;
        }
        return p;
    }
}
