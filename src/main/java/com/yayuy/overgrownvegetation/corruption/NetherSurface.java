package com.yayuy.overgrownvegetation.corruption;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import javax.annotation.Nullable;

/**
 * <b>天井のある次元(ネザー)の「地表」</b>(2026-10-02)。
 *
 * <p>オーバーワールドの侵食は地表を高さマップで探すが、ネザーでは高さマップがどれも天井の岩盤を返すので、
 * 何も見つからない。ここでは列を上から下へ読み、<b>床</b>(動きを止めるブロックで、真上が空いている = 動きを止めず液体も無い)を
 * すべて拾う。ネザーの列には床が何段もある(洞窟の床・崖の棚・溶岩の海の岸)ので、1 つの Y ではなく床の一覧を返す。
 *
 * <p>読むのは天井の岩盤より下({@link #topY})から床の岩盤より上({@link #bottomY})まで。空気だけのセクションは
 * 16 段まとめて飛ばす。
 */
public final class NetherSurface {

    /** バイオームを見る高さ(書き換える前のネザーのバイオームの種類を決める)。書き換えた後は全高が同じ。 */
    public static final int BIOME_SAMPLE_Y = 64;
    /** 1 列から拾う床の上限。 */
    public static final int MAX_FLOORS = 8;

    /** この次元は天井があって、地表を {@link NetherSurface} で探すべきか。 */
    public static boolean applies(Level level) {
        return level.dimensionType().hasCeiling();
    }

    /** 床を探す上端(天井の岩盤の層より下)。 */
    public static int topY(Level level) {
        return Math.min(level.getMaxBuildHeight(), level.getMinBuildHeight() + level.dimensionType().logicalHeight()) - 6;
    }

    /** 床を探す下端(床の岩盤の層より上)。 */
    public static int bottomY(Level level) {
        return level.getMinBuildHeight() + 5;
    }

    /** 床の上として空いているか(動きを止めず、液体も無い)。草や蔦は空いている扱い。 */
    public static boolean isOpen(BlockState state) {
        return !state.blocksMotion() && state.getFluidState().isEmpty();
    }

    /**
     * 列 {@code (x, z)}(ワールド座標)の床の Y を上から順に {@code out} へ入れ、その数を返す({@code out.length} まで)。
     * チャンクは呼ぶ側が渡す(読み込まれていること)。
     */
    public static int floors(LevelChunk chunk, int x, int z, int top, int bottom, int[] out) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, top, z);
        LevelChunkSection[] sections = chunk.getSections();
        int count = 0;
        boolean aboveOpen = isOpen(chunk.getBlockState(cursor.setY(top + 1)));
        int y = top;
        while (y >= bottom && count < out.length) {
            int index = chunk.getSectionIndex(y);
            if (index >= 0 && index < sections.length && sections[index].hasOnlyAir()) {
                // 空気だけのセクション: 床は無い。その下端まで飛ばす(真上は空いている)。
                int sectionBottom = chunk.getMinBuildHeight() + index * 16;
                y = Math.min(y, sectionBottom) - 1;
                aboveOpen = true;
                continue;
            }
            BlockState state = chunk.getBlockState(cursor.setY(y));
            if (aboveOpen && state.blocksMotion()) {
                out[count++] = y;
            }
            aboveOpen = isOpen(state);
            y--;
        }
        return count;
    }

    /** {@link #floors(LevelChunk, int, int, int, int, int[])} の、高さを次元から決める版。 */
    public static int floors(ServerLevel level, LevelChunk chunk, int x, int z, int[] out) {
        return floors(chunk, x, z, topY(level), bottomY(level), out);
    }

    /**
     * 列 {@code (x, z)} で、{@code refY} にいちばん近い<b>立てる</b>床(上に 2 マス空きがある)の、立つ位置(床の 1 つ上)。
     * 無ければ null。湧かせる場所探し用。
     */
    @Nullable
    public static BlockPos standNear(ServerLevel level, int x, int z, int refY) {
        if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z))) {
            return null;
        }
        LevelChunk chunk = level.getChunkAt(new BlockPos(x, 0, z));
        int[] floors = new int[MAX_FLOORS * 2];
        int n = floors(level, chunk, x, z, floors);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int stand = floors[i] + 1;
            if (!isOpen(chunk.getBlockState(cursor.set(x, stand + 1, z)))) {
                continue; // 頭がつかえる
            }
            int d = Math.abs(stand - refY);
            if (d < bestDist) {
                bestDist = d;
                best = new BlockPos(x, stand, z);
            }
        }
        return best;
    }

    /** {@code (x, z)} にいちばん近いプレイヤーの足の高さ(水平 128 マス以内)。いなければ {@link #BIOME_SAMPLE_Y}。 */
    public static int referenceY(ServerLevel level, int x, int z) {
        ServerPlayer nearest = null;
        double best = 128.0D * 128.0D;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - x;
            double dz = p.getZ() - z;
            double d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        return nearest != null ? nearest.getBlockY() : BIOME_SAMPLE_Y;
    }

    private NetherSurface() {
    }
}
