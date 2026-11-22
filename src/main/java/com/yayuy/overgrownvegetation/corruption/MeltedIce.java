package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 汚染が氷に届いたら溶かす(2026-10-16 ユーザー指定「汚染地域に氷が残ってしまう問題」)。
 *
 * <ul>
 *     <li>氷({@code #minecraft:ice} = 氷・薄氷・パックされた氷・青氷)は対応表に無いので、以前は
 *     コンクリートと同じく苔で覆って残していた。今はどの汚染の経路でも {@link #stateAt} に置き換わる
 *     ({@link CorruptionRegistry#corrupt(ServerLevel, BlockPos, BlockState, net.minecraft.util.RandomSource)} の頭で)。
 *     溶け方は過繁茂バイオームの凍結面を剥がす処理({@link OvergrownBiomeCreep#stripFrozenSurface}、2026-09-29)と同じ。</li>
 *     <li>寒いバイオームのままの所では、バニラが岸の水からまた凍らせる。汚染された地面・覆ったマスの隣の水は凍らせない
 *     ({@link #nextToCorruption}。{@code mixin/BiomeFreezeNearCorruptionMixin} から)。</li>
 * </ul>
 */
public final class MeltedIce {

    private MeltedIce() {
    }

    public static boolean isIce(BlockState state) {
        return state.is(BlockTags.ICE);
    }

    /**
     * 溶けた氷の跡に置くブロック。上下か横 4 方向のどれかが水なら<b>水源</b>(氷床・氷山の水面より
     * 下・凍った川や湖の面は水に戻る)、水に接していなければ<b>空気</b>(陸の氷柱や、水面より上に
     * 突き出た氷山の塊は跡形なく消える)。
     *
     * <p>水に接しているかだけで見るので、氷山の内部のように周りが氷だらけの段はいったん空気になる。
     * そこへ周りの海水が流れ込むため、次の走査ではその下の段が「水に接している」と判定され、
     * 下へ向かって水に置き換わっていく。陸の上の氷が水に化けて洪水になることはない。
     */
    public static BlockState stateAt(ServerLevel level, BlockPos pos) {
        if (isWaterAt(level, pos.below()) || isWaterAt(level, pos.above())) {
            return Blocks.WATER.defaultBlockState();
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (isWaterAt(level, pos.relative(direction))) {
                return Blocks.WATER.defaultBlockState();
            }
        }
        return Blocks.AIR.defaultBlockState();
    }

    private static boolean isWaterAt(ServerLevel level, BlockPos pos) {
        return com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, pos) && level.getFluidState(pos).is(FluidTags.WATER);
    }

    /**
     * この水のマスの下か横 4 方向に、汚染された地面({@code #corrupted})か覆ったマスがあるか。
     * バニラが凍らせてよいと答えた(寒いバイオームの岸の水)ときだけ呼ばれる。読み込まれていない隣は見ない
     * (見るとチャンクを同期で読み込んでしまう)。
     */
    public static boolean nextToCorruption(ServerLevel level, BlockPos water) {
        if (touchesCorruption(level, water.below())) {
            return true;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (touchesCorruption(level, water.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    private static boolean touchesCorruption(ServerLevel level, BlockPos pos) {
        if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && (state.is(ModTags.CORRUPTED) || OvergrownLayerData.isCovered(level, pos));
    }
}
