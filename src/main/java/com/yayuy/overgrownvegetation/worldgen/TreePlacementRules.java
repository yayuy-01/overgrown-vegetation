package com.yayuy.overgrownvegetation.worldgen;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.corruption.CaveGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 木の生成可否の共通ルール。{@link ForcedTreeFeature} と {@link GiantTreeFeature} から参照する。
 */
public final class TreePlacementRules {

    /** この幹の高さ<b>まで</b>の木なら洞窟内でも生えてよい。これを超える木は空が見える場所限定。 */
    public static final int CAVE_MAX_TREE_HEIGHT = 6;

    /**
     * 木(幹・根・葉)が置き換えないブロック(2026-10-23 胞子の場 A2: 根付いた萌芽体が周りに生えた木の根に消されていた)。
     * 木は「何があっても置き換える」作りなので、ここに挙げたものだけ避ける。
     */
    public static boolean treeKeeps(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(com.yayuy.overgrownvegetation.registry.ModBlocks.BLIGHT_FERN_ROOTED.get());
    }

    /** {@link #isEnclosed} で「上に地形がある」とみなす、地表からの最小の潜り込み量。 */
    private static final int ENCLOSED_MARGIN = 5;

    /**
     * 天井を探して地表から下へ潜る最大の段数。巨大樹(高さ 120〜240)の樹冠を突き抜けて地面まで
     * 届く必要があるので、汚染の装飾({@link CaveGrowth} の既定)よりずっと大きく取る。木が生える
     * のは稀なので、1 本につきこの段数ぶんブロックを読んでも問題にならない。
     */
    private static final int ROOF_SCAN_LIMIT = 320;

    /**
     * {@code origin}(苗木の位置)が洞窟内・厚い天井の下にあるか。その列の<b>本物の天井</b>
     * (木ではない地形・建築)より {@value #ENCLOSED_MARGIN} ブロック以上低ければ、上に地形が
     * 乗っている = 洞窟内とみなす({@link CaveGrowth#isCave} と同じ判定を共有している)。
     *
     * <p>列の地表({@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES})は<b>木を数えてしまう</b>ので、
     * それだけで判定してはいけない。過繁茂した葉({@code overgrown_leaves})はバニラの
     * {@code #minecraft:leaves} に入っていないため「葉を無視した地表」にも数えられ、過繁茂した原木・
     * 巨大樹の幹や枝も当然数えられる。つまり地表高をそのまま使うと、<b>地上でも樹冠や枝の下がすべて
     * 洞窟扱い</b>になり、大きな木・巨大樹がその場所に生えられなくなる(2026-09-23 の修正)。
     * そこで地表から下へ {@link ModTags#NOT_CAVE_ROOF}(木・巨大樹・菌の柱など)と空気を読み飛ばし、
     * 最初に見つかった「木以外の固体ブロック」を天井とみなす。
     *
     * <p>空に開けた谷・峡谷やオーバーハングの縁はその列の天井が {@code origin} に近いので、
     * これも洞窟とはみなさない。
     */
    public static boolean isEnclosed(WorldGenLevel level, BlockPos origin) {
        return CaveGrowth.isCave(level, origin, ENCLOSED_MARGIN, ROOF_SCAN_LIMIT);
    }

    private TreePlacementRules() {
    }
}
