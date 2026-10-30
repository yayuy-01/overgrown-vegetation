package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.block.RepellentLampBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>点いている</b>忌避灯({@link RepellentLampBlock})の台帳(2026-09-19)。
 *
 * <p>ロード済みで蔦に覆われていない忌避灯の座標をワールドごとに持つ。
 * {@code entity/RepellentAvoidGoal}(グループ1〜4 が近寄らない)と
 * {@code entity/VelophyllLampBombGoal}(ヴェロフィルが爆撃しに来る)が毎回ここを引くので、
 * ブロックを走査せずに済む。登録・解除は {@link com.yayuy.overgrownvegetation.block.RepellentLampBlockEntity}
 * のロード/アンロード/破壊と、{@link RepellentLampBlock#setCovered} の状態変更から。
 */
public final class RepellentLamps {

    private static final Map<ResourceKey<Level>, Set<BlockPos>> ACTIVE = new ConcurrentHashMap<>();

    /**
     * {@link #ACTIVE} と同じ灯りを、水平 {@code 1 << CELL_SHIFT} ブロック四方の区画ごとに分けたもの
     * (2026-09-24 軽量化、監査 653 / 920 / 1780)。{@link #nearestActive} は半径にかかる区画だけを見る ——
     * 前はグループ1〜4 の Mob が 2 tick ごとに、次元の灯りを全部回していた。
     * 追加・削除・全消去は必ず {@link #ACTIVE} と一緒に行う(ここだけが書き換える)。
     */
    private static final Map<ResourceKey<Level>, Map<Long, Set<BlockPos>>> CELLS = new ConcurrentHashMap<>();
    /** 区画の大きさ(32 ブロック)。忌避の半径(既定 12)なら 2×2 区画まで、ヴェロフィルの 64 でも 5×5。仮決め 2026-09-24。 */
    private static final int CELL_SHIFT = 5;

    public static void register(Level level, BlockPos pos) {
        BlockPos key = pos.immutable();
        ACTIVE.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(key);
        CELLS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                .compute(cellOf(key.getX(), key.getZ()), (k, cell) -> {
                    Set<BlockPos> set = cell != null ? cell : ConcurrentHashMap.newKeySet();
                    set.add(key);
                    return set;
                });
    }

    public static void unregister(Level level, BlockPos pos) {
        Set<BlockPos> set = ACTIVE.get(level.dimension());
        if (set != null) {
            set.remove(pos);
        }
        Map<Long, Set<BlockPos>> cells = CELLS.get(level.dimension());
        if (cells != null) {
            cells.computeIfPresent(cellOf(pos.getX(), pos.getZ()), (k, cell) -> {
                cell.remove(pos);
                return cell.isEmpty() ? null : cell;
            });
        }
    }

    public static void clearAll() {
        ACTIVE.clear();
        CELLS.clear();
    }

    private static long cellOf(int blockX, int blockZ) {
        return ChunkPos.asLong(blockX >> CELL_SHIFT, blockZ >> CELL_SHIFT);
    }

    /** {@code from} から {@code range} 以内で最も近い、点いている忌避灯。無ければ null。 */
    @Nullable
    public static BlockPos nearestActive(Level level, Vec3 from, double range) {
        Set<BlockPos> set = ACTIVE.get(level.dimension());
        if (set == null || set.isEmpty()) {
            return null;
        }
        // 2026-09-24 軽量化(監査 653 / 920 / 1780): 半径にかかる区画({@link #CELLS})だけを見る。
        // 灯りの中心は座標 + 0.5 なので、区画は 1 ブロック余分に取る(半径の中の灯りは必ずこの中にある)。
        Map<Long, Set<BlockPos>> cells = CELLS.get(level.dimension());
        if (cells == null || !(range >= 0.0D)) {
            return nearestLinear(set, from, range);
        }
        int minCx = Mth.floor(from.x - range - 1.0D) >> CELL_SHIFT;
        int maxCx = Mth.floor(from.x + range + 1.0D) >> CELL_SHIFT;
        int minCz = Mth.floor(from.z - range - 1.0D) >> CELL_SHIFT;
        int maxCz = Mth.floor(from.z + range + 1.0D) >> CELL_SHIFT;
        if ((long) (maxCx - minCx + 1) * (long) (maxCz - minCz + 1) >= set.size()) {
            // 区画を引く回数のほうが灯りの数より多い(灯りが少ない・半径が大きい)なら、全部回すほうが速い。
            return nearestLinear(set, from, range);
        }
        BlockPos best = null;
        double bestSq = range * range;
        int ties = 0; // いまの最短距離ぴったりの灯りの数
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                Set<BlockPos> cell = cells.get(ChunkPos.asLong(cx, cz));
                if (cell == null) {
                    continue;
                }
                for (BlockPos pos : cell) {
                    double sq = from.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = pos;
                        ties = 1;
                    } else if (sq == bestSq) {
                        best = pos;
                        ties++;
                    }
                }
            }
        }
        if (ties > 1) {
            // 距離がぴったり同じ灯りが複数ある。前の選び方(台帳を全部回して最後に当たったもの)と
            // 同じにするため、そのときだけ全部を回し直す。
            return nearestLinear(set, from, range);
        }
        return best;
    }

    /**
     * {@code from} から {@code range} 以内の点いている忌避灯を<b>全部</b>(並びは決まっていない。新しいリストで返す)。
     * TD の攻城({@code td/TdSiege})が、いちばん近い灯り 1 つだけでなく、寄り道を塞いでいる灯りをどれでも見られるように
     * (レビュー指摘 2026-09-25: 近くに見えない囮の灯りを置くと、本当に塞いでいる灯りが無視された)。
     */
    public static List<BlockPos> activeWithin(Level level, Vec3 from, double range) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> set = ACTIVE.get(level.dimension());
        if (set == null || set.isEmpty() || !(range >= 0.0D)) {
            return out;
        }
        double rangeSq = range * range;
        Map<Long, Set<BlockPos>> cells = CELLS.get(level.dimension());
        int minCx = Mth.floor(from.x - range - 1.0D) >> CELL_SHIFT;
        int maxCx = Mth.floor(from.x + range + 1.0D) >> CELL_SHIFT;
        int minCz = Mth.floor(from.z - range - 1.0D) >> CELL_SHIFT;
        int maxCz = Mth.floor(from.z + range + 1.0D) >> CELL_SHIFT;
        if (cells == null || (long) (maxCx - minCx + 1) * (long) (maxCz - minCz + 1) >= set.size()) {
            for (BlockPos pos : set) {
                if (from.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= rangeSq) {
                    out.add(pos);
                }
            }
            return out;
        }
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                Set<BlockPos> cell = cells.get(ChunkPos.asLong(cx, cz));
                if (cell == null) {
                    continue;
                }
                for (BlockPos pos : cell) {
                    if (from.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= rangeSq) {
                        out.add(pos);
                    }
                }
            }
        }
        return out;
    }

    /** 前からの選び方: 台帳を全部回し、{@code range} 以内でいちばん近いもの(同じ距離なら後に回ったもの)。 */
    @Nullable
    private static BlockPos nearestLinear(Set<BlockPos> set, Vec3 from, double range) {
        BlockPos best = null;
        double bestSq = range * range;
        for (BlockPos pos : set) {
            double sq = from.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (sq <= bestSq) {
                bestSq = sq;
                best = pos;
            }
        }
        return best;
    }

    /**
     * {@code center} から {@code radius} 以内の点いている忌避灯をすべて蔦で覆う(= 止める)。
     * ヴェロフィルの爆弾が炸裂したときに呼ばれる。覆った数を返す。
     */
    public static int coverNear(ServerLevel level, Vec3 center, double radius) {
        if (com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()) {
            return 0; // /ov corruption false(2026-10-10 ユーザー指定): 胞子爆弾でも忌避灯を覆わない
        }
        Set<BlockPos> set = ACTIVE.get(level.dimension());
        if (set == null || set.isEmpty() || radius <= 0.0D) {
            return 0;
        }
        double radiusSq = radius * radius;
        List<BlockPos> hit = new ArrayList<>();
        for (BlockPos pos : set) {
            if (center.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= radiusSq) {
                hit.add(pos);
            }
        }
        int covered = 0;
        for (BlockPos pos : hit) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof RepellentLampBlock lamp) {
                lamp.setCovered(level, pos, state, true);
                covered++;
            } else {
                unregister(level, pos); // 台帳がずれていた(ブロックが別物に変わっている)
            }
        }
        return covered;
    }

    private RepellentLamps() {
    }
}
