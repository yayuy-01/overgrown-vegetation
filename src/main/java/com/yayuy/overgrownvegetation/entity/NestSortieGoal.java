package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.nest.NestData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 巣の住人が<b>狩りに出る</b>(ユーザー指定 2026-09-20:
 * 「巣の中にいるアルボラがしっかり外に出て狩りをしにいくようにしてほしい」)。
 *
 * <p>仕事は 2 つ。
 * <ol>
 *     <li><b>出かける合図</b> —— ときどき {@code leavingNest} を立てる。歩いて坑口まで出るのは
 *     {@link NestExitGoal}(優先度 2)の仕事で、この Goal は場所を譲る。</li>
 *     <li><b>外を歩き回る</b> —— 出たあと {@link #ROAM_TICKS} のあいだ、巣から離れた地点を
 *     順に歩く。<b>これが無いと坑口の前でうろついて終わる</b> ——
 *     索敵の範囲に獲物が入らないので「外に出ない・狩りをしない」ように見える
 *     (2026-09-20 のユーザー報告)。</li>
 * </ol>
 *
 * <p>外に出てしまえば巣の綱({@code guardLeashRadius})は効かない ——
 * 綱は「<b>巣の中に居るあいだ</b>、外の敵に釣られて壁越しに湧き出さない」ためのもので、
 * 出かけた個体を縛るものではない({@link com.yayuy.overgrownvegetation.nest.NestLeash})。
 *
 * <p>狩りの戦利品は {@link ArboraHaulGoal} が巣へ持ち帰るので、放っておいても巣に戻る。
 */
public class NestSortieGoal extends Goal {

    /** 出かけるかどうかを考える間隔(tick)。 */
    private static final int THINK_INTERVAL = 100;
    /** 1 回の判定で出かける確率。 */
    private static final double SORTIE_CHANCE = 0.35D;
    /** 出たあと外を歩き回る時間(tick)。 */
    private static final int ROAM_TICKS = 2400;
    /** 歩き回る先を巣からどれだけ離すか。 */
    private static final double ROAM_MIN = 24.0D;
    private static final double ROAM_MAX = 64.0D;
    /** 1 回に指してよい距離(外は開けているが、それでも刻む)。 */
    private static final double LEG = 16.0D;
    private static final double SPEED = 1.0D;

    private final AbstractOvergrownInvaderEntity mob;
    private int cooldown;
    /** この時刻まで外を歩き回る。0 なら出かけていない。 */
    private long roamUntil;
    @Nullable
    private BlockPos spot;

    public NestSortieGoal(AbstractOvergrownInvaderEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.nestRole() != NestRole.RESIDENT || mob.nestHome() == null) {
            return false;
        }
        if (mob.getTarget() != null || !mob.carriedLoot().isEmpty() || mob.isLeavingNest()) {
            return false;                              // 戦闘中・運搬中・出ている最中は口を出さない
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!mob.isInsideOwnNest()) {
            // もう外。出かけた直後なら歩き回る
            return roamUntil > level.getGameTime() && pickSpot(level);
        }
        if (--cooldown > 0) {
            return false;
        }
        cooldown = THINK_INTERVAL;
        NestData.Nest nest = NestData.get(level).byOrigin(mob.nestHome());
        if (nest == null || nest.route().isEmpty()) {
            return false;
        }
        if (mob.getRandom().nextDouble() >= SORTIE_CHANCE) {
            return false;
        }
        // 合図だけ立てて場所は譲る。歩くのは NestExitGoal(優先度 2)。
        mob.setLeavingNest(true);
        roamUntil = level.getGameTime() + ROAM_TICKS;
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.getTarget() != null || !mob.carriedLoot().isEmpty() || spot == null) {
            return false;                              // 獲物を見つけたらそちらが本番
        }
        if (!(mob.level() instanceof ServerLevel level) || roamUntil <= level.getGameTime()) {
            return false;
        }
        return spot.distSqr(mob.blockPosition()) > 9.0D;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        spot = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (spot == null || !mob.getNavigation().isDone()) {
            return;
        }
        Vec3 here = mob.position();
        Vec3 to = Vec3.atCenterOf(spot).subtract(here);
        double distance = to.length();
        Vec3 aim = distance <= LEG ? Vec3.atCenterOf(spot) : here.add(to.normalize().scale(LEG));
        if (!mob.getNavigation().moveTo(aim.x, aim.y, aim.z, SPEED)) {
            spot = null;                               // 行けない。次の判定で選び直す
        }
    }

    /** 巣から {@link #ROAM_MIN}〜{@link #ROAM_MAX} 離れた地上の点を選ぶ。 */
    private boolean pickSpot(ServerLevel level) {
        BlockPos home = mob.nestHome();
        if (home == null) {
            return false;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = mob.getRandom().nextDouble() * Math.PI * 2.0D;
            double range = ROAM_MIN + mob.getRandom().nextDouble() * (ROAM_MAX - ROAM_MIN);
            int x = home.getX() + (int) Math.round(Math.cos(angle) * range);
            int z = home.getZ() + (int) Math.round(Math.sin(angle) * range);
            if (!level.hasChunkAt(new BlockPos(x, home.getY(), z))) {
                continue;
            }
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    x, z);
            spot = new BlockPos(x, y, z);
            return true;
        }
        return false;
    }
}
