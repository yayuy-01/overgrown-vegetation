package com.yayuy.overgrownvegetation.swarm.goal;

import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 群れが<b>止まって全員で汚染を広げる</b>(SPREAD)ときの一員の Goal(ユーザー指定 2026-09-25「暇なら止まって全員で」)。
 * 優先度 3、MOVE + LOOK + JUMP。G4 の地上の種すべてに積む。
 *
 * <p>{@code canUse} = {@code canContinueToUse} = 群れが SPREAD。司令塔が配った<b>場所</b>({@link Swarm#spreadSpot}、
 * 汚染の場が重ならないよう {@code 2 × invaderCorruptionRadius} 以上離してある)へ歩き、着いたら<b>種ごとの動作</b>
 * ({@link Action}。エンティティが {@code createSpreadAction} で渡す)を始めて {@link Swarm#noteSpreadStarted}、
 * 汚し尽くしたら {@link Swarm#noteSpreadFinished}。始めた全員が終わる・600 tick・標的が出た、のどれかで群れは次へ進む。
 * <ul>
 *     <li>アルボラ … うずくまって汚す(個体の {@code InvaderSporeCrouchGoal} と同じ場・間隔。1 体ごとの間隔は無視)。</li>
 *     <li>スカトゥリウム … 座って汚す({@code ShielderSporeGoal} と同じ)。</li>
 *     <li>フィロセンス … 索敵({@code PhyllosensScoutGoal} と同じ動き。根を張るのはこのときだけ期待どおり)。</li>
 *     <li>スポロフィル … 立っているだけ(傘の胞子がいつも汚している)。始めたと報告しない(終わりを待たせない)。</li>
 * </ul>
 * 場所をもらえなかった一員はその場に立つ。場所へ {@value #WALK_GIVE_UP_TICKS} tick で着けなければ、その場で始める。
 * 歩き方は後続と同じ直の舵取り(場所は群れのすぐまわりの読み込み済みの地面なので、経路は引かない)。
 */
public class SwarmSpreadGoal extends Goal {

    /** 種ごとの「その場で汚す」動作。1 回の SPREAD につき 1 つ作る。 */
    public interface Action {
        /**
         * この場所ではやらない(始めたとも報告せずに立っている)か。うずくまり・座り込みは足元が既に汚れていればやらない
         * (ユーザー指示 2026-09-14「汚れていない土地でだけ」。場所を配ったあと、隣のスポロフィルの傘が先に汚していることがある)。
         */
        default boolean shouldSkip() {
            return false;
        }

        /** 今始められるか(接地している・水に入っていない・ほかの行動中でない)。 */
        boolean canStart();

        /** 始める(行動状態・音・汚染の場を作る)。 */
        void start(ServerLevel level);

        /** 毎 tick(動かない・粒子・感染・汚染)。 */
        void tick(ServerLevel level);

        /** 汚し尽くした(索敵なら時間いっぱい続けた)。 */
        boolean isFinished();

        /**
         * 終える。{@code completed} は汚し尽くして終わったか(中断なら false。個体のうずくまりと同じく、芽吹きは完了のときだけ)。
         */
        void stop(ServerLevel level, boolean completed);
    }

    /** 場所に「着いた」距離(ブロック、水平)。 */
    private static final double ARRIVE = 1.0D;
    /** 場所へ歩いてよい上限(tick)。着けなければその場で始める。仮決め 2026-09-25。 */
    private static final int WALK_GIVE_UP_TICKS = 200;
    /** 歩く速さの倍率。 */
    private static final double SPEED = 1.0D;

    private enum Phase { WALK, ACT, DONE }

    private final Mob mob;
    /** 種ごとの動作を作る。null を返す種(スポロフィル)は立っているだけ。 */
    private final Supplier<Action> actions;

    private Phase phase = Phase.WALK;
    @Nullable
    private Action action;
    private int walkTicks;
    /**
     * 同じ SPREAD の中で動作を 2 回始めないための控え(群れ ID と場所)。途中で上の Goal に割り込まれて
     * 戻ってきたとき、もう終えた場所ならそのまま立つ。
     */
    @Nullable
    private UUID doneSwarm;
    @Nullable
    private BlockPos doneSpot;

    public SwarmSpreadGoal(Mob mob, Supplier<Action> actions) {
        this.mob = mob;
        this.actions = actions;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return spreading(SwarmManager.get(this.mob));
    }

    @Override
    public boolean canContinueToUse() {
        return spreading(SwarmManager.get(this.mob));
    }

    private boolean spreading(@Nullable Swarm swarm) {
        return swarm != null && swarm.kind() == Swarm.Kind.GROUND && swarm.isSpreading();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.mob.getNavigation().stop();
        this.action = null;
        this.walkTicks = 0;
        Swarm swarm = SwarmManager.get(this.mob);
        BlockPos spot = swarm != null ? swarm.spreadSpot(this.mob) : null;
        boolean alreadyDone = swarm != null && swarm.id().equals(this.doneSwarm)
                && spot != null && spot.equals(this.doneSpot);
        this.phase = alreadyDone || spot == null ? Phase.DONE : Phase.WALK;
    }

    @Override
    public void stop() {
        Swarm swarm = SwarmManager.get(this.mob);
        if (this.action != null) {
            if (this.mob.level() instanceof ServerLevel level) {
                this.action.stop(level, false);
            }
            this.action = null;
            // 途中で止めた(標的が出た・上の Goal に取られた)。群れを待たせないよう終わったと報告する
            // (SPREAD が続いていれば数えられ、終わっていれば群れの側で捨てられる)。
            if (swarm != null) {
                swarm.noteSpreadFinished(this.mob);
            }
            rememberDone(swarm);
        }
        this.phase = Phase.WALK;
    }

    @Override
    public void tick() {
        Swarm swarm = SwarmManager.get(this.mob);
        if (swarm == null || !(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
        switch (this.phase) {
            case WALK -> tickWalk(swarm, level);
            case ACT -> tickAct(swarm, level);
            default -> standStill();
        }
    }

    private void tickWalk(Swarm swarm, ServerLevel level) {
        BlockPos spot = swarm.spreadSpot(this.mob);
        if (spot == null) {
            this.phase = Phase.DONE;
            return;
        }
        Vec3 want = Vec3.atBottomCenterOf(spot);
        boolean arrived = SwarmGoals.horizontalSq(this.mob.position(), want) <= ARRIVE * ARRIVE;
        if (!arrived && ++this.walkTicks <= WALK_GIVE_UP_TICKS) {
            PathNavigation nav = this.mob.getNavigation();
            if (!nav.isDone()) {
                nav.stop();
            }
            this.mob.getMoveControl().setWantedPosition(want.x, want.y, want.z, SPEED);
            if (this.mob.horizontalCollision && this.mob.onGround()) {
                this.mob.getJumpControl().jump();
            }
            return;
        }
        // 着いた(または着けなかったのでその場で)。種の動作を始める。
        Action created = this.actions.get();
        if (created == null || created.shouldSkip()) {
            // 立っているだけの種(スポロフィル)・足元が既に汚れている。終わりを待たせないよう始めたとも報告しない。
            rememberDone(swarm);
            this.phase = Phase.DONE;
            return;
        }
        if (!created.canStart()) {
            // 水の中・ほかの行動中など。少し待ってまた試す(歩く数えは上限のまま)。
            return;
        }
        this.action = created;
        created.start(level);
        swarm.noteSpreadStarted(this.mob);
        this.phase = Phase.ACT;
    }

    private void tickAct(Swarm swarm, ServerLevel level) {
        Action current = this.action;
        if (current == null) {
            this.phase = Phase.DONE;
            return;
        }
        current.tick(level);
        if (current.isFinished()) {
            current.stop(level, true);
            this.action = null;
            swarm.noteSpreadFinished(this.mob);
            rememberDone(swarm);
            this.phase = Phase.DONE;
        }
    }

    private void standStill() {
        PathNavigation nav = this.mob.getNavigation();
        if (!nav.isDone()) {
            nav.stop();
        }
    }

    private void rememberDone(@Nullable Swarm swarm) {
        if (swarm == null) {
            return;
        }
        this.doneSwarm = swarm.id();
        this.doneSpot = swarm.spreadSpot(this.mob);
    }
}
