package com.yayuy.overgrownvegetation.worldgen;

import com.yayuy.overgrownvegetation.block.BranchJointBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * 枝 1 本に<b>枝の継ぎ目</b>({@link BranchJointBlock})をどう置くかの計画(2026-11-01。隙間埋めと先細りに使う = ユーザー決定、細部は仮決め)。
 * 世界を見ない純粋な関数 {@link #plan} と、計画どおりに置く {@link #place}・腕を組み直す {@link #reconnect} に分けてある。
 * 原木と継ぎ目のブロックは引数で渡すので、封印木など別の木の枝にも使える。
 *
 * <ul>
 *     <li><b>隙間埋め</b>: 列の隣り合う 2 マスが面で接していない(辺か角で接する)とき、その間を面で繋ぐ空いたマスに太い継ぎ目を置く。
 *     辺で接するなら 1 マス、角で接するなら L 字に 2 マス。候補が複数あれば低い方(合計の y が小さい)→ 根元に近い方を選ぶ(仮決め)。
 *     候補が空いていなければ(空いているかは呼び出し側の {@code free})次の候補、どれも駄目なら埋めない。</li>
 *     <li><b>先細り</b>: 枝の最後の 1〜2 マスを原木の代わりに継ぎ目に。3 マス以上なら最後から 2 番目が太い・最後が細い、2 マスなら最後だけ細い、
 *     1 マスなら原木のまま(仮決め)。</li>
 * </ul>
 */
public final class BranchJoints {

    /** 置く継ぎ目 1 個。 */
    public record Joint(BlockPos pos, boolean thick) {
    }

    /** {@code logs} = 原木を置くマス(根元 → 先の順)、{@code joints} = 継ぎ目を置くマス。 */
    public record Plan(List<BlockPos> logs, List<Joint> joints) {
    }

    /**
     * @param path   枝の列。先頭は枝が生える幹のマス(置かない)、続けて枝の原木のマスを根元 → 先の順に
     * @param taper  先細りにするか
     * @param fill   隙間を埋めるか
     * @param free   隙間埋めの候補のマスが空いているか(空気・置き換えられる物など)
     * @param wood   そのマスにもう木(原木・継ぎ目)があるか。幹など既にある木で繋がっている隙間は埋めない
     */
    public static Plan plan(List<BlockPos> path, boolean taper, boolean fill, Predicate<BlockPos> free, Predicate<BlockPos> wood) {
        int steps = path.size() - 1;
        List<BlockPos> logs = new ArrayList<>();
        List<Joint> joints = new ArrayList<>();
        if (steps <= 0) {
            return new Plan(logs, joints);
        }
        int thickTip = -1, thinTip = -1;
        if (taper && steps >= 2) {
            thinTip = steps;
            if (steps >= 3) {
                thickTip = steps - 1;
            }
        }
        for (int i = 1; i <= steps; i++) {
            if (i == thinTip) {
                joints.add(new Joint(path.get(i), false));
            } else if (i == thickTip) {
                joints.add(new Joint(path.get(i), true));
            } else {
                logs.add(path.get(i));
            }
        }
        if (fill) {
            Set<BlockPos> taken = new HashSet<>(path);
            BlockPos root = path.get(0);
            for (int i = 0; i < steps; i++) {
                List<BlockPos> route = bestRoute(path.get(i), path.get(i + 1), root, taken, free, wood);
                for (BlockPos p : route) {
                    taken.add(p);
                    joints.add(new Joint(p, true));
                }
            }
        }
        return new Plan(logs, joints);
    }

    /** a から b へ 1 軸ずつ進む道のうち、途中のマスがすべて空いている最良のもの(途中のマスだけを返す)。面で接していれば空。 */
    private static List<BlockPos> bestRoute(BlockPos a, BlockPos b, BlockPos root, Set<BlockPos> taken, Predicate<BlockPos> free,
            Predicate<BlockPos> wood) {
        int[] delta = {b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ()};
        List<Integer> axes = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            if (delta[k] != 0) {
                if (Math.abs(delta[k]) > 1) {
                    return List.of(); // 1 マスより離れた列は想定外(埋めない)
                }
                axes.add(k);
            }
        }
        if (axes.size() <= 1) {
            return List.of();
        }
        List<List<BlockPos>> routes = new ArrayList<>();
        for (List<Integer> order : permutations(axes)) {
            List<BlockPos> route = new ArrayList<>();
            int[] at = {a.getX(), a.getY(), a.getZ()};
            for (int j = 0; j < order.size() - 1; j++) {
                at[order.get(j)] += delta[order.get(j)];
                route.add(new BlockPos(at[0], at[1], at[2]));
            }
            routes.add(route);
        }
        routes.sort(Comparator.<List<BlockPos>>comparingInt(r -> r.stream().mapToInt(BlockPos::getY).sum())
                .thenComparingDouble(r -> r.stream().mapToDouble(p -> p.distSqr(root)).sum()));
        for (List<BlockPos> route : routes) {
            if (route.stream().allMatch(p -> taken.contains(p) || wood.test(p))) {
                return List.of(); // 幹などでもう面で繋がっている
            }
        }
        for (List<BlockPos> route : routes) {
            boolean ok = true;
            List<BlockPos> place = new ArrayList<>();
            for (BlockPos p : route) {
                if (taken.contains(p) || wood.test(p)) {
                    continue; // もう原木か継ぎ目がある = 繋がっている
                }
                if (!free.test(p)) {
                    ok = false;
                    break;
                }
                place.add(p);
            }
            if (ok) {
                return place;
            }
        }
        return List.of();
    }

    private static List<List<Integer>> permutations(List<Integer> items) {
        if (items.size() <= 1) {
            return List.of(new ArrayList<>(items));
        }
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<Integer> rest = new ArrayList<>(items);
            Integer head = rest.remove(i);
            for (List<Integer> tail : permutations(rest)) {
                tail.add(0, head);
                out.add(tail);
            }
        }
        return out;
    }

    /**
     * 計画どおりに原木と継ぎ目を置く({@code set} は呼び出し側の置き方。強制で置くなど)。置いた継ぎ目のマスを返す
     * (腕は全部置き終えてから {@link #reconnect} で組む)。
     */
    public static List<BlockPos> place(LevelAccessor level, Plan plan, BlockState log, BlockState joint, BiConsumer<BlockPos, BlockState> set) {
        for (BlockPos p : plan.logs()) {
            set.accept(p, log);
        }
        List<BlockPos> placed = new ArrayList<>(plan.joints().size());
        for (Joint j : plan.joints()) {
            // 水は置く前に読む(置いた後では元の水が消えている)
            boolean water = level.getFluidState(j.pos()).getType() == Fluids.WATER;
            set.accept(j.pos(), joint.setValue(BranchJointBlock.THICK, j.thick()).setValue(BranchJointBlock.WATERLOGGED, water));
            placed.add(j.pos());
        }
        return placed;
    }

    /** 置いた継ぎ目の腕を今のまわりから組み直す(生成中は隣の updateShape が呼ばれないことがあるので)。 */
    public static void reconnect(LevelAccessor level, Iterable<BlockPos> joints) {
        for (BlockPos p : joints) {
            BlockState here = level.getBlockState(p);
            if (here.getBlock() instanceof BranchJointBlock) {
                BlockState next = BranchJointBlock.connectedState(level, p, here);
                if (next != here) {
                    level.setBlock(p, next, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private BranchJoints() {
    }
}
