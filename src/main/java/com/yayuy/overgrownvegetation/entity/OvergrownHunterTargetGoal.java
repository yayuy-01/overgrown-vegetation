package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.effect.SporeInfectionDetection;
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

    private boolean isValidTarget(LivingEntity candidate) {
        if (!selector.test(candidate)) {
            return false;
        }
        double allowed = baseRange + SporeInfectionDetection.bonusFor(mob, candidate);
        return mob.distanceToSqr(candidate) <= allowed * allowed;
    }

    @Override
    protected AABB getTargetSearchArea(double targetDistance) {
        return super.getTargetSearchArea(baseRange + SporeInfectionDetection.maxBonus());
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
