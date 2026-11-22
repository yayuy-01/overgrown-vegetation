package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * 森の縁の見え方(2026-11-13、司令塔 第 1 段の区切り 2)。「森の縁から 48〜96 ブロック離れてから根付く・苗床になる」(D-15)のための、
 * 縁までの距離と、森から離れる向き。森 = 過繁茂のチャンクの台帳({@link PhaseData#isOvergrownChunk})。
 *
 * <p>経路探索はしない: 周り {@code forestSearchChunks} チャンクの台帳を 1 回なめるだけ(既定 8 = 17 × 17 升)。呼ぶのは歩きの 1 区切りごと。
 */
public interface ForestEdge {

    /** この場所から、いちばん近い森(過繁茂のチャンク)までの水平の距離(ブロック)。探す範囲に森が無ければ正の無限大。森の中は 0。 */
    double distance(Vec3 pos);

    /** この場所から森を離れる向き(水平・長さ 1)。森が見当たらなければ null。 */
    Vec3 away(Vec3 pos);

    /** 台帳から見る本物の森。 */
    static ForestEdge of(ServerLevel level) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            // 台帳はオーバーワールドのもの。ほかの次元では森を見分けられないので「森は見当たらない」(= その場で根を下ろす)
            return new ForestEdge() {
                @Override
                public double distance(Vec3 pos) {
                    return Double.POSITIVE_INFINITY;
                }

                @Override
                public Vec3 away(Vec3 pos) {
                    return null;
                }
            };
        }
        PhaseData phase = PhaseData.get(level);
        int r = DesignData.director().forestSearchChunks;
        return new ForestEdge() {
            @Override
            public double distance(Vec3 pos) {
                int cx = (int) Math.floor(pos.x) >> 4, cz = (int) Math.floor(pos.z) >> 4;
                double best = Double.POSITIVE_INFINITY;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (!phase.isOvergrownChunk(new ChunkPos(cx + dx, cz + dz))) continue;
                        best = Math.min(best, toChunk(pos, cx + dx, cz + dz));
                    }
                }
                return best;
            }

            @Override
            public Vec3 away(Vec3 pos) {
                int cx = (int) Math.floor(pos.x) >> 4, cz = (int) Math.floor(pos.z) >> 4;
                double ax = 0.0D, az = 0.0D;
                boolean any = false;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (!phase.isOvergrownChunk(new ChunkPos(cx + dx, cz + dz))) continue;
                        any = true;
                        // 近い升ほど強く押す(距離の 2 乗に反比例)。升の真ん中から
                        double ox = pos.x - ((cx + dx) * 16 + 8.0D), oz = pos.z - ((cz + dz) * 16 + 8.0D);
                        double d2 = Math.max(64.0D, ox * ox + oz * oz);
                        double d = Math.sqrt(d2);
                        ax += ox / d / d2;
                        az += oz / d / d2;
                    }
                }
                if (!any) return null;
                double len = Math.sqrt(ax * ax + az * az);
                // 森の真ん中で四方から同じに押されたら、どちらでもよいので東へ
                return len < 1.0E-9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(ax / len, 0.0D, az / len);
            }
        };
    }

    /** 点から升(16 × 16 の四角)までの水平の距離。升の中は 0。 */
    static double toChunk(Vec3 pos, int chunkX, int chunkZ) {
        double minX = chunkX * 16.0D, minZ = chunkZ * 16.0D;
        double dx = Math.max(0.0D, Math.max(minX - pos.x, pos.x - (minX + 16.0D)));
        double dz = Math.max(0.0D, Math.max(minZ - pos.z, pos.z - (minZ + 16.0D)));
        return Math.sqrt(dx * dx + dz * dz);
    }
}
