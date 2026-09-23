package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 「ヴェロフィル」({@code velophyll}) — グループ4(拡大種)の<b>爆撃役</b>。
 *
 * <p>膜翼を広げた滑空型の飛行 Mob。ふだんは {@code velophyllCruiseAltitude} の高さを飛び回り、
 * 標的を見つけると<b>急降下して真上 {@code velophyllDropAltitude} ブロックから胞子爆弾を
 * 1 発ずつ落とす</b>({@link VelophyllBombGoal} / {@link VelophyllBombEntity})。
 * 撃ち切ったあとは {@code velophyllBombCooldownTicks}(既定 20 秒)のあいだ爆弾を落とせないので、
 * その間は標的の上空を<b>旋回して待つ</b>。
 *
 * <p>{@code velophyllQuietTicks}(既定 3 分)プレイヤーを見つけられなかったときは地上へ降り、
 * <b>翼の先の手で体を引きずって這いながら</b>胞子をばらまいて周囲を汚染する
 * ({@link VelophyllGroundSporeGoal})。グループ4 共通の「汚染を自分の足で運ぶ」役割を、
 * アルボラより遠くまで運べる形にしたのがこの Mob。
 *
 * <h2>群れの上空を飛ぶ(2026-09-15)</h2>
 * グループ4 の群れ({@link InvaderPack})の一員で、地上の仲間が居るあいだは
 * <b>その重心から {@code velophyllPackLeash} の内側</b>しか飛び回らない
 * ({@link VelophyllWanderGoal})。空から見つけた相手は仲間へ回し
 * ({@link InvaderPack#shareTarget})、逆に仲間が回してきた相手も受け取る —— ただし
 * <b>爆弾が届かない相手は断る</b>({@link #acceptsPackTarget})。
 * これで「上空の斥候が群れを引っ張る」形になる。
 *
 * <h2>なぜ {@link OvergrownFlyerEntity} を継承しないのか</h2>
 * あちらは「トンボ系(ステノデクティア・メガネウラ)」専用の土台で、行動状態も枝に止まる・
 * 獲物を咀嚼するといったトンボの都合で埋まっている。ヴェロフィルは
 * <b>飛行と地上の 2 つの移動モードを切り替える</b>必要があり(地上では重力を戻して
 * {@link GroundPathNavigation} で這う)、状態もまったく別物なので、飛行まわりの仕掛けだけを
 * 写して独立させてある。
 *
 * <h2>姿勢はレンダラー側</h2>
 * モデルの根ボーンが {@code head} で、回すと全身が回ってしまう({@code velophyll.geo.json} の
 * 出力そのまま)。そのため<b>全身の傾き(急降下の頭下げ・旋回のバンク)はアニメーションではなく
 * {@code client/VelophyllRenderer} が速度から付ける</b>。ここでは補間用の値
 * ({@link #getPitchAmount} / {@link #getBankAmount})だけを持つ。
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code glide} / {@code fly} / {@code dive} / {@code crawl} / {@code ground_idle} /
 * {@code spore} / {@code takeoff}。
 * {@code assets/overgrown_vegetation/animations/velophyll.animation.json}。
 * 動きの確認は {@code tools/anim-preview}(モーションビューア)で。
 */
public class VelophyllEntity extends PathfinderMob
        implements GeoEntity, ParasitizedMob, InvaderPackMember {

    /** 空を滑空している(翼はほぼ止めたまま)。 */
    public static final int STATE_GLIDE = 0;
    /** 羽ばたいて上昇・加速している。 */
    public static final int STATE_FLAP = 1;
    /** 爆撃のため急降下している。 */
    public static final int STATE_DIVE = 2;
    /** 地上を這って移動している。 */
    public static final int STATE_CRAWL = 3;
    /** 地上で止まっている。 */
    public static final int STATE_GROUND_IDLE = 4;
    /** 地上で胞子をばらまいている。 */
    public static final int STATE_SPORE = 5;
    /** 飛び立つ瞬間。 */
    public static final int STATE_TAKEOFF = 6;

    private static final EntityDataAccessor<Integer> DATA_ACTION_STATE =
            SynchedEntityData.defineId(VelophyllEntity.class, EntityDataSerializers.INT);
    /** 地上モード(重力あり・地上経路)か。描画とアニメーション選択でもクライアントが見る。 */
    private static final EntityDataAccessor<Boolean> DATA_GROUNDED =
            SynchedEntityData.defineId(VelophyllEntity.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation GLIDE_ANIM = RawAnimation.begin().thenLoop("glide");
    private static final RawAnimation FLY_ANIM = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation DIVE_ANIM = RawAnimation.begin().thenLoop("dive");
    private static final RawAnimation CRAWL_ANIM = RawAnimation.begin().thenLoop("crawl");
    private static final RawAnimation GROUND_IDLE_ANIM = RawAnimation.begin().thenLoop("ground_idle");
    private static final RawAnimation SPORE_ANIM = RawAnimation.begin().thenLoop("spore");
    private static final RawAnimation TAKEOFF_ANIM = RawAnimation.begin().thenPlayAndHold("takeoff");

    /**
     * 動作を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>(4.7.6 の {@code AnimationController#process})。
     * だから画面の上では、どのアニメも状態を切り替えた tick より 5 tick 遅れて始まる。
     * アニメの長さに合わせる tick は「これ + アニメの秒 × 20」で数える。
     */
    static final int TRANSITION_TICKS = 5;

    /**
     * {@link #STATE_TAKEOFF} を見せる長さ(tick)。{@code takeoff} の 0.9 秒に補間のぶんを足す。
     * 足していなかったころ(18)は画面の 0.6 秒ほどで滑空へ切り替わり、翼を広げきる最後が切れていた(2026-09-23)。
     */
    public static final int TAKEOFF_TICKS = TRANSITION_TICKS + 18;

    /** 羽ばたき(= {@code fly})へ切り替える上昇速度の下限(ブロック/tick)。 */
    private static final double FLAP_CLIMB_SPEED = 0.02D;
    /** 翼の後ろに粒子を曳く間隔(tick)。 */
    private static final int TRAIL_INTERVAL = 2;
    /** 1 回につき片翼から出す粒の数。1 粒が細かいので数で見せる。 */
    private static final int TRAIL_PER_WING = 3;
    /** 翼端のおおよその位置(体の中心から左右へ、ブロック)。粒子を出す場所。 */
    private static final double WING_TIP_OFFSET = 2.3D;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    private final PathNavigation flyingNavigation;
    private final PathNavigation groundNavigation;
    private final MoveControl flyingMoveControl;
    private final MoveControl groundMoveControl;

    /**
     * 飛行中に向かっている点。<b>飛んでいる間は経路探索を使わない</b>(ファントムと同じ) —
     * 経路の折り返しでその場コマのように回ってしまい、「飛行機のような大きな旋回」に
     * ならないため(ユーザー要望 2026-09-14)。飛行中のゴールはこの点を置くだけで、
     * 実際の曲がり方と加減速は {@link VelophyllFlightMoveControl} が決める。
     */
    @Nullable
    private Vec3 moveTargetPoint;

    /** プレイヤーを見つけられないまま経った tick。{@link VelophyllGroundSporeGoal} の発動条件。 */
    private int playerFreeTicks;
    /** 次の爆撃に入れるようになる {@code tickCount}。 */
    private int bombReadyTick;
    /** 次に噛めるようになる {@code tickCount}。 */
    private int biteReadyTick;
    /** {@link #STATE_TAKEOFF} の残り tick。 */
    private int takeoffTicks;

    /** 機首の上げ下げ(-1 = 真下、+1 = 真上)。描画専用の補間値。 */
    private float pitchAmount;
    private float pitchAmountO;
    /** 旋回のバンク(-1 = 左、+1 = 右)。描画専用の補間値。 */
    private float bankAmount;
    private float bankAmountO;
    /** 地上モードへの寄り具合(0 = 飛行、1 = 地上)。描画専用の補間値。 */
    private float groundAmount;
    private float groundAmountO;
    /** 前 tick の座標。「実際に動いた量」を出すために自前で持つ(姿勢の計算に使う)。 */
    private double lastX;
    private double lastY;
    private double lastZ;

    /**
     * 群れの所属(2026-09-15)。同じ ID を持つ個体が 1 つの群れ。
     * 決め方は {@link InvaderPackBonds}、置き場所の考え方は {@link InvaderPackBond}。
     */
    private final InvaderPackBond packBond = new InvaderPackBond();

    @Override
    public InvaderPackBond packBond() {
        return this.packBond;
    }

    public VelophyllEntity(EntityType<? extends VelophyllEntity> type, Level level) {
        super(type, level);
        this.flyingMoveControl = new VelophyllFlightMoveControl(this);
        this.groundMoveControl = new MoveControl(this);
        this.flyingNavigation = createFlyingNavigation(level);
        this.groundNavigation = createGroundNavigation(level);
        this.moveControl = this.flyingMoveControl;
        this.navigation = this.flyingNavigation;
        this.setPathfindingMalus(PathType.DANGER_FIRE, -1.0F);
        this.setPathfindingMalus(PathType.WATER, -1.0F);
        this.setPathfindingMalus(PathType.WATER_BORDER, 16.0F);
        // 葉の当たり判定は 1 ブロックまるごとなので「通れる」扱いにすると必ず詰まる
        // (グループ4 共通の教訓。AbstractOvergrownInvaderEntity のコメント参照)。
        this.setPathfindingMalus(PathType.LEAVES, -1.0F);
        this.xpReward = MobStats.xpReward("velophyll", 10);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "velophyll");
    }

    // --- 移動モードの切り替え ---

    /**
     * {@code Mob} のコンストラクタが呼ぶ {@link #createNavigation} は<b>飛行側</b>を返す
     * (湧いた直後は必ず空にいる)。地上側は {@link #setGrounded} で差し替える。
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return createFlyingNavigation(level);
    }

    private PathNavigation createFlyingNavigation(Level level) {
        FlyingPathNavigation navigation = new FlyingPathNavigation(this, level) {
            @Override
            public boolean isStableDestination(BlockPos pos) {
                return true;
            }

            /**
             * 経路上の次の点の高さをそのまま使う。既定実装は足元の床の高さへ引き下げてしまうため、
             * 地面や葉の上を飛ぶあいだ目標高度が暴れて体が上下に揺れる
             * ({@link OvergrownFlyerEntity} と同じ理由)。
             */
            @Override
            protected double getGroundY(Vec3 vec) {
                return vec.y;
            }
        };
        navigation.setCanOpenDoors(false);
        navigation.setCanFloat(false);
        navigation.setCanPassDoors(false);
        return navigation;
    }

    private PathNavigation createGroundNavigation(Level level) {
        return new GroundPathNavigation(this, level) {
            @Override
            protected PathFinder createPathFinder(int maxVisitedNodes) {
                // 幅 1 ブロック超の体でも「上りきるまでに体がつかえないか」を必ず確認する
                // (グループ4 共通。wide-mob pathing の項を参照)。
                this.nodeEvaluator = new OvergrownNodeEvaluator();
                this.nodeEvaluator.setCanPassDoors(false);
                return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
            }
        };
    }

    /** 飛行中に向かっている点。{@code null} なら移動しない。 */
    @Nullable
    public Vec3 getMoveTargetPoint() {
        return this.moveTargetPoint;
    }

    /** 飛行中の行き先を置く。曲がり方と加減速は {@link VelophyllFlightMoveControl} 任せ。 */
    public void setMoveTargetPoint(@Nullable Vec3 point) {
        this.moveTargetPoint = point;
    }

    /** 地上モードか。 */
    public boolean isGrounded() {
        return this.entityData.get(DATA_GROUNDED);
    }

    /**
     * 飛行 ⇔ 地上 を切り替える。
     *
     * <p>飛行側は<b>行き先の点 + {@link VelophyllFlightMoveControl}</b>(経路探索なし)、
     * 地上側は<b>{@link GroundPathNavigation} + 素の {@link MoveControl}</b>。
     * 重力の有無は {@link #travel} が {@link #isGrounded()} を見て決めるので、
     * ここで {@code setNoGravity} を触る必要はない。
     */
    public void setGrounded(boolean grounded) {
        if (isGrounded() == grounded) {
            return;
        }
        this.entityData.set(DATA_GROUNDED, grounded);
        this.navigation.stop();
        this.moveTargetPoint = null;
        if (grounded) {
            this.moveControl = this.groundMoveControl;
            this.navigation = this.groundNavigation;
        } else {
            this.moveControl = this.flyingMoveControl;
            this.navigation = this.flyingNavigation;
        }
    }

    // --- 状態 ---

    public int getActionState() {
        return this.entityData.get(DATA_ACTION_STATE);
    }

    public void setActionState(int state) {
        this.entityData.set(DATA_ACTION_STATE, state);
    }

    /** 飛び立つモーションを見せ始める(地上 → 飛行の切り替えは呼び出し側で)。 */
    public void beginTakeoff() {
        this.takeoffTicks = TAKEOFF_TICKS;
        setActionState(STATE_TAKEOFF);
    }

    /** プレイヤーを見つけられないまま経った tick。 */
    public int getPlayerFreeTicks() {
        return this.playerFreeTicks;
    }

    public void resetPlayerFreeTicks() {
        this.playerFreeTicks = 0;
    }

    /**
     * <b>手が届く標的</b>(爆撃か噛みつきのどちらかで襲える相手)を持っているか。
     * 行動の分かれ目はすべてこれで判定する({@code getTarget() != null} ではなく)。
     *
     * <p>{@link VelophyllTargetGoal} は地下の相手を選ばないが、<b>反撃</b>
     * ({@link OvergrownHurtByTargetGoal})は洞窟から撃ってきた相手でも標的にしてしまう。
     * そこを素通りさせると「届かない相手を抱えたまま上空を旋回し続ける」に逆戻りするので、
     * <b>届かない標的は行動の上では居ないものとして扱う</b>(= ふだんの飛び回りや
     * 地上での胞子まきに移る)。
     */
    public boolean hasAttackTarget() {
        LivingEntity target = getTarget();
        return target != null && target.isAlive() && VelophyllTargetGoal.isAttackable(this, target);
    }

    /** <b>爆弾が届く</b>標的(= 地下にも空中にもいない相手)か。{@link VelophyllBombGoal} の入口。 */
    public boolean hasBombTarget() {
        LivingEntity target = getTarget();
        return target != null && target.isAlive() && VelophyllTargetGoal.isBombable(this, target);
    }

    /**
     * <b>噛みつく</b>標的(= 浮いている相手)か。{@link VelophyllBiteGoal} の入口。
     * ユーザー要望 2026-09-17「ファントムのようにかみついて攻撃できるように」。
     */
    public boolean hasBiteTarget() {
        LivingEntity target = getTarget();
        return target != null && target.isAlive() && VelophyllTargetGoal.isAirborne(this, target);
    }

    /** いま噛めるか(前の一噛みからの間隔が明けているか)。 */
    public boolean canBiteNow() {
        return this.tickCount >= this.biteReadyTick;
    }

    /** 一噛みした。次に噛めるのは {@code velophyllBiteCooldownTicks} 後。 */
    public void startBiteCooldown() {
        this.biteReadyTick = this.tickCount
                + OvergrownVegetationConfig.COMMON.velophyllBiteCooldownTicks.get();
    }

    /** いま爆撃に入れるか(撃ち切ってからのクールタイムが明けているか)。 */
    public boolean canBombNow() {
        return this.tickCount >= this.bombReadyTick;
    }

    /** 1 回の爆撃を撃ち切った。次に撃てるのは {@code velophyllBombCooldownTicks} 後。 */
    public void startBombCooldown() {
        this.bombReadyTick = this.tickCount
                + com.yayuy.overgrownvegetation.data.MobStats.bomb("velophyll").cooldownTicks();
    }

    /**
     * <b>「暇そうだから消す」を止める</b>(ユーザー報告 2026-09-15「少し離れただけでデスポーンする」)。
     *
     * <p>この Mob では特に効く —— 巡航高度が {@code velophyllCruiseAltitude}(既定 28)＋ばらつきなので
     * <b>プレイヤーの真上を飛んでいるだけでバニラの「32 ブロック」を超える</b>。そのままだと
     * 見えている個体が 1 分ほどで消え、「3 分見つけられなかったら降りて汚染」にも永久に届かない。
     * 詳しくは {@link InvaderDespawn}。
     */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return InvaderDespawn.removeWhenFarAway(this, distanceToClosestPlayer);
    }

    // --- 群れ(グループ4 同士の集団行動。2026-09-15) ---

    /**
     * 地上の仲間の重心からこれだけ離れてよい(ブロック)。飛んでいる分ずっと広い
     * ({@code velophyllPackLeash})—— 群れの真上を大きく回るための余裕。
     * 実際に守らせているのは {@link VelophyllWanderGoal}(飛行中は経路探索を使わないので、
     * {@link InvaderPackGoal} は使えない)。
     */
    @Override
    public double packCohesionDistance() {
        return OvergrownVegetationConfig.COMMON.velophyllPackLeash.get();
    }

    /**
     * 見つけた相手を回すとき、声が届く距離。<b>巡航高度のぶん足す</b> —— 上空 28 ブロックから
     * 地上の仲間へ伝えるので、地上同士と同じ {@code invaderPackCallRange} では真下にも届かない。
     *
     * <p>逆向き(地上の仲間 → 上空のヴェロフィル)は広げていない。ヴェロフィルの索敵は
     * 上下も水平と同じだけ探す半径 {@code followRange}(既定 64)なので、
     * 地上で始まった戦いは自分で見つけられる({@link VelophyllTargetGoal} 参照)。
     */
    @Override
    public double packCallRange() {
        return OvergrownVegetationConfig.COMMON.invaderPackCallRange.get()
                + OvergrownVegetationConfig.COMMON.velophyllCruiseAltitude.get();
    }

    /**
     * 仲間が回してきた標的のうち、<b>手の届かない相手は断る</b>(地下に潜っている相手)。
     * 受け取ってしまうと、地下のプレイヤーを抱えたまま上空を旋回し続ける
     * ({@link #hasAttackTarget()} と同じ理由)。空中の相手は噛みつきで襲えるので受け取る。
     */
    @Override
    public boolean acceptsPackTarget(LivingEntity target) {
        return VelophyllTargetGoal.isAttackable(this, target);
    }

    /**
     * <b>飛んでいる間は群れの重心に数えない</b>。高度 30 ブロックの個体を混ぜると重心が空へ引かれ、
     * 地上の仲間が全員「重心から離れている」ことになって集まり方が壊れる。
     * 降りて胞子をまいている間は普通に数える。
     */
    @Override
    public boolean countsInPackCentroid() {
        return isGrounded();
    }

    // --- ゴール ---

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 空中の相手には噛みつき、地上の相手には爆撃。canUse は互いに排他なので順序は見た目だけ。
        this.goalSelector.addGoal(1, new VelophyllBiteGoal(this));
        // 忌避灯の爆撃(2026-09-19)。生き物の爆撃と同じ優先度で先に登録 = 灯りが見えていれば灯りを優先。
        this.goalSelector.addGoal(2, new VelophyllLampBombGoal(this));
        this.goalSelector.addGoal(2, new VelophyllBombGoal(this));
        this.goalSelector.addGoal(3, new VelophyllGroundSporeGoal(this));
        this.goalSelector.addGoal(6, new VelophyllWanderGoal(this));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 16.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        // グループ4 共通: プレイヤー + グループ1〜10 に属していない生き物すべて。
        // 上下も水平と同じだけ探す索敵(飛んでいる Mob がバニラの「上下 4 ブロック」の箱では
        // 真下のプレイヤーを一生見つけられないため。VelophyllTargetGoal の説明を参照)。
        this.targetSelector.addGoal(2, new VelophyllTargetGoal(this, Predation::isInvaderTarget));
        // グループ1〜3 も獲物(2026-09-15)。爆弾が届く相手だけを選ぶのは同じ。
        this.targetSelector.addGoal(3, new VelophyllTargetGoal(this, Predation::isInvaderPrey));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION_STATE, STATE_GLIDE);
        builder.define(DATA_GROUNDED, false);
    }

    // --- tick ---

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (this.takeoffTicks > 0) {
            tickTakeoff();
        }
        tickPlayerSearch();
        // 空から見つけた相手を地上のグループ4 へ回す(群れで一緒に襲う)。中で間引いている。
        // 近くの仲間と群れ(所属)を作る・入る・合流する・抜ける。これも中で間引いている。
        InvaderPackBonds.tick(this);
        InvaderPack.shareTarget(this);
    }

    /**
     * 飛び立つモーションのあいだ、実際に体を持ち上げる。
     *
     * <p>これが無いと 0.9 秒のモーションの間だけ<b>飛び立つ姿勢のまま地面へ落ちる</b> —
     * 飛行の移動コントロールは目的地を渡されて初めて推力を入れるが、その目的地を出すのは
     * 次のゴール({@link VelophyllWanderGoal} など)で、モーションが終わるまで動き出さないため。
     */
    private void tickTakeoff() {
        // 補間の間は画面でまだ飛び立つモーションが始まっていないので、持ち上げない(持ち上げる tick 数は以前と同じ 18)。
        if (this.takeoffTicks <= TAKEOFF_TICKS - TRANSITION_TICKS) {
            this.setDeltaMovement(this.getDeltaMovement().add(0.0D, 0.055D, 0.0D));
        }
        if (--this.takeoffTicks == 0) {
            if (getActionState() == STATE_TAKEOFF) {
                // 以降は速度から滑空/羽ばたきが選ばれる。
                setActionState(STATE_GLIDE);
            }
        }
    }

    /**
     * 「3 分プレイヤーを見つけられなかったか」を数える。
     *
     * <p><b>見ているのはプレイヤーだけ</b>(ユーザー指定)。フィロセンスで一度やらかしたように
     * 「襲う相手がいないこと」を条件にすると、牛 1 頭や地下のコウモリ 1 匹で永久に発動しなくなる。
     */
    private void tickPlayerSearch() {
        double range = OvergrownVegetationConfig.COMMON.velophyllSightRange.get();
        double rangeSq = range * range;
        for (Player player : this.level().players()) {
            if (this.distanceToSqr(player) > rangeSq
                    || !Predation.isInvaderTarget(this, player)
                    // 地下に潜っているプレイヤーは「見つけた」に数えない。数えてしまうと、
                    // 近くの洞窟に 1 人いるだけで<b>永久に地上へ降りて胞子をまかなくなる</b>。
                    || !VelophyllTargetGoal.isAttackable(this, player)) {
                continue;
            }
            this.playerFreeTicks = 0;
            return;
        }
        this.playerFreeTicks++;
    }

    @Override
    public void tick() {
        super.tick();
        this.pitchAmountO = this.pitchAmount;
        this.bankAmountO = this.bankAmount;
        this.groundAmountO = this.groundAmount;
        this.groundAmount += ((isGrounded() ? 1.0F : 0.0F) - this.groundAmount) * 0.12F;
        if (isGrounded()) {
            // 地上では機体姿勢を水平へ戻す。
            this.pitchAmount += (0.0F - this.pitchAmount) * 0.2F;
            this.bankAmount += (0.0F - this.bankAmount) * 0.2F;
        } else {
            tickFlightPosture();
            if (this.level().isClientSide()) {
                spawnWingTrail();
            }
        }
        this.lastX = this.getX();
        this.lastY = this.getY();
        this.lastZ = this.getZ();
    }

    /**
     * 速度から機首の上げ下げとバンクを決める(エリトラ飛行の見え方に寄せる)。
     * どちらも指数平滑して急に折れないようにする。
     *
     * <p>見るのは {@code getDeltaMovement()} ではなく<b>実際に動いた量</b>。
     * クライアントでは遠くの Mob の {@code deltaMovement} は更新されない(座標は補間で動く)ので、
     * それを見ると機首が永久に水平のままになる。
     */
    private void tickFlightPosture() {
        Vec3 motion = new Vec3(this.getX() - this.lastX, this.getY() - this.lastY, this.getZ() - this.lastZ);
        double horizontal = motion.horizontalDistance();
        // 上下速度を水平速度で割った角度を -1..1 に写す。止まっているときは水平。
        float wantedPitch = horizontal < 1.0E-4D
                ? 0.0F
                : (float) Mth.clamp(motion.y / Math.max(horizontal, 0.08D), -1.0D, 1.0D);
        this.pitchAmount += (wantedPitch - this.pitchAmount) * 0.12F;

        // 旋回の速さからバンクを作る。yRotO との差はそのままこの tick の旋回量。
        float turn = Mth.wrapDegrees(this.getYRot() - this.yRotO);
        float wantedBank = Mth.clamp(turn / 6.0F, -1.0F, 1.0F);
        this.bankAmount += (wantedBank - this.bankAmount) * 0.15F;
    }

    /**
     * ファントムのように翼の後ろへ粒子を曳く(ユーザー要望)。色は汚染シダなどと同じ胞子だが、
     * 粒は {@link ModParticles#FINE_SPORE 細かいほう}を使う —— 地割れ用の
     * {@code OVERGROWN_SPORE} は 1 粒が大きすぎて、航跡には向かなかった(2026-09-14 の指摘)。
     *
     * <p>翼端 1 点ではなく、<b>翼の外寄り数か所から少しずつ散らす</b>ことで「粉が筋を引く」形にする。
     * クライアント側だけで出すので通信は一切発生しない。
     */
    private void spawnWingTrail() {
        if (this.tickCount % TRAIL_INTERVAL != 0 || this.isInvisible()) {
            return;
        }
        float yaw = this.yBodyRot * Mth.DEG_TO_RAD;
        double sideX = Mth.cos(yaw);
        double sideZ = Mth.sin(yaw);
        // 翼は後退しているので、翼端は体の中心より少し後ろにある。
        double backX = Mth.sin(yaw) * 0.4D;
        double backZ = -Mth.cos(yaw) * 0.4D;
        double y = this.getY() + this.getBbHeight() * 0.6D;
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < TRAIL_PER_WING; i++) {
                // 翼端寄りほど濃く出す(付け根側は薄い)。
                double along = WING_TIP_OFFSET * (0.55D + 0.45D * this.random.nextDouble());
                double x = this.getX() + sideX * along * side + backX;
                double z = this.getZ() + sideZ * along * side + backZ;
                this.level().addParticle(ModParticles.FINE_SPORE.get(),
                        x + (this.random.nextDouble() - 0.5D) * 0.12D,
                        y + (this.random.nextDouble() - 0.5D) * 0.12D,
                        z + (this.random.nextDouble() - 0.5D) * 0.12D,
                        0.0D, -0.005D, 0.0D);
            }
        }
    }

    /** 描画用: 機首の上げ下げ(-1 = 真下、+1 = 真上)。 */
    public float getPitchAmount(float partialTick) {
        return Mth.lerp(partialTick, this.pitchAmountO, this.pitchAmount);
    }

    /** 描画用: 旋回のバンク(-1 = 左、+1 = 右)。 */
    public float getBankAmount(float partialTick) {
        return Mth.lerp(partialTick, this.bankAmountO, this.bankAmount);
    }

    /**
     * 描画用: 地上モードへの寄り具合(0 = 飛行、1 = 地上)。
     *
     * <p>このモデルは<b>翼の膜が原点(= 足元の高さ)より 0.5 ブロックほど下へ垂れている</b>
     * (飛んでいる姿で作られているので当然)。そのまま地面に置くと膜が床へ沈むので、
     * 地上にいる間だけレンダラーが体を持ち上げる。切り替えでガクッと跳ねないよう、
     * この値を補間して使う。
     */
    public float getGroundAmount(float partialTick) {
        return Mth.lerp(partialTick, this.groundAmountO, this.groundAmount);
    }

    // --- 移動 ---

    /** 空気抵抗(1 tick あたり)。バニラの {@code FlyingMob} と同じ値。 */
    private static final float AIR_DRAG = 0.91F;

    /**
     * 飛んでいる間は<b>重力も脚の推力も無い</b> — 速度は
     * {@link VelophyllFlightMoveControl} が機首方向へ直接入れたものだけで、ここは
     * それを動かして空気抵抗を掛けるだけ(バニラの {@code FlyingMob#travel} と同じ形)。
     *
     * <p>これが「飛行機のような動き」の土台になっている: 推力が常に機首方向にしか入らないので
     * <b>横滑りができず、行き先が真横にあっても回り込むしかない</b>。
     * 地上モードでは普通の歩行({@code super.travel})に戻り、重力も戻る。
     *
     * <h2>⚠ {@code isControlledByLocalInstance()} の囲みは必須</h2>
     * {@code travel()} は<b>クライアントでも毎 tick 呼ばれる</b>。この囲みを忘れると、
     * クライアントが「サーバーから来た座標へ補間する」のと「自分で {@code move()} して進む」のを
     * 同時にやってしまい、<b>飛んでいる姿がガクガク震える</b>(2026-09-14 のユーザー報告
     * 「飛行中がくついてしまっています」の原因。速いほど目立つ)。
     * バニラの {@code LivingEntity#travel} も {@code FlyingMob#travel} も同じ囲みを持っている。
     */
    @Override
    public void travel(Vec3 travelVector) {
        if (isGrounded()) {
            super.travel(travelVector);
            return;
        }
        if (this.isControlledByLocalInstance()) {
            this.move(net.minecraft.world.entity.MoverType.SELF, this.getDeltaMovement());
            this.setDeltaMovement(this.getDeltaMovement().scale(AIR_DRAG));
        }
        this.calculateEntityAnimation(false);
    }

    /** 飛行 Mob なので落下では傷まない(地上へ降りるのも自分の意思)。 */
    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround,
                                   net.minecraft.world.level.block.state.BlockState state, BlockPos pos) {
    }

    @Override
    public boolean isFlapping() {
        return !isGrounded();
    }

    /**
     * バニラ既定の描画距離は「当たり判定の辺の平均 × 64」で、この Mob だと約 88 ブロックで
     * 描画が切れる。当たり判定は<b>胴体だけ</b>に絞ってあり、実際の見た目(翼を広げて 5.8 ブロック)
     * より遥かに小さいので、そのままだと上空を旋回している姿が近距離で唐突に消える。
     * {@code clientTrackingRange}(16 チャンク)に見合う距離まで広げる。
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 192.0D * 192.0D;
    }

    // --- 死亡時の汚染(グループ4 共通) ---

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

    // --- アニメーション ---

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, state -> {
            switch (getActionState()) {
                case STATE_TAKEOFF -> {
                    return state.setAndContinue(TAKEOFF_ANIM);
                }
                case STATE_SPORE -> {
                    return state.setAndContinue(SPORE_ANIM);
                }
                case STATE_DIVE -> {
                    return state.setAndContinue(DIVE_ANIM);
                }
                case STATE_CRAWL -> {
                    return state.setAndContinue(CRAWL_ANIM);
                }
                case STATE_GROUND_IDLE -> {
                    return state.setAndContinue(GROUND_IDLE_ANIM);
                }
                default -> {
                }
            }
            // 滑空か羽ばたきかは上下速度で決める:降りている間は翼を止め(滑空)、
            // 高度を保つ・上げるときだけ羽ばたく。これが「モモンガのような滑空」の見え方を作る。
            return state.setAndContinue(
                    this.getDeltaMovement().y > FLAP_CLIMB_SPEED ? FLY_ANIM : GLIDE_ANIM);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // --- 音 ---

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.PHANTOM_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PHANTOM_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PHANTOM_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.8F;
    }
    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        this.packBond.save(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.packBond.load(tag,
                com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.invaderPackCompleteGraceTicks.get());
    }

}
