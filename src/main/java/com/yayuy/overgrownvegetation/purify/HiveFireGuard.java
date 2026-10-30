package com.yayuy.overgrownvegetation.purify;

import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * ハイヴノードのまわりでは火が燃え広がらない(2026-10-10、ユーザー指定「hivenode から半径 512 の範囲は火が燃え広がらないように」。
 * 同じ日に火による浄化を実験的要素から外したのに合わせて)。
 *
 * <p>範囲は生きているハイヴノード({@link PhaseData#hasHiveNodeWithinHorizontal} の記録。倒すと外れる)から<b>水平 {@value #RADIUS} ブロック</b>、
 * 高さは問わない(Claude 仮決め: 地図の円で考えられるように)。オーバーワールドだけ(記録はワールドに 1 つで、座標に次元を持たない)。
 * 記録の無い旧ワールドでは巨大樹の中心で代用する(胞子感染3 の範囲と同じ決まり)。
 *
 * <p>「燃え広がる」= 火が新しいマスへ移ること。範囲の中では:
 * <ul>
 *     <li>空いたマスへ火が飛び移らない({@code mixin/FireBlockPurifyMixin} が {@code FireBlock#getIgniteOdds} を 0 にする。外から範囲の中へも飛ばない)。</li>
 *     <li>燃え尽きたマスに火が残らない(バニラの {@code checkBurnOut} の代わり・{@link VanillaFire#consume}・{@link TreeBurn} の燃え落ち)。</li>
 * </ul>
 * 火を付けること・付いた火がその隣を燃やすこと・汚染された地面を燻る灰に変えること・宙に残った木が燃え落ちることは今までどおり
 * (Claude 仮決め)。火炎放射器(実験的要素)の炎も付く。
 */
public final class HiveFireGuard {

    /** ハイヴノードから火が燃え広がらない範囲(水平、ブロック)。ユーザー指定。 */
    public static final double RADIUS = 512.0D;

    /** そのマスでは火が燃え広がらないか。 */
    public static boolean protects(ServerLevel level, BlockPos pos) {
        return level.dimension() == Level.OVERWORLD && PhaseData.get(level).hasHiveNodeWithinHorizontal(pos, RADIUS);
    }

    private HiveFireGuard() {
    }
}
