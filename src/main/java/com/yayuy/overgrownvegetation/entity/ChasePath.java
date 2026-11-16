package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.entity.runner.ChasePathBudget;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * 相手を追う経路の引き直しの共通の部品(2026-11-16 コードの見直し ①4。ヴェナムネスの作りを出した)。
 * <ol>
 *   <li>サーバー全体の予算 {@link ChasePathBudget}(1 tick 2 ms)に聞く。使い切っていれば何もしない(呼ぶ側は次の tick にもう一度)。</li>
 *   <li>探す数を {@code nodeMult} 倍に絞る(1 回の探索の重さを抑える)。</li>
 *   <li>届かなかったのと同じマス同士の探索は {@link PathRetryMemo} で省く。</li>
 * </ol>
 * 追う Goal ごとに 1 つ持つ。サーバーのスレッドからだけ呼ぶ。
 */
public final class ChasePath {

    private final PathRetryMemo memo = new PathRetryMemo();

    /** 予算の中で {@code target} へ経路を引き直す。予算を使い切っていて引かなかったら false。 */
    public boolean repath(Mob mob, Entity target, double speed, float nodeMult) {
        if (!(mob.level() instanceof ServerLevel sl)) return false;
        if (!ChasePathBudget.tryStart(sl)) return false;
        long t0 = System.nanoTime();
        mob.getNavigation().setMaxVisitedNodesMultiplier(nodeMult);
        try {
            this.memo.moveTo(mob, target, speed);
        } finally {
            mob.getNavigation().resetMaxVisitedNodesMultiplier();
        }
        ChasePathBudget.spend(sl, System.nanoTime() - t0);
        return true;
    }

    /** 予算に通さず(届かない探索の控えだけ通して)引き直す。確かめ用の素の追い方。 */
    public boolean moveTo(Mob mob, Entity target, double speed) {
        return this.memo.moveTo(mob, target, speed);
    }
}
