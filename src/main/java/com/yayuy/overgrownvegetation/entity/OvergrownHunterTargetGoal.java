package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.effect.SporeInfectionDetection;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.phys.AABB;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * グループ3の捕食者({@link AbstractOvergrownPredatorEntity} / {@link MeganeuraEntity})用の索敵 Goal。
 *
 * <p>バニラの {@link NearestAttackableTargetGoal} をそのまま使うと、{@link TargetingConditions#forCombat()}
 * が既定で視線(LOS)チェック込みなうえ {@code mustSee=true} で追跡継続も視線に依存するため、
 * 過繁茂バイオームのように地表が葉・茂み・下草で埋め尽くされた環境では「相手が近くにいるのに
 * 索敵できない」「一瞬狙ったのに視線が切れて 3 秒で見失い、どこかへ歩き去る」が多発する。
 *
 * <p>この Goal は {@code mustSee=false} かつ {@link TargetingConditions#ignoreLineOfSight()} で
 * 視線を一切見ない。障害物越しでも新規に発見でき、追跡中に見えなくなっても
 * {@code FOLLOW_RANGE} 以内にいる限り見失わない({@link com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal}
 * と同じ方針。壁越しに殴れるようにはならない — 実際の近接ヒットは {@code MeleeAttackGoal} 側が
 * 視線と間合いを見る)。
 *
 * <p>「胞子感染」にかかっている相手は、段階に応じた {@link SporeInfectionDetection#bonusFor 被検知距離ボーナス}
 * のぶん {@code FOLLOW_RANGE} より遠くからでも見つけられる(胞子感染2/3 の「検知範囲増加」)。
 *
 * <p>対象の絞り込みは {@code selector}(自分, 候補)→ boolean で指定する
 * ({@link com.yayuy.overgrownvegetation.entity.Predation#isPredatorOf 捕食対象}や
 * {@link com.yayuy.overgrownvegetation.entity.Predation#isIntruder 侵入者}など)。
 */
public class OvergrownHunterTargetGoal extends NearestAttackableTargetGoal<LivingEntity> {

    /** 新規索敵の試行間隔(バニラの敵対 Mob と同程度)。 */
    private static final int RANDOM_INTERVAL = 10;

    private final double baseRange;
    private final Predicate<LivingEntity> selector;

    /**
     * 算数だけの足切りに使う距離の 2 乗(2026-09-24 軽量化、報告 120 行)。{@link #findTarget} の頭で
     * 1 回だけ決め、候補ごとに設計データやグループを引き直さない。{@link #findTarget} の外では
     * 足切りしない値(無限大)に戻しておく。
     */
    private double cutoffSq = Double.POSITIVE_INFINITY;
    /**
     * 被検知ボーナスが全部 0 以上と分かっているか({@link #findTarget} の間だけ true)。そのときは許容距離が
     * {@link #baseRange} を下回らないので、その内側の相手には {@link SporeInfectionDetection#bonusFor} を引かない。
     */
    private boolean bonusesNonNegative;

    /** {@code selector}(自分, 候補)→ 対象にするか。 */
    public OvergrownHunterTargetGoal(Mob mob, BiPredicate<LivingEntity, LivingEntity> selector) {
        this(mob, candidate -> selector.test(mob, candidate));
    }

    /** {@code selector}(候補)→ 対象にするか。 */
    public OvergrownHunterTargetGoal(Mob mob, Predicate<LivingEntity> selector) {
        super(mob, LivingEntity.class, RANDOM_INTERVAL, false, false, null);
        this.baseRange = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        this.selector = selector;
        // 親コンストラクタ時点では selector を渡せない(this を安全にキャプチャできない)ので、
        // ここで targetConditions を組み直す。ignoreLineOfSight() が肝。範囲は最大ボーナス込みで
        // 広めに取り、対象ごとの実際の許容距離は isValidTarget が段階別に決める。
        this.targetConditions = TargetingConditions.forCombat()
                .range(baseRange + SporeInfectionDetection.maxBonus())
                .ignoreLineOfSight()
                .selector(this::isValidTarget);
    }

    /**
     * <b>群れの一員は自分で標的を探さない</b>(ユーザー指定 2026-09-25「後続は自分で標的を探さない」。先導役も同じで、
     * 標的は司令塔だけが決めて役割の距離で渡す)。重い探索(箱の走査)に入る前に落とす。群れに居ない個体は今までどおり。
     */
    @Override
    public boolean canUse() {
        if (SwarmMember.isInSwarm(this.mob)) {
            return false;
        }
        return super.canUse();
    }

    /**
     * 群れに入った個体では止める。止めないと、司令塔が標的を外した({@code setTarget(null)})次の tick に
     * {@code TargetGoal#canContinueToUse} が覚えている相手({@code targetMob})を<b>付け直して</b>しまう。
     */
    @Override
    public boolean canContinueToUse() {
        if (SwarmMember.isInSwarm(this.mob)) {
            return false;
        }
        return super.canContinueToUse();
    }

    /** 群れの一員では標的を消さない(司令塔が渡した標的を、止まった索敵 Goal が消さないように)。 */
    @Override
    public void stop() {
        if (SwarmMember.isInSwarm(this.mob)) {
            this.targetMob = null;
            return;
        }
        super.stop();
    }

    /**
     * 安い判定から先に見る(2026-09-24、軽量化)。索敵箱は付きうるボーナス込みで広く取っているので、
     * 箱の中の候補は大半が許容距離の外にいる。{@code selector} には水や地表の高さを読む判定
     * ({@link OvergrownLandTargetGoal})が入るので、距離で落とせる相手にはそれを引かない。
     * どれも副作用の無い判定の AND なので、並べ替えても選ばれる相手は変わらない。
     */
    private boolean isValidTarget(LivingEntity candidate) {
        double distSq = mob.distanceToSqr(candidate);
        // 算数だけの足切り。ボーナスが全部 0 以上なら、許容距離は baseRange + searchBonus を超えない
        // (findTarget が決めた cutoffSq。それ以外のときは無限大で、足切りしない)。
        if (distSq > cutoffSq) {
            return false;
        }
        // ボーナスが全部 0 以上なら許容距離は baseRange を下回らないので、その内側の相手は被検知ボーナスを
        // 数えなくても距離の条件を満たす(2026-09-24 軽量化、報告 120 行)。群れの仲間の大半はここで
        // bonusFor(相手の効果を 3 回引く)を払わずに selector へ進み、種類の判定で落ちる。結果は同じ。
        // 「<= でなければ」と書くのは、数でない距離を従来どおり下の判定で外すため。
        if (!(bonusesNonNegative && distSq <= baseRange * baseRange)) {
            double allowed = baseRange + SporeInfectionDetection.bonusFor(mob, candidate);
            if (!(distSq <= allowed * allowed)) {
                return false;
            }
        }
        return selector.test(candidate);
    }

    /**
     * 親({@link NearestAttackableTargetGoal#findTarget})と同じ検索(2026-09-24 軽量化、報告 120 行)。
     * 違うのは 2 点だけで、選ばれる相手は変わらない。
     * <ul>
     *     <li>足切りの距離をここで 1 回だけ決める({@link #cutoffSq})。</li>
     *     <li>その距離の外にいる相手(箱の角など)を、集める段階で読み飛ばす —— {@link #isValidTarget} が
     *     必ず落とす相手なので、一覧に入れても {@code targetConditions} を通らない。残る候補の並び順は同じ。</li>
     * </ul>
     */
    @Override
    protected void findTarget() {
        boolean nonNegative = SporeInfectionDetection.bonusesNonNegative();
        double reach = baseRange + SporeInfectionDetection.searchBonus(mob);
        double cutoff = nonNegative ? reach * reach : Double.POSITIVE_INFINITY;
        this.cutoffSq = cutoff;
        this.bonusesNonNegative = nonNegative;
        this.target = mob.level().getNearestEntity(
                mob.level().getEntitiesOfClass(LivingEntity.class, getTargetSearchArea(getFollowDistance()),
                        candidate -> !(mob.distanceToSqr(candidate) > cutoff)),
                targetConditions, mob, mob.getX(), mob.getEyeY(), mob.getZ());
        this.cutoffSq = Double.POSITIVE_INFINITY;
        this.bonusesNonNegative = false;
    }

    /**
     * 箱に足す被検知ボーナスは{@link SporeInfectionDetection#searchBonus 自分のグループで付きうるぶん}だけ
     * (2026-09-24 軽量化、報告 120 行)。グループ3 の捕食者など、纏わりついた胞子のぶんが付かない探し手は
     * そのぶん箱が小さくなる。外側の相手は {@link #isValidTarget} が距離で必ず落とすので、選ばれる相手は同じ。
     *
     * <p>{@code targetConditions} の {@code range}(コンストラクタ)は<b>縮めない</b>。あちらは相手の
     * 見えにくさ(スニーク・透明・頭装備)を掛けた距離で判定するので、縮めると見えにくい相手の
     * 見つかり方が変わってしまう。
     */
    @Override
    protected AABB getTargetSearchArea(double targetDistance) {
        return super.getTargetSearchArea(baseRange + SporeInfectionDetection.searchBonus(mob));
    }

    /**
     * 親コンストラクタからも一度呼ばれる(その時点では {@code baseRange} が 0)が、結果は直後に
     * {@code targetConditions} ごと上書きされるので実害は無い。
     */
    @Override
    protected double getFollowDistance() {
        LivingEntity currentTarget = mob.getTarget();
        if (currentTarget != null) {
            return baseRange + SporeInfectionDetection.bonusFor(mob, currentTarget);
        }
        return baseRange;
    }
}
