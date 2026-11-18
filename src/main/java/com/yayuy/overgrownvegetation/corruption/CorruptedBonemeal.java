package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 「過繁茂した〇〇」系のブロックに骨粉を使ったときの効果。
 *
 * <p>バニラの草ブロックに骨粉を使ったときと同じ発想で、対象ブロックとその周囲
 * (水平半径 {@value #RADIUS}、上下 1 ブロック)にある汚染ブロック(フルブロック)の
 * 露出面から、汚染されたときに生えるのと同じ装飾植物(シダ・苔・蔦・下草・茂みの塊など)を
 * 一気に生やす。中心に近いブロックほど高い確率で対象になる。
 *
 * <p>骨粉アイテムの使用時({@link CorruptedBonemealHandler})に呼ばれる。
 * (胞子感染で死んだスケルトンの死亡地点からも呼んでいた SporeInfectionDeathHandler は 2026-10-30 に parked/ へ退避した。)
 */
public final class CorruptedBonemeal {

    /** 効果が及ぶ水平半径(ブロック)。バニラの骨粉の草拡散({@code BoneMealItem.GRASS_SPREAD_WIDTH})と同じ。 */
    private static final int RADIUS = 3;

    /**
     * {@code center} を中心に骨粉の成長効果を適用する。
     *
     * @return 実際に 1 つ以上のブロックへ成長処理を行ったら true
     *         (周囲に汚染フルブロックが一切無ければ false)
     */
    public static boolean apply(ServerLevel level, BlockPos center) {
        if (!OvergrownVegetationConfig.COMMON.spawnGrowthPlants.get()) {
            return false;
        }

        final RandomSource random = level.random;
        final double growthChance = OvergrownVegetationConfig.COMMON.growthChance.get();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        boolean any = false;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, cursor)) {
                        continue;
                    }

                    BlockState state = level.getBlockState(cursor);
                    if (!state.is(ModTags.CORRUPTED) || !state.isCollisionShapeFullBlock(level, cursor)) {
                        continue;
                    }

                    double horizontal = Math.sqrt((double) (dx * dx + dz * dz));
                    double pick = horizontal <= 1.5D ? 1.0D : (horizontal <= 2.5D ? 0.6D : 0.25D);
                    if (random.nextDouble() >= pick) {
                        continue;
                    }

                    BlockPos pos = cursor.immutable();
                    // 骨粉で同じ岩を何度も装飾しても鉱石は増やさない(2026-11-17。鉱石は汚染が来たときだけ)
                    com.yayuy.overgrownvegetation.gear.FungalOre.suppressed = true;
                    try {
                        CorruptionSpreader.growSurfacePlants(level, pos, state, growthChance);
                    } finally {
                        com.yayuy.overgrownvegetation.gear.FungalOre.suppressed = false;
                    }
                    LeafClumpGrowth.tryGrow(level, pos, random);
                    any = true;
                }
            }
        }

        if (any) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D,
                    15, 0.6D, 0.6D, 0.6D, 0.0D);
        }
        return any;
    }

    private CorruptedBonemeal() {
    }
}
