package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * <b>獲物の居場所を狩り手へ配る</b>(ユーザー要望 2026-09-15:
 * 「フィロセンスはグループ1〜3 の mob を探し出し、属している群れもしくは近くのアルボラや
 * スカトゥリウムに居場所を伝える」)。
 *
 * <h2>役割分担</h2>
 * グループ4 がグループ1〜3 を狩るようになったが、<b>狩って強くなれるのは
 * アルボラとスカトゥリウムだけ</b>({@link PredatorMob})。ほかのグループ4 は
 * その 2 種のために地ならしをする側に回る。これはフィロセンスぶんの地ならし:
 * <ul>
 *     <li>フィロセンスは索敵役なので、<b>自分では狩らず</b>見つけて渡す。</li>
 *     <li>渡す相手は<b>同じ群れの狩り手</b>({@link InvaderPack#packMatesNear})。
 *     群れに属していない個体は誰とでも組む({@link InvaderPackBond#shares})ので、
 *     「近くのアルボラやスカトゥリウム」も自然に含まれる。</li>
 *     <li>渡すのは<b>手の空いている狩り手</b>だけ。戦っている最中に別の獲物を押し付けない。</li>
 * </ul>
 *
 * <h2>プレイヤー向けの「視線」との違い</h2>
 * {@link PhyllosensInfluence} はプレイヤーを群れ全体(グループ1〜10)へ差し向ける戦略の仕掛けで、
 * 何百ブロックも届く。こちらは<b>グループ4 の狩り手だけ</b>に、<b>目の前の獲物</b>を渡す戦術の仕掛け。
 * 別物なので混ぜていない。
 */
public final class InvaderPreyIntel {

    /**
     * {@code scout} の周り {@code searchRange} から獲物を探し、群れの狩り手へ配る。
     *
     * @return 実際に獲物を渡した狩り手の数
     */
    public static int report(ServerLevel level, Mob scout, double searchRange) {
        if (searchRange <= 0.0D) {
            return 0;
        }
        List<Mob> hunters = hunters(scout);
        if (hunters.isEmpty()) {
            return 0;
        }
        List<LivingEntity> prey = level.getEntitiesOfClass(LivingEntity.class,
                scout.getBoundingBox().inflate(searchRange),
                candidate -> Predation.isInvaderPrey(scout, candidate));
        if (prey.isEmpty()) {
            return 0;
        }
        int told = 0;
        for (Mob hunter : hunters) {
            LivingEntity quarry = nearestPrey(hunter, prey, searchRange);
            // 渡す相手の足で届くか。深く潜っている獲物を渡すと、受け取った側が
            // 地表で睨み続ける —— アルボラならそこから坑道を掘り始める
            // (ユーザー報告 2026-09-20。もともとここが「地下の洞窟の相手」を配っていた)。
            if (quarry == null || !Predation.isWithinChaseDepth(hunter, quarry)) {
                continue;
            }
            hunter.setTarget(quarry);
            told++;
        }
        return told;
    }

    /**
     * 獲物を渡してよい仲間 —— <b>同じ群れの、ステータスが上がる 2 種</b>
     * ({@link PredatorMob} を実装しているアルボラとスカトゥリウム)のうち、いま手が空いている個体。
     */
    private static List<Mob> hunters(Mob scout) {
        return InvaderPack.packMatesNear(scout, InvaderPack.recruitRange()).stream()
                .filter(mob -> mob instanceof PredatorMob)
                .filter(mob -> {
                    LivingEntity held = mob.getTarget();
                    return held == null || !held.isAlive();
                })
                .toList();
    }

    /**
     * {@code hunter} から見ていちばん近い獲物。{@code range} より遠いものは渡さない ——
     * 見つけた場所が遠すぎると、狩り手が群れを離れて延々と歩くことになる。
     */
    @Nullable
    private static LivingEntity nearestPrey(Mob hunter, List<LivingEntity> prey, double range) {
        LivingEntity best = null;
        double bestSq = range * range;
        for (LivingEntity candidate : prey) {
            if (!candidate.isAlive() || !hunter.canAttack(candidate)
                    || !Predation.isLandTarget(hunter, candidate)) {
                continue;
            }
            double sq = hunter.distanceToSqr(candidate);
            if (sq <= bestSq) {
                bestSq = sq;
                best = candidate;
            }
        }
        return best;
    }

    /** 索敵役が獲物を探す半径(ブロック)。 */
    public static double searchRange() {
        return OvergrownVegetationConfig.COMMON.invaderPreyIntelRange.get();
    }

    /** 獲物を配り直す間隔(tick)。 */
    public static int interval() {
        return OvergrownVegetationConfig.COMMON.invaderPreyIntelIntervalTicks.get();
    }

    private InvaderPreyIntel() {
    }
}
