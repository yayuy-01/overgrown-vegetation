package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 地下(洞窟)の面の判定(2026-11-03 地下の植生 1)。洞窟の菌糸 {@code cave_mycelium} を貼ってよい面を、サーバーの置き方({@link CaveFlora})で選ぶのに使う
 * (最初は覆いの絵を菌糸に差し替える判定としてクライアントの組み立てから呼んでいたが、同じ日の直しで菌糸は面に貼るブロックになった)。開発用の {@code lightat} も呼ぶ。
 *
 * <p>地下の面 = 面の隣のマスが空いていて空の光が 0、かつその列の「いちばん上の岩」(木の一部・水・下草を読み飛ばし、{@link #MIN_ROOF} 段以上続く岩の上面)が
 * そのブロックより上にある(地下モードの地図 {@code CaveMap} と同じ数え方)。空の光 0 だけでは、巨大樹の林冠の下の林床が地下になる。
 * 空の無い次元(ネザー・エンド)は地下にしない(全部が菌糸になるので)。
 */
public final class CaveCover {

    /** 天井と見なす岩の厚み(CaveMap と同じ 3)。 */
    public static final int MIN_ROOF = 3;
    /** 岩が無い列。 */
    public static final int NO_ROCK = Integer.MIN_VALUE;

    private CaveCover() {
    }

    /** 隣のマスが空いていて空の光が 0 の面(1 << {@link Direction#ordinal})。 */
    public static int darkFaces(BlockAndTintGetter region, BlockPos p) {
        int mask = 0;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            n.setWithOffset(p, d);
            BlockState s = region.getBlockState(n);
            if (s.canOcclude() && s.isSolidRender(region, n)) {
                continue;
            }
            if (region.getBrightness(LightLayer.SKY, n) == 0) {
                mask |= 1 << d.ordinal();
            }
        }
        return mask;
    }

    /** 岩(洞窟の天井になれるブロック)か。CaveMap#isRock と同じ。 */
    public static boolean isRock(BlockState state) {
        return !state.isAir() && state.canOcclude() && state.blocksMotion() && state.getFluidState().isEmpty()
                && !state.is(ModTags.NOT_CAVE_ROOF) && !TreeCanopyGroundFinder.isTreePart(state);
    }

    /** 列 (x, z) のいちばん上の岩の y(上から {@link #MIN_ROOF} 段以上続く最初の岩の上端)。無ければ {@link #NO_ROCK}。 */
    public static int topRockY(LevelReader level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        for (int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1; y >= minY; y--) {
            if (!isRock(level.getBlockState(pos.set(x, y, z)))) {
                continue;
            }
            int run = 0;
            while (run < MIN_ROOF && y - run >= minY && isRock(level.getBlockState(pos.set(x, y - run, z)))) {
                run++;
            }
            if (run >= MIN_ROOF || y - run < minY) {
                return y;
            }
            y -= run - 1;
        }
        return NO_ROCK;
    }

    /** {@code p} の面のうち地下の面(1 << 向き)。菌糸を貼る面の判定({@link CaveFlora})と開発用の lightat。 */
    public static int caveFaces(LevelReader level, BlockPos p) {
        if (!level.dimensionType().hasSkyLight()) {
            return 0;
        }
        int dark = darkFaces(level, p);
        if (dark == 0) {
            return 0;
        }
        int top = topRockY(level, p.getX(), p.getZ());
        return top != NO_ROCK && top > p.getY() ? dark : 0;
    }
}
