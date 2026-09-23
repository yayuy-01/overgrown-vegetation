package com.yayuy.overgrownvegetation.raid;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * レイド中、苗床の周囲を {@code /forceload} と同じ仕組み({@link ServerLevel#setChunkForced})で
 * 強制ロードする。プレイヤーが一時的に離れても汚染・成長・ウェーブが止まらないようにするため。
 *
 * <p>{@link #force} は「このレイドが新たに強制ロードした(それまで強制ロードされていなかった)」
 * チャンクだけを記録して返す。{@link #release} はその記録ぶんだけを解除するので、プレイヤーが
 * 自分で {@code /forceload add} していたチャンクを巻き込んで解除することはない。記録は
 * {@link RaidData.ActiveRaid} に永続化され、サーバー再起動をまたいでも解除できる。
 */
public final class RaidChunkLoader {

    /** {@code origin} を含むチャンクを中心に半径 {@code radiusChunks} のチャンクを強制ロードし、新たに固定した分を返す。 */
    public static long[] force(ServerLevel level, BlockPos origin, int radiusChunks) {
        ChunkPos center = new ChunkPos(origin);
        LongSet already = level.getForcedChunks();
        LongArrayList forced = new LongArrayList();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = center.x + dx;
                int cz = center.z + dz;
                long key = ChunkPos.asLong(cx, cz);
                if (already.contains(key)) {
                    continue;
                }
                if (level.setChunkForced(cx, cz, true)) {
                    forced.add(key);
                }
            }
        }
        return forced.toLongArray();
    }

    /** {@link #force} が返したキー配列ぶんの強制ロードを解除する。 */
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
