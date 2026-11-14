package com.yayuy.overgrownvegetation.director;

import net.minecraft.server.level.ServerLevel;

/**
 * 森の予算の口(2026-11-13)。0.2.0 は {@link ForestDirector} の中の簡単な予算(汚染の広さで貯まり、ハイヴノードで上限)。
 * 退避中のバイオマスの台帳({@code ecosystem/})を戻したら、この口の向こうだけを差し替える(11-13 ユーザー
 * 「0.2.0 では簡単な森の予算に」「台帳は後で戻して差し替える」)。
 */
public interface ForestBudget {

    /** 今使える量(呼んだ時刻までの貯まりを足してから答える)。 */
    double available(ServerLevel level);

    /** 払えれば払って true。足りなければ何もせず false。 */
    boolean tryPay(ServerLevel level, double cost);

    /** 払ったものを戻す(変われなかったとき)。上限は超えない。 */
    void refund(ServerLevel level, double amount);
}
