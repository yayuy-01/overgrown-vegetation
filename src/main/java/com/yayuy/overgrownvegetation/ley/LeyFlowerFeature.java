package com.yayuy.overgrownvegetation.ley;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * 世界を作るとき、地脈の線の上に地脈の花を置く(決定 25)。biome_modifier {@code ley_flowers} がオーバーワールドの全バイオームの
 * 植物の段(vegetal_decoration)に足す。原点のチャンクの中で {@code flowerWorldgenTries} 回、ばらばらの列を選び、
 * 線の帯の中で草・土の上の空気なら置く(線が横切るチャンクで 3〜4 本の点線になる。数は【仮】)。
 */
public class LeyFlowerFeature extends Feature<NoneFeatureConfiguration> {

    public LeyFlowerFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        BlockPos origin = context.origin();
        int baseX = origin.getX() & ~15;
        int baseZ = origin.getZ() & ~15;
        long seed = level.getSeed();
        int tries = DesignData.sporeField().ley().flowerWorldgenTries();
        boolean placed = false;
        for (int i = 0; i < tries; i++) {
            int x = baseX + random.nextInt(16);
            int z = baseZ + random.nextInt(16);
            if (!LeyLines.inFlowerBand(seed, x, z)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            placed |= placeAt(level, new BlockPos(x, y, z));
        }
        return placed;
    }

    /** {@code pos} に花を置けるなら置く。空気か置き換えられる草(短い草など。水は除く)で、下が草・土の類、花が生き残れる所。 */
    static boolean placeAt(LevelAccessor level, BlockPos pos) {
        BlockState here = level.getBlockState(pos);
        if (!here.isAir() && !(here.canBeReplaced() && here.getFluidState().isEmpty()
                && !(here.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock))) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        if (!below.is(BlockTags.DIRT)) {
            return false;
        }
        BlockState flower = ModLey.FLOWER.get().defaultBlockState();
        if (!flower.canSurvive(level, pos)) {
            return false;
        }
        return level.setBlock(pos, flower, 2);
    }
}
