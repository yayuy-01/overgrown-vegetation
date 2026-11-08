package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.block.WallFormGrowthBlock;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SpreadTable;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 壁の側面に生える植物を、場所で選ぶ(2026-11-08。下絵は tools/wall-plants、報告は side-session-notes の「壁の植物」)。
 *
 * <p>場所の分け方(どれも仮決め、値は {@code spread.json} の {@code wallPlants}):
 * <ol>
 *     <li><b>湿った壁</b> — 半径 {@code wetRadius} の中に水がある、または真上の張り出しの上に水がある(滴る所)。</li>
 *     <li><b>地上</b> — 湿っていなくて、空の光が {@code skyLight} 以上。</li>
 *     <li><b>洞窟</b> — どちらでもない(地下の暗い壁)。</li>
 * </ol>
 * 場所ごとの重みで 1 つ引く。引いたものが置けない所(垂れ苔は真上が固体の面だけ)なら<b>何も置かない</b>(引き直さない)。
 * 引き直すと、天井の無い壁で立体のもの(胞子嚢・菌の棚・壁シダ)の割合が上がり、重さと見た目の密度が変わるため。
 *
 * <p>壁を這う小葉類は、手前の床に小葉類(イワヒバ・ヒカゲノカズラ・小葉類の敷物・立ち上がる小葉類)があり、
 * 真下が床なら「床から続く最下段」の絵(form 1)。それ以外は {@code clubmossShare} の割合でヒカゲノカズラ(form 2)、残りは扇(form 0)。
 */
public final class WallPlantGrowth {

    /** 場所。 */
    public enum Place { SURFACE, WET, CAVE }

    private WallPlantGrowth() {
    }

    /** {@code pos}(壁の前の空気)に置く植物。何も置かないときは null。{@code facing} は壁から離れる向き。 */
    @Nullable
    public static BlockState pick(Level level, BlockPos pos, RandomSource random, Direction facing) {
        SpreadTable.WallPlants w = DesignData.spread().wallPlants();
        Place place = placeAt(level, pos, w);
        BlockState state = switch (place) {
            case SURFACE -> pickWeighted(random,
                    w.surfaceLycopsid(), lycopsid(level, pos, facing, random, w),
                    w.surfaceMoss(), moss(level, pos, 1),
                    w.surfaceFern(), plain(ModBlocks.WALL_FERN.get()));
            case WET -> pickWeighted(random,
                    w.wetMoss(), moss(level, pos, 0),
                    w.wetFern(), plain(ModBlocks.WALL_FERN.get()),
                    w.wetFernLarge(), plain(ModBlocks.WALL_FERN_LARGE.get()));
            case CAVE -> pickWeighted(random,
                    w.caveMoss(), moss(level, pos, 2),
                    w.caveSporeSacs(), plain(ModBlocks.WALL_SPORE_SACS.get()),
                    w.caveBracket(), plain(ModBlocks.WALL_BRACKET_FUNGUS.get()));
        };
        return state == null ? null : state.setValue(HorizontalDirectionalBlock.FACING, facing);
    }

    /** 場所の判定(テストと devbridge からも)。 */
    public static Place placeAt(Level level, BlockPos pos, SpreadTable.WallPlants w) {
        if (isWet(level, pos, w.wetRadius())) {
            return Place.WET;
        }
        return level.getBrightness(LightLayer.SKY, pos) >= w.skyLight() ? Place.SURFACE : Place.CAVE;
    }

    private static boolean isWet(Level level, BlockPos pos, int radius) {
        BlockPos above = pos.above();
        if (level.getBlockState(above).isFaceSturdy(level, above, Direction.DOWN)
                && level.getFluidState(above.above()).is(FluidTags.WATER)) {
            return true; // 真上の張り出しの上に水 = 滴る
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    m.setWithOffset(pos, dx, dy, dz);
                    if (level.isLoaded(m) && level.getFluidState(m).is(FluidTags.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Nullable
    private static BlockState plain(Block block) {
        return block.defaultBlockState();
    }

    /** 垂れ苔。真上が固体の面(張り出しの下・天井との角)だけ。 */
    @Nullable
    private static BlockState moss(Level level, BlockPos pos, int form) {
        BlockPos above = pos.above();
        if (!level.getBlockState(above).isFaceSturdy(level, above, Direction.DOWN)) {
            return null;
        }
        return ModBlocks.HANGING_MOSS.get().defaultBlockState().setValue(WallFormGrowthBlock.FORM, form);
    }

    private static BlockState lycopsid(Level level, BlockPos pos, Direction facing, RandomSource random, SpreadTable.WallPlants w) {
        int form;
        if (lycopsidFloorInFront(level, pos, facing)) {
            form = 1;
        } else {
            form = random.nextDouble() < w.clubmossShare() ? 2 : 0;
        }
        return ModBlocks.WALL_LYCOPSID.get().defaultBlockState().setValue(WallFormGrowthBlock.FORM, form);
    }

    /** 真下が床で、手前か両脇の床(同じ高さのマス)に小葉類があるか。 */
    public static boolean lycopsidFloorInFront(Level level, BlockPos pos, Direction facing) {
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        return isLycopsid(level.getBlockState(pos.relative(facing)))
                || isLycopsid(level.getBlockState(pos.relative(facing.getClockWise())))
                || isLycopsid(level.getBlockState(pos.relative(facing.getCounterClockWise())));
    }

    private static boolean isLycopsid(BlockState s) {
        return s.is(ModBlocks.SELAGINELLA.get()) || s.is(ModBlocks.CLUBMOSS.get()) || s.is(ModBlocks.LYCOPSID_CARPET.get())
                || s.is(ModBlocks.LYCOPSID_UPRIGHT.get()) || s.is(ModBlocks.WALL_LYCOPSID.get());
    }

    /** 重み a・b・c で 1 つ引く。引いたものが null(その所に置けない)なら null。 */
    @Nullable
    private static BlockState pickWeighted(RandomSource random, double wa, @Nullable BlockState a, double wb, @Nullable BlockState b,
                                           double wc, @Nullable BlockState c) {
        double total = Math.max(0, wa) + Math.max(0, wb) + Math.max(0, wc);
        if (total <= 0) {
            return null;
        }
        double k = random.nextDouble() * total;
        if ((k -= Math.max(0, wa)) < 0) {
            return a;
        }
        if ((k -= Math.max(0, wb)) < 0) {
            return b;
        }
        return c;
    }
}
