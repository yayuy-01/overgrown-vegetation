package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/**
 * 乗っ取りの器官の形作り({@link TakeoverShapes#plan(TakeoverShapes.World, ForestOrgans.Organ)})を別スレッドで回すための、
 * 面のまわりの地形の写し(2026-11-20 全体管理の決定「案 2」。巨大樹の {@code TreeTerrainSnapshot} と同じ手)。
 *
 * <p>{@link #capture} をサーバースレッドで呼ぶ。面から水平 {@code reach}・下 {@code down}・上 {@code up} ブロックのチャンクのうち
 * 読み込み終わっている物({@code getChunkNow})の区画を {@code PalettedContainer#copy} で写し、列ごとの高さ(MOTION_BLOCKING)と
 * 構造物の守る範囲({@link StructureFusion.Protect})も写す。読み方は生きた世界と同じ(読み込まれていないチャンクは null)。
 * 写した範囲の外を読んだら {@link #misses} を数える(呼ぶ側は計画を捨てて、広げて写し直す)。別スレッドは世界を直に読まない。
 */
final class TakeoverSnapshot implements TakeoverShapes.World {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final int minY, maxY, minSection;
    private final int cx0, cz0, cx1, cz1, s0, s1;
    /** [チャンク][区画 - s0]。チャンクが読み込まれていなければ null の行。区画が空気だけなら null。 */
    private final PalettedContainer<BlockState>[][] sections;
    private final boolean[] loaded;
    private final int[][] heights;
    private final StructureFusion.Protect protect;
    /** 写した範囲の外を読んだ回数(計画のスレッドだけが書く)。 */
    int misses;
    final int reach, down, up;
    long captureNanos, protectNanos;
    int sectionCount;

    @SuppressWarnings("unchecked")
    private TakeoverSnapshot(ServerLevel level, int x, int y, int z, int reach, int down, int up) {
        this.reach = reach;
        this.down = down;
        this.up = up;
        minY = level.getMinBuildHeight();
        maxY = level.getMaxBuildHeight();
        minSection = level.getMinSection();
        cx0 = (x - reach) >> 4;
        cz0 = (z - reach) >> 4;
        cx1 = (x + reach) >> 4;
        cz1 = (z + reach) >> 4;
        s0 = Math.max(minSection, (y - down) >> 4);
        s1 = Math.min(level.getMaxSection() - 1, (y + up) >> 4);
        int n = (cx1 - cx0 + 1) * (cz1 - cz0 + 1);
        sections = new PalettedContainer[n][];
        loaded = new boolean[n];
        heights = new int[n][];
        long tp = System.nanoTime();
        protect = StructureFusion.capture(level, cx0, cz0, cx1, cz1);
        protectNanos = System.nanoTime() - tp;
    }

    /** サーバースレッドで呼ぶ。 */
    @SuppressWarnings("unchecked")
    static TakeoverSnapshot capture(ServerLevel level, int x, int y, int z, int reach, int down, int up) {
        long t0 = System.nanoTime();
        TakeoverSnapshot s = new TakeoverSnapshot(level, x, y, z, reach, down, up);
        for (int cx = s.cx0; cx <= s.cx1; cx++) {
            for (int cz = s.cz0; cz <= s.cz1; cz++) {
                LevelChunk ch = level.getChunkSource().getChunkNow(cx, cz);
                if (ch == null) continue;
                int i = s.index(cx, cz);
                s.loaded[i] = true;
                LevelChunkSection[] secs = ch.getSections();
                PalettedContainer<BlockState>[] copy = new PalettedContainer[s.s1 - s.s0 + 1];
                for (int si = s.s0; si <= s.s1; si++) {
                    LevelChunkSection sec = secs[si - s.minSection];
                    if (sec == null || sec.hasOnlyAir()) continue; // 生きた世界も空気を返す
                    copy[si - s.s0] = sec.getStates().copy();
                    s.sectionCount++;
                }
                s.sections[i] = copy;
                int[] h = new int[256];
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) h[lx * 16 + lz] = ch.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz) + 1; // level.getHeight と同じ(読み込み済みのチャンク)
                }
                s.heights[i] = h;
            }
        }
        s.captureNanos = System.nanoTime() - t0;
        return s;
    }

    private int index(int cx, int cz) {
        return (cx - cx0) * (cz1 - cz0 + 1) + (cz - cz0);
    }

    private boolean inside(int cx, int cz) {
        return cx >= cx0 && cx <= cx1 && cz >= cz0 && cz <= cz1;
    }

    /** 写しの外を読んだ回数(守る範囲の写しの分も)。 */
    int totalMisses() {
        return misses + protect.misses;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Nullable
    @Override
    public BlockState state(int x, int y, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (!inside(cx, cz)) {
            misses++;
            return null;
        }
        int i = index(cx, cz);
        if (!loaded[i]) return null;
        int si = y >> 4;
        if (si < s0 || si > s1) {
            misses++;
            return AIR;
        }
        PalettedContainer<BlockState> sec = sections[i][si - s0];
        return sec == null ? AIR : sec.get(x & 15, y & 15, z & 15);
    }

    @Override
    public boolean artificial(int x, int y, int z, BlockState s) {
        if (!Takeover.maybeArtificial(s)) return false;
        if (s.is(Takeover.BUILDING)) return true;
        return protect.state(x, y, z) == StructureFusion.PROTECTED;
    }

    @Override
    public int height(int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (!inside(cx, cz)) {
            misses++;
            return Integer.MIN_VALUE;
        }
        int i = index(cx, cz);
        if (!loaded[i]) return Integer.MIN_VALUE;
        return heights[i][(x & 15) * 16 + (z & 15)];
    }
}
