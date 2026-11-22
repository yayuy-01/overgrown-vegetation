package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 薄く広がる苔。バニラのヒカリゴケ({@link net.minecraft.world.level.block.GlowLichenBlock})や
 * ツタと同じ「多面ブロック」({@link MultifaceBlock})で、上面・側面・下面が同じ1マスの中で
 * 同時に存在できる(積もった薄い雪と同じくらいの薄さで、汚染された地形の表面を覆う)。
 *
 * <p>ヒカリゴケの写経だが、名前のとおり「光らない」ので発光は持たない。
 * 収穫物のような特別なドロップも無く、壊すと自分自身が1つ返る(ルートテーブル参照)。
 * 骨粉で隣接する汚染ブロックの面へ広がる(ヒカリゴケと同じ {@link MultifaceSpreader})。
 *
 * <p>汚染時の自動生成は {@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader}
 * の露出面抽選(上面・側面・下面すべて)に組み込まれている。
 */
public class OvergrownMossLayerBlock extends MultifaceBlock implements BonemealableBlock {


    private final MultifaceSpreader spreader = new MultifaceSpreader(this);

    public OvergrownMossLayerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MultifaceSpreader getSpreader() {
        return this.spreader;
    }

    @Override
    public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state, boolean isClient) {
        return Direction.stream()
                .anyMatch(direction -> this.spreader.canSpreadInAnyDirection(state, level, pos, direction.getOpposite()));
    }

    @Override
    public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state) {
        return true;
    }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        this.spreader.spreadFromRandomFaceTowardRandomDirection(state, level, pos, random);
    }
}
