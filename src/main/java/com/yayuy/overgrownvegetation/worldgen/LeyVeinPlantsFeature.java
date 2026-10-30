package com.yayuy.overgrownvegetation.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * 地脈の線に沿って地表に<b>地脈の花</b>と<b>地脈の結晶</b>を生やす(2026-10-22、序盤の作り直し。ユーザー指定
 * 「地脈の線に沿って点々と生える。どのバイオームでも、汚染の有無に関係なく」)。
 *
 * <p>配置の修飾子なしで 1 チャンクに 1 回呼ばれ、チャンクの 16×16 の列を全部見る。{@link LeyVeinField} はシードと座標だけの
 * 関数なので、チャンクの境目でも線はずれない。線の上の列ごとに {@code flower_chance × 強さ}(線の真ん中ほど濃い)で花、
 * {@code crystal_chance × 強さ} で結晶(地面を芽吹く地脈の結晶に置き換え、上に芽か塊を 1 つ)。確率は configured_feature の json。
 *
 * <p>地表は {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES}(木の下の地面にも生える。葉のてっぺんには生えない)。
 */
public class LeyVeinPlantsFeature extends Feature<LeyVeinPlantsFeature.Config> {

    public record Config(float flowerChance, float crystalChance) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.floatRange(0.0F, 1.0F).fieldOf("flower_chance").forGetter(Config::flowerChance),
                Codec.floatRange(0.0F, 1.0F).fieldOf("crystal_chance").forGetter(Config::crystalChance)
        ).apply(i, Config::new));
    }

    public LeyVeinPlantsFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> context) {
        return placeInChunk(context.level(), context.origin(), context.random(), context.config()) > 0;
    }

    /** {@code origin} を含むチャンクに生やす。生やした数を返す(GameTest からも呼ぶ)。 */
    public static int placeInChunk(WorldGenLevel level, BlockPos origin, RandomSource random, Config config) {
        long seed = level.getSeed();
        int minX = origin.getX() & ~15;
        int minZ = origin.getZ() & ~15;
        BlockState flower = ModBlocks.LEY_FLOWER.get().defaultBlockState();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int placed = 0;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = minX + dx;
                int z = minZ + dz;
                double strength = LeyVeinField.strengthAt(seed, x, z);
                if (strength <= 0.0D) {
                    continue;
                }
                float roll = random.nextFloat();
                boolean crystal = roll < config.crystalChance() * strength;
                if (!crystal && roll >= (config.crystalChance() + config.flowerChance()) * strength) {
                    continue;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                at.set(x, y, z);
                BlockState here = level.getBlockState(at);
                if (!here.canBeReplaced() || !here.getFluidState().isEmpty() || here.getBlock() instanceof DoublePlantBlock) {
                    continue;
                }
                BlockPos ground = at.below();
                BlockState below = level.getBlockState(ground);
                if (crystal) {
                    if (placeCrystal(level, ground.immutable(), at.immutable(), below, random)) {
                        placed++;
                    }
                } else if (flower.canSurvive(level, at)) {
                    level.setBlock(at, flower, Block.UPDATE_CLIENTS);
                    placed++;
                }
            }
        }
        return placed;
    }

    private static boolean placeCrystal(WorldGenLevel level, BlockPos ground, BlockPos above, BlockState below, RandomSource random) {
        if (!(below.is(BlockTags.DIRT) || below.is(BlockTags.SAND) || below.is(BlockTags.BASE_STONE_OVERWORLD)
                || below.is(Blocks.GRAVEL))) {
            return false;
        }
        level.setBlock(ground, ModBlocks.BUDDING_LEY_CRYSTAL.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        // 芽の段(仮決め): 小 4 割・中 3 割・大 2 割・塊 1 割。
        int r = random.nextInt(10);
        Block bud = r < 4 ? ModBlocks.SMALL_LEY_CRYSTAL_BUD.get() : r < 7 ? ModBlocks.MEDIUM_LEY_CRYSTAL_BUD.get()
                : r < 9 ? ModBlocks.LARGE_LEY_CRYSTAL_BUD.get() : ModBlocks.LEY_CRYSTAL.get();
        level.setBlock(above, bud.defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.UP), Block.UPDATE_CLIENTS);
        return true;
    }
}
