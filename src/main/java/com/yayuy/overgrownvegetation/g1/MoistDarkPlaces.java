package com.yayuy.overgrownvegetation.g1;

import com.yayuy.overgrownvegetation.data.G1Table;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * 苗床の行き先 = 近くの湿った暗い場所(G1 の作り直し 第 1 段)。
 *
 * <p>湿り気の測り方は萌芽体の {@code BlightFernEntity#moisture}(周り 5×5×2 の水 1 つで +0.4)を流用し、
 * 時刻・雨の分(その時だけ湿る)は数えずに、沼のようなバイオームなら +0.5 を足した(苗床は居着く所なので、仮決め)。
 * 暗さは空の光が {@code g1.json nursery.maxSkyLight} 以下で、松明などの明かりが 7 以下。
 * 経路探索はしない(近くの点を数十だけ当てて、立てる所の点数がいちばん高い所)。
 */
public final class MoistDarkPlaces {

    private static final int SAMPLES = 32;

    /** {@code origin} のまわり(半径 {@code radius})で、湿っていて暗い立てる所。無ければ null。 */
    @Nullable
    public static BlockPos find(ServerLevel level, BlockPos origin, int radius, G1Table g, RandomSource random) {
        BlockPos best = null;
        double bestScore = -1;
        for (int i = 0; i < SAMPLES; i++) {
            int x = origin.getX() + random.nextInt(radius * 2 + 1) - radius;
            int z = origin.getZ() + random.nextInt(radius * 2 + 1) - radius;
            BlockPos stand = standAt(level, x, origin.getY(), z);
            if (stand == null) continue;
            if (level.getBrightness(LightLayer.SKY, stand) > g.nurseryMaxSkyLight) continue;
            if (level.getBrightness(LightLayer.BLOCK, stand) > 7) continue;
            double m = moisture(level, stand);
            if (m < g.nurseryMinMoisture) continue;
            double score = m - 0.02D * Math.sqrt(stand.distSqr(origin));
            if (score > bestScore) {
                bestScore = score;
                best = stand;
            }
        }
        return best;
    }

    /** 湿り気: 周り 5×5×2 の水 1 マスで +0.4、沼・マングローブのようなバイオームで +0.5。 */
    public static double moisture(ServerLevel level, BlockPos pos) {
        double m = 0;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -1; dy <= 0; dy++) {
                    c.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.getFluidState(c).is(FluidTags.WATER)) {
                        m += 0.4;
                    }
                }
        if (ModBiomes.isSwampLike(level.getBiome(pos))) {
            m += 0.5;
        }
        return m;
    }

    /** (x, z) の y の近く(上 4〜下 6)で、足元が固く、そこと 1 つ上が空いている(水でない)所。 */
    @Nullable
    public static BlockPos standAt(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = 4; dy >= -6; dy--) {
            p.set(x, y + dy, z);
            if (!level.isLoaded(p)) return null;
            if (!free(level, p) || !free(level, p.above())) continue;
            BlockPos below = p.below();
            BlockState under = level.getBlockState(below);
            if (under.isFaceSturdy(level, below, Direction.UP)) {
                return p.immutable();
            }
        }
        return null;
    }

    private static boolean free(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty();
    }

    private MoistDarkPlaces() {
    }
}
