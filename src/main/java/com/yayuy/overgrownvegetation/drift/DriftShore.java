package com.yayuy.overgrownvegetation.drift;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.function.LongPredicate;

/**
 * 海と岸の見分け(漂流する擬態 Mob の岸探し・島の測り・流す岸の選び。2026-11-20)。
 *
 * <p>決まり:
 * <ul>
 *   <li>読み込まれたチャンク({@code getChunkNow})だけを読む。読めば読み込みが走るので {@code getBlockState} を直に呼ばない。</li>
 *   <li>高さの地図は使わない(テスト場では天井のバリアを答える)。水面の段 {@code wy}(いちばん上の水のマス)を基準に、その列の数マスだけを見る。</li>
 *   <li>「陸の列」 = 水面の段に水の無い固い物がある、または水面の段が空いていて真下が水の無い固い物(水面と同じ高さの砂浜)。</li>
 * </ul>
 */
public final class DriftShore {

    private DriftShore() {
    }

    /** 列の答え。 */
    public enum Col { WATER, LAND, UNKNOWN }

    /** {@code pos} の列の水面の段(いちばん上の水のマス)。{@code pos} とその 1 つ下が水でなければ {@link Integer#MIN_VALUE}。上へ 8 マスまで。 */
    public static int waterSurfaceY(ServerLevel level, BlockPos pos) {
        if (chunk(level, pos.getX(), pos.getZ()) == null) return Integer.MIN_VALUE;
        BlockPos.MutableBlockPos m = pos.mutable();
        if (!water(level, m)) {
            m.move(0, -1, 0);
            if (!water(level, m)) return Integer.MIN_VALUE;
        }
        for (int i = 0; i < 8 && water(level, m.move(0, 1, 0)); i++) {
        }
        return m.getY() - 1;
    }

    static boolean water(ServerLevel level, BlockPos p) {
        return level.getFluidState(p).is(FluidTags.WATER);
    }

    @Nullable
    static LevelChunk chunk(ServerLevel level, int x, int z) {
        return level.getChunkSource().getChunkNow(x >> 4, z >> 4);
    }

    /** 列 (x, z) は水面の段 {@code wy} で見て陸か。 */
    public static Col column(ServerLevel level, int x, int z, int wy) {
        LevelChunk c = chunk(level, x, z);
        if (c == null) return Col.UNKNOWN;
        BlockPos p = new BlockPos(x, wy, z);
        BlockState s = c.getBlockState(p);
        if (!s.getFluidState().isEmpty()) return Col.WATER;
        if (solid(c, p, s)) return Col.LAND;
        BlockPos b = p.below();
        BlockState sb = c.getBlockState(b);
        if (sb.getFluidState().isEmpty() && solid(c, b, sb)) return Col.LAND;
        return Col.WATER; // 水面より低い穴・空気(崖の下の海など)は渡れる側に数える
    }

    private static boolean solid(LevelChunk c, BlockPos p, BlockState s) {
        // バリア(テスト場の壁・天井)は岸に数えない
        return !s.isAir() && !s.is(BlockTags.LEAVES) && !s.is(net.minecraft.world.level.block.Blocks.BARRIER) && s.isCollisionShapeFullBlock(c, p);
    }

    /**
     * 列 (x, z) の水の深さ(水面の段 {@code wy} から下へ続く水のマスの数。{@code cap} で打ち切る)。
     * 読み込まれていない列は {@code cap}(深い扱い。動かない所へは体も進まない)。
     */
    public static int depth(ServerLevel level, int x, int z, int wy, int cap) {
        LevelChunk c = chunk(level, x, z);
        if (c == null) return cap;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, wy, z);
        int n = 0;
        while (n < cap && c.getBlockState(m).getFluidState().is(FluidTags.WATER)) {
            n++;
            m.move(0, -1, 0);
        }
        return n;
    }

    /** 列 (x, z) の立てる所(水面の段から上へ {@code maxUp} まで、上から見ていちばん上の固い物の 1 つ上)。無ければ null。 */
    @Nullable
    public static BlockPos standOn(ServerLevel level, int x, int z, int wy, int maxUp) {
        LevelChunk c = chunk(level, x, z);
        if (c == null) return null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, wy + maxUp, z);
        for (; m.getY() >= wy - 1; m.move(0, -1, 0)) {
            BlockState s = c.getBlockState(m);
            if (s.getFluidState().isEmpty() && solid(c, m, s)) {
                BlockState up = c.getBlockState(m.above());
                if (up.getFluidState().isEmpty()) return m.above().immutable();
                return null;
            }
        }
        return null;
    }

    /**
     * いちばん近い岸の列を、{@code (x, z)} を中心に輪で探す(半径 {@code ringStep} から {@code radius} まで、輪ごとに約 2 ブロックおき)。
     * {@code skip} が true を返すチャンクの列は飛ばす(出てきた岸・押し返しで断られた岸)。見つからなければ null。
     */
    @Nullable
    public static BlockPos findShore(ServerLevel level, double x, double z, int wy, int radius, int ringStep, LongPredicate skip) {
        int step = Math.max(1, ringStep);
        for (int r = step; r <= radius; r += step) {
            int n = Math.max(8, (int) Math.ceil(2 * Math.PI * r / 2.0));
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * i / n;
                int px = (int) Math.floor(x + Math.cos(a) * r), pz = (int) Math.floor(z + Math.sin(a) * r);
                if (skip.test(ChunkPos.asLong(px >> 4, pz >> 4))) continue;
                if (column(level, px, pz, wy) != Col.LAND) continue;
                double d = (px + 0.5 - x) * (px + 0.5 - x) + (pz + 0.5 - z) * (pz + 0.5 - z);
                if (d < bestD) {
                    bestD = d;
                    best = new BlockPos(px, wy, pz);
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** 測った島。{@code columns} は陸の列の数、{@code (cx, cz)} は重心、{@code radius} は同じ面積の円の半径。 */
    public record Island(int columns, int cx, int cz, double radius) {
    }

    /**
     * 陸の列 {@code (x, z)} から陸をたどって島の大きさを測る。陸が {@code maxColumns} を超える・{@code scanRadius} の外へ続く・
     * 読み込まれていない列に当たったら本土(null)。
     */
    @Nullable
    public static Island island(ServerLevel level, int x, int z, int wy, int maxColumns, int scanRadius) {
        if (column(level, x, z, wy) != Col.LAND) return null;
        LongOpenHashSet seen = new LongOpenHashSet();
        ArrayDeque<long[]> q = new ArrayDeque<>();
        seen.add(BlockPos.asLong(x, 0, z));
        q.add(new long[]{x, z});
        long sx = 0, sz = 0;
        int n = 0;
        int r2 = scanRadius * scanRadius;
        while (!q.isEmpty()) {
            long[] c = q.poll();
            int cx = (int) c[0], cz = (int) c[1];
            n++;
            sx += cx;
            sz += cz;
            if (n > maxColumns) return null;
            for (int d = 0; d < 4; d++) {
                int nx = cx + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = cz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                long k = BlockPos.asLong(nx, 0, nz);
                if (seen.contains(k)) continue;
                Col col = column(level, nx, nz, wy);
                if (col == Col.WATER) continue;
                if (col == Col.UNKNOWN) return null;
                if ((nx - x) * (nx - x) + (nz - z) * (nz - z) > r2) return null;
                seen.add(k);
                q.add(new long[]{nx, nz});
            }
        }
        return new Island(n, (int) Math.round((double) sx / n), (int) Math.round((double) sz / n), Math.sqrt(n / Math.PI));
    }
}
