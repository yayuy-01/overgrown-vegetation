package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
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
 *     <li><b>外へ出る</b> — 過繁茂バイオームの中にいるときは外へ向かって歩く
 *     ({@link InvaderAdvanceGoal})。汚染地形の縁で止まらず、その先へ侵入していく。</li>
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
 * フィロセンスが獲物の居場所を配り({@link InvaderPreyIntel})、
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
 * <h2>群れ(2026-09-15)</h2>
 * 「グループ4 の mob 達は基本的にグループ4 の mob 同士で集団行動する」というユーザー要望の実装は
 * {@link InvaderPack} に集約してある。この土台が受け持つのは 4 つ —
 * 編成({@link InvaderPackComposition}: アルボラ 8 / スポロフィル 2 / フィロセンス 1 /
 * ヴェロフィル 3 / スカトゥリウム 2、いずれも<b>最低数</b>)に足りない種を迎えに行く、離れたら合流する
 * (どちらも {@link InvaderPackGoal})、外へ出るときは先に動いた仲間と<b>同じ方向</b>へ進む
 * ({@link InvaderAdvanceGoal})、掴んだ標的を手の空いている仲間へ回す
 * ({@link InvaderPack#shareTarget})。飛行のヴェロフィルも同じ群れに数えられる。
 *
 * <p>{@link ParasitizedMob} 実装なので「胞子感染」には一切かからず
 * ({@link com.yayuy.overgrownvegetation.effect.ParasitizedSporeImmunity})、自分がばらまいた胞子や
 * 自分が汚した地面で自滅しない。グループ4 は
 * {@link com.yayuy.overgrownvegetation.phase.MobGroupFriendlyFire} の同士討ち禁止にも入っている。
 */
public abstract class AbstractOvergrownInvaderEntity extends PathfinderMob
        implements GeoEntity, ParasitizedMob, InvaderPackMember {

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
     * 群れの Goal({@link #isPackBusy()})も壁登り({@code shouldStartClinging})も動かない ——
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
    /** 往復よけに憶えておくマスの数。 */
    private static final int CLING_TRAIL = 10;
    /** 張り付きをやめてから、貼り直せるようになるまで(tick)。 */
    private static final int CLING_REST_TICKS = 20;

    // --- 巣との関わり(2026-09-20) ---

    /** この個体が属する巣の入口。属していなければ null。 */
    @Nullable
    private BlockPos nestHome;
    /** 巣とのつきあい方({@link NestRole})。 */
    private NestRole nestRole = NestRole.NONE;
    /** 建設グループの ID(同じ ID の 15 体で 1 グループ)。 */
    @Nullable
    private java.util.UUID builderGroup;
    /** 建設グループが向かう先(巣を作る地表の座標)。 */
    @Nullable
    private BlockPos builderSite;
    /**
     * 建設グループを諦めるゲーム内時刻。崖や海に阻まれて着けないグループが
     * <b>永続個体のまま無害に溜まり続ける</b>のを防ぐための期限。
     */
    private long builderUntil;
    /** 巣から外へ出たがっているか(小規模レイドの増援に選ばれた)。 */
    private boolean leavingNest;
    /** 運んでいる戦利品。貯蔵胞へ入れるまで持ち歩く。 */
    private net.minecraft.world.item.ItemStack carriedLoot = net.minecraft.world.item.ItemStack.EMPTY;


    @Nullable
    public BlockPos nestHome() {
        return this.nestHome;
    }

    public void setNestHome(@Nullable BlockPos pos) {
        this.nestHome = pos == null ? null : pos.immutable();
    }

    public NestRole nestRole() {
        return this.nestRole;
    }

    public void setNestRole(NestRole role) {
        this.nestRole = role;
    }

    @Nullable
    public java.util.UUID builderGroup() {
        return this.builderGroup;
    }

    @Nullable
    public BlockPos builderSite() {
        return this.builderSite;
    }

    /** 建設グループに入れる。以後 {@link NestRole#BUILDER} として振る舞う。 */
    public void joinBuilderGroup(java.util.UUID group, BlockPos site, long until) {
        this.builderGroup = group;
        this.builderSite = site.immutable();
        this.builderUntil = until;
        this.nestRole = NestRole.BUILDER;
        this.packBond().setId(null);                  // 元の群れからは抜ける
        this.setTarget(null);
        this.setPersistenceRequired();
        setWaterCrossing(true);
    }

    /**
     * <b>水を渡ってよいか</b>(2026-09-20 のユーザー報告「川があっても飛び越えようとしない」)。
     *
     * <p>経路探索は水マスに<b>重み 8</b>を付けている(バニラの Monster の既定)。
     * 1 マスの水たまりなら回り込めるが、<b>川は端から端まで水</b>なので、重み 8 が横に何十マスも
     * 並んでいることになり、<b>渡るより遠回りするほうが安い</b>と判断されて延々と岸を歩く。
     * 経路そのものも予算(展開ノード数)を食い潰して出なくなる。
     *
     * <p>建設グループは数百マス先の建設地まで歩くので、川を渡れないと事実上どこにも行けない。
     * 行軍のあいだだけ重みを 0 にして、まっすぐ泳いで渡らせる。
     * {@code FloatGoal}(優先度 0)が水面で浮かせるので溺れない。
     */
    public void setWaterCrossing(boolean crossing) {
        float malus = crossing ? 0.0F : 8.0F;
        setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, malus);
        setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER_BORDER, malus);
    }

    /** 建設グループを解散する。巣ができたあと、ふつうの個体に戻る。 */
    /** 建設を諦める期限(ゲーム内時刻)。過ぎたら解散する。 */
    public long builderUntil() {
        return this.builderUntil;
    }

    public void leaveBuilderGroup(@Nullable BlockPos home) {
        this.builderGroup = null;
        this.builderSite = null;
        this.builderUntil = 0L;
        this.nestRole = NestRole.NONE;
        this.nestHome = home == null ? null : home.immutable();
        setWaterCrossing(false);                      // 行軍が終われば水を避ける側に戻す
    }

    public boolean isLeavingNest() {
        return this.leavingNest;
    }

    public void setLeavingNest(boolean leaving) {
        this.leavingNest = leaving;
    }

    /** 自分の巣の<b>中</b>に居るか({@link com.yayuy.overgrownvegetation.nest.NestLeash})。 */
    public boolean isInsideOwnNest() {
        return com.yayuy.overgrownvegetation.nest.NestLeash.isInside(this, this.nestHome);
    }

    public net.minecraft.world.item.ItemStack carriedLoot() {
        return this.carriedLoot;
    }

    public void setCarriedLoot(net.minecraft.world.item.ItemStack stack) {
        this.carriedLoot = stack == null ? net.minecraft.world.item.ItemStack.EMPTY : stack;
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
    /** 直前に通ったマス(往復よけ)。 */
    private final Deque<BlockPos> clingTrail = new ArrayDeque<>();
    /** 張り付きをやめたあと、貼り直してよくなる {@code tickCount}。 */
    private int clingRestUntil;

    /**
     * いま群れ全体で向かっている先({@link InvaderAdvanceGoal} が置き、止めるときに外す)。
     * 後から動き出した仲間がこれを写して同じ方向へ進む({@link InvaderPack#sharedDestination})。
     */
    @Nullable
    private Vec3 packDestination;

    /**
     * 群れの所属(2026-09-15)。同じ ID を持つ個体が 1 つの群れ。
     * 決め方は {@link InvaderPackBonds}、置き場所の考え方は {@link InvaderPackBond}。
     */
    private final InvaderPackBond packBond = new InvaderPackBond();

    @Override
    public InvaderPackBond packBond() {
        return this.packBond;
    }

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

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 詰まったら何よりも先に立て直す(追跡も前進も止めて回り道する)。
        this.goalSelector.addGoal(1, new InvaderUnstickGoal(this));
        // 巣の出入り(2026-09-20)。<b>狩りより先</b>に置くこと —— 巣の奥から地上の標的を
        // 直に指すと経路が出ず、壁を登って天井に張り付いたまま暴れる(ユーザー報告)。
        this.goalSelector.addGoal(2, new NestExitGoal(this));
        // 巣を掘る(切羽を割り当てられているあいだだけ)。行軍より先に置く ——
        // 建設地に着いてからは「歩く」より「掘る」が仕事になる。
        this.goalSelector.addGoal(2, new NestDigGoal(this));
        this.goalSelector.addGoal(2, new BuilderMarchGoal(this));
        // 住人がときどき狩りに出る(旗を立てるだけ。歩くのは NestExitGoal)
        // 狩りに出る合図と、外を歩き回るぶん。<b>近接攻撃(4)より後ろ</b>に置く ——
        // 獲物を見つけたら歩き回りより戦うのが先(2026-09-20)。
        this.goalSelector.addGoal(6, new NestSortieGoal(this));
        registerSpeciesGoals();
        // うずくまりは追跡より先(優先度3)。ただし標的を持っている間は canUse() が false を返す。
        if (useSporeCrouchGoal()) {
            this.goalSelector.addGoal(3, new InvaderSporeCrouchGoal(this));
        }
        if (useMeleeAttackGoal()) {
            this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.0D, true));
        }
        // 群れから離れていたら、外へ出る前にまず合流する(グループ4 同士の集団行動)。
        this.goalSelector.addGoal(5, new InvaderPackGoal(this));
        // 標的がいないときは過繁茂バイオームの外へ向かって歩き続ける。
        this.goalSelector.addGoal(6, new InvaderAdvanceGoal(this));
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.9D));
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
     * 掴んだところで何もできないうえ、{@link InvaderPackGoal} と {@link InvaderAdvanceGoal} は
     * どちらも「標的がいる間は動かない」ので、<b>相手を抱えたままランダム徘徊だけが残って
     * 群れから離れていく</b> —— 支援役にとっては行動不能と変わらない。
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
            this.attackAnimTicks = attackAnimationTicks();
            if (getActionState() == STATE_IDLE) {
                setActionState(STATE_ATTACK);
            }
        }
        return hit;
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
        // 掴んでいる相手を近くのグループ4 へ回して、群れで一緒に襲う(中で間引いている)。
        // 近くの仲間と群れ(所属)を作る・入る・合流する・抜ける。これも中で間引いている。
        InvaderPackBonds.tick(this);
        InvaderPack.shareTarget(this);
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
        this.stuck = true;
        resetProgress();
        this.recentStucks++;
        this.recentStuckDecay = STUCK_MEMORY_TICKS;
        LivingEntity target = getTarget();
        if (target != null && this.recentStucks >= STUCKS_BEFORE_GIVE_UP) {
            this.unreachableTarget = target;
            this.giveUpUntilTick = this.tickCount + GIVE_UP_TICKS;
            this.recentStucks = 0;
            setTarget(null);
        }
    }

    private void resetProgress() {
        this.progressAnchor = null;
        this.progressTimer = PROGRESS_CHECK_TICKS;
        this.noProgressChecks = 0;
        this.blockedJumps = 0;
        this.noApproachTicks = 0;
    }

    /** 殴られたなら相手はそこまで来られている。「辿り着けない相手」の判断は取り消す。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.unreachableTarget != null && source.getEntity() == this.unreachableTarget) {
            this.unreachableTarget = null;
        }
        return super.hurt(source, amount);
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

    /** その相手を {@code ticks} のあいだ狙わない(掘っても届かないと分かったとき)。 */
    public void giveUpOnTarget(LivingEntity target, int ticks) {
        this.unreachableTarget = target;
        this.giveUpUntilTick = this.tickCount + ticks;
        if (getTarget() == target) {
            setTarget(null);
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
            return false;
        }
        // 壁にぶつかっただけでは登らない —— <b>歩いて行けるなら歩く</b>。
        // 経路が引けている(= 壁を回り込む道がある)うちに登り始めると、
        // 這う速さは歩きより遅いので、わざわざ遅い方を選ぶことになる。
        boolean noWayRound = this.noApproachTicks > 0;
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
            return null;
        }
        int state = getActionState();
        if (state != STATE_IDLE && state != STATE_ATTACK) {
            return null;
        }
        LivingEntity target = getTarget();
        if (target == null || !target.isAlive()) {
            return null;
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
        this.clingRestUntil = this.tickCount + CLING_REST_TICKS;
    }

    /**
     * 張り付いたまま 1 歩進む。<b>行き先は {@link SurfaceCrawl} が地形を見て選ぶ</b> ——
     * 壁なら「上へ」、天井なら「標的の方角へ」と決め打ちしていた以前と違い、
     * 塞がっていれば横へ逸れ、角に当たれば面を乗り換える。
     */
    private void tickSurfaceCrawl() {
        this.fallDistance = 0.0F;
        LivingEntity target = getTarget();
        if (target == null) {
            return;
        }
        Direction anchor = clingAnchor();
        BlockPos cell = blockPosition();

        // 天井から相手のほぼ真上まで来たら、落ちて襲いかかる(従来どおり)。
        if (anchor == Direction.UP && target.getY() < getY()
                && horizontalDistanceSqrTo(target) < CEILING_DROP_RANGE_SQ) {
            dropOnTarget(target);
            return;
        }

        Vec3 goal = crawlGoal(target);
        SurfaceCrawl.Step step = SurfaceCrawl.next(this, cell, anchor, goal, this.clingTrail);
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

        // 近づけているか。近づけていない時間が続いたら張り付きをやめる。
        double distanceSq = position().distanceToSqr(goal);
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
        if (step.anchor() != anchor) {
            setCling(step.anchor());
        }
        moveOnSurface(step);
    }

    /**
     * 這っていく先。<b>経路があるならその次の点</b>、無ければ標的そのもの。
     *
     * <p>経路を見るのが肝(2026-09-20)。{@link ClimbNodeEvaluator} が壁を登る辺を持つようになり、
     * A* は「この壁を登って、上を歩いて回り込む」という<b>登り込みの経路</b>を作る。
     * 標的だけを見て這うと、せっかく引いた経路を無視して<b>標的の方角の壁をひたすら登る</b>ことになり、
     * 登りきった先が行き止まりでも気づけない。
     */
    private Vec3 crawlGoal(LivingEntity target) {
        Path path = getNavigation().getPath();
        if (path != null && !path.isDone()) {
            return path.getNextEntityPos(this);
        }
        return target.position();
    }

    /** 面へ押し付けるだけ(進まない)。張り付きが切れて落ちないように。 */
    private void holdOnTo(Direction anchor) {
        setDeltaMovement(anchor.getStepX() * CLIMB_PUSH,
                anchor.getStepY() * CLIMB_PUSH,
                anchor.getStepZ() * CLIMB_PUSH);
        this.hasImpulse = true;
    }

    /** 選んだマスへ、面に押し付けながら寄る。 */
    private void moveOnSurface(SurfaceCrawl.Step step) {
        Direction anchor = step.anchor();
        Vec3 want = SurfaceCrawl.clingPoint(this, step.cell(), anchor);
        Vec3 delta = want.subtract(position());
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

    /**
     * 辿り着けないと判断した相手は、しばらく狙い直さない。
     *
     * <p>さらに<b>巣との関わり</b>で 2 つ絞る(ユーザー指定 2026-09-20):
     * <ul>
     *     <li>巣の住人({@link NestRole#RESIDENT})は、巣から {@code guardLeashRadius} より外の相手を狙わない。
     *     外の敵に釣られて巣を空にしないため。</li>
     *     <li>建設グループ({@link NestRole#BUILDER})は、<b>自分を殴った相手以外</b>を狙わない。</li>
     * </ul>
     */
    /**
     * <b>巣の中に居るのに外の獲物を狙ったら、まず坑口まで出る</b>(2026-09-20 のユーザー報告
     * 「巣の出入り口を使おうとせず、直線的に地上を目指して天井に引っかかる」)。
     *
     * <p>原因はここが抜けていたこと。標的を掴むと近接の Goal が直に相手を指し、
     * 壁のぼりの経路探索は経路が無いと直進するので、坑道の天井へ張り付いて暴れていた
     * ([[wallclimber-nav-hidden-beeline]])。{@code leavingNest} を立てておけば
     * {@link NestExitGoal}(優先度 2)が道しるべを辿って先に外へ出してくれる。
     */
    @Override
    public void setTarget(@Nullable LivingEntity target) {
        super.setTarget(target);
        if (target != null && this.nestHome != null
                && com.yayuy.overgrownvegetation.nest.NestLeash.isInside(this, this.nestHome)
                && !com.yayuy.overgrownvegetation.nest.NestLeash.isInside(target, this.nestHome)) {
            setLeavingNest(true);
        }
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (target == this.unreachableTarget && this.tickCount < this.giveUpUntilTick) {
            return false;
        }
        if (this.nestRole == NestRole.BUILDER && target != getLastHurtByMob()) {
            return false;
        }
        // 荷物を抱えているあいだは狩らない(殴られた相手には反撃する)。
        // これが無いと、狩るたびに次の標的を掴んで運搬がいつまでも始まらない
        // ——「1 体狩ったら必ず運ぶ」というユーザー指定のための歯止め(2026-09-20)。
        if (!this.carriedLoot.isEmpty() && target != getLastHurtByMob()) {
            return false;
        }
        if (this.nestRole == NestRole.RESIDENT
                && com.yayuy.overgrownvegetation.nest.NestLeash.blocks(this, this.nestHome, this.leavingNest, target)) {
            return false;
        }
        return super.canAttack(target);
    }

    // --- 群れ(グループ4 同士の集団行動。2026-09-15) ---

    /**
     * 群れの重心からこれだけ離れたら戻る(ブロック)。既定は {@code invaderPackCohesionDistance}。
     * <b>役割で立ち位置が違う種は上書きする</b> —— スポロフィルは傘のオーラが仲間を覆えるよう
     * 中央寄り、フィロセンスは撃ち合う間合いを保てるよう後ろ寄り。
     */
    @Override
    public double packCohesionDistance() {
        return OvergrownVegetationConfig.COMMON.invaderPackCohesionDistance.get();
    }

    /**
     * 飛びつき・うずくまり・索敵・ビームの最中と、壁・天井に張り付いている間は群れの動きに移らない。
     * <b>建設グループと巣から出ようとしている間も</b>同じ扱いにして、群れ作りと「外へ進む」を止める
     * (2026-09-20) —— 建設グループはアルボラだけの特別な群れなので、ふつうの編成には加わらない。
     */
    @Override
    public boolean isPackBusy() {
        if (this.nestRole() == NestRole.BUILDER || this.isLeavingNest()) {
            return true;
        }
        return getActionState() != STATE_IDLE || isClimbing() || isOnCeiling();
    }

    @Override
    @Nullable
    public Vec3 getPackDestination() {
        return this.packDestination;
    }

    @Override
    public void setPackDestination(@Nullable Vec3 destination) {
        this.packDestination = destination;
    }

    /**
     * <b>「暇そうだから消す」を止める</b>(ユーザー報告 2026-09-15「少し離れただけでデスポーンする」)。
     * 理由と仕組みは {@link InvaderDespawn} に。{@code invaderDespawnRange} の外へ出たぶんは
     * 今までどおり消えるので、数が増え続けることはない。
     */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return InvaderDespawn.removeWhenFarAway(this, distanceToClosestPlayer);
    }

    // --- 死亡時の汚染 ---

    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel serverLevel && !this.isRemoved()) {
            int radius = OvergrownVegetationConfig.COMMON.invaderDeathCorruptionRadius.get();
            double noise = OvergrownVegetationConfig.COMMON.invaderDeathCorruptionNoise.get();
            if (radius > 0
                    && !SporeDeviceData.inhibits(serverLevel, this.blockPosition(), SporeDeviceData.Inhibit.CORRUPTION)) {
                NoisyCorruptionBurst.sphere(serverLevel, this.position(), radius, noise);
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
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("NestRole", this.nestRole.name());
        if (this.nestHome != null) {
            tag.putLong("NestHome", this.nestHome.asLong());
        }
        if (this.builderGroup != null) {
            tag.putUUID("BuilderGroup", this.builderGroup);
        }
        if (this.builderSite != null) {
            tag.putLong("BuilderSite", this.builderSite.asLong());
        }
        tag.putLong("BuilderUntil", this.builderUntil);
        tag.putBoolean("LeavingNest", this.leavingNest);
        if (!this.carriedLoot.isEmpty()) {
            tag.put("CarriedLoot", this.carriedLoot.save(this.registryAccess()));
        }
        this.packBond.save(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("NestRole")) {
            try {
                this.nestRole = NestRole.valueOf(tag.getString("NestRole"));
            } catch (IllegalArgumentException ignored) {
                this.nestRole = NestRole.NONE;
            }
        }
        this.nestHome = tag.contains("NestHome") ? BlockPos.of(tag.getLong("NestHome")) : null;
        this.builderGroup = tag.hasUUID("BuilderGroup") ? tag.getUUID("BuilderGroup") : null;
        this.builderSite = tag.contains("BuilderSite") ? BlockPos.of(tag.getLong("BuilderSite")) : null;
        this.builderUntil = tag.getLong("BuilderUntil");
        this.leavingNest = tag.getBoolean("LeavingNest");
        // 経路の重みは保存されないので、読み直したら付け直す ——
        // これが無いと、再ログイン後の建設グループだけ川を渡れなくなる(2026-09-20)。
        setWaterCrossing(this.nestRole == NestRole.BUILDER);
        this.carriedLoot = tag.contains("CarriedLoot")
                ? net.minecraft.world.item.ItemStack.parse(this.registryAccess(), tag.getCompound("CarriedLoot"))
                        .orElse(net.minecraft.world.item.ItemStack.EMPTY)
                : net.minecraft.world.item.ItemStack.EMPTY;
        this.packBond.load(tag,
                com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.invaderPackCompleteGraceTicks.get());
    }

}
