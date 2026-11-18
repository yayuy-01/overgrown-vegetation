package com.yayuy.overgrownvegetation.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 巨大樹の計画を別スレッドで作るための、周りの地形の写し(2026-11-18 大群の軽量化 B の案 A。ユーザー決定「巨大樹 A 案を入れよう」)。
 *
 * <p>{@link #capture} をサーバースレッドで呼ぶと、苗木のまわり {@link #RADIUS_CHUNKS} チャンクのうち<b>読み込み終わっている</b>チャンク
 * ({@code getChunkNow})の区画を写す(区画ごとの {@code PalettedContainer#copy}。空気だけの区画は写さない)。
 * 読み方は生きた世界と同じにしてある: 高さの範囲の外は {@code VOID_AIR}・空気だけの区画は {@code AIR}・流体はブロックから。
 * 「読み込まれているか」は写したチャンクかどうか。
 *
 * <p>写していないチャンクを読み込み済みかを確かめずに読んだ回数は {@link #misses} に数える(生きた世界ならその場でチャンクを読み込んでいた所。
 * 写しでは空気として読む)。テストでは 0 を確かめる。
 */
public final class TreeTerrainSnapshot implements TreeTerrain {

    /**
     * 写す半径(チャンク)。巨大樹の手の届く範囲: 高さは設定の 2 倍(240)まで伸び、そのとき幹の太さ・傘・根の長さも約 1.6 倍
     * ({@code heightScaleFactor})。傘の半径 50 × 1.6 = 80・地表の根 100 × 1.6 に蛇行と渡り(最大 28)・深い根 150 を足して約 220 ブロック。
     * 余裕を見て 16 チャンク(256 ブロック)。読み込まれていないチャンクは写さないので、遊んでいる間はたいてい描画距離で頭打ち。
     */
    static final int RADIUS_CHUNKS = 16;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState VOID_AIR = Blocks.VOID_AIR.defaultBlockState();

    private final Long2ObjectOpenHashMap<PalettedContainer<BlockState>[]> chunks = new Long2ObjectOpenHashMap<>();
    private final int minY;
    private final int height;
    private final int minSection;
    /** 写していないチャンクを確かめずに読んだ回数。 */
    final AtomicInteger misses = new AtomicInteger();
    /** 写したチャンク・区画の数と、写すのにかかった時間(ログ・測り用)。 */
    int chunkCount;
    /** 読んだ所のうち、真ん中のチャンクからいちばん遠いチャンクの距離(チェビシェフ。測り用。計画のスレッドだけが書く)。 */
    int maxReachChunks;
    private int centerCx, centerCz;
    int sectionCount;
    long captureNanos;

    private TreeTerrainSnapshot(ServerLevel level) {
        this.minY = level.getMinBuildHeight();
        this.height = level.getHeight();
        this.minSection = level.getMinSection();
    }

    /** サーバースレッドで呼ぶ。{@code center} のまわりの読み込み終わったチャンクを写す。 */
    @SuppressWarnings("unchecked")
    public static TreeTerrainSnapshot capture(ServerLevel level, BlockPos center, int radiusChunks) {
        long t0 = System.nanoTime();
        TreeTerrainSnapshot s = new TreeTerrainSnapshot(level);
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        s.centerCx = cx;
        s.centerCz = cz;
        for (int x = cx - radiusChunks; x <= cx + radiusChunks; x++) {
            for (int z = cz - radiusChunks; z <= cz + radiusChunks; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                PalettedContainer<BlockState>[] copy = new PalettedContainer[sections.length];
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection sec = sections[i];
                    if (sec == null || sec.hasOnlyAir()) continue; // 生きた世界も AIR を返す
                    copy[i] = sec.getStates().copy();
                    s.sectionCount++;
                }
                s.chunks.put(ChunkPos.asLong(x, z), copy);
            }
        }
        s.chunkCount = s.chunks.size();
        s.captureNanos = System.nanoTime() - t0;
        return s;
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        reach(pos);
        return !isOutsideBuildHeight(pos) && chunks.containsKey(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null; // 計画は使わない
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int y = pos.getY();
        if (isOutsideBuildHeight(y)) return VOID_AIR;
        reach(pos);
        PalettedContainer<BlockState>[] sections = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (sections == null) {
            misses.incrementAndGet();
            return AIR;
        }
        int i = (y >> 4) - minSection;
        if (i < 0 || i >= sections.length || sections[i] == null) return AIR;
        return sections[i].get(pos.getX() & 15, y & 15, pos.getZ() & 15);
    }

    private void reach(BlockPos pos) {
        int d = Math.max(Math.abs((pos.getX() >> 4) - centerCx), Math.abs((pos.getZ() >> 4) - centerCz));
        if (d > maxReachChunks) maxReachChunks = d;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        if (isOutsideBuildHeight(pos.getY())) return Fluids.EMPTY.defaultFluidState();
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minY;
    }
}
