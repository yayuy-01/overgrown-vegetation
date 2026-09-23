package com.yayuy.overgrownvegetation.worldgen.grammar;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * {@link WorldGenLevel} を {@link GrammarTerrain} に見せるアダプタ(ゲーム内配置用)。
 * SPEC §5。
 */
final class LevelTerrain implements GrammarTerrain {

    private final WorldGenLevel level;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    LevelTerrain(WorldGenLevel level) {
        this.level = level;
    }

    @Override
    public int groundY(int x, int z) {
        // 流体を除いた一番上の固体の y(= 表面のブロックそのものの y)。
        return level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        var state = level.getBlockState(cursor.set(x, y, z));
        return !state.isAir() && !state.canBeReplaced();
    }

    @Override
    public boolean isReplaceable(int x, int y, int z) {
        var state = level.getBlockState(cursor.set(x, y, z));
        return state.isAir() || state.canBeReplaced();
    }
}
