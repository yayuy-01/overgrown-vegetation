package com.yayuy.overgrownvegetation.entity.cling;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 面の点の上の A*(2026-11-05)。床も壁も天井も葉の中も<b>同じ 1 つのグラフ</b>({@link SurfaceGraph})で引く
 * (11-05 ユーザー決定「地面も同じ面を歩く仕組みに統一する」)。前の作りの「1 歩ずつ目的地に一番近づく手」(貪欲)と違い、
 * 遠回りが要る形(庇の裏・柱の上の小屋)も解ける。
 *
 * <p>読むのは {@link BlockGetter} だけなので、裏のスレッドで {@link WorldSnapshot} の上で回す({@link SurfacePaths})。
 * 調べる点の数に上限があり、届かなければ<b>行き先に一番近づいた点まで</b>の経路を返す(バニラの経路と同じ考え方)。
 */
public final class SurfacePathfinder {

    /** 経路の 1 歩: 行き先の点・つながりの種類・途中で通るマス(外側の角・落ちる縁。無ければ null)。 */
    public record Step(SurfaceNode node, SurfaceGraph.Kind kind, @Nullable BlockPos via) {
    }

    /**
     * 引いた経路。{@code steps.get(0)} は出発点({@link SurfaceGraph.Kind#START})。
     *
     * @param reached  行き先の範囲に着いたか(false なら一番近づいた所まで)
     * @param explored 調べた点の数
     */
    public record Result(List<Step> steps, boolean reached, int explored) {
        public SurfaceNode last() {
            return steps.get(steps.size() - 1).node();
        }
    }

    /**
     * 行き先: 相手の当たりの箱と、そこから何ブロック以内のマスなら着いたとみなすか。
     *
     * @param box   相手の当たりの箱(相手が居なければ点を少し膨らませた箱)
     * @param reach マスの箱と相手の箱の隙間がこれ以下なら着いた
     */
    public record Goal(AABB box, double reach) {
        double gap(BlockPos cell) {
            double dx = axisGap(cell.getX(), cell.getX() + 1.0D, box.minX, box.maxX);
            double dy = axisGap(cell.getY(), cell.getY() + 1.0D, box.minY, box.maxY);
            double dz = axisGap(cell.getZ(), cell.getZ() + 1.0D, box.minZ, box.maxZ);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private static double axisGap(double a0, double a1, double b0, double b1) {
            return a1 < b0 ? b0 - a1 : (b1 < a0 ? a0 - b1 : 0.0D);
        }

        boolean reached(SurfaceNode n) {
            return gap(n.cell()) <= reach;
        }

        double heuristic(SurfaceNode n) {
            return Math.max(0.0D, gap(n.cell()) - reach);
        }
    }

    private SurfacePathfinder() {
    }

    private static final class Rec {
        final SurfaceNode node;
        double g = Double.POSITIVE_INFINITY;
        @Nullable
        Rec parent;
        SurfaceGraph.Kind kind = SurfaceGraph.Kind.START;
        @Nullable
        BlockPos via;
        boolean closed;

        Rec(SurfaceNode node) {
            this.node = node;
        }
    }

    private record Open(double f, double h, long seq, Rec rec) implements Comparable<Open> {
        @Override
        public int compareTo(Open o) {
            int c = Double.compare(f, o.f);
            if (c != 0) {
                return c;
            }
            c = Double.compare(h, o.h);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    /**
     * {@code start} から {@code goal} へ。{@code bounds} が null でなければ、その中のマスだけを点にする({@link WorldSnapshot#inside})。
     *
     * @param maxNodes 調べる点の数の上限
     */
    public static Result find(BlockGetter g, SurfaceNode start, Goal goal, int maxNodes, @Nullable WorldSnapshot bounds) {
        Map<SurfaceNode, Rec> recs = new HashMap<>();
        PriorityQueue<Open> open = new PriorityQueue<>();
        Rec first = new Rec(start);
        first.g = 0.0D;
        recs.put(start, first);
        long[] seq = {0L};
        double h0 = goal.heuristic(start);
        open.add(new Open(h0, h0, seq[0]++, first));
        Rec best = first;
        double bestH = h0;
        int explored = 0;
        Rec found = null;
        while (!open.isEmpty() && explored < maxNodes) {
            Open o = open.poll();
            Rec cur = o.rec();
            if (cur.closed || o.f() > cur.g + o.h() + 1.0E-9D) {
                continue; // 古い順番待ち
            }
            cur.closed = true;
            explored++;
            if (goal.reached(cur.node)) {
                found = cur;
                break;
            }
            double ch = o.h();
            if (ch < bestH - 1.0E-9D || (Math.abs(ch - bestH) < 1.0E-9D && cur.g < best.g)) {
                bestH = ch;
                best = cur;
            }
            SurfaceGraph.expand(g, cur.node, (to, kind, via, cost) -> {
                if (bounds != null && !bounds.inside(to.cell())) {
                    return;
                }
                Rec r = recs.computeIfAbsent(to, Rec::new);
                if (r.closed) {
                    return;
                }
                double ng = cur.g + cost;
                if (ng >= r.g - 1.0E-9D) {
                    return;
                }
                r.g = ng;
                r.parent = cur;
                r.kind = kind;
                r.via = via;
                double h = goal.heuristic(to);
                open.add(new Open(ng + h, h, seq[0]++, r));
            });
        }
        Rec end = found != null ? found : best;
        List<Step> steps = new ArrayList<>();
        for (Rec r = end; r != null; r = r.parent) {
            steps.add(new Step(r.node, r.kind, r.via));
        }
        Collections.reverse(steps);
        return new Result(List.copyOf(steps), found != null, explored);
    }
}
