package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 世界生成の地形の高さ(ノイズから。読み込んでいない場所・生成の途中でも分かる)で、基地の地形の標本を取る(2026-10-04、
 * {@code /ov base place} のコマンドから移した。世界生成の基地も同じ取り方 —— 順 2 の着手順 2)。
 * 地面 = 固いブロックの天辺(水の下なら水底)、水面 = 空気でない天辺(水を含む)。水面が地面より上の標本が水で、配置計画は区画を掛けない。
 * 地形の装飾(木・湖の地物)は入らない。生成の裏の作業場から呼んでよい(ノイズは同時に読める)。
 */
public final class BaseNoise {

    /** 中心 {@code center}・向き {@code rotation}(時計回りの 1/4 回転の回数)の基地の標本。 */
    public static BaseTerrain sample(ChunkGenerator gen, RandomState rs, LevelHeightAccessor heights, BlockPos center, int rotation) {
        return BaseTerrain.sample(sampler(gen, rs, heights, center, rotation));
    }

    /**
     * 地形のノイズを読む口(同じ点は 1 回だけ読む。世界生成の判定で、中心だけ先に読んでから残りを読むため)。いくつものスレッドから同時に読んでよい
     * ({@link #warm} が点を並べて読む。同じ点を 2 つのスレッドが同時に読んだら、先に入れた方を使う)。
     */
    public static BaseTerrain.Sampler sampler(ChunkGenerator gen, RandomState rs, LevelHeightAccessor heights, BlockPos center, int rotation) {
        java.util.Map<Long, int[]> cache = new java.util.concurrent.ConcurrentHashMap<>();
        return new BaseTerrain.Sampler() {
            private int[] at(int lx, int lz) {
                long key = ((long) lx << 32) ^ (lz & 0xFFFFFFFFL);
                int[] v = cache.get(key);
                if (v != null) {
                    return v;
                }
                v = read(lx, lz); // 重い計算はマップの鍵の外で(computeIfAbsent の中だと同じ箱の点を読むほかのスレッドが待つ)
                int[] prev = cache.putIfAbsent(key, v);
                return prev != null ? prev : v;
            }

            private int[] read(int lx, int lz) {
                BlockPos w = worldOf(center, rotation, lx, lz);
                // 柱を 1 回だけ計算して、上から「空気でない」(= 高さ地図 WORLD_SURFACE_WG)と「動きを止める」(= OCEAN_FLOOR_WG)を探す
                // (高さ地図を 2 回聞くのと同じ値。柱の計算が重いので 1 回に)
                NoiseColumn col = gen.getBaseColumn(w.getX(), w.getZ(), heights, rs);
                int min = heights.getMinBuildHeight();
                int surface = min - 1;
                int ground = min - 1;
                for (int y = heights.getMaxBuildHeight() - 1; y >= min; y--) {
                    BlockState s = col.getBlock(y);
                    if (surface < min && !s.isAir()) {
                        surface = y;
                    }
                    if (s.blocksMotion()) {
                        ground = y;
                        break;
                    }
                }
                return new int[]{ground, surface};
            }

            @Override
            public int groundY(int lx, int lz) {
                return at(lx, lz)[0];
            }

            @Override
            public int surfaceY(int lx, int lz) {
                return at(lx, lz)[1];
            }
        };
    }

    /**
     * 中心から {@code radius} ブロックの四角の中だけ本当に読み、外はいちばん近い中の点の値で埋めた標本(中心の判定
     * {@code BasePlanner#coreCheck} を安く先に済ませる。半径 56 なら判定に効く点 —— 水は中心 ± 42、起伏はなだらかな面の 2 回の均しで ± 56 —— が全部入る)。
     */
    public static BaseTerrain sampleCore(BaseTerrain.Sampler sampler, int radius) {
        return BaseTerrain.sample(new BaseTerrain.Sampler() {
            @Override
            public int groundY(int lx, int lz) {
                return sampler.groundY(clamp(lx, radius), clamp(lz, radius));
            }

            @Override
            public int surfaceY(int lx, int lz) {
                return sampler.surfaceY(clamp(lx, radius), clamp(lz, radius));
            }
        });
    }

    /** 中心から {@code radius} ブロックの四角の中の、標本の格子(8 マスおき)の点(半径 58 なら 15 × 15 = {@link #sampleCore} が本当に読む点)。 */
    public static java.util.List<int[]> gridPoints(int radius) {
        int lim = Math.floorDiv(radius, BaseTerrain.STEP) * BaseTerrain.STEP;
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int x = -lim; x <= lim; x += BaseTerrain.STEP) {
            for (int z = -lim; z <= lim; z += BaseTerrain.STEP) {
                out.add(new int[]{x, z});
            }
        }
        return out;
    }

    /**
     * 点 {@code points} を先に読んでおく(2026-10-04、{@code /locate} を軽くするため)。本体のスレッド({@code /locate} は本体の tick で候補を順に判定する)
     * からなら、点を並べて読む(地形の柱の計算は生成の作業場が同時に何本も回しているもので、並べて読んでよい)。生成の作業場・数え上げの裏の作業場
     * (どちらも ForkJoin の作業スレッドで、もう候補ごとに並んでいる)からは何もしない —— 後で順に読まれる。
     */
    public static void warm(BaseTerrain.Sampler sampler, java.util.List<int[]> points) {
        if (Thread.currentThread() instanceof java.util.concurrent.ForkJoinWorkerThread) {
            return;
        }
        points.parallelStream().forEach(p -> sampler.groundY(p[0], p[1]));
    }

    /** {@code v} を [−r, r] の中の、標本の格子(8 マスおき)の点へ寄せる。 */
    private static int clamp(int v, int r) {
        int lim = Math.floorDiv(r, BaseTerrain.STEP) * BaseTerrain.STEP;
        return Math.max(-lim, Math.min(lim, v));
    }

    /** 局所座標 → 世界の座標(y = 0。{@link BaseSite#toWorld} と同じ回し方)。 */
    static BlockPos worldOf(BlockPos center, int rotation, int lx, int lz) {
        int x = lx;
        int z = lz;
        for (int i = 0; i < Math.floorMod(rotation, 4); i++) {
            int t = x;
            x = -z;
            z = t;
        }
        return new BlockPos(center.getX() + x, 0, center.getZ() + z);
    }

    private BaseNoise() {
    }
}
