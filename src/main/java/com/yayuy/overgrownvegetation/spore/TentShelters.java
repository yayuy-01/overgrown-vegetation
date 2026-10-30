package com.yayuy.overgrownvegetation.spore;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 防胞子の天幕のまわり(2026-10-16、⑮。ユーザー指定「周り半径 3 の胞子濃度を 0 にする」)。
 * 読み込まれている天幕の座標を次元ごとに持ち、{@link SporeConcentration#percentAt(net.minecraft.server.level.ServerLevel, BlockPos)} が
 * この中なら 0 を返す(チャンクの値のキャッシュの外で見る)。天幕が無い次元はすぐ抜ける。
 */
public final class TentShelters {

    public static final double RADIUS = 3.0D;
    private static final double RADIUS_SQ = (RADIUS + 0.5D) * (RADIUS + 0.5D);
    private static final Map<ResourceKey<Level>, Set<BlockPos>> TENTS = new ConcurrentHashMap<>();

    private TentShelters() {
    }

    public static void register(Level level, BlockPos pos) {
        TENTS.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
    }

    public static void unregister(Level level, BlockPos pos) {
        Set<BlockPos> set = TENTS.get(level.dimension());
        if (set != null) {
            set.remove(pos);
        }
    }

    public static void clearAll() {
        TENTS.clear();
    }

    /** {@code pos} が天幕の半径 3 の中か。 */
    public static boolean covers(Level level, BlockPos pos) {
        Set<BlockPos> set = TENTS.get(level.dimension());
        if (set == null || set.isEmpty()) {
            return false;
        }
        for (BlockPos tent : set) {
            if (tent.distSqr(pos) <= RADIUS_SQ) {
                return true;
            }
        }
        return false;
    }
}
