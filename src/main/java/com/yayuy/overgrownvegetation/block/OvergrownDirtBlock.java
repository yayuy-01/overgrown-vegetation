package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 汚染された土。
 *
 * <p>バニラの土と違い、隣に汚染された草ブロックが無くても、ランダムティックのたびに
 * 一定確率({@link OvergrownVegetationConfig.Common#dirtToGrassChance})で自分自身が
 * {@link ModBlocks#OVERGROWN_GRASS_BLOCK}(過繁茂した草ブロック)へ変わる。
 * 「胞子が常に降り注いでいるので、露出した土はいずれ必ず草に覆われる」というコンセプト。
 *
 * <p>草化できるのは、真上が深い水中でなく、最低限の明るさがある場合だけ
 * ({@link OvergrownGrassBlock} の伝染条件と同じ判定)。地中に埋もれた土や
 * 真っ暗な場所の土は草化しない。
 *
 * <p>隣接する {@link ModBlocks#OVERGROWN_GRASS_BLOCK} からの伝染で草になる経路
 * ({@link OvergrownGrassBlock#randomTick})は従来どおり残っており、こちらはそれに
 * 加えて「伝染元が周囲に一切いなくても、時間経過で草化する」ようにするもの。
 * 砂漠のように元々草ブロックが存在しないバイオームで汚染が広がったあとでも、
 * 最終的に草地になる。
 */
public class OvergrownDirtBlock extends Block {

    public static final MapCodec<OvergrownDirtBlock> CODEC = simpleCodec(OvergrownDirtBlock::new);

    public OvergrownDirtBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.isAreaLoaded(pos, 1)) {
            return;
        }
        double chance = OvergrownVegetationConfig.COMMON.dirtToGrassChance.get();
        if (chance <= 0.0D || random.nextDouble() >= chance) {
            return;
        }
        if (!canBecomeGrass(level, pos)) {
            return;
        }
        // 上に取り残された植物がこの更新で壊れても、アイテムは落とさない(CorruptionDropSuppressor)
        CorruptionDropSuppressor.run(() -> level.setBlockAndUpdate(pos, ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState()));
    }

    /**
     * 直接草ブロックへ変わってよいか。{@link OvergrownGrassBlock} の伝染条件
     * (上がふさがっていない・頭上が深い水中でない・最低限の明るさがある)と揃えてある。
     */
    private static boolean canBecomeGrass(ServerLevel level, BlockPos pos) {
        if (!OvergrownGrassBlock.canStayGrass(level, pos)) {
            return false; // 上がふさがっている(草ブロックが土に戻る規則と同じ判定。11-02)
        }
        BlockPos abovePos = pos.above();
        BlockState aboveState = level.getBlockState(abovePos);
        if (aboveState.getFluidState().getAmount() >= 8) {
            return false;
        }
        return level.getMaxLocalRawBrightness(abovePos) >= 4;
    }
}
