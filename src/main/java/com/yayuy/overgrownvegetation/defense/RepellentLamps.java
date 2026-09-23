package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.block.RepellentLampBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
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

    public static void register(Level level, BlockPos pos) {
        ACTIVE.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
    }

    public static void unregister(Level level, BlockPos pos) {
        Set<BlockPos> set = ACTIVE.get(level.dimension());
        if (set != null) {
            set.remove(pos);
        }
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    /** {@code from} から {@code range} 以内で最も近い、点いている忌避灯。無ければ null。 */
    @Nullable
    public static BlockPos nearestActive(Level level, Vec3 from, double range) {
        Set<BlockPos> set = ACTIVE.get(level.dimension());
        if (set == null || set.isEmpty()) {
            return null;
        }
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
