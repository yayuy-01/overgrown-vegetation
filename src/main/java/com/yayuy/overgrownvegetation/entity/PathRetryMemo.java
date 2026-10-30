package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;

/**
 * <b>届かない相手への経路探索(A*)を、同じ条件のまま繰り返さない</b>ための控え(2026-09-24、重さ対策。
 * ユーザー承認「経路探索の間引き」)。経路を引き直す呼び出しをこれに通す。控えは呼ぶゴール(スクティムネスは本体)ごとに 1 つ。
 *
 * <p>届かない相手へ引いた経路は「いちばん近づける点まで」の途中までの経路で、歩き終えると {@code isDone} になる。
 * バニラの {@code PathNavigation#createPath} は歩いている途中の経路しか使い回さないので、その点に着いたまま引き直すたびに
 * 同じ探索を丸ごとやり直す(大きな体・飛行の探索は 1 回が重い)。しかも同じマスから同じ目標への探索は同じ点の並びを返すので、
 * {@code moveTo} はいま持っている歩き終えた経路を差し替えずに false を返すだけ —— 探索の手間だけ掛かって何も起きない。
 *
 * <p>探索を省くのは次が<b>全部</b>揃ったときだけ。それ以外は元どおり探索する。
 * <ul>
 *   <li>前回<b>実際に探索した</b>結果が、届かない経路だった。</li>
 *   <li>自分の足元のブロックも、目標のブロック(相手の足元・幹・水)も、その探索のときから変わっていない。</li>
 *   <li>その探索から {@link #PATH_RETRY_TICKS} tick 経っていない。</li>
 *   <li>ナビがいまも、その探索の直後に持っていた経路を持っていて、歩き終えている。止められた・ほかの経路に替わった・
 *       まだ歩いている途中なら探索する(元の呼び出しが経路を置き直す場面と、探索しない場面なので)。</li>
 * </ul>
 * 省いた呼び出しは、同じ探索をしたときと同じく<b>ナビに触れず false を返す</b>。結果が元と変わるのは、2 つのブロックが
 * 変わらないまま地形だけが変わったときで、それも {@link #PATH_RETRY_TICKS} tick 以内に探し直す。
 *
 * <p>経路が null のとき(探索の手前で止まった —— 宙に浮いている・何かに乗っている・目標のチャンクが読み込まれていない。
 * 探索そのものは、届かなくても必ず途中までの経路を返す)と、歩いている途中の経路を使い回したときは、探索していないので
 * 控えを書き換えない —— 着地した直後の探索まで省いてしまわないように。
 */
final class PathRetryMemo {

    /** 届かなかった探索と同じマス同士なら、この tick 数が経つまで探し直さない(仮決め 2026-09-24。{@link ShielderPursueGoal} と同じ)。 */
    static final int PATH_RETRY_TICKS = 40;

    /** 届かなかった探索の直後にナビが持っていた経路。無ければ {@code null}(控えなし)。 */
    @Nullable
    private Path held;
    /** その探索のときの自分／目標のブロック座標({@link BlockPos#asLong})と、ゲーム時刻。 */
    private long from;
    private long to;
    private long searchedAt;

    /**
     * {@code mob.getNavigation().moveTo(target, speed)} と同じ(戻り値も)。前回の届かなかった探索と同じ条件なら探索を省いて
     * false(同じ探索をしたときに返る値)。
     */
    boolean moveTo(Mob mob, Entity target, double speed) {
        BlockPos goal = target.blockPosition();
        if (sameAsFailedSearch(mob, goal)) {
            return false;
        }
        PathNavigation navigation = mob.getNavigation();
        Path before = navigation.getPath();
        // PathNavigation#moveTo(Entity, double) の中身そのまま(探索の結果を見るために開いた)。
        Path path = navigation.createPath(target, 1);
        boolean moving = path != null && navigation.moveTo(path, speed);
        remember(mob, goal, before, path);
        return moving;
    }

    /**
     * {@code navigation.moveTo(navigation.createPath(target, accuracy), speed)} と同じ(経路が無ければナビを止めるのも、戻り値も)。
     * 前回の届かなかった探索と同じ条件なら探索を省いて false(同じ探索をしたときに返る値)。
     */
    boolean moveTo(Mob mob, BlockPos target, int accuracy, double speed) {
        if (sameAsFailedSearch(mob, target)) {
            return false;
        }
        PathNavigation navigation = mob.getNavigation();
        Path before = navigation.getPath();
        Path path = navigation.createPath(target, accuracy);
        boolean moving = navigation.moveTo(path, speed);
        remember(mob, target, before, path);
        return moving;
    }

    /** 前回の届かなかった探索と同じ条件で、探し直しても何も起きないか。 */
    private boolean sameAsFailedSearch(Mob mob, BlockPos target) {
        Path current = mob.getNavigation().getPath();
        return this.held != null
                && current == this.held
                && current.isDone()
                && mob.level().getGameTime() - this.searchedAt < PATH_RETRY_TICKS
                && mob.blockPosition().asLong() == this.from
                && target.asLong() == this.to;
    }

    /**
     * 引き直しの結果を控える。探索して届かなければ控え、届けば控えを消す。探索していない(null・使い回し)なら触らない。
     *
     * @param before 呼ぶ前にナビが持っていた経路({@code createPath} が使い回したかを見分ける)
     */
    private void remember(Mob mob, BlockPos target, @Nullable Path before, @Nullable Path result) {
        if (result == null || result == before) {
            return;
        }
        if (result.canReach()) {
            this.held = null;
            return;
        }
        this.held = mob.getNavigation().getPath();
        this.from = mob.blockPosition().asLong();
        this.to = target.asLong();
        this.searchedAt = mob.level().getGameTime();
    }
}
