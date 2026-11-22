package com.yayuy.overgrownvegetation.corruption;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;

/**
 * <b>そのチャンクは溶岩の海を含むか</b>(2026-11-11 骨組み)。根の前線の速さ(海は速い)と、最初の根の行き先に使う。
 *
 * <p>読み込まれているチャンクは高さ {@link NetherLava#surfaceY} を 256 列読み、真上が空いた溶岩(か、もう殻・湖になった所)が
 * {@link #MIN_CELLS} マス以上あれば海。読み込まれていないチャンクは<b>地形の生成器の列</b>({@link ChunkGenerator#getBaseColumn})を
 * 5 列だけ読む(チャンクを生成しない。洞窟の彫りは入らないが、海面の高さの溶岩はノイズの地形だけで決まる)。
 */
public final class NetherTerrain {

    /** 海とみなす表面のマス数(読み込まれているチャンク)。 */
    static final int MIN_CELLS = 4;
    private static final int[][] SAMPLES = {{8, 8}, {2, 2}, {13, 2}, {2, 13}, {13, 13}};

    private NetherTerrain() {
    }

    public static boolean isSea(ServerLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        return chunk != null ? isSeaLoaded(level, chunk) : isSeaByGenerator(level, cx, cz);
    }

    static boolean isSeaLoaded(ServerLevel level, LevelChunk chunk) {
        int s = NetherLava.surfaceY(level);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        int cells = 0;
        for (int c = 0; c < 256; c++) {
            BlockState top = chunk.getBlockState(p.set(x0 + (c & 15), s, z0 + (c >> 4)));
            if (!(NetherLava.isLava(top) || NetherLava.isCrust(top) || NetherLava.isWater(top))) continue;
            BlockState up = chunk.getBlockState(p.setY(s + 1));
            if (up.isAir() || NetherSurface.isOpen(up)) {
                if (++cells >= MIN_CELLS) return true;
            }
        }
        return false;
    }

    static boolean isSeaByGenerator(ServerLevel level, int cx, int cz) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        int s = NetherLava.surfaceY(level);
        for (int[] o : SAMPLES) {
            NoiseColumn col = gen.getBaseColumn((cx << 4) + o[0], (cz << 4) + o[1], level, level.getChunkSource().randomState());
            if (col.getBlock(s).is(Blocks.LAVA) && col.getBlock(s + 1).isAir()) return true;
        }
        return false;
    }

    /**
     * {@code from} から半径 {@code radius} チャンクの中で、いちばん近い海のチャンク(四角い輪を内から順に)。無ければ null。
     */
    @Nullable
    public static ChunkPos nearestSea(ServerLevel level, ChunkPos from, int radius) {
        return nearestSea(from, radius, (x, z) -> isSea(level, x, z));
    }

    /** {@link #nearestSea} の、海かどうかを渡す版(台帳の覚えを使う)。 */
    @Nullable
    public static ChunkPos nearestSea(ChunkPos from, int radius, java.util.function.BiPredicate<Integer, Integer> sea) {
        for (int r = 0; r <= radius; r++) {
            ChunkPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    double d = dx * dx + dz * dz;
                    if (d >= bestD) continue;
                    if (sea.test(from.x + dx, from.z + dz)) {
                        best = new ChunkPos(from.x + dx, from.z + dz);
                        bestD = d;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }
}
