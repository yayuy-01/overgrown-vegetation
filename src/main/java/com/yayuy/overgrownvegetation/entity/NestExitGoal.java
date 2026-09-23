package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.nest.NestData;
import com.yayuy.overgrownvegetation.nest.NestRoute;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/**
 * 巣から<b>外へ歩いて出る</b>(2026-09-20)。小規模レイドの増援、狩りに出る住人、そして
 * <b>巣の外の獲物を見つけた個体</b>が使う。
 *
 * <p>道しるべの木({@link NestRoute})の<b>親をたぐって</b>坑口まで 1 点ずつ歩く。
 * 1 区間は 3 マスほどなので経路探索がまず失敗しない。
 *
 * <p>地上の獲物を直に指すと経路が出ず、壁を登って天井に張り付いたまま暴れる ——
 * ユーザー報告(2026-09-20)の直接の原因なので、<b>この Goal は狩りの Goal より
 * 優先度を高くしておくこと</b>(優先度 2。近接攻撃は 4、レイドの追跡は 3)。
 *
 * <p>作り直し前は道しるべが<b>幹の 1 本線</b>しかなく、枝や側室に居る個体はそこまでが遠いので
 * 「12 マス先の中継点」を直線で置いていた。<b>その中継点が岩の中に落ちる</b>ので経路が出ず、
 * 結局直進して天井に張り付く —— 症状が消えなかった本当の原因はここだった。
 */
public class NestExitGoal extends Goal {

    /** 道しるべ 1 区間ぶん歩く速さ。 */
    private static final double SPEED = 1.15D;
    /** 1 つの道しるべに手間取ってよい上限(tick)。越えたら次へ飛ばす。 */
    private static final int WAY_LIMIT = 60;
    /** 続けて経路が出なかった回数の上限。越えたら諦めて外へ出た扱いにする。 */
    private static final int FAIL_LIMIT = 40;

    private final AbstractOvergrownInvaderEntity mob;
    private List<BlockPos> route = List.of();
    private IntList steps = IntList.of();
    private int cursor;
    private int waited;
    private int fails;

    public NestExitGoal(AbstractOvergrownInvaderEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!mob.isLeavingNest() || mob.nestHome() == null) {
            return false;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        NestData.Nest nest = NestData.get(level).byOrigin(mob.nestHome());
        if (nest == null || nest.route().isEmpty()) {
            mob.setLeavingNest(false);                // 道しるべが無い巣。諦めてふつうに動く
            return false;
        }
        route = nest.route();
        int at = NestRoute.nearestIndex(route, mob.blockPosition());
        if (at < 0) {
            mob.setLeavingNest(false);
            return false;
        }
        // 近くに道しるべが 1 つも無いなら、もう巣の中ではない
        if (route.get(at).distSqr(mob.blockPosition()) > 24.0D * 24.0D) {
            mob.setLeavingNest(false);
            return false;
        }
        steps = NestRoute.toRoot(nest.routeParents(), at);
        cursor = 0;
        if (steps.size() <= 1) {
            mob.setLeavingNest(false);                // もう坑口。出ている
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isLeavingNest() && cursor < steps.size() && fails < FAIL_LIMIT;
    }

    @Override
    public void start() {
        waited = 0;
        fails = 0;
    }

    @Override
    public void stop() {
        mob.setLeavingNest(false);
        mob.getNavigation().stop();
        steps = IntList.of();
        cursor = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (cursor >= steps.size()) {
            mob.setLeavingNest(false);
            return;
        }
        int index = steps.getInt(cursor);
        if (index < 0 || index >= route.size()) {
            mob.setLeavingNest(false);
            return;
        }
        BlockPos way = route.get(index);
        mob.getLookControl().setLookAt(way.getX() + 0.5D, way.getY() + 1.0D, way.getZ() + 0.5D);
        if (NestRoute.reached(mob, way)) {
            cursor++;
            waited = 0;
            fails = 0;
            mob.getNavigation().stop();
            if (cursor >= steps.size()) {
                mob.setLeavingNest(false);            // 坑口まで出た
            }
            return;
        }
        // 1 つの道しるべで手間取ったら次へ飛ばす。坑道は繋がっているので、
        // 1 点くらい飛ばしても次の点へは歩ける —— ここで粘ると壁に張り付いたまま固まる。
        if (++waited > WAY_LIMIT) {
            waited = 0;
            cursor++;
            mob.getNavigation().stop();
            return;
        }
        if (!mob.getNavigation().isDone()) {
            return;
        }
        if (mob.getNavigation().moveTo(way.getX() + 0.5D, way.getY(), way.getZ() + 0.5D, SPEED)) {
            fails = 0;
        } else {
            fails++;
        }
    }
}
