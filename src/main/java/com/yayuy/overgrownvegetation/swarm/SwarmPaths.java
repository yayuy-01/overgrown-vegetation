package com.yayuy.overgrownvegetation.swarm;

import com.yayuy.overgrownvegetation.entity.InvaderNavigation;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.raid.SmallRaidHunt;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 群れが引く<b>経路探索</b>の窓口(群れ v2、2026-09-25)。群れの持ち物の探索はすべてここを通す。
 *
 * <ul>
 *     <li><b>先導役の区間</b>({@link #requestLeg}): 群れの体の包絡({@link Swarm#envelope()})で、
 *     {@code legMaxLength}(24)ブロックまでの区間を引く。狩り・攻城で水だけに塞がれたら、次の区間は泳いで渡る。</li>
 *     <li><b>後続の立て直し</b>({@link #requestRecovery}): 後続は普段は経路を引かない(足跡を純追跡する)。
 *     先導役が動いているのに 40 tick 進めないときだけ、自分の体で足跡のカーソルへ 1 本引く。</li>
 * </ul>
 * どちらも<b>ワールドごとの札の入れ物</b>({@link TokenBucket}、1 tick に {@code tokensPerTick} 本)を通る。札が取れない
 * 要求は今の経路のまま次の tick にやり直す。並びは先導役が先、同じ中では先に待ち始めたほうが先(FIFO)。
 * 数えた本数はテスト用の数え({@link #requests})にも入る —— 「普通に付いていく後続は経路を引かない」を確かめるため。
 *
 * <p>サーバースレッドだけが触る。
 */
public final class SwarmPaths {

    /** 群れの持ち物の探索の種類(数え分け)。 */
    public enum RequestKind {
        /** 先導役の区間。 */
        LEG,
        /** 後続の立て直し。 */
        RECOVERY
    }

    /** 要求の結果。 */
    public enum Status {
        /** 経路が引けた({@link LegResult#path()})。 */
        OK,
        /** この tick の札が無い。今の経路のまま、次の tick にやり直す。 */
        NO_TOKEN,
        /**
         * いまは経路を引けない体勢(宙にいる・跳んでいる・壁に張り付いている)。札も使わず、失敗にも数えない。
         * 今の経路のまま、次の tick にやり直す(レビュー指摘: バニラの {@code createPath} はこの体勢だと黙って null を返し、
         * それを失敗と数えて待ち・向き変え・小規模レイドの「届かない」まで進めていた)。
         */
        NOT_READY,
        /** 失敗が続いたので待っている(区間だけ)。 */
        BACKOFF,
        /** 引けなかった(または、ほとんど進まない部分経路で捨てた)。 */
        FAILED,
        /**
         * 群れの包絡では塞がれていたので、次の区間から先導役の体の幅で引く({@link Swarm#slimLegs})。待ちは付けない。
         * 次の tick にやり直す(区間だけ)。
         */
        NARROWED,
        /** 群れの先導役・一員ではない。 */
        NOT_IN_SWARM
    }

    /** {@link #requestLeg} / {@link #requestRecovery} の結果。 */
    public record LegResult(Status status, @Nullable Path path) {
        public boolean ok() {
            return this.status == Status.OK && this.path != null;
        }
    }

    /** 立て直しを報告した結果。 */
    public enum RecoveryOutcome {
        /** 続けてよい(成功した・まだ失敗の上限に届いていない)。 */
        CONTINUE,
        /** 外せない群れ(イベント・TD)なので、足跡のカーソルを近い点へ戻した。その点へ引き直すこと。 */
        FALLBACK,
        /** 自然の群れから外した(ふつうの個体に戻った。消え方も普段の規則)。 */
        EJECTED
    }

    private static final long[] REQUESTS = new long[RequestKind.values().length];
    private static long denied;

    /** 区間の行き先が水の向こうか調べる刻み(ブロック)。 */
    private static final double WATER_PROBE_STEP = 2.0D;
    /** 部分経路の終わりが先導役のこれ以内なら「塞がれた」と数える(TD の壁を壊す判断)。 */
    private static final double BLOCKED_END_DISTANCE = 3.0D;
    /** 区間が「進んだ」と認める距離の縮み(ブロック)。 */
    private static final double LEG_PROGRESS = 2.0D;
    /** 包絡で塞がれたあと、先導役の体の幅で引く区間の本数(最大 24 ブロック × 6)。使い切ったら包絡でもう一度試す。仮決め 2026-09-25。 */
    static final int SLIM_LEGS = 6;
    /**
     * TD の群れで、立て直しの失敗が続いてカーソルを戻した回数がこれに達したら、その一員を群れから外す(自分でターゲットへ向かう)。
     * 立て直しの失敗 {@code recoveryMaxFailures}(3)回で 1 回戻すので、続けて 6 回の失敗。仮決め 2026-09-25。
     */
    static final int TD_RELEASE_FALLBACKS = 2;

    // --- 先導役の区間 ---

    /**
     * 先導役の区間を引く(札を 1 本使う)。{@code goal} が {@code maxLength} より遠ければ、その向きへ {@code maxLength} 進んだ点
     * (地面の高さに合わせる)までにする。
     *
     * <ul>
     *     <li>包絡 … 群れの体の包絡(前衛・支援役のいちばん大きい体)。近接・立て直し・坑道は自分の体のまま。</li>
     *     <li>水 … 直前の区間が水だけに塞がれていて、狩り・攻城なら、この区間は泳いで渡る(仮決め 2026-09-25。
     *     暇なときは泳がず、行き先を変える)。</li>
     *     <li>引けなかったら待ち({@code legBackoffTicks}: 40 → 80 → 160)、暇なときは同じ行き先への失敗を数え、
     *     {@code failedLegsBeforeReheading} 回で次の外へ向きを変える。</li>
     *     <li>宙にいる・跳んでいる・壁に張り付いている間は引かない({@link Status#NOT_READY}。札も使わず失敗にも数えない)。</li>
     * </ul>
     * 経路を歩き始めるのは {@link #followLeg}(この結果の経路を渡す)。
     */
    public static LegResult requestLeg(Mob vanguard, Swarm swarm, Vec3 goal, double maxLength) {
        if (!(vanguard.level() instanceof ServerLevel level) || swarm.vanguard() != vanguard
                || swarm.kind() != Swarm.Kind.GROUND) {
            return new LegResult(Status.NOT_IN_SWARM, null);
        }
        long now = level.getGameTime();
        if (now < swarm.legBackoffUntil) {
            return new LegResult(Status.BACKOFF, null);
        }
        if (!InvaderNavigation.canSearchNow(vanguard)) {
            return new LegResult(Status.NOT_READY, null);
        }
        if (!tryAcquire(level, vanguard, true)) {
            return new LegResult(Status.NO_TOKEN, null);
        }
        count(swarm, RequestKind.LEG);
        // 攻城で行き先が「塊」と「防衛側」のあいだで替わったら、塞がれた区間の数えをやり直す。防衛側を追って塞がれた区間の
        // 数えが残ったまま塊へ戻ると、最初の区間の前に TD が塊の方向の壁を選び、関係のない丘・木を掘り始めた(レビュー指摘 2026-09-25)。
        BlockPos siegeBlock = swarm.mode() == Swarm.Mode.SIEGE ? swarm.targetBlock() : null;
        boolean atBlock = siegeBlock != null && goal.distanceToSqr(Vec3.atBottomCenterOf(siegeBlock)) < 1.0E-4D;
        if (atBlock != swarm.lastLegAtBlock) {
            swarm.lastLegAtBlock = atBlock;
            swarm.consecutiveBlockedLegs = 0;
        }
        Vec3 end = legEnd(level, vanguard.position(), goal, maxLength);
        BlockPos endPos = BlockPos.containing(end);
        InvaderNavigation.Envelope full = swarm.envelope();
        InvaderNavigation.Envelope slim = slimEnvelope(full, vanguard);
        boolean slimLeg = slim != null && swarm.slimLegs > 0;
        InvaderNavigation.Envelope envelope = slimLeg ? slim : full;
        boolean water = swarm.nextLegWater && modeMaySwim(swarm);
        Path path = InvaderNavigation.withRequest(vanguard, envelope, water,
                () -> vanguard.getNavigation().createPath(endPos, 1));
        if (slimLeg) {
            swarm.slimLegs--;
        } else if (slim != null && blockedLeg(vanguard, path)) {
            // 群れの包絡(いちばん大きい一員の体。TD の群れはフェーズ 6 から幅 2.5〜3.5 のスカトゥリウムが必ず入り、3 マス幅)では
            // 塞がれている —— 森の木の間・狭い谷。次の tick から SLIM_LEGS 本は先導役の体の幅で引く(大きい一員は遅れて立て直しで追う。
            // 先導役が待つのは waitCapTicks まで)。この区間は塞がれたと数えない(待ちも、TD の壁選びも無し)。
            // 2026-09-25 のユーザー報告「TD 中にターゲットへ行かず、湧いた場所でずっとくるくるしてスタック」: はじめは包絡のまま
            // 引き続けたので、森では区間が 1 本も引けず、待ち(40 → 160 tick)と、湧いた場所の木を壁として壊す BREACH を繰り返していた
            // (登る群れ = アルボラだけの 1 マス幅の群れと、空の群れだけがターゲットに届いていた)。
            swarm.slimLegs = SLIM_LEGS;
            return new LegResult(Status.NARROWED, null);
        }
        if (path == null || path.getNodeCount() == 0) {
            swarm.consecutiveBlockedLegs++;
            if (!water && modeMaySwim(swarm) && waterOnRoute(level, vanguard.position(), end)) {
                // 水だけに塞がれていそう。待たずに、次の区間は泳いで渡る。
                swarm.nextLegWater = true;
            } else {
                swarm.nextLegWater = false;
                noteLegOutcome(swarm, false);
            }
            return new LegResult(Status.FAILED, null);
        }
        // 攻城の塊が固い(ターゲット・囲いの中)と、バニラの GroundPathNavigation#createPath は行き先をその上の最初の空きマスへ
        // 持ち上げる。囲ったターゲットでは行き先が囲いの屋根の上になり、経路は「届いた」と答える —— 群れは屋根の上で塊に着かないまま
        // 塞がれたとも数えず、壁選び(縦穴)も起きなかった(2026-09-25 のユーザー報告「ターゲットブロックを囲むと、攻めてくる mob が
        // 上に乗った状態で何もしなくなる」、GameTest encasedTargetIsStruckFromTheRoof)。持ち上げられた行き先に届いただけなら届いていない扱い。
        // ただし 1 段だけの持ち上げは塊そのものの上面(真上が空いている = 上から手が届く)なので届いた扱い(2026-10-19)。床に置いた
        // ターゲットはいつも 1 段持ち上がるので、届く経路でも「塞がれた」かが終わりまでの距離だけで決まり、金網の向こうのターゲットへ
        // 登る群れの先導役が金網に体を付けた地面から区間を 2 回引くと(登りをしくじって降りた)、終わり(ターゲットの上)まで 3 未満で
        // 塞がれたと数えられ、登れる金網を壊しにかかっていた(GameTest climberSwarmCrossesChainLinkFence が全体 150 回に 1 回ほど落ちた)。
        BlockPos reachedTarget = path.getTarget();
        boolean roofOnly = atBlock && path.canReach() && reachedTarget != null && reachedTarget.getY() > endPos.getY() + 1;
        if (path.canReach() && !roofOnly) {
            swarm.consecutiveBlockedLegs = 0;
        } else {
            Node last = path.getEndNode();
            if (last != null && vanguard.position().distanceToSqr(last.x + 0.5D, last.y, last.z + 0.5D)
                    <= BLOCKED_END_DISTANCE * BLOCKED_END_DISTANCE) {
                swarm.consecutiveBlockedLegs++;
            } else {
                swarm.consecutiveBlockedLegs = 0;
            }
        }
        swarm.pendingLegGoal = end;
        swarm.pendingEnvelope = envelope;
        swarm.pendingCorridorOffset = corridorOffset(envelope, vanguard);
        swarm.pendingLegWater = water;
        return new LegResult(Status.OK, path);
    }

    /**
     * 先導役の体の幅・高さに絞った包絡(登る・這うは群れの包絡のまま)。群れの包絡が先導役の体より大きくなければ null
     * (絞っても変わらない)。
     */
    @Nullable
    private static InvaderNavigation.Envelope slimEnvelope(@Nullable InvaderNavigation.Envelope full, Mob vanguard) {
        if (full == null) {
            return null;
        }
        float width = Math.min(full.width(), vanguard.getBbWidth());
        float height = Math.min(full.height(), vanguard.getBbHeight());
        InvaderNavigation.Envelope slim = new InvaderNavigation.Envelope(width, height, full.canClimb(), full.crawl());
        if (slim.blockWidth() >= full.blockWidth() && Mth.floor(height + 1.0F) >= Mth.floor(full.height() + 1.0F)) {
            return null;
        }
        return slim;
    }

    /** 区間が塞がれているか: 引けない、または先導役の {@value #BLOCKED_END_DISTANCE} ブロック以内で終わる部分経路。 */
    private static boolean blockedLeg(Mob vanguard, @Nullable Path path) {
        if (path == null || path.getNodeCount() == 0) {
            return true;
        }
        if (path.canReach()) {
            return false;
        }
        Node last = path.getEndNode();
        return last != null && vanguard.position().distanceToSqr(last.x + 0.5D, last.y, last.z + 0.5D)
                <= BLOCKED_END_DISTANCE * BLOCKED_END_DISTANCE;
    }

    /**
     * ノードから通り道の中心までのずれ。包絡があればその幅、無ければ(包絡を一度も考えていない群れ)先導役自身の体の幅で、
     * {@code Path#getEntityPosAtNode} と同じ {@code (int)(幅 + 1) × 0.5}。
     */
    private static double corridorOffset(@Nullable InvaderNavigation.Envelope envelope, Mob vanguard) {
        return envelope != null ? envelope.corridorOffset() : (int) (vanguard.getBbWidth() + 1.0F) * 0.5D;
    }

    /**
     * {@link #requestLeg} で引いた区間を歩き始める({@code navigation.moveTo})。前の区間の足跡を書き切ってから切り替える。
     *
     * @return 歩き始めたら true
     */
    public static boolean followLeg(Mob vanguard, Swarm swarm, Path path, double speed) {
        if (swarm.vanguard() != vanguard) {
            return false;
        }
        recordTrail(swarm);
        boolean ok = vanguard.getNavigation().moveTo(path, speed);
        if (!ok) {
            return false;
        }
        startLegBookkeeping(swarm, vanguard, vanguard.getNavigation().getPath(),
                swarm.pendingLegGoal != null ? swarm.pendingLegGoal : Vec3.atBottomCenterOf(path.getTarget()),
                swarm.pendingEnvelope, swarm.pendingCorridorOffset, swarm.pendingLegWater);
        swarm.pendingLegGoal = null;
        swarm.pendingEnvelope = null;
        return true;
    }

    /**
     * 先導役が替わったとき、前の先導役の区間(写し)を新しい先導役で歩き始める。先導の Goal の {@code start()} で呼ぶ。
     * 引き継ぐ区間が無ければ false(ふつうに {@link #requestLeg} する)。
     *
     * <p>足跡は、区間を<b>引いたときの</b>包絡のずれで記録する(レビュー指摘: 今の包絡のずれで記録すると、包絡が替わった群れ・
     * 細い体で引いた経路では、足跡が確かめた列から 1〜1.5 ブロックずれて木や壁の中に入る)。写した区間はナビにも
     * 「上書きつきで引いた経路」として覚えさせる(ブロックの変化で引き直すときも同じ包絡で引き直すように)。
     */
    public static boolean resumeHandedOverLeg(Mob vanguard, Swarm swarm, double speed) {
        Path handed = swarm.handOverPath;
        swarm.handOverPath = null;
        if (handed == null || handed.isDone() || swarm.vanguard() != vanguard) {
            return false;
        }
        InvaderNavigation.adoptRequest(vanguard, handed, swarm.handOverEnvelope, swarm.handOverWater);
        if (!vanguard.getNavigation().moveTo(handed, speed)) {
            return false;
        }
        Node end = handed.getEndNode();
        Vec3 goal = end != null ? new Vec3(end.x + 0.5D, end.y, end.z + 0.5D) : vanguard.position();
        startLegBookkeeping(swarm, vanguard, vanguard.getNavigation().getPath(), goal,
                swarm.handOverEnvelope, swarm.handOverCorridorOffset, swarm.handOverWater);
        return true;
    }

    private static void startLegBookkeeping(Swarm swarm, Mob vanguard, @Nullable Path applied, Vec3 goal,
                                            @Nullable InvaderNavigation.Envelope envelope, double corridorOffset,
                                            boolean water) {
        swarm.currentLeg = applied;
        swarm.legRoot = applied;
        swarm.legEnvelope = envelope;
        swarm.legRecorded = applied != null ? Math.max(0, applied.getNextNodeIndex()) : 0;
        swarm.legCorridorOffset = corridorOffset;
        swarm.currentLegWater = water;
        swarm.legGoal = goal;
        swarm.legStartDistance = horizontalDistance(vanguard.position(), goal);
        if (water) {
            swarm.nextLegWater = false;
        }
    }

    /**
     * 区間を歩き終えた・打ち切ったときに、進んだかどうかを報告する。進まなければ待ちを延ばし
     * ({@code legBackoffTicks})、暇なときは同じ行き先への失敗を数えて {@code failedLegsBeforeReheading} 回で向きを変える。
     * 狩りでは、狙い(HUNT)なら小規模レイドの「届かない」を数え、それ以外の標的なら同じ回数で群れとして諦める
     * ({@link Swarm#giveUpTarget}。2026-09-25)。先導役の Goal からだけ呼ぶ(降ろされた先導役は呼ばない)。
     */
    public static void noteLegOutcome(Swarm swarm, boolean progressed) {
        SwarmTable table = Swarm.table();
        long now = swarm.level().getGameTime();
        boolean huntingQuarry = swarm.mode() == Swarm.Mode.HUNT && swarm.targetReason() == Swarm.TargetReason.HUNT;
        // 狙い以外の標的(プレイヤー・視線持ち・反撃・獲物)へ向かっている区間か。相手が替わったら数え直す。
        // 攻城(SIEGE)で防衛側へ向かっている区間も数える(2026-09-25: 数えていなかったので、届かない所の相手 —— 崖・柵の中の生き物、
        // 塔の上のプレイヤー —— を攻城の群れがいつまでも追い、塊へ向かわなかった)。
        LivingEntity chased = (swarm.mode() == Swarm.Mode.HUNT && !huntingQuarry) || swarm.mode() == Swarm.Mode.SIEGE
                ? swarm.target() : null;
        if (chased == null || !chased.getUUID().equals(swarm.targetFailedLegsFor)) {
            swarm.targetFailedLegs = 0;
            swarm.targetFailedLegsFor = chased != null ? chased.getUUID() : null;
        }
        if (progressed) {
            swarm.legFailures = 0;
            swarm.wanderFailedLegs = 0;
            swarm.legBackoffUntil = 0L;
            swarm.targetFailedLegs = 0;
            if (huntingQuarry) {
                swarm.huntFailedLegs = 0;
            }
            return;
        }
        if (huntingQuarry) {
            // 小規模レイドの「届かない」(raid/SmallRaidHunt#unreachable)。司令塔が次に探すときに諦める。
            swarm.huntFailedLegs++;
        } else if (chased != null && SmallRaidHunt.unreachable(++swarm.targetFailedLegs)) {
            // 狙い以外の標的も、同じ回数(SmallRaidHunt.UNREACHABLE_LEGS = 6)続けて区間が進まなければ群れとして諦める
            // (giveUpTicks のあいだ渡さない)。レビュー指摘 2026-09-25: 諦める口(Swarm#giveUpTarget)に呼び手が無く、
            // 閉じこもったプレイヤー(視線持ちは距離の上限も無い)を群れがいつまでも追い、暇にも戻らず消えもしなかった。
            // 数は小規模レイドの規則を流用(仮決め 2026-09-25)。
            swarm.giveUpTarget(chased);
        }
        swarm.legBackoffUntil = now + table.legBackoffTicks(swarm.legFailures);
        swarm.legFailures++;
        if (swarm.mode() == Swarm.Mode.WANDER) {
            swarm.wanderFailedLegs++;
            Mob anchor = swarm.vanguard();
            if (swarm.wanderFailedLegs >= table.failedLegsBeforeReheading() && anchor != null) {
                SwarmController.reHead(swarm, anchor);
                // 向きを変えたら待たずに引き直してよい。
                swarm.legBackoffUntil = 0L;
                swarm.legFailures = 0;
            }
        }
    }

    /**
     * いまの区間で先導役が行き先へ近づいたか(区間を始めたときより {@value #LEG_PROGRESS} ブロック以上縮んだ、
     * または行き先の {@value #LEG_PROGRESS} ブロック以内)。区間を歩き終えたとき・打ち切るときの {@link #noteLegOutcome} に使う。
     */
    public static boolean legMadeProgress(Mob vanguard, Swarm swarm) {
        Vec3 goal = swarm.legGoal;
        if (goal == null) {
            return false;
        }
        double now = horizontalDistance(vanguard.position(), goal);
        return now <= LEG_PROGRESS || now <= swarm.legStartDistance - LEG_PROGRESS;
    }

    /**
     * 区間の終点。遠ければ向きだけ使って {@code maxLength} で切り、読み込み済みの地面の高さに合わせる。
     * 地面は木を掘り抜いた高さ({@link TreeCanopyGroundFinder}。過繁茂の葉・巨大樹の幹はバニラの葉・幹のタグを持たない)。
     *
     * <p>2026-09-25 ユーザー報告「TD 中にターゲットへ行かず、湧いた場所でずっとくるくるしてスタック」: はじめは高さ地図(葉を除く)
     * だけで合わせていたので、過繁茂の森(TD の群れはそこから来る)では終点が<b>梢の上</b>になった。届かない部分経路が木の根元で
     * 終わり続け(先導役は進まず、後続は先導役の向きで決まる陣形の位置のまわりを回る)、登れる群れは木に登っていた。
     */
    private static Vec3 legEnd(ServerLevel level, Vec3 from, Vec3 goal, double maxLength) {
        double dx = goal.x - from.x;
        double dz = goal.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= maxLength) {
            return goal;
        }
        double t = maxLength / horizontal;
        double x = from.x + dx * t;
        double z = from.z + dz * t;
        double y = from.y + (goal.y - from.y) * t;
        LevelChunk chunk = level.getChunkSource().getChunkNow(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
        if (chunk != null) {
            // 水たまりの底を狙わない(手前の乾いた地面へ戻す。2026-09-25、湿地で水へ入り直し続けていた)。
            BlockPos ground = TreeCanopyGroundFinder.findDrySurfaceToward(level, Mth.floor(x), Mth.floor(z), from.x, from.z,
                    DRY_BACKTRACK);
            if (ground != null && (ground.getX() != Mth.floor(x) || ground.getZ() != Mth.floor(z))) {
                x = ground.getX() + 0.5D;
                z = ground.getZ() + 0.5D;
            }
            int surface = ground != null ? ground.getY()
                    : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x) & 15, Mth.floor(z) & 15) + 1;
            // 地下・洞窟の中の群れは地表へ引っ張らない(高さの差が大きければ直線のまま)。仮決め 2026-09-25。
            if (Math.abs(surface - y) <= SURFACE_SNAP_RANGE) {
                y = surface;
            }
        }
        return new Vec3(x, y, z);
    }

    /** 区間の終点を地面へ合わせてよい高さの差(ブロック)。仮決め 2026-09-25。 */
    private static final double SURFACE_SNAP_RANGE = 12.0D;
    /** 区間の終点が水の中なら、先導役の方へ戻って乾いた地面を探す列の数。仮決め 2026-09-25。 */
    private static final int DRY_BACKTRACK = 8;

    /** 狩り・攻城のときだけ泳いで渡る(暇なときは泳がず向きを変える)。 */
    private static boolean modeMaySwim(Swarm swarm) {
        Swarm.Mode mode = swarm.mode();
        return mode == Swarm.Mode.HUNT || mode == Swarm.Mode.SIEGE;
    }

    /** {@code from} → {@code to} の直線の下に水面があるか(読み込み済みのチャンクだけ見る)。 */
    private static boolean waterOnRoute(ServerLevel level, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, Mth.ceil(length / WATER_PROBE_STEP));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            int x = Mth.floor(from.x + dx * t);
            int z = Mth.floor(from.z + dz * t);
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) {
                return false;
            }
            // MOTION_BLOCKING は液体も数える。チャンクの getHeight はいちばん上のマスそのもの(水なら水面のマス)。
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
            cursor.set(x, top, z);
            if (chunk.getFluidState(cursor).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 足跡を書き足す(司令塔が考えるたび・区間を切り替える直前)。先導役が今の区間を歩いていれば、通り過ぎたノードを
     * <b>通り道の中心</b>(ノード + 包絡のずれ)で足す。区間を歩いていなければ(囲んで殴っている・跳んだ・張り付いている)、
     * 前の点から 1 ブロック以上動いたときに先導役の位置を足す(後続が行き場を失わないように)。
     *
     * <p>ナビがブロックの変化で区間を<b>同じ上書きで</b>引き直していたら({@link InvaderNavigation#continuesRequest})、
     * 引き直した経路を区間の続きとして引き継ぐ(レビュー指摘: はじめは引き直しに上書きが掛からず、先導役自身の体の経路に
     * 黙って置き換わり、足跡も先導役の位置へ落ちていた)。区間がほかの経路に置き換わったときも、置き換わるまでに通った
     * ノードは先に書き切る。
     */
    static void recordTrail(Swarm swarm) {
        Mob vanguard = swarm.vanguard();
        if (vanguard == null || swarm.kind() != Swarm.Kind.GROUND) {
            return;
        }
        Trail trail = swarm.trail();
        Path leg = swarm.currentLeg;
        Path walking = vanguard.getNavigation().getPath();
        if (leg != null && walking != leg) {
            // 置き換わった区間は、置き換わった時点までの進み具合で止まっている。そこまでを書き切る。
            appendPassed(swarm, leg);
            if (walking != null && InvaderNavigation.continuesRequest(vanguard, walking, swarm.legRoot)) {
                // ブロックの変化で、同じ包絡・同じ水の扱いで引き直した続き。区間として引き継ぐ(ずれも同じ)。
                swarm.currentLeg = walking;
                swarm.legRecorded = 0;
                leg = walking;
            } else {
                // 区間はほかの Goal(跳ぶ・立て直し)に置き換えられた。以後は先導役の位置で記録する。
                swarm.currentLeg = null;
                leg = null;
            }
        }
        if (leg != null) {
            appendPassed(swarm, leg);
            if (trail.isEmpty()) {
                trail.append(vanguard.position(), Trail.Kind.WALK);
            }
            return;
        }
        Vec3 pos = vanguard.position();
        Vec3 last = trail.newest();
        if (last == null) {
            trail.append(pos, Trail.Kind.WALK);
            return;
        }
        double dx = pos.x - last.x;
        double dz = pos.z - last.z;
        double dy = pos.y - last.y;
        if (dx * dx + dz * dz >= 1.0D || Math.abs(dy) >= 1.0D) {
            Trail.Kind kind;
            if (vanguard.isInWater()) {
                kind = Trail.Kind.SWIM;
            } else if (dy > 1.0D && dx * dx + dz * dz < 1.0D) {
                kind = Trail.Kind.CLIMB;
            } else {
                kind = Trail.Kind.WALK;
            }
            trail.append(pos, kind);
        }
    }

    /** 区間のうち、先導役が通り過ぎたのにまだ書いていないノードを、通り道の中心で足跡へ足す。 */
    private static void appendPassed(Swarm swarm, Path leg) {
        Trail trail = swarm.trail();
        int passed = Math.min(leg.getNextNodeIndex(), leg.getNodeCount());
        double offset = swarm.legCorridorOffset;
        // 登る群れの攻城の区間(壁を横へ・天井を這う辺がある)だけ、足場の無いノードも CLIMB と書く。
        InvaderNavigation.Envelope envelope = swarm.legEnvelope;
        ServerLevel level = envelope != null && envelope.crawl() ? swarm.level() : null;
        for (int i = Math.max(0, swarm.legRecorded); i < passed; i++) {
            Node node = leg.getNode(i);
            Node prev = i > 0 ? leg.getNode(i - 1) : null;
            trail.append(node.x + offset, node.y, node.z + offset, kindOf(level, node, prev));
        }
        swarm.legRecorded = Math.max(swarm.legRecorded, passed);
    }

    /**
     * いまの区間の通り過ぎたノードを書き切って、区間を外す(先導役が跳んだとき。{@link Swarm#noteVanguardLeap})。
     * 先導役の位置は足さない。
     */
    static void flushLeg(Swarm swarm) {
        Path leg = swarm.currentLeg;
        if (leg != null && swarm.kind() == Swarm.Kind.GROUND) {
            appendPassed(swarm, leg);
        }
        swarm.currentLeg = null;
    }

    /**
     * ノードの通り方。水のノードは SWIM、前のノードと同じ列で高さだけ違えば(壁の縦の辺)CLIMB。
     * {@code crawlLevel} があれば(登る群れの攻城の区間。壁を横へ・天井を這う・角を回る辺がある)、足場の無いノード
     * (真下のマスが足場にならない = 壁・天井に張り付いているところ)も CLIMB(後続がそこで張り付く。2026-09-25)。
     */
    private static Trail.Kind kindOf(@Nullable ServerLevel crawlLevel, Node node, @Nullable Node prev) {
        if (node.type == PathType.WATER) {
            return Trail.Kind.SWIM;
        }
        if (prev != null && prev.x == node.x && prev.z == node.z && prev.y != node.y) {
            return Trail.Kind.CLIMB;
        }
        if (crawlLevel != null && prev != null && !standsOn(crawlLevel, node)) {
            return Trail.Kind.CLIMB;
        }
        return Trail.Kind.WALK;
    }

    /**
     * ノードの真下のマスが足場になるか(読み込まれていなければ「なる」= 歩く扱い)。
     * フェンス・塀の上(真下のマスに当たり判定が食い込んでいるだけ)は足場に数えない —— 登らないと着けない点なので、後続には
     * CLIMB として張り付かせる(2026-10-02。数えて WALK にしたら、後続が塀を登り切る前にカーソルが点を通り過ぎて塀の前で跳び続けた)。
     */
    private static boolean standsOn(ServerLevel level, Node node) {
        BlockPos below = new BlockPos(node.x, node.y - 1, node.z);
        LevelChunk chunk = level.getChunkSource().getChunkNow(below.getX() >> 4, below.getZ() >> 4);
        return chunk == null || chunk.getBlockState(below).blocksMotion()
                || !chunk.getFluidState(below).isEmpty();
    }

    // --- 後続の立て直し ---

    /**
     * 後続が自分の体で {@code point}(ふつうは足跡のカーソルの点)へ経路を 1 本引く(札を 1 本使う)。
     * 次の点が SWIM なら、その 1 回だけ泳ぐ経路にする。歩き始めるのは呼び出し側({@code navigation.moveTo})。
     * 歩き終えたら経路を捨て({@code navigation.stop()})、足跡の純追跡へ戻ること。
     */
    public static LegResult requestRecovery(Mob follower, Swarm swarm, Vec3 point) {
        if (!(follower.level() instanceof ServerLevel level) || !swarm.contains(follower)
                || swarm.isVanguard(follower)) {
            return new LegResult(Status.NOT_IN_SWARM, null);
        }
        if (!InvaderNavigation.canSearchNow(follower)) {
            // 宙・張り付きの最中はバニラが黙って null を返す。失敗に数えない(呼び出し側は次の tick にやり直す)。
            return new LegResult(Status.NOT_READY, null);
        }
        if (!tryAcquire(level, follower, false)) {
            return new LegResult(Status.NO_TOKEN, null);
        }
        count(swarm, RequestKind.RECOVERY);
        boolean water = swarm.followerNextKind(follower) == Trail.Kind.SWIM;
        BlockPos target = BlockPos.containing(point);
        // 登る群れの攻城の区間(壁を横へ・天井を這う・縁を乗り越える辺がある)を追っている後続は、立て直しの経路も同じ辺で引く
        // (自分の体の大きさで)。はじめは自分の体のまま(縦の辺だけ)で引いていたので、壁の上の縁を越える辺が無く、壁の上端で
        // 張り付いたまま行き詰まって剥がれ、また引いて …… を繰り返し、後続が壁を越えられなかった(GameTest
        // climberSwarmCrossesWallWithoutBreaking、2026-09-25)。
        InvaderNavigation.Envelope leg = swarm.legEnvelope != null ? swarm.legEnvelope : swarm.envelope();
        InvaderNavigation.Envelope own;
        if (leg != null && leg.crawl()) {
            own = new InvaderNavigation.Envelope(follower.getBbWidth(), follower.getBbHeight(), true, true);
        } else if (!swarm.legAllowsClimb() && SwarmRole.canClimb(follower.getType())) {
            // 区間が登らない群れ(壁を壊して入る攻城の群れ・登れない体が混じる狩りの群れ)は、立て直しの経路も登らない。自分の体のまま
            // 引くと、アルボラは種の既定で壁を登る辺を持ち、壁を壊す側の後続が壁を越えていた(レビュー指摘 2026-09-25)。
            own = new InvaderNavigation.Envelope(follower.getBbWidth(), follower.getBbHeight(), false);
        } else {
            own = null;
        }
        Path path = InvaderNavigation.withRequest(follower, own, water,
                () -> follower.getNavigation().createPath(target, 1));
        if (path == null || path.getNodeCount() == 0) {
            return new LegResult(Status.FAILED, null);
        }
        return new LegResult(Status.OK, path);
    }

    /**
     * 立て直しの結果を報告する。失敗(経路が無い・歩いても進まない)が {@code recoveryMaxFailures} 回続いたら、
     * 自然の群れからは外し(ふつうの個体に戻る)、イベント・TD の群れはカーソルを近い点へ戻して続けさせる。
     * TD の群れは、戻しても {@value #TD_RELEASE_FALLBACKS} 回続けて付いて来られなければ外す(自分でターゲットへ向かう。2026-09-25)。
     */
    public static RecoveryOutcome noteRecoveryOutcome(Mob follower, Swarm swarm, boolean success) {
        Swarm.Member member = swarm.member(follower);
        if (member == null) {
            return RecoveryOutcome.EJECTED;
        }
        if (success) {
            member.recoveryFailures = 0;
            member.recoveryFallbacks = 0;
            return RecoveryOutcome.CONTINUE;
        }
        member.recoveryFailures++;
        if (member.recoveryFailures < Swarm.table().recoveryMaxFailures()) {
            return RecoveryOutcome.CONTINUE;
        }
        member.recoveryFailures = 0;
        if (swarm.origin() == SwarmOrigin.TD && ++member.recoveryFallbacks >= TD_RELEASE_FALLBACKS) {
            // TD の群れは、付いて来られない一員を群れから外し、自分の経路でターゲットへ向かわせる({@code td/TdAssaultGoal}。
            // 群れにいる間は動かない)。2026-09-25(ユーザー報告「湧いた場所でずっとくるくるしてスタック」の調べ): 先導役が森の木の間を
            // 自分の体の幅で抜けるようになると、通れない大きい一員(スカトゥリウム・スポロフィル・フィロセンス)は湧いた場所で
            // 立て直しに失敗し続け、カーソルを戻しては同じ所へ引き直すのを永久に繰り返していた。
            SwarmManager.leave(follower);
            return RecoveryOutcome.EJECTED;
        }
        if (swarm.origin().keepsStragglers()) {
            swarm.retreatCursor(follower);
            return RecoveryOutcome.FALLBACK;
        }
        SwarmManager.leave(follower);
        return RecoveryOutcome.EJECTED;
    }

    // --- 札の入れ物 ---

    /**
     * ワールドごとの札の入れ物。1 tick に {@code tokensPerTick} 本。取れなかった要求は待ちの列に並び、
     * 次の tick からは列の前の要求から札を渡す(先導役の列が先、同じ列の中は並んだ順)。
     * 2 tick 続けて要求しなかった(Goal が止まった)要求は列から外す。
     */
    static final class TokenBucket {
        private long tick = Long.MIN_VALUE;
        private int used;
        /** entity id → 最後に要求した tick。挿入順 = 並んだ順。 */
        private final LinkedHashMap<Integer, Long> vanguards = new LinkedHashMap<>();
        private final LinkedHashMap<Integer, Long> followers = new LinkedHashMap<>();

        boolean acquire(int entityId, boolean vanguard, long now, int perTick) {
            if (now != this.tick) {
                this.tick = now;
                this.used = 0;
                purge(this.vanguards, now);
                purge(this.followers, now);
            }
            int free = perTick - this.used;
            if (free <= 0) {
                enqueue(vanguard ? this.vanguards : this.followers, entityId, now);
                return false;
            }
            int rank = rank(entityId, vanguard);
            if (rank < free) {
                this.used++;
                (vanguard ? this.vanguards : this.followers).remove(entityId);
                return true;
            }
            enqueue(vanguard ? this.vanguards : this.followers, entityId, now);
            return false;
        }

        /** 自分より前に並んでいる要求の数(先導役の列 → 後続の列)。並んでいなければ列の長さ(最後尾)。 */
        private int rank(int entityId, boolean vanguard) {
            int ahead = 0;
            for (Integer id : this.vanguards.keySet()) {
                if (vanguard && id == entityId) {
                    return ahead;
                }
                ahead++;
            }
            if (vanguard) {
                return ahead;
            }
            for (Integer id : this.followers.keySet()) {
                if (id == entityId) {
                    return ahead;
                }
                ahead++;
            }
            return ahead;
        }

        private static void enqueue(LinkedHashMap<Integer, Long> queue, int entityId, long now) {
            // 並び順は最初に並んだときのまま(put は既存のキーの順番を変えない)。
            queue.put(entityId, now);
        }

        private static void purge(LinkedHashMap<Integer, Long> queue, long now) {
            Iterator<Map.Entry<Integer, Long>> it = queue.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > 2L) {
                    it.remove();
                }
            }
        }
    }

    /**
     * ナビがブロックの変化で<b>群れの経路</b>(区間・立て直し)を引き直す直前に、札を 1 本取る({@code entity/InvaderNavigation} から)。
     * 取れなければ false —— ナビは引き直しを 1 tick 遅らせてやり直す(レビュー指摘: 引き直しが札の入れ物を素通りしていた)。
     * 群れに居ない個体(群れを抜けた直後など)は札を取らずに true。数えは区間(先導役)/ 立て直し(後続)に入る。
     */
    public static boolean acquireRecompute(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return true;
        }
        Swarm swarm = SwarmManager.get(mob);
        if (swarm == null) {
            return true;
        }
        boolean vanguard = swarm.isVanguard(mob);
        if (!tryAcquire(level, mob, vanguard)) {
            return false;
        }
        count(swarm, vanguard ? RequestKind.LEG : RequestKind.RECOVERY);
        return true;
    }

    /** 札を 1 本取る。取れなければ数え({@link #denied})を増やして false。 */
    static boolean tryAcquire(ServerLevel level, Mob mob, boolean vanguard) {
        TokenBucket bucket = SwarmManager.bucket(level);
        boolean ok = bucket.acquire(mob.getId(), vanguard, level.getGameTime(), Swarm.table().tokensPerTick());
        if (!ok) {
            denied++;
        }
        return ok;
    }

    // --- テスト用の数え ---

    private static void count(Swarm swarm, RequestKind kind) {
        REQUESTS[kind.ordinal()]++;
        swarm.pathRequests[kind.ordinal()]++;
    }

    /** サーバー全体で群れが引いた探索の数(起動からの累計。テスト用)。 */
    public static long requests(RequestKind kind) {
        return REQUESTS[kind.ordinal()];
    }

    /** 札が取れずに待たされた回数(累計。テスト・計測用)。 */
    public static long denied() {
        return denied;
    }

    /** 数えを 0 に戻す(テスト用)。群れごとの数え({@link Swarm#pathRequests})は戻さない。 */
    public static void resetCounters() {
        java.util.Arrays.fill(REQUESTS, 0L);
        denied = 0L;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 区間の写しを作る(ノードの並びを新しい一覧へ。ナビの刈り込みが元の区間を書き換えても影響しない)。 */
    static Path copyPath(Path leg, Mob walker) {
        int n = leg.getNodeCount();
        List<Node> nodes = new ArrayList<>(n);
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            nodes.add(leg.getNode(i));
            double d = walker.position().distanceToSqr(leg.getEntityPosAtNode(walker, i));
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        Path copy = new Path(nodes, leg.getTarget(), leg.canReach());
        copy.setNextNodeIndex(nearest);
        return copy;
    }

    private SwarmPaths() {
    }
}
