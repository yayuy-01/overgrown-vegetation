package com.yayuy.overgrownvegetation.wall;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 蠢く壁から見た升の種類(決定 その 3・7)。
 * <ul>
 *     <li>{@link Kind#PASS}: 通れる。空気・水・当たりの無いもの(植物・レール・松明)・灯り・BlockEntity を持つもの(チェスト・成れの果て。覆うだけ)。</li>
 *     <li>{@link Kind#CHEW}: 置いたブロック。硬さ × 2 秒で壊して進む。天然の地形でない固いブロック + プレイヤーが置いたと覚えているもの。</li>
 *     <li>{@link Kind#WALL}: 天然の岩・溶岩・壊せないブロック。</li>
 *     <li>{@link Kind#SKY}: 真上に空が見える升。日の光の当たる所までは来ない。</li>
 * </ul>
 * 空の判定は光の値を使わない(更新が遅れ、GameTest で揺れる)。列の上から下へ、葉・木を読み飛ばして最初の「屋根になる」ブロックを探し、
 * それより上を空とする(列ごとに壁が覚える)。
 */
public final class WallCells {

    public enum Kind { PASS, CHEW, WALL, SKY }

    private WallCells() {
    }

    /** 升の種類。{@code pos} のチャンクは読み込まれていること。 */
    public static Kind classify(ServerLevel level, BlockPos pos, CreepingWall wall) {
        BlockState state = level.getBlockState(pos);
        Kind kind = kindOf(level, pos, state);
        if (kind == Kind.WALL) {
            return Kind.WALL;
        }
        return wall.isSky(pos) ? Kind.SKY : kind;
    }

    /** 壁の覚え無しで見る(コマンドの始まりの場所選び・テスト)。 */
    public static Kind classify(ServerLevel level, BlockPos pos) {
        return classify(level, pos, (it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap) null);
    }

    /** {@code roofs} = 列 → 屋根の y の覚え(null なら毎回調べる)。 */
    public static Kind classify(ServerLevel level, BlockPos pos, @javax.annotation.Nullable it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap roofs) {
        Kind kind = kindOf(level, pos, level.getBlockState(pos));
        if (kind == Kind.WALL) {
            return Kind.WALL;
        }
        int roof;
        if (roofs == null) {
            roof = roofY(level, pos.getX(), pos.getZ());
        } else {
            long col = ((long) pos.getX() << 32) | (pos.getZ() & 0xFFFFFFFFL);
            if (roofs.containsKey(col)) {
                roof = roofs.get(col);
            } else {
                roof = roofY(level, pos.getX(), pos.getZ());
                roofs.put(col, roof);
            }
        }
        return pos.getY() > roof ? Kind.SKY : kind;
    }

    static Kind kindOf(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return Kind.PASS;
        }
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return Kind.WALL;
        }
        if (state.hasBlockEntity() || isLight(state) || state.getCollisionShape(level, pos).isEmpty()) {
            return Kind.PASS;
        }
        return isChewable(level, pos, state) ? Kind.CHEW : Kind.WALL;
    }

    /** 置いたブロックとして壊せるか。 */
    static boolean isChewable(ServerLevel level, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return false; // 岩盤・バリアなど
        }
        if (PlacedBlocks.isPlaced(level, pos.asLong())) {
            return true;
        }
        return !isNaturalTerrain(state);
    }

    /** 天然の地形(壁が掘り進まないもの)。タグ cave_rock + 洞窟にふつうにある土・氷・スカルクなど。 */
    static boolean isNaturalTerrain(BlockState state) {
        return state.is(ModTags.CAVE_ROCK) || state.is(BlockTags.ICE) || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                || state.is(Blocks.CLAY) || state.is(Blocks.MUD) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SCULK) || state.is(Blocks.POINTED_DRIPSTONE) || state.is(Blocks.SMALL_AMETHYST_BUD)
                || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.AMETHYST_CLUSTER)
                || state.is(ModTags.CORRUPTED) || TreeCanopyGroundFinder.isTreePart(state);
    }

    /** 灯り(先頭が来たら消える)。光を出すブロックのうち溶岩・天然の地形でないもの。 */
    public static boolean isLight(BlockState state) {
        return state.getLightEmission() > 0 && !state.getFluidState().is(FluidTags.LAVA) && !state.is(ModTags.CAVE_ROCK)
                && !state.is(ModTags.CORRUPTED) && !state.hasBlockEntity() && !state.is(Blocks.MAGMA_BLOCK)
                && !isNaturalGlow(state);
    }

    /**
     * 光る天然の植物・鉱物(グロウベリーのツタ・ヒカリゴケ・シーピクルス・アメジストの芽など)。「灯り」ではなく覆うだけ
     * (2026-11-16 計測: 繁茂の洞窟のツタを 1 つずつ消すと周りの更新が連鎖し、重い tick と落ちたグロウベリーの山になった)。
     */
    static boolean isNaturalGlow(BlockState state) {
        return state.is(BlockTags.CAVE_VINES) || state.is(Blocks.GLOW_LICHEN) || state.is(Blocks.SEA_PICKLE)
                || state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.LARGE_AMETHYST_BUD)
                || state.is(Blocks.AMETHYST_CLUSTER) || state.is(Blocks.BROWN_MUSHROOM) || state.is(Blocks.SCULK_SENSOR)
                || state.is(BlockTags.REPLACEABLE_BY_TREES) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                || state.getBlock().getDescriptionId().startsWith("block.overgrown_vegetation."); // この MOD の光る植物(洞窟の光る根・狐火茸など)も
    }

    /** 列の屋根: 上から下へ見て最初の、屋根になるブロック(当たりのある・葉と木でない)の y。無ければ最低の高さ − 1。 */
    static int roofY(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = top; y >= level.getMinBuildHeight(); y--) {
            BlockState s = level.getBlockState(p.set(x, y, z));
            if (s.isAir() || !s.getFluidState().isEmpty() && !s.blocksMotion()) {
                continue;
            }
            if (s.blocksMotion() && !s.is(BlockTags.LEAVES) && !TreeCanopyGroundFinder.isTreePart(s)) {
                return y;
            }
        }
        return level.getMinBuildHeight() - 1;
    }
}
