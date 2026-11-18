package com.yayuy.overgrownvegetation.raid;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;

/**
 * 決まったチャンクを {@code /forceload} と同じ仕組みで固定する(今の使い手は地震 {@code quake/EarthquakeData}・{@code EarthquakeEvent})。
 *
 * <p>固定の関数は「新たに固定した(それまで固定されていなかった)」チャンクだけを記録して返す。{@link #release} はその記録ぶんだけを
 * 解除するので、プレイヤーが自分で {@code /forceload add} していたチャンクを巻き込んで解除することはない。記録は使い手が保存する。
 *
 * <p>11-18 コードの見直し 第 2 回 ⑤7: 苗床レイド・攻城戦(退避済み)用の 3 つ(その場で読み込む固定・中心だけ読み込む固定・範囲の外の解除)は
 * 使い手がいないので外し、退避済みのクラスを指す説明も直した。
 */
public final class RaidChunkLoader {

    /**
     * {@code origin} を含むチャンクを中心に半径 {@code radiusChunks} のチャンクに強制ロードの印を付ける。<b>その場でチャンクを読み込まない</b>
     * (2026-09-24 軽量化)。
     *
     * <p>バニラの {@link ServerLevel#setChunkForced} は印を付けたあと {@code getChunk} でチャンクを FULL まで
     * 同期で読み込む(未生成なら生成が終わるまでサーバーが止まる)。ここでは保存先({@link ForcedChunksSavedData}、{@code /forceload} と同じ)への
     * 記録とチケットだけを行い、読み込みはチケットに任せて裏で進める。呼んだ直後はまだ読み込まれていないことがあるので、
     * 呼び出し側は読む前に読み込み終わったかを確かめること。
     */
    public static long[] forceWithoutLoading(ServerLevel level, BlockPos origin, int radiusChunks) {
        ChunkPos center = new ChunkPos(origin);
        LongArrayList keys = new LongArrayList();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                keys.add(ChunkPos.asLong(center.x + dx, center.z + dz));
            }
        }
        return forceKeysWithoutLoading(level, keys.toLongArray());
    }

    /**
     * {@link #forceWithoutLoading} の、正方形ではなく<b>チャンクを 1 枚ずつ指定する</b>版(2026-09-25)。
     * 地割れは裂け目の足跡が掛かるチャンクだけを固定する({@code EarthquakeFissure#footprintChunks})。
     */
    public static long[] forceKeysWithoutLoading(ServerLevel level, long[] chunkKeys) {
        ForcedChunksSavedData data = level.getDataStorage()
                .computeIfAbsent(ForcedChunksSavedData.factory(), ForcedChunksSavedData.FILE_ID);
        LongArrayList forced = new LongArrayList();
        for (long key : chunkKeys) {
            // setChunkForced(add = true) から getChunk だけを抜いたもの。既に固定済みなら何もしない(他人が固定したチャンクは記録しない)
            if (data.getChunks().add(key)) {
                data.setDirty();
                level.getChunkSource().updateChunkForced(new ChunkPos(key), true);
                forced.add(key);
            }
        }
        return forced.toLongArray();
    }

    /** 固定の関数が返したキー配列ぶんの強制ロードを解除する。 */
    public static void release(ServerLevel level, long[] forcedChunks) {
        if (forcedChunks == null) {
            return;
        }
        for (long key : forcedChunks) {
            level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
        }
    }

    private RaidChunkLoader() {
    }
}
