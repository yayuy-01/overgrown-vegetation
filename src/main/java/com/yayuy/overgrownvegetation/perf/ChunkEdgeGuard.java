package com.yayuy.overgrownvegetation.perf;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;

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

    // ---- 2026-11-18 夕: isLoaded・hasChunk・hasChunkAt の置き換え(全体管理の依頼「生成し終わったかを確かめずに使っている所を直す」) ----
    // 意味は元のメソッドと同じ(高さの外は false など)だが、サーバーのワールドをサーバースレッドで見るときだけ「読み込み終わっているか」で答える。
    // それ以外(クライアントのワールド・世界生成の WorldGenRegion・別スレッド)は元のメソッドのまま。読み込み途中のチャンクは「読み込まれていない」扱いになり、
    // 触る側はいつもどおり飛ばす・先送りする(読み込み終われば次の回に入る)。

    /** {@code Level#isLoaded(pos)} の置き換え。 */
    public static boolean isLoaded(LevelReader level, BlockPos pos) {
        if (level instanceof ServerLevel sl && sl.getServer().isSameThread()) {
            if (sl.isOutsideBuildHeight(pos)) return false;
            return readyOrOld(sl, pos.getX() >> 4, pos.getZ() >> 4);
        }
        return level instanceof Level l ? l.isLoaded(pos) : level.hasChunkAt(pos);
    }

    /** {@code LevelReader#hasChunk(x, z)} の置き換え。 */
    public static boolean hasChunk(LevelReader level, int chunkX, int chunkZ) {
        if (level instanceof ServerLevel sl && sl.getServer().isSameThread()) {
            return readyOrOld(sl, chunkX, chunkZ);
        }
        return level.hasChunk(chunkX, chunkZ);
    }

    /** 測り用の切り替え(false = 元のメソッドのまま。/ov perf horde edge on|off)。既定 true。 */
    public static volatile boolean replaceEnabled = true;
    /** 元のメソッドなら true だったが読み込み途中だった回数(= 前ならサーバースレッドが待っていた所)を、呼んだ所ごとに。サーバースレッドだけ。 */
    public static final java.util.Map<String, Integer> DIVERGED = new java.util.HashMap<>();
    /** 測りの外で数えた分の行(呼び元は辿らない)。 */
    static final String UNTRACED = "(呼び元は /ov perf horde の測りの間だけ)";

    private static boolean readyOrOld(ServerLevel sl, int chunkX, int chunkZ) {
        if (ready(sl, chunkX, chunkZ)) return true;
        if (!sl.getChunkSource().hasChunk(chunkX, chunkZ)) return false; // 元のメソッドでも読み込まれていない
        // 呼び元の文字列づくり(スタックを辿る)は、大群の測り(HordeMeter)を回している間だけ。ふだんは数だけ(11-18 見直し ③3。
        // 前は読み込みの縁でいつも辿って HashMap に足していた)
        if (!HordeMeter.on) {
            DIVERGED.merge(UNTRACED, 1, Integer::sum);
            return !replaceEnabled;
        }
        String caller = StackWalker.getInstance().walk(s -> s.map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                .filter(n -> !n.startsWith("ChunkEdgeGuard.")).findFirst().orElse("?"));
        DIVERGED.merge(caller, 1, Integer::sum);
        return !replaceEnabled; // 切ってあれば元のとおり true(読み込みを待つ)
    }

    /** {@code LevelReader#hasChunkAt(pos)} の置き換え。 */
    public static boolean hasChunkAt(LevelReader level, BlockPos pos) {
        return hasChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** {@link #edge} の答え。 */
    public enum Edge {
        /** 読み込み終わっている(触ってよい)。 */
        READY,
        /** 読み込み・生成の途中(もうすぐ終わる。触らずに、カーソルを進めず次の回に同じ所からやり直す)。 */
        WAIT,
        /** 読み込まれていない(元のメソッドでも false。いつもどおり飛ばす)。 */
        GONE
    }

    /**
     * カーソルで順に置く仕事(巨大樹・つる・文法の木)向けの見張り(11-18 見直し ③4)。{@link #isLoaded} に置き換えるだけだと、
     * 読み込み途中のチャンクも「読み込まれていない」になってカーソルが進み、その所の設置を永久に飛ばす(Codex の注意)。
     * 真ん中のチャンクが読み込み途中なら {@link Edge#WAIT}、読み込まれていなければ {@link Edge#GONE}。
     * {@code margin} ブロックの中の隣のチャンクが読み込み途中なら WAIT(隣が読み込まれていないだけなら、前と同じく触る)。
     */
    public static Edge edge(ServerLevel level, BlockPos pos, int margin) {
        if (level.isOutsideBuildHeight(pos)) return Edge.GONE;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        if (!ready(level, cx, cz)) {
            return level.getChunkSource().hasChunk(cx, cz) ? Edge.WAIT : Edge.GONE;
        }
        if (margin > 0) {
            int x0 = (pos.getX() - margin) >> 4, x1 = (pos.getX() + margin) >> 4;
            int z0 = (pos.getZ() - margin) >> 4, z1 = (pos.getZ() + margin) >> 4;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if ((x != cx || z != cz) && !ready(level, x, z) && level.getChunkSource().hasChunk(x, z)) return Edge.WAIT;
                }
            }
        }
        return Edge.READY;
    }

    /** そのチャンクが読み込み終わっているか(サーバースレッドで呼ぶ)。 */
    public static boolean ready(ServerLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }
}
