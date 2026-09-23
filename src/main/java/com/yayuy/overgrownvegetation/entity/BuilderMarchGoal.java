package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 建設グループのアルボラが<b>建設地まで歩く</b>(2026-09-20)。
 *
 * <p>目的地は数百ブロック先なので、一気に指すと経路探索が届かず
 * <b>そのまま直進して壁に張り付く</b>([[wallclimber-nav-hidden-beeline]])。
 * ここでは中継点を 1 つ置いて、短い区間を繰り返し歩かせる。
 *
 * <h2>中継点は<b>着くまで変えない</b>(2026-09-20 の作り直し)</h2>
 * <p>前の版は 20 tick ごとに中継点を選び直していた。自分が動けば向きが変わるので
 * <b>選び直すたびに別の点が当たり</b>、水際では「水に入る中継点」と「岸へ戻る中継点」が
 * 交互に当たって<b>入っては上がってを繰り返した</b>(ユーザー報告)。
 * いまは一度決めた中継点に着くか、{@link #LEG_PATIENCE} 経つまで変えない。
 *
 * <h2>中継点は<b>対岸</b>に置く</h2>
 * <p>水の重みを 0 にした({@code AbstractOvergrownInvaderEntity#setWaterCrossing})だけだと、
 * 中継点が<b>川の真ん中</b>に落ちる。そこへ着いたらまた選び直し —— 水の上で立ち止まる形になる。
 * 中継点を選ぶときに、その柱が水なら<b>同じ向きへ伸ばして最初の陸</b>を探す。
 *
 * <p>着いたかどうかの判定と巣の掘り始めは
 * {@link com.yayuy.overgrownvegetation.nest.BuilderGroups} 側。この Goal は歩くだけ。
 */
public class BuilderMarchGoal extends Goal {

    /** 1 回に指す距離(ブロック)。経路探索が確実に届く長さにする。 */
    private static final double LEG = 24.0D;
    /** 中継点を探す距離(遠い順)。 */
    private static final double[] LEGS = {LEG, 12.0D};
    /** 建設地の方向から何ラジアン振って探すか(まっすぐ先から順に)。 */
    private static final double[] TURNS = {0.0D, 0.5D, -0.5D, 1.0D, -1.0D, 1.8D, -1.8D};
    /** 水の柱に当たったとき、同じ向きへ陸を探して伸ばす距離。 */
    private static final double SHORE_REACH = 40.0D;
    private static final double SHORE_STEP = 6.0D;
    /** 建設地に着いたとみなす距離。 */
    private static final double ARRIVE = 10.0D;
    /** 中継点に着いたとみなす距離。 */
    private static final double LEG_ARRIVE = 3.5D;
    /** 1 つの中継点に掛けてよい時間(tick)。泳いでいるあいだは倍見る。 */
    private static final int LEG_PATIENCE = 200;
    private static final double SPEED = 1.0D;
    /** 経路が出ないまま粘る上限(tick)。 */
    private static final int STUCK_LIMIT = 200;
    /** 同じ中継点へ経路を引き直す間隔(tick)。 */
    private static final int REPATH_INTERVAL = 10;

    private final AbstractOvergrownInvaderEntity mob;
    @Nullable
    private BlockPos leg;
    private int legAge;
    private int stuck;
    /**
     * 同じ中継点へ経路を引き直すまでの待ち。泳いでいるあいだは経路が頻繁に終わる(水のノードは
     * 途中で切れる)ので、毎 tick 引き直すと経路探索がそのぶん走ってしまう。
     */
    private int repathIn;

    public BuilderMarchGoal(AbstractOvergrownInvaderEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        BlockPos site = mob.builderSite();
        if (mob.nestRole() != NestRole.BUILDER || site == null) {
            return false;
        }
        return site.distSqr(mob.blockPosition()) > ARRIVE * ARRIVE;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse() && stuck < STUCK_LIMIT;
    }

    @Override
    public void start() {
        stuck = 0;
        leg = null;
        legAge = 0;
        repathIn = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        leg = null;
    }

    @Override
    public void tick() {
        BlockPos site = mob.builderSite();
        if (site == null || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        mob.getLookControl().setLookAt(site.getX() + 0.5D, site.getY() + 1.0D, site.getZ() + 0.5D);

        // 建設地そのものが届く距離なら、中継点を挟まず直に指す
        Vec3 here = mob.position();
        Vec3 to = Vec3.atCenterOf(site).subtract(here);
        if (to.length() <= LEG) {
            leg = null;
            if (mob.getNavigation().isDone() && --repathIn <= 0) {
                repathIn = REPATH_INTERVAL;
                if (!mob.getNavigation().moveTo(site.getX() + 0.5D, site.getY(), site.getZ() + 0.5D, SPEED)) {
                    stuck++;
                }
            }
            return;
        }

        // 決めた中継点は<b>着くまで変えない</b>。ここを毎回選び直すと水際で往復する。
        if (leg != null) {
            int patience = mob.isInWater() ? LEG_PATIENCE * 2 : LEG_PATIENCE;
            boolean reached = leg.distToCenterSqr(here.x, here.y, here.z) <= LEG_ARRIVE * LEG_ARRIVE;
            if (!reached && legAge++ < patience) {
                if (mob.getNavigation().isDone() && --repathIn <= 0) {
                    repathIn = REPATH_INTERVAL;
                    mob.getNavigation().moveTo(leg.getX() + 0.5D, leg.getY(), leg.getZ() + 0.5D, SPEED);
                }
                return;
            }
            leg = null;
        }

        // 建設地の方向から順に扇形へ振って、経路の出る中継点を探す。
        double base = Math.atan2(to.z, to.x);
        for (double reach : LEGS) {
            for (double turn : TURNS) {
                BlockPos candidate = shoreAhead(level, here, base + turn, reach);
                if (candidate == null) {
                    continue;
                }
                if (mob.getNavigation().moveTo(candidate.getX() + 0.5D, candidate.getY(),
                        candidate.getZ() + 0.5D, SPEED)) {
                    leg = candidate;
                    legAge = 0;
                    stuck = 0;
                    return;
                }
            }
        }
        stuck++;
    }

    /**
     * その向き {@code reach} マス先の地表。<b>水なら同じ向きへ伸ばして最初の陸</b>を返す ——
     * 川の真ん中を中継点にすると、そこで立ち止まってまた選び直すことになる。
     * 伸ばしきっても陸が無ければ(広い湖・海)null。
     */
    @Nullable
    private static BlockPos shoreAhead(ServerLevel level, Vec3 here, double angle, double reach) {
        for (double r = reach; r <= reach + SHORE_REACH; r += SHORE_STEP) {
            int x = Mth.floor(here.x + Math.cos(angle) * r);
            int z = Mth.floor(here.z + Math.sin(angle) * r);
            if (!level.hasChunkAt(new BlockPos(x, level.getSeaLevel(), z))) {
                return null;                          // 未ロード。そこは指さない
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos at = new BlockPos(x, y, z);
            if (level.getFluidState(at).isEmpty() && level.getFluidState(at.below()).isEmpty()) {
                return at;                            // 水の上ではない = 岸
            }
        }
        return null;
    }
}
