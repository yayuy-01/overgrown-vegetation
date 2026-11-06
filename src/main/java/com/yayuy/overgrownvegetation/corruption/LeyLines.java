package com.yayuy.overgrownvegetation.corruption;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地脈の線までの距離だけを測る小さな場(2026-11-03 地下の植生 1。地脈の根 {@code ley_root} の光り方を決める)。
 *
 * <p>本来の地脈の場 {@code LeyVeinField} は 2026-10-30 に極相・地脈ごと parked へ退避した(U6)。その線の作り(歪めた座標のボロノイの境界)と
 * 数(セル 192・歪み 0.35・塩・ハッシュ)を<b>そのまま</b>写したので、同じシードなら退避した地脈と同じ所を通る。
 * 地脈(序盤の地脈技術)を戻すときは、こちらを消して {@code LeyVeinField#distanceToLine} を呼ぶよう替える。
 * シードと座標だけの決定的な関数(チャンクも隣も見ない)。
 */
public final class LeyLines {

    /** 網目の粗さ(セル 1 つの 1 辺、ブロック)。parked の design/climax.json の leyVein.cellSize。 */
    private static final double CELL_SIZE = 192.0D;
    /** 境界線をうねらせる強さ(セルの大きさに対する割合)。leyVein.warp。 */
    private static final double WARP = 0.35D;
    /** 地脈のセルのシード塩(LeyVeinField と同じ)。 */
    private static final long SALT = 0x1E4A1E17L;

    private static final Map<Long, SimplexNoise> WARP_NOISE = new ConcurrentHashMap<>();

    private LeyLines() {
    }

    /** いちばん近い地脈の線までの水平の距離(ブロック、歪めた座標の上)。セルの真ん中でおよそ 100。 */
    public static double distanceToLine(long seed, int blockX, int blockZ) {
        SimplexNoise noise = WARP_NOISE.computeIfAbsent(seed ^ SALT, s -> new SimplexNoise(RandomSource.create(s)));
        double scale = 1.0D / Math.max(16.0D, CELL_SIZE * 0.75D);
        double amp = CELL_SIZE * WARP;
        double x = blockX + noise.getValue(blockX * scale, blockZ * scale) * amp;
        double z = blockZ + noise.getValue(blockZ * scale + 1337.0D, blockX * scale - 911.0D) * amp;
        return distanceToBorder(seed, x / CELL_SIZE, z / CELL_SIZE) * CELL_SIZE;
    }

    /** セル座標から、いちばん近い母点と 2 番目の母点の垂直二等分線までの距離(セル単位)。LeyVeinField と同じ。 */
    private static double distanceToBorder(long seed, double cx, double cz) {
        int baseX = Mth.floor(cx);
        int baseZ = Mth.floor(cz);
        double bestSq = Double.MAX_VALUE;
        double bestX = 0.0D;
        double bestZ = 0.0D;
        double secondSq = Double.MAX_VALUE;
        double secondX = 0.0D;
        double secondZ = 0.0D;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int gx = baseX + dx;
                int gz = baseZ + dz;
                long h = hash(seed, gx, gz);
                double px = gx + ((h >>> 16) & 0xFFFF) / 65536.0D;
                double pz = gz + ((h >>> 32) & 0xFFFF) / 65536.0D;
                double dSq = (px - cx) * (px - cx) + (pz - cz) * (pz - cz);
                if (dSq < bestSq) {
                    secondSq = bestSq;
                    secondX = bestX;
                    secondZ = bestZ;
                    bestSq = dSq;
                    bestX = px;
                    bestZ = pz;
                } else if (dSq < secondSq) {
                    secondSq = dSq;
                    secondX = px;
                    secondZ = pz;
                }
            }
        }
        if (secondSq == Double.MAX_VALUE) {
            return Double.MAX_VALUE;
        }
        double siteDist = Math.sqrt((secondX - bestX) * (secondX - bestX) + (secondZ - bestZ) * (secondZ - bestZ));
        if (siteDist < 1.0e-6D) {
            return Double.MAX_VALUE;
        }
        return (secondSq - bestSq) / (2.0D * siteDist);
    }

    private static long hash(long seed, int x, int z) {
        long h = seed ^ SALT;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return h;
    }
}
