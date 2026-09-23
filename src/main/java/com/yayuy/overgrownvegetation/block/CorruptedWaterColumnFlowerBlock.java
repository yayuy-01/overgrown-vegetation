package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 藻柱({@link CorruptedWaterColumnBlock})の先端に咲く花。
 *
 * <p>{@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader} が藻柱を積んでいく途中、
 * {@code waterColumnFlowerChance} の確率でその段を柱ではなくこの花にする。花が咲いた時点で
 * その藻柱の成長は止まる(花の上には何も積まない)ので、「花が咲いた藻柱 = 伸びきった藻柱」になる。
 *
 * <p>形・当たり判定・水の扱いは藻柱と同じで、生存条件だけが違う: 真下が<b>藻柱</b>(または上面の
 * 塞がったブロック)であれば生存する。藻柱が崩れれば花も水へ戻る。
 */
public class CorruptedWaterColumnFlowerBlock extends CorruptedWaterColumnBlock {

    public static final MapCodec<CorruptedWaterColumnFlowerBlock> CODEC =
            simpleCodec(CorruptedWaterColumnFlowerBlock::new);

    public CorruptedWaterColumnFlowerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends net.minecraft.world.level.block.Block> codec() {
        return CODEC;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (!level.getFluidState(pos).is(FluidTags.WATER)) {
            return false;
        }
        BlockPos belowPos = pos.below();
        BlockState below = level.getBlockState(belowPos);
        return below.is(ModBlocks.OVERGROWN_WATER_COLUMN.get())
                || below.isFaceSturdy(level, belowPos, Direction.UP);
    }
}
