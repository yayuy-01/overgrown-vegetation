package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionProximity;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
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
 * 「胞子感染」({@link ModMobEffects#SPORE_INFECTION})にかかっている間、汚染ブロック
 * ({@code #overgrown_vegetation:corrupted})から {@link OvergrownVegetationConfig.Common#infectionFleeMinDistance}
 * (既定 5 ブロック)以上離れるまで、非汚染ブロックの上を目指して逃げ続ける Goal。
 * {@link SporeInfectionGoalInjector} が全ての {@link PathfinderMob} に自動で追加する
 * (感染していない・既に十分離れている間は {@link #canUse} が false を返すだけで、普段は何もしない)。
 *
 * <p>探索は {@link LandRandomPos#getPos} で周囲のランダムな着地点を何度か試し、その周囲
 * (球状、半径 {@link OvergrownVegetationConfig.Common#infectionFleeMinDistance})に汚染ブロックが
 * 1つも無い候補が見つかればそこを目指す。周囲一帯が汚染だらけで候補が見つからない場合は
 * {@link DefaultRandomPos#getPosAway} で「今いる場所からとにかく離れる方向」へ向かわせ、
 * これを繰り返すことで少しずつ汚染域の外(必要な距離の外)へ抜け出させる。
 */
public class FleeCorruptionGoal extends Goal {

    /** 着地点候補を探す水平半径。 */
    private static final int SEARCH_RADIUS = 24;
    /** 着地点候補を探す鉛直方向の範囲。 */
    private static final int SEARCH_VERTICAL_RANGE = 7;
    /** 「必要な距離だけ汚染ブロックから離れた着地点」を何回まで試すか。尽きたら離脱方向へのフォールバックに切り替える。 */
    private static final int ESCAPE_ATTEMPTS = 8;
    private static final double SPEED_MODIFIER = 1.0D;

    private final PathfinderMob mob;
    private final PathNavigation pathNav;
    private Path path;

    public FleeCorruptionGoal(PathfinderMob mob) {
        this.mob = mob;
        this.pathNav = mob.getNavigation();
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!mob.hasEffect(ModMobEffects.SPORE_INFECTION)) {
            return false;
        }
        if (!(mob.level() instanceof ServerLevel)) {
            return false;
        }
        if (hasClearance(mob.blockPosition())) {
            // 既に汚染ブロックから十分離れているなら、わざわざ逃げ回る必要は無い
            return false;
        }

        Vec3 escapePos = findEscapePos();
        if (escapePos == null) {
            return false;
        }
        path = pathNav.createPath(escapePos.x, escapePos.y, escapePos.z, 0);
        return path != null;
    }

    @Override
    public boolean canContinueToUse() {
        return mob.hasEffect(ModMobEffects.SPORE_INFECTION) && !pathNav.isDone();
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
            if (hasClearance(support)) {
                return candidate;
            }
        }
        // 周囲一帯が汚染済みで必要な距離を確保できる候補が見つからない場合、
        // とりあえず今の場所から離れる方向へ向かう(これを繰り返すことで少しずつ外へ抜け出す)
        return DefaultRandomPos.getPosAway(mob, SEARCH_RADIUS, SEARCH_VERTICAL_RANGE, mob.position());
    }

    /**
     * {@code center} が繁茂系バイオーム(過繫茂・繫茂、陸/海/川)の<b>外</b>で、かつ
     * {@code center} を中心とした半径 {@code infectionFleeMinDistance} の球の中に汚染ブロックが1つも無いか。
     * (胞子感染した Mob は「過繫茂したバイオーム、繫茂したバイオーム類から離れる」— ユーザー仕様 2026-09-10)
     */
    private boolean hasClearance(BlockPos center) {
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
