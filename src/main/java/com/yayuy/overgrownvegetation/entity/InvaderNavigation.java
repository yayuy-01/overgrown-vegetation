package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * グループ4(拡大種)の<b>経路探索</b>。バニラの経路探索に 4 つ手を入れてある
 * (ユーザー報告 2026-09-17:「壁につっかえって動けなくなる個体があまりにも多い」
 * 「目標にしている敵への道を探す行動があまり賢くない」)。
 *
 * <h2>1. クモ由来の「経路を無視した直進」を止める(壁に刺さる最大の原因)</h2>
 * 壁を登る種({@link ArboraEntity})はクモと同じ {@link WallClimberNavigation} を使っていた。
 * これには<b>隠れた直進機能</b>がある:
 * <pre>{@code
 * public Path createPath(BlockPos pos, int accuracy) {
 *     this.pathToPosition = pos;          // 経路が引けなくても記録される
 *     return super.createPath(pos, accuracy);
 * }
 * public void tick() {
 *     if (!this.isDone()) { super.tick(); }
 *     else if (this.pathToPosition != null) {
 *         this.mob.getMoveControl().setWantedPosition(...);   // 経路を無視して直進
 *     }
 * }
 * }</pre>
 * クモは登れるので成立する設計だが、この MOD では次の 3 つが重なって<b>詰みになる</b>。
 * <ol>
 *     <li>{@code PathNavigation#stop()} は {@code path} しか消さない ——
 *     {@code pathToPosition} は残るので、直進は<b>その地点に近づくまで永久に続く</b>。</li>
 *     <li>{@link InvaderPackGoal} は 30 tick ごと、{@link InvaderAdvanceGoal} は 10 tick ごとに
 *     {@code createPath} を呼ぶ。失敗するたびに直進先が更新される。</li>
 *     <li>直進中は {@code isDone()} が <b>true</b>。
 *     {@link AbstractOvergrownInvaderEntity} の詰まり検知は標的を持たない個体について
 *     「経路が無い = 歩いていない」とみなして見逃すので、<b>永久に壁を押し続ける</b>。
 *     しかも壁登りの判定({@code detectWallClimb})も「標的が無く経路も無い」個体は弾くので、
 *     登って抜けることもできない。</li>
 * </ol>
 * ここでは {@link Climber#tick} で直進を握り潰し、{@link Climber#moveTo(Entity, double)} が
 * <b>経路を引けなかったことを正直に false で返す</b>ようにしてある。
 * 壁の上の相手へ体を押し当てて登るきっかけは、
 * {@link AbstractOvergrownInvaderEntity} 側が<b>時間制限つきで</b>作り直している。
 *
 * <h2>2. 経路探索の射程を索敵距離から切り離す</h2>
 * バニラは探索範囲も打ち切り距離も {@code FOLLOW_RANGE} で決める
 * ({@code PathNavigation#createPath} → {@code PathFinder#findPath} の {@code maxRange})。
 * アルボラは 32 なので、<b>32 ブロック歩いて届かない回り道は最初から探さない</b>。
 * 索敵距離を伸ばすと敵対範囲まで広がってしまうので、ここでは
 * <b>経路探索にだけ</b> {@code invaderPathRange}(既定 48)を使う。
 *
 * <h2>3. 探索の予算を増やす</h2>
 * 展開してよいノード数は {@code FOLLOW_RANGE × 16} = 512。幅 2.0 のスポロフィルは 3×3 の枡目を占めるので
 * 通れる道が減り、洞窟や谷では 512 で足りない。{@code invaderPathBudgetMultiplier}(既定 3)倍する。
 *
 * <h2>4. 「どこへも行かない経路」を捨てる</h2>
 * バニラの {@code PathFinder} は目的地へ届かないとき<b>いちばん近いノードまでの部分経路</b>を返す
 * ({@code reachesTarget = false})。呼び出し側はこれを成功と区別しないので、
 * 壁の手前まで歩いて止まり、また同じ経路を引き直す —— これが「道を探すのが賢くない」の正体。
 * ここでは<b>ほとんど進まない部分経路を {@code null} に落とし</b>、
 * 落とした回数を {@link AbstractOvergrownInvaderEntity#notePathFailure} に伝える。
 * 一定回数続けば「詰まった」印が立ち、{@link InvaderUnstickGoal} が回り道を探す。
 */
public final class InvaderNavigation {

    /**
     * 部分経路が「進んだ」と認めるのに要る距離(ブロック)。
     * これ未満しか動けない経路は無かったことにする。
     */
    private static final double MIN_PROGRESS = 3.0D;

    /** {@code climber} が true なら壁を登れる種(クモと同じ経路探索)。 */
    public static PathNavigation create(AbstractOvergrownInvaderEntity mob, Level level, boolean climber) {
        return climber ? new Climber(mob, level) : new Walker(mob, level);
    }

    /** 経路探索にだけ使う射程。索敵距離({@code FOLLOW_RANGE})より短くはならない。 */
    static float pathRange(Mob mob) {
        double follow = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        return (float) Math.max(follow, OvergrownVegetationConfig.COMMON.invaderPathRange.get());
    }

    /** 展開してよいノード数の倍率。 */
    static float budget() {
        return OvergrownVegetationConfig.COMMON.invaderPathBudgetMultiplier.get().floatValue();
    }

    /**
     * 引けた経路を採点する。使えないものは {@code null} にして、
     * その回数を本体へ伝える({@link AbstractOvergrownInvaderEntity#notePathFailure})。
     */
    @Nullable
    static Path judge(AbstractOvergrownInvaderEntity mob, @Nullable Path path) {
        if (path == null || path.getNodeCount() == 0) {
            mob.notePathFailure();
            return null;
        }
        if (path.canReach()) {
            mob.notePathSuccess();
            return path;
        }
        // 届かない経路。少しでも前へ進めるなら使う(近づいた先で改めて探し直せる)。
        Vec3 end = path.getEntityPosAtNode(mob, path.getNodeCount() - 1);
        if (mob.position().closerThan(end, MIN_PROGRESS)) {
            mob.notePathFailure();
            return null;
        }
        return path;
    }

    /** 壁を登る種(アルボラ)。 */
    private static final class Climber extends WallClimberNavigation {

        private final AbstractOvergrownInvaderEntity invader;

        Climber(AbstractOvergrownInvaderEntity mob, Level level) {
            super(mob, level);
            this.invader = mob;
        }

        /**
         * <b>壁を登る辺を持った</b>経路探索({@link ClimbNodeEvaluator}、2026-09-20)。
         * これで A* が「壁を登って越える」経路を自分で見つけるようになり、
         * 崖の下で階段状の場所を探して遠回りしなくなる(ユーザー要望)。
         */
        @Override
        protected PathFinder createPathFinder(int maxVisitedNodes) {
            this.nodeEvaluator = new ClimbNodeEvaluator();
            this.nodeEvaluator.setCanPassDoors(true);
            return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
        }

        @Override
        protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy) {
            this.setMaxVisitedNodesMultiplier(budget());
            return judge(this.invader,
                    super.createPath(targets, regionOffset, offsetUpward, accuracy, pathRange(this.mob)));
        }

        /** 経路が引けなければ<b>false</b>。バニラは「直進するから true」と嘘をつく。 */
        @Override
        public boolean moveTo(Entity entity, double speed) {
            Path path = this.createPath(entity, 1);
            return path != null && this.moveTo(path, speed);
        }

        /** 経路が無いときの直進(クモの機能)をしない。 */
        @Override
        public void tick() {
            if (this.isDone()) {
                return;
            }
            super.tick();
        }
    }

    /** 壁を登らない種(フィロセンス・スポロフィル)。 */
    private static final class Walker extends GroundPathNavigation {

        private final AbstractOvergrownInvaderEntity invader;

        Walker(AbstractOvergrownInvaderEntity mob, Level level) {
            super(mob, level);
            this.invader = mob;
        }

        @Override
        protected PathFinder createPathFinder(int maxVisitedNodes) {
            this.nodeEvaluator = new OvergrownNodeEvaluator();
            this.nodeEvaluator.setCanPassDoors(true);
            return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
        }

        @Override
        protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy) {
            this.setMaxVisitedNodesMultiplier(budget());
            return judge(this.invader,
                    super.createPath(targets, regionOffset, offsetUpward, accuracy, pathRange(this.mob)));
        }
    }

    private InvaderNavigation() {
    }
}
