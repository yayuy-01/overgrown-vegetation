package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionProximity;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * フェーズ2以降、グループ1({@link MobGroup#GROUP_1})の Mob を「過繁茂バイオーム・汚染ブロックから
 * 離れる」方向へ動かす Goal。汚染地形の縁の外側で他の Mob を襲わせ、倒した相手を汚染死体/汚染シダに
 * 変えさせることで、汚染を外へ押し広げる狙い。
 *
 * <p>{@link com.yayuy.overgrownvegetation.effect.FleeCorruptionGoal}(胞子感染中の一般 Mob 用)と
 * ほぼ同じ探索ロジックだが、発動条件が「フェーズ2以降 かつ グループ1 かつ
 * {@code phase2Group1FleeCorruption} が有効」で、離れる対象に過繁茂バイオームそのものも含める点が違う。
 *
 * <p>{@link Group1PhaseGoalInjector} が全グループ1の {@link PathfinderMob} に優先度1で仕込む
 * (条件を満たさない間は {@link #canUse()} が false を返すだけ)。優先度1なので、条件を満たす間は
 * {@code ParasitizedTargetGoal} の近接追跡(優先度2)より優先され、まず汚染地形の外へ出てから
 * 獲物を襲う動きになる。
 *
 * <p>ただし<b>既に標的を持っている(＝今まさに何かを襲っている / 反撃している)間は逃げない</b> —
 * これが無いと、過繁茂バイオームの中(周囲すべてが汚染地形)ではこの Goal が常時発動し、目の前の
 * 敵対 Mob を無視してひたすらバイオームの外へ歩き続けてしまう(ユーザー報告 2026-09-08)。
 * 標的を倒す/見失うと再びこの Goal が有効になり、汚染地形から離れる動きに戻る。
 */
public final class Group1FleeCorruptionGoal extends Goal {

    private static final int SEARCH_RADIUS = 24;
    private static final int SEARCH_VERTICAL_RANGE = 7;
    private static final int ESCAPE_ATTEMPTS = 8;
    private static final double SPEED_MODIFIER = 1.1D;
    /**
     * 逃げ先か経路が見つからなかったあと、探し直すまで待つ tick(2026-09-24 軽量化、報告 1033 行。仮決め 2026-09-24)。
     * 空中にいる・周りに立てる場所が無いときは、次の評価(2 tick 後)でも同じ理由で失敗しやすいのに、
     * 足元の汚染の球走査・着地点の候補 8 回・離脱方向・経路探索を毎回やり直していた。逃げ始めが最大この分だけ遅れる。
     */
    private static final int FAILED_SEARCH_RETRY_TICKS = 10;
    /** 周りに繁茂系でないチャンクが無かったあと、見直すまで待つ tick(5 秒。仮決め 2026-10-06)。 */
    private static final int NO_EXIT_RETRY_TICKS = 100;

    private final PathfinderMob mob;
    private final PathNavigation pathNav;
    private Path path;
    /** 失敗した探索をやり直してよい {@code tickCount}({@link #FAILED_SEARCH_RETRY_TICKS})。 */
    private int retryTick;

    public Group1FleeCorruptionGoal(PathfinderMob mob) {
        this.mob = mob;
        this.pathNav = mob.getNavigation();
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** そのフェーズでグループ1が汚染地形から離れる挙動が有効か({@code phases.json})。 */
    private static boolean fleeCorruptionActive(ServerLevel level) {
        int phase = PhaseData.get(level).getPhase();
        return com.yayuy.overgrownvegetation.data.DesignData.phases().group1FleeCorruption(phase);
    }

    @Override
    public boolean canUse() {
        if (!(mob.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        // 何かを襲っている / 反撃している間は逃げない(クラス javadoc 参照)。
        // いちばん安いこの判定を先に見る(2026-09-24 軽量化、報告 1033 行)。下のグループ・フェーズの判定と
        // どれも副作用の無い判定の AND なので、並べ替えても結果は同じ。グループの判定は消さない ——
        // 所属はタグで決まり、データパックの /reload で変わりうるので、注入時の判定だけでは足りない。
        if (mob.getTarget() != null && mob.getTarget().isAlive()) {
            return false;
        }
        if (MobGroup.of(mob) != MobGroup.GROUP_1) {
            return false;
        }
        if (!fleeCorruptionActive(serverLevel)) {
            return false;
        }
        if (mob.tickCount < retryTick) {
            // 直前の探索が失敗した。しばらく探し直さない(FAILED_SEARCH_RETRY_TICKS)。
            return false;
        }
        if (!CorruptionProximity.hasOpenChunkWithin(serverLevel, mob.blockPosition(), SEARCH_RADIUS)) {
            // 逃げ先の候補が届く範囲に繁茂系でないチャンクが無い(過繁茂域の奥)。探しても必ず失敗し、ランダムな方向へ
            // 歩くだけなので逃げない(2026-10-06 ユーザー指定「過繁茂ではないチャンクが近くにある時だけ離れる」)。
            // 縁に近づいた Mob は今までどおり外へ逃げる。しばらく見直さない。
            retryTick = mob.tickCount + NO_EXIT_RETRY_TICKS;
            return false;
        }
        if (isClear(mob.blockPosition())) {
            return false;
        }

        Vec3 escapePos = findEscapePos();
        if (escapePos == null) {
            retryTick = mob.tickCount + FAILED_SEARCH_RETRY_TICKS;
            return false;
        }
        path = pathNav.createPath(escapePos.x, escapePos.y, escapePos.z, 0);
        if (path == null) {
            retryTick = mob.tickCount + FAILED_SEARCH_RETRY_TICKS;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !pathNav.isDone()
                && (mob.getTarget() == null || !mob.getTarget().isAlive())
                && mob.level() instanceof ServerLevel serverLevel
                && fleeCorruptionActive(serverLevel);
    }

    @Override
    public void start() {
        pathNav.moveTo(path, SPEED_MODIFIER);
    }

    @Override
    public void stop() {
        path = null;
    }

    private Vec3 findEscapePos() {
        for (int i = 0; i < ESCAPE_ATTEMPTS; i++) {
            Vec3 candidate = LandRandomPos.getPos(mob, SEARCH_RADIUS, SEARCH_VERTICAL_RANGE);
            if (candidate == null) {
                continue;
            }
            BlockPos support = BlockPos.containing(candidate.x, candidate.y - 1.0D, candidate.z);
            if (isClear(support)) {
                return candidate;
            }
        }
        // 周囲一帯が汚染地形で候補が無い場合は、とにかく今の場所から離れる方向へ。
        return DefaultRandomPos.getPosAway(mob, SEARCH_RADIUS, SEARCH_VERTICAL_RANGE, mob.position());
    }

    /**
     * {@code center} が「過繁茂バイオームの外」かつ「半径 {@code infectionFleeMinDistance} の球内に
     * 汚染ブロックが 1 つも無い」か。
     */
    private boolean isClear(BlockPos center) {
        Level level = mob.level();
        if (ModBiomes.isOvergrownLike(level.getBiome(center))) {
            return false;
        }
        int distance = OvergrownVegetationConfig.COMMON.infectionFleeMinDistance.get();
        if (distance <= 0) {
            return true;
        }
        // 球走査の本体は CorruptionProximity(中心から近い順 + チャンクキャッシュ付きの読み。判定結果は同じ)。
        return !CorruptionProximity.hasCorruptedWithin(level, center, distance);
    }
}
