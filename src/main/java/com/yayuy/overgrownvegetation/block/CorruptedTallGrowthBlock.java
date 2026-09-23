package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 汚染ブロックの上面から生える、高さ 2 ブロック分の植物。
 *
 * <p>バニラの背高草・大型シダと同じ基底クラス {@code DoublePlantBlock} をそのまま使う。
 * 下半分・上半分の同時設置や、どちらを壊しても両方消えて 1 個ドロップする挙動は
 * すべてバニラの実装に任せており、ここでは {@link CorruptedGroundGrowthBlock} と同様に
 * 「土に限らず上面が塞がっていれば生える」ように生存条件だけを緩和している。
 */
public class CorruptedTallGrowthBlock extends DoublePlantBlock {

    public static final MapCodec<CorruptedTallGrowthBlock> CODEC = simpleCodec(CorruptedTallGrowthBlock::new);

    public CorruptedTallGrowthBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<CorruptedTallGrowthBlock> codec() {
        return CODEC;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isFaceSturdy(level, pos, Direction.UP);
    }
}
