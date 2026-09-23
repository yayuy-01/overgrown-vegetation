package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * <b>壁・天井に張り付いたまま目的地へ進む</b>ための地形計算(ユーザー報告 2026-09-20:
 * 「アルボラが壁や天井を歩けるというのをうまく使いこなせていない。一か所で突っかかる」)。
 *
 * <h2>それまでの作りの何が駄目だったか</h2>
 * 張り付いている間の動きは<b>2 行の物理だけ</b>だった。
 * <pre>{@code
 * // 壁: 壁へ押し付けながら「まっすぐ上へ」
 * setDeltaMovement(facing.getStepX() * 0.12, max(motion.y, 0.12), facing.getStepZ() * 0.12);
 * // 天井: 標的の真上へ「まっすぐ」
 * setDeltaMovement(dx * 0.14, 0.1, dz * 0.14);
 * }</pre>
 * どちらも<b>行く先にブロックがあるかを一切見ていない</b>。だから
 * <ul>
 *     <li>壁の上に庇(ひさし)があると、隅に頭を押し付けたまま永久に上を目指す。</li>
 *     <li>天井が途中で途切れる/柱があると、そこへ体を押し付けたまま止まる。</li>
 *     <li>壁を横へ回り込む、角を曲がる、天井から隣の壁へ移る、がそもそも<b>できない</b>。
 *     上に行くか、標的の方角へ直進するかの 2 つしか手が無かった。</li>
 * </ul>
 * 「一か所で突っかかる」個体が多いのはこれが原因。
 *
 * <h2>ここでやること —— 面の上のマス目を歩く</h2>
 * 張り付いている状態を<b>「マス {@code cell}」と「張り付いている面の向き {@code anchor}」</b>の
 * 組で表す(壁なら anchor は水平、天井なら {@link Direction#UP})。
 * そのうえで<b>anchor に垂直な 4 方向</b>へ 1 マスずつ動く手を数え上げ、
 * 目的地にいちばん近づく手を選ぶ。角の扱いが肝で、3 通りある。
 *
 * <ol>
 *     <li><b>同じ面を進む</b> —— 行き先のマスが空いていて、その先(anchor 方向)に壁が続いている。
 *     いちばん普通の 1 歩。</li>
 *     <li><b>外側の角を回る</b>({@link #reanchor}) —— 行き先のマスは空いているが
 *     <b>壁が途切れている</b>。壁の端。そこで張り付ける別の面を探し直す ——
 *     見つかれば体を回して裏側へ回り込み(壁 → 隣の壁、壁 → 天井の裏)、
 *     {@link Direction#DOWN} しか無ければ<b>そこは床</b>なので張り付きをやめて歩きに戻る。</li>
 *     <li><b>内側の角を折れる</b> —— 行き先のマスが<b>塞がっている</b>。
 *     その塞いでいるブロックの面へ<b>その場で張り替える</b>(マスは動かない)。
 *     壁を登りきって庇に当たったら天井へ移る、というのがこれ。
 *     それまでは<b>ここで詰まっていた</b>。</li>
 * </ol>
 *
 * <p>同じところを往復しないよう、<b>直前に通ったマス</b>を呼び出し側が憶えていて
 * {@code recent} で渡す。候補が全部そこに入っているときだけ、やむなく戻る手も選ぶ。
 *
 * <p>行き止まりで候補が 1 つも無ければ {@code null} を返す。呼び出し側は
 * しばらく粘ってから張り付きをやめる。
 */
public final class SurfaceCrawl {

    /** 進む先のマスと、そこで張り付く面の向き。{@code anchor} が DOWN なら「床に降りた」= 張り付き終了。 */
    public record Step(BlockPos cell, Direction anchor) {
    }

    /**
     * {@code from} に {@code anchor} の面で張り付いている個体が、{@code goal} へ 1 歩近づく手。
     * 無ければ {@code null}。
     *
     * @param recent 直前に通ったマス(往復よけ)。ここに入っているマスは最後に回す。
     */
    @Nullable
    public static Step next(Mob mob, BlockPos from, Direction anchor, Vec3 goal, Collection<BlockPos> recent) {
        List<Step> candidates = new ArrayList<>(8);
        List<Step> backtrack = new ArrayList<>(4);
        for (Direction move : Direction.values()) {
            if (move.getAxis() == anchor.getAxis()) {
                // 面から離れる/めり込む向きには動けない。
                continue;
            }
            Step step = stepTowards(mob, from, anchor, move);
            if (step == null) {
                continue;
            }
            if (!step.cell().equals(from) && recent.contains(step.cell())) {
                backtrack.add(step);
            } else {
                candidates.add(step);
            }
        }
        Step best = closestTo(goal, candidates);
        return best != null ? best : closestTo(goal, backtrack);
    }

    /** {@code goal} にいちばん近づく手。 */
    @Nullable
    private static Step closestTo(Vec3 goal, List<Step> steps) {
        Step best = null;
        double bestSq = Double.MAX_VALUE;
        for (Step step : steps) {
            double sq = Vec3.atCenterOf(step.cell()).distanceToSqr(goal);
            if (sq < bestSq) {
                bestSq = sq;
                best = step;
            }
        }
        return best;
    }

    /** {@code move} の向きへ 1 歩。上の javadoc の 3 通りをここで判定する。 */
    @Nullable
    private static Step stepTowards(Mob mob, BlockPos from, Direction anchor, Direction move) {
        BlockPos cell = from.relative(move);
        if (!canOccupy(mob, cell)) {
            // 3) 内側の角 —— 行き先が塞がっている。そのブロックの面へ張り替えてその場に留まる。
            return move == anchor.getOpposite() ? null : new Step(from, move);
        }
        if (isAnchor(mob.level(), cell, anchor)) {
            // 1) 同じ面を進む。
            return new Step(cell, anchor);
        }
        // 2) 外側の角 —— 壁が途切れた。そのマスで張り付ける面を探し直す。
        Direction next = reanchor(mob, cell, anchor, move);
        return next == null ? null : new Step(cell, next);
    }

    /**
     * {@code cell} で張り付ける面を探す。
     *
     * <p>優先順は <b>いま進んでいる向きの先 → 元の面と同じ向き → 残りの 4 面 → 床</b>。
     * 「進んでいる向きの先」を先に見るのは、壁の端を回り込むとき<b>裏側の面</b>へ移るのが
     * 自然な動きだから。床({@link Direction#DOWN})を最後に回すのは、
     * ちょっとした出っ張りに乗っただけで張り付きをやめてしまわないようにするため。
     */
    @Nullable
    private static Direction reanchor(Mob mob, BlockPos cell, Direction anchor, Direction move) {
        BlockGetter level = mob.level();
        if (move != Direction.DOWN && isAnchor(level, cell, move)) {
            return move;
        }
        if (isAnchor(level, cell, anchor)) {
            return anchor;
        }
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN || d == move || d == anchor) {
                continue;
            }
            if (isAnchor(level, cell, d)) {
                return d;
            }
        }
        return isAnchor(level, cell, Direction.DOWN) ? Direction.DOWN : null;
    }

    /**
     * {@code cell} からいちばん近い張り付き先。{@code preferred} を先に試す。
     * どの面にも触れていなければ {@code null}(= 宙に浮いている)。
     */
    @Nullable
    public static Direction findAnchor(Mob mob, BlockPos cell, @Nullable Direction preferred) {
        BlockGetter level = mob.level();
        if (preferred != null && isAnchor(level, cell, preferred)) {
            return preferred;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (isAnchor(level, cell, d)) {
                return d;
            }
        }
        return isAnchor(level, cell, Direction.UP) ? Direction.UP : null;
    }

    /** {@code cell} の {@code dir} 隣に、張り付ける固い面があるか。 */
    public static boolean isAnchor(BlockGetter level, BlockPos cell, Direction dir) {
        return level.getBlockState(cell.relative(dir)).blocksMotion();
    }

    /**
     * {@code cell} のまわり<b>どこかに</b>張り付ける面があるか(床は数えない)。
     * 経路探索({@link ClimbNodeEvaluator})が「ここは登れるマスか」を見るのに使う。
     */
    public static boolean hasAnyAnchor(BlockGetter level, BlockPos cell) {
        for (Direction d : Direction.values()) {
            if (d != Direction.DOWN && isAnchor(level, cell, d)) {
                return true;
            }
        }
        return false;
    }

    /** そのマスに体がまるごと入るか。 */
    public static boolean canOccupy(Mob mob, BlockPos cell) {
        return canOccupy(mob, mob.level(), cell);
    }

    /**
     * そのマスに体がまるごと入るか。
     *
     * <p>{@code level} を分けて受け取るのは、経路探索が<b>スナップショット
     * ({@code PathNavigationRegion})の上で動く</b>ため —— ワールドを直接読むと
     * 経路探索スレッドの前提が崩れる。
     */
    public static boolean canOccupy(Mob mob, CollisionGetter level, BlockPos cell) {
        if (cell.getY() < mob.level().getMinBuildHeight() || cell.getY() >= mob.level().getMaxBuildHeight()) {
            return false;
        }
        AABB box = mob.getDimensions(mob.getPose())
                .makeBoundingBox(Vec3.atBottomCenterOf(cell))
                .deflate(0.02D);
        return level.noCollision(mob, box);
    }

    /**
     * そのマスに張り付いているときに体を置きたい点。
     *
     * <p>マスの中心ではなく<b>張り付いている面へ寄せる</b> —— 中心だと壁から浮いて見えるうえ、
     * 面から離れて張り付きが切れる。
     */
    public static Vec3 clingPoint(Mob mob, BlockPos cell, Direction anchor) {
        Vec3 bottom = Vec3.atBottomCenterOf(cell);
        if (anchor == Direction.UP) {
            // 天井: 体の上端を天井へ合わせる。
            return new Vec3(bottom.x, cell.getY() + 1.0D - mob.getBbHeight(), bottom.z);
        }
        if (anchor == Direction.DOWN) {
            return bottom;
        }
        double hug = 0.5D - mob.getBbWidth() * 0.5D;
        return new Vec3(bottom.x + anchor.getStepX() * hug, bottom.y, bottom.z + anchor.getStepZ() * hug);
    }

    private SurfaceCrawl() {
    }
}
