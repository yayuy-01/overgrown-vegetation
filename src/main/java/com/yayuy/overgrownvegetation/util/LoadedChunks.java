package com.yayuy.overgrownvegetation.util;

import net.minecraft.server.level.ServerLevel;

/**
 * 「本当に読み込み済みか」の判定(2026-10-06)。
 *
 * <p>{@code level.hasChunk} は<b>チケットの距離しか見ない</b>ので、読み込み・生成の途中でも true になる。
 * そのまま {@code getBlockState} などで中を読むと、サーバースレッドが読み込み・生成が終わるまで止まる。
 * 読み込み済み(FULL)かどうかは {@code getChunkSource().getChunkNow(x, z) != null} で見る。
 *
 * <p>チャンクの縁のマスを {@code UPDATE_ALL} で書き換えると近隣更新が隣のチャンクのブロックを読むので、
 * 自分だけでなく<b>周り 8 チャンク</b>も読み込み済みである必要がある({@link #neighbourhoodLoaded})。
 * 読み込み範囲のいちばん外側のチャンクは、その外が読み込まれていないので、ここで待つことになる。
 * 2026-10-06 の計測では、台帳の書き換え({@code LedgerSpreadCatchUp})がこれを守っておらず、
 * 飛んで移動するとサーバースレッドの約半分がこの待ちだった(1 tick 最大 1.2 秒)。
 */
public final class LoadedChunks {

    /** チャンク (cx, cz) が読み込み済み(FULL)か。 */
    public static boolean loaded(ServerLevel level, int cx, int cz) {
        return level.getChunkSource().getChunkNow(cx, cz) != null;
    }

    /** チャンク (cx, cz) と周り 8 チャンクがすべて読み込み済み(FULL)か。 */
    public static boolean neighbourhoodLoaded(ServerLevel level, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private LoadedChunks() {
    }
}
