package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
import com.yayuy.overgrownvegetation.swarm.Trail;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmApproachHandler;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmAttacker;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmEngageGoal;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmFollowGoal;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmGoals;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmLeadGoal;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmSpreadGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * グループ4(拡大種)の Mob 共通の土台 — {@link MobGroup#GROUP_4}。
 *
 * <p>汚染シダ({@link BlightFernEntity})が「その場に根を張って広げる」側なのに対して、
 * 拡大種は<b>自分の足で汚染を運ぶ</b>側。行動は 4 本柱:
 * <ol>
 *     <li><b>外へ出る</b> — 群れ({@link Swarm})で過繁茂バイオームの外へ向かって歩く(暇な群れの行き先は司令塔が決める)。
 *     汚染地形の縁で止まらず、その先へ侵入していく。群れに居ない個体(スポーンエッグ・{@code /summon}・外湧き・群れから
 *     外れた個体)は外へ歩いて出ない(旧い群れの Goal は 2026-09-25 に消えた)。</li>
 *     <li><b>襲う</b> — プレイヤーと、グループ1〜10 に属していない生き物すべてを襲う
 *     ({@link Predation#isInvaderTarget})。牛や村人のような無害な相手も対象。</li>
 *     <li><b>休んで胞子をばらまき、そこを汚す</b> — <b>汚染されていない地面の上</b>にいて標的が
 *     見つからないとき、その場に留まってくつろいだ姿勢で胞子をばらまく({@link InvaderSporeCrouchGoal})。
 *     周りの Mob は胞子感染し、<b>その間だけ</b>足元の周りが汚染ブロックへ変わっていく。
 *     終わりぎわに確率で汚染シダ・過繁茂 Mob が増える。</li>
 * </ol>
 * <b>汚染が広がるのはこのうずくまりの最中だけ</b>(ユーザー指示 2026-09-14)。歩いているだけでは
 * 何も汚さないので、「汚れている場所 = 拡大種が腰を落ち着けた場所」になる。
 *
 * <h2>捕食(2026-09-15)</h2>
 * グループ4 は<b>グループ1〜3 を獲物として狩る</b>(ユーザー要望)。グループ3 がグループ2 を
 * 狩るのと同じ食物連鎖を、1 段上から掛け直した形({@link Predation#isInvaderPrey})。
 * <b>狩って強くなれるのはアルボラとスカトゥリウムだけ</b>({@link PredatorMob}: 1 体ごとに
 * 最大体力と攻撃力が複利で上がる)。ほかのグループ4 はその 2 種の地ならしに回る ——
 * 群れの司令塔が獲物を探して前衛へ渡し(旧 {@code InvaderPreyIntel} の役目。2026-09-25 に群れの標的探しへ統合)、
 * スポロフィルは獲物をバフの対象から外してデバフを掛ける({@link SporophyllAura})。
 *
 * <p>そして<b>死んだ瞬間、半径 {@code invaderDeathCorruptionRadius} ブロックを汚染する</b>
 * ({@link #die})。輪郭は {@link NoisyCorruptionBurst} のノイズで波打たせるので綺麗な球にはならない
 * (ユーザー要望 2026-09-13)。倒しても汚染は残る、という圧の掛け方。
 *
 * <h2>詰まらない足(2026-09-14)</h2>
 * 「上にブロックがあるのに段差へ挑み続ける」というユーザー報告への対処。3 段構え:
 * <ol>
 *     <li>{@link OvergrownNodeEvaluator} — 幅 1 ブロック以上の Mob ではバニラが省略してしまう
 *     「上りきるまでに体がつかえないか」の確認を必ず行い、<b>登れない段差を経路に含めない</b>。</li>
 *     <li>{@link #jumpFromGround()} — 頭上が塞がっているときは跳ばない(天井に頭をぶつけ続けない)。</li>
 *     <li>{@link InvaderUnstickGoal} — それでも進めなくなったら最優先で割り込んで<b>回り道</b>する。
 *     同じ相手を追って繰り返し詰まるなら、その相手を {@value #GIVE_UP_TICKS} tick のあいだ諦める。</li>
 * </ol>
 * 葉({@link PathType#LEAVES})を「通れる」扱いにしていたのも同時に取りやめた — 葉の当たり判定は
 * 1 ブロックまるごとなので、通れる扱いでは葉の中へ突っ込む経路が引かれて必ず詰まる。
 *
 * <h2>壁のぼり / 天井這い(2026-09-14)</h2>
 * {@link #canClimbWalls()} が {@code true} の種({@link ArboraEntity})は、
 * {@link PhalangiotarbusEntity} と同じ要領で<b>壁を登り、天井を逆さで這う</b>。
 * <ul>
 *     <li><b>登る</b> — どこかへ行こうとしていて壁にぶつかったら、その面に張り付いて登る
 *     ({@link #shouldStartClinging})。<b>面の上をどう進むかは {@link SurfaceCrawl} が地形を見て決める</b>
 *     —— 塞がっていれば横へ逸れ、角に当たれば面を乗り換え、進めなくなったら離す(2026-09-20)。
 *     用も無くそのへんの壁を登り出さないよう、標的を追っているか
 *     経路を歩いている最中に限る。登りきって壁が切れたら、そのまま上の地面へ出る。</li>
 *     <li><b>天井</b> — <b>標的を追っているときだけ</b>、壁を登りきって頭上に天井があればそのまま
 *     逆さに移り、標的の真上まで這って落ちかかる({@link #tickSurfaceCrawl})。</li>
 *     <li>張り付いている間は重力を切り、他の Mob に押されず、離れた直後
 *     {@value #FALL_GRACE_TICKS} tick は落下ダメージを受けない(自分で降りたぶんは痛くない)。</li>
 * </ul>
 * 体の傾き(壁で 90 度・天井で 180 度)は {@code getClimbAmount} / {@code getCeilingAmount} を見て
 * レンダラー側が付ける。専用アニメは持たず、脚は {@code walk} / {@code run} を流用する。
 *
 * <h2>群れ(v2、2026-09-25)</h2>
 * 「グループ4 の mob 達は基本的にグループ4 の mob 同士で集団行動する」(ユーザー要望 2026-09-15)は、2026-09-25 から
 * <b>1 群れに司令塔 1 つ、1 匹が先導</b>の形(ユーザー指定。{@code swarm/})。標的・行き先・陣形・汚染を広げる時機は
 * 司令塔が決め、一員は Goal で従うだけ。この土台が受け持つのは:
 * <ul>
 *     <li>群れの Goal を積む({@link #registerGoals} の優先度の並びを参照)。</li>
 *     <li>一員の間は個体の索敵・反撃・近接・散歩・うずくまり・誘導の狩り・小規模レイドの狩りを止める
 *     (それぞれの Goal の {@code canUse} が群れを見て落ちる。群れに居ない個体は今までどおり)。</li>
 *     <li>毎 tick の自己修復({@link SwarmMember#tick})、殴られたら司令塔へ({@link SwarmManager#onMemberHurt})、
 *     詰まり検知を群れの舵取りと喧嘩させない({@link #tickStuckDetection})、同じ群れで押し合わない、
 *     ポータルを使わない、旧 NBT の {@code PackId} の引き継ぎ。</li>
 * </ul>
 *
 * <p>{@link ParasitizedMob} 実装なので「胞子感染」には一切かからず
 * ({@link com.yayuy.overgrownvegetation.effect.ParasitizedSporeImmunity})、自分がばらまいた胞子や
 * 自分が汚した地面で自滅しない。グループ4 は
 * {@link com.yayuy.overgrownvegetation.phase.MobGroupFriendlyFire} の同士討ち禁止にも入っている。
 */
public abstract class AbstractOvergrownInvaderEntity extends PathfinderMob
        implements GeoEntity, ParasitizedMob, SwarmAttacker, SwarmApproachHandler {

    /** 通常(idle / walk / run を移動状態で切替)。 */
    public static final int STATE_IDLE = 0;
    /** 飛びつき中。 */
    public static final int STATE_LEAP = 1;
    /** 攻撃モーション中。 */
    public static final int STATE_ATTACK = 2;
    /** その場で休んで胞子をばらまいている(見た目はくつろいだ姿勢。{@link InvaderSporeCrouchGoal} 参照)。 */
    public static final int STATE_SPORE = 3;
    /**
     * 標的への坑道を掘っている({@link ArboraTunnelGoal}、2026-09-17)。アルボラだけが使う。
     *
     * <p>{@link #STATE_IDLE} でなくなるので、掘っているあいだは詰まり検知も
     * 誘導・小規模レイドの狩りも壁登り({@code shouldStartClinging})も動かない ——
     * 掘っている最中に横から割り込まれると坑道が中途半端に残る。
     */
    public static final int STATE_DIG = 4;

    protected static final EntityDataAccessor<Integer> DATA_ACTION_STATE =
            SynchedEntityData.defineId(AbstractOvergrownInvaderEntity.class, EntityDataSerializers.INT);
    /** 壁に張り付いて登っている。クライアントは体を 90 度立てる描画に使う。 */
    private static final EntityDataAccessor<Boolean> DATA_CLIMBING =
            SynchedEntityData.defineId(AbstractOvergrownInvaderEntity.class, EntityDataSerializers.BOOLEAN);
    /** 張り付いている壁の方向(水平)。{@link Direction#get3DDataValue()}。 */
    private static final EntityDataAccessor<Byte> DATA_CLIMB_FACING =
            SynchedEntityData.defineId(AbstractOvergrownInvaderEntity.class, EntityDataSerializers.BYTE);
    /** 天井に張り付いて逆さまに這っている。クライアントは体を 180 度回す描画に使う。 */
    private static final EntityDataAccessor<Boolean> DATA_ON_CEILING =
            SynchedEntityData.defineId(AbstractOvergrownInvaderEntity.class, EntityDataSerializers.BOOLEAN);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // --- 詰まり検知(「上にブロックがあるのに段差へ挑み続ける」対策。2026-09-14) ---

    /** 進めているかを見る間隔。 */
    private static final int PROGRESS_CHECK_TICKS = 20;
    /** 1 回の間隔でこれだけ(水平距離)進めていなければ「進めていない」。 */
    private static final double PROGRESS_MIN_DISTANCE = 0.6D;
    /** 「進めていない」がこの回数続いたら詰まり扱い(= 約 2 秒)。 */
    private static final int NO_PROGRESS_LIMIT = 2;
    /** 頭上が塞がっていて無駄になったジャンプがこの回数続いたら詰まり扱い。 */
    private static final int BLOCKED_JUMP_LIMIT = 2;
    /** 詰まりを憶えている時間。この間に繰り返し詰まったら標的そのものを諦める。 */
    private static final int STUCK_MEMORY_TICKS = 300;
    /** 同じ相手を追って詰まり続けたときに諦めるまでの回数。 */
    private static final int STUCKS_BEFORE_GIVE_UP = 2;
    /** 諦めた相手を狙わずにおく時間(10 秒)。 */
    private static final int GIVE_UP_TICKS = 200;
    /** 諦めた相手でも、これより近づいてきたら諦めを取り消す。 */
    private static final double GIVE_UP_FORGET_DISTANCE = 3.0D;
    /** 相手がいるのに経路が引けないまま立ち尽くしてよい時間(3 秒)。 */
    private static final int NO_APPROACH_LIMIT = 60;
    /**
     * 群れの囲む Goal の「壁で近づけない」({@link #noteSwarmApproachBlocked})を憶えておく時間(tick)。
     * 囲む Goal は毎 tick 伝えてくるので、途切れたらすぐ忘れる短さでよい。仮決め 2026-09-25。
     */
    private static final int SWARM_BLOCKED_MEMORY_TICKS = 5;
    /**
     * 経路が引けない相手へ<b>まっすぐ体を押し当てに行く</b>距離の上限(ブロック)。
     * これより遠い相手には歩き出さない —— 壁の向こうの相手を延々と押しても意味がない。
     */
    private static final double DIRECT_APPROACH_RANGE = 24.0D;
    /** 経路を引けなかった回数を憶えておく時間(tick)。これを過ぎると数え直し。 */
    private static final int PATH_FAILURE_MEMORY = 200;
    /** {@link #pushTowards} が「この先は水」と判断するのに読むマス数。 */
    private static final int WATER_PROBE_STEPS = 3;

    // --- 壁のぼり / 天井這い({@link #canClimbWalls} な種だけ。2026-09-14) ---

    /** 標的がこの距離(の2乗)まで真下に来たら、天井から落ちて襲いかかる。 */
    private static final double CEILING_DROP_RANGE_SQ = 2.25D;
    /** 壁へ押し付ける強さ(離れると張り付きが切れる)。 */
    private static final double CLIMB_PUSH = 0.12D;
    /** 壁・天井から離れた直後、落下ダメージを受けない時間(自分で降りたぶんは痛くない)。 */
    private static final int FALL_GRACE_TICKS = 40;
    /** 面を這う速さ(1 tick あたりのブロック数)。 */
    private static final double CLING_SPEED = 0.16D;
    /** 相手がこれだけ上にいたら、壁にぶつかるのを待たずに自分から登り始める(ブロック)。 */
    private static final double CLING_START_RISE = 2.5D;
    /** 経路の次の点がこれだけ上なら、経路の指示として登り始める(ブロック)。 */
    private static final double PATH_CLIMB_RISE = 1.2D;
    /** 1 回の張り付きで這い続けてよい上限(tick)。20 秒。 */
    private static final int CLING_MAX_TICKS = 400;
    /** 相手に近づけないまま這っていてよい上限(tick)。 */
    private static final int CLING_STALL_TICKS = 40;
    /** 「近づいた」と認める距離(2乗)の縮み。小刻みな揺れを進捗に数えないため。 */
    private static final double CLING_PROGRESS = 0.5D;
    /** 這う先が 1 tick にこれより(2 乗で)跳んだら「次の点へ移った」とみなす。経路の点は 1 ブロック以上離れている。仮決め 2026-09-25。 */
    private static final double CLING_GOAL_JUMP_SQ = 0.75D * 0.75D;
    /** 往復よけに憶えておくマスの数。 */
    private static final int CLING_TRAIL = 10;
    /** 張り付きをやめてから、貼り直せるようになるまで(tick)。 */
    private static final int CLING_REST_TICKS = 20;

    /**
     * <b>水を渡ってよいか</b>(2026-09-20 のユーザー報告「川があっても飛び越えようとしない」)。
     *
     * <p>経路探索は水マスに<b>重み 8</b>を付けている(バニラの Monster の既定)。
     * 1 マスの水たまりなら回り込めるが、<b>川は端から端まで水</b>なので、重み 8 が横に何十マスも
     * 並んでいることになり、<b>渡るより遠回りするほうが安い</b>と判断されて延々と岸を歩く。
     * 経路そのものも予算(展開ノード数)を食い潰して出なくなる。
     *
     * <p>遠くまで歩かせるあいだだけ重みを 0 にして、まっすぐ泳いで渡らせる。
     * {@code FloatGoal}(優先度 0)が水面で浮かせるので溺れない。
     * 保存はしないので、使う側が読み込み後にもう一度呼ぶこと。
     * 呼び出し元は今は無い(アルボラの巣の建設グループを 2026-09-24 に削除)。群れ(v2、2026-09-25)はこれを使わず、
     * 探索 1 回ぶんだけ重みを外す {@link InvaderNavigation#withRequest} を使う(置きっぱなしにすると近接・立て直しまで泳ぐ)。
     */
    public void setWaterCrossing(boolean crossing) {
        float malus = crossing ? 0.0F : 8.0F;
        setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, malus);
        setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER_BORDER, malus);
        // 重みが変わると、同じ場所・同じ目標でも見つかる経路が変わる。届かなかった探索の控えを捨てる
        // (2026-09-24 軽量化、報告 109 のレビュー指摘。控えは地形以外の探索の条件が同じ前提)。
        InvaderNavigation.forgetFailedSearch(this.navigation);
    }

    /** 攻撃モーションの残り tick。0 になったら {@link #STATE_IDLE} へ戻す。 */
    private int attackAnimTicks;

    /** 前回の進捗チェック時の位置。 */
    private Vec3 progressAnchor;
    private int progressTimer = PROGRESS_CHECK_TICKS;
    private int noProgressChecks;
    private int blockedJumps;
    /** 標的がいるのに経路が引けないまま経った tick。 */
    private int noApproachTicks;
    /**
     * 群れの囲む Goal が「相手へまっすぐ向かっても壁で近づけない」と伝えてきた期限({@link #noteSwarmApproachBlocked})。
     * これより前の tick の間は「回り道が無い」として張り付きを始める(群れ v2、2026-09-25、レビュー指摘)。
     */
    private int swarmBlockedUntil;
    /** 経路を引けなかった回数({@link InvaderNavigation} から数える)。 */
    private int pathFailures;
    /** {@link #pathFailures} を捨てるまでの残り tick。 */
    private int pathFailureDecay;
    /** {@link InvaderUnstickGoal} へ渡す「詰まった」の印。 */
    private boolean stuck;
    private int recentStucks;
    private int recentStuckDecay;
    /** 何度やっても辿り着けなかった相手。{@link #giveUpUntilTick} までは狙わない。 */
    private LivingEntity unreachableTarget;
    private int giveUpUntilTick;

    /** 体の傾き(0 = 水平/地上、1 = 垂直/壁)の補間値。描画専用。 */
    private float climbAmount;
    private float climbAmountO;
    /** 天井への逆さ張り付き(0 = 通常、1 = 完全に逆さ)の補間値。描画専用。 */
    private float ceilingAmount;
    private float ceilingAmountO;
    /** 壁・天井から離れた直後、落下ダメージを受けない猶予。 */
    private int fallGraceTicks;

    // --- 面を這う制御(SurfaceCrawl。2026-09-20 に作り直し) ---

    /** いまの張り付きが続いている tick。 */
    private int clingTicks;
    /** 相手に近づけていない tick。{@value #CLING_STALL_TICKS} を超えたら張り付きをやめる。 */
    private int clingStallTicks;
    /** これまでに詰めた標的までの距離(2乗)。これより縮まなければ「進めていない」。 */
    private double clingBestDistanceSq = Double.MAX_VALUE;
    /** 前の tick に這っていた先({@link #clingGoal})。これから大きく跳んだら(経路・足跡が次の点へ進んだ)、進み具合を数え直す。 */
    @Nullable
    private Vec3 clingGoalRef;
    /** 直前に通ったマス(往復よけ)。 */
    private final Deque<BlockPos> clingTrail = new ArrayDeque<>();
    /** 張り付きをやめたあと、貼り直してよくなる {@code tickCount}。 */
    private int clingRestUntil;
    /**
     * 面の縁を回り込んでいる途中の行き先({@link SurfaceCrawl} の「外側の角を巻く」。庇の裏 → 庇の外の面など)。null なら回り込んでいない。
     * 回り込む間は宙のマスを通るので、張り付きの判定・面の上の 1 歩を止め、元の面のまま行き先へ動かす(2026-09-25)。
     */
    @Nullable
    private SurfaceCrawl.Step wrapStep;
    /** 回り込み始めてからの tick。 */
    private int wrapTicks;
    /** 張り付いたまま掘っている面のブロック(箱・小屋の壁。{@link #digThroughFace}、2026-10-02)。null なら掘っていない。 */
    @Nullable
    private BlockPos clingDigPos;
    /** {@link #clingDigPos} を掘り始めてからの tick。 */
    private int clingDigTicks;
    /**
     * 壁の上端・天井の縁を探した答えの控え({@link #surfaceScan})。張り付いている間は毎 tick 探すので、同じマス・同じ面のあいだは
     * 前の答えを使う(探すのは上へ最大 24 マス・四方へ最大 8 マスの当たり判定。1 マス這うのに数 tick かかる)。
     */
    @Nullable
    private BlockPos scanFrom;
    @Nullable
    private Direction scanAnchor;
    private boolean scanCeiling;
    @Nullable
    private BlockPos scanResult;

    protected AbstractOvergrownInvaderEntity(EntityType<? extends AbstractOvergrownInvaderEntity> type, Level level) {
        super(type, level);
        // 葉は見た目こそ茂みでも当たり判定は 1 ブロックまるごと。malus 0(= 通れる扱い)にすると
        // 葉の中を突っ切る経路が引かれ、実際には入れずに詰まる。バニラ既定どおり通らせない。
        this.setPathfindingMalus(PathType.LEAVES, -1.0F);
        this.setPathfindingMalus(PathType.WATER, -1.0F);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION_STATE, STATE_IDLE);
        builder.define(DATA_CLIMBING, false);
        builder.define(DATA_CLIMB_FACING, (byte) Direction.NORTH.get3DDataValue());
        builder.define(DATA_ON_CEILING, false);
    }

    public int getActionState() {
        return this.entityData.get(DATA_ACTION_STATE);
    }

    public void setActionState(int state) {
        this.entityData.set(DATA_ACTION_STATE, state);
    }

    /**
     * Goal の優先度の並び(群れ v2、2026-09-25。swarm-api の「Priority ladder」)。数字が小さいほど先で、
     * 実行中の Goal を奪えるのは<b>数字が厳密に小さい</b> Goal だけ。
     * <ol start="0">
     *     <li>水に浮く(FloatGoal)・水から上がる({@link EscapeWaterGoal}、差し込み。群れが泳いで渡る間は止まる)</li>
     *     <li>詰まりの立て直し({@link InvaderUnstickGoal}。先導役と個体だけ)・坑道(アルボラ)・忌避灯から出る(差し込み)</li>
     *     <li>種族の Goal(飛びつき・川の跳び越え・ビーム)</li>
     *     <li>群れの囲む({@link SwarmEngageGoal})・汚染を広げる({@link SwarmSpreadGoal}) /
     *     個体のうずくまり・誘導の狩り・小規模レイドの狩り(一員では止まる)</li>
     *     <li>群れの先導({@link SwarmLeadGoal})・後続({@link SwarmFollowGoal}) / 個体の近接(一員では止まる)</li>
     * </ol>
     * 7 が個体の散歩(一員では止まる)、8 以降が見回し。
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 詰まったら何よりも先に立て直す(追跡も前進も止めて回り道する)。群れの後続では動かない。
        this.goalSelector.addGoal(1, new InvaderUnstickGoal(this));
        registerSpeciesGoals();
        // 群れの一員: 渡された標的を囲む / 群れが止まって汚染を広げる(どちらも一員でなければ動かない)。
        this.goalSelector.addGoal(3, new SwarmEngageGoal(this));
        this.goalSelector.addGoal(3, new SwarmSpreadGoal(this, this::createSpreadAction));
        // うずくまりは追跡より先(優先度3)。ただし標的を持っている間と群れの一員の間は canUse() が false を返す。
        if (useSporeCrouchGoal()) {
            this.goalSelector.addGoal(3, new InvaderSporeCrouchGoal(this));
        }
        // 群れの先導役・後続。同じ優先度なので、役が替わったらそれぞれ自分で止まる(SwarmLeadGoal の javadoc)。
        this.goalSelector.addGoal(4, new SwarmLeadGoal(this));
        this.goalSelector.addGoal(4, new SwarmFollowGoal(this));
        if (useMeleeAttackGoal()) {
            // 群れの一員は近接を SwarmEngageGoal で行う(ここでは経路を引かせない)。
            this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.0D, true) {
                @Override
                public boolean canUse() {
                    // 群れを先に見る(親の canUse は経路探索をする)。
                    return !SwarmMember.isInSwarm(AbstractOvergrownInvaderEntity.this) && super.canUse();
                }

                @Override
                public boolean canContinueToUse() {
                    // 途中で群れに入ったら止める(同じ優先度の先導・後続の Goal は割り込めない)。
                    return !SwarmMember.isInSwarm(AbstractOvergrownInvaderEntity.this) && super.canContinueToUse();
                }
            });
        }
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.9D) {
            @Override
            public boolean canUse() {
                // 群れの後続はうろつかない(ユーザー指定 2026-09-25)。先導役も群れの行き先へだけ歩く。
                return !SwarmMember.isInSwarm(AbstractOvergrownInvaderEntity.this) && super.canUse();
            }
        });
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        if (useRandomLookAroundGoal()) {
            this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        }

        if (useTargetGoals()) {
            this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
            // プレイヤー + グループ1〜10 に属していない生き物すべて。水中の相手は狙わない。
            this.targetSelector.addGoal(2, new OvergrownLandTargetGoal(this, Predation::isInvaderTarget));
            // グループ1〜3 を獲物として狩る(2026-09-15)。プレイヤーや部外者より優先度は下。
            // 狩って強くなるのはアルボラとスカトゥリウムだけだが、狙うのはグループ4 全員
            // —— 弱らせて追い込むところまでが「補助」の仕事。
            this.targetSelector.addGoal(3, new OvergrownLandTargetGoal(this, Predation::isInvaderPrey));
        }
    }

    /** 種族固有のゴール(優先度 2 目安。0 は水泳、1 は詰まりの立て直しが使っている)。 */
    protected void registerSpeciesGoals() {
    }

    /**
     * 群れが止まって全員で汚すとき({@link SwarmSpreadGoal})のこの種の動作。既定はうずくまって汚す
     * ({@link InvaderSporeCrouchGoal} と同じ中身。アルボラ)。null を返す種は自分の場所に立っているだけ(スポロフィル)。
     * 個体のうずくまり({@link #useSporeCrouchGoal})を使わない種でも、群れの中ではこちらで汚す
     * (ユーザー指定 2026-09-20 / 2026-09-24「アルボラは 1 体ずつではなく群れで止まって汚染」)。
     */
    @Nullable
    protected SwarmSpreadGoal.Action createSpreadAction() {
        return SporeSpreadSession.crouchAction(this);
    }

    /**
     * 「その場にうずくまって汚染を広げる」({@link InvaderSporeCrouchGoal})を使うか。
     * フィロセンスのように<b>別の形で同じ役割を果たす</b>種は false を返して、自前のゴールを
     * {@link #registerSpeciesGoals()} で入れる。
     */
    protected boolean useSporeCrouchGoal() {
        return true;
    }

    /**
     * 近接攻撃({@link MeleeAttackGoal})を使うか。
     * 遠距離で戦う種(フィロセンスのビーム)は false を返す — 追跡して殴りに行くと、
     * 撃ち合う間合いを保つという行動そのものが成立しない。
     */
    protected boolean useMeleeAttackGoal() {
        return true;
    }

    /**
     * 標的を掴む({@link OvergrownHurtByTargetGoal} / {@link OvergrownLandTargetGoal})を使うか。
     *
     * <p><b>攻撃手段をまったく持たない種は false を返すこと</b>(スポロフィル)。
     * 掴んだところで何もできないうえ、相手を抱えたまま動けなくなる(旧い群れの Goal は「標的がいる間は動かない」作りで、
     * ランダム徘徊だけが残って群れから離れていった)。群れの中では司令塔が支援役に標的を渡さない(ユーザー指定)。
     */
    public boolean useTargetGoals() {
        return true;
    }

    /**
     * あたりをきょろきょろ見回す({@link net.minecraft.world.entity.ai.goal.RandomLookAroundGoal})を使うか。
     *
     * <p>このゴールは 1〜2 秒おきに<b>まったくの別方向</b>を選ぶので、体がそれに付いていく
     * (バニラの体幹制御は 10 tick で体を頭へ揃える)。<b>背の高い大きなモデルでは、立ったまま
     * ぐるぐる回っているようにしか見えない</b>ため、そういう種は false を返す
     * (ユーザー指摘 2026-09-14、フィロセンス)。
     */
    protected boolean useRandomLookAroundGoal() {
        return true;
    }

    /**
     * その場に留まって汚染を広げるときの水平半径(ブロック)。
     * 既定は {@code invaderCorruptionRadius}。フィロセンスのように広く汚す種は上書きする。
     */
    public int sporeCorruptionRadius() {
        return OvergrownVegetationConfig.COMMON.invaderCorruptionRadius.get();
    }

    // --- 攻撃モーション ---

    /**
     * 攻撃アニメーションを何 tick 見せるか。{@code arbora.animation.json} の {@code attack} と合わせる。
     * GeckoLib は補間({@code transitionLength})が済んでからアニメを 0 秒目から流すので、
     * 「補間の tick + アニメの秒 × 20」にする({@link ArboraEntity#TRANSITION_TICKS})。
     */
    protected int attackAnimationTicks() {
        return 12;
    }

    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && !this.level().isClientSide()) {
            playAttackAnimation();
        }
        return hit;
    }

    /**
     * 攻撃アニメを 1 回見せる({@link #attackAnimationTicks} のあいだ {@link #STATE_ATTACK})。待機中のときだけ状態を変える
     * (飛びつき・掘り・うずくまりの最中に書き換えると、その Goal の終わりの判定が狂う)。
     * 近接が当たったとき({@link #doHurtTarget})と、攻城で塊・壁を叩いたとき({@link SwarmEngageGoal} → TD)に呼ぶ。
     * 外から {@code setActionState(STATE_ATTACK)} を直に呼ばないこと(残り tick が数えられず戻らない)。
     */
    @Override
    public void playAttackAnimation() {
        if (this.level().isClientSide()) {
            return;
        }
        this.attackAnimTicks = attackAnimationTicks();
        if (getActionState() == STATE_IDLE) {
            setActionState(STATE_ATTACK);
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide()) {
            return;
        }
        // 張り付きの駆動は travel() のあと(= この tick の移動が済んだあと)に速度を置く。
        if (isClinging()) {
            tickSurfaceCrawl();
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (this.attackAnimTicks > 0) {
            this.attackAnimTicks--;
            if (this.attackAnimTicks == 0 && getActionState() == STATE_ATTACK) {
                setActionState(STATE_IDLE);
            }
        }
        tickStuckDetection();
        // 群れの一員の自己修復(所属があるのに名簿に居なければ入り直す。中で 20 tick に 1 回へ間引いている)。
        // 群れに居ない個体では所属を 1 回引くだけ。
        SwarmMember.tick(this);
    }

    // --- 詰まり検知と立て直し ---

    /**
     * 経路探索を直しても({@link OvergrownNodeEvaluator})、体の幅を枡目に丸めて扱う
     * バニラの都合上「経路の上では通れるのに体がつかえる」場所は残る。ここでは
     * <ul>
     *     <li>歩こうとしているのに {@value #PROGRESS_CHECK_TICKS} tick 単位で進めていない、または</li>
     *     <li>頭上が塞がっていてジャンプが無駄になった({@link #jumpFromGround})</li>
     * </ul>
     * を見て「詰まった」の印を立てる。実際に回り道するのは {@link InvaderUnstickGoal}。
     *
     * <p>同じ相手を追って {@value #STUCKS_BEFORE_GIVE_UP} 回詰まったら、その相手は
     * (少なくとも今いる場所からは)辿り着けないと判断して {@value #GIVE_UP_TICKS} tick のあいだ狙わない。
     * これをしないと、追跡 → 壁 → 立て直し → また同じ壁、を延々と繰り返す。
     *
     * <p><b>群れ(v2、2026-09-25)</b>: 後続は数えない(経路を持たない。進めないときは後続の Goal が立て直す)。
     * 群れに動かされている先導役({@link SwarmMember#isSwarmSteered}: 交戦中・標的を渡されている・攻城)は
     * 「経路の無い標的へ体を押し当てる」枝を通らない。攻城中の先導役は詰まりの印も立てない。
     * 群れの一員は自分で標的を諦めない(諦めるのは群れの側)。
     * 押し当てる枝の代わり —— 壁に当たったら登る・続けば先導役は詰まった印(坑道・回り道)—— は、群れの囲む Goal が
     * {@link #noteSwarmApproachBlocked} / {@link #noteSwarmApproachExhausted} で伝えてくる(レビュー指摘 2026-09-25)。
     */
    private void tickStuckDetection() {
        if (this.unreachableTarget != null) {
            if (this.tickCount >= this.giveUpUntilTick
                    || !this.unreachableTarget.isAlive()
                    // 向こうから降りてきた = 辿り着けない相手ではなくなった。
                    || distanceToSqr(this.unreachableTarget) < GIVE_UP_FORGET_DISTANCE * GIVE_UP_FORGET_DISTANCE) {
                this.unreachableTarget = null;
            } else if (getTarget() == this.unreachableTarget) {
                setTarget(null);
            }
        }
        if (this.recentStuckDecay > 0 && --this.recentStuckDecay == 0) {
            this.recentStucks = 0;
        }
        if (this.pathFailureDecay > 0 && --this.pathFailureDecay == 0) {
            this.pathFailures = 0;
        }
        if (SwarmMember.isFollower(this)) {
            // 群れの後続は詰まりを数えない(群れ v2、2026-09-25)。後続は経路を持たずに足跡をなぞり、進めなければ
            // 後続の Goal が足跡のカーソルへ立て直しの経路を 1 本引く。回り道・坑道の Goal も後続では動かないので、
            // ここで印を立てると誰も下ろさない(先導役に選ばれた瞬間に古い印で回り道を始めてしまう)。
            this.stuck = false;
            resetProgress();
            return;
        }
        if (this.stuck) {
            // 立て直し待ち。
            return;
        }
        LivingEntity target = getTarget();
        boolean idle = getActionState() == STATE_IDLE;
        if (!idle || (target != null && isWithinMeleeAttackRange(target))) {
            // 別の行動中/殴れる間合いにいるなら、動いていなくて当たり前。
            resetProgress();
            return;
        }
        if (isClinging()) {
            // 張り付き中は<b>面の制御が自前で諦める</b>({@link #keepClinging} の
            // CLING_STALL_TICKS / CLING_MAX_TICKS)ので、ここでは数えない。
            // 2026-09-20 より前はここでも詰まりを数えていたが、いまは二重になる ——
            // markStuck() されると回り道の Goal が経路を引こうとし、
            // 張り付き中は travel() が止めているので動けず、無駄に標的を諦めることになる。
            resetProgress();
            return;
        }
        if (target != null && getNavigation().isDone()) {
            if (SwarmMember.isSwarmSteered(this)) {
                // 群れに動かされている(囲む Goal が輪の上の位置へ直に動かしている・攻城の塊の輪・渡された標的がある)。
                // ここで体を標的へ押し当てると、囲む位置を毎 tick 上書きし、60 tick で詰まった印、2 回で標的を諦めて
                // 殴れなくなる(レビュー指摘。この処理は Goal より後に走る)。届かない相手を諦めるかは司令塔が決める。
                resetProgress();
                return;
            }
            // 相手はそこにいるのに経路が一本も引けない = 壁の向こう/上にいる。
            // <b>まっすぐ体を押し当てに行く</b>(2026-09-17)。壁にぶつかれば shouldStartClinging が
            // 成立して登り始めるので、これが「壁の上の相手へ辿り着く」唯一の手段になる。
            // クモ由来の無制限な直進({@link InvaderNavigation})を消した代わりだが、
            // こちらは下の NO_APPROACH_LIMIT で必ず打ち切られる。
            pushTowards(target);
            if (++this.noApproachTicks >= NO_APPROACH_LIMIT) {
                markStuck();
            }
            return;
        }
        this.noApproachTicks = 0;
        if (getNavigation().isDone()) {
            resetProgress();
            return;
        }
        if (--this.progressTimer <= 0) {
            this.progressTimer = PROGRESS_CHECK_TICKS;
            Vec3 now = position();
            if (this.progressAnchor != null
                    && Mth.square(now.x - this.progressAnchor.x) + Mth.square(now.z - this.progressAnchor.z)
                        < PROGRESS_MIN_DISTANCE * PROGRESS_MIN_DISTANCE) {
                this.noProgressChecks++;
            } else {
                this.noProgressChecks = 0;
                this.blockedJumps = 0;
            }
            this.progressAnchor = now;
        }
        if (this.noProgressChecks >= NO_PROGRESS_LIMIT || this.blockedJumps >= BLOCKED_JUMP_LIMIT) {
            markStuck();
        }
    }

    /**
     * 経路が引けない相手へ、まっすぐ体を向けて押し当てる。
     *
     * <p>{@code MoveControl} に行き先を渡すだけなので、途中に壁があればそこで止まる ——
     * <b>止まること自体が目的</b>で、{@code horizontalCollision} が立てば
     * {@link #shouldStartClinging} が壁登りを始める。張り付いている最中は自前で動いているので触らない。
     */
    private void pushTowards(LivingEntity target) {
        if (isOnCeiling() || isClimbing() || isInWater()) {
            return;
        }
        if (distanceToSqr(target) > DIRECT_APPROACH_RANGE * DIRECT_APPROACH_RANGE) {
            return;
        }
        if (waterAhead(target)) {
            // 相手が水の向こうにいる。踏み込むと「岸へ上がる → また押し込まれる」を繰り返す
            // (ユーザー報告 2026-09-20「水辺で陸地に上がった後すぐ振り返ってまた水に入る」)。
            // 渡るなら跳んで渡る({@link InvaderWaterLeapGoal})。押すのはここでやめる。
            return;
        }
        getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), 1.0D);
    }

    /**
     * 相手の方向、足元の {@value #WATER_PROBE_STEPS} マス先までに水があるか。
     *
     * <p>{@link #pushTowards} の足切り専用。1 直線しか読まないので安い。
     */
    private boolean waterAhead(LivingEntity target) {
        double dx = target.getX() - getX();
        double dz = target.getZ() - getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-3D) {
            return false;
        }
        dx /= length;
        dz /= length;
        BlockPos foot = blockPosition();
        for (int step = 1; step <= WATER_PROBE_STEPS; step++) {
            BlockPos ahead = foot.offset(Mth.floor(dx * step + 0.5D), 0, Mth.floor(dz * step + 0.5D));
            if (!level().getFluidState(ahead).isEmpty()
                    || !level().getFluidState(ahead.below()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 経路が一本も引けなかった({@link InvaderNavigation#judge})。
     *
     * <p>{@code invaderPathFailuresBeforeStuck} 回続いたら「詰まった」印を立てて、
     * {@link InvaderUnstickGoal} に回り道を探させる。これが無いと、標的を持たない個体
     * (群れの陣形移動・縄張りの外へ出る途中)は<b>経路が引けないまま延々と引き直すだけ</b>になる
     * —— 詰まり検知は「経路が無い = 歩いていない」とみなして素通りするため。
     */
    public void notePathFailure() {
        if (this.stuck) {
            // 立て直しの最中。ここで数えると回り道の経路探索が失敗するたびに数が増える。
            return;
        }
        if (isSiegeVanguard()) {
            // 攻城中の先導役は、塞がれたら壁を壊すかを先導の Goal が決める(td-design)。回り道へは回さない。
            return;
        }
        this.pathFailureDecay = PATH_FAILURE_MEMORY;
        int limit = OvergrownVegetationConfig.COMMON.invaderPathFailuresBeforeStuck.get();
        if (++this.pathFailures >= limit) {
            this.pathFailures = 0;
            markStuck();
        }
    }

    /** 目的地まで届く経路が引けた。失敗の数を捨てる。 */
    public void notePathSuccess() {
        this.pathFailures = 0;
        this.pathFailureDecay = 0;
    }

    private void markStuck() {
        if (isSiegeVanguard()) {
            // 攻城中の先導役は回り道しない(壁を壊すかは先導の Goal が決める。td-design)。
            resetProgress();
            return;
        }
        this.stuck = true;
        resetProgress();
        this.recentStucks++;
        this.recentStuckDecay = STUCK_MEMORY_TICKS;
        LivingEntity target = getTarget();
        // 群れの一員は自分で標的を諦めない(標的は司令塔が渡す。諦めて外しても次に考えるときにまた渡され、
        // 付けた・外したを繰り返すだけ。届かない相手を諦めるのは群れの側 = Swarm#giveUpTarget。呼ぶのは囲む Goal
        // (先導役がまっすぐ向かっても壁で近づけない回が続いた)・区間の報告(狙い以外の標的へ区間が続けて進まない)・
        // 坑道(掘れないものに当たった。giveUpOnTarget))。
        if (target != null && this.recentStucks >= STUCKS_BEFORE_GIVE_UP && !SwarmMember.isInSwarm(this)) {
            this.unreachableTarget = target;
            this.giveUpUntilTick = this.tickCount + GIVE_UP_TICKS;
            this.recentStucks = 0;
            setTarget(null);
        }
    }

    /** 攻城(TD の SIEGE / BREACH)中の群れの先導役か。詰まりの印・経路の失敗の数えを使わない。 */
    private boolean isSiegeVanguard() {
        Swarm swarm = SwarmManager.get(this);
        return swarm != null && swarm.isVanguard(this)
                && (swarm.mode() == Swarm.Mode.SIEGE || swarm.mode() == Swarm.Mode.BREACH);
    }

    private void resetProgress() {
        this.progressAnchor = null;
        this.progressTimer = PROGRESS_CHECK_TICKS;
        this.noProgressChecks = 0;
        this.blockedJumps = 0;
        this.noApproachTicks = 0;
    }

    /**
     * 殴られたなら相手はそこまで来られている。「辿り着けない相手」の判断は取り消す。
     * 群れの一員なら、実際に効いたときに司令塔へ伝える({@link SwarmManager#onMemberHurt}: 役割の距離にいればその一員だけ反撃、
     * 群れの標的を替えるかは優先順で。個体の反撃の Goal は一員の間は動かない)。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.unreachableTarget != null && source.getEntity() == this.unreachableTarget) {
            this.unreachableTarget = null;
        }
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide()) {
            SwarmManager.onMemberHurt(this, source);
        }
        return hurt;
    }

    /** {@link InvaderUnstickGoal} 用。 */
    public boolean isStuck() {
        return this.stuck;
    }

    /**
     * <b>標的はいるのに経路が一本も引けていない</b>状態が続いている tick 数
     * ({@link #tickStuckDetection} が数えている)。経路が引けた時点で 0 に戻る。
     *
     * <p>{@link InvaderWaterLeapGoal} が「陸路があるなら跳ばない」を判断するのに使う
     * (ユーザー要望 2026-09-19)。<b>経路探索をやり直さずに済む</b>のが肝 ——
     * 跳ぶかどうかのたびに {@code createPath} を呼ぶと、水際に立っている個体が
     * 毎回 A* を回すことになる。
     */
    public int noApproachTicks() {
        return this.noApproachTicks;
    }

    /**
     * 最近詰まった回数({@value #STUCK_MEMORY_TICKS} tick で忘れる)。
     * {@link ArboraTunnelGoal} が「登るのを一度試して駄目だったか」を見るのに使う。
     */
    public int recentStuckCount() {
        return this.recentStucks;
    }

    /**
     * 詰まった記録を捨てる。掘り始めるときに呼ぶ ——
     * 掘っている最中に {@value #STUCKS_BEFORE_GIVE_UP} 回目の詰まりが来て
     * <b>標的を捨てられては坑道が無駄になる</b>。
     */
    public void clearStuckMemory() {
        this.recentStucks = 0;
        this.recentStuckDecay = 0;
        this.unreachableTarget = null;
    }

    /**
     * その相手を {@code ticks} のあいだ狙わない(掘っても届かないと分かったとき)。
     *
     * <p>群れの一員(掘るのは先導役だけ)は、自分の「辿り着けない相手」にはせず<b>群れとして</b>諦める
     * ({@link Swarm#giveUpTarget}、期間は群れの {@code giveUpTicks})。一員の側で外すと、司令塔が 10 tick ごとに渡し直し、
     * 詰まり検知が毎 tick 外す、を繰り返すだけになる(群れ v2、2026-09-25)。
     */
    public void giveUpOnTarget(LivingEntity target, int ticks) {
        Swarm swarm = SwarmManager.get(this);
        if (swarm != null) {
            swarm.giveUpTarget(target);
            return;
        }
        this.unreachableTarget = target;
        this.giveUpUntilTick = this.tickCount + ticks;
        if (getTarget() == target) {
            setTarget(null);
        }
    }

    /**
     * 群れの囲む Goal から: 相手へまっすぐ向かっているのに壁に当たって近づけない({@link SwarmApproachHandler})。
     * 少しの間「回り道が無い」とみなし、{@link #shouldStartClinging} が同じ高さの相手でも壁を登り始める
     * (個体なら「経路の無い相手へ体を押し当てる」枝の {@code noApproachTicks} が担うところ。群れの一員はその枝を通らない。
     * 2026-09-20 の壁登りのユーザー要望を群れの前衛にも効かせる。レビュー指摘 2026-09-25)。
     */
    @Override
    public void noteSwarmApproachBlocked() {
        this.swarmBlockedUntil = this.tickCount + SWARM_BLOCKED_MEMORY_TICKS;
    }

    /**
     * 群れの囲む Goal から: 壁に当たったまままっすぐの回が続けて終わった。<b>先導役だけ</b>「詰まった」印を立てる
     * ({@link ArboraTunnelGoal} が坑道を掘る(2026-09-17 のユーザー要望)、掘れなければ {@link InvaderUnstickGoal} が回り道)。
     * 後続は掘らない・回り道しない(swarm-design)。攻城中の先導役は {@link #markStuck} が何もしない(壁は TD が壊す)。
     */
    @Override
    public void noteSwarmApproachExhausted() {
        if (!this.stuck && !isClinging() && SwarmMember.isVanguard(this)) {
            markStuck();
        }
    }

    /**
     * 詰まったとき、回り道({@link InvaderUnstickGoal})より先に試す手があるか。
     * アルボラだけが true を返しうる(坑道を掘る)。
     */
    public boolean prefersDiggingWhenStuck() {
        return false;
    }

    /** {@link InvaderUnstickGoal} 用。 */
    public void clearStuck() {
        this.stuck = false;
        resetProgress();
    }

    /**
     * 頭のすぐ上が塞がっているときは跳ばない。跳んでも天井に頭をぶつけるだけで、
     * 見た目にもひたすら段差へ挑み続けているように映る(ユーザー報告 2026-09-14)。
     * 跳ぶ代わりに「詰まり」を数えて {@link InvaderUnstickGoal} へ回す。
     */
    @Override
    public void jumpFromGround() {
        if (!this.level().isClientSide() && !this.isInWater() && !this.isInLava() && !hasHeadroomToJump()) {
            this.blockedJumps++;
            return;
        }
        this.blockedJumps = 0;
        super.jumpFromGround();
    }

    /** 頭上に、段差を乗り越えるのに要るぶんの空きがあるか。 */
    private boolean hasHeadroomToJump() {
        AABB box = getBoundingBox();
        double lift = Math.max(0.6D, this.maxUpStep());
        // 横は少し内側に取る(壁を擦っているだけで塞がっている判定にしない)。
        AABB probe = new AABB(box.minX + 0.05D, box.maxY + 1.0E-4D, box.minZ + 0.05D,
                box.maxX - 0.05D, box.maxY + lift, box.maxZ - 0.05D);
        return this.level().noCollision(this, probe);
    }

    /**
     * 経路探索。中身はすべて {@link InvaderNavigation} —— バニラに 4 つ手が入っている
     * (クモ由来の直進を止める / 探索射程を索敵距離から切り離す / 予算を増やす /
     * どこへも行かない部分経路を捨てる)。理由はあちらの javadoc に書いてある。
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return InvaderNavigation.create(this, level, canClimbWalls());
    }

    // --- 壁のぼり / 天井這い ---

    /**
     * この種が壁を登り、天井を這えるか。既定は {@code false}(普通に歩くだけ)。
     * {@link ArboraEntity} が {@code true} を返す。
     *
     * <p>{@link #createNavigation} から(= コンストラクタの途中で)呼ばれるので、
     * <b>フィールドを見ない定数</b>として実装すること。
     */
    protected boolean canClimbWalls() {
        return false;
    }

    /** 壁を登っているとき、どちら向きの壁に張り付いているか。 */
    public Direction getClimbFacing() {
        return Direction.from3DDataValue(this.entityData.get(DATA_CLIMB_FACING));
    }

    private void setClimbFacing(Direction facing) {
        this.entityData.set(DATA_CLIMB_FACING, (byte) facing.get3DDataValue());
    }

    public boolean isClimbing() {
        return this.entityData.get(DATA_CLIMBING);
    }

    public boolean isOnCeiling() {
        return this.entityData.get(DATA_ON_CEILING);
    }

    /** 描画用: 体の傾き(0 = 水平、1 = 壁に対して垂直)。 */
    public float getClimbAmount(float partialTick) {
        return Mth.lerp(partialTick, this.climbAmountO, this.climbAmount);
    }

    /** 描画用: 天井への逆さ度合い(0 = 通常、1 = 完全に逆さ)。 */
    public float getCeilingAmount(float partialTick) {
        return Mth.lerp(partialTick, this.ceilingAmountO, this.ceilingAmount);
    }

    @Override
    public boolean onClimbable() {
        return isClimbing() || isOnCeiling() || super.onClimbable();
    }

    /** 張り付いている間は他の Mob に押されて剥がれない。 */
    @Override
    public boolean isPushable() {
        return !isClimbing() && !isOnCeiling() && super.isPushable();
    }

    /** 壁・天井から降りたときに落下ダメージを食らわない(張り付いていたぶんは高さに数えない)。 */
    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        if (isClimbing() || isOnCeiling() || this.fallGraceTicks > 0) {
            return false;
        }
        return super.causeFallDamage(distance, multiplier, source);
    }

    /**
     * 張り付いている間は自力移動しない(速度は {@link #tickSurfaceCrawl} が決める)。
     *
     * <p>壁登りも対象にしたのは 2026-09-20。それまで壁は素の {@code travel} を通していたので、
     * <b>歩こうとする力と張り付きの力が毎 tick 喧嘩していた</b> ——
     * 面に沿って横へ動くには邪魔でしかない。
     */
    @Override
    public void travel(Vec3 travelVector) {
        if (isClinging()) {
            super.travel(Vec3.ZERO);
        } else {
            super.travel(travelVector);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (canClimbWalls() && !this.level().isClientSide()) {
            boolean wasClinging = isClinging();
            tickClingState();
            boolean ceiling = isOnCeiling();
            boolean climb = isClimbing();
            if (wasClinging && !ceiling && !climb) {
                // 壁・天井から離れた直後の落下は自分で降りたぶん。ダメージを受けない。
                this.fallGraceTicks = FALL_GRACE_TICKS;
            } else if (this.fallGraceTicks > 0) {
                this.fallGraceTicks--;
            }
            // 張り付いている間は重力を切って安定させる。
            boolean wantNoGravity = ceiling || climb;
            if (this.isNoGravity() != wantNoGravity) {
                this.setNoGravity(wantNoGravity);
            }
        }
        // 体勢の補間(サーバー・クライアント両方 — 同期フラグに追従する)。
        this.climbAmountO = this.climbAmount;
        this.climbAmount = Mth.approach(this.climbAmount, isClimbing() ? 1.0F : 0.0F, 0.2F);
        this.ceilingAmountO = this.ceilingAmount;
        this.ceilingAmount = Mth.approach(this.ceilingAmount, isOnCeiling() ? 1.0F : 0.0F, 0.2F);
    }

    /** 壁・天井のどちらかに張り付いているか。 */
    public boolean isClinging() {
        return isClimbing() || isOnCeiling();
    }

    /** いま張り付いている面の向き(自分のマスから見て、面がどちら側にあるか)。 */
    private Direction clingAnchor() {
        return isOnCeiling() ? Direction.UP : getClimbFacing();
    }

    /**
     * 張り付きの<b>開始・継続・終了</b>(2026-09-20 に作り直し。ユーザー報告
     * 「壁や天井を歩けるのをうまく使いこなせていない。一か所で突っかかる」)。
     *
     * <p>それまでは {@code horizontalCollision}(= 壁にぶつかった)だけで張り付き、
     * 離れる条件も「ぶつからなくなったら」しか無かった。そのため
     * <b>庇の下の隅に体を押し付けたまま、永久に上を目指す</b>個体が出ていた。
     * いまは<b>進める手が無くなったら離す</b>という終わり方がある。
     */
    private void tickClingState() {
        if (isClinging()) {
            Direction anchor = keepClinging();
            if (anchor == null) {
                releaseCling();
            } else if (anchor != clingAnchor()) {
                // 角を回って別の面へ移った。張り付き先を付け替える。
                setCling(anchor);
            }
            return;
        }
        if (!shouldStartClinging()) {
            return;
        }
        Direction anchor = SurfaceCrawl.findAnchor(this, blockPosition(),
                WallCrawl.collidingWallDirection(this));
        if (anchor == null || anchor == Direction.DOWN) {
            return;
        }
        setCling(anchor);
        this.clingTicks = 0;
        this.clingStallTicks = 0;
        this.clingTrail.clear();
        this.clingBestDistanceSq = Double.MAX_VALUE;
        this.clingGoalRef = null;
    }

    /**
     * 張り付き<b>始める</b>か。
     *
     * <ul>
     *     <li>標的を追っているときだけ(用も無くそのへんの壁を登らない)。</li>
     *     <li><b>壁にぶつかった</b> —— 従来どおり。歩いていて行き止まったら登る。</li>
     *     <li><b>相手が頭より上にいて、隣に張り付ける面がある</b> —— 2026-09-20 に追加。
     *     ぶつかるのを待たずに自分から登り始める。屋根の上・木の上の相手へ
     *     <b>壁に体当たりするまで気づかない</b>のが、使いこなせていない一因だった。</li>
     * </ul>
     */
    private boolean shouldStartClinging() {
        if (this.isInWater() || this.tickCount < this.clingRestUntil) {
            return false;
        }
        int state = getActionState();
        if (state != STATE_IDLE && state != STATE_ATTACK) {
            return false;
        }
        LivingEntity target = getTarget();
        if (target == null || !target.isAlive()) {
            // 群れ(v2、2026-09-25): 標的が無くても、先導役は区間の登る辺で、後続は足跡の CLIMB の点で登る。
            return shouldStartSwarmClimb();
        }
        // 壁にぶつかっただけでは登らない —— <b>歩いて行けるなら歩く</b>。
        // 経路が引けている(= 壁を回り込む道がある)うちに登り始めると、
        // 這う速さは歩きより遅いので、わざわざ遅い方を選ぶことになる。
        // 群れの前衛は経路を引かずに囲む Goal が直に動かすので、その Goal が「まっすぐ向かっても壁で近づけない」と
        // 伝えてきた間も回り道が無いとみなす(群れ v2、2026-09-25、レビュー指摘: 同じ高さの壁の向こうの相手へ登らなかった)。
        boolean noWayRound = this.noApproachTicks > 0 || this.tickCount < this.swarmBlockedUntil;
        if (this.horizontalCollision && (noWayRound || target.getY() > getY() + 1.0D)) {
            return true;
        }
        // 経路そのものが上を向いている = A* が「ここは登って越えろ」と言っている(2026-09-20)。
        Path path = getNavigation().getPath();
        if (path != null && !path.isDone()
                && path.getNextEntityPos(this).y > getY() + PATH_CLIMB_RISE
                && SurfaceCrawl.findAnchor(this, blockPosition(), null) != null) {
            return true;
        }
        return target.getY() > getY() + CLING_START_RISE
                && SurfaceCrawl.findAnchor(this, blockPosition(), null) != null;
    }

    /**
     * 標的を持たない群れの一員が登り始めるか(群れ v2、2026-09-25)。
     * <ul>
     *     <li>先導役 … 歩いている区間の次の点が {@value #PATH_CLIMB_RISE} より上(区間は、群れの全員が登れる狩りのときだけ
     *     登る辺を持つ。swarm-api の包絡)。</li>
     *     <li>後続 … 足跡の次の点が CLIMB(先導役が登った辺)で、{@value #PATH_CLIMB_RISE} より上にある。
     *     経路を持たないので、ぶつかった壁の向き({@link WallCrawl#collidingWallDirection})を張り付き先の候補にする。</li>
     * </ul>
     */
    private boolean shouldStartSwarmClimb() {
        Swarm swarm = SwarmManager.get(this);
        if (swarm == null || swarm.kind() != Swarm.Kind.GROUND) {
            return false;
        }
        if (swarm.isFollower(this)) {
            Path own = getNavigation().getPath();
            if (own != null && !own.isDone()) {
                // 立て直しの経路を歩いている(自分で引いた経路。登る群れなら這う辺もある): 先導役と同じ見方で登る。
                // 区間が登らない群れ(壁を壊して入る攻城の群れ・登れない体が混じる狩りの群れ)の後続は登らない(レビュー指摘 2026-09-25)。
                if (!swarm.legAllowsClimb()) {
                    return false;
                }
                Vec3 next = own.getNextEntityPos(this);
                return (next.y > getY() + PATH_CLIMB_RISE || climbsStraightUp(next) || descendsOffEdge(swarm, next))
                        && SurfaceCrawl.findAnchor(this, blockPosition(), null) != null;
            }
            Vec3 goal = clingGoal();
            if (goal == null) {
                return false;
            }
            // 足跡の次の点が CLIMB で上か下にある。登る群れでは、次の点がずっと上にあれば点の種類を問わない(壁の上の点へ向かう
            // 途中で剥がれて床へ落ちた後続は、次の点が壁の上の WALK のまま登り直せず、壁の前で跳び続けた。2026-09-25、GameTest
            // climberSwarmCrossesWallWithoutBreaking)。
            boolean climbPoint = swarm.followerNextKind(this) == Trail.Kind.CLIMB
                    && (goal.y > getY() + PATH_CLIMB_RISE || descendsOffEdge(swarm, goal));
            boolean highPoint = swarm.climber() && goal.y > getY() + PATH_CLIMB_RISE;
            Direction wall = this.horizontalCollision ? WallCrawl.collidingWallDirection(this) : null;
            // 足跡の点が、ぶつかっている壁・柵の真裏にある(越える点をカーソルが通り過ぎて、越えた先の低い点しか残っていない)。
            // 登ってよい区間なら張り付いて越える(2026-10-02。それまでは低い点なので張り付かず、塀の前で跳び続けた)。
            boolean behindWall = wall != null && swarm.legAllowsClimb() && isBehindFace(goal, wall);
            if (!climbPoint && !highPoint && !behindWall) {
                return false;
            }
            return SurfaceCrawl.findAnchor(this, blockPosition(), wall) != null;
        }
        if (swarm.isVanguard(this)) {
            Path path = getNavigation().getPath();
            if (path == null || path.isDone()) {
                return false;
            }
            Vec3 next = path.getNextEntityPos(this);
            return (next.y > getY() + PATH_CLIMB_RISE || climbsStraightUp(next) || descendsOffEdge(swarm, next))
                    && SurfaceCrawl.findAnchor(this, blockPosition(), null) != null;
        }
        return false;
    }

    /**
     * 経路の次の点が<b>同じ列の真上</b> = 区間の縦の辺(A* が「ここは壁を登れ」と言っている)。縦の辺の 1 段目は 1 ブロックしか
     * 上がらないので {@value #PATH_CLIMB_RISE} では拾えず、標的を持たない先導役(攻城の群れ)は壁の前で跳び続けていた
     * (2026-09-25、GameTest climberSwarmCrossesWallWithoutBreaking)。段を上る歩きの辺は隣の列へ進むので、ここには掛からない。
     */
    private boolean climbsStraightUp(Vec3 next) {
        return next.y > getY() + 0.5D && Mth.floor(next.x) == getBlockX() && Mth.floor(next.z) == getBlockZ();
    }

    /**
     * 足場の縁から降りて、次の点が下にある(宙にいる)か = 壁の上から向こう側の面へ這って降りるところ(登る群れの攻城の区間は
     * 壁の上の縁から向こうの面へ降りる辺を持つ。2026-09-25)。落ちる途中で面を掴む —— 掴まないと高い壁から落ちて傷つく。
     * 登る群れ({@link Swarm#climber})だけ(ほかの群れの段差の飛び降りは今までどおり落ちる)。
     */
    private boolean descendsOffEdge(Swarm swarm, Vec3 next) {
        if (!swarm.climber() || onGround() || !(next.y < getY() - DESCEND_GRAB_DROP)) {
            return false;
        }
        // 次の点に足場が無い(= 面の上の点)ときだけ。足場のある点(ふつうの 1 段下り)で掴むと、面の制御がすぐ「床へ」と返して離し、
        // 貼り直しの休み(CLING_REST_TICKS)で本当の登りが遅れた(レビュー指摘 2026-09-25)。
        return !level().getBlockState(BlockPos.containing(next.x, next.y - 1.0D, next.z)).blocksMotion();
    }

    /** 宙にいて次の点がこれより下なら、面を掴んで這い降りる(ブロック)。仮決め 2026-09-25。 */
    private static final double DESCEND_GRAB_DROP = 0.5D;

    /**
     * 張り付きを<b>続ける</b>か。続けるなら張り付く面の向き、やめるなら {@code null}。
     *
     * <p>やめる条件は 4 つ。<b>どれも「時間で諦める」形になっている</b>のが以前との違いで、
     * 永久に同じ隅で押し合うことが無くなる。
     * <ol>
     *     <li>標的がいない/水に入った/別の行動に入った</li>
     *     <li><b>{@value #CLING_MAX_TICKS} tick 張り付き続けた</b> —— 登りっぱなしの打ち切り</li>
     *     <li><b>{@value #CLING_STALL_TICKS} tick 相手に近づけていない</b> —— 行き止まり</li>
     *     <li>面から離れた、または<b>床に着いた</b>({@link Direction#DOWN})</li>
     * </ol>
     */
    @Nullable
    private Direction keepClinging() {
        if (this.isInWater()) {
            this.wrapStep = null;
            return null;
        }
        if (this.wrapStep != null) {
            return clingAnchor(); // 縁を回り込んでいる途中(宙のマスを通る)。着くまで元の面のまま
        }
        int state = getActionState();
        if (state != STATE_IDLE && state != STATE_ATTACK) {
            return null;
        }
        LivingEntity target = getTarget();
        if (target == null || !target.isAlive()) {
            // 群れの一員は標的が無くても、這う先(区間・足跡)があるあいだは続ける。群れに居ない個体は今までどおり離す。
            if (!SwarmMember.isInSwarm(this) || clingGoal() == null) {
                return null;
            }
        }
        if (++this.clingTicks > CLING_MAX_TICKS || this.clingStallTicks > CLING_STALL_TICKS) {
            return null;
        }
        Direction anchor = SurfaceCrawl.findAnchor(this, blockPosition(), clingAnchor());
        return anchor == Direction.DOWN ? null : anchor;
    }

    /** 張り付き先を設定する(天井か壁かはここで決まる)。 */
    private void setCling(Direction anchor) {
        boolean ceiling = anchor == Direction.UP;
        if (isOnCeiling() != ceiling) {
            this.entityData.set(DATA_ON_CEILING, ceiling);
        }
        if (isClimbing() == ceiling) {
            this.entityData.set(DATA_CLIMBING, !ceiling);
        }
        if (!ceiling) {
            setClimbFacing(anchor);
        }
    }

    /** 張り付きをやめる。すぐ貼り直さないよう少し間を置く。 */
    private void releaseCling() {
        if (isClimbing()) {
            this.entityData.set(DATA_CLIMBING, false);
        }
        if (isOnCeiling()) {
            this.entityData.set(DATA_ON_CEILING, false);
        }
        this.clingTrail.clear();
        this.wrapStep = null;
        this.scanFrom = null;
        this.clingDigPos = null;
        this.clingDigTicks = 0;
        this.clingRestUntil = this.tickCount + CLING_REST_TICKS;
    }

    /**
     * 張り付いたまま 1 歩進む。<b>行き先は {@link SurfaceCrawl} が地形を見て選ぶ</b> ——
     * 壁なら「上へ」、天井なら「標的の方角へ」と決め打ちしていた以前と違い、
     * 塞がっていれば横へ逸れ、角に当たれば面を乗り換える。
     */
    private void tickSurfaceCrawl() {
        this.fallDistance = 0.0F;
        advanceClingPath();
        if (this.wrapStep != null && tickWrap()) {
            return;
        }
        LivingEntity target = getTarget();
        Vec3 goal = clingGoal();
        if (goal == null) {
            return;
        }
        Direction anchor = clingAnchor();
        BlockPos cell = blockPosition();

        // 天井から相手のほぼ真上まで来たら、落ちて襲いかかる(従来どおり)。
        if (target != null && anchor == Direction.UP && target.getY() < getY()
                && horizontalDistanceSqrTo(target) < CEILING_DROP_RANGE_SQ) {
            dropOnTarget(target);
            return;
        }

        // 相手を直に追っている(経路を持たない)とき(2026-10-02、ユーザー報告「フェンスや塀を登って超えられない」
        // 「柱の上の小屋の中の Mob に集まって詰まる」)。経路・足跡を這うときの行き先はその点。
        Path path = getNavigation().getPath();
        boolean chasing = (path == null || path.isDone()) && target != null && target.isAlive();
        if (chasing && isWithinMeleeAttackRange(target) && getSensing().hasLineOfSight(target)) {
            // 手が届いている(壁を掘り抜いた先・塀の上端など)。剥がれずに掴まったまま殴らせる(殴るのは近接・群れの囲む Goal)。
            this.clingStallTicks = 0;
            holdOnTo(anchor);
            return;
        }

        Vec3 crawlGoal = goal;
        if (anchor == Direction.UP) {
            // 天井の上(天井のブロックの向こう)にいる相手へは、真下で粘っても届かない。天井の縁へ回って横の面へ移る
            // (縁では SurfaceCrawl の「外側の角を巻く」で横の面へ回り込む)。
            if (chasing && target.getY() >= cell.getY() + 1.0D) {
                BlockPos edge = surfaceScan(cell, anchor, true, target.position());
                if (edge != null) {
                    crawlGoal = Vec3.atBottomCenterOf(edge);
                }
            }
        } else {
            double dx = goal.x - getX();
            double dz = goal.z - getZ();
            double across = dx * anchor.getStepX() + dz * anchor.getStepZ();
            double lateral = Math.abs(dx * anchor.getStepZ() - dz * anchor.getStepX());
            // 行き先が張り付いている面の向こう(壁・柵の裏)にある。経路・足跡の点は真裏のときだけ(面に沿って角を回る点を拾わない)。
            boolean behind = across > BEHIND_ACROSS && (chasing || lateral < BEHIND_LATERAL);
            boolean alongFace = false;
            if (behind && chasing && enclosedRightBehind(target, across, lateral)) {
                // 相手が面のすぐ裏で屋根の下にいる(箱・小屋に閉じこもっている)。越えても届かないので、体の高さが合えば面を掘って入る。
                // 合わなければ、面に沿って相手の高さへ寄る(行き先は相手のまま)。
                if (overlapsBody(target)) {
                    if (digThroughFace(cell.relative(anchor), anchor)) {
                        return;
                    }
                    // 掘れない面(岩盤・持ち物・水に面したマス)なら、越えるほうへ。
                } else {
                    alongFace = true;
                }
            }
            if (behind && !alongFace) {
                // 越えるしかない。上端なら跳び上がり、まだなら上端まで登る —— 行き先が低くても(柵の向こうの地面にいる相手・
                // 塀の向こうへ降りる足跡の点)。それまでは相手に近づく 1 歩だけを選んでいたので、柵の根元で横へ逸れて剥がれていた。
                if (SurfaceCrawl.mantleCell(this, cell, anchor) != null) {
                    mantleOnto(anchor);
                    return;
                }
                crawlGoal = Vec3.atBottomCenterOf(surfaceScan(cell, anchor, false, goal));
            } else if (!behind && goal.y > getY() - 0.5D && across > 0.0D
                    && SurfaceCrawl.mantleCell(this, cell, anchor) != null) {
                // 壁の上端まで登った。行き先が壁の上(上で、壁の向こう側)なら、壁の上へ跳び上がって張り付きをやめる(2026-09-25)。
                // 行き先が同じ高さでもよい(2026-10-02): フェンス・塀の上に立つ点は、バニラの評価器だと当たり判定が食い込んだマス
                // (いま張り付いているマスと同じ高さ)で表されるので、「上」だけを見ると塀の上端の横で行き詰まって剥がれていた。
                mantleOnto(anchor);
                return;
            }
        }

        SurfaceCrawl.Step step = SurfaceCrawl.next(this, cell, anchor, crawlGoal, this.clingTrail);
        if (step == null) {
            // 行き止まり。面に掴まったまま粘る(数えているので、続けば離す)。
            this.clingStallTicks++;
            holdOnTo(anchor);
            return;
        }
        if (step.anchor() == Direction.DOWN) {
            releaseCling();
            return;
        }

        // 近づけているか。近づけていない時間が続いたら張り付きをやめる。這う先が次の点へ移った(経路・足跡が 1 点進んだ)ら数え直す ——
        // 数え直さないと、次の点までの距離が 1 点目に詰めた距離より縮まることは無く、長い壁・天井の途中で 40 tick ごとに剥がれた
        // (レビュー指摘 2026-09-25。動く標的は 1 tick にこれほど跳ばないので、今までどおり標的までの距離で数える)。
        // 測るのは実際に向かっている点(壁の上端・天井の縁へ回っている間はそこ。2026-10-02)。
        double distanceSq = position().distanceToSqr(crawlGoal);
        if (this.clingGoalRef == null || crawlGoal.distanceToSqr(this.clingGoalRef) > CLING_GOAL_JUMP_SQ) {
            this.clingBestDistanceSq = Double.MAX_VALUE;
            this.clingStallTicks = 0;
        }
        this.clingGoalRef = crawlGoal;
        if (distanceSq < this.clingBestDistanceSq - CLING_PROGRESS) {
            this.clingBestDistanceSq = distanceSq;
            this.clingStallTicks = 0;
        } else {
            this.clingStallTicks++;
        }

        if (!step.cell().equals(cell)) {
            this.clingTrail.addLast(cell.immutable());
            while (this.clingTrail.size() > CLING_TRAIL) {
                this.clingTrail.removeFirst();
            }
        }
        if (isWrap(cell, step)) {
            // 縁を回り込む(行き先は 2 軸ずれたマス)。着くまで元の面のまま動かす(tickWrap)。
            this.wrapStep = step;
            this.wrapTicks = 0;
            tickWrap();
            return;
        }
        if (step.anchor() != anchor) {
            setCling(step.anchor());
        }
        moveOnSurface(step);
    }

    /**
     * 相手が張り付いている面の<b>すぐ裏で、屋根の下</b>にいるか = 箱・小屋に閉じこもっている(ユーザー報告 2026-10-02
     * 「3×3×3 の立方体を柱 1 本で支えた構造の中の Mob に集まり、ブロックを壊そうとしても壊せずに詰まる」)。
     * 屋根を見るのは、柵・塀のすぐ向こうの空の下にいる相手(基地の兵士など)へは越えて襲えばよい —— 柵に穴を開けて回らない —— から。
     */
    private boolean enclosedRightBehind(LivingEntity target, double across, double lateral) {
        if (across > ENCLOSED_DEPTH || lateral > ENCLOSED_LATERAL) {
            return false;
        }
        BlockPos head = BlockPos.containing(target.getX(), target.getBoundingBox().maxY, target.getZ());
        for (int up = 0; up <= ENCLOSED_ROOF_SCAN; up++) {
            if (this.level().getBlockState(head.above(up)).blocksMotion()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 面の上端({@link SurfaceCrawl#faceTop}、{@code ceiling} が false)か天井の縁({@link SurfaceCrawl#ceilingEdge})。
     * 同じマス・同じ面・同じ種類なら前の答えを返す(控えは張り付きをやめると捨てる)。天井の縁で {@code toward} は同点の決め手に
     * 使うだけなので、同じマスの間は前の答えのままでよい。
     */
    @Nullable
    private BlockPos surfaceScan(BlockPos cell, Direction anchor, boolean ceiling, Vec3 toward) {
        if (cell.equals(this.scanFrom) && anchor == this.scanAnchor && ceiling == this.scanCeiling) {
            return this.scanResult;
        }
        this.scanFrom = cell.immutable();
        this.scanAnchor = anchor;
        this.scanCeiling = ceiling;
        this.scanResult = ceiling ? SurfaceCrawl.ceilingEdge(this, cell, toward) : SurfaceCrawl.faceTop(this, cell, anchor);
        return this.scanResult;
    }

    /** {@code goal} が {@code face} の向きの面の真裏にあるか(奥行き {@value #BEHIND_ACROSS} より奥、横のずれ {@value #BEHIND_LATERAL} 未満)。 */
    private boolean isBehindFace(Vec3 goal, Direction face) {
        double dx = goal.x - getX();
        double dz = goal.z - getZ();
        return dx * face.getStepX() + dz * face.getStepZ() > BEHIND_ACROSS
                && Math.abs(dx * face.getStepZ() - dz * face.getStepX()) < BEHIND_LATERAL;
    }

    /** 相手と自分の体の高さが重なっているか。近接は横にしか届かない —— 重ならない高さで面を抜いても殴れない。 */
    private boolean overlapsBody(LivingEntity target) {
        AABB own = getBoundingBox();
        AABB other = target.getBoundingBox();
        return other.minY < own.maxY - BODY_OVERLAP_MARGIN && other.maxY > own.minY + BODY_OVERLAP_MARGIN;
    }

    /**
     * 張り付いたまま面のブロック({@code face})を掘る。坑道({@link ArboraTunnelGoal})と同じ速さ({@code arboraDigTicksPerBlock})・
     * 同じ「掘らないもの」(岩盤・バリア・持ち物の入ったブロック・水や溶岩に面したマス)・同じ汚染。坑道は張り付いている間は
     * 動かない(地面を掘り進む作り)ので、壁・柱を登った先の箱の壁はここで掘る(2026-10-02)。抜けたら、その穴へ這い込む。
     *
     * @return この tick の動きを決めた(掘っている・穴へ入り始めた)。掘れない面なら false
     */
    private boolean digThroughFace(BlockPos face, Direction anchor) {
        if (!(this.level() instanceof ServerLevel server) || !OvergrownVegetationConfig.COMMON.arboraDigEnabled.get()) {
            return false;
        }
        if (server.getBlockState(face).getCollisionShape(server, face).isEmpty()) {
            // もう開いている(仲間が掘った)。穴へ入る。
            return enterOpening(face, anchor);
        }
        if (!ArboraTunnelGoal.canDig(server, face)) {
            return false;
        }
        this.clingStallTicks = 0;
        holdOnTo(anchor);
        if (!face.equals(this.clingDigPos)) {
            this.clingDigPos = face.immutable();
            this.clingDigTicks = 0;
        }
        if (this.clingDigTicks % CLING_DIG_SWING_TICKS == 0) {
            playAttackAnimation();
        }
        if (++this.clingDigTicks < OvergrownVegetationConfig.COMMON.arboraDigTicksPerBlock.get()) {
            return true;
        }
        this.clingDigPos = null;
        this.clingDigTicks = 0;
        ArboraTunnelGoal.breakAndCorrupt(server, face);
        // 自分で壁を抜いた。届かなかった探索の控えは地形が同じ前提なので捨てる(坑道と同じ)。
        InvaderNavigation.forgetFailedSearch(this.navigation);
        enterOpening(face, anchor);
        return true;
    }

    /**
     * 掘り抜いた穴({@code opening})へ這い込む。穴の中で掴まる面を探し、縁を回り込むのと同じ動き({@link #tickWrap})で入る
     * (いまのマスの面は掘って無くなったので、ふつうの張り付きの判定では次の tick に剥がれてしまう)。
     */
    private boolean enterOpening(BlockPos opening, Direction anchor) {
        if (!SurfaceCrawl.canOccupy(this, opening)) {
            return false;
        }
        Direction next = SurfaceCrawl.findAnchor(this, opening, anchor);
        if (next == null) {
            return false;
        }
        this.wrapStep = new SurfaceCrawl.Step(opening.immutable(), next);
        this.wrapTicks = 0;
        tickWrap();
        return true;
    }

    /** 行き先が「面の向こう」とみなす奥行き(面に垂直な向きの差、ブロック)。体の中心から面までが 0.5 前後なので、それより奥。仮決め 2026-10-02。 */
    private static final double BEHIND_ACROSS = 0.5D;
    /** 経路・足跡の点を「面の真裏」とみなす横のずれ(ブロック)。仮決め 2026-10-02。 */
    private static final double BEHIND_LATERAL = 1.0D;
    /**
     * 相手が「面のすぐ裏」にいるとみなす奥行き(体の中心から。厚さ 1 の面の奥 2 マスまで = 中が 3 マス幅の部屋の真ん中まで)。
     * 仮決め 2026-10-02(はじめ 2.6 = 奥 1 マスまでにしていたが、ユーザーの「3×3×3」が中の広さなら真ん中の相手が外れ、屋根に乗って止まる)。
     */
    private static final double ENCLOSED_DEPTH = 3.6D;
    /** 同じく横のずれ(ブロック。3 マス幅の部屋の外壁のどこに張り付いていても)。仮決め 2026-10-02。 */
    private static final double ENCLOSED_LATERAL = 2.6D;
    /** 相手の頭の上に屋根を探す高さ(マス。中が 3 段の部屋で 1.95 の体なら、屋根は頭のマスの 2 つ上)。仮決め 2026-10-02。 */
    private static final int ENCLOSED_ROOF_SCAN = 4;
    /** 体の高さの重なりを数えるときの余白(ブロック)。かすっただけの重なりで掘り始めない。 */
    private static final double BODY_OVERLAP_MARGIN = 0.1D;
    /** 張り付いて掘っている間に攻撃アニメを出す間隔(tick)。 */
    private static final int CLING_DIG_SWING_TICKS = 10;

    /** 面の 1 歩が縁を回り込む手か(行き先のマスが今のマスから 2 軸ずれている)。 */
    private static boolean isWrap(BlockPos from, SurfaceCrawl.Step step) {
        BlockPos to = step.cell();
        int axes = (to.getX() != from.getX() ? 1 : 0) + (to.getY() != from.getY() ? 1 : 0) + (to.getZ() != from.getZ() ? 1 : 0);
        return axes >= 2;
    }

    /**
     * 縁を回り込む途中の 1 tick。行き先の張り付く点へまっすぐ向かう(縁の角に頭が当たる間は、当たり判定が横へだけ滑らせる)。
     * 着いたら({@value #WRAP_ARRIVE} 以内)行き先の面に張り替えて終わる。{@value #WRAP_MAX_TICKS} tick 着けなければやめる。
     *
     * @return まだ回り込んでいる(この tick の動きは決めた)
     */
    private boolean tickWrap() {
        SurfaceCrawl.Step step = this.wrapStep;
        if (step == null) {
            return false;
        }
        Vec3 want = SurfaceCrawl.clingPoint(this, step.cell(), step.anchor());
        Vec3 delta = want.subtract(position());
        // 着いた = 行き先のマスに入っていて、張り付く点のすぐそば(マスの外で張り替えると、そのマスには掴まる面が無く剥がれる)。
        boolean arrived = blockPosition().equals(step.cell()) && delta.length() <= WRAP_ARRIVE;
        if (arrived || ++this.wrapTicks > WRAP_MAX_TICKS) {
            this.wrapStep = null;
            if (arrived && step.anchor() != clingAnchor()) {
                setCling(step.anchor());
            }
            return false;
        }
        // 軸ごとに動かす(縁の角に頭が当たっている間、縦は当たり判定に止められ、横だけが進む)。残りわずかな軸は位置を合わせる ——
        // 斜めの速さをそのまま縮めると横の成分が 0.003 を切り、LivingEntity#aiStep が 0 に丸めて縁の下から抜け出せなかった
        // (GameTest climberSwarmRoundsOverhang、2026-09-25)。
        double x = snapAxis(delta.x);
        double y = snapAxis(delta.y);
        double z = snapAxis(delta.z);
        if (x == 0.0D || y == 0.0D || z == 0.0D) {
            setPos(x == 0.0D ? want.x : getX(), y == 0.0D ? want.y : getY(), z == 0.0D ? want.z : getZ());
        }
        setDeltaMovement(x, y, z);
        this.hasImpulse = true;
        return true;
    }

    /** 回り込みの 1 軸ぶんの速さ。残りが {@value #WRAP_SNAP} より短ければ 0(呼び出し側が位置を合わせる)。 */
    private static double snapAxis(double remaining) {
        return Math.abs(remaining) < WRAP_SNAP ? 0.0D : Mth.clamp(remaining, -CLING_SPEED, CLING_SPEED);
    }

    /** 縁を回り込むとき、軸ごとの残りがこれより短ければ位置を合わせる(ブロック)。 */
    private static final double WRAP_SNAP = 0.05D;

    /** 縁を回り込んだ先に「着いた」距離(ブロック)。仮決め 2026-09-25。 */
    private static final double WRAP_ARRIVE = 0.25D;
    /** 縁を回り込むのに使ってよい上限(tick)。1 マス半を 0.16 ブロック / tick で動く長さ + 縁に引っかかるぶん。仮決め 2026-09-25。 */
    private static final int WRAP_MAX_TICKS = 40;

    /**
     * 這っていく先。<b>経路があるならその次の点</b>、無ければ標的そのもの。群れの後続(経路を持たない)で標的も無ければ
     * 足跡の次の点(無ければ陣形の位置)。どれも無ければ null(張り付きをやめる)。
     *
     * <p>経路を見るのが肝(2026-09-20)。{@link ClimbNodeEvaluator} が壁を登る辺を持つようになり、
     * A* は「この壁を登って、上を歩いて回り込む」という<b>登り込みの経路</b>を作る。
     * 標的だけを見て這うと、せっかく引いた経路を無視して<b>標的の方角の壁をひたすら登る</b>ことになり、
     * 登りきった先が行き止まりでも気づけない。群れの先導役は区間(経路)を、後続は足跡を這う(群れ v2、2026-09-25)。
     *
     * <p>張り付きを始める・続ける・這うの 3 か所がこれを見る。種で這う先を変えたいときは上書きしてよい。
     */
    @Nullable
    protected Vec3 clingGoal() {
        Path path = getNavigation().getPath();
        if (path != null && !path.isDone()) {
            return path.getNextEntityPos(this);
        }
        LivingEntity target = getTarget();
        if (target != null && target.isAlive()) {
            return target.position();
        }
        Swarm swarm = SwarmManager.get(this);
        if (swarm != null && swarm.isFollower(this)) {
            long cursor = swarm.followerCursor(this);
            if (swarm.trail().has(cursor)) {
                return swarm.trail().point(cursor);
            }
            return swarm.formationPoint(this);
        }
        return null;
    }

    /**
     * 張り付いている間、経路の次の点に着いたら経路を 1 点進める。バニラのナビ({@code GroundPathNavigation})は足が地面に
     * 着いていないと経路を進めない({@code canUpdatePath} が false。進むのは「真上から落ちてきた」ときだけ)ので、縦の辺・這う辺の
     * 点へ着くと次の点が今いる点のまま動かず、面の上で止まって張り付きの行き詰まりで剥がれていた(2026-09-25、GameTest
     * climberSwarmCrossesWallWithoutBreaking)。
     */
    private void advanceClingPath() {
        Path path = getNavigation().getPath();
        if (path != null && !path.isDone()
                && position().distanceToSqr(path.getNextEntityPos(this)) < CLING_NODE_REACH * CLING_NODE_REACH) {
            path.advance();
        }
    }

    /** 張り付いている間に経路の点へ「着いた」とみなす距離(ブロック)。仮決め 2026-09-25。 */
    private static final double CLING_NODE_REACH = 0.5D;

    /** テスト・調べもの用: 張り付きの内部の状態(行動・張り付いた tick・進めなかった tick・休みの残り)。 */
    public String debugClimbState() {
        return "act=" + getActionState() + " clingT=" + this.clingTicks + " stall=" + this.clingStallTicks
                + " rest=" + Math.max(0, this.clingRestUntil - this.tickCount) + " noApproach=" + this.noApproachTicks
                + " stuck=" + this.stuck;
    }

    /**
     * 壁の上端から壁の上へ跳び上がる(張り付きをやめ、壁の側へ少し押しながら跳ぶ。{@link SurfaceCrawl#mantleCell})。
     * 跳ぶ高さはふつうの跳び(1.25 ブロック)で、壁の上端の 1 段上に届く。落ちても痛くない({@code fallGraceTicks})。
     */
    private void mantleOnto(Direction anchor) {
        releaseCling();
        this.fallGraceTicks = FALL_GRACE_TICKS;
        setDeltaMovement(anchor.getStepX() * MANTLE_PUSH, MANTLE_JUMP, anchor.getStepZ() * MANTLE_PUSH);
        this.hasImpulse = true;
    }

    /** 壁の上へ跳び上がる上向きの速さ(バニラの跳びの 0.42 より少し強く、壁の上端の 1 段上に確実に届く)。仮決め 2026-09-25。 */
    private static final double MANTLE_JUMP = 0.5D;
    /** そのとき壁の側へ押す速さ(ブロック / tick)。仮決め 2026-09-25。 */
    private static final double MANTLE_PUSH = 0.2D;

    /** 面へ押し付けるだけ(進まない)。張り付きが切れて落ちないように。 */
    private void holdOnTo(Direction anchor) {
        setDeltaMovement(anchor.getStepX() * CLIMB_PUSH,
                anchor.getStepY() * CLIMB_PUSH,
                anchor.getStepZ() * CLIMB_PUSH);
        this.hasImpulse = true;
    }

    /**
     * 選んだマスへ、面に押し付けながら寄る。
     *
     * <p>面に沿った軸(張り付く向き以外)で残りが {@value #WRAP_SNAP} より短ければ、その軸は位置を合わせる(2026-10-02、縁を回り込む
     * {@link #tickWrap} と同じ扱い)。速さで寄せ続けると最後のわずかな残りが 0.003 を切り、{@code LivingEntity#aiStep} が 0 に丸めて
     * マスの境目の手前(y = 2.999 など)で止まる —— 足のマスが変わらないので同じ 1 歩を選び続け、行き詰まって剥がれていた。
     * 壁なら面にぶつかってバニラの登り(上へ 0.2)が押し上げるので隠れていたが、金網(鉄格子)は格子がマスの真ん中にあって
     * 体がぶつからない(ユーザー報告「金網を登って超えられない」)。
     */
    private void moveOnSurface(SurfaceCrawl.Step step) {
        Direction anchor = step.anchor();
        Vec3 want = SurfaceCrawl.clingPoint(this, step.cell(), anchor);
        Vec3 delta = want.subtract(position());
        Direction.Axis pressed = anchor.getAxis();
        boolean snapX = pressed != Direction.Axis.X && delta.x != 0.0D && Math.abs(delta.x) < WRAP_SNAP;
        boolean snapY = pressed != Direction.Axis.Y && delta.y != 0.0D && Math.abs(delta.y) < WRAP_SNAP;
        boolean snapZ = pressed != Direction.Axis.Z && delta.z != 0.0D && Math.abs(delta.z) < WRAP_SNAP;
        if (snapX || snapY || snapZ) {
            setPos(snapX ? want.x : getX(), snapY ? want.y : getY(), snapZ ? want.z : getZ());
            delta = new Vec3(snapX ? 0.0D : delta.x, snapY ? 0.0D : delta.y, snapZ ? 0.0D : delta.z);
        }
        double length = delta.length();
        Vec3 move = length < 1.0E-3D
                ? Vec3.ZERO
                : delta.scale(Math.min(CLING_SPEED, length) / length);
        setDeltaMovement(move.add(anchor.getStepX() * CLIMB_PUSH,
                anchor.getStepY() * CLIMB_PUSH,
                anchor.getStepZ() * CLIMB_PUSH));
        this.hasImpulse = true;
        faceSurface(step);
    }

    /**
     * 体の向き。<b>壁では面を向く</b>(レンダラーがそれ前提で体を立てる)、
     * <b>天井では進行方向を向く</b>。
     */
    private void faceSurface(SurfaceCrawl.Step step) {
        float yaw;
        if (step.anchor() == Direction.UP) {
            Vec3 forward = Vec3.atCenterOf(step.cell()).subtract(position());
            if (forward.x * forward.x + forward.z * forward.z < 1.0E-4D) {
                return;
            }
            yaw = (float) (Mth.atan2(forward.z, forward.x) * (180.0D / Math.PI)) - 90.0F;
        } else {
            yaw = step.anchor().toYRot();
        }
        setYRot(yaw);
        this.yRotO = yaw;
        this.yBodyRot = yaw;
        this.yBodyRotO = yaw;
    }

    private double horizontalDistanceSqrTo(LivingEntity target) {
        double dx = target.getX() - getX();
        double dz = target.getZ() - getZ();
        return dx * dx + dz * dz;
    }

    /** 天井から獲物へ落ちかかる。 */
    private void dropOnTarget(LivingEntity target) {
        releaseCling();
        setNoGravity(false);
        double dx = target.getX() - getX();
        double dz = target.getZ() - getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length > 1.0E-3D) {
            dx /= length;
            dz /= length;
        } else {
            dx = 0.0D;
            dz = 0.0D;
        }
        setDeltaMovement(dx * 0.18D, -0.4D, dz * 0.18D);
        this.hasImpulse = true;
        playCeilingDropSound();
    }

    /** 天井から獲物へ落ちるときの音。種ごとに差し替えてよい。 */
    protected void playCeilingDropSound() {
        SoundEvent sound = getAmbientSound();
        if (sound != null) {
            this.playSound(sound, 0.7F, 1.4F);
        }
    }

    /** 辿り着けないと判断した相手は、しばらく狙い直さない。 */
    @Override
    public boolean canAttack(LivingEntity target) {
        if (target == this.unreachableTarget && this.tickCount < this.giveUpUntilTick) {
            return false;
        }
        return super.canAttack(target);
    }

    // --- 群れ(v2、2026-09-25) ---

    /**
     * <b>「暇そうだから消す」を止める</b>(ユーザー報告 2026-09-15「少し離れただけでデスポーンする」)。
     * 理由と仕組みは {@link InvaderDespawn} に。群れの一員はバニラには消させない(消えるときは群れごと。
     * ユーザー指定 2026-09-25。司令塔が全員の距離を見て消す)。群れに居ない個体は {@code invaderDespawnRange} の外へ
     * 出たぶんだけ今までどおり消えるので、数が増え続けることはない。
     */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        if (SwarmMember.hasSwarmAttachment(this)) {
            return false;
        }
        return InvaderDespawn.removeWhenFarAway(this, distanceToClosestPlayer);
    }

    /**
     * 群れの一員・イベントの個体はポータルを使わない(別のディメンションへ行くと群れの名簿からもイベントの数えからも外れ、
     * 戻れないまま永続で残る)。名簿で引けなくても所属を持っていれば止める(読み込み直後など)。
     */
    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        if (SwarmGoals.blocksPortal(this)) {
            return false;
        }
        return super.canUsePortal(allowPassengers);
    }

    /**
     * 同じ群れの一員どうしは押し合わない(群れ v2、2026-09-25)。押し合うと、縦に並んだ群れが体で陣形を崩し、
     * 足跡から押し出された後続が立て直しの経路探索を呼ぶ。ほかの群れ・群れに居ない Mob とは今までどおり押し合う。
     */
    @Override
    public void push(Entity entity) {
        if (SwarmGoals.sameSwarm(this, entity)) {
            return;
        }
        super.push(entity);
    }

    /** {@link #push} と同じ(こちらは自分から押しに行く側)。 */
    @Override
    protected void doPush(Entity entity) {
        if (SwarmGoals.sameSwarm(this, entity)) {
            return;
        }
        super.doPush(entity);
    }

    /**
     * 群れの後続は押し合いの走査を 2 tick に 1 回にする(ID で位相をずらす)。後続は先導役の足跡に沿って固まって歩くので、
     * 毎 tick の周囲の走査がそのまま頭数ぶん乗る。同じ群れどうしは押し合わないので、半分にしても見た目は変わらない。
     */
    @Override
    protected void pushEntities() {
        if (!this.level().isClientSide() && Math.floorMod(this.tickCount + this.getId(), 2) != 0
                && SwarmMember.isFollower(this)) {
            return;
        }
        super.pushEntities();
    }

    // --- 死亡時の汚染 ---

    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel serverLevel && !this.isRemoved()) {
            int radius = OvergrownVegetationConfig.COMMON.invaderDeathCorruptionRadius.get();
            double noise = OvergrownVegetationConfig.COMMON.invaderDeathCorruptionNoise.get();
            if (radius > 0
                    && !SporeDeviceData.inhibits(serverLevel, this.blockPosition(), SporeDeviceData.Inhibit.CORRUPTION)) {
                // 同じ tick にまとめて倒れたときは、決まった数を超えたぶんを次の tick 以降へ回す
                // (2026-09-24 軽量化、報告 235。ふだんの 1〜3 体は今までどおりその場で汚染する)。
                NoisyCorruptionBurst.deathSphere(serverLevel, this.position(), radius, noise);
            }
        }
        super.die(source);
    }

    // --- 位置の判定(ゴールから使う) ---

    /** いま過繁茂系バイオームの中にいるか。 */
    public boolean isInsideOvergrownBiome() {
        return ModBiomes.isOvergrownLike(this.level().getBiome(this.blockPosition()));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    /** {@link InvaderSporeCrouchGoal} から呼ぶ「胞子感染させる相手か」。 */
    public static boolean isSporeVictim(LivingEntity self, LivingEntity candidate) {
        return candidate != self
                && candidate.isAlive()
                && !(candidate instanceof Player)
                && MobGroup.of(candidate.getType()) == null
                && !(candidate instanceof ParasitizedMob);
    }
    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // 旧ワールドの後始末(2026-09-24)。アルボラの巣を消す前は、建設グループ(BUILDER)と
        // 巣の住人(RESIDENT)を永続にしていたので、そのままだと二度と消えない。永続を戻すだけで、
        // 古いキー(NestRole・NestHome・Builder*・LeavingNest・CarriedLoot)は次の保存で消える。
        String oldNestRole = tag.getString("NestRole");
        if ("BUILDER".equals(oldNestRole)) {
            com.yayuy.overgrownvegetation.raid.EventMobs.restorePersistence(this, tag.getBoolean("PersistentBeforeBuilder"));
        } else if ("RESIDENT".equals(oldNestRole)) {
            com.yayuy.overgrownvegetation.raid.EventMobs.restorePersistence(this, false);
        }
        // 旧い群れ(2026-09-25 まで)の NBT の PackId を群れ ID として引き継ぐ。attachment は Entity#load で
        // このメソッドより先に読まれているので、群れの所属をもう持っていれば何もしない(adoptLegacyPack が見る)。
        // PackId・PackComplete のキーは次の保存で消える(もう書かない)。
        if (tag.hasUUID("PackId")) {
            SwarmManager.adoptLegacyPack(this, tag.getUUID("PackId"));
        }
    }

}
