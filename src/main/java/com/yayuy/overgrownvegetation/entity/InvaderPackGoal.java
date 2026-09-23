package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * <b>群れを組み、離れたら戻る</b>(ユーザー要望 2026-09-15)。
 * 地上のグループ4(アルボラ・フィロセンス・スポロフィル・スカトゥリウム)が使う。飛行のヴェロフィルは
 * 経路探索を使わないので、同じことを {@link VelophyllWanderGoal} が行き先の選び方でやっている。
 *
 * <h2>4 つの動き(上から順に試す)</h2>
 * <ol>
 *     <li><b>迎えに行く(編成が足りないとき)</b> —— {@link InvaderPackComposition} の編成
 *     (アルボラ 8 / スポロフィル 2 / フィロセンス 1 / ヴェロフィル 3 / スカトゥリウム 2)に足りない種があるあいだは、
 *     <b>ふだんより広い {@code invaderPackRecruitRange}</b> まで見て、足りない種のいちばん近い個体へ歩く。
 *     半端な群れ同士が互いに足りない種を目指すので、結果として<b>合流する</b>。</li>
 *     <li><b>はぐれたら帰る</b>({@link #rallyToPack}、2026-09-15 に追加) —— 近くに仲間が
 *     1 体も居なければ、{@code invaderPackRallyRange}(既定 160)まで<b>同じ群れ ID</b> を探して
 *     走って戻る。これが無いと 48 ブロック離れた時点で戻る手段が消える。</li>
 *     <li><b>陣形を組む</b>({@link #moveToFormationSlot}、2026-09-15 に追加) —— 近くに先遣隊
 *     ({@link InvaderPackMember#isPackPointUnit()} = アルボラ)が居るなら、
 *     <b>先遣隊を原点・その向きを前</b>として {@link InvaderPackMember#packFormationOffset()} の
 *     立ち位置へ。スカトゥリウムが前、スポロフィルとフィロセンスが後ろに並ぶ。
 *     {@code invaderPackDashDistance}(既定 26)より離されたら<b>走る</b> ——
 *     アルボラは足が速いので、等速では一度離されると二度と追いつけない。</li>
 *     <li><b>戻る(先遣隊が近くに居ないとき)</b> —— 仲間の重心から
 *     {@link InvaderPackMember#packCohesionDistance()} 以上離れていたら、その内側へ歩いて戻る。
 *     先遣隊自身もこの規則で動く(アルボラ同士が横に広がりすぎない)。</li>
 * </ol>
 *
 * <p>重心ではなく先遣隊を目印にするのは、<b>重心が群れの多数派に引きずられる</b>ため。
 * アルボラ 8 体に対して支援が数体という編成だと重心はほぼアルボラの位置になり、
 * 「アルボラは常に重心の中に居る = 何をしても離れた判定にならない」まま
 * 支援だけが置いていかれる(2026-09-15 のテスト報告「アルボラが独断で飛び出していく」)。
 * <b>編成は最低数であって上限ではない。</b> 揃ったあとに近くへ来た個体もそのまま群れに入る
 * (追い出しはしない)。揃っていれば迎えには行かなくなるだけ。
 *
 * <p>重心を目指すときは<b>ぴったり重心には行かない</b>({@value #SETTLE_FRACTION} 倍の位置)。
 * 全員が同じ 1 点を目指すと押し合ってその場で団子になり、そこから誰も出ていかなくなる。
 *
 * <h2>いつまでも迎えに行かせない</h2>
 * 世界にそもそも 8 体目のアルボラが居なければ編成は永久に埋まらない。そのままだと
 * <b>集まろうとし続けて誰も外へ出ていかなくなる</b>ので、{@code invaderPackRecruitMaxTicks} 迎えに
 * 歩いても揃わなければ {@code invaderPackRecruitRestTicks} のあいだ迎えをやめる
 * (そのあいだは「戻る」だけになり、{@link InvaderAdvanceGoal} が普通に働く)。
 *
 * <h2>優先順位</h2>
 * <b>過繁茂バイオームの外へ出る({@link InvaderAdvanceGoal})より先</b>に置いてある。
 * ばらけたまま外へ押し出すと群れが解けてしまうので、<b>まず集まって、それから一緒に出ていく</b>
 * 順にする。うずくまり・飛びつき・ビームなどの行動中と、標的を掴んでいる間は動かない
 * (戦いが群れ形成より先)。
 */
public class InvaderPackGoal extends Goal {

    /** 仲間を数え直す間隔(tick)。周囲の走査は重いので毎 tick はやらない。 */
    private static final int SCAN_INTERVAL = 30;
    /**
     * 迎えに行く相手を探し直す間隔(tick)。こちらは {@code invaderPackRecruitRange}(既定 96)の
     * <b>ずっと大きい箱</b>を走査するので、群れの数え直しより更に間隔を空ける。
     */
    private static final int RECRUIT_SCAN_INTERVAL = 60;
    /** 重心までの距離のこれだけの位置を狙う(1.0 = 重心ぴったり)。 */
    private static final double SETTLE_FRACTION = 0.45D;
    /**
     * 迎えに行くとき、1 度に目指す距離の上限(ブロック)。
     * 経路探索は {@code invaderPathRange} より遠い目的地に経路を引けないので、遠い相手へは
     * <b>その方向のこのくらい先</b>を刻んで目指す(着くたびに引き直すので結果的に辿り着く)。
     */
    private static final double RECRUIT_STEP = 24.0D;
    /** はぐれた個体が群れを広く探し直す間隔(tick)。大きい箱を走査するので絞る。 */
    private static final int RALLY_SCAN_INTERVAL = 100;
    /** 陣形で同じ種が重ならないよう、個体ごとに横へずらす幅(ブロック)。 */
    private static final double LANE_WIDTH = 3.0D;
    /** 迎えに行った相手のこの距離まで寄れば、あとは重心側の引き合いに任せる(ブロック)。 */
    private static final double RECRUIT_ARRIVED = 8.0D;
    private static final double SPEED_MODIFIER = 1.0D;

    private final PathfinderMob mob;
    private final InvaderPackMember member;
    private final PathNavigation pathNav;
    private Path path;
    private int scanCooldown;
    /** はぐれたときに群れを探し直すまでの残り tick。 */
    private int rallyScanCooldown;
    /** 迎えに行く相手を探し直すまでの残り tick。 */
    private int recruitScanCooldown;
    /** 迎えに歩き続けている tick 数。{@code invaderPackRecruitMaxTicks} で打ち切る。 */
    private int recruitTicks;
    /** 迎えを休んでいるあいだの終わりの {@code tickCount}。 */
    private int recruitRestUntil;
    /** いま迎えに行っているか(true のあいだだけ {@link #recruitTicks} が増える)。 */
    private boolean recruiting;
    /** この経路を歩く速さの倍率。離されているときは走る。 */
    private double speed = SPEED_MODIFIER;

    /** 使えるのは<b>歩ける群れの一員</b>だけ(アルボラ・フィロセンス・スポロフィル・スカトゥリウム)。 */
    public <T extends PathfinderMob & InvaderPackMember> InvaderPackGoal(T mob) {
        this.mob = mob;
        this.member = mob;
        this.pathNav = mob.getNavigation();
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (--this.scanCooldown > 0) {
            return false;
        }
        this.scanCooldown = SCAN_INTERVAL;
        if (!available()) {
            return false;
        }
        List<Mob> members = InvaderPack.packMatesNear(this.mob, InvaderPack.scanRange());

        // 1) 編成が足りていなければ、足りない種を迎えに行く。
        if (canRecruitNow()) {
            Map<EntityType<?>, Integer> missing = InvaderPackComposition.shortfall(this.mob, members);
            if (!missing.isEmpty()) {
                Mob recruit = InvaderPack.nearestRecruit(this.mob, missing.keySet(), InvaderPack.recruitRange());
                if (recruit != null && this.mob.distanceTo(recruit) > RECRUIT_ARRIVED
                        && tryPathTo(stepToward(recruit.position()))) {
                    this.speed = SPEED_MODIFIER;
                    this.recruiting = true;
                    return true;
                }
            }
        }

        this.recruiting = false;

        // 2) 群れが 1 体も近くに居ない = はぐれている。広く探して帰る。
        if (members.isEmpty()) {
            return rallyToPack();
        }

        // 3) 先遣隊(アルボラ)が近くに居るなら、陣形の自分の位置へ。
        if (!this.member.isPackPointUnit()) {
            Mob point = InvaderPack.nearestPointUnit(this.mob, members);
            if (point != null) {
                return moveToFormationSlot(point);
            }
        }

        // 4) 先遣隊が居ない(自分が先遣隊 / まだ合流していない)なら、重心から離れすぎないようにするだけ。
        double cohesion = this.member.packCohesionDistance();
        if (cohesion <= 0.0D) {
            return false;
        }
        Vec3 centre = InvaderPack.centroid(this.mob, members);
        if (centre == null) {
            return false;
        }
        double distance = this.mob.position().distanceTo(centre);
        if (distance <= cohesion) {
            return false;
        }
        this.speed = SPEED_MODIFIER;
        return pathToCentre(centre, distance);
    }

    /**
     * <b>はぐれたら帰る</b>(ユーザー報告 2026-09-15:「群れがかなり離れても集まろうとしない」)。
     *
     * <p>それまでは仲間を {@code invaderPackScanRange}(48)までしか探していなかったので、
     * <b>48 を超えて離れた瞬間に「近くに仲間が 1 体も居ない」= 何もしない</b>に落ちていた。
     * 群れ検査器が「最も離れた個体 131 ブロック」を出していたのがまさにこれ ——
     * 所属は同じままなのに、戻る手段が無かった。
     *
     * <p>群れに ID が付いた今は<b>距離で群れを決める必要がない</b>ので、
     * ここでは {@code invaderPackRallyRange}(既定 160)まで同じ ID を探し、
     * 先遣隊(居なければ重心)へ向かって歩く。走って帰る。
     *
     * <p>広い箱の走査になるので<b>{@value #RALLY_SCAN_INTERVAL} tick に 1 回</b>に絞ってある。
     * はぐれている個体しかここへ来ないので、ふだんは走らない。
     */
    private boolean rallyToPack() {
        this.rallyScanCooldown -= SCAN_INTERVAL;
        if (this.rallyScanCooldown > 0) {
            return false;
        }
        this.rallyScanCooldown = RALLY_SCAN_INTERVAL;
        double range = OvergrownVegetationConfig.COMMON.invaderPackRallyRange.get();
        if (range <= 0.0D) {
            return false;
        }
        List<Mob> pack = InvaderPack.packMatesNear(this.mob, range);
        if (pack.isEmpty()) {
            return false;
        }
        Mob point = InvaderPack.nearestPointUnit(this.mob, pack);
        Vec3 goal = point != null ? point.position() : InvaderPack.centroid(this.mob, pack);
        if (goal == null) {
            return false;
        }
        this.speed = OvergrownVegetationConfig.COMMON.invaderPackDashSpeed.get();
        return tryPathTo(stepToward(goal));
    }

    /**
     * <b>陣形の自分の位置へ</b>(ユーザー要望 2026-09-15:
     * 「アルボラ・スカトゥリウム・ヴェロフィルを前に、スポロフィル・フィロセンスを後方に」)。
     *
     * <p>位置は<b>先遣隊を原点、先遣隊の向きを前</b>として決める:
     * <pre>
     *   自分の位置 = 先遣隊 + 前方向 × {@link InvaderPackMember#packFormationOffset()}
     *                        + 横方向 × (個体ごとの決まったずらし)
     * </pre>
     * 前後は種ごとの固定値(タンクは前、支援と遠距離は後ろ)。
     * 横は {@code getId()} から決めるので<b>個体ごとに違い、かつ毎回同じ</b> ——
     * 乱数にすると立ち位置が落ち着かず、同じ種が 3 体いると重なる。
     *
     * <p>{@code invaderPackFormationTolerance} ブロック以内なら動かない。
     * ぴったり合わせに行くと、先遣隊が 1 歩動くたびに全員が経路を引き直すことになる。
     */
    private boolean moveToFormationSlot(Mob point) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        Vec3 slot = formationSlot(point);
        double distance = this.mob.position().distanceTo(slot);
        if (distance <= cfg.invaderPackFormationTolerance.get()) {
            return false;
        }
        this.speed = this.mob.distanceTo(point) > cfg.invaderPackDashDistance.get()
                ? cfg.invaderPackDashSpeed.get()
                : SPEED_MODIFIER;
        return tryPathTo(stepToward(slot)) || tryPathTo(stepToward(point.position()));
    }

    /** 陣形の中の自分の立ち位置。 */
    private Vec3 formationSlot(Mob point) {
        // yBodyRot は「0 で +Z 向き」。前方向は (-sin, 0, cos)、その右は (cos, 0, sin)。
        float yaw = point.yBodyRot * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw);
        double fz = Mth.cos(yaw);
        double forward = this.member.packFormationOffset();
        // 同じ種が並んだときに重ならないよう、個体ごとに横へずらす(-2..+2 列)。
        double lane = (Math.floorMod(this.mob.getId(), 5) - 2) * LANE_WIDTH;
        return new Vec3(
                point.getX() + fx * forward + fz * lane,
                point.getY(),
                point.getZ() + fz * forward - fx * lane);
    }

    @Override
    public boolean canContinueToUse() {
        return !this.pathNav.isDone() && available();
    }

    /** 群れに戻ってよい状態か(戦っていない・自分の行動の最中でない)。 */
    private boolean available() {
        if (this.mob.getTarget() != null && this.mob.getTarget().isAlive()) {
            return false;
        }
        return !this.member.isPackBusy();
    }

    /** いま迎えに行く相手を探してよいか(休憩中でなく、広い走査の間隔も明けているか)。 */
    private boolean canRecruitNow() {
        if (this.mob.tickCount < this.recruitRestUntil) {
            return false;
        }
        this.recruitScanCooldown -= SCAN_INTERVAL;
        if (this.recruitScanCooldown > 0) {
            return false;
        }
        this.recruitScanCooldown = RECRUIT_SCAN_INTERVAL;
        return true;
    }

    @Override
    public void start() {
        this.pathNav.moveTo(this.path, this.speed);
    }

    @Override
    public void stop() {
        this.path = null;
        this.recruiting = false;
        this.speed = SPEED_MODIFIER;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!this.recruiting) {
            this.recruitTicks = 0;
            return;
        }
        int max = OvergrownVegetationConfig.COMMON.invaderPackRecruitMaxTicks.get();
        if (max > 0 && ++this.recruitTicks > max) {
            // 揃わないものは揃わない。しばらく諦めて、群れとして動けるぶんだけで動く。
            this.recruitTicks = 0;
            this.recruiting = false;
            this.recruitRestUntil = this.mob.tickCount
                    + OvergrownVegetationConfig.COMMON.invaderPackRecruitRestTicks.get();
            this.pathNav.stop();
        }
    }

    /**
     * {@code destination} へ向かう手前の点。経路探索は {@code FOLLOW_RANGE} より遠くへは引けないので、
     * 遠い相手には {@value #RECRUIT_STEP} ブロック刻みで近づく。
     */
    private Vec3 stepToward(Vec3 destination) {
        Vec3 here = this.mob.position();
        Vec3 delta = destination.subtract(here);
        double distance = delta.length();
        return distance <= RECRUIT_STEP ? destination : here.add(delta.scale(RECRUIT_STEP / distance));
    }

    /** {@code point} へ経路が引けたら {@link #path} に入れて true。 */
    private boolean tryPathTo(Vec3 point) {
        this.path = this.pathNav.createPath(point.x, point.y, point.z, 0);
        return this.path != null;
    }

    /**
     * 重心の手前({@value #SETTLE_FRACTION} 倍の位置)へ向かう経路。引けなければ重心そのものを試す
     * —— 手前の 1 点が壁の中や崖の上でも、群れへ戻れなくならないように。
     */
    private boolean pathToCentre(Vec3 centre, double distance) {
        Vec3 here = this.mob.position();
        Vec3 settle = here.add(centre.subtract(here).scale(1.0D - SETTLE_FRACTION));
        if (tryPathTo(settle)) {
            return true;
        }
        return distance > 1.0D && tryPathTo(centre);
    }
}
