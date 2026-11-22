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
        if (!com.yayuy.overgrownvegetation.util.LoadedChunks.loaded(level, x >> 4, z >> 4)) { // 読み込み途中のチャンクを読んで止まらないよう、hasChunk ではなく読み込み済みかで見る(2026-10-10、0.1.9 の前)
            return null;
        }
        if (com.yayuy.overgrownvegetation.corruption.NetherSurface.applies(level)) {
            // 天井のある次元(ネザー)では高さマップが天井の岩盤を返し、岩盤の上に湧いてしまう(2026-10-02)。
            // 床が何段もあるので、いちばん近いプレイヤーの高さに近い床を選ぶ。
            return com.yayuy.overgrownvegetation.corruption.NetherSurface.standNear(level, x, z,
                    com.yayuy.overgrownvegetation.corruption.NetherSurface.referenceY(level, x, z));
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

    /**
     * {@link #findSpawnSurface} の、水たまり・池を避ける形(2026-09-25)。(x, z) の地面が水の中(立つマスに水がある。水は動きを止めないので
     * {@link #findSpawnSurface} は水の底まで掘り下げる)なら、{@code (fromX, fromZ)} の方へ 1 ブロックずつ戻って、水の無い地面を
     * {@code maxSteps} 列まで探す。見つからなければ元の結果(水の底)。
     *
     * <p>ユーザー報告 2026-09-25「湿地で水の中を突っ切ろうとして水中に入るも、陸地に上がれずスタック」: 群れの区間の終点・護衛の行き先が
     * 水たまりの底を狙っていて、湿地では何度も水へ入り直していた。
     */
    @Nullable
    public static BlockPos findDrySurfaceToward(ServerLevel level, int x, int z, double fromX, double fromZ, int maxSteps) {
        BlockPos first = findSpawnSurface(level, x, z);
        if (first == null || level.getFluidState(first).isEmpty()) {
            return first;
        }
        double dx = fromX - (x + 0.5D);
        double dz = fromZ - (z + 0.5D);
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) {
            return first;
        }
        double ux = dx / len;
        double uz = dz / len;
        int steps = Math.min(maxSteps, (int) Math.floor(len));
        for (int i = 1; i <= steps; i++) {
            BlockPos p = findSpawnSurface(level, (int) Math.floor(x + 0.5D + ux * i), (int) Math.floor(z + 0.5D + uz * i));
            if (p != null && level.getFluidState(p).isEmpty()) {
                return p;
            }
        }
        return first;
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
                || block == ModBlocks.ROOT_ARCH_LOG.get() // 汚染の根(11-16。前は巨大樹の原木で作っていた)
                || block instanceof com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock // 封印木の葉も(原木は #logs で拾う)
                || block instanceof com.yayuy.overgrownvegetation.block.TreeFernTrunkBlock; // 木生シダの幹(#logs でない)
    }

    private TreeCanopyGroundFinder() {
    }
}
