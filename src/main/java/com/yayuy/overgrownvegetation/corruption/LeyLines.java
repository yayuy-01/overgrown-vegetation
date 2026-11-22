package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.LeySpec;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>地脈の場の正本</b>(2026-11-03 地下の植生 1 で作り、2026-11-13 サバイバルの背骨 段 2b で正本に決定 = Vault の決定 24)。どこを地脈の線が通るか。
 *
 * <p>線は歪めた座標のボロノイの境界(セルの境目がそのまま切れ目の無い網になる)。退避した {@code parked/…/LeyVeinField} と式・数(セル 192・歪み 0.35・塩・ハッシュ)が
 * 同じなので、同じシードなら前の地脈と同じ所を通る(決定 19「線の計算は前の物を戻す」)。シードと座標だけの決定的な関数(チャンクも隣も見ない)。
 * 形の数は {@code spore_field.json} の {@code ley}({@link LeySpec})。使う所: 洞窟の地脈の根({@code CaveFlora})・巨大樹の夜の光({@code GiantTreeSync})・
 * 地脈の花と汲み上げ器({@code ley/})。
 */
public final class LeyLines {

    private static LeySpec spec() {
        return DesignData.sporeField().ley();
    }
    /** 地脈のセルのシード塩(LeyVeinField と同じ)。 */
    private static final long SALT = 0x1E4A1E17L;

    private static final Map<Long, SimplexNoise> WARP_NOISE = new ConcurrentHashMap<>();

    private LeyLines() {
    }

    /** いちばん近い地脈の線までの水平の距離(ブロック、歪めた座標の上)。セルの真ん中でおよそ 100。 */
    public static double distanceToLine(long seed, int blockX, int blockZ) {
        SimplexNoise noise = WARP_NOISE.computeIfAbsent(seed ^ SALT, s -> new SimplexNoise(RandomSource.create(s)));
        LeySpec spec = spec();
        double cell = spec.cellSize();
        double scale = 1.0D / Math.max(16.0D, cell * 0.75D);
        double amp = cell * spec.warp();
        double x = blockX + noise.getValue(blockX * scale, blockZ * scale) * amp;
        double z = blockZ + noise.getValue(blockZ * scale + 1337.0D, blockX * scale - 911.0D) * amp;
        return distanceToBorder(seed, x / cell, z / cell) * cell;
    }

    /**
     * 汲み上げ器の効率 0〜1(決定 20: 線の真上で 1、線から {@code pumpReach} 離れると {@code pumpMinEfficiency} まで直線に下がる。下がり方は【仮】)。
     */
    public static double efficiency(long seed, int blockX, int blockZ) {
        LeySpec spec = spec();
        double d = distanceToLine(seed, blockX, blockZ);
        double t = 1.0D - Math.min(1.0D, d / spec.pumpReach());
        return spec.pumpMinEfficiency() + (1.0D - spec.pumpMinEfficiency()) * t;
    }

    /** 地脈の花が生えてよい帯の中か(線から {@code flowerBand} 以内)。 */
    public static boolean inFlowerBand(long seed, int blockX, int blockZ) {
        return distanceToLine(seed, blockX, blockZ) <= spec().flowerBand();
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
