package com.yayuy.overgrownvegetation.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;

/**
 * 同じ tick の中で近傍のブロックを大量に読むときの、{@link Level#getBlockState} /
 * {@link Level#isLoaded} / {@link Level#getFluidState} の軽量版。
 *
 * <p>バニラの {@code Level#getBlockState} は 1 回ごとに「高さ範囲チェック → チャンクソースからチャンクを
 * 引く → セクション → パレット」を通る。近傍走査(半径 5 の球 = 515 マス、25×25×13 の直方体 = 8000 マス
 * など)ではチャンクを引く部分が毎回同じ 1〜4 チャンクを引き直しているだけなので、直近に触った
 * チャンクを 2×2 = 4 スロットに覚えておき、同じチャンクへの読みはチャンクオブジェクトから直接読む。
 *
 * <h2>挙動の同一性</h2>
 * <ul>
 *     <li>{@link #getBlockState(int, int, int)} / {@link #getFluidState(int, int, int)} は
 *     {@code Level#getBlockState} / {@code Level#getFluidState} と同じ結果を返す
 *     (高さ範囲外は {@code VOID_AIR} / {@code EMPTY}、未ロードのチャンクはバニラと同じく
 *     {@link Level#getChunk(int, int)} で同期ロードする)。</li>
 *     <li>{@link #isLoaded(int, int, int)} / {@link #stateIfLoaded(int, int, int)} は
 *     {@code Level#isLoaded} と同じ判定(高さ範囲外は false、チャンク未ロードは false)。</li>
 * </ul>
 *
 * <h2>使い方の制約</h2>
 * <p>1 回の走査(同じ tick 内)の間だけ使うこと。tick をまたいで使い回す場合は走査の前に
 * {@link #reset()} を呼ぶ(前の tick で覚えたチャンクは、その間にアンロードされて別のオブジェクトに
 * 置き換わっている可能性がある)。同じ tick の中ならブロックを書き換えてもチャンクオブジェクト自体は
 * 変わらないので、書き換えを挟みながら読み続けてよい。
 */
public final class ChunkCachedReader {

    private static final int SLOTS = 4;

    private final Level level;
    private final int minY;
    private final int maxY;
    private final LevelChunk[] chunks = new LevelChunk[SLOTS];
    private final long[] keys = new long[SLOTS];
    private final boolean[] valid = new boolean[SLOTS];
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    public ChunkCachedReader(Level level) {
        this.level = level;
        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight();
    }

    public Level level() {
        return level;
    }

    /** 覚えているチャンクを全部忘れる。tick をまたいで使い回す前に呼ぶ。 */
    public void reset() {
        for (int i = 0; i < SLOTS; i++) {
            valid[i] = false;
            chunks[i] = null;
        }
    }

    /** {@code Level#isOutsideBuildHeight(int)} と同じ。 */
    public boolean isOutsideBuildHeight(int y) {
        return y < minY || y >= maxY;
    }

    /**
     * ブロック座標 {@code (x, z)} を含むチャンク。ロード済みでなければ {@code null}
     * ({@code ChunkSource#getChunkNow} と同じ)。
     */
    @Nullable
    public LevelChunk chunkNow(int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        int slot = (cx & 1) | ((cz & 1) << 1);
        long key = ChunkPos.asLong(cx, cz);
        if (valid[slot] && keys[slot] == key) {
            return chunks[slot];
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk != null) {
            chunks[slot] = chunk;
            keys[slot] = key;
            valid[slot] = true;
        }
        return chunk;
    }

    /** {@code Level#getChunk(int, int)} と同じ(未ロードなら同期ロードする)。 */
    private LevelChunk chunkOrLoad(int x, int z) {
        LevelChunk chunk = chunkNow(x, z);
        if (chunk != null) {
            return chunk;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        chunk = level.getChunk(cx, cz);
        int slot = (cx & 1) | ((cz & 1) << 1);
        chunks[slot] = chunk;
        keys[slot] = ChunkPos.asLong(cx, cz);
        valid[slot] = true;
        return chunk;
    }

    /** {@code Level#isLoaded(BlockPos)} と同じ判定。 */
    public boolean isLoaded(int x, int y, int z) {
        return !isOutsideBuildHeight(y) && chunkNow(x, z) != null;
    }

    /**
     * {@code Level#isLoaded(pos) ? Level#getBlockState(pos) : null} を 1 回のチャンク参照で。
     * 未ロード・高さ範囲外なら {@code null}。
     */
    @Nullable
    public BlockState stateIfLoaded(int x, int y, int z) {
        if (isOutsideBuildHeight(y)) {
            return null;
        }
        LevelChunk chunk = chunkNow(x, z);
        if (chunk == null) {
            return null;
        }
        scratch.set(x, y, z);
        return chunk.getBlockState(scratch);
    }

    /** {@code Level#getBlockState(BlockPos)} と同じ結果。 */
    public BlockState getBlockState(int x, int y, int z) {
        if (isOutsideBuildHeight(y)) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        scratch.set(x, y, z);
        return chunkOrLoad(x, z).getBlockState(scratch);
    }

    /** {@code Level#getFluidState(BlockPos)} と同じ結果。 */
    public FluidState getFluidState(int x, int y, int z) {
        if (isOutsideBuildHeight(y)) {
            return Fluids.EMPTY.defaultFluidState();
        }
        return chunkOrLoad(x, z).getFluidState(x, y, z);
    }
}
