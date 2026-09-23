package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * チャンクごとの「胞子濃度への加算 %」({@code ModAttachments.SPORE_CHUNK_BONUS})。
 *
 * <p>プレイヤーが胞子感染にかかるたび、そのプレイヤーのいるチャンクへ段階ごとの
 * {@code chunkSporeBonusPercent}(胞子感染1 = +1%、胞子感染2 = +5% …)を積み上げる。
 * チャンクのデータ添付として保存されるので、ワールドを読み直しても残る。
 * {@link SporeConcentration#compute} が距離成分・割合成分と同じ「基礎値」として加算する。
 */
public final class SporeChunkBonus {

    /** 加算 % の上限(100 を超えても意味が無い)。 */
    private static final double MAX_BONUS = 100.0D;

    /** {@code chunk} の加算 %。未ロードなら 0。 */
    public static double get(ServerLevel level, ChunkPos chunk) {
        LevelChunk loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
        if (loaded == null || !loaded.hasData(ModAttachments.SPORE_CHUNK_BONUS)) {
            return 0.0D;
        }
        return loaded.getData(ModAttachments.SPORE_CHUNK_BONUS);
    }

    /**
     * {@code chunk} の加算 % に {@code percent} を足す(上限 {@value #MAX_BONUS})。
     * 胞子濃度のキャッシュも捨てて、次の参照から反映されるようにする。
     *
     * @return 実際に加算できたか(チャンク未ロード・0 以下の加算なら false)
     */
    public static boolean add(ServerLevel level, ChunkPos chunk, double percent) {
        if (percent <= 0.0D) {
            return false;
        }
        LevelChunk loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
        if (loaded == null) {
            return false;
        }
        double current = loaded.hasData(ModAttachments.SPORE_CHUNK_BONUS)
                ? loaded.getData(ModAttachments.SPORE_CHUNK_BONUS) : 0.0D;
        double next = Math.min(MAX_BONUS, current + percent);
        if (next <= current) {
            return false;
        }
        loaded.setData(ModAttachments.SPORE_CHUNK_BONUS, next);
        SporeConcentration.invalidate(level, chunk);
        return true;
    }

    private SporeChunkBonus() {
    }
}
