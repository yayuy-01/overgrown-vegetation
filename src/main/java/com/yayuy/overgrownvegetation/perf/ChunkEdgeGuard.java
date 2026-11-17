package com.yayuy.overgrownvegetation.perf;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * <b>周期処理がまだ読み込まれていないチャンクを同期で読み込まないための見張り</b>(2026-11-18 大群のための軽量化 B の ④)。
 *
 * <p>11-18 の測り(F7 の前線・約 11 分)で、100 ms 以上の止まり約 10 秒のうち 5 秒が、この MOD の周期処理(胞子の芽の汚染の広がり・
 * 湿地の木の片付け・バイオームの侵食・地脈の送り)の「まだ読み込まれていないチャンクの同期読み込み」の待ちだった。置く場所のチャンクは
 * 読み込み済みでも、{@code setBlock} の隣の形の更新・支えの確かめがチャンクの端で隣のチャンクを読みに行く。待ちは 0.1 ms ずつ寝るので
 * JFR には出ない({@code HordeMeter} の {@code block_ms} で見つけた)。
 *
 * <p>ここで「その周りのチャンクが全部読み込まれているか」を見て、読み込まれていなければ今回は触らずに先送りする(結果は同じで、
 * 読み込まれた範囲の縁で少し遅れるだけ)。
 */
public final class ChunkEdgeGuard {

    private ChunkEdgeGuard() {
    }

    /**
     * {@code pos} から水平に {@code margin} ブロックの四角が触るチャンクが、全部<b>読み込み終わっている</b>か。
     * {@code Level#isLoaded}・{@code hasChunk} は「フルにする予定のチケットがある」だけで、まだ読み込み・生成の途中でも true を返す
     * (そのチャンクに触るとサーバースレッドが終わるまで待つ = 11-18 の止まりの正体)。ここは {@code getChunkNow}(終わっていなければ null)で見る。
     */
    public static boolean areaLoaded(ServerLevel level, BlockPos pos, int margin) {
        boolean ok = areaReady(level, pos, margin);
        if (!ok) {
            denied++;
            lastDenied = pos.immutable();
        }
        return ok;
    }

    /** 先送りした回数(測り用。{@code HordeMeter} が 1 秒ごとに読んで 0 に戻す)。 */
    public static int denied;
    /** 最後に先送りした場所(測り用)。 */
    public static BlockPos lastDenied;

    private static boolean areaReady(ServerLevel level, BlockPos pos, int margin) {
        int x0 = (pos.getX() - margin) >> 4, x1 = (pos.getX() + margin) >> 4;
        int z0 = (pos.getZ() - margin) >> 4, z1 = (pos.getZ() + margin) >> 4;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                if (!ready(level, cx, cz)) return false;
            }
        }
        return true;
    }

    /** そのチャンクが読み込み終わっているか(サーバースレッドで呼ぶ)。 */
    public static boolean ready(ServerLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }
}
