package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/**
 * (x, z) 列の {@code MOTION_BLOCKING_NO_LEAVES} ハイトマップ上の地表が「木の天蓋・幹の途中」
 * だった場合に、本物の地面(木の一部でない motion-blocking ブロック)まで掘り下げた位置を返す。
 *
 * <p>過繁茂した葉({@code overgrown_leaves})や巨大樹の幹({@code giant_overgrown_log} 等)は
 * バニラの {@code minecraft:leaves}/{@code minecraft:logs} タグに入っていないため、
 * {@code MOTION_BLOCKING_NO_LEAVES} ハイトマップにそのまま「地表」として拾われてしまう
 * (バニラの葉はこのハイトマップの対象から除外されるが、独自ブロックはタグを持たないので
 * 除外されない)。これを補正せずに地表 Y をそのまま Mob の湧き位置に使うと、木の天蓋や幹の
 * てっぺんに Mob が湧いてしまう。
 *
 * <p>もともと {@link OvergrownBiomeSpawner} だけがこの掘り下げ処理を持っていたが、
 * {@link com.yayuy.overgrownvegetation.raid.RaidWaveSpawner}(レイド中のウェーブ湧き。
 * 苗床が巨大樹の近くに立つことが多く、最も頻繁に発生源になっていた)には無く、同じ原因で
 * 木の上に湧くことがあったため、掘り下げロジックを共通化してどちらからも使えるようにした。
 * ({@link com.yayuy.overgrownvegetation.quake.EarthquakeData} の震央 Y 確定にも使う。)
 */
public final class TreeCanopyGroundFinder {

    /** これ以上掘っても本物の地面に届かない場合は諦める(あり得ない高さの木は無い前提)。 */
    private static final int MAX_DIG = 48;

    /**
     * {@code (x, z)} 列で、木の天蓋・幹を掘り抜いた先にある本物の地表 — Mob が実際に立つ位置
     * (地面ブロックの1つ上、空気であるべき座標)を返す。対象チャンクが未ロード、または
     * {@link #MAX_DIG} ブロック掘っても木の一部でない床に届かない場合は {@code null}。
     *
     * <p>掘り下げの経路は「木の一部」か「動きを止めないブロック」(葉の隙間の空気・蔦など)しか
     * 通らないので、木の構造を素通りして本来の地形を突き抜けて地下に湧くことはない。
     */
    @Nullable
    public static BlockPos findSpawnSurface(ServerLevel level, int x, int z) {
        if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z))) {
            return null;
        }

        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, surfaceY, z);
        int minY = level.getMinBuildHeight() + 1;

        for (int drop = 0; drop < MAX_DIG; drop++) {
            BlockState below = level.getBlockState(cursor.below());
            if (below.blocksMotion() && !isTreePart(below)) {
                return cursor.immutable();
            }
            cursor.move(Direction.DOWN);
            if (cursor.getY() <= minY) {
                return null;
            }
        }
        return null;
    }

    /** {@code state} が木の一部(幹・葉、バニラ/過繁茂どちらも)かどうか。 */
    public static boolean isTreePart(BlockState state) {
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
            return true;
        }
        Block block = state.getBlock();
        return block == ModBlocks.OVERGROWN_LOG.get()
                || block == ModBlocks.GIANT_OVERGROWN_LOG.get()
                || block == ModBlocks.GIANT_OVERGROWN_CAMBIUM.get()
                || block == ModBlocks.OVERGROWN_LEAVES.get()
                || block == ModBlocks.OVERGROWN_SWAMP_LEAVES.get()
                || block == ModBlocks.OVERGROWN_SWAMP_LOG.get()
                || block == ModBlocks.OVERGROWN_SWAMP_WOOD.get()
                || block == ModBlocks.FUNGAL_PILLAR.get();
    }

    private TreeCanopyGroundFinder() {
    }
}
