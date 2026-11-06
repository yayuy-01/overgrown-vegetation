package com.yayuy.overgrownvegetation.entity.cling;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import org.jetbrains.annotations.Nullable;

/**
 * 面の点({@link SurfaceNode})どうしの<b>つながり</b>(2026-11-05)。設計ノート「全面に張り付く移動」1 節の 3 種類が芯:
 * <ol>
 *     <li>{@link Kind#PLANE 同じ平面を隣へ} —— 行き先のマスが空いていて、同じ向きの面が続いている。</li>
 *     <li>{@link Kind#INNER 内側の角} —— 行き先のマスが塞がっている。その塞いでいるブロックの面へ<b>その場で</b>張り替える
 *     (床 → 壁、壁 → 天井。壁を登りきって庇に当たったら天井へ)。</li>
 *     <li>{@link Kind#OUTER 外側の角} —— 行き先のマスは空いているが面が途切れた。面の縁を回り込んで裏の面へ
 *     (壁の上端 → 壁の上の床、庇の下 → 庇の外の面、幅 1 の幹を巻く)。途中で行き先のマス({@code via})を通る。</li>
 * </ol>
 * 前の作り({@code parked/…/SurfaceCrawl#stepTowards})の判定をそのまま引き継いだ。違いは、前は「外側の角で床へは回らない」
 * (壁の上端は {@code mantleCell} の跳び上がりで別に越えていた)のを、床もただの下向きの面として同じ角で回ること。
 *
 * <p>ほかに、同じ仕組みで地面を歩くための<b>床だけのつながり</b>(段を上がる・落ちる)と、葉の中のつながりを足した:
 * <ul>
 *     <li>{@link Kind#SWITCH 面を替えて進む} —— 進んだ先のマスで今の面が途切れ、別の面がある(窓の切れ目の脇の壁など)。</li>
 *     <li>{@link Kind#STEP_UP 段を上がる}【仮 11-05 Claude】—— 床から 1 段上の床へ。角 2 つ(床 → 壁 → 段の上)でも行けるが、
 *     地面のでこぼこのたびに体を 2 回回すのは不自然なので、バニラの段差の上がり方(体はそのまま)で上がる 1 歩を足した。</li>
 *     <li>{@link Kind#DROP 落ちる} —— 床の縁から 1〜3 段下の床へ歩いて落ちる(落下の傷みが出ない高さまで)。</li>
 *     <li>{@link Kind#FOLIAGE 葉の中} —— 葉へ潜る・葉の中を 6 方向へ・葉から出て面に付く(11-05 ユーザー決定「葉の中は潜れて自由自在に動ける」)。</li>
 * </ul>
 */
public final class SurfaceGraph {

    /** つながりの種類。体の動かし方({@link ClingMover})が見分ける。 */
    public enum Kind {
        /** 最初の点(経路の頭)。 */
        START,
        PLANE,
        INNER,
        OUTER,
        SWITCH,
        STEP_UP,
        DROP,
        FOLIAGE;

        /** 床から床への 1 歩(体はバニラの物理で歩く)。 */
        public boolean walksOnFloor(SurfaceNode from, SurfaceNode to) {
            return from.isFloor() && to.isFloor() && (this == PLANE || this == STEP_UP || this == DROP || this == SWITCH);
        }
    }

    /** 1 歩の重み(ブロックで数えた道のり)。【仮 11-05 Claude】 */
    static final double COST_PLANE = 1.0D;
    static final double COST_INNER = 0.8D;
    static final double COST_OUTER = 2.0D;
    static final double COST_SWITCH = 1.4D;
    static final double COST_STEP = 1.5D;
    static final double COST_FOLIAGE = 1.6D;
    static final double COST_FOLIAGE_EXIT = 1.2D;
    /** 落ちる高さの上限(マス)。4 以上は落下の傷みが出る。 */
    static final int MAX_DROP = 3;

    private static final Direction[] ALL = Direction.values();

    /** つながりの受け取り口。 */
    @FunctionalInterface
    public interface Sink {
        void accept(SurfaceNode to, Kind kind, @Nullable BlockPos via, double cost);
    }

    private SurfaceGraph() {
    }

    /** {@code n} から 1 歩で行ける点を全部 {@code sink} へ渡す。 */
    public static void expand(BlockGetter g, SurfaceNode n, Sink sink) {
        if (n.isFoliage()) {
            expandFoliage(g, n, sink);
            return;
        }
        Direction a = n.face();
        BlockPos c = n.cell();
        // 張り付いている面そのものが葉なら、そこへ潜れる(梢の上から下へ・幹の葉の面から中へ)。
        BlockPos into = c.relative(a);
        if (ClingRules.isFoliageCell(g, into)) {
            sink.accept(SurfaceNode.foliage(into), Kind.FOLIAGE, null, COST_FOLIAGE);
        }
        for (Direction m : ALL) {
            if (m.getAxis() == a.getAxis()) {
                continue; // 面から離れる/めり込む向きには動けない
            }
            BlockPos t = c.relative(m);
            if (ClingRules.isFoliageCell(g, t)) {
                sink.accept(SurfaceNode.foliage(t), Kind.FOLIAGE, null, COST_FOLIAGE);
                continue;
            }
            if (!ClingRules.canOccupy(g, t)) {
                // 2) 内側の角 —— 塞いでいるブロックの面へその場で張り替える。
                if (ClingRules.isAnchor(g, c, m)) {
                    sink.accept(SurfaceNode.of(c, m), Kind.INNER, null, COST_INNER);
                }
                // 床だけ: 1 段上の床へ上がる(体は回さない)。
                if (a == Direction.DOWN && m.getAxis().isHorizontal()) {
                    BlockPos up = t.above();
                    if (ClingRules.canOccupy(g, up) && ClingRules.hasFloor(g, up) && ClingRules.canOccupy(g, c.above())) {
                        sink.accept(SurfaceNode.of(up, Direction.DOWN), Kind.STEP_UP, null, COST_STEP);
                    }
                }
                continue;
            }
            if (ClingRules.isAnchor(g, t, a)) {
                // 1) 同じ平面を隣へ。
                sink.accept(SurfaceNode.of(t, a), Kind.PLANE, null, COST_PLANE);
                continue;
            }
            // 3) 外側の角 —— 面の縁を回って裏の面へ(行き先のマス t を通る)。
            BlockPos wrap = t.relative(a);
            Direction back = m.getOpposite();
            if (ClingRules.canOccupy(g, wrap) && ClingRules.isAnchor(g, wrap, back)) {
                sink.accept(SurfaceNode.of(wrap, back), Kind.OUTER, t, COST_OUTER);
            }
            // 面が途切れた先のマスに、別の面があればそこへ付く。
            for (Direction d : ALL) {
                if (d == a || d == m.getOpposite()) {
                    continue;
                }
                if (ClingRules.isAnchor(g, t, d)) {
                    sink.accept(SurfaceNode.of(t, d), Kind.SWITCH, null, COST_SWITCH);
                }
            }
            // 床だけ: 縁から下の床へ落ちる。
            if (a == Direction.DOWN && m.getAxis().isHorizontal()) {
                BlockPos at = t;
                for (int k = 1; k <= MAX_DROP; k++) {
                    at = at.below();
                    if (!ClingRules.canOccupy(g, at)) {
                        break;
                    }
                    if (ClingRules.hasFloor(g, at)) {
                        sink.accept(SurfaceNode.of(at, Direction.DOWN), Kind.DROP, t, COST_PLANE + 0.5D * k);
                        break;
                    }
                }
            }
        }
    }

    private static void expandFoliage(BlockGetter g, SurfaceNode n, Sink sink) {
        BlockPos c = n.cell();
        for (Direction m : ALL) {
            BlockPos t = c.relative(m);
            if (ClingRules.isFoliageCell(g, t)) {
                sink.accept(SurfaceNode.foliage(t), Kind.FOLIAGE, null, COST_FOLIAGE);
            } else if (ClingRules.canOccupy(g, t)) {
                // 葉から出て、出たマスの面に付く。
                for (Direction d : ALL) {
                    if (ClingRules.isAnchor(g, t, d)) {
                        sink.accept(SurfaceNode.of(t, d), Kind.FOLIAGE, null, COST_FOLIAGE_EXIT);
                    }
                }
            }
        }
    }

    /** {@code cell} で張り付ける面を 1 つ選ぶ(床 → 横 → 天井の順)。どこにも付けなければ null。 */
    @Nullable
    public static SurfaceNode nodeAt(BlockGetter g, BlockPos cell, @Nullable Direction preferred) {
        if (ClingRules.isFoliageCell(g, cell)) {
            return SurfaceNode.foliage(cell);
        }
        if (!ClingRules.canOccupy(g, cell)) {
            return null;
        }
        if (preferred != null && ClingRules.isAnchor(g, cell, preferred)) {
            return SurfaceNode.of(cell, preferred);
        }
        if (ClingRules.isAnchor(g, cell, Direction.DOWN)) {
            return SurfaceNode.of(cell, Direction.DOWN);
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (ClingRules.isAnchor(g, cell, d)) {
                return SurfaceNode.of(cell, d);
            }
        }
        return ClingRules.isAnchor(g, cell, Direction.UP) ? SurfaceNode.of(cell, Direction.UP) : null;
    }
}
