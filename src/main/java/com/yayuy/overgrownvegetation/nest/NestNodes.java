package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 胞子ノード({@code block/SporeNodeBlock})の台帳(2026-09-20)。
 *
 * <p>数えているのは<b>汚染の広がる速さの倍率</b>だけ。ノード 1 個につき
 * {@code nodeCorruptionPerNode}(既定 0.05)ずつ速くなり、{@code nodeCorruptionMaxNodes}
 * (既定 10)個で打ち止め —— 11 個以上置いても 1.5 倍を超えない(ユーザー指定 2026-09-19)。
 *
 * <p>噴出孔を育てるのはノード自身({@code block/SporeNodeBlockEntity})の仕事で、ここには無い。
 */
public final class NestNodes {

    private static final Map<ResourceKey<Level>, Set<BlockPos>> NODES = new ConcurrentHashMap<>();

    public static void register(Level level, BlockPos pos) {
        NODES.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
    }

    public static void unregister(Level level, BlockPos pos) {
        Set<BlockPos> set = NODES.get(level.dimension());
        if (set != null) {
            set.remove(pos);
        }
    }

    public static Set<BlockPos> nodes(Level level) {
        Set<BlockPos> set = NODES.get(level.dimension());
        return set != null ? Collections.unmodifiableSet(set) : Set.of();
    }

    public static void clearAll() {
        NODES.clear();
    }

    /** 汚染の広がる速さに掛ける倍率(1.0 = ノード無し)。 */
    public static double corruptionMultiplier(Level level) {
        NestTable t = DesignData.nest();
        int counted = Math.min(nodes(level).size(), t.nodeCorruptionMaxNodes());
        return 1.0D + counted * t.nodeCorruptionPerNode();
    }

    private NestNodes() {
    }
}
