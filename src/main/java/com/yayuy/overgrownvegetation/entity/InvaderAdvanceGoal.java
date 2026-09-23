package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * グループ4(拡大種)を<b>過繁茂バイオームの外へ押し出す</b> Goal。
 * 「積極的に過繁茂したバイオームの外に出て襲う」という要望の足の部分。
 *
 * <p>{@link com.yayuy.overgrownvegetation.phase.Group1FleeCorruptionGoal}(フェーズ2以降の
 * グループ1が汚染地形から離れる)と狙いは同じだが、こちらは
 * <ul>
 *     <li>条件がフェーズに依存しない(拡大種は最初からこう動く)、</li>
 *     <li>判定が<b>バイオームだけ</b>(汚染ブロックからの距離は見ない) — 拡大種は汚染地形の上を
 *     歩くこと自体は平気で、「過繁茂バイオームという縄張りから出ていく」ことだけが目的、</li>
 *     <li>外に出たあとは発動しない(外でうろつくのは
 *     {@link net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal} と
 *     {@link InvaderSporeCrouchGoal} の仕事)</li>
 * </ul>
 * という違いがある。
 *
 * <p>何かを襲っている/反撃している間は発動しない — これが無いと、過繁茂バイオームの中(周囲すべてが
 * 対象外の地形)では常時発動して、目の前の敵を無視して歩き続けてしまう
 * (グループ1で実際に起きた不具合。{@code Group1FleeCorruptionGoal} の javadoc 参照)。
 *
 * <h2>行き先の決め方は「群れを組んでいるか」で変わる(2026-09-15)</h2>
 * <ul>
 *     <li><b>群れを組んでいる</b>({@link InvaderPackBond#id()} を持っている)なら
 *     {@link InvaderPackMarch} —— 8 方向のバイオームを測って<b>いちばん早く外へ出られる向き</b>を選び、
 *     その境目の先(数十〜百数十ブロック)を行き先にする。ユーザー要望
 *     「群れを組んだ際、過繁茂したバイオームの外に出ようとする行動」の本体。</li>
 *     <li><b>まだ群れを組んでいない</b>単体なら、従来どおり足元 {@value #SEARCH_RADIUS} ブロックの
 *     範囲で外を探して一歩ずつ({@link #findOutwardPos})。近ければ出られる、という程度の動き。</li>
 * </ul>
 * 群れのほうを遠くまで測るのは、<b>バイオームの内側では近場に答えが無い</b>から ——
 * 以前はそこで「とにかく今の場所から離れる」ランダム方向に落ちていたので、
 * 群れは縄張りの中をうろつくだけで出ていかなかった。
 *
 * <h2>群れで同じ方向へ出ていく</h2>
 * 各自が勝手に外側の 1 点を選ぶと、同じ場所に居た群れが<b>放射状に散ってしまう</b>。そこで
 * 行き先を選ぶ前に<b>近くの仲間がすでに置いている行き先</b>({@link InvaderPack#sharedDestination})を
 * 見て、あればそれを写す。写す側も自分の行き先として置き直すので、あとから動き出した個体まで
 * 順に同じ方向へ連なっていく。
 *
 * <p>行き先が遠いときは<b>{@value #MARCH_STEP} ブロック刻みで近づく</b> ——
 * 経路探索は {@code invaderPathRange} より遠い目的地に経路を引けないので、
 * その方向の手前の点を目指して着くたびに引き直す({@link InvaderPackGoal} と同じやり方)。
 *
 * <p>ただし<b>歩く先は少しずらす</b>({@value #SCATTER}) —— 全員が同一座標を目指すと、
 * 到着点で押し合って団子になる。ずらすのは経路の終点だけで、仲間へ見せる行き先は
 * <b>ずらす前のもとの点</b>。そうしないと写すたびにずれが積もって、群れが少しずつ扇形に開く。
 *
 * <p>行き先に着いたら({@value #ARRIVED_DISTANCE} ブロック以内)、群れは<b>次の行き先を測り直す</b>。
 * まだバイオームの中なら更に外へ、外に出ていればこの Goal 自体が発動しなくなる。
 * 群れを組んでいない個体だけは着いた場所で<b>待つ</b>(遅れている仲間が着いて行き先を外すまで)。
 *
 * <h2>使える Mob</h2>
 * <b>歩ける群れの一員</b>すべて({@link PathfinderMob} かつ {@link InvaderPackMember})。
 * {@link AbstractOvergrownInvaderEntity} を継承しないスカトゥリウム
 * ({@link OvergrownShielderEntity})も使う —— 2026-09-15 にここを一般化するまで、
 * スカトゥリウムだけが縄張りから出ていかなかった。
 */
public class InvaderAdvanceGoal extends Goal {

    private static final int SEARCH_RADIUS = 28;
    private static final int SEARCH_VERTICAL_RANGE = 7;
    private static final int ATTEMPTS = 10;
    private static final double SPEED_MODIFIER = 1.0D;
    /** 仲間の行き先を写すとき、実際に歩く終点をずらす幅(ブロック、±)。 */
    private static final double SCATTER = 3.0D;
    /** 行き先を探し直す間隔(tick)。 */
    private static final int SCAN_INTERVAL = 10;
    /** 群れの行き先にこれだけ近づいたら「着いた」(ブロック)。 */
    private static final double ARRIVED_DISTANCE = 8.0D;
    /** 遠い行き先へ向かうとき、1 度に目指す距離の上限(ブロック)。 */
    private static final double MARCH_STEP = 24.0D;

    private final PathfinderMob mob;
    private final InvaderPackMember member;
    private final PathNavigation pathNav;
    private Path path;
    /** 仲間へ見せる行き先(群れ全体の目標)。ずらす前のもとの点。 */
    private Vec3 shared;
    /**
     * 行き先を探し直すまでの残り tick。{@code canUse} は毎 tick 呼ばれるのに対し、中でやるのは
     * <b>周囲の走査と経路探索</b>(仲間を数える + 候補地探し)なので、
     * 毎 tick はやらない。歩き出すのが最大 {@value #SCAN_INTERVAL} tick 遅れるだけ。
     */
    private int scanCooldown;
    /** 先遣隊が後続を待っている tick 数。{@code invaderPackLeadWaitTicks} で打ち切る。 */
    private int leadWaitTicks;

    public <T extends PathfinderMob & InvaderPackMember> InvaderAdvanceGoal(T mob) {
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
        if (this.mob.getTarget() != null && this.mob.getTarget().isAlive()) {
            return false;
        }
        if (this.member.isPackBusy()) {
            return false;                   // 建設グループと巣から出る途中もここで止まる(2026-09-20)
        }
        if (!insideOvergrownBiome()) {
            return false;
        }
        List<Mob> members = InvaderPack.packMatesNear(this.mob, InvaderPack.scanRange());
        if (!leadReady(members)) {
            return false;
        }
        boolean inPack = this.member.packBond().id() != null;

        // まず群れの行き先を写す。写せなければ(誰も動いていない / そこへ経路が引けない)自分で選ぶ。
        Vec3 pack = InvaderPack.sharedDestination(this.mob, members);
        if (pack == null) {
            pack = this.member.getPackDestination();
        }
        if (pack != null) {
            if (!this.mob.position().closerThan(pack, ARRIVED_DISTANCE)) {
                if (tryPathTo(scatter(stepToward(pack)))) {
                    this.shared = pack;
                    return true;
                }
            } else if (!inPack) {
                // もう着いている。群れを組んでいない個体は<b>先へ進まず待つ</b> ——
                // 目の前の点へ経路を引き直し続けると、着いた側が 10 tick おきに無駄な探索をする。
                // 待っているあいだの見た目はランダム徘徊(優先度がひとつ下)が受け持つ。
                return false;
            }
            // 群れなら、着いた時点で次の行き先を測り直す(まだバイオームの中なので更に外へ)。
        }

        Vec3 out = inPack ? InvaderPackMarch.exitTarget(this.mob) : findOutwardPos();
        if (out == null || !tryPathTo(stepToward(out))) {
            return false;
        }
        this.shared = out;
        return true;
    }

    /** いま繁茂系バイオームの中に居るか。 */
    private boolean insideOvergrownBiome() {
        return ModBiomes.isOvergrownLike(this.mob.level().getBiome(this.mob.blockPosition()));
    }

    /**
     * <b>先遣隊(アルボラ)が、後続を置き去りにしていないか</b>
     * (ユーザー要望 2026-09-15「アルボラが勝手に飛び出さず、味方を連れてくるように」)。
     *
     * <p>先遣隊は足が速く、そのままだと後続を千切って一体で前進してしまう。
     * そこで<b>連れて行く相手が {@code invaderPackFollowMax} より離れているあいだは前へ進まない</b>。
     * 立ち止まっているあいだに後続が追いつき({@link InvaderPackGoal} が走って詰める)、
     * 揃ったところでまた一区切り進む —— という歩き方になる。
     *
     * <p>ただし<b>いつまでも待たない</b>。追いつけない仲間(詰まっている・水の向こう)が居ると
     * 群れ全体が永久に止まってしまうので、{@code invaderPackLeadWaitTicks} 待ったら先に行く。
     * 待ちは連れて行く相手が近づいた時点で解ける。
     *
     * <p>先遣隊でない種と、そもそも連れて行く相手が居ない個体には掛からない。
     */
    private boolean leadReady(List<Mob> members) {
        if (!this.member.isPackPointUnit()) {
            return true;
        }
        double farthest = InvaderPack.farthestFollower(this.mob, members);
        if (farthest < 0.0D
                || farthest <= OvergrownVegetationConfig.COMMON.invaderPackFollowMax.get()) {
            this.leadWaitTicks = 0;
            return true;
        }
        int limit = OvergrownVegetationConfig.COMMON.invaderPackLeadWaitTicks.get();
        this.leadWaitTicks += SCAN_INTERVAL;
        if (this.leadWaitTicks >= limit) {
            this.leadWaitTicks = 0;
            return true;
        }
        return false;
    }

    /** {@code point} へ経路が引けたら {@link #path} に入れて true。 */
    private boolean tryPathTo(Vec3 point) {
        this.path = this.pathNav.createPath(point.x, point.y, point.z, 0);
        return this.path != null;
    }

    /**
     * {@code destination} へ向かう手前の点。経路探索は {@code invaderPathRange} より遠くへは引けないので、
     * 遠い行き先には {@value #MARCH_STEP} ブロック刻みで近づく(着くたびに引き直すので結果的に辿り着く)。
     */
    private Vec3 stepToward(Vec3 destination) {
        Vec3 here = this.mob.position();
        Vec3 delta = destination.subtract(here);
        double distance = delta.length();
        return distance <= MARCH_STEP ? destination : here.add(delta.scale(MARCH_STEP / distance));
    }

    /** 到着点で押し合わないよう、水平に ±{@value #SCATTER} ブロックずらす。 */
    private Vec3 scatter(Vec3 point) {
        double dx = (this.mob.getRandom().nextDouble() * 2.0D - 1.0D) * SCATTER;
        double dz = (this.mob.getRandom().nextDouble() * 2.0D - 1.0D) * SCATTER;
        return new Vec3(point.x + dx, point.y, point.z + dz);
    }

    @Override
    public boolean canContinueToUse() {
        return !this.pathNav.isDone()
                && (this.mob.getTarget() == null || !this.mob.getTarget().isAlive())
                && !this.member.isPackBusy();
    }

    @Override
    public void start() {
        this.pathNav.moveTo(this.path, SPEED_MODIFIER);
        // ここから先、あとから動き出した仲間はこの行き先を写す。
        this.member.setPackDestination(this.shared);
    }

    @Override
    public void stop() {
        this.path = null;
        this.shared = null;
        this.member.setPackDestination(null);
    }

    /**
     * 過繁茂系バイオームの外に出られる地点(足元 {@value #SEARCH_RADIUS} ブロックの範囲)。
     * 見つからなければ「とにかく今の場所から離れる」方向。
     *
     * <p>使うのは<b>群れを組んでいない個体だけ</b>。群れは {@link InvaderPackMarch} で
     * ずっと遠くまで測る —— ここの探し方はバイオームの内側では答えが出ず、
     * 下の「とにかく離れる」に落ちてしまうため。
     */
    private Vec3 findOutwardPos() {
        for (int i = 0; i < ATTEMPTS; i++) {
            Vec3 candidate = LandRandomPos.getPos(this.mob, SEARCH_RADIUS, SEARCH_VERTICAL_RANGE);
            if (candidate == null) {
                continue;
            }
            BlockPos at = BlockPos.containing(candidate.x, candidate.y, candidate.z);
            if (!ModBiomes.isOvergrownLike(this.mob.level().getBiome(at))) {
                return candidate;
            }
        }
        // 周囲一帯が過繁茂バイオーム(内陸にいる)なら、外周へ向かう当てがないのでとにかく遠くへ。
        return DefaultRandomPos.getPosAway(this.mob, SEARCH_RADIUS, SEARCH_VERTICAL_RANGE, this.mob.position());
    }
}
