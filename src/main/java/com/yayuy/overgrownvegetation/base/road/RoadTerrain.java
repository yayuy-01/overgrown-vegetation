package com.yayuy.overgrownvegetation.base.road;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 道筋を探すための地形の高さ(順 2 の着手順 7)。地面 = 固いブロックの天辺(水の下なら水底)、水面 = 空気でない天辺(水を含む)。
 * 水面が地面より上なら水。
 */
public interface RoadTerrain {

    int ground(int x, int z);

    int surface(int x, int z);

    default boolean water(int x, int z) {
        return surface(x, z) > ground(x, z);
    }

    /**
     * 世界生成のノイズから読む(読み込まれていない・生成されていない土地も分かる。{@code BaseNoise} と同じ読み方)。
     * 洞窟の切れ目・木・村などの地物は入らないので、敷くときにその場の地形へ合わせる。いくつものスレッドから同時に読んでよい。
     */
    static RoadTerrain noise(ServerLevel level) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        LevelHeightAccessor heights = LevelHeightAccessor.create(level.getMinBuildHeight(), level.getHeight());
        ConcurrentHashMap<Long, int[]> cache = new ConcurrentHashMap<>();
        return new RoadTerrain() {
            private int[] at(int x, int z) {
                long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
                int[] v = cache.get(key);
                if (v != null) {
                    return v;
                }
                NoiseColumn col = gen.getBaseColumn(x, z, heights, rs);
                int min = heights.getMinBuildHeight();
                int surface = min - 1;
                int ground = min - 1;
                for (int y = heights.getMaxBuildHeight() - 1; y >= min; y--) {
                    BlockState s = col.getBlock(y);
                    if (surface < min && !s.isAir()) {
                        surface = y;
                    }
                    if (s.blocksMotion()) {
                        ground = y;
                        break;
                    }
                }
                v = new int[]{ground, surface};
                int[] prev = cache.putIfAbsent(key, v);
                return prev != null ? prev : v;
            }

            @Override
            public int ground(int x, int z) {
                return at(x, z)[0];
            }

            @Override
            public int surface(int x, int z) {
                return at(x, z)[1];
            }
        };
    }
}
