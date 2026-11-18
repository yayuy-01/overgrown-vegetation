package com.yayuy.overgrownvegetation.corruption.pushback;

import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * ハイヴノードのまわりでは火が燃え広がらない(2026-10-10 ユーザー指定「hivenode から半径 512 の範囲は火が燃え広がらないように」。
 * 11-18 夜に胞子焼きの筒の火と一緒に戻した。前の版は parked/purify/HiveFireGuard)。
 * 範囲は生きているハイヴノードの記録から水平 {@value #RADIUS} ブロック・高さは問わない・オーバーワールドだけ(Claude 仮決め 10-10)。
 * 火を付けること・付いた火が隣を燃やすこと・汚染の地面を燻る灰にすることは今までどおり(止めるのは「燃え広がり」だけ。{@code mixin/FireBlockHiveGuardMixin})。
 */
public final class HiveFireGuard {

    /** ハイヴノードから火が燃え広がらない範囲(水平、ブロック)。ユーザー指定。 */
    public static final double RADIUS = 512.0D;

    public static boolean protects(ServerLevel level, BlockPos pos) {
        return level.dimension() == Level.OVERWORLD && PhaseData.get(level).hasHiveNodeWithinHorizontal(pos, RADIUS);
    }

    private HiveFireGuard() {
    }
}
