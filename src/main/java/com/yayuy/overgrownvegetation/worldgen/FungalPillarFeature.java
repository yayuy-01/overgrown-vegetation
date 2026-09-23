package com.yayuy.overgrownvegetation.worldgen;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * 菌湿地帯の「菌類の柱」。根元が太く、上へ行くほど細くなる先細りの円柱を積み上げるだけの Feature
 * (葉は無い。プロトタキシテスのような巨大菌類のイメージ)。
 *
 * <p>{@link ForcedTreeFeature} と同じく条件判定を一切せず、必要な位置を強制的に置き換える。
 * 苗木({@link com.yayuy.overgrownvegetation.block.CorruptedSaplingBlock})がその位置の環境が
 * 菌湿地帯のときに {@code overgrown_tree_fungal_swamp}(random_selector)経由で選ぶ。
 *
 * <h2>形</h2>
 * <ul>
 *     <li>高さ {@code min_height}〜{@code max_height}。</li>
 *     <li>半径は根元 {@code base_radius} から先端 {@code top_radius} へ、高さの 0.8 乗で滑らかに細くなる
 *     (半径 0 = 1 マス、1 = 十字形の 3x3、1.5 以上 = 角の取れた円)。</li>
 *     <li>一番下の {@code flare_rows} 段は半径 +{@code flare} で裾が広がる。</li>
 *     <li>{@code lean} > 0 なら数段ごとに中心が 1 マスずれ、最大 {@code lean} マスまで傾く。</li>
 * </ul>
 */
public class FungalPillarFeature extends Feature<FungalPillarFeature.Config> {

    public FungalPillarFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        Config config = context.config();
        BlockPos origin = context.origin();

        int minHeight = Math.min(config.minHeight(), config.maxHeight());
        int maxHeight = Math.max(config.minHeight(), config.maxHeight());
        int height = minHeight >= maxHeight ? minHeight : minHeight + random.nextInt(maxHeight - minHeight + 1);
        height = Math.max(1, height);

        if (height > TreePlacementRules.CAVE_MAX_TREE_HEIGHT && TreePlacementRules.isEnclosed(level, origin)) {
            return false;
        }

        double baseRadius = Math.max(0.0D, config.baseRadius());
        double topRadius = Math.max(0.0D, Math.min(baseRadius, config.topRadius()));
        int flareRows = Math.max(0, config.flareRows());
        double flare = Math.max(0.0D, config.flare());
        int maxLean = Math.max(0, config.lean());

        int cx = origin.getX();
        int cz = origin.getZ();
        int leanX = 0;
        int leanZ = 0;
        int leanEvery = Math.max(3, height / Math.max(1, maxLean + 1));

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < height; i++) {
            double t = height <= 1 ? 1.0D : (double) i / (double) (height - 1);
            double radius = baseRadius + (topRadius - baseRadius) * Math.pow(t, 0.8D);
            if (i < flareRows) {
                radius += flare * (1.0D - (double) i / Math.max(1, flareRows));
            }
            if (maxLean > 0 && i > 0 && i % leanEvery == 0 && Math.abs(leanX) + Math.abs(leanZ) < maxLean) {
                if (random.nextBoolean()) {
                    leanX += random.nextBoolean() ? 1 : -1;
                } else {
                    leanZ += random.nextBoolean() ? 1 : -1;
                }
            }
            int y = origin.getY() + i;
            int ir = Mth.ceil(radius);
            double edgeSq = (radius + 0.5D) * (radius + 0.5D);
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    if (dx * dx + dz * dz > edgeSq) {
                        continue;
                    }
                    cursor.set(cx + leanX + dx, y, cz + leanZ + dz);
                    level.setBlock(cursor, config.pillarState(), Block.UPDATE_ALL);
                    if (i == 0) {
                        anchorToGround(level, cursor.immutable(), config);
                    }
                }
            }
        }
        return true;
    }

    /**
     * 裾(一番下の円盤)の各列を、下に地面が来るまで柱ブロックで埋める。苗木の位置より周りの地面が低い
     * (斜面・段差・水辺)と裾が宙に浮くため。{@code root_depth} マスまで下を探し、その範囲に固体が無ければ
     * そこまでで止める(深い谷の上ではその長さの根として垂れる)。
     */
    private static void anchorToGround(WorldGenLevel level, BlockPos base, Config config) {
        int maxDepth = Math.max(0, config.rootDepth());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int d = 1; d <= maxDepth; d++) {
            cursor.set(base.getX(), base.getY() - d, base.getZ());
            BlockState state = level.getBlockState(cursor);
            if (state.blocksMotion() && !state.canBeReplaced() && !state.is(BlockTags.LEAVES)
                    && !state.is(ModBlocks.OVERGROWN_LEAVES.get()) && !state.is(ModBlocks.OVERGROWN_SWAMP_LEAVES.get())) {
                return; // 地面に着いた(葉は地面と見なさず貫く)。
            }
            level.setBlock(cursor, config.pillarState(), Block.UPDATE_ALL);
        }
    }

    /**
     * @param pillarState 柱に使うブロック状態(例: {@code fungal_pillar[axis=y]})
     * @param minHeight   高さの最小値
     * @param maxHeight   高さの最大値(この範囲でランダム)
     * @param baseRadius  根元の半径(0 = 1 マス、1 = 十字 3x3、2 = 5x5 の円)
     * @param topRadius   先端の半径(根元以下に丸められる)
     * @param flareRows   裾を広げる段数
     * @param flare       裾の追加半径
     * @param lean        傾きの最大量(マス)。0 でまっすぐ
     * @param rootDepth   裾の各列を地面に着くまで下へ伸ばす最大深さ(マス)。0 で伸ばさない
     */
    public record Config(
            BlockState pillarState,
            int minHeight,
            int maxHeight,
            double baseRadius,
            double topRadius,
            int flareRows,
            double flare,
            int lean,
            int rootDepth
    ) implements FeatureConfiguration {

        public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        BlockState.CODEC.fieldOf("pillar_state").forGetter(Config::pillarState),
                        Codec.INT.fieldOf("min_height").forGetter(Config::minHeight),
                        Codec.INT.fieldOf("max_height").forGetter(Config::maxHeight),
                        Codec.DOUBLE.optionalFieldOf("base_radius", 1.0D).forGetter(Config::baseRadius),
                        Codec.DOUBLE.optionalFieldOf("top_radius", 0.0D).forGetter(Config::topRadius),
                        Codec.INT.optionalFieldOf("flare_rows", 2).forGetter(Config::flareRows),
                        Codec.DOUBLE.optionalFieldOf("flare", 1.0D).forGetter(Config::flare),
                        Codec.INT.optionalFieldOf("lean", 1).forGetter(Config::lean),
                        Codec.INT.optionalFieldOf("root_depth", 8).forGetter(Config::rootDepth))
                .apply(instance, Config::new));
    }
}
