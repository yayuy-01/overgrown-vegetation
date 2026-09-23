package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.nest.NestCrew;
import com.yayuy.overgrownvegetation.nest.NestRoute;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * 建設グループのアルボラが<b>実際に巣を掘る</b>(2026-09-20、ユーザー指定の折衷案)。
 *
 * <p>掘る場所({@link NestCrew#faceFor})は {@code nest/NestGrowthQueue} が毎 tick 配る。
 * この Goal は<b>そこまで潜って、着いたら掘る仕草をする</b>だけ —— ブロックを実際に置き換えるのは
 * 掘削キューの側で、<b>切羽にアルボラが居るあいだしか進まない</b>。
 *
 * <h2>坑道を通って潜る(2026-09-20 の作り直し)</h2>
 * <p>前の版は切羽を<b>直線で</b>指し、遠ければ 12 マス先に中継点を置いていた。
 * 切羽は地下にあるので、その中継点は<b>岩の中</b>に落ちる。経路が出ないまま地上をうろつき、
 * <b>地上に立ったまま巣が出来上がっていく</b>という絵になっていた(ユーザー報告)。
 *
 * <p>いまは掘りかけの巣の<b>道しるべの木</b>({@link NestCrew#route})を辿る。
 * まだ掘っていない先の道しるべは使わない —— 切羽のひとつ手前までしか辿らないので、
 * 通る道は<b>必ず掘り終わっている</b>。巣の外に居るあいだはまず坑口を目指す。
 *
 * <h2>「着いた」の判定にも高さを見る</h2>
 * <p>水平距離だけで見ると、浅い切羽の真上の地面に立っただけで着いた扱いになる。
 * {@link NestCrew#REACH_Y} マス以内の高さに居ることも求める({@code countDiggers} と同じ規則)。
 */
public class NestDigGoal extends Goal {

    private static final double SPEED = 1.0D;
    /** 掘る仕草に入る水平距離。 */
    private static final double SWING = 4.0D;
    /**
     * 掘る仕草から抜ける水平距離。入るときより<b>広く取る</b>(2026-09-21)。
     * 同じ値にすると、隣の掘り手に押されて 1 マス動いただけで歩き直しになり、
     * 押し合いが収まらない —— 「一か所に集まってとどまる」の見た目になる。
     */
    private static final double SWING_LEAVE = 7.0D;
    /** 道しるべに乗っているとみなす距離。これより遠ければ、まず坑口へ向かう。 */
    private static final double ON_ROUTE = 10.0D;
    /** 1 つの道しるべで手間取ってよい上限(tick)。越えたら次へ飛ばす。 */
    private static final int WAY_LIMIT = 60;
    /** 経路を引き直す間隔(tick)。 */
    private static final int REPATH_INTERVAL = 10;

    private final AbstractOvergrownInvaderEntity mob;
    private IntList steps = IntList.of();
    private int cursor;
    private int waited;
    private int repathIn;
    @Nullable
    private BlockPos plannedFor;

    public NestDigGoal(AbstractOvergrownInvaderEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.getTarget() == null && NestCrew.faceFor(mob) != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        steps = IntList.of();
        cursor = 0;
        waited = 0;
        repathIn = 0;
        plannedFor = null;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        steps = IntList.of();
        plannedFor = null;
        if (mob.getActionState() == AbstractOvergrownInvaderEntity.STATE_DIG) {
            mob.setActionState(AbstractOvergrownInvaderEntity.STATE_IDLE);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        BlockPos face = NestCrew.faceFor(mob);
        if (face == null) {
            return;
        }
        mob.getLookControl().setLookAt(face.getX() + 0.5D, face.getY() + 0.5D, face.getZ() + 0.5D);
        if (atFace(face, mob.getActionState() == AbstractOvergrownInvaderEntity.STATE_DIG)) {
            // 着いた。掘る仕草(attack のモーションを流用)に入って足を止める
            mob.getNavigation().stop();
            if (mob.getActionState() == AbstractOvergrownInvaderEntity.STATE_IDLE) {
                mob.setActionState(AbstractOvergrownInvaderEntity.STATE_DIG);
            }
            return;
        }
        if (mob.getActionState() == AbstractOvergrownInvaderEntity.STATE_DIG) {
            mob.setActionState(AbstractOvergrownInvaderEntity.STATE_IDLE);
        }

        List<BlockPos> route = NestCrew.route();
        if (route.isEmpty()) {
            walkDirect(face);                         // 道しるべが無い巣。素直に指す
            return;
        }
        int mouth = 0;
        int at = NestRoute.nearestIndex(route, mob.blockPosition());
        if (at < 0 || route.get(at).distSqr(mob.blockPosition()) > ON_ROUTE * ON_ROUTE) {
            walkDirect(route.get(mouth));             // まだ巣の外。まず坑口へ
            return;
        }
        // 切羽のひとつ手前の道しるべまで潜る。<b>そこまでは必ず掘り終わっている</b>。
        int goal = NestRoute.nearestIndex(route, face);
        if (!face.equals(plannedFor) || steps.isEmpty()) {
            steps = NestRoute.between(NestCrew.routeParents(), at, goal);
            cursor = 0;
            waited = 0;
            plannedFor = face;
        }
        if (cursor >= steps.size()) {
            // 道しるべの先は掘りながら進む。<b>自分の立ち位置</b>を指すので団子にならない。
            BlockPos stand = NestCrew.standFor(mob);
            walkDirect(stand != null ? stand : face);
            return;
        }
        int index = steps.getInt(cursor);
        if (index < 0 || index >= route.size()) {
            cursor++;
            return;
        }
        BlockPos way = route.get(index);
        if (NestRoute.reached(mob, way)) {
            cursor++;
            waited = 0;
            mob.getNavigation().stop();
            return;
        }
        // 1 点で手間取ったら飛ばす。坑道は繋がっているので次の点へは歩ける。
        if (++waited > WAY_LIMIT) {
            waited = 0;
            cursor++;
            mob.getNavigation().stop();
            return;
        }
        walkDirect(way);
    }

    /**
     * 切羽に着いているか。水平距離と<b>高さ</b>の両方を見る。
     * 既に掘っているなら広めに見る({@link #SWING_LEAVE}) —— 押し合いで少し動いただけで
     * 歩き直さないようにするため。
     */
    private boolean atFace(BlockPos face, boolean digging) {
        double reach = digging ? SWING_LEAVE : SWING;
        double dx = mob.getX() - (face.getX() + 0.5D);
        double dz = mob.getZ() - (face.getZ() + 0.5D);
        return dx * dx + dz * dz <= reach * reach
                && Math.abs(mob.getBlockY() - face.getY()) <= NestCrew.REACH_Y;
    }

    /** そこへ直に歩く。1 区間は短いので、壁のぼり用の経路探索でも経路が出る。 */
    private void walkDirect(BlockPos goal) {
        if (!mob.getNavigation().isDone() || --repathIn > 0) {
            return;
        }
        repathIn = REPATH_INTERVAL;
        mob.getNavigation().moveTo(goal.getX() + 0.5D, goal.getY(), goal.getZ() + 0.5D, SPEED);
    }
}
