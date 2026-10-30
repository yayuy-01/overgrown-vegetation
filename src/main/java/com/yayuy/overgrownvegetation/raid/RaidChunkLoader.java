package com.yayuy.overgrownvegetation.raid;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;

/**
 * レイド中、苗床の周囲を {@code /forceload} と同じ仕組み({@link ServerLevel#setChunkForced})で
 * 強制ロードする。プレイヤーが一時的に離れても苗床が止まらない(根と汚染が進む)ようにするため。
 *
 * <p>2026-09-25(ユーザー承認「レイドの見直し」、報告 1097 / 1450): 固定する範囲は苗床のまわりの小さな正方形
 * ({@code raidForceLoadRadius}、既定 2)と、地割れの足跡のチャンクだけにした。この固定(FORCED チケット)は
 * そのチャンクの中のエンティティまで動かし、周囲 2 チャンクも読み込む(ブロックの書き換えはできるが Mob は止まる)。
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

    /**
     * {@link #force} と同じ範囲・同じ記録の仕方で強制ロードの印を付けるが、<b>その場でチャンクを読み込まない</b>
     * (2026-09-24 軽量化、報告 1064 / 1150)。
     *
     * <p>バニラの {@link ServerLevel#setChunkForced} は印を付けたあと {@code getChunk} でチャンクを FULL まで
     * 同期で読み込む(未生成なら生成が終わるまでサーバーが止まる)。地割れの開始では 17×17 = 289 チャンクを
     * 一度に、攻城戦ボスの錨では中心がチャンクをまたぐたびに外周の数チャンクをそうしていた。ここでは
     * 保存先({@link ForcedChunksSavedData}、{@code /forceload} と同じ)への記録とチケットだけを行い、
     * 読み込みはチケットに任せて裏で進める。解除は {@link #release} のままでよい。
     *
     * <p>呼んだ直後はまだ読み込まれていないことがあるので、呼び出し側は読む前に {@code hasChunk} 等で確かめるか、
     * 読み込みが間に合わなかったときは従来どおりその場で読み込まれてもよい処理にだけ使うこと。
     * 苗床レイドの開始({@link RaidController})は直後に同じ場所へエンティティを置くので、中心のチャンクだけ
     * {@link #force} で読み込む({@link #forceLoadingCenter}、2026-09-25)。攻城戦ボスの錨(SiegeData.updateAnchor)も、実エンティティを置く中心のチャンクだけは呼び出し側で
     * {@code getChunk} して同期で読み込む(近距離モードへの切り替えを遅らせないため)。
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
     * {@link #forceWithoutLoading} の、正方形ではなく<b>チャンクを 1 枚ずつ指定する</b>版(2026-09-25、報告 1450)。
     * 地割れは裂け目の足跡が掛かるチャンクだけを固定する({@code EarthquakeFissure#footprintChunks})。
     * 記録の仕方(新たに固定した分だけを返す)と、その場で読み込まない点は {@link #forceWithoutLoading} と同じ。
     */
    public static long[] forceKeysWithoutLoading(ServerLevel level, long[] chunkKeys) {
        ForcedChunksSavedData data = level.getDataStorage()
                .computeIfAbsent(ForcedChunksSavedData.factory(), ForcedChunksSavedData.FILE_ID);
        LongArrayList forced = new LongArrayList();
        for (long key : chunkKeys) {
            // setChunkForced(add = true) から getChunk だけを抜いたもの。既に固定済みなら何もしない
            // ({@link #force} の already.contains と同じく、他人が固定したチャンクは記録しない)。
            if (data.getChunks().add(key)) {
                data.setDirty();
                level.getChunkSource().updateChunkForced(new ChunkPos(key), true);
                forced.add(key);
            }
        }
        return forced.toLongArray();
    }

    /**
     * 苗床レイドの開始用(2026-09-25、報告 1097)。苗床を置く<b>中心のチャンクだけ</b>を {@link #force} で同期に読み込み
     * (直後にそこへ苗床を置くため)、残りの半径ぶんは {@link #forceWithoutLoading} で印だけ付けて裏で読み込ませる。
     * 以前は半径ぶん(既定 15×15)を全部その tick に同期で読み込んでいた。返す記録は両方を合わせたもの。
     */
    public static long[] forceLoadingCenter(ServerLevel level, BlockPos origin, int radiusChunks) {
        long[] center = force(level, origin, 0);
        // 中心は上で固定済みなので forceWithoutLoading は飛ばし、周りの新しく固定した分だけが返る。
        long[] around = radiusChunks > 0 ? forceWithoutLoading(level, origin, radiusChunks) : new long[0];
        LongArrayList all = new LongArrayList(center.length + around.length);
        all.addElements(0, center);
        all.addElements(all.size(), around);
        return all.toLongArray();
    }

    /**
     * 記録 {@code forcedChunks} のうち、{@code center} のチャンクから半径 {@code radiusChunks}(チェビシェフ距離)の
     * 外にあるものの強制ロードを解除し、残した分を返す(2026-09-25、報告 1097)。何も外さなければ<b>同じ配列</b>をそのまま返す。
     * 既定の半径を下げる前に始まったレイド(既定 7 = 225 チャンクで固定したもの)や、途中で設定を下げたときに、
     * レイドの終わりを待たずに新しい範囲まで縮めるため。広げる方向には何もしない。
     */
    public static long[] releaseOutside(ServerLevel level, long[] forcedChunks, ChunkPos center, int radiusChunks) {
        if (forcedChunks == null || forcedChunks.length == 0) {
            return forcedChunks;
        }
        LongArrayList kept = null;
        for (int i = 0; i < forcedChunks.length; i++) {
            long key = forcedChunks[i];
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            boolean outside = Math.abs(cx - center.x) > radiusChunks || Math.abs(cz - center.z) > radiusChunks;
            if (outside) {
                if (kept == null) {
                    kept = new LongArrayList(forcedChunks.length);
                    kept.addElements(0, forcedChunks, 0, i);
                }
                level.setChunkForced(cx, cz, false);
            } else if (kept != null) {
                kept.add(key);
            }
        }
        return kept == null ? forcedChunks : kept.toLongArray();
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
