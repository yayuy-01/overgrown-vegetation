package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

/**
 * 地表の汚染の縁を列ごとに決める(2026-11-04 ユーザー決定「地上の汚染がチャンクの境で止まる」の A。数は仮決め)。
 *
 * <p>チャンクごとの値 = 繁茂系のバイオーム(真ん中 1 点)か場で発芽済みなら 1、それ以外は 場の降り積もり ÷ 発芽のしきい値(1 で頭打ち)× {@code edge.depositWeight}。
 * 列の値 = まわり 4 チャンクの中心どうしで双線形に混ぜた値(濃度・地下の芽と同じ混ぜ方)+ 雑音 ±{@code edge.noiseAmp}
 * (模様の大きさ {@code edge.noiseBlocks} ブロックの値の雑音)。{@code edge.threshold} を超える列だけ縁の浸食が進む。
 * 汚染されたチャンクと汚染されていないチャンクの境は値 0.5 になり、しきい値 0.35 なら隣へ最大 8 ブロックほど、雑音で入り組んで入る(格子に沿わない)。
 * 繁茂系のチャンクの中では門を使わない(内側に塗り残しを作らない)。
 * バイオームの札の付け方そのものは変えない。
 */
public final class EdgeGate {

    private final ServerLevel level;
    private final int cx0, cz0;
    /** まわり 3×3 チャンク(cx0-1 .. cx0+1)の値。 */
    private final double[] score = new double[9];
    private final double amp, cell, threshold;
    private final long seed;

    private EdgeGate(ServerLevel level, ChunkPos center) {
        this.level = level;
        this.cx0 = center.x;
        this.cz0 = center.z;
        SporeFieldTable t = DesignData.sporeField();
        this.amp = t.edgeNoiseAmp();
        this.cell = t.edgeNoiseBlocks();
        this.threshold = t.edgeThreshold();
        this.seed = level.getSeed() ^ 0xED6E6A7EL;
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) score[(dz + 1) * 3 + dx + 1] = chunkScore(level, center.x + dx, center.z + dz, t);
    }

    /** そのチャンクと周りの値を読んで門を作る(チャンク 1 つの処理の頭で 1 回)。 */
    public static EdgeGate of(ServerLevel level, ChunkPos center) {
        return new EdgeGate(level, center);
    }

    static double chunkScore(ServerLevel level, int cx, int cz, SporeFieldTable t) {
        BlockPos sample = new BlockPos((cx << 4) + 8, level.getSeaLevel(), (cz << 4) + 8);
        if (ModBiomes.isOvergrownLike(level.getBiome(sample))) return 1.0D;
        SporeFieldServer.Cell c = SporeFieldServer.cell(level, cx, cz);
        if (c == null) return 0.0D;
        if (c.stage() > 0) return 1.0D;
        return Math.min(1.0D, c.dep() / Math.max(1e-9, t.field().germThreshold)) * t.edgeDepositWeight();
    }

    /** 列 (x, z) の混ぜた値(雑音なし)。このチャンクと 8 方の隣の中だけ。 */
    public double blended(int x, int z) {
        double fx = x / 16.0D - 0.5D, fz = z / 16.0D - 0.5D;
        int x0 = Mth.floor(fx), z0 = Mth.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        return (at(x0, z0) * (1 - tx) + at(x0 + 1, z0) * tx) * (1 - tz) + (at(x0, z0 + 1) * (1 - tx) + at(x0 + 1, z0 + 1) * tx) * tz;
    }

    private double at(int cx, int cz) {
        int dx = Mth.clamp(cx - cx0, -1, 1), dz = Mth.clamp(cz - cz0, -1, 1);
        return score[(dz + 1) * 3 + dx + 1];
    }

    /** 列 (x, z) で縁の浸食を進めてよいか。 */
    public boolean open(int x, int z) {
        return blended(x, z) + amp * noise(seed, x, z, cell) > threshold;
    }

    /** 値の雑音(-1〜1、格子 cell ブロックの点の乱数を双線形に混ぜる)。 */
    public static double noise(long seed, int x, int z, double cell) {
        double fx = x / cell, fz = z / cell;
        int x0 = Mth.floor(fx), z0 = Mth.floor(fz);
        double tx = Mth.smoothstep(fx - x0), tz = Mth.smoothstep(fz - z0);
        return Mth.lerp(tz, Mth.lerp(tx, lattice(seed, x0, z0), lattice(seed, x0 + 1, z0)),
                Mth.lerp(tx, lattice(seed, x0, z0 + 1), lattice(seed, x0 + 1, z0 + 1)));
    }

    private static double lattice(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return ((h >>> 11) * 0x1.0p-53) * 2 - 1;
    }

    /** そのチャンクか 4 方の隣が繁茂系(縁の浸食の対象にするチャンク)か。 */
    public static boolean nearOvergrown(ServerLevel level, int cx, int cz) {
        return like(level, cx, cz) || like(level, cx + 1, cz) || like(level, cx - 1, cz) || like(level, cx, cz + 1) || like(level, cx, cz - 1);
    }

    private static boolean like(ServerLevel level, int cx, int cz) {
        if (!level.hasChunk(cx, cz)) return false;
        return ModBiomes.isOvergrownLike(level.getBiome(new BlockPos((cx << 4) + 8, level.getSeaLevel(), (cz << 4) + 8)));
    }

}
