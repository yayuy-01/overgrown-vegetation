package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 根付き(後で苗床も)に決まった段階 0 が、森から離れて歩く(2026-11-13、司令塔 第 1 段の区切り 2。D-15)。
 *
 * <p>遠くまでの経路は探さない: 森から離れる向き({@link ForestEdge#away})へ 24 ブロック先を目あてに、歩ける所を近くから 1 つ選んで
 * (バニラの {@code LandRandomPos})そこまで歩く、を繰り返す。着いたか・時間切れかは {@link G1Fate} が見る。
 * 読み込まれている間しか動かないので、止まった個体は次に読み込まれたとき続きを歩く。
 *
 * <p>歩いている間は獲物を追わない(この Goal が {@code MOVE} を握り、3 段の 1 撃より先。標的も外す)【仮】。
 * ただし<b>殴られたら殴り返す</b>(11-13 ユーザー決定。{@link G1Fate#retaliating}): 殴ってきた相手が生きていて 10 秒以内の間は退く。
 * 歩いていく段階 0 そのものが前兆で、途中で倒せば止まる(D-15)。
 */
public final class G1WalkAwayGoal extends Goal {

    /** 目あてを選び直す間(tick)と、着いてしまったときに待つ最短の間。 */
    private static final int REPATH = 60;
    private static final int REPATH_MIN = 10;
    /** 歩く速さ(移動速度の倍率)。段階 0 のうろつきは 0.8。 */
    private static final double SPEED = 1.0D;

    private final AbstractParasitizedMonster mob;
    private int repath;

    public G1WalkAwayGoal(AbstractParasitizedMonster mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        G1Fate fate = mob.fate();
        // 殴られたら殴り返す(その間はこの Goal が退き、3 段の 1 撃が殴ってきた相手を追う)。済んだらまた歩く
        return fate != null && fate.walkingAway() && !G1Fate.retaliating(mob);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (mob.getTarget() != null) mob.setTarget(null);
        repath--;
        if (repath > 0 && !(mob.getNavigation().isDone() && repath <= REPATH - REPATH_MIN)) return;
        repath = REPATH;
        if (!(mob.level() instanceof ServerLevel level)) return;
        Vec3 dir = mob.fate().settleDirection(level);
        Vec3 to = dir == null ? null : LandRandomPos.getPosTowards(mob, 14, 7, mob.position().add(dir.scale(24.0D)));
        if (to == null) to = LandRandomPos.getPos(mob, 10, 7);
        if (to != null) mob.getNavigation().moveTo(to.x, to.y, to.z, SPEED);
    }
}
