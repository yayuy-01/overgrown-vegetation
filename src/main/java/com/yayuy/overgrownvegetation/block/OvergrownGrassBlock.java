package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * 汚染された草ブロック。
 *
 * <p>バニラの草ブロックと同じように、ランダムティックのたびに周囲の
 * {@link ModBlocks#OVERGROWN_DIRT} を探して汚染された草ブロックに変えていく
 * (バニラの {@code GrassBlock} の伝染ロジックを踏襲)。
 *
 * <p>上がふさがる(上のブロックが光を通さない・上が水 1 マス)と {@link ModBlocks#OVERGROWN_DIRT} に戻る(2026-11-02 ユーザー指定。
 * バニラの {@code SpreadingSnowyDirtBlock} と同じ規則)。判定は頭上の実際の明るさではなく上のブロックの不透明度なので、
 * 林冠の下の暗い森の床は土にならない(暗さで土に戻る仕様は今も無い)。土から草への経路はどれも同じ判定 {@link #canStayGrass} を通るので往復しない。
 */
public class OvergrownGrassBlock extends Block {


    public OvergrownGrassBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!canStayGrass(level, pos)) {
            // 上がふさがった(11-02)。広がりの明るさの判定より前に置く(ふさがった所は暗いのでそこで帰ってしまう)
            level.setBlockAndUpdate(pos, ModBlocks.OVERGROWN_DIRT.get().defaultBlockState());
            return;
        }
        // 明るさを先に見る。深部変換で地下にも大量にあり、そこでは必ずここで帰るため、
        // 安い明るさの判定を先にして isAreaLoaded(チャンクを最大 4 つ引く)を省く。
        // どちらも読むだけで乱数も引かずに帰るので、順番を入れ替えても結果は同じ。
        if (level.getMaxLocalRawBrightness(pos.above()) < 9) {
            // 自分自身の頭上が暗すぎる場合は今回は広がらない
            return;
        }
        if (!level.isAreaLoaded(pos, 3)) {
            // 周囲が読み込まれていない状態でチャンク境界を跨いだ判定をしない(バニラ準拠)
            return;
        }

        BlockState grassState = this.defaultBlockState();
        Block dirt = ModBlocks.OVERGROWN_DIRT.get();

        for (int i = 0; i < 4; i++) {
            BlockPos targetPos = pos.offset(random.nextInt(3) - 1, random.nextInt(5) - 3, random.nextInt(3) - 1);
            if (level.getBlockState(targetPos).is(dirt) && canSpreadTo(level, targetPos)) {
                // 上に取り残された植物がこの更新で壊れても、アイテムは落とさない(CorruptionDropSuppressor)
                CorruptionDropSuppressor.run(() -> level.setBlockAndUpdate(targetPos, grassState));
            }
        }
    }

    /**
     * 対象の汚染された土が草化できるか。上がふさがっていない({@link #canStayGrass})・頭上が深い水中でなく、最低限の明るさがあること。
     */
    private static boolean canSpreadTo(ServerLevel level, BlockPos pos) {
        if (!canStayGrass(level, pos)) {
            return false;
        }
        BlockPos abovePos = pos.above();
        BlockState aboveState = level.getBlockState(abovePos);
        if (aboveState.getFluidState().getAmount() >= 8) {
            return false;
        }
        return level.getMaxLocalRawBrightness(abovePos) >= 4;
    }

    /**
     * そのマスで草ブロックでいられるか(バニラの {@code SpreadingSnowyDirtBlock#canBeGrass} と同じ)。1 段の雪の層なら可、
     * 上が水 1 マス(量 8)なら不可、それ以外は上のブロックが光を通すか(不透明度。実際の明るさは見ない)。
     * 草ブロック自身・土の草化・隣の土への広がりの 3 つで使う。
     */
    public static boolean canStayGrass(LevelReader level, BlockPos pos) {
        BlockPos abovePos = pos.above();
        BlockState above = level.getBlockState(abovePos);
        if (above.is(Blocks.SNOW) && above.getValue(SnowLayerBlock.LAYERS) == 1) {
            return true;
        }
        if (above.getFluidState().getAmount() == 8) {
            return false;
        }
        BlockState grass = ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState();
        int opacity = LightEngine.getLightBlockInto(level, grass, pos, above, abovePos, Direction.UP, above.getLightBlock(level, abovePos));
        return opacity < level.getMaxLightLevel();
    }
}
