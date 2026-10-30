package com.yayuy.overgrownvegetation.swarm;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.InvaderNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 1 つの<b>群れ</b>の実行時のデータ(群れ v2、2026-09-25)。保存しない —— 読み込まれた個体の所属
 * ({@link SwarmMembership})から {@link SwarmManager} が組み直す。
 *
 * <p>ユーザー指定(2026-09-25):「1 群れに司令塔 1 つ、1 匹が先導」。司令塔はこのデータと
 * {@link SwarmController}(10 tick ごとに考える)で、エンティティではない。後続は自分で標的を探さず、経路も引かず、
 * うろつかず、群れの走査もしない。
 *
 * <h2>Goal からの使い方</h2>
 * <ul>
 *     <li><b>Swarm を持ち続けない</b>。毎 tick {@link SwarmManager#get(Mob)} で引き直す(合流・組み直しで別の物になる)。</li>
 *     <li>読むだけのもの: 先導役・標的・モード・行き先・足跡・包絡・陣形の位置・遅れ・囲む位置・汚染を広げる場所。
 *     どれも司令塔が考えた時点の値で、Goal の tick の間は変わらない(標的の位置など、相手の今の位置から出す値を除く)。</li>
 *     <li>書くもの: 後続のカーソル({@link #followerSteerPoint})・汚染を広げた報告・跳んだ報告・諦めた相手・TD のフック。</li>
 * </ul>
 *
 * <p>サーバースレッドだけが触る。{@link Mob} への参照は読み込まれている間だけ持つ(離れた個体は
 * {@link SwarmManager} の Leave の処理と、考えるたびの見直しで外れる)。
 */
public final class Swarm {

    /** 群れの種類。 */
    public enum Kind {
        /** 地上の群れ(アルボラ・スカトゥリウム・スポロフィル・フィロセンス)。 */
        GROUND,
        /** 空の群れ(ヴェロフィルだけ)。 */
        AIR
    }

    /**
     * 群れのモード。
     * <ul>
     *     <li>{@link #HUNT} … 標的がいる。</li>
     *     <li>{@link #SPREAD} … 暇なので止まって全員で汚染を広げている(ユーザー指定 2026-09-25)。</li>
     *     <li>{@link #WANDER} … 暇。過繁茂バイオームの外へ向かう(外ではぶらつく)。</li>
     *     <li>{@link #SIEGE} / {@link #BREACH} … TD の標的の塊を攻めている / 壁を壊している。TD だけが入れる・外す。</li>
     * </ul>
     * 「その場で待つ」モードは無い(根を張ったフィロセンスは遅れて、あとで追いつく)。
     */
    public enum Mode {
        HUNT, SPREAD, WANDER, SIEGE, BREACH
    }

    /**
     * 群れの標的を選んだ理由。小さいほど優先({@link #rank})。
     * ユーザー指定の「視線持ちのプレイヤー優先」「どれだけ離れていても感知して襲いに行く」は {@link #WATCHED}。
     *
     * <p>仮決め 2026-09-25(ユーザー未確認): 反撃({@link #RETALIATION})は狙い({@link #HUNT})と視線持ち({@link #WATCHED})の
     * <b>下</b>、ふつうのプレイヤー({@link #PLAYER})の上。はじめは反撃がいちばん上で、殴ったプレイヤーが誰でも次の探索で
     * 小規模レイドの狙い・視線持ちから群れを奪っていた(レビュー指摘。殴られたときの規則「今の標的がプレイヤーなら替えない」とも、
     * 「視線持ちのプレイヤー優先」とも食い違っていた)。
     */
    public enum TargetReason {
        /** 小規模レイドの狙い・湧かせた側が指定した相手。距離の上限なし。 */
        HUNT(0),
        /** 視線持ちのプレイヤー(群れの誰かが誘導を受けているときだけ)。距離の上限なし。 */
        WATCHED(1),
        /** 群れの誰かを殴ったプレイヤー(追いかける距離は {@link #PLAYER} と同じ)。 */
        RETALIATION(2),
        /** 追跡範囲の中のプレイヤー。 */
        PLAYER(3),
        /** プレイヤー以外(グループ1〜3 の獲物・グループ外の生き物)。 */
        OTHER(4);

        public final int rank;

        TargetReason(int rank) {
            this.rank = rank;
        }
    }

    /** 1 体ぶんの実行時の状態。 */
    static final class Member {
        final Mob mob;
        final SwarmRole role;
        int slot;
        /** いま動いている(エンティティとして tick されている)か。考えるたびに決め直す。 */
        boolean active;

        // 陣形(地上)
        int formationIndex = -1;
        long formationSeq = -1L;
        @Nullable
        Vec3 formationPoint;
        /** 陣形の位置で横へずれているか(ずれていなければ足跡の上)。 */
        boolean inLane;
        double rowDepth;
        boolean lagging;
        /** 後続の「次に向かう点」(足跡の番号)。-1 なら未定。 */
        long cursor = -1L;

        // 標的
        @Nullable
        LivingEntity handed;
        @Nullable
        LivingEntity localTarget;
        long localUntil;
        int engageIndex;
        int engageCount;
        double engageBaseAngle;
        /** {@link #engageBaseAngle} を決めたときに囲んでいた相手(同じ相手を囲み続けている間は向きを保つ)。 */
        @Nullable
        LivingEntity ringTarget;
        boolean blockEngaged;

        // 汚染を広げる
        @Nullable
        BlockPos spreadSpot;
        boolean spreadStarted;
        boolean spreadFinished;

        // 立て直し
        int recoveryFailures;
        /** 立て直しに失敗し続けてカーソルを手前へ戻した回数(TD の群れはこれが続くと群れから外す)。 */
        int recoveryFallbacks;

        // 空
        int airIndex = -1;

        Member(Mob mob, SwarmRole role, int slot) {
            this.mob = mob;
            this.role = role;
            this.slot = slot;
        }
    }

    /** 「まだ一度も」を表す時刻(引き算で桁あふれしない大きさの負の値)。 */
    static final long NEVER = Long.MIN_VALUE / 4L;

    private final UUID id;
    private final ServerLevel level;
    private final Kind kind;
    final SwarmOrigin origin;
    final String eventId;
    /**
     * 壁・天井を登って進む群れか(TD の攻め手の一部。2026-09-25 のユーザー指定「アルボラは壁や天井を通ってターゲットブロックへ
     * 行くルートも」)。最初に登録された一員の所属({@link SwarmMembership#climber})から決まり、群れが生きている間は変わらない。
     * 登るのは地上の全員が登れる種のときだけ(包絡。{@code SwarmController#envelope})。
     */
    final boolean climber;

    /** 読み込まれている一員(加わった順)。 */
    final LinkedHashMap<Mob, Member> members = new LinkedHashMap<>();

    final long createdTick;
    boolean firstThinkDone;
    /** Leave の処理で立てる。次に考えるときに見直す。 */
    boolean dirty;
    /**
     * 読み込み外れた(チャンクごと・見えない範囲へ)まま、まだ戻っていない一員の UUID。戻れば(登録で)外す。
     * 空いていない間は残党の合流も空きの判定もしない(残りがまだ読み込まれていないだけかもしれない)。
     * 最後に外れてから {@code settleWindowTicks} 経っても戻らなければ、戻らないものとして忘れる({@link #membersMissing})。
     * はじめは「一度でも外れたか」の印で、一度立つと二度と下りなかった(レビュー指摘: シミュレーション距離の縁を歩く群れは
     * すぐ印が立ち、以後ずっと防衛の追加・召喚が別の群れになり、残党の合流も起きなかった)。
     */
    final Set<UUID> departed = new HashSet<>();
    long lastDepartureTick = NEVER;
    int peak;
    /** 所属に書いた peak(書き直すかの判定)。 */
    int peakWritten;

    @Nullable
    Mob vanguard;
    long electedTick;
    /** 新しい先導役へ引き継ぐ区間(先導の Goal の start で使う)。前の先導役の<b>区間</b>の写しだけ(ナビの経路は写さない)。 */
    @Nullable
    Path handOverPath;
    /** 引き継ぐ区間を引いたときの包絡・通り道の中心のずれ・水を渡るか(足跡を区間と同じずれで記録するため)。 */
    @Nullable
    InvaderNavigation.Envelope handOverEnvelope;
    double handOverCorridorOffset = 0.5D;
    boolean handOverWater;

    // 標的
    @Nullable
    LivingEntity target;
    TargetReason targetReason = TargetReason.OTHER;
    long targetSince;
    /** 獲物・部外者を箱で探した結果の控え(次に箱で探すまで、生きていて条件を満たす限り使う)。 */
    @Nullable
    LivingEntity cachedOther;
    long lastTargetSearch = NEVER;
    long lastOtherSearch = NEVER;
    boolean anyGuided;
    @Nullable
    UUID retaliationPlayer;
    long retaliationUntil;
    /** 小規模レイドの狙い(hunt_target)・湧かせた側が指定したプレイヤー。 */
    @Nullable
    UUID huntTarget;
    int huntLostTicks;
    /** 狙いへ向かう区間が続けて失敗した数({@code raid/SmallRaidHunt#unreachable} で諦める)。 */
    int huntFailedLegs;
    /** 諦めた hunt_target の文字列(あとから読み込まれた一員の狙いも消す)。 */
    final List<String> givenUpHunts = new ArrayList<>(1);
    /** 諦めた相手 → いつまで狙わないか。 */
    final Map<UUID, Long> gaveUpUntil = new HashMap<>();
    /**
     * 狙い(HUNT)以外の標的へ向かう区間が続けて進まなかった数と、その相手({@link SwarmPaths#noteLegOutcome})。
     * 小規模レイドの「届かない」と同じ回数で群れとして諦める(レビュー指摘 2026-09-25: 諦める口に呼び手が無く、
     * 閉じこもったプレイヤーを群れがいつまでも追っていた)。
     */
    int targetFailedLegs;
    @Nullable
    UUID targetFailedLegsFor;
    /** 囲む Goal で一員が最後に標的を殴った時刻と相手(群れとして「届いている」かの目安。{@link #engageHitRecently})。 */
    long lastEngageHitTick = NEVER;
    @Nullable
    UUID lastEngageHitTarget;

    // モード
    Mode mode = Mode.WANDER;
    @Nullable
    BlockPos targetBlock;
    @Nullable
    BlockPos breachPos;
    double siegeEngageRadius = -1.0D;
    @Nullable
    Vec3 destination;
    /** 外へ向かう候補(近い外から順)。使い切ったらぶらつく。 */
    List<Vec3> headings = List.of();
    int headingIndex;
    double lastHeadingAngle = Double.NaN;
    int wanderFailedLegs;
    int idleTicks;
    int nextSpreadIdleTicks = -1;
    long spreadStartTick;
    /** 汚染を広げている間の中心(先導役の足元・空の群れは先頭の真下の地面)。SPREAD でなければ null。 */
    @Nullable
    BlockPos spreadCentre;

    // 陣形・待つ
    final Trail trail;
    @Nullable
    InvaderNavigation.Envelope envelope;
    boolean anyLagging;
    int waitTicks;
    double paceSpeed;
    boolean vanguardEngaged;
    /**
     * 攻城(SIEGE)で先導役が標的の塊に着いたか。着くまでは後続も塊を囲まず、先導役は区間を引き続ける
     * (塊が壁の向こうなら区間が塞がれ、TD が壁を壊すと決められる)。
     */
    boolean siegeArrived;
    /**
     * この時刻までは「塊に着いた」としない({@link #holdSiegeArrival})。TD の見張りが、着いたのに塊を叩けず壊す壁も
     * 見つからない先導役を区間へ戻すときに立てる(着いたままだと先導の Goal が止まり、回り道も壁選びも起きない)。
     */
    long siegeArrivalHoldUntil = NEVER;
    @Nullable
    Vec3 lastVanguardPos;
    long lastVanguardMoveTick;

    // 区間
    @Nullable
    Path currentLeg;
    /**
     * いまの区間を歩き始めたときの経路(ナビへ渡した物)。ナビがブロックの変化で区間を引き直しても変わらない
     * ({@code InvaderNavigation#continuesRequest} で「同じ上書きで引き直した続きか」を確かめる鍵)。
     */
    @Nullable
    Path legRoot;
    /** いまの区間を引いたときの包絡(無ければ null = 先導役自身の体)。 */
    @Nullable
    InvaderNavigation.Envelope legEnvelope;
    int legRecorded;
    double legCorridorOffset = 0.5D;
    @Nullable
    Vec3 legGoal;
    double legStartDistance;
    long legBackoffUntil;
    int legFailures;
    int consecutiveBlockedLegs;
    /**
     * 残りこの本数の区間は、群れの包絡(いちばん大きい一員の体)ではなく<b>先導役の体の幅</b>で引く({@link SwarmPaths#requestLeg})。
     * 包絡で引いた区間が塞がれたときに立てる(2026-09-25)。
     */
    int slimLegs;
    /**
     * 直前に頼んだ区間の行き先が攻城の塊だったか。塊と防衛側のあいだで行き先が替わったら {@link #consecutiveBlockedLegs} を
     * 数え直す({@link SwarmPaths#requestLeg}。防衛側を追って塞がれた区間の数えで、塊の方向の壁を選ばせない。レビュー指摘 2026-09-25)。
     */
    boolean lastLegAtBlock;
    /** 直前の区間が水だけで塞がれていた。次の区間は水を渡る(狩り・攻城のときだけ)。 */
    boolean nextLegWater;
    boolean currentLegWater;
    /** {@link SwarmPaths#requestLeg} が引いた区間の行き先と包絡(followLeg で使う)。 */
    @Nullable
    Vec3 pendingLegGoal;
    @Nullable
    InvaderNavigation.Envelope pendingEnvelope;
    double pendingCorridorOffset = 0.5D;
    boolean pendingLegWater;

    // テスト・計測
    @Nullable
    AABB testBounds;
    final long[] pathRequests = new long[SwarmPaths.RequestKind.values().length];

    Swarm(UUID id, ServerLevel level, Kind kind, SwarmOrigin origin, String eventId, boolean climber, long now) {
        this.id = id;
        this.level = level;
        this.kind = kind;
        this.origin = origin;
        this.eventId = eventId;
        this.climber = climber;
        this.createdTick = now;
        this.trail = new Trail(table().trailCapacity());
        this.lastVanguardMoveTick = now;
    }

    static SwarmTable table() {
        return DesignData.swarm();
    }

    // --- 基本 ---

    public UUID id() {
        return this.id;
    }

    public ServerLevel level() {
        return this.level;
    }

    public Kind kind() {
        return this.kind;
    }

    public SwarmOrigin origin() {
        return this.origin;
    }

    /** イベントを見分ける値(無ければ "")。 */
    public String eventId() {
        return this.eventId;
    }

    /** 壁・天井を登って進む群れか({@link #climber} のフィールドの説明)。 */
    public boolean climber() {
        return this.climber;
    }

    /** 読み込まれている一員の数。 */
    public int size() {
        return this.members.size();
    }

    public boolean isEmpty() {
        return this.members.isEmpty();
    }

    /** これまでにいちばん大きかったときの頭数。 */
    public int peak() {
        return this.peak;
    }

    // --- 読み込み外れた一員 ---

    /** 一員が読み込み外れた(Leave の理由が null / UNLOADED_TO_CHUNK、または見直しで見えなくなった)。 */
    void noteDeparture(UUID member, long now) {
        this.departed.add(member);
        this.lastDepartureTick = now;
    }

    /** 一員が(また)登録された。 */
    void noteArrival(UUID member) {
        this.departed.remove(member);
    }

    /**
     * 読み込み外れたまま戻っていない一員がいるか(いれば残党の合流・空きの判定をしない)。
     * 最後に外れてから {@code settleWindowTicks} 経っていれば、戻らないものとして忘れて false(仮決め 2026-09-25。
     * 戻ってきた一員は自分の所属で登録し直され、群れが無くなっていれば 1 体の群れとして残党の規則で合流する)。
     */
    boolean membersMissing(long now) {
        if (this.departed.isEmpty()) {
            return false;
        }
        if (now - this.lastDepartureTick >= table().settleWindowTicks()) {
            this.departed.clear();
            return false;
        }
        return true;
    }

    /** 読み込まれている一員の写し(加わった順)。 */
    public List<Mob> members() {
        return List.copyOf(this.members.keySet());
    }

    /** 動いている(エンティティとして tick されている)一員の写し。考えた時点の分け方。 */
    public List<Mob> activeMembers() {
        List<Mob> out = new ArrayList<>(this.members.size());
        for (Member m : this.members.values()) {
            if (m.active) {
                out.add(m.mob);
            }
        }
        return out;
    }

    public int activeCount() {
        int n = 0;
        for (Member m : this.members.values()) {
            if (m.active) {
                n++;
            }
        }
        return n;
    }

    public boolean contains(Mob mob) {
        return this.members.containsKey(mob);
    }

    /** 考えた時点で動いていたか。 */
    public boolean isActive(Mob mob) {
        Member m = this.members.get(mob);
        return m != null && m.active;
    }

    @Nullable
    Member member(Mob mob) {
        return this.members.get(mob);
    }

    /** その一員の役割。一員でなければ null。 */
    @Nullable
    public SwarmRole role(Mob mob) {
        Member m = this.members.get(mob);
        return m != null ? m.role : null;
    }

    /** その一員の番号(陣形の並べ替えの鍵)。一員でなければ -1。 */
    public int slot(Mob mob) {
        Member m = this.members.get(mob);
        return m != null ? m.slot : -1;
    }

    // --- 先導役 ---

    /** 先導役(空の群れでは先頭)。選べる一員が動いていなければ null。 */
    @Nullable
    public Mob vanguard() {
        return this.vanguard;
    }

    public boolean isVanguard(Mob mob) {
        return mob == this.vanguard;
    }

    /** 先導役以外の一員。 */
    public boolean isFollower(Mob mob) {
        return mob != this.vanguard && this.members.containsKey(mob);
    }

    /**
     * 先導役が交戦中か(先導の Goal は譲り、囲む Goal が動く)。渡された標的が囲む距離({@code engageRadius} + 余り)の内側、
     * または攻城で標的の塊・壊す壁を囲んでいる({@link #blockEngageSlot} が null でない)とき。
     */
    public boolean isVanguardEngaged() {
        return this.vanguardEngaged;
    }

    /** 最後に先導役が動いて(1 ブロック以上)から経った tick。後続の立て直しは「先導役が動いているのに進めない」ときだけ。 */
    public long ticksSinceVanguardMoved() {
        return this.level.getGameTime() - this.lastVanguardMoveTick;
    }

    // --- 標的・モード ---

    @Nullable
    public LivingEntity target() {
        return this.target;
    }

    public TargetReason targetReason() {
        return this.targetReason;
    }

    public Mode mode() {
        return this.mode;
    }

    /** 暇な群れが向かう先(外へ出る向き・ぶらつく先)。 */
    @Nullable
    public Vec3 destination() {
        return this.destination;
    }

    /**
     * 先導役がいま向かうべき点。狩りなら標的、攻城なら標的の塊、暇なら行き先。
     * 汚染を広げている間・壁を壊している間は null(区間を引かない)。
     */
    @Nullable
    public Vec3 leadGoal() {
        switch (this.mode) {
            case SPREAD, BREACH -> {
                return null;
            }
            case SIEGE -> {
                if (this.target != null && this.target.isAlive()) {
                    return this.target.position();
                }
                return this.targetBlock != null ? Vec3.atBottomCenterOf(this.targetBlock) : null;
            }
            case HUNT -> {
                return this.target != null && this.target.isAlive() ? this.target.position() : this.destination;
            }
            default -> {
                return this.destination;
            }
        }
    }

    /** 群れの体の包絡(先導役の区間の探索に掛ける)。地上の群れで一度も考えていなければ null。 */
    @Nullable
    public InvaderNavigation.Envelope envelope() {
        return this.envelope;
    }

    /**
     * いまの区間(無ければ群れの包絡)が壁を登ってよいか。どちらも無ければ true(種の既定)。区間が登らない群れの後続は、
     * 立て直しの経路でも登らない({@code SwarmPaths#requestRecovery}・{@code AbstractOvergrownInvaderEntity#shouldStartSwarmClimb}。
     * レビュー指摘 2026-09-25)。
     */
    public boolean legAllowsClimb() {
        InvaderNavigation.Envelope e = this.legEnvelope != null ? this.legEnvelope : this.envelope;
        return e == null || e.canClimb();
    }

    /** 足跡。 */
    public Trail trail() {
        return this.trail;
    }

    /** 動いている一員のうちいちばん遅い移動速度(属性値)。先導役はこの速さで歩く(交戦中を除く)。 */
    public double paceSpeed() {
        return this.paceSpeed;
    }

    // --- 陣形・後続 ---

    /** 陣形の番号(先導役・空の群れ・動いていない個体は -1)。 */
    public int formationIndex(Mob mob) {
        Member m = this.members.get(mob);
        return m != null ? m.formationIndex : -1;
    }

    /** 陣形の位置(横の並びが使えればずらした位置)。考えた時点の値。先導役・空の群れは null。 */
    @Nullable
    public Vec3 formationPoint(Mob mob) {
        Member m = this.members.get(mob);
        return m != null ? m.formationPoint : null;
    }

    /** 陣形の位置の足跡の番号(カーソルがここまで来たら陣形の位置へ向かう)。 */
    public long formationSeq(Mob mob) {
        Member m = this.members.get(mob);
        return m != null ? m.formationSeq : -1L;
    }

    /** 自分の陣形の位置から {@code laggardDistance} 以上離れているか。 */
    public boolean isLagging(Mob mob) {
        Member m = this.members.get(mob);
        return m != null && m.lagging;
    }

    public boolean anyLagging() {
        return this.anyLagging;
    }

    /**
     * 先導役が止まって待つべきか(ユーザー指定 2026-09-15「アルボラが勝手に飛び出さず、味方を連れてくるように」)。
     * 遅れている一員がいる間、{@code waitCapTicks} まで。数えはみんなが追いついたときだけ 0 に戻る。
     */
    public boolean shouldVanguardWait() {
        return this.anyLagging && this.waitTicks < table().waitCapTicks()
                && (this.mode == Mode.WANDER || this.mode == Mode.HUNT || this.mode == Mode.SIEGE)
                && !this.vanguardEngaged;
    }

    /**
     * 後続が今向かう点(<b>純追跡</b>)。カーソル(足跡の番号)を 1 ブロック以内に来た点から先へ進め、
     * 自分の陣形の番号まで来たら陣形の位置へ向かう。陣形の位置に着いていれば null(立ち止まる = 移動の指示を出さない)。
     *
     * <p>カーソルは群れの側に持つ(Goal に持たせない。組み直しで足跡が替わるため)。毎 tick 呼んでよい(軽い)。
     *
     * @return 向かう点。null なら立ち止まる
     */
    @Nullable
    public Vec3 followerSteerPoint(Mob mob) {
        Member m = this.members.get(mob);
        if (m == null || mob == this.vanguard || this.kind != Kind.GROUND) {
            return null;
        }
        Vec3 goal = m.formationPoint;
        if (this.trail.isEmpty() || m.formationSeq < 0L) {
            return goal != null && !arrived(mob.position(), goal) ? goal : null;
        }
        Vec3 pos = mob.position();
        // 先導役が替わって足跡の先が捨てられた直後は、陣形の番号が足跡の外を指しうる(次に考えるまで)。
        long formationSeq = Math.min(m.formationSeq, this.trail.newestSeq());
        if (!this.trail.has(m.cursor)) {
            // 初回・足跡が一周して消えた・先が捨てられた: いまの位置にいちばん近い点から。
            m.cursor = this.trail.nearestSeq(pos);
        }
        // 押されて先へ出ていたら、数点先までで自分にいちばん近い点へ飛ぶ(後戻りしない)。
        long ahead = this.trail.nearestSeq(pos, m.cursor, Math.min(m.cursor + LOOKAHEAD_POINTS, formationSeq + 1L));
        if (ahead > m.cursor) {
            m.cursor = ahead;
        }
        while (m.cursor < formationSeq && horizontalNear(pos, m.cursor, CURSOR_REACH)) {
            m.cursor++;
        }
        if (m.cursor >= formationSeq) {
            // 自分の番号まで来た。陣形の位置へ(横の並びはここでだけずれる)。
            return goal != null && !arrived(pos, goal) ? goal : null;
        }
        return this.trail.point(m.cursor);
    }

    /** 後続の次の点の通り方(張り付き・水から上がるか・跳ぶかの判断に)。カーソルが無ければ null。 */
    @Nullable
    public Trail.Kind followerNextKind(Mob mob) {
        Member m = this.members.get(mob);
        if (m == null || !this.trail.has(m.cursor)) {
            return null;
        }
        return this.trail.kind(m.cursor);
    }

    /** 後続のカーソル(足跡の番号)。未定なら -1。 */
    public long followerCursor(Mob mob) {
        Member m = this.members.get(mob);
        return m != null ? m.cursor : -1L;
    }

    /**
     * 立て直しに失敗し続けた後続(外せない群れ)を、<b>いまの位置にいちばん近い点</b>からやり直させる。
     * 立て直しの経路はその点へ引き直す。
     */
    public void retreatCursor(Mob mob) {
        Member m = this.members.get(mob);
        if (m != null && !this.trail.isEmpty()) {
            m.cursor = this.trail.nearestSeq(mob.position());
        }
    }

    /**
     * 後続の歩く速さの倍率。陣形の位置から {@code catchUpDistance} 以上離れていれば走って追いつく。
     * 先導役はいちばん遅い一員の速さで歩くので、遅い種(フィロセンス)も根を張ったあと追いつける。
     */
    public double followSpeed(Mob mob) {
        Member m = this.members.get(mob);
        if (m == null || m.formationPoint == null) {
            return 1.0D;
        }
        double d = table().catchUpDistance();
        return mob.position().distanceToSqr(m.formationPoint) > d * d ? table().catchUpSpeed() : 1.0D;
    }

    private boolean horizontalNear(Vec3 pos, long seq, double reach) {
        double dx = pos.x - this.trail.x(seq);
        double dz = pos.z - this.trail.z(seq);
        double dy = pos.y - this.trail.y(seq);
        return dx * dx + dz * dz <= reach * reach && Math.abs(dy) <= CURSOR_REACH_DY;
    }

    /** カーソルを進める距離(水平、ブロック)。 */
    private static final double CURSOR_REACH = 1.0D;
    /** カーソルを進めてよい高さの差(壁を登り切っていない点を飛ばさない)。 */
    private static final double CURSOR_REACH_DY = 1.5D;
    /** 陣形の位置に「着いた」水平の距離(2 乗)。 */
    private static final double ARRIVE_SQ = 0.6D * 0.6D;
    /** 陣形の位置に「着いた」とみなす高さの差(ブロック)。仮決め 2026-09-25。 */
    private static final double ARRIVE_DY = 3.0D;

    /**
     * 陣形の位置に着いたか: 水平に {@code 0.6} 以内で、高さの差が {@value #ARRIVE_DY} 以内。
     *
     * <p>2026-09-25 ユーザー報告「TD 中に湧いた場所でずっとくるくるしてスタック」: はじめは 3 次元で 0.6 以内だった。足跡が 1 点しか無い
     * (先導役がまだ歩いていない)間の陣形の位置は、先導役の背中側へ<b>先導役の高さのまま</b>延ばした点なので、段々・坂では宙や地面の中になる。
     * 後続はその真下(真上)まで来ても「着かない」まま向かい続け、バニラの MoveControl が水平にほぼ 0 の向きへ体を回し、跳び続けて
     * その場で回っていた(先導役が向きを変えるたびに点も背中側へ回る)。
     */
    private static boolean arrived(Vec3 pos, Vec3 goal) {
        double dx = pos.x - goal.x;
        double dz = pos.z - goal.z;
        return dx * dx + dz * dz <= ARRIVE_SQ && Math.abs(pos.y - goal.y) <= ARRIVE_DY;
    }
    /** 押されて先へ出たとき、飛んでよい点の数。 */
    private static final long LOOKAHEAD_POINTS = 4L;

    // --- 標的の受け渡し ---

    /** 司令塔がその一員へ渡した標的(自分だけの反撃を含む)。 */
    @Nullable
    public LivingEntity handedTarget(Mob mob) {
        Member m = this.members.get(mob);
        return m != null ? m.handed : null;
    }

    /** 殴られた一員が自分だけで反撃している相手(期限内のとき)。 */
    @Nullable
    public LivingEntity localTarget(Mob mob) {
        Member m = this.members.get(mob);
        if (m == null || m.localTarget == null || this.level.getGameTime() >= m.localUntil) {
            return null;
        }
        return m.localTarget;
    }

    /**
     * 囲んで殴る相手({@code SwarmEngageGoal} が使う)。前衛(スカトゥリウムを除く。あちらは自分の突進・迫撃砲・追跡の Goal)で、
     * 渡された標的が {@code engageRadius}(+ 揺れよけ)以内のときだけ。それ以外は null。
     */
    @Nullable
    public LivingEntity engageTarget(Mob mob) {
        Member m = this.members.get(mob);
        if (m == null || m.role != SwarmRole.FRONT || SwarmRole.isShielder(mob.getType())
                || m.handed == null || !m.handed.isAlive()) {
            return null;
        }
        double r = table().engageRadius() + table().clearMargin();
        return mob.distanceToSqr(m.handed) <= r * r ? m.handed : null;
    }

    /**
     * 囲む位置(標的のまわりの輪の上。同じ相手を囲む前衛の中での番号で角度を割り振る)。
     * 半径 = 標的の半幅 + 自分の半幅 + 間合いの余り。相手の<b>今の</b>位置から出す。囲む相手が無ければ null。
     */
    @Nullable
    public Vec3 engageSlot(Mob mob) {
        Member m = this.members.get(mob);
        LivingEntity target = engageTarget(mob);
        if (m == null || target == null || m.engageCount <= 0) {
            return null;
        }
        double radius = target.getBbWidth() * 0.5D + mob.getBbWidth() * 0.5D + ENGAGE_REACH_PAD;
        double angle = m.engageBaseAngle + (Math.PI * 2.0D) * m.engageIndex / m.engageCount;
        return new Vec3(target.getX() + Math.cos(angle) * radius, target.getY(), target.getZ() + Math.sin(angle) * radius);
    }

    /**
     * 攻城(SIEGE / BREACH)で、標的の塊(または壊す壁)を囲む位置。前衛で、エンティティの標的を持っておらず、
     * しかも<b>塊の近くにいる</b>ときだけ(司令塔が考えるたびに決める。{@code SwarmController#handTargets}):
     * <ul>
     *     <li>先導役 … 壁を壊している間(BREACH)はいつでも。攻城(SIEGE)では塊に着いてから(輪 + 2 ブロック以内。
     *     着いたあとは {@code engageRadius} + {@code clearMargin} まで)。それまでは先導の Goal が区間を引く。</li>
     *     <li>後続 … 先導役が塊に着いてから(BREACH ではいつでも)、塊から {@code engageRadius} 以内
     *     (入ったあとは + {@code clearMargin} まで)。</li>
     * </ul>
     * 塊の中心から 半幅 + 自分の半幅 + 余り の輪の上。無ければ null。
     * はじめは距離を見ずに全前衛を入れていた(レビュー指摘: 先導役まで囲む Goal に取られて区間を引かず、壁を壊す判断が起きず、
     * 後続は経路なしで塊へ直進していた)。
     */
    @Nullable
    public Vec3 blockEngageSlot(Mob mob) {
        Member m = this.members.get(mob);
        BlockPos block = this.mode == Mode.BREACH && this.breachPos != null ? this.breachPos : this.targetBlock;
        if (m == null || block == null || !m.blockEngaged || m.engageCount <= 0) {
            return null;
        }
        double radius = blockRingRadius(mob);
        double angle = m.engageBaseAngle + (Math.PI * 2.0D) * m.engageIndex / m.engageCount;
        Vec3 c = Vec3.atBottomCenterOf(block);
        // 高さは自分の足の高さ(輪は水平に回り込むだけ。手が届くかは canReachBlock が上下も見る)。2026-09-25: はじめは塊の高さで、
        // 頭の高さの壁・台の上の塊・床に埋めた塊では輪の位置が宙や地面の中になり、真下(真上)まで来ても「着かない」まま回り続けた。
        return new Vec3(c.x + Math.cos(angle) * radius, mob.getY(), c.z + Math.sin(angle) * radius);
    }

    /** 塊を囲む輪の半径(塊の半幅 + 自分の半幅 + 余り)。 */
    static double blockRingRadius(Mob mob) {
        return 0.5D + mob.getBbWidth() * 0.5D + ENGAGE_REACH_PAD;
    }

    /** 囲む輪の半径の余り(ブロック)。仮決め 2026-09-25。 */
    private static final double ENGAGE_REACH_PAD = 0.6D;

    /**
     * 塊・壁に手が届く余り(ブロック)。体の箱を横へ {@value}、下へ {@value #BLOCK_REACH_DOWN}、上へ {@value #BLOCK_REACH_UP}
     * 広げた箱が塊の箱と重なれば届く({@link #canReachBlock})。
     *
     * <p>仮決め 2026-09-25(レビュー指摘): はじめは横だけ広げていた。{@code AABB#intersects} は面が触れただけでは重ならないので、
     * 床と面一に埋めたターゲット・1 段の台に載せたターゲット・壊す壁の 2〜3 段目に、背の低いアルボラ(0.98。フェーズ 5 の編成の
     * ただ一つの前衛)の手が一度も届かなかった。下 1 = 足元の 1 段(床に埋めた塊)まで、上 1.5 = アルボラでも足の段から 3 段目
     * (TD が開ける穴の高さの上限 {@code SwarmSiegeHooks#MAX_BREACH_CELLS})まで。
     */
    public static final double BLOCK_REACH_SIDE = 0.8D;
    /** {@link #BLOCK_REACH_SIDE} を見よ。 */
    public static final double BLOCK_REACH_DOWN = 1.0D;
    /** {@link #BLOCK_REACH_SIDE} を見よ。 */
    public static final double BLOCK_REACH_UP = 1.5D;
    /**
     * 塊の<b>真上のマスが空いている</b>とき(上面が見えている)の下への手の届く余り(ブロック)。足の段から 2 段下 = 1 マスの穴の底まで。
     * 2026-09-25 のユーザー報告(runClient 3 回目)「ターゲットブロックを囲むと、一部のアルボラがターゲットの真上のブロックは壊すが
     * 1 マスの隙間に入れず、ターゲットを攻撃できないでスタックする」: 下 {@value #BLOCK_REACH_DOWN} では囲いの屋根の上(ターゲットの
     * 2 段上)から穴の底のターゲットに届かず、アルボラ(幅 1 = 経路では 2 マス)は 1 マスの穴へ入れもしなかった。仮決め。
     */
    public static final double BLOCK_REACH_DOWN_OPEN = 2.0D;

    /**
     * その一員の手が塊({@code block} の 1 マス)に届くか。囲む Goal が叩く前に見る。
     * 塊の真上のマスに当たりが無ければ、上から {@value #BLOCK_REACH_DOWN_OPEN} 段下まで届く(穴の縁から底へ手を伸ばす)。
     */
    public static boolean canReachBlock(Mob mob, BlockPos block) {
        AABB box = mob.getBoundingBox();
        double down = BLOCK_REACH_DOWN;
        if (box.minY >= block.getY() + 1.0D - 1.0E-3D && box.minY - BLOCK_REACH_DOWN <= block.getY() + 1.0D + 1.0E-3D
                && openTop(mob.level(), block)) {
            down = BLOCK_REACH_DOWN_OPEN;
        }
        AABB reach = new AABB(box.minX - BLOCK_REACH_SIDE, box.minY - down, box.minZ - BLOCK_REACH_SIDE,
                box.maxX + BLOCK_REACH_SIDE, box.maxY + BLOCK_REACH_UP, box.maxZ + BLOCK_REACH_SIDE);
        return reach.intersects(new AABB(block));
    }

    /**
     * 高さだけを見て、その一員の手が塊の段に届くか({@link #canReachBlock} の上下の幅 + {@code slack})。
     * 司令塔が「先導役が塊に着いた」を決めるときに使う(3D の距離だけでは、床の下に埋めた・1 階上に置いた塊でも着いたことになる)。
     * 塊の真上が空いていれば下は {@value #BLOCK_REACH_DOWN_OPEN} まで({@link #canReachBlock} と同じ)。
     */
    public static boolean withinBlockReachHeight(Mob mob, BlockPos block, double slack) {
        double down = block.getY() + 1.0D <= mob.getY() + 1.0E-3D && openTop(mob.level(), block)
                ? BLOCK_REACH_DOWN_OPEN : BLOCK_REACH_DOWN;
        double low = mob.getY() - down - slack;
        double high = mob.getY() + mob.getBbHeight() + BLOCK_REACH_UP + slack;
        return block.getY() < high && block.getY() + 1.0D > low;
    }

    /** 塊の真上のマスに当たりが無いか(読み込まれていなければ false。読み込まない)。 */
    private static boolean openTop(Level level, BlockPos block) {
        BlockPos above = block.above();
        BlockState state;
        if (level instanceof ServerLevel server) {
            LevelChunk chunk = server.getChunkSource().getChunkNow(above.getX() >> 4, above.getZ() >> 4);
            if (chunk == null) {
                return false;
            }
            state = chunk.getBlockState(above);
        } else {
            state = level.getBlockState(above);
        }
        return state.getCollisionShape(level, above).isEmpty();
    }

    /**
     * {@code untilTick} まで「塊に着いた」としない(TD だけが呼ぶ)。着いたのに塊を叩けず、壊す壁も見つからないとき、
     * 先導役を区間へ戻して回り道を探させる(レビュー指摘 2026-09-25: 着いた扱いのままだと先導の Goal が止まり、
     * 群れ全体が壁に体を押し付けたまま動かなかった)。
     */
    public void holdSiegeArrival(long untilTick) {
        this.siegeArrivalHoldUntil = untilTick;
        this.siegeArrived = false;
    }

    /**
     * 群れとしてその相手を諦める({@code giveUpTicks} のあいだ渡さない)。群れの標的ならその場で外す。
     * 呼ぶところ(2026-09-25): 囲む Goal で先導役が相手へまっすぐ向かっても壁で近づけない回が続いた
     * ({@code SwarmEngageGoal})、狙い以外の標的へ区間が続けて進まなかった({@link SwarmPaths#noteLegOutcome})、
     * 先導役の坑道が掘れないものに当たった({@code AbstractOvergrownInvaderEntity#giveUpOnTarget})。
     * 一員は自分では標的を諦めない(外しても次に考えるときにまた渡されるだけ)。
     */
    public void giveUpTarget(LivingEntity entity) {
        this.gaveUpUntil.put(entity.getUUID(), this.level.getGameTime() + table().giveUpTicks());
        if (this.target == entity) {
            this.target = null;
            this.cachedOther = null;
        }
        if (entity instanceof Player && entity.getUUID().equals(this.retaliationPlayer)) {
            this.retaliationPlayer = null;
        }
        if (entity.getUUID().equals(this.targetFailedLegsFor)) {
            this.targetFailedLegs = 0;
            this.targetFailedLegsFor = null;
        }
    }

    /** 囲む Goal で一員が {@code entity} を殴った(当たった)。群れとして「届いている」目安にする。 */
    public void noteEngageHit(LivingEntity entity) {
        this.lastEngageHitTick = this.level.getGameTime();
        this.lastEngageHitTarget = entity.getUUID();
    }

    /** 直近 {@code ticks} tick 以内に、群れの誰かが囲む Goal で {@code entity} を殴ったか。 */
    public boolean engageHitRecently(LivingEntity entity, int ticks) {
        return entity.getUUID().equals(this.lastEngageHitTarget)
                && this.level.getGameTime() - this.lastEngageHitTick <= ticks;
    }

    public boolean hasGivenUp(LivingEntity entity) {
        Long until = this.gaveUpUntil.get(entity.getUUID());
        return until != null && this.level.getGameTime() < until;
    }

    /**
     * 湧かせた側・呼んだ側が標的を指定する(防衛の群れ → プレイヤー、召喚 → 殴った相手など)。
     * プレイヤーなら小規模レイドの狙いと同じ扱い(距離の上限なし、死亡・観戦・クリエイティブ・長く不在で諦める)。
     * それ以外はその場で群れの標的にする(追いかける距離はふつうの相手と同じ)。
     */
    public void commandTarget(LivingEntity entity) {
        this.gaveUpUntil.remove(entity.getUUID());
        if (entity instanceof Player player) {
            this.huntTarget = player.getUUID();
            this.huntLostTicks = 0;
            this.huntFailedLegs = 0;
            setTarget(player, TargetReason.HUNT);
        } else {
            setTarget(entity, TargetReason.OTHER);
        }
    }

    /**
     * 湧かせた側が<b>勧める</b>標的(レイドのウェーブ・攻城戦の増援。{@link SwarmOrigin#huntsInitialTarget} が false の出どころ)。
     * {@link #commandTarget} と違って狙い({@link TargetReason#HUNT}: 距離の上限なし・ほかの理由に奪われない)にはせず、
     * プレイヤーはふつうのプレイヤー({@link TargetReason#PLAYER}: 一員の追跡範囲 × {@code leashMultiplier} を出たら外す。
     * 殴った相手・視線持ちには替わる)、それ以外は {@link TargetReason#OTHER} として持つ。今の標的のほうが優先なら替えない。
     *
     * <p>仮決め 2026-09-25(ユーザー確認待ち、レビュー指摘): 以前のウェーブ・増援は 1 体ずつ {@code setTarget} していただけで、
     * ふつうの索敵の Goal が働いた。群れにしたとき {@link #commandTarget} で狙いにしたので、永続の群れがビーコン・攻城の場を
     * 離れて 1 人のプレイヤーをどこまでも追うようになっていた(「レイド中は苗床の周りに留まらせたい」と食い違う)。
     */
    public void offerTarget(LivingEntity entity) {
        this.gaveUpUntil.remove(entity.getUUID());
        TargetReason reason = entity instanceof Player ? TargetReason.PLAYER : TargetReason.OTHER;
        if (this.target == null || reason.rank < this.targetReason.rank) {
            setTarget(entity, reason);
        }
    }

    void setTarget(@Nullable LivingEntity entity, TargetReason reason) {
        if (entity != this.target) {
            this.targetSince = this.level.getGameTime();
        }
        this.target = entity;
        this.targetReason = entity != null ? reason : TargetReason.OTHER;
    }

    // --- 汚染を広げる ---

    /** 汚染を広げている最中か。 */
    public boolean isSpreading() {
        return this.mode == Mode.SPREAD;
    }

    /** 汚染を広げる場所(汚染を広げている間だけ)。持っていなければ null。 */
    @Nullable
    public BlockPos spreadSpot(Mob mob) {
        if (this.mode != Mode.SPREAD) {
            return null;
        }
        Member m = this.members.get(mob);
        return m != null ? m.spreadSpot : null;
    }

    /**
     * 汚染を広げている間の中心(地上の群れは先導役の足元、空の群れは先頭の真下の地面の上のマス)。SPREAD でなければ null。
     * 空の群れで場所をもらえなかった一員の降り先・降りていない先頭の旋回の中心に使う(レビュー指摘 2026-09-25)。
     */
    @Nullable
    public BlockPos spreadCentre() {
        return this.mode == Mode.SPREAD ? this.spreadCentre : null;
    }

    /** 汚染の場(うずくまる・座る・索敵)を始めた。 */
    public void noteSpreadStarted(Mob mob) {
        Member m = this.members.get(mob);
        if (m != null && this.mode == Mode.SPREAD) {
            m.spreadStarted = true;
        }
    }

    /** 始めた場を汚し尽くした(または打ち切った)。始めた全員が終わったら群れは次へ進む。 */
    public void noteSpreadFinished(Mob mob) {
        Member m = this.members.get(mob);
        if (m != null && this.mode == Mode.SPREAD) {
            m.spreadFinished = true;
        }
    }

    // --- 跳ぶ ---

    /**
     * 先導役が川などを跳び越えた。着地点を LEAP の点として足跡へ足す(後続は手前から同じように跳ぶ)。
     * 跳ぶ Goal の着地の瞬間(または跳び出した瞬間に着地の予定点で)呼ぶ。
     */
    public void noteVanguardLeap(Vec3 landing) {
        // 跳ぶ手前までに通った区間のノードを、LEAP の点より前に書き切る(足跡の並びは通った順)。
        // 先導役の位置は足さない —— 着地の瞬間に呼ばれると、着地点が WALK の点として LEAP の前に入り、後続が水へ歩いて入る。
        // 跳んだ先は区間の経路の外なので、区間は外す(flushLeg)。次の点からは先導役の位置で記録し直す。
        SwarmPaths.flushLeg(this);
        this.trail.append(landing, Trail.Kind.LEAP);
    }

    // --- 空の群れ ---

    /** 空の群れの V 字の番号(先頭は -1)。 */
    public int airFormationIndex(Mob mob) {
        Member m = this.members.get(mob);
        return m != null ? m.airIndex : -1;
    }

    /**
     * 空の後続の横のずれ(ブロック、先導役の進む向きに対して右が +)。番号 0 → 右 1 列目、1 → 左 1 列目、2 → 右 2 列目…
     * 先頭・一員でなければ 0。
     */
    public double airLateralOffset(Mob mob) {
        int i = airFormationIndex(mob);
        if (i < 0) {
            return 0.0D;
        }
        int rank = i / 2 + 1;
        double side = (i % 2 == 0) ? 1.0D : -1.0D;
        return side * rank * table().airLateralSpacing();
    }

    // --- TD のフック(td-design) ---

    /** 標的の塊を攻める(SIEGE)。TD だけが呼ぶ。 */
    public void setSiege(BlockPos block) {
        if (!block.equals(this.targetBlock)) {
            this.siegeArrived = false;
            this.siegeArrivalHoldUntil = NEVER;
        }
        this.targetBlock = block.immutable();
        this.breachPos = null;
        this.mode = Mode.SIEGE;
        this.destination = null;
    }

    /** 攻城中に壁を壊す(BREACH)。区間は引かず、壊し終えたら {@link #endBreach}。 */
    public void setBreach(BlockPos pos) {
        this.breachPos = pos.immutable();
        this.mode = Mode.BREACH;
    }

    /**
     * 壁が壊れた(または諦めた)。SIEGE へ戻り、次の区間を引き直す。区間の失敗の数えも戻す(2026-09-25: 戻していなかったので、
     * 壁を壊すたびに次の区間の待ちが 160 tick の上限から始まっていた)。
     */
    public void endBreach() {
        this.breachPos = null;
        if (this.targetBlock != null) {
            this.mode = Mode.SIEGE;
        }
        this.legBackoffUntil = 0L;
        this.legFailures = 0;
        this.consecutiveBlockedLegs = 0;
    }

    /**
     * {@code untilTick} まで区間を頼まない(TD が崖・溝に橋を架けている間、群れを手前で待たせる。{@code td/TdBridges})。
     * 待ちは区間の失敗に数えない(先導役は {@code BACKOFF} で止まるだけ)。
     */
    public void holdLegs(long untilTick) {
        this.legBackoffUntil = Math.max(this.legBackoffUntil, untilTick);
    }

    /** 区間の待ちと失敗の数えを戻す(橋が架かった・諦めた: 次の区間をすぐ群れの体の包絡で引く)。 */
    public void resumeLegs() {
        this.legBackoffUntil = 0L;
        this.legFailures = 0;
        this.consecutiveBlockedLegs = 0;
        this.slimLegs = 0;
    }

    /** 攻城をやめる(イベントの終わり・中止)。暇な群れに戻る。 */
    public void clearSiege() {
        this.targetBlock = null;
        this.breachPos = null;
        this.siegeArrived = false;
        this.siegeArrivalHoldUntil = NEVER;
        if (this.mode == Mode.SIEGE || this.mode == Mode.BREACH) {
            this.mode = this.target != null ? Mode.HUNT : Mode.WANDER;
        }
    }

    /** 攻城中、標的の塊より先に相手をする防衛側の距離(先導役から)。負なら {@code swarm.json} の値。 */
    public void setSiegeEngageRadius(double radius) {
        this.siegeEngageRadius = radius;
    }

    double siegeEngageRadius() {
        return this.siegeEngageRadius >= 0.0D ? this.siegeEngageRadius : table().siegeEngageRadius();
    }

    @Nullable
    public BlockPos targetBlock() {
        return this.targetBlock;
    }

    @Nullable
    public BlockPos breachPos() {
        return this.breachPos;
    }

    /**
     * 標的へ向かう区間が続けて塞がれた回数(引けなかった、または先導役の 3 ブロック先で終わる部分経路)。
     * 届く区間が引けたら 0。TD の「壁を壊すか」の判断に使う。
     */
    public int consecutiveBlockedLegs() {
        return this.consecutiveBlockedLegs;
    }

    // --- テスト・計測 ---

    /** テスト用: 標的をこの箱の中だけで探す(並んで走るほかのテストの群れ・Mob を狙わない)。null で解除。 */
    public void setTestBounds(@Nullable AABB bounds) {
        this.testBounds = bounds;
    }

    /** この群れが引いた経路探索の数(区間 / 後続の立て直し)。テスト用。 */
    /** 調べもの用(GameTest の失敗の文言): 先導と区間の状態。 */
    public String debugState() {
        long now = this.level.getGameTime();
        return "mode=" + this.mode + " arrived=" + this.siegeArrived + " engaged=" + this.vanguardEngaged
                + " blocked=" + this.consecutiveBlockedLegs + " failures=" + this.legFailures
                + " backoff=" + Math.max(0L, this.legBackoffUntil - now) + " slim=" + this.slimLegs
                + " holdArrival=" + Math.max(0L, this.siegeArrivalHoldUntil - now) + " leg=" + (this.currentLeg != null)
                + " legs=" + pathRequests(SwarmPaths.RequestKind.LEG);
    }

    public long pathRequests(SwarmPaths.RequestKind kind) {
        return this.pathRequests[kind.ordinal()];
    }

    @Override
    public String toString() {
        return "Swarm[" + this.id.toString().substring(0, 8) + " " + this.kind + " " + this.origin + " " + this.mode
                + " size=" + this.members.size() + "]";
    }

    /** 表示用の短い ID(先頭 8 桁)。 */
    public String shortId() {
        return this.id.toString().substring(0, 8);
    }

    /** 読み込まれている一員の状態の写し(内部用、並びは加わった順)。 */
    List<Member> memberStates() {
        return List.copyOf(this.members.values());
    }

    /** 変更できない一員の表(内部用)。 */
    Map<Mob, Member> memberView() {
        return Collections.unmodifiableMap(this.members);
    }
}
