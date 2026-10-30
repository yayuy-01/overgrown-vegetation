package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
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
        Step best = closestTo(goal, candidates, from);
        return best != null ? best : closestTo(goal, backtrack, from);
    }

    /**
     * {@code goal} にいちばん近づく手。行き先({@code goal})は足の高さ(経路の点・標的の {@code position()})なので、
     * マスも<b>底の中心</b>で比べる。2026-09-25 まではマスの中心(+0.5)で比べていたため、壁の足元から真上の経路の点へ登るとき
     * 「1 つ上のマス」と「その場で床へ張り替える」が同じ距離になり、先に数える床(DOWN)が勝って張り付いた次の tick に
     * 剥がれていた(GameTest climberSwarmCrossesWallWithoutBreaking)。
     *
     * <p>同じ近さなら、<b>その場で面を張り替えるだけの手より、マスを動く手</b>を選ぶ(2026-10-02)。柱の上の小屋の床下の角で、
     * 「天井の縁へ回り込む手」と「その場で柱の面へ張り替える手」が同点になり、先に数える張り替えが勝って、柱の面と天井の間を
     * 動かずに行き来し続けた(柱の面では、こんどは天井へ張り替える手が選ばれる)。
     */
    @Nullable
    private static Step closestTo(Vec3 goal, List<Step> steps, BlockPos from) {
        Step best = null;
        double bestSq = Double.MAX_VALUE;
        for (Step step : steps) {
            double sq = Vec3.atBottomCenterOf(step.cell()).distanceToSqr(goal);
            boolean tieThatMoves = best != null && Math.abs(sq - bestSq) < TIE_EPSILON
                    && best.cell().equals(from) && !step.cell().equals(from);
            if (sq < bestSq - TIE_EPSILON || best == null || tieThatMoves) {
                bestSq = sq;
                best = step;
            }
        }
        return best;
    }

    /** 2 つの手の近さを同点とみなす差(距離の 2 乗)。 */
    private static final double TIE_EPSILON = 1.0E-6D;

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
        if (next != null) {
            return new Step(cell, next);
        }
        // 2') 外側の角を巻く —— 行き先のマスにも掴まる面が無ければ、元の面の縁を回り込んで裏の面へ
        // (庇の下 → 庇の外の面。2026-09-25、登る群れの天井のルート)。回り込んだ先は 行き先 + 元の面の向き、張り付く面は来た向き。
        if (anchor.getAxis() != move.getAxis()) {
            BlockPos wrap = cell.relative(anchor);
            Direction back = move.getOpposite();
            if (back != Direction.DOWN && canOccupy(mob, wrap) && isAnchor(mob.level(), wrap, back)) {
                return new Step(wrap, back);
            }
        }
        return null;
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

    /**
     * 壁({@code anchor} は水平)の<b>上端</b>に着いていて、壁の上に立てるなら、その立つマス(壁の 1 段上)。
     * 条件: {@code from} の {@code anchor} 側が壁、その壁の上のマスと {@code from} の上のマスに体が入る(= 壁はこの段で終わり、
     * 頭の上も空いている)。面の上の 1 歩({@link #next})では、壁の上端の外側の角(真上は宙、張り付く面が無い)を越えられないので、
     * 呼び出し側はこれを見て跳び上がる(2026-09-25、登る群れ。それまでは壁の上端で横へ這い、行き詰まって剥がれていた)。
     */
    @Nullable
    public static BlockPos mantleCell(Mob mob, BlockPos from, Direction anchor) {
        if (anchor.getAxis() == Direction.Axis.Y || !isAnchor(mob.level(), from, anchor)) {
            return null;
        }
        BlockPos top = from.relative(anchor).above();
        return canOccupy(mob, top) && canOccupy(mob, from.above()) ? top : null;
    }

    /**
     * {@code from} から {@code anchor} の面に沿って真上へ、面が続いて体が入るかぎり登った先のマス(面の上端の横)。
     * 行き先が面の向こう(壁・柵の裏)にあるとき、ここまで登れば {@link #mantleCell} で越えられる(2026-10-02)。
     */
    public static BlockPos faceTop(Mob mob, BlockPos from, Direction anchor) {
        BlockGetter level = mob.level();
        BlockPos at = from;
        for (int i = 0; i < FACE_SCAN; i++) {
            BlockPos up = at.above();
            if (!isAnchor(level, up, anchor) || !canOccupy(mob, up)) {
                break;
            }
            at = up;
        }
        return at;
    }

    /**
     * 天井({@code from} の上)に張り付いているとき、天井が途切れるいちばん近いマス(縁の 1 つ外)。近い順、同じ近さなら
     * {@code toward} に近いほう。体が入らないマスに当たった向きは数えない。無ければ null。
     * 天井の上にいる相手へは、縁を回って横の面へ移るしかない({@link #next} の「外側の角を巻く」。2026-10-02、柱の上の小屋)。
     */
    @Nullable
    public static BlockPos ceilingEdge(Mob mob, BlockPos from, Vec3 toward) {
        BlockGetter level = mob.level();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction d : HORIZONTALS) {
            BlockPos at = from;
            for (int k = 1; k <= EDGE_SCAN; k++) {
                at = at.relative(d);
                if (!canOccupy(mob, at)) {
                    break;
                }
                if (!isAnchor(level, at, Direction.UP)) {
                    double score = k + 0.1D * Vec3.atBottomCenterOf(at).distanceTo(toward);
                    if (score < bestScore) {
                        bestScore = score;
                        best = at;
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** {@link #faceTop} が面を登って探す高さの上限(マス)。 */
    private static final int FACE_SCAN = 24;
    /** {@link #ceilingEdge} が天井の縁を探す距離(マス)。 */
    private static final int EDGE_SCAN = 8;
    /** 東西南北(呼ぶたびに配列を複製する {@code Plane.HORIZONTAL} の反復を避ける)。 */
    private static final Direction[] HORIZONTALS = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /**
     * {@code cell} の {@code dir} 隣に、張り付ける固い面があるか。
     *
     * <p>横の面は、隣のマスが空いていても<b>真下のブロックの当たり判定が食い込んでいれば</b>面に数える({@link #raisedFromBelow}。
     * フェンス・塀の上の 0.5)。数えないと、塀の横を登る列が塀の上端より 1 つ低いところで途切れ、上の 0.5 を越えられなかった
     * (ユーザー報告 2026-10-02「フェンスや塀を登って超えられない」。基地の胸壁・金網の柱も塀のブロック)。
     */
    public static boolean isAnchor(BlockGetter level, BlockPos cell, Direction dir) {
        BlockPos side = cell.relative(dir);
        if (level.getBlockState(side).blocksMotion()) {
            return true;
        }
        return dir.getAxis().isHorizontal() && raisedFromBelow(level, side);
    }

    /**
     * {@code cell} のマスに、<b>真下のブロックの当たり判定が食い込んでいる</b>か(フェンス・塀の当たり判定は高さ 1.5)。
     * 食い込んだ 0.5 は、横から見れば張り付ける面、上から見れば立てる床になる({@code ClimbNodeEvaluator} の足場)。
     * はみ出す当たり判定を持つブロックはバニラが前もって数えている({@code hasLargeCollisionShape})ので、ほとんどのマスはそこで終わる。
     */
    public static boolean raisedFromBelow(BlockGetter level, BlockPos cell) {
        BlockPos below = cell.below();
        BlockState state = level.getBlockState(below);
        return state.hasLargeCollisionShape()
                && state.getCollisionShape(level, below).max(Direction.Axis.Y) > 1.0D;
    }

    /**
     * {@code cell} のまわり<b>どこかに</b>張り付ける面があるか(床は数えない)。
     * 経路探索({@link ClimbNodeEvaluator})が「ここは登れるマスか」を見るのに使う。
     */
    public static boolean hasAnyAnchor(BlockGetter level, BlockPos cell) {
        for (Direction d : ANCHOR_DIRECTIONS) {
            if (isAnchor(level, cell, d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link #hasAnyAnchor} が見る向き(床を除く。{@code Direction.values()} から DOWN を抜いた同じ順)。
     * 経路探索のノードごとに呼ばれるので、呼ぶたびに配列を複製する {@code values()} を使わない(2026-09-24 軽量化、報告 277)。
     */
    private static final Direction[] ANCHOR_DIRECTIONS = {
            Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

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
