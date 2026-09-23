package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.corruption.LeyVeinPlacer;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.OrganTable;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * <b>器官を生やす</b>(2026-09-23)。
 *
 * <h2>いつ生えるか</h2>
 * <p>極相チャンクの<b>全層置換が終わった瞬間</b>({@code ClimaxDeepConverter})。
 * 地脈を敷いた直後に呼ばれる —— 器官は<b>地脈の上に立つ</b>ので、
 * 地脈より先に生やすと立つ場所が無い。
 *
 * <p>1 チャンクにつき<b>ちょうど 1 回</b>しか通らない(やり終えたチャンクは台帳に記録され
 * 二度と走らない)ので、「生えたかどうか」を別に覚えなくていい。
 *
 * <h2>置くのは節だけ</h2>
 * <p>ここがやるのは<b>節を 1 つ置くこと</b>だけ。体を組み上げるのは節の
 * {@code onPlace}({@link com.yayuy.overgrownvegetation.block.OrganNodeBlock})で、
 * 生成もコマンドもクリエイティブの手置きも<b>同じ道</b>を通る。
 * 経路ごとに組み立てを書くと「生成でだけ形が違う」が必ず出る。
 */
public final class OrganPlacer {

    /** 節を埋める深さ(地脈の表面から下へ)。体の根元の塊の中に収まる高さ。 */
    private static final int NODE_DEPTH = 1;

    private OrganPlacer() {
    }

    /**
     * そのチャンクが候補地なら器官を生やす。全層置換を終えたチャンクごとに 1 回だけ呼ばれる。
     *
     * @return 生やした節の位置。生えなければ {@code null}
     */
    @Nullable
    public static BlockPos maybeGrow(ServerLevel level, ChunkPos chunk) {
        OrganTable table = DesignData.organs();
        if (!table.enabled()) {
            return null;
        }
        OrganTable.SporeGland spec = table.sporeGland();
        if (!OrganField.isSite(level.getSeed(), chunk.x, chunk.z, spec.cellSizeChunks())) {
            return null;
        }
        return plant(level, chunk, OrganKind.SPORE_GLAND);
    }

    /**
     * 候補地かどうかを見ずに生やす(コマンド用)。
     *
     * @return 生やした節の位置。地脈が見つからなければ {@code null}
     */
    @Nullable
    public static BlockPos plant(ServerLevel level, ChunkPos chunk, OrganKind kind) {
        if (!level.hasChunk(chunk.x, chunk.z)) {
            return null;
        }
        BlockPos anchor = findVeinGround(level, chunk);
        if (anchor == null) {
            return null;
        }
        // 節を置くと onPlace が台帳へ載せ、その場で体を組み上げる。
        level.setBlock(anchor, ModBlocks.ORGAN_NODE.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        return anchor;
    }

    /**
     * そのチャンクの中で、<b>地脈が通っていて地面がある</b>列を 1 つ探す。
     *
     * <p>地脈の筋そのものの上に立たせる —— 器官が地脈に沿って並ぶのが狙いなので、
     * 「チャンクのどこか」ではなく「地脈のどこか」でなければ意味がない。
     * 節は地面から {@link #NODE_DEPTH} だけ潜らせて、体の根元の塊に埋まるようにする。
     *
     * @return 節を置く位置。見つからなければ {@code null}
     */
    @Nullable
    private static BlockPos findVeinGround(ServerLevel level, ChunkPos chunk) {
        for (int dx = 2; dx < 14; dx++) {
            for (int dz = 2; dz < 14; dz++) {
                int x = chunk.getMinBlockX() + dx;
                int z = chunk.getMinBlockZ() + dz;
                if (!LeyVeinField.isVeinAt(level.getSeed(), x, z)) {
                    continue;
                }
                int y = LeyVeinPlacer.groundHeight(level, x, z);
                if (y == Integer.MIN_VALUE) {
                    continue;
                }
                return new BlockPos(x, y - NODE_DEPTH, z);
            }
        }
        return null;
    }

    /**
     * 節から体を組み上げる({@code OrganNodeBlock#onPlace} から)。
     *
     * <p><b>置き換えてよいものだけ</b>を置き換える —— 空気・水・植物・地面(汚染済み)は潰すが、
     * プレイヤーの建てたものや、まだ汚染されていないブロックには手を出さない。
     * 器官が拠点の壁を食い破って生えるのは、遠くで静かに進んでほしいこの仕掛けの趣旨に合わない。
     */
    public static void grow(ServerLevel level, BlockPos node, OrganKind kind) {
        List<OrganCell> cells = OrganShape.build(kind, level.getSeed(), node.getX(), node.getY(), node.getZ());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (OrganCell cell : cells) {
            cursor.set(node.getX() + cell.x(), node.getY() + cell.y(), node.getZ() + cell.z());
            if (cursor.equals(node)) {
                continue; // 節そのもの。上書きしない。
            }
            if (!level.isInWorldBounds(cursor) || !canReplace(level.getBlockState(cursor))) {
                continue;
            }
            level.setBlock(cursor, blockFor(cell.part()), Block.UPDATE_CLIENTS);
        }
    }

    private static BlockState blockFor(OrganCell.Part part) {
        return switch (part) {
            case VINE -> ModBlocks.ORGAN_VINE.get().defaultBlockState();
            case GLAND -> ModBlocks.SPORE_GLAND.get().defaultBlockState();
            case OVERGROWN -> ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState();
        };
    }

    /** 器官が押しのけてよいか。汚染済みのものと、壊れやすいものだけ。 */
    private static boolean canReplace(BlockState state) {
        return state.isAir()
                || state.is(ModTags.CORRUPTED)
                || state.canBeReplaced();
    }
}
