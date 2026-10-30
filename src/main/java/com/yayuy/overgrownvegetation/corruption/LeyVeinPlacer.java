package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 極相になったチャンクの地表へ<b>地脈</b>({@link ModBlocks#LEY_VEIN})を敷く(2026-09-22)。
 *
 * <p>どこに敷くかは {@link LeyVeinField} が決める —— シードと座標だけの決定的な関数なので、
 * チャンクごとに独立して敷いても<b>境目で線がずれない</b>。何度呼んでも同じ結果になる(冪等)。
 *
 * <h2>何を置き換えるか</h2>
 * <p><b>{@link ModTags#LEY_VEIN_REPLACEABLE} のブロックだけ</b> —— 過繫茂した土・草ブロック・石など、
 * <b>地表の地面</b>に限る。
 *
 * <p>ここを「汚染済みなら何でも」にすると<b>地脈が木のてっぺんに生える</b>(一度そうなった)。
 * 高さの起点に使う {@link Heightmap.Types#OCEAN_FLOOR} は「動きを止める最上段」なので、
 * <b>木の幹も葉も地表として拾ってしまう</b>ため。だから起点から下へ
 * {@link #MAX_DESCENT} マスぶん降りて、<b>最初に見つかった地面</b>に敷く。
 * 木の下なら地脈は幹の根元の地面に走る —— 網目は途切れない。
 *
 * <p>タグに鉱石を入れないこと(潰すと資源が消える)。プレイヤーの建材はそもそも汚染タグに入らないので
 * 対象外。
 */
public final class LeyVeinPlacer {

    /**
     * 地表の起点から下へ地面を探す最大段数。木の高さ(巨大樹でも 30 前後)を越えられるだけあればよい。
     * ここを無制限にすると、洞窟の上でひたすら降りて地下深くに敷いてしまう。
     */
    private static final int MAX_DESCENT = 48;

    private LeyVeinPlacer() {
    }

    /**
     * チャンク 1 つぶんの地脈を敷く。<b>読み込まれているチャンクにだけ呼ぶこと。</b>
     *
     * @return 置いたブロック数
     */
    public static int place(ServerLevel level, ChunkPos chunkPos) {
        ClimaxTable.LeyVein spec = DesignData.climax().leyVein();
        if (spec.width() <= 0.0D) {
            return 0;
        }
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        BlockState vein = ModBlocks.LEY_VEIN.get().defaultBlockState();
        long seed = level.getSeed();
        int placed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = chunkPos.getMinBlockX() + dx;
                int z = chunkPos.getMinBlockZ() + dz;
                if (!LeyVeinField.isVeinAt(seed, x, z)) {
                    continue;
                }
                int top = findGround(level, chunk, cursor, x, z);
                if (top == Integer.MIN_VALUE) {
                    continue; // 木の下にも水の底にも地面が無い(あり得るのは空中くらい)。
                }
                for (int i = 0; i < spec.depth(); i++) {
                    cursor.set(x, top - i, z);
                    if (cursor.getY() <= level.getMinBuildHeight()) {
                        break;
                    }
                    if (!level.getBlockState(cursor).is(ModTags.LEY_VEIN_REPLACEABLE)) {
                        continue;
                    }
                    // 近隣更新を飛ばして置く。地脈は見た目だけのブロックなので、
                    // 1 マスごとに更新を飛ばすと広い範囲を敷くときに跳ねる。
                    level.setBlock(cursor, vein, Block.UPDATE_CLIENTS);
                    placed++;
                }
            }
        }
        return placed;
    }

    /**
     * その列の<b>地面の高さ</b>({@link #findGround} の、チャンクを自分で引く版)。
     * 1 列だけ知りたい側から呼ぶ。
     *
     * @return 見つからなければ {@link Integer#MIN_VALUE}
     */
    public static int groundHeight(ServerLevel level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return Integer.MIN_VALUE;
        }
        return findGround(level, level.getChunk(x >> 4, z >> 4), new BlockPos.MutableBlockPos(), x, z);
    }

    /**
     * その列の<b>地面の高さ</b>を探す。{@link Heightmap.Types#OCEAN_FLOOR}(動きを止める最上段 ——
     * <b>木の幹や葉も含む</b>)から下へ降りて、最初に見つかった
     * {@link ModTags#LEY_VEIN_REPLACEABLE} の高さを返す。
     *
     * @return 見つからなければ {@link Integer#MIN_VALUE}
     */
    private static int findGround(ServerLevel level, LevelChunk chunk, BlockPos.MutableBlockPos cursor,
                                  int x, int z) {
        int top = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15);
        int floor = Math.max(level.getMinBuildHeight() + 1, top - MAX_DESCENT);
        for (int y = top; y >= floor; y--) {
            cursor.set(x, y, z);
            if (level.getBlockState(cursor).is(ModTags.LEY_VEIN_REPLACEABLE)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }
}
