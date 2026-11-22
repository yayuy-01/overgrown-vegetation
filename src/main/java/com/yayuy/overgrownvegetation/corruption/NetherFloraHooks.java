package com.yayuy.overgrownvegetation.corruption;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>ネザーの植物の差し込み口</b>(2026-11-11 骨組み)。中身は後の植物の段(設備の層・緑の層)が {@link #register} で足す。
 * 骨組み({@link NetherStageWorker})は決まった時に呼ぶだけで、植物を 1 つも置かない。
 *
 * <p>呼ばれるのはどれもサーバースレッド・そのチャンクと周り 8 チャンクが読み込まれているとき。
 */
public final class NetherFloraHooks {

    /** 植物の段が実装する口。使わないものは空のままでよい。 */
    public interface Flora {
        /**
         * そのチャンクの地面の作り変えが段階 {@code stage}(1〜5)まで終わった直後に 1 回(段階ごと)。
         * 段階 1: 先駆け / 2: 熱を吸う / 3: 水 / 4: 雲と雨 / 5: 湿地。
         */
        default void onStageApplied(ServerLevel level, LevelChunk chunk, int stage) {
        }

        /**
         * 段階 1 のチャンクで「地上の植物が芽生えては枯れる」ための定期の呼び出し({@code ringTicks} ごと)。
         * {@code age} は前線が届いてからの tick。{@code budget[0]} は帯の予算(blocksPerTick)の残り。置いた数だけ引く(2026-11-12)。
         */
        default void onPioneerTick(ServerLevel level, LevelChunk chunk, long age, int[] budget) {
        }

        /**
         * 段階 1 の終わり(覆いの見回りが終わった後、段階 1 を記録する前)。段階 1 の間に描ききれなかった分を、
         * 帯の予算 {@code budget[0]} の中で少しずつ仕上げる。全部終われば true(false の間は次の tick にまた呼ばれ、段階 2 へ進まない)。
         */
        default boolean finishPioneer(ServerLevel level, LevelChunk chunk, int[] budget) {
            return true;
        }

        /**
         * 段階 2 から(熱を吸う)、海の表面の殻の輪と同じ間隔({@code ringTicks})で。管の樹など、殻の上に生える植物を帯の予算 {@code budget[0]} の中で置く
         * (置いた数だけ引く)。{@code cols} はこのチャンクが持ち主の列(境目のまだら)。{@code crustDone} = 殻の輪が終わった(残りを全部置いてよい)。
         * 全部終われば true(殻が終わってから true を返すまで、段階 3 へ進まない)。2026-11-12。
         */
        default boolean onHeatTick(ServerLevel level, LevelChunk chunk, int[] cols, boolean crustDone, int[] budget) {
            return true;
        }

        /**
         * 湖が {@code skyPillarLakeCells} マスの水面を超えたとき、その湖に 1 回だけ(天を支える樹を 1 本生やす口)。
         * {@code water} は湖の水面の 1 マス(湖のいちばん大きいチャンクのうち、中心に近い所)。
         */
        default void onLakeReady(ServerLevel level, BlockPos water, int lakeCells) {
        }
    }

    private static final List<Flora> FLORA = new CopyOnWriteArrayList<>();

    public static void register(Flora flora) {
        FLORA.add(flora);
    }

    /** 計測用(植物の段の重さ。NetherStageWorker.perfLine が出す): 呼んだ回数・使った時間・いちばん長かった 1 回。 */
    static long calls, nanos, maxNanos;
    /** 植物が置いたブロック数(植物の段が足す)。 */
    public static long blocks;

    static void stageApplied(ServerLevel level, LevelChunk chunk, int stage) {
        long t0 = System.nanoTime();
        for (Flora f : FLORA) f.onStageApplied(level, chunk, stage);
        measure(t0);
    }

    static void pioneerTick(ServerLevel level, LevelChunk chunk, long age, int[] budget) {
        long t0 = System.nanoTime();
        for (Flora f : FLORA) f.onPioneerTick(level, chunk, age, budget);
        measure(t0);
    }

    /** 段階 1 の仕上げ。どの植物も終わっていれば true。 */
    static boolean finishPioneer(ServerLevel level, LevelChunk chunk, int[] budget) {
        long t0 = System.nanoTime();
        boolean done = true;
        for (Flora f : FLORA) done &= f.finishPioneer(level, chunk, budget);
        measure(t0);
        return done;
    }

    /** 段階 2 からの殻の上の植物。どの植物も終わっていれば true。 */
    static boolean heatTick(ServerLevel level, LevelChunk chunk, int[] cols, boolean crustDone, int[] budget) {
        long t0 = System.nanoTime();
        boolean done = true;
        for (Flora f : FLORA) done &= f.onHeatTick(level, chunk, cols, crustDone, budget);
        measure(t0);
        return done;
    }

    private static void measure(long t0) {
        long d = System.nanoTime() - t0;
        calls++;
        nanos += d;
        maxNanos = Math.max(maxNanos, d);
    }

    static void lakeReady(ServerLevel level, BlockPos water, int lakeCells) {
        for (Flora f : FLORA) f.onLakeReady(level, water, lakeCells);
    }

    /** テスト用: 天を支える樹の口が呼ばれた回数を数える。 */
    public static final class Counter implements Flora {
        public int lakes;
        public final List<int[]> stages = new CopyOnWriteArrayList<>();

        @Override
        public void onStageApplied(ServerLevel level, LevelChunk chunk, int stage) {
            ChunkPos p = chunk.getPos();
            stages.add(new int[]{p.x, p.z, stage});
        }

        @Override
        public void onLakeReady(ServerLevel level, BlockPos water, int lakeCells) {
            lakes++;
        }
    }

    public static void unregister(Flora flora) {
        FLORA.remove(flora);
    }

    private NetherFloraHooks() {
    }
}
