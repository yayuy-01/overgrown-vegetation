package com.yayuy.overgrownvegetation.swarm.goal;

import com.yayuy.overgrownvegetation.swarm.Swarm;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * 攻城(TD)が群れの Goal に差し込むフック(群れ v2、2026-09-25。td-design の「壁を壊す判断」「塊・壁へのダメージ」)。
 *
 * <p>群れの Goal は TD を知らない。TD 側が起動時に 2 つを登録し、Goal は決まった間隔で呼ぶだけ:
 * <ul>
 *     <li>{@link BreachPicker} … {@link SwarmLeadGoal} が、攻城(SIEGE)中に標的の塊への区間が
 *     {@value #BREACH_MIN_BLOCKED_LEGS} 回続けて塞がれたとき({@link Swarm#consecutiveBlockedLegs})に、
 *     多くても {@value #BREACH_CHECK_INTERVAL_TICKS} tick に 1 回呼ぶ。壊す壁を返せば群れは BREACH に入る
 *     ({@link Swarm#setBreach})。どの壁を選ぶか(DDA・壊せる塊か・水に面していないか・ブロックエンティティでないか)は TD が決める。</li>
 *     <li>{@link BlockStrike} … {@link SwarmEngageGoal} の塊の輪で、前衛が塊(壊す壁)に手が届く位置にいる間、
 *     一員ごとに {@value #STRIKE_INTERVAL_TICKS} tick に 1 回呼ぶ(人数が多いほど速く壊れる)。
 *     ダメージの量・削ってよいか(侵攻中のターゲットか)・壊し終えたら {@link Swarm#endBreach} も TD の仕事。
 *     true を返したときだけ、Goal が一員に攻撃アニメを付ける({@link SwarmAttacker})。</li>
 * </ul>
 * どちらも未登録なら何も起きない(攻城のモードは TD しか入れないので、TD が無ければそもそも呼ばれない)。
 *
 * <p>静的に持つのは TD のコードへの参照だけで、ワールドや個体は持たない(サーバーを止めても消す必要がない。
 * ワールドをまたいで残るのは「どう判断するか」だけ)。サーバースレッドから登録・呼び出しすること。
 */
public final class SwarmSiegeHooks {

    /** 壊す壁を選ぶ。 */
    @FunctionalInterface
    public interface BreachPicker {
        /**
         * @param vanguard 先導役
         * @param swarm    その群れ(攻城中)
         * @return 壊す壁。選べなければ null(先導役は区間を引き続け、塞がれ続ければまた呼ばれる)
         */
        @Nullable
        BlockPos pick(Mob vanguard, Swarm swarm);
    }

    /** 塊・壁を 1 回叩く。 */
    @FunctionalInterface
    public interface BlockStrike {
        /**
         * @param member 叩く一員(前衛、手が届く位置にいる)
         * @param swarm  その群れ
         * @param block  叩く塊(BREACH なら壊す壁、SIEGE なら標的の塊)
         * @param breach 壁を壊している(BREACH)か
         * @return 当てたなら true(攻撃アニメを付ける)。一時停止中などで削らなかったら false
         */
        boolean strike(Mob member, Swarm swarm, BlockPos block, boolean breach);
    }

    /** 区間がこの回数続けて塞がれたら、壊す壁を選ばせる(td-design「2 回以上」)。 */
    public static final int BREACH_MIN_BLOCKED_LEGS = 2;
    /** 壊す壁を選ばせる間隔の下限(tick)。選べなかった間、毎 tick DDA を回させない。仮決め 2026-09-25。 */
    public static final int BREACH_CHECK_INTERVAL_TICKS = 20;
    /** 一員が塊・壁を叩く間隔(tick。td-design「20 tick ごと」)。 */
    public static final int STRIKE_INTERVAL_TICKS = 20;
    /**
     * TD が壁に開ける穴の大きさの上限(幅・高さ、マス。TD 設計「3×3 まで」)。攻城(SIEGE / BREACH)の区間の包絡も
     * これに頭打ちする({@code SwarmController#envelope}): 区間の探索が「4 マス要る」と言う体(大きいスポロフィル・
     * 大きいスカトゥリウム)に合わせると、3×3 の穴を開け終えても区間が引けず、群れが拠点の前で止まり続けた(レビュー指摘 2026-09-25)。
     */
    public static final int MAX_BREACH_CELLS = 3;

    @Nullable
    private static BreachPicker breachPicker;
    @Nullable
    private static BlockStrike blockStrike;

    /** TD が起動時に登録する(null で外す)。 */
    public static void setBreachPicker(@Nullable BreachPicker picker) {
        breachPicker = picker;
    }

    /** TD が起動時に登録する(null で外す)。 */
    public static void setBlockStrike(@Nullable BlockStrike strike) {
        blockStrike = strike;
    }

    @Nullable
    static BreachPicker breachPicker() {
        return breachPicker;
    }

    @Nullable
    static BlockStrike blockStrike() {
        return blockStrike;
    }

    private SwarmSiegeHooks() {
    }
}
