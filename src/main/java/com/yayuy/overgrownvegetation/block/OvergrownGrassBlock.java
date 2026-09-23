package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 汚染された草ブロック。
 *
 * <p>バニラの草ブロックと同じように、ランダムティックのたびに周囲の
 * {@link ModBlocks#OVERGROWN_DIRT} を探して汚染された草ブロックに変えていく
 * (バニラの {@code GrassBlock} の伝染ロジックを踏襲)。
 *
 * <p>バニラと違い、光量不足で自分自身が土に戻る仕様は実装していない。
 * 「広がる密林」というコンセプト上、一度茂った場所が後退するのは不自然なため。
 */
public class OvergrownGrassBlock extends Block {

    public static final MapCodec<OvergrownGrassBlock> CODEC = simpleCodec(OvergrownGrassBlock::new);

    public OvergrownGrassBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.isAreaLoaded(pos, 3)) {
            // 周囲が読み込まれていない状態でチャンク境界を跨いだ判定をしない(バニラ準拠)
            return;
        }
        if (level.getMaxLocalRawBrightness(pos.above()) < 9) {
            // 自分自身の頭上が暗すぎる場合は今回は広がらない
            return;
        }

        BlockState grassState = this.defaultBlockState();
        Block dirt = ModBlocks.OVERGROWN_DIRT.get();

        for (int i = 0; i < 4; i++) {
            BlockPos targetPos = pos.offset(random.nextInt(3) - 1, random.nextInt(5) - 3, random.nextInt(3) - 1);
            if (level.getBlockState(targetPos).is(dirt) && canSpreadTo(level, targetPos)) {
                level.setBlockAndUpdate(targetPos, grassState);
            }
        }
    }

    /**
     * 対象の汚染された土が草化できるか。頭上が深い水中でなく、最低限の明るさがあること。
     */
    private static boolean canSpreadTo(ServerLevel level, BlockPos pos) {
        BlockPos abovePos = pos.above();
        BlockState aboveState = level.getBlockState(abovePos);
        if (aboveState.getFluidState().getAmount() >= 8) {
            return false;
        }
        return level.getMaxLocalRawBrightness(abovePos) >= 4;
    }
}
