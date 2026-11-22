package com.yayuy.overgrownvegetation.entity.runner;

import net.minecraft.server.level.ServerLevel;

/**
 * 追う間の経路づくりの、サーバー全体の予算(2026-11-15、ヴェナムネスの段 A。回り道の下調べ {@code CoarseDetour} と同じ考え方)。
 *
 * <p>段 1 の計測で、10 体が走り続ける 1 tick が一度 38 ms に跳ねた。出どころはバニラの経路探索 1 回(最悪 23 ms)で、何体もが同じ tick に
 * 引き直すと重なる。追う Goal は引き直しの前に {@link #tryStart} を聞き、この tick の使った時間に「1 回の見積もり」を足して
 * {@link #BUDGET_NANOS} を超えるなら次の tick へ回す(1 tick に少なくとも 1 回は通す。1 回そのものの重さは探す数で抑える ——
 * 呼ぶ側の {@code setMaxVisitedNodesMultiplier})。値は【仮】。サーバーのスレッドからだけ呼ぶ。
 */
public final class ChasePathBudget {

    /** 追う経路づくりに使う時間の、サーバー全体の 1 tick あたりの上限(ns)【仮 2 ms】。 */
    public static final long BUDGET_NANOS = 2_000_000L;

    private static long budgetTick = Long.MIN_VALUE;
    private static long spent;
    private static long avgCall;
    /** 計測用: 引き直した回数・次の tick へ回した回数・使った時間の合計(ns)・1 回の最悪(ns)。 */
    public static volatile long calls, deferred, nanos, worst;

    private ChasePathBudget() {
    }

    private static void roll(ServerLevel level) {
        long t = level.getGameTime();
        if (t != budgetTick) {
            budgetTick = t;
            spent = 0L;
        }
    }

    /** この tick にもう 1 回引き直してよいか(よくなければ数えて false)。 */
    public static boolean tryStart(ServerLevel level) {
        roll(level);
        if (spent > 0L && spent + avgCall > BUDGET_NANOS) {
            deferred++;
            return false;
        }
        return true;
    }

    /** 引き直し 1 回にかかった時間を付ける。 */
    public static void spend(ServerLevel level, long ns) {
        roll(level);
        spent += ns;
        avgCall = avgCall == 0L ? ns : (avgCall * 4L + ns) / 5L;
        calls++;
        nanos += ns;
        worst = Math.max(worst, ns);
    }

    public static void resetStats() {
        calls = deferred = nanos = worst = 0L;
    }
}
