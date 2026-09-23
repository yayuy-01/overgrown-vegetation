package com.yayuy.overgrownvegetation.worldgen;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 「大きな未発達な果実」({@link ModBlocks#LARGE_UNDEVELOPED_FRUIT})を<b>葉の真下</b>に
 * 低確率でぶら下げる処理の一本化。
 *
 * <p>呼ぶのは 2 箇所:
 * <ul>
 *     <li>{@link ForcedTreeFeature} — 過繫茂した木。幹・枝・葉をすべて置き終えたあとに、
 *         その木が置いた葉だけをたどって抽選する({@code undevelopedFruitChance})。</li>
 *     <li>{@link com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue} — 巨大樹。
 *         葉の露出面に装飾植物を生やすフェーズのついでに抽選する
 *         ({@code giantTreeUndevelopedFruitChance})。</li>
 * </ul>
 *
 * <h2>確率は葉ブロック 1 個あたり</h2>
 * 巨大樹の葉は数万個、普通の木は数百個と桁が 2 つ違うので、<b>木の種類ごとに別の確率</b>を
 * 持たせてある。実際にぶら下がれるのは「真下が空気」の葉、つまり樹冠の下面だけなので、
 * 葉の総数より実際の抽選対象はずっと少ない。
 */
public final class UndevelopedFruitPlacement {

    /**
     * {@code leafPos} の葉の真下へ、確率 {@code chance} で実をぶら下げる。
     * 真下が読み込まれていない / 空気でない場合は何もしない。
     *
     * @return 実際に置いたか
     */
    public static boolean tryHang(LevelAccessor level, RandomSource random, BlockPos leafPos, double chance) {
        if (chance <= 0.0D || random.nextDouble() >= chance) {
            return false;
        }
        BlockPos pos = leafPos.below();
        if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir()) {
            return false;
        }
        BlockState fruit = ModBlocks.LARGE_UNDEVELOPED_FRUIT.get().defaultBlockState();
        if (!fruit.canSurvive(level, pos)) {
            return false;
        }
        level.setBlock(pos, fruit, Block.UPDATE_ALL);
        return true;
    }

    private UndevelopedFruitPlacement() {
    }
}
