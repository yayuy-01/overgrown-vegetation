package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * グループ2の Mob が、対応するグループ3の捕食者({@link Predation})が近づいてきたら反対方向へ
 * 逃げるゴール。グループ2の Mob は反撃しない代わりに、見つかったら距離を取ろうとする。
 */
public class CritterAvoidPredatorGoal extends Goal {

    private static final double SEARCH_RADIUS = 12.0D;
    private static final float FLEE_SPEED = 1.5F;
    /**
     * 捕食者は見つかったのに逃げ先か経路が無かったあと、探し直すまで待つ tick
     * (2026-09-24 軽量化、報告 1659 行。仮決め 2026-09-24)。それまでは 2 tick ごとに箱の検索・逃げ先 2 回・
     * 経路探索をやり直していた。捕食者がいないときは待たない(近づかれたときの反応の速さはそのまま)。
     * 待つのは「逃げ先探し 2 回がどちらも null」と「createPath が null」の 2 つだけ(探索そのものの失敗。
     * {@code FleeCorruptionGoal} と同じ切り方)。逃げ先が捕食者寄りだったときは 1 点の乱数の外れにすぎず、
     * 次の評価で引き直すと通ることが多いので待たない(2026-09-24 レビュー指摘)。
     * 探索が失敗したあとの再挑戦だけが最大この分だけ遅れる。
     */
    private static final int FAILED_FLEE_RETRY_TICKS = 10;

    private final PathfinderMob mob;
    private final PathNavigation navigation;
    private LivingEntity predator;
    private Path fleePath;
    /** 失敗した逃げ先探しをやり直してよい {@code tickCount}({@link #FAILED_FLEE_RETRY_TICKS})。 */
    private int retryTick;

    public CritterAvoidPredatorGoal(PathfinderMob mob) {
        this.mob = mob;
        this.navigation = mob.getNavigation();
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // どの種の preys にも自分の種が載っていなければ、下の検索は必ず空になる(今の設計データでは
        // リトプレウラ・スペラエオプレウラ)。周りの Mob を全部なめる前にそこで打ち切る(結果は同じ)。
        if (!com.yayuy.overgrownvegetation.data.MobStats.hasAnyPredator(this.mob.getType())) {
            return false;
        }
        if (this.mob.tickCount < this.retryTick) {
            // 直前の逃げ先探しが失敗した。しばらく探し直さない(FAILED_FLEE_RETRY_TICKS)。
            return false;
        }
        List<Mob> predators = this.mob.level().getEntitiesOfClass(Mob.class,
                this.mob.getBoundingBox().inflate(SEARCH_RADIUS),
                candidate -> candidate.isAlive() && Predation.isPredatorOf(candidate, this.mob));
        if (predators.isEmpty()) {
            return false;
        }
        // 一番近い捕食者(同距離なら先に見つかった方)。stream().min() と同じ選び方を、
        // 毎 tick 呼ばれる場所なので Comparator/Optional の生成なしで行う。
        Mob nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (Mob candidate : predators) {
            double distSq = this.mob.distanceToSqr(candidate);
            if (distSq < nearestSq) {
                nearestSq = distSq;
                nearest = candidate;
            }
        }
        this.predator = nearest;
        if (this.predator == null) {
            return false;
        }
        Vec3 away = DefaultRandomPos.getPosAway(this.mob, 14, 7, this.predator.position());
        if (away == null) {
            away = DefaultRandomPos.getPosAway(this.mob, 10, 7, this.predator.position());
        }
        if (away == null) {
            // 2 回の逃げ先探し(乱数の候補 10 個ずつ、計 20 個)がどちらも全滅した = 探索そのものの失敗。
            this.retryTick = this.mob.tickCount + FAILED_FLEE_RETRY_TICKS;
            return false;
        }
        if (this.predator.distanceToSqr(away.x, away.y, away.z) < this.predator.distanceToSqr(this.mob)) {
            // 逃げ先は見つかったが、選ばれた 1 点がたまたま捕食者寄りだっただけ(上下にずれた捕食者や、
            // 追い詰められたときに多い)。次の評価(2 tick 後)は新しい乱数で引き直せば通ることが多いので、
            // ここでは待たない。待たせると逃げ出しが最大 8 tick 遅れて捕まりやすくなり、乱数の消費も
            // 変わる(2026-09-24 軽量化のレビュー指摘で、この分岐だけバックオフから外した)。
            return false;
        }
        this.fleePath = this.navigation.createPath(away.x, away.y, away.z, 0);
        if (this.fleePath == null) {
            this.retryTick = this.mob.tickCount + FAILED_FLEE_RETRY_TICKS;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.navigation.isDone()
                && this.predator != null
                && this.predator.isAlive()
                && this.mob.distanceToSqr(this.predator) < SEARCH_RADIUS * SEARCH_RADIUS * 1.6D;
    }

    @Override
    public void start() {
        this.navigation.moveTo(this.fleePath, FLEE_SPEED);
    }

    @Override
    public void stop() {
        this.predator = null;
        this.fleePath = null;
    }
}
