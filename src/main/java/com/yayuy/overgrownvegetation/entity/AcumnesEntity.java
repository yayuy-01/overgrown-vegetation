package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.flyer.DartHover;
import com.yayuy.overgrownvegetation.entity.flyer.FlightBody;
import com.yayuy.overgrownvegetation.entity.flyer.Flock;
import com.yayuy.overgrownvegetation.entity.flyer.FlyerWind;
import com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer;
import com.yayuy.overgrownvegetation.entity.flyer.SkyProbe;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * アクムネス {@code acumnes}(G3・空の狙撃)。Vault「Mob の能力/アクムネス」。
 *
 * <h2>段 1(2026-11-15): 飛ぶ・止まる・移るまで</h2>
 * 動きは「飛ぶ型」の土台 {@link SkyFlyer} の上に、トンボの「止まっては飛ぶ」({@link DartHover})を {@link #ownFlight} で載せる。
 * <ul>
 *   <li>空中でピタッと止まり(実体は動かさない。わずかな揺れはアニメ)、相手を向く。次の場所へは、まず向きを変えてから短く鋭く飛んで、また止まる。</li>
 *   <li>高さ: 相手の足元の 8〜20 ブロック上【決定 11-14】。相手から水平に 10〜40(11-16 に射程を 60 へ広げたとき。前は 5〜14)、見える所(射線が通る所)を優先【仮】。
 *       相手がいないときは家のまわり 16 の林冠 +3〜8【仮】。</li>
 *   <li>撃った後は必ず場所を移す口 {@link #relocate()}(今いる所を「使った場所」に入れ、そこから 4 ブロック以内は選ばない)。
 *       技(狙撃・散弾・縫い止め)を撃った後に呼ぶ。</li>
 *   <li>休む: 相手がいないまま 200 tick【仮】経つと、近くの一番高い木のてっぺんに止まって待つ(土台の止まり木。巨大樹の枝を選ぶ仕組みはまだ)。
 *       近づきすぎる(8、しゃがむと 4)・叩かれると飛び立つ(土台)。止まっている間は叩いた相手にだけ気付く。</li>
 * </ul>
 * 技(狙撃・急降下の刺突・散弾・縫い止め)は段 2(11-15)で入った({@link #tryStartSkill})。アニメは r8 のまま(新しいアニメは Fable のセッションが作る決まり)。
 */
public class AcumnesEntity extends SkyFlyer implements GeoEntity, ParasitizedMob {

    public static final String ID = "acumnes";

    /** 止まり木へ寄るときの飛び方(土台の惰性の飛び方。ほかはトンボの動き)【仮】。 */
    private static final FlightBody.Params PARAMS = new FlightBody.Params();
    /** 止まっては飛ぶ【仮】。 */
    public static final DartHover.Params DART = new DartHover.Params();
    /** 止まる場所の選び方【仮。高さの帯 8〜20 は決定】。 */
    public static final DartHover.SpotRules SPOTS = new DartHover.SpotRules();

    static {
        PARAMS.cruise = 0.6D;
        PARAMS.minSpeed = 0.2D;
        PARAMS.maxSpeed = 1.0D;
        PARAMS.maxTurnDeg = 6.0D;
        // 11-16 ユーザー「射程を最大 60 に」: 高さの帯 8〜20(決定)はそのまま、相手から水平に 10〜40 へ広げて遠くから撃つ【仮】(前は 5〜14)
        SPOTS.radiusMin = 10.0D;
        SPOTS.radiusMax = 40.0D;
    }

    /** 止まる長さ(tick)。相手がいるとき・いないとき【仮】。 */
    public static final int HOLD_AIM_MIN = 30, HOLD_AIM_MAX = 60, HOLD_IDLE_MIN = 40, HOLD_IDLE_MAX = 100;
    /** 飛ぶ前に向きを変える速さ(度/tick)と、飛び出してよい向きのずれ(度)・止まっている間に相手へ向く速さ【仮】。 */
    public static final float PIVOT_DEG = 15.0F, PIVOT_OK = 25.0F, AIM_TURN_DEG = 8.0F;
    /** 相手がいないまま止まり木へ向かうまで(tick)【仮】。 */
    public static final int IDLE_BEFORE_PERCH = 200;
    /** 覚えておく「使った場所」の数【仮】。 */
    public static final int USED_MEMORY = 4;
    /** 相手を見失う距離(追跡の距離に掛ける)【仮】。 */
    public static final double LOSE_FACTOR = 1.25D;
    /** 体の芯の中ほど・目(棘の付け根)の高さ(ブロック、足元から)。モデルの胸 y 39.6 単位。 */
    public static final double SIGHT_Y = 2.5D;

    /**
     * 叩ける部位【仮】: 左右の翅(付け根 x ±4.5・先 ±43.6 単位 → 中心 ±1.6 ブロック、幅 2.2、胸の上 y 44 単位)・頭(z −9〜−16 → 前 0.8)・
     * 腹(z 9〜50 → 後ろ 1.0 と 2.4 の 2 つ)。体の芯(1.2 × 3.0、垂らした脚まで)は本体の箱。細い針(産卵管)は叩けない。
     */
    static final String[] PARTS = {"wing_l", "wing_r", "head", "gaster_a", "gaster_b"};
    static final float[] PART_W = {2.2F, 2.2F, 0.8F, 1.0F, 1.0F};
    static final float[] PART_H = {0.5F, 0.5F, 0.8F, 0.8F, 0.8F};
    static final double[] PART_CENTERS = {
            0.0D, 1.6D, 2.75D,
            0.0D, -1.6D, 2.75D,
            0.8D, 0.0D, 2.5D,
            -1.0D, 0.0D, 2.25D,
            -2.4D, 0.0D, 2.05D};

    /** 動きの見せ方(描画へ送る): 止まる・相手を狙って止まる・飛ぶ。 */
    public static final int MOTION_HOLD = 0, MOTION_AIM = 1, MOTION_DART = 2;
    private static final EntityDataAccessor<Byte> DATA_MOTION = SynchedEntityData.defineId(AcumnesEntity.class, EntityDataSerializers.BYTE);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation HOVER = RawAnimation.begin().thenLoop("hover");
    private static final RawAnimation FLY = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("death");
    private static final RawAnimation BUZZ = RawAnimation.begin().thenLoop("wings_buzz");
    // 段 2 のアニメ(2026-11-15 モデル制作。Vault アクムネス 5.3・5.7)
    private static final RawAnimation PERCH_IDLE = RawAnimation.begin().thenLoop("perch_idle");
    private static final RawAnimation WINDUP = RawAnimation.begin().thenPlayAndHold("takeoff_windup");
    private static final RawAnimation TAKEOFF = RawAnimation.begin().thenPlay("takeoff").thenLoop("idle");
    private static final RawAnimation LAND = RawAnimation.begin().thenPlay("land_windup").thenLoop("land");
    private static final RawAnimation LAND_RECOVER = RawAnimation.begin().thenPlay("land_recover").thenLoop("perch_idle");
    private static final RawAnimation DART_FLY = RawAnimation.begin().thenPlay("dart").thenLoop("fly");
    private static final RawAnimation RECOVER_IDLE = RawAnimation.begin().thenPlay("dart_recover").thenLoop("idle");
    private static final RawAnimation RECOVER_HOVER = RawAnimation.begin().thenPlay("dart_recover").thenLoop("hover");
    private static final RawAnimation TURN_L = RawAnimation.begin().thenPlayAndHold("turn_l");
    private static final RawAnimation TURN_R = RawAnimation.begin().thenPlayAndHold("turn_r");
    private static final RawAnimation TURN_L_RECOVER = RawAnimation.begin().thenPlay("turn_l_recover");
    private static final RawAnimation TURN_R_RECOVER = RawAnimation.begin().thenPlay("turn_r_recover");
    // 技(2026-11-15 能力の段)
    private static final RawAnimation AIM = RawAnimation.begin().thenPlay("aim").thenLoop("aim_hold");
    private static final RawAnimation AIM_V = RawAnimation.begin().thenPlay("aim_v").thenLoop("aim_hold");
    private static final RawAnimation FIRE = RawAnimation.begin().thenPlayAndHold("fire");
    private static final RawAnimation RELOAD = RawAnimation.begin().thenPlayAndHold("reload");
    private static final RawAnimation RELOAD_V = RawAnimation.begin().thenPlayAndHold("reload_v");
    private static final RawAnimation CANCEL = RawAnimation.begin().thenPlayAndHold("aim_cancel");
    private static final RawAnimation CANCEL_V = RawAnimation.begin().thenPlayAndHold("aim_cancel_v");
    private static final RawAnimation DEATH_AIM = RawAnimation.begin().thenPlayAndHold("death_aim");
    private static final RawAnimation DIVE_WINDUP = RawAnimation.begin().thenPlayAndHold("dive_windup");
    private static final RawAnimation DIVE = RawAnimation.begin().thenLoop("dive");
    private static final RawAnimation DIVE_HIT_RECOVER = RawAnimation.begin().thenPlayAndHold("dive_hit_recover");
    private static final RawAnimation DIVE_MISS_RECOVER = RawAnimation.begin().thenPlayAndHold("dive_recover");
    private static final RawAnimation DIVE_AWAY = RawAnimation.begin().thenLoop("dive_away");
    private static final RawAnimation DIVE_END = RawAnimation.begin().thenPlay("dive_end").thenLoop("idle");
    private static final RawAnimation SCATTER_WINDUP = RawAnimation.begin().thenPlayAndHold("scatter_windup");
    private static final RawAnimation SCATTER = RawAnimation.begin().thenPlayAndHold("scatter");
    private static final RawAnimation SCATTER_RECOVER = RawAnimation.begin().thenPlay("scatter_recover").thenLoop("idle");

    /** 飛び立ちの溜め takeoff_windup(8 tick)と、溜めへ入る補間(その分だけ溜めを長くして、溜めの終わりと昇り始めを揃える)。 */
    public static final int WINDUP_TICKS = 8, ENTRY_SHORT = 3;
    /** 途中から入る所の補間(tick)・空中の dart へ入る補間【仮】。 */
    static final int ENTRY = 4, DART_ENTRY = 2;
    /** アニメの長さ(tick): dart・dart_recover・takeoff・turn_*_recover。 */
    static final int DART_ANIM = 5, DART_RECOVER_ANIM = 12, TAKEOFF_ANIM = 18, TURN_RECOVER_ANIM = 9;
    /** 向き直りの層を出す回りの速さ(度/tick)【仮・5.7 の勧め】。 */
    static final float TURN_LAYER_MIN = 3.0F;
    /** 止まっている間(案 A「若枝」: 翅を背の後ろへ畳み、腹を立てる。Vault 5.7 の 10)の叩ける部位の置き場【仮】。 */
    static final double[] PART_CENTERS_PERCHED = {
            -1.2D, 0.5D, 1.05D,
            -1.2D, -0.5D, 1.05D,
            0.75D, 0.0D, 0.85D,
            -1.0D, 0.0D, 1.0D,
            -1.4D, 0.0D, 2.0D};
    /** 送る動き: 土台の飛び方で止まり木へ寄っている(ただの fly。dart の放ちは流さない)。 */
    public static final int MOTION_APPROACH = 3;

    // ------------------------------------------------------------------ 技(2026-11-15 能力の段。Vault アクムネス「技の決定」)

    /** 送る技の段(下位 5 ビット)と印。{@link #SKILL_V} = 縦回し(aim_v・reload_v・aim_cancel_v)、{@link #SKILL_PIN} = 縫い止め。上位は出し直しの番号。 */
    public static final int S_NONE = 0, S_AIM = 1, S_FIRE = 2, S_RELOAD = 3, S_CANCEL = 4,
            S_DIVE_WINDUP = 5, S_DIVE = 6, S_DIVE_HIT = 7, S_DIVE_MISS = 8, S_DIVE_AWAY = 9, S_DIVE_END = 10,
            S_SCATTER_WINDUP = 11, S_SCATTER = 12, S_SCATTER_RECOVER = 13;
    public static final int SKILL_MASK = 31, SKILL_V = 32, SKILL_PIN = 64;
    private static final EntityDataAccessor<Integer> DATA_SKILL = SynchedEntityData.defineId(AcumnesEntity.class, EntityDataSerializers.INT);
    /** 狙っている相手(針の俯角に使う。いなければ −1)。 */
    private static final EntityDataAccessor<Integer> DATA_AIM_TARGET = SynchedEntityData.defineId(AcumnesEntity.class, EntityDataSerializers.INT);

    /** 相手を探す間隔(tick)【決定 11-15: 40 tick おき。見つけた相手は持ち続ける】。 */
    public static final int SEARCH_EVERY = 40;
    /** 狙撃できる距離(胸から相手の胸、ブロック)【12 は仮。最大 60 は 11-16 ユーザー決定(前は 40)】・散弾の距離【仮】。 */
    public static final double SNIPE_MIN = 12.0D, SNIPE_MAX = 60.0D, SCATTER_RANGE = 8.0D;
    /** 狙撃の溜め = 入り 4 + aim 2 秒、縫い止め = 入り 4 + aim 2 秒 + aim_hold 1 秒(合わせて約 3 秒)【決定 11-15】。 */
    public static final int AIM_TICKS = ENTRY + 40, PIN_AIM_TICKS = ENTRY + 60;
    /** 狙いの線(見えない)をこれだけ続けて切られたら取りやめる(0.3 秒)【決定 11-15】。 */
    public static final int LOS_GRACE = 6;
    /** fire の 0.1 秒で針の先が消える = 2 tick 目に弾を出す。fire 0.8 秒・reload 1.2 秒・aim_cancel 0.95 秒。 */
    static final int FIRE_SHOT_TICK = 2, FIRE_TICKS = 16, RELOAD_TICKS = 24, CANCEL_TICKS = 19;
    /** 技を終えてから次の技まで(tick)【決定 11-15: 2.5 秒】。 */
    public static final int SKILL_COOLDOWN = 50;
    /** 縫い止めは狙撃の何回目ごとか【決定 11-15: 3 回目ごと】。 */
    public static final int PIN_EVERY = 3;
    /** ダメージ【決定 11-15 勧めのまま。狙撃は攻撃の値 12】: 縫い止め・散弾 1 発・刺突。縫い止めで動けない tick。 */
    public static final float PIN_DAMAGE = 14.0F, SCATTER_DAMAGE = 3.0F, DIVE_DAMAGE = 10.0F;
    public static final int PIN_TICKS = 20;
    /** 弾の速さ(ブロック/tick)【仮】。11-16 に射程 60 に合わせて狙撃 3.0 → 4.0・縫い止め 2.5 → 3.5(60 ブロックを 15〜17 tick)。 */
    static final double SNIPE_SPEED = 4.0D, PIN_SPEED = 3.5D, SCATTER_SPEED = 1.6D;
    /** 散弾: 前触れ 10・払い 8(弾は 2〜6 tick に 1 発ずつ 5 発)・隙 12【決定 11-15】。扇の向きのずれ(度、左から)【仮】。 */
    static final int SCATTER_WINDUP_TICKS = 10, SCATTER_TICKS = 8, SCATTER_RECOVER_TICKS = 12;
    static final float[] SCATTER_FAN = {-30.0F, -15.0F, 0.0F, 15.0F, 30.0F};
    /** 散弾の弾の出る所(針の先 muzzle、模型の画素。Vault 5.6 の表。x の − が自分の左・z の − が前)。 */
    static final double[][] SCATTER_MUZZLE = {
            {-40.8D, -33.5D, -45.4D}, {-21.6D, -37.7D, -52.1D}, {0.0D, -39.1D, -54.7D}, {21.6D, -37.7D, -52.1D}, {40.8D, -33.4D, -45.3D}};
    /** 刺突: 前触れ 20【決定】・突っ込む速さ 0.9 と最長 16 ブロック【仮】・当てた後 12・外した後 34(針が刺さって抜けない 1.25 秒 + 抜く)【決定】・離れる最長 60・解く 16。 */
    static final int DIVE_WINDUP_TICKS = 20, DIVE_HIT_TICKS = 12, DIVE_MISS_TICKS = 34, DIVE_AWAY_MAX = 60, DIVE_END_TICKS = 16;
    static final double DIVE_SPEED = 0.9D, DIVE_RANGE = 16.0D;
    /** 突っ込む姿(dive)の針の先: 足元の 0.9 上・前 5.2(Vault 5.6)。 */
    static final double DIVE_TIP_UP = 0.9D, DIVE_TIP_FWD = 5.2D;
    /** 刺突の入り口を探す: 相手から水平に 6〜10(屋根の縁の外)【仮】。見えない所に止まってこれだけ続いたら刺突を考える。 */
    static final double DIVE_FROM_MIN = 6.0D, DIVE_FROM_MAX = 10.0D;
    static final int NO_SIGHT_BEFORE_DIVE = 3;
    /** 刺突の後、下がこれだけ空いたら針を解く(dive_end で針が足元の 4.8 下までくぐる)。 */
    static final double DIVE_END_CLEAR = 5.0D;
    /** 照準の姿の針の付け根(needle_aim)の高さ・付け根から針の先(muzzle)までの長さ(ブロック。模型から測った値 17 画素・79.7 画素)。 */
    public static final double NEEDLE_PIVOT_Y = 17.0D / 16.0D, NEEDLE_LEN = 79.7D / 16.0D;
    /** 針を下へ向けられる上限(度)【仮】。 */
    public static final float MAX_DEPRESSION = 75.0F;
    /** aim_v(縦回し)は針の先が足元の 3.3 下までくぐるので、下がこれだけ空いているときだけ。 */
    static final double AIM_V_CLEARANCE = 3.5D;
    /** 狙う間の向き直り(度/tick)【仮】。 */
    static final float SKILL_TURN_DEG = 10.0F;

    // サーバーの技の状態
    private int skillTicks, skillCooldown, losLost, snipeCount, noSightHolds, skillSeq, pickFails;
    /** 刺突へ向かう段(0 = 向かっていない、1 = 入り口の真上へ、2 = 入り口へ降りる)と入り口。 */
    private int diveStage;
    private double diveX, diveY, diveZ;
    /** 突っ込む向き(単位)と進んだ長さ。 */
    private Vec3 diveDir = Vec3.ZERO;
    private double diveDone;
    // クライアント: 針の俯角
    private float needleDep, needleDepO;
    private int clientSkillSeen = -1, clientSkillStart;

    /** クライアント: 今流しているつなぎ・流し始めた tick・入りの補間・この tick の向きの回り(度)。 */
    private RawAnimation curAnim;
    private int curStart, curEntry;
    private float turnRate;
    /** クライアント: 向き直りの層(−1 = 左・1 = 右・0 = 止めている)と、揺れ戻しを始めた tick(−1 = まだ)。 */
    private int turnDir, turnRecStart = -1;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // --- サーバー ---
    private final DartHover hover = new DartHover();
    private DartHover.Phase lastPhase = DartHover.Phase.HOLD;
    private int holdLeft;
    /** 向きを変え終えたら飛ぶ行き先(無ければ NaN)。 */
    private double pendX = Double.NaN, pendY, pendZ;
    /** 場所を移さなければならない(選べなかったので待っている)。 */
    private boolean mustMove;
    private final double[] used = new double[USED_MEMORY * 3];
    private int usedCount, usedNext;
    private int idleTicks;
    private final double[] windNow = new double[2];
    /** 相手がいないときに飛び回る中心(最初に飛んだ所。保存しない)。 */
    private double homeX = Double.NaN, homeZ = Double.NaN;
    private int lastOwnTick = Integer.MIN_VALUE;
    /** 計測: 場所を移した回数(テスト用)。 */
    public int relocations;

    public AcumnesEntity(EntityType<? extends AcumnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 40);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    // ------------------------------------------------------------------ 土台へ渡す値

    @Override
    protected FlightBody.Params params() {
        return PARAMS;
    }

    /** 止まり木へ寄る間の高さ(林冠 + これ)。トンボの動きの間は使わない【仮】。 */
    @Override
    protected double layerMin() {
        return 3.0D;
    }

    @Override
    protected double layerMax() {
        return 8.0D;
    }

    /** 1 体ずつ(群れは組まない。G3 は 1 体ずつ湧く)。 */
    @Override
    protected int flockMax() {
        return 1;
    }

    @Override
    protected String[] partNames() {
        return PARTS;
    }

    @Override
    protected float[] partWidths() {
        return PART_W;
    }

    @Override
    protected float[] partHeights() {
        return PART_H;
    }

    @Override
    protected double[] partCenters() {
        return perchedPose() ? PART_CENTERS_PERCHED : PART_CENTERS;
    }

    /** 止まった姿のままか(止まっている・飛び立ちの待ちと溜め)。送られた状態で決めるのでサーバーもクライアントも同じ。 */
    private boolean perchedPose() {
        int m = mode();
        return m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() < 2;
    }

    /**
     * 木から飛び立つ前の溜め【決定 11-15 ユーザー: 溜めるのは枝(今は木のてっぺん)から飛び立つときだけ】。
     * 空中の「止まっては飛ぶ」は溜めずに dart で鋭く移る。
     */
    /** 止まった姿(案 A「若枝」)は針の先が足元 +7.35 なので、止まり木の真上を 8 空ける(Vault 5.7 の 12。既定は 6)。 */
    public static final double PERCH_CLEAR_UP = 8.0D;

    @Override
    protected double perchClearUp() {
        return PERCH_CLEAR_UP;
    }

    @Override
    protected int takeoffWindupTicks() {
        return WINDUP_TICKS + ENTRY_SHORT;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MOTION, (byte) MOTION_HOLD);
        builder.define(DATA_SKILL, S_NONE);
        builder.define(DATA_AIM_TARGET, -1);
    }

    /**
     * 刺突の間(前触れ〜離れる)は脚を畳むので、当たりの芯を 1.2 × {@link #DIVE_BODY_H} に縮める(3.0 のままだと 3 ブロックの軒下へ入れない)。
     * 送った技の段で決めるのでサーバーもクライアントも同じ。
     */
    public static final float DIVE_BODY_H = 1.4F;

    static boolean divingBody(int sk) {
        return sk == S_DIVE_WINDUP || sk == S_DIVE || sk == S_DIVE_HIT || sk == S_DIVE_MISS || sk == S_DIVE_AWAY;
    }

    @Override
    public net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        net.minecraft.world.entity.EntityDimensions d = super.getDefaultDimensions(pose);
        return divingBody(skill()) ? net.minecraft.world.entity.EntityDimensions.scalable(d.width(), DIVE_BODY_H).withEyeHeight(1.0F) : d;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_SKILL.equals(key)) this.refreshDimensions();
    }

    /** 今の技の段({@link #S_NONE} など)。 */
    public int skill() {
        return this.entityData.get(DATA_SKILL) & SKILL_MASK;
    }

    public boolean skillVertical() {
        return (this.entityData.get(DATA_SKILL) & SKILL_V) != 0;
    }

    public boolean skillPin() {
        return (this.entityData.get(DATA_SKILL) & SKILL_PIN) != 0;
    }

    public int motion() {
        return this.entityData.get(DATA_MOTION);
    }

    /** 相手がいないまま {@link #IDLE_BEFORE_PERCH} tick 経ったら止まり木で待つ(夜・雨は関係ない)。 */
    @Override
    public boolean wantsRoost() {
        if (this.roostOverride != null) return this.roostOverride;
        return this.getTarget() == null && this.idleTicks > IDLE_BEFORE_PERCH;
    }

    // ------------------------------------------------------------------ 相手

    @Override
    protected void customServerAiStep() {
        if (this.level() instanceof ServerLevel sl) tickTargeting(sl);
        super.customServerAiStep();
        // 止まり木へ寄る間(土台の飛び方)は飛ぶ姿に
        // (昇り終えた最初の tick も土台の飛び方になるが、そこは止まり木へ寄るのではないので fly を挟まない)
        if (mode() == MODE_FLY && this.lastOwnTick != this.tickCount && wantsRoost() && this.entityData.get(DATA_MOTION) != MOTION_APPROACH) {
            this.entityData.set(DATA_MOTION, (byte) MOTION_APPROACH);
        } else if (mode() != MODE_FLY && this.entityData.get(DATA_MOTION) != MOTION_HOLD) {
            this.entityData.set(DATA_MOTION, (byte) MOTION_HOLD);   // 降りる・止まる・飛び立つ間は土台の段で決める。昇り終えた所で寄る印が残らないように
        }
    }

    /**
     * 狙ってよい相手か【決定 11-15 ユーザー】: 植物側の Mob 以外すべて(プレイヤー・村人・同盟軍・ゴーレム・動物・ほかの敵対 Mob)。
     * 植物側(G1〜G8・環境生物・萌芽体・ハイヴノード・汎用の感染体 = {@link SporeExposureTracker#isPlantSide})・創造/観戦のプレイヤー・防具立て・無敵の相手は狙わない。
     * 狙っている相手が感染して植物側になったら、そこで忘れる。
     */
    public static boolean isPrey(@javax.annotation.Nullable LivingEntity self, LivingEntity e) {
        return PlantFoes.isFoe(self, e);   // 陣営と除外は共通(11-16 見直し ①13)
    }

    /**
     * 相手の選び方【仮・Claude 11-15】: 叩いた相手 → 見えるプレイヤー → 見えるほかの生き物(近い順)。
     * プレイヤー以外を狙っている間も、見えるプレイヤーが現れたらそちらへ替える(狙撃手はまずプレイヤーを狙う)。
     */
    private void tickTargeting(ServerLevel sl) {
        LivingEntity t = this.getTarget();
        double range = this.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (t != null && (t.level() != this.level() || !isPrey(this, t)
                || this.distanceToSqr(t) > range * range * LOSE_FACTOR * LOSE_FACTOR)) {
            this.setTarget(null);
            t = null;
        }
        // 止まっている間は、叩かれる・近づかれて飛び立つ(土台)まで気付かない
        boolean looking = (this.tickCount + this.getId()) % SEARCH_EVERY == 0 && mode() != MODE_PERCHED && mode() != MODE_LANDING;
        if (t == null) {
            LivingEntity h = this.getLastHurtByMob();
            if (h != null && h.level() == this.level() && isPrey(this, h) && this.distanceToSqr(h) < range * range) {
                this.setTarget(h);
                t = h;
            } else if (looking) {
                t = findPrey(sl, range);
                if (t != null) this.setTarget(t);
            }
        } else if (looking && skill() == S_NONE && this.diveStage == 0 && !(t instanceof Player) && t != this.getLastHurtByMob()) {
            // (技の途中では替えない: 前触れ無しで新しい相手を撃つことになる)
            Player p = sl.getNearestPlayer(preyConditions(range), this);
            if (p != null) {
                this.setTarget(p);
                t = p;
            }
        }
        this.idleTicks = t == null ? this.idleTicks + 1 : 0;
    }

    private TargetingConditions preyConditions(double range) {
        return TargetingConditions.forCombat().range(range).selector(e -> isPrey(this, e));
    }

    /** 見える相手: プレイヤーを先に、いなければ近い生き物。 */
    private LivingEntity findPrey(ServerLevel sl, double range) {
        TargetingConditions c = preyConditions(range);
        Player p = sl.getNearestPlayer(c, this);
        if (p != null) return p;
        return sl.getNearestEntity(LivingEntity.class, c, this, this.getX(), this.getEyeY(), this.getZ(),
                this.getBoundingBox().inflate(range, range, range));
    }

    // ------------------------------------------------------------------ 止まっては飛ぶ

    @Override
    protected boolean ownFlight(ServerLevel sl, Flock f) {
        LivingEntity t = this.getTarget();
        double x = this.getX(), y = this.getY(), z = this.getZ();
        if (Double.isNaN(this.homeX)) {
            this.homeX = x;
            this.homeZ = z;
        }
        if (this.lastOwnTick != this.tickCount - 1) {
            // 飛び立った直後・止まり木へ寄るのをやめた直後: その場でピタッと止まってから選び直す
            this.hover.holdAt(x, y, z);
            this.lastPhase = DartHover.Phase.HOLD;
            this.holdLeft = 10;
            this.pendX = Double.NaN;
            this.idleTicks = 0;   // 飛び立った直後にまた同じ木へ降りない(相手がいなくても 200 tick は飛び回る)
            this.diveStage = 0;
            if (skill() != S_NONE) setSkill(S_NONE, 0);
        }
        this.lastOwnTick = this.tickCount;
        if (this.skillCooldown > 0) this.skillCooldown--;
        if (skill() != S_NONE) {
            tickSkill(sl, t);
            return true;
        }
        if (this.hover.holding()) {
            if (!Double.isNaN(this.pendX)) {
                // 飛ぶ前に向きを変える(その場で回る)
                double yaw = FlightBody.yawOf(this.pendX - x, this.pendZ - z);
                double hd = Math.sqrt((this.pendX - x) * (this.pendX - x) + (this.pendZ - z) * (this.pendZ - z));
                if (hd > 1.0D) turnToward(yaw, PIVOT_DEG);
                if (hd <= 1.0D || Math.abs(Mth.wrapDegrees((float) yaw - this.getYRot())) < PIVOT_OK) {
                    this.hover.dartTo(this.pendX, this.pendY, this.pendZ);
                    this.pendX = Double.NaN;
                }
            } else if (this.diveStage > 0) {
                // 刺突の入り口へ: 真上 → 降りる → 着いたら前触れ
                if (t == null || !t.isAlive()) {
                    this.diveStage = 0;
                } else if (this.hover.settled()) {
                    if (this.diveStage == 1) {
                        this.pendX = this.diveX;
                        this.pendY = this.diveY;
                        this.pendZ = this.diveZ;
                        this.diveStage = 2;
                    } else {
                        this.diveStage = 0;
                        if (diveLine(t, x, y, z)) {
                            setSkill(S_DIVE_WINDUP, 0);
                        } else {
                            relocate();   // 着く間に相手が動いて真っすぐ届かない: やめる
                        }
                    }
                }
            } else {
                if (t != null) turnToward(FlightBody.yawOf(t.getX() - x, t.getZ() - z), AIM_TURN_DEG);
                if (this.hover.settled() && --this.holdLeft <= 0) {
                    if (this.mustMove) {
                        chooseNext(sl, true);   // 前に選べなかった移動の選び直し
                    } else if (t != null) {
                        if (!tryStartSkill(sl, t)) relocate();   // 技が出せなければ場所を移す(見える所を探す)
                    } else {
                        chooseNext(sl, false);
                    }
                }
            }
        } else {
            double hd = Math.sqrt((this.hover.tx - x) * (this.hover.tx - x) + (this.hover.tz - z) * (this.hover.tz - z));
            if (hd > 1.0D) turnToward(FlightBody.yawOf(this.hover.tx - x, this.hover.tz - z), PIVOT_DEG);
        }
        FlyerWind.now(sl, this.windNow);
        this.hover.step(DART, x, y, z, this.windNow[0], this.windNow[1], this.horizontalCollision || this.verticalCollision);
        if (this.hover.phase != this.lastPhase) {
            if (this.hover.phase == DartHover.Phase.HOLD) {
                this.holdLeft = t != null ? Mth.nextInt(this.random, HOLD_AIM_MIN, HOLD_AIM_MAX) : Mth.nextInt(this.random, HOLD_IDLE_MIN, HOLD_IDLE_MAX);
            }
            this.lastPhase = this.hover.phase;
        }
        setOwnMotion(this.hover.vx, this.hover.vy, this.hover.vz);
        // 飛ぶ前にその場で向きを変える間は止まった姿のまま(向き直りの層が体をひねる)
        int m = this.hover.holding() ? (t != null ? MOTION_AIM : MOTION_HOLD) : MOTION_DART;
        if (this.entityData.get(DATA_MOTION) != m) this.entityData.set(DATA_MOTION, (byte) m);
        if (DEV_TRACE && this.hasCustomName() && this.getCustomName().getString().contains("trace") && this.tickCount % 10 == 0) {
            LOG.info("ACUMNES_TRACE {} 段 {} 位置 {} {} {} 速さ {} 相手 {} 相手の上 {} 移った {} 待ち {} 落ち着き {} 移る {} 間 {} 見えない {} 線 {} 段の続き {}", this.getId(), this.hover.phase,
                    String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z), String.format("%.3f", this.hover.speed()),
                    t == null ? "-" : t.getName().getString(), t == null ? "-" : String.format("%.1f", y - t.getY()), this.relocations, this.holdLeft, this.hover.settled(), this.mustMove, this.skillCooldown, this.noSightHolds, t == null ? "-" : hasShotLine(t), this.lastOwnTick);
        }
        return true;
    }

    // ------------------------------------------------------------------ 技(2026-11-15)

    /**
     * 止まり終えたときに技を選ぶ【決定 11-15 勧めのまま】: 見えていて {@link #SCATTER_RANGE}(8)以内なら散弾、{@link #SNIPE_MIN}〜{@link #SNIPE_MAX}(12〜60)なら狙撃(3 回目ごとに縫い止め)。
     * 見えない所に {@link #NO_SIGHT_BEFORE_DIVE} 回続けて止まり、相手が屋根の下なら刺突の入り口を探す。どれでもなければ false(場所を移す)。
     */
    private boolean tryStartSkill(ServerLevel sl, LivingEntity t) {
        if (this.skillCooldown > 0) return false;
        boolean los = hasShotLine(t);
        double d = chest().distanceTo(targetChest(t));
        if (los) {
            this.noSightHolds = 0;
            if (d <= SCATTER_RANGE) {
                setSkill(S_SCATTER_WINDUP, 0);
                return true;
            }
            if (d >= SNIPE_MIN && d <= SNIPE_MAX) {
                boolean pin = this.snipeCount % PIN_EVERY == PIN_EVERY - 1;
                this.snipeCount++;
                this.losLost = 0;
                setSkill(S_AIM, (clearBelow(AIM_V_CLEARANCE) ? SKILL_V : 0) | (pin ? SKILL_PIN : 0));
                this.entityData.set(DATA_AIM_TARGET, t.getId());
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(), net.minecraft.sounds.SoundEvents.GUARDIAN_ATTACK,
                        net.minecraft.sounds.SoundSource.HOSTILE, 2.0F, pin ? 1.0F : 1.3F);   // 狙い始めの合図【仮】
                return true;
            }
            return false;
        }
        if (++this.noSightHolds >= NO_SIGHT_BEFORE_DIVE && underRoof(sl, t)) {
            double[] p = findDiveStart(sl, t);
            if (p != null) {
                this.noSightHolds = 0;
                this.diveX = p[0];
                this.diveY = p[1];
                this.diveZ = p[2];
                this.diveStage = 1;
                this.pendX = p[0];   // まず入り口の真上(今の高さ)へ
                this.pendY = Math.max(this.getY(), p[1]);
                this.pendZ = p[2];
                return true;
            }
        }
        return false;
    }

    private void setSkill(int s, int flags) {
        int prev = skill();
        if (DEV_TRACE && this.hasCustomName() && this.getCustomName().getString().contains("trace")) {
            LivingEntity t = this.getTarget();
            LOG.info("ACUMNES_SKILL {} t {} {} -> {} 印 {} 相手 {} 距離 {}", this.getId(), this.tickCount, prev, s, flags,
                    t == null ? "-" : t.getName().getString(), t == null ? "-" : String.format("%.1f", chest().distanceTo(targetChest(t))));
        }
        this.skillSeq = (this.skillSeq + 1) & 0xFFFF;
        this.entityData.set(DATA_SKILL, s | flags | (this.skillSeq << 8));
        this.skillTicks = 0;
        boolean moving = s == S_DIVE || s == S_DIVE_AWAY;
        boolean wasMoving = prev == S_DIVE || prev == S_DIVE_AWAY;
        if (!moving && (wasMoving || prev == S_NONE)) this.hover.holdAt(this.getX(), this.getY(), this.getZ());   // その場でピタッと止める
        if (s == S_NONE) this.entityData.set(DATA_AIM_TARGET, -1);
    }

    /** 同じ印(縦回し・縫い止め)のまま次の段へ。 */
    private void nextSkill(int s) {
        setSkill(s, this.entityData.get(DATA_SKILL) & (SKILL_V | SKILL_PIN));
    }

    /** 技を終える: 次の技まで {@link #SKILL_COOLDOWN}、そして必ず場所を移す(同じ所から二度は撃たない)。 */
    private void finishSkill() {
        setSkill(S_NONE, 0);
        this.skillCooldown = SKILL_COOLDOWN;
        relocate();
    }

    private void tickSkill(ServerLevel sl, LivingEntity t) {
        this.skillTicks++;
        int s = skill();
        double x = this.getX(), y = this.getY(), z = this.getZ();
        boolean alive = t != null && t.isAlive() && t.level() == this.level();
        switch (s) {
            case S_AIM -> {
                if (!alive || chest().distanceTo(targetChest(t)) > SNIPE_MAX * 1.2D) {
                    cancelAim();
                    break;
                }
                turnToward(FlightBody.yawOf(t.getX() - x, t.getZ() - z), SKILL_TURN_DEG);
                this.losLost = hasShotLine(t) ? 0 : this.losLost + 1;
                if (this.losLost > LOS_GRACE) {
                    cancelAim();   // 物陰で狙いの線を切られた
                    break;
                }
                if (this.skillTicks >= (skillPin() ? PIN_AIM_TICKS : AIM_TICKS)) nextSkill(S_FIRE);
            }
            case S_FIRE -> {
                if (this.skillTicks == FIRE_SHOT_TICK && alive) shoot(t, skillPin());
                if (this.skillTicks >= FIRE_TICKS) {
                    this.entityData.set(DATA_AIM_TARGET, -1);
                    nextSkill(S_RELOAD);
                }
            }
            case S_RELOAD -> {
                if (this.skillTicks >= RELOAD_TICKS) finishSkill();
            }
            case S_CANCEL -> {
                if (this.skillTicks >= CANCEL_TICKS) finishSkill();
            }
            case S_SCATTER_WINDUP -> {
                if (alive) turnToward(FlightBody.yawOf(t.getX() - x, t.getZ() - z), SKILL_TURN_DEG * 1.5F);
                if (this.skillTicks >= SCATTER_WINDUP_TICKS) setSkill(S_SCATTER, 0);
            }
            case S_SCATTER -> {
                int i = this.skillTicks - 2;   // 2〜6 tick に 1 発ずつ
                if (i >= 0 && i < SCATTER_FAN.length) scatterShot(sl, t, i);
                if (this.skillTicks >= SCATTER_TICKS) setSkill(S_SCATTER_RECOVER, 0);
            }
            case S_SCATTER_RECOVER -> {
                if (this.skillTicks >= SCATTER_RECOVER_TICKS) finishSkill();
            }
            case S_DIVE_WINDUP -> {
                if (!alive) {
                    finishSkill();
                    break;
                }
                turnToward(FlightBody.yawOf(t.getX() - x, t.getZ() - z), SKILL_TURN_DEG);
                if (this.skillTicks >= DIVE_WINDUP_TICKS) {
                    // 相手の胸へ、針の先(足元の 0.9 上・前 5.2)が届く向きで突っ込む
                    Vec3 from = new Vec3(x, y + DIVE_TIP_UP, z);
                    Vec3 dir = targetChest(t).subtract(from);
                    this.diveDir = dir.lengthSqr() > 1e-6 ? dir.normalize() : Vec3.directionFromRotation(0.0F, this.getYRot());
                    this.diveDone = 0.0D;
                    setSkill(S_DIVE, 0);
                }
            }
            case S_DIVE -> {
                tickDive(sl, t);
                return;
            }
            case S_DIVE_HIT -> {
                if (this.skillTicks >= DIVE_HIT_TICKS) setSkill(S_DIVE_AWAY, 0);
            }
            case S_DIVE_MISS -> {
                // 針が刺さって抜けない(殴り返せる隙)。体は動かさない
                if (this.skillTicks >= DIVE_MISS_TICKS) setSkill(S_DIVE_AWAY, 0);
            }
            case S_DIVE_AWAY -> {
                // 針を前へ向けたまま、後ろ上へ離れる。下が 5 空いたら解く
                Vec3 back = new Vec3(-this.diveDir.x, 0.0D, -this.diveDir.z);
                back = back.lengthSqr() > 1e-6 ? back.normalize().scale(0.2D) : Vec3.ZERO;
                setOwnMotion(back.x, 0.25D, back.z);
                // 解くと脚が垂れて芯が 3.0 に戻るので、頭の上も空いてから(軒下で戻ると屋根に埋まる)
                boolean room = this.level().noCollision(this, net.minecraft.world.entity.EntityDimensions.scalable(this.getBbWidth(),
                        super.getDefaultDimensions(this.getPose()).height()).makeBoundingBox(this.position()));
                if (room && (this.skillTicks >= 8 && clearBelow(DIVE_END_CLEAR) || this.skillTicks >= DIVE_AWAY_MAX) || this.skillTicks >= DIVE_AWAY_MAX * 3) {
                    setSkill(S_DIVE_END, 0);
                }
                return;
            }
            case S_DIVE_END -> {
                if (this.skillTicks >= DIVE_END_TICKS) finishSkill();
            }
            default -> setSkill(S_NONE, 0);
        }
        // 技の間はその場でピタッと止まる(風にも流されない)
        FlyerWind.now(sl, this.windNow);
        this.hover.step(DART, x, y, z, this.windNow[0], this.windNow[1], false);
        setOwnMotion(this.hover.vx, this.hover.vy, this.hover.vz);
    }

    /** 照準の取りやめ。狙い始めて 0.5 秒(アニメの時刻)までは待機へ直に戻し、それより後は aim_cancel で戻す(旧版と同じ)。 */
    private void cancelAim() {
        this.entityData.set(DATA_AIM_TARGET, -1);
        if ((this.skillTicks - ENTRY) / 20.0F < 0.5F) {
            finishSkill();
        } else {
            nextSkill(S_CANCEL);
        }
    }

    /** 突っ込む: まっすぐ {@link #DIVE_SPEED}。針の先が相手に届けば当たり、ブロックに刺さる・ぶつかる・{@link #DIVE_RANGE} 進めば外れ。 */
    private void tickDive(ServerLevel sl, LivingEntity t) {
        Vec3 v = this.diveDir.scale(DIVE_SPEED);
        setOwnMotion(v.x, v.y, v.z);
        turnToward(FlightBody.yawOf(this.diveDir.x, this.diveDir.z), SKILL_TURN_DEG * 2.0F);
        this.diveDone += DIVE_SPEED;
        Vec3 root = new Vec3(this.getX(), this.getY() + DIVE_TIP_UP, this.getZ());
        Vec3 tip = root.add(this.diveDir.scale(DIVE_TIP_FWD));
        // 当たり: 針(付け根 → 先)に触れた獲物
        AABB sweep = new AABB(root, tip).inflate(0.4D);
        boolean hit = false;
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, sweep, e -> isPrey(this, e))) {
            if (e.getBoundingBox().inflate(0.3D).clip(root, tip).isPresent() || e.getBoundingBox().contains(tip)) {
                e.hurt(this.damageSources().mobAttack(this), DIVE_DAMAGE);
                hit = true;
            }
        }
        if (hit) {
            sl.playSound(null, tip.x, tip.y, tip.z, net.minecraft.sounds.SoundEvents.TRIDENT_HIT, net.minecraft.sounds.SoundSource.HOSTILE, 1.4F, 0.7F);
            setSkill(S_DIVE_HIT, 0);
            return;
        }
        boolean stuck = sl.clip(new ClipContext(root, tip, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS;
        if (stuck || this.horizontalCollision || this.verticalCollision || this.diveDone >= DIVE_RANGE) {
            if (stuck) sl.playSound(null, tip.x, tip.y, tip.z, net.minecraft.sounds.SoundEvents.TRIDENT_HIT_GROUND, net.minecraft.sounds.SoundSource.HOSTILE, 1.4F, 0.6F);
            setOwnMotion(0.0D, 0.0D, 0.0D);
            setSkill(S_DIVE_MISS, 0);
        }
    }

    /** 狙撃・縫い止めの 1 発: 針の付け根から相手の胸へ、針の先から出す(先がブロックの向こうなら手前から)。 */
    private void shoot(LivingEntity t, boolean pin) {
        Vec3 pivot = new Vec3(this.getX(), this.getY() + NEEDLE_PIVOT_Y, this.getZ());
        Vec3 dir = targetChest(t).subtract(pivot).normalize();
        Vec3 tip = pivot.add(dir.scale(NEEDLE_LEN));
        net.minecraft.world.phys.BlockHitResult blocked = this.level().clip(new ClipContext(pivot, tip, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (blocked.getType() != HitResult.Type.MISS) tip = blocked.getLocation().subtract(dir.scale(0.2D));
        float dmg = pin ? PIN_DAMAGE : (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
        this.level().addFreshEntity(new AcumnesSpine(this.level(), this, pin ? AcumnesSpine.PIN : AcumnesSpine.SNIPE, tip,
                dir.scale(pin ? PIN_SPEED : SNIPE_SPEED), dmg, PIN_TICKS));
        this.level().playSound(null, tip.x, tip.y, tip.z, net.minecraft.sounds.SoundEvents.TRIDENT_THROW.value(), net.minecraft.sounds.SoundSource.HOSTILE,
                1.6F, pin ? 0.45F : 0.6F);
    }

    /** 散弾の i 発目(左から右へ): 針の先(Vault 5.6)から、相手への向きを扇にずらして。 */
    private void scatterShot(ServerLevel sl, LivingEntity t, int i) {
        double yaw = Math.toRadians(this.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double[] m = SCATTER_MUZZLE[i];
        Vec3 muzzle = new Vec3(this.getX() + (rx * m[0] - fx * m[2]) / 16.0D, this.getY() + m[1] / 16.0D, this.getZ() + (rz * m[0] - fz * m[2]) / 16.0D);
        Vec3 aim = t != null && t.isAlive() ? targetChest(t).subtract(muzzle) : new Vec3(fx, -0.6D, fz);
        Vec3 dir = aim.normalize().yRot((float) Math.toRadians(-SCATTER_FAN[i]));   // 正 = 右へ(yRot は反時計回り)
        dir = dir.add((this.random.nextDouble() - 0.5D) * 0.06D, (this.random.nextDouble() - 0.5D) * 0.06D, (this.random.nextDouble() - 0.5D) * 0.06D).normalize();
        sl.addFreshEntity(new AcumnesSpine(sl, this, AcumnesSpine.SCATTER, muzzle, dir.scale(SCATTER_SPEED), SCATTER_DAMAGE, 0));
        if (i == 0) sl.playSound(null, muzzle.x, muzzle.y, muzzle.z, net.minecraft.sounds.SoundEvents.TRIDENT_THROW.value(),
                net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.4F);
    }

    Vec3 chest() {
        return new Vec3(this.getX(), this.getY() + NEEDLE_PIVOT_Y, this.getZ());
    }

    static Vec3 targetChest(LivingEntity t) {
        return t.position().add(0.0D, t.getBbHeight() * 0.55D, 0.0D);
    }

    /** 狙いの線(見えない): 針の付け根から相手の胸まで、固いブロックが無いか。 */
    boolean hasShotLine(LivingEntity t) {
        return this.level().clip(new ClipContext(chest(), targetChest(t), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
                .getType() == HitResult.Type.MISS;
    }

    /** 足元から下が {@code d} ブロック空いているか(針が体の下をくぐるアニメを流してよいか)。 */
    boolean clearBelow(double d) {
        Vec3 from = this.position();
        return this.level().clip(new ClipContext(from, from.add(0.0D, -d, 0.0D), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this))
                .getType() == HitResult.Type.MISS;
    }

    /** 相手が屋根の下か(頭の上の空が見えない)。 */
    static boolean underRoof(ServerLevel sl, LivingEntity t) {
        return !sl.canSeeSky(net.minecraft.core.BlockPos.containing(t.getX(), t.getEyeY() + 0.5D, t.getZ()));
    }

    /** ここ(体の足元 x, y, z)から突っ込めば、針の先が相手の胸へまっすぐ届くか。 */
    private boolean diveLine(LivingEntity t, double x, double y, double z) {
        Vec3 from = new Vec3(x, y + DIVE_TIP_UP, z);
        Vec3 to = targetChest(t);
        return from.distanceTo(to) <= DIVE_RANGE + DIVE_TIP_FWD && this.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    /**
     * 刺突の入り口【決定 11-15 勧めのまま】: 屋根の下の相手から水平に 6〜10、針の先が相手の胸の高さに来る高さ、空が見える(屋根の外)・
     * 体が収まる・そこから相手の胸までまっすぐ通る・今の高さから真下へ降りられる所。近い順に選ぶ。無ければ null。
     */
    private double[] findDiveStart(ServerLevel sl, LivingEntity t) {
        WorldSpace space = new WorldSpace(sl, t);
        Vec3 tc = targetChest(t);
        double by = tc.y - DIVE_TIP_UP;
        double cy = Math.max(this.getY(), by);
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < 16; i++) {
            double a = this.random.nextDouble() * Math.PI * 2.0D;
            double r = DIVE_FROM_MIN + this.random.nextDouble() * (DIVE_FROM_MAX - DIVE_FROM_MIN);
            double px = t.getX() + Math.cos(a) * r, pz = t.getZ() + Math.sin(a) * r;
            if (!space.fits(px, by, pz)) continue;
            if (!sl.canSeeSky(net.minecraft.core.BlockPos.containing(px, by + this.getBbHeight() + 0.5D, pz))) continue;
            if (!diveLine(t, px, by, pz)) continue;
            if (!space.clearPath(px, cy, pz, px, by, pz) || !space.clearPath(this.getX(), this.getY(), this.getZ(), px, cy, pz)) continue;
            double d = (px - this.getX()) * (px - this.getX()) + (pz - this.getZ()) * (pz - this.getZ());
            if (d < bestD) {
                bestD = d;
                best = new double[]{px, by, pz};
            }
        }
        return best;
    }

    /** テスト用: 今すぐ技を始める(相手を決めて、待ち時間を 0 に)。 */
    public void debugStartSkill(LivingEntity t, int s, int flags) {
        this.setTarget(t);
        this.skillCooldown = 0;
        this.lastOwnTick = this.tickCount;
        this.hover.holdAt(this.getX(), this.getY(), this.getZ());
        setSkill(s, flags);
        if (s == S_AIM) this.entityData.set(DATA_AIM_TARGET, t.getId());
    }

    public int skillTicks() {
        return this.skillTicks;
    }

    private static final boolean DEV_TRACE = Boolean.getBoolean("ov.devbridge");
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /**
     * 場所を移す(段 2 の技から、撃った後に呼ぶ口)。今いる所を「使った場所」に入れ({@link #USED_MEMORY} か所まで覚える)、
     * そこから {@link DartHover.SpotRules#reuseGap} 以内ではない次の場所へ、向きを変えてから鋭く飛ぶ。選べなければ少し待って選び直す。
     */
    public void relocate() {
        rememberUsed(this.getX(), this.getY(), this.getZ());
        this.mustMove = true;
        if (this.level() instanceof ServerLevel sl) chooseNext(sl, true);
    }

    private void rememberUsed(double x, double y, double z) {
        this.used[this.usedNext * 3] = x;
        this.used[this.usedNext * 3 + 1] = y;
        this.used[this.usedNext * 3 + 2] = z;
        this.usedNext = (this.usedNext + 1) % USED_MEMORY;
        this.usedCount = Math.min(USED_MEMORY, this.usedCount + 1);
    }

    private void chooseNext(ServerLevel sl, boolean forced) {
        LivingEntity t = this.getTarget();
        double hx = this.homeX(), hz = this.homeZ();
        double[] s = DartHover.pickSpot(SPOTS, new WorldSpace(sl, t), this.random::nextDouble, this.getX(), this.getY(), this.getZ(),
                t != null, t == null ? 0.0D : t.getX(), t == null ? 0.0D : t.getY(), t == null ? 0.0D : t.getZ(), hx, hz, this.used, this.usedCount);
        if (s == null) {
            this.holdLeft = 10;   // どこも選べない: 少し待って選び直す(移らねばならないなら、そのまま覚えておく)
            // 葉の中に挟まって 3 回続けて選べないときは、真上の空いている所へ抜ける(11-16 テストクライアントで林冠に挟まったまま動けなくなった)
            if (++this.pickFails >= 3) {
                WorldSpace space = new WorldSpace(sl, t);
                double x = this.getX(), y = this.getY(), z = this.getZ();
                for (double up = 2.0D; up <= SPOTS.hopMax; up += 2.0D) {
                    if (!space.clearPath(x, y, z, x, y + up, z)) break;
                    if (space.fits(x, y + up, z) && space.clearPath(x, y + up, z, x, y + up + 3.0D, z)) {
                        this.pendX = x;
                        this.pendY = y + up;
                        this.pendZ = z;
                        this.pickFails = 0;
                        return;   // 移らねばならない印は残す(抜けた所で選び直す)
                    }
                }
            }
            return;
        }
        this.pickFails = 0;
        this.pendX = s[0];
        this.pendY = s[1];
        this.pendZ = s[2];
        this.mustMove = false;
        if (forced) this.relocations++;
    }

    private double homeX() {
        return Double.isNaN(this.homeX) ? this.getX() : this.homeX;
    }

    private double homeZ() {
        return Double.isNaN(this.homeZ) ? this.getZ() : this.homeZ;
    }

    /** 世界を {@link DartHover.Space} として見る。 */
    private final class WorldSpace implements DartHover.Space {
        private final ServerLevel sl;
        private final LivingEntity target;

        WorldSpace(ServerLevel sl, LivingEntity target) {
            this.sl = sl;
            this.target = target;
        }

        @Override
        public boolean fits(double x, double y, double z) {
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.hasChunk(this.sl, Mth.floor(x) >> 4, Mth.floor(z) >> 4)) return false;
            if (y < this.sl.getMinBuildHeight() + 1 || y + AcumnesEntity.this.getBbHeight() > this.sl.getMaxBuildHeight()) return false;
            AABB box = AcumnesEntity.this.getDimensions(AcumnesEntity.this.getPose()).makeBoundingBox(x, y, z);
            return this.sl.noCollision(AcumnesEntity.this, box) && !this.sl.containsAnyLiquid(box);
        }

        @Override
        public boolean clearPath(double x0, double y0, double z0, double x1, double y1, double z1) {
            double h = AcumnesEntity.this.getBbHeight();
            for (double oy : new double[]{0.25D, h * 0.5D, h - 0.25D}) {
                if (this.sl.clip(new ClipContext(new Vec3(x0, y0 + oy, z0), new Vec3(x1, y1 + oy, z1),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, AcumnesEntity.this)).getType() != HitResult.Type.MISS) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean canSee(double x, double y, double z) {
            if (this.target == null) return false;
            return this.sl.clip(new ClipContext(new Vec3(x, y + SIGHT_Y, z), this.target.getEyePosition(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, AcumnesEntity.this)).getType() == HitResult.Type.MISS;
        }

        @Override
        public double floorAt(double x, double z) {
            return SkyProbe.canopy(this.sl, Mth.floor(x), Mth.floor(z));
        }
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", ENTRY, this::mainState));
        controllers.add(new AnimationController<>(this, "buzz", 2, this::buzzState));
        // 向き直りの層(main の後。骨は pitch・needle_aim・翅の付け根 wing_f/h_* だけ。うなりの層の骨とは重ならない)
        controllers.add(new AnimationController<>(this, "turn", 2, this::turnState));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            this.turnRate = Mth.wrapDegrees(this.getYRot() - this.yRotO);
            int raw = this.entityData.get(DATA_SKILL);
            if (raw != this.clientSkillSeen) {
                this.clientSkillSeen = raw;
                this.clientSkillStart = this.tickCount;
            }
            this.needleDepO = this.needleDep;
            this.needleDep = computeDepression();
        }
    }

    /** クライアント: 今の技の段に入ってからの tick。 */
    private float skillAge(float partialTick) {
        return this.tickCount - this.clientSkillStart + partialTick;
    }

    /**
     * クライアント: 針の俯角(度、下向きが +)。aim の 1.35 秒(針が真正面で止まる)から 0.15 秒で相手へ寄せ、fire の 0.35 秒から 0.3 秒で 0 へ戻す
     * (aim の針は俯角 0 の前提で動くので、それより前に入れると溜めの間に針が跳ね上がる。旧版と同じ)。
     */
    private float computeDepression() {
        int sk = skill();
        float w;
        if (sk == S_AIM) {
            w = smoothstep(((skillAge(0.0F) - ENTRY) / 20.0F - 1.35F) / 0.15F);
        } else if (sk == S_FIRE) {
            w = 1.0F - smoothstep((skillAge(0.0F) / 20.0F - 0.35F) / 0.3F);
        } else {
            return this.needleDep * 0.6F;
        }
        net.minecraft.world.entity.Entity e = this.level().getEntity(this.entityData.get(DATA_AIM_TARGET));
        if (!(e instanceof LivingEntity t)) return this.needleDep * w;
        Vec3 pivot = chest();
        Vec3 aim = targetChest(t);
        double h = Math.sqrt((aim.x - pivot.x) * (aim.x - pivot.x) + (aim.z - pivot.z) * (aim.z - pivot.z));
        float dep = (float) (Mth.atan2(pivot.y - aim.y, h) * Mth.RAD_TO_DEG);
        return Mth.clamp(dep, 0.0F, MAX_DEPRESSION) * w;
    }

    /** クライアント: 描画用の針の俯角(度)。 */
    public float needleDepression(float partialTick) {
        return Mth.lerp(partialTick, this.needleDepO, this.needleDep);
    }

    /**
     * クライアント: 縫い止めの溜めの間の針の先の光の強さ(光の箱の大きさに掛ける)【仮・決定 11-15「光を強める・速く脈打たせる」】。
     * 針の先が光り始める aim の 1.35 秒から、1.35 倍を中心に 0.3 秒の周期で脈打つ。狙撃・ほかの技は 1。
     */
    public float pinGlow(float partialTick) {
        if (!skillPin() || skill() != S_AIM) return 1.0F;
        float t = (skillAge(partialTick) - ENTRY) / 20.0F;
        if (t < 1.35F) return 1.0F;
        float in = smoothstep((t - 1.35F) / 0.2F);
        return 1.0F + in * (0.35F + 0.2F * Mth.sin(t * (float) (Math.PI * 2.0D / 0.3D)));
    }

    static float smoothstep(float u) {
        float x = Mth.clamp(u, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    /**
     * つなぎを選ぶ(Vault 5.7。作りはヴェロフィルと同じ): 入りの補間は流し始めにだけ使い、過ぎたら 0 にする(つないだ段の間は差 0。
     * GeckoLib は段の間でも補間の長さを使うので)。
     * <ul>
     *   <li>止まる = perch_idle。降りてきたら止まった tick に land_recover → perch_idle。セーブから止まった姿で読み込んだ個体は直に perch_idle。</li>
     *   <li>飛び立つ = その場で takeoff_windup → 昇り始めに takeoff → idle。降りている途中からは溜めずに takeoff。</li>
     *   <li>降りる = land_windup → land(繰り返し)。止まり木へ寄る間は fly。</li>
     *   <li>空中で移る = 溜めずに dart → fly → 止まった tick に dart_recover → idle(相手がいれば hover)【決定 11-15: 溜めは枝から飛び立つときだけ】。</li>
     * </ul>
     */
    private PlayState mainState(AnimationState<AcumnesEntity> state) {
        if (this.isDeadOrDying()) {
            // 狙っている・撃った直後に死ねば、腹を巻いたまま横倒し(death_aim)
            boolean aiming = this.curAnim == AIM || this.curAnim == AIM_V || this.curAnim == FIRE || this.curAnim == DEATH_AIM;
            this.curAnim = aiming ? DEATH_AIM : DEATH;
            state.getController().transitionLength(ENTRY);
            return state.setAndContinue(this.curAnim);
        }
        int m = mode();
        RawAnimation cur = this.curAnim;
        int age = this.tickCount - this.curStart;
        RawAnimation want;
        int entry;
        int sk = skill();
        if (sk != S_NONE && m == MODE_FLY) {
            want = skillAnim(sk, skillVertical());
            // 差 0 でつながる段は補間 0。前触れ(aim・dive_windup・scatter_windup)と取りやめだけ、浮いた姿の途中から短く補間
            entry = want == cur ? this.curEntry
                    : sk == S_AIM ? ENTRY : sk == S_DIVE_WINDUP || sk == S_SCATTER_WINDUP ? ENTRY_SHORT : sk == S_CANCEL ? 2 : 0;
        } else if (m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() == 0) {
            if (cur == LAND || cur == LAND_RECOVER) {
                want = LAND_RECOVER;   // 止まった tick に掴む(land の途中から入るので短く補間)
                entry = cur == LAND ? ENTRY_SHORT : this.curEntry;
            } else {
                want = PERCH_IDLE;
                entry = ENTRY;
            }
        } else if (m == MODE_TAKEOFF && takeoffPhase() == 1) {
            want = WINDUP;
            entry = cur == WINDUP ? this.curEntry : ENTRY_SHORT;
        } else if (m == MODE_TAKEOFF) {
            want = TAKEOFF;
            entry = cur == WINDUP ? 0 : (cur == TAKEOFF ? this.curEntry : ENTRY);   // 溜めの終わり = takeoff の 0 秒(差 0)
        } else if (m == MODE_LANDING) {
            want = LAND;
            entry = cur == LAND ? this.curEntry : ENTRY;
        } else {
            int mo = motion();
            RawAnimation loop = mo == MOTION_AIM ? HOVER : IDLE;
            if (mo == MOTION_APPROACH) {
                want = FLY;
                entry = cur == FLY ? this.curEntry : ENTRY;
            } else if (mo == MOTION_DART) {
                want = DART_FLY;
                entry = cur == DART_FLY ? this.curEntry : DART_ENTRY;
            } else if (cur == DART_FLY) {
                want = mo == MOTION_AIM ? RECOVER_HOVER : RECOVER_IDLE;   // 着いて止まった tick に(fly → dart_recover は差 0)
                entry = 0;
            } else if ((cur == RECOVER_IDLE || cur == RECOVER_HOVER) && (age < DART_RECOVER_ANIM || (cur == RECOVER_IDLE) == (loop == IDLE))) {
                want = cur;   // 揺れ戻しは最後まで。その後は繰り返しの方が合っていればそのまま
                entry = this.curEntry;
            } else if ((cur == DIVE_END || cur == SCATTER_RECOVER) && (age < (cur == DIVE_END ? DIVE_END_TICKS : SCATTER_RECOVER_TICKS) || loop == IDLE)) {
                want = cur;   // 技の後の解き・揺れ戻し(→ idle@0)は最後まで
                entry = this.curEntry;
            } else if (cur == TAKEOFF && (age < TAKEOFF_ANIM || loop == IDLE)) {
                want = cur;   // takeoff の後はそのまま idle を繰り返している
                entry = this.curEntry;
            } else {
                want = loop;
                entry = cur == loop ? this.curEntry : ENTRY;
            }
        }
        if (want != cur) {
            if (DEV_TRACE && this.hasCustomName() && this.getCustomName().getString().contains("trace")) {
                LOG.info("ACUMNES_ANIM {} t {} {} -> {} 補間 {} 動き {} 段 {}", this.getId(), this.tickCount, animName(cur), animName(want), entry, m, motion());
            }
            this.curAnim = want;
            this.curStart = this.tickCount;
            this.curEntry = entry;
            age = 0;
        }
        boolean chained = want == TAKEOFF || want == LAND || want == LAND_RECOVER || want == WINDUP
                || want == DART_FLY || want == RECOVER_IDLE || want == RECOVER_HOVER || sk != S_NONE && m == MODE_FLY
                || want == DIVE_END || want == SCATTER_RECOVER;
        state.getController().transitionLength(chained && age > this.curEntry ? 0 : this.curEntry);
        return state.setAndContinue(want);
    }

    /**
     * 翅のうなり(別の層)。止まっている間・飛び立ちの待ちと溜めの間・死ぬときは止める。昇り始める tick(takeoff)から入れ、
     * 降りて止まった tick(land_recover の 0 tick)に止める。
     */
    private PlayState buzzState(AnimationState<AcumnesEntity> state) {
        int m = mode();
        if (this.isDeadOrDying() || m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() < 2) return PlayState.STOP;
        return state.setAndContinue(BUZZ);
    }

    /**
     * 向き直りの層(Vault 5.7 の 5): 浮いて止まっている間(main が idle・hover・dart_recover)に向きが回っている間 turn_l か turn_r
     * (終わりで止まる)、回り終えたら turn_*_recover(9 tick)を流して止める。1 tick に {@link #TURN_LAYER_MIN} 度より小さい回りでは出さない。
     * 左 = yaw が減る向き(ゲーム内で逆に見えたら入れ替える。未確認)。
     * 飛ぶ前の向き直りが終わるとすぐ dart で飛び出すので、揺れ戻しは dart の 5 tick の間だけ続け、fly へ入る所で止める
     * (止める tick に翅の付け根などが main の値へ跳ぶ。ゲーム内で目立つならアニメの側で直す = モデル制作へ)。
     */
    /** 技の段 → アニメ(Vault 5.3。狙撃・縫い止めは r8 の aim 系、刺突・散弾は段 2 のアニメ)。 */
    private static RawAnimation skillAnim(int sk, boolean v) {
        return switch (sk) {
            case S_AIM -> v ? AIM_V : AIM;
            case S_FIRE -> FIRE;
            case S_RELOAD -> v ? RELOAD_V : RELOAD;
            case S_CANCEL -> v ? CANCEL_V : CANCEL;
            case S_DIVE_WINDUP -> DIVE_WINDUP;
            case S_DIVE -> DIVE;
            case S_DIVE_HIT -> DIVE_HIT_RECOVER;
            case S_DIVE_MISS -> DIVE_MISS_RECOVER;
            case S_DIVE_AWAY -> DIVE_AWAY;
            case S_DIVE_END -> DIVE_END;
            case S_SCATTER_WINDUP -> SCATTER_WINDUP;
            case S_SCATTER -> SCATTER;
            case S_SCATTER_RECOVER -> SCATTER_RECOVER;
            default -> IDLE;
        };
    }

    private static String animName(RawAnimation a) {
        if (a == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (RawAnimation.Stage s : a.getAnimationStages()) {
            if (sb.length() > 0) sb.append('>');
            sb.append(s.animationName());
        }
        return sb.toString();
    }

    private PlayState turnState(AnimationState<AcumnesEntity> state) {
        int m = mode();
        RawAnimation cur = this.curAnim;
        boolean hovering = m == MODE_FLY && !this.isDeadOrDying()
                && (cur == IDLE || cur == HOVER || cur == RECOVER_IDLE || cur == RECOVER_HOVER || cur == TAKEOFF && this.tickCount - this.curStart >= TAKEOFF_ANIM);
        boolean darting = m == MODE_FLY && cur == DART_FLY && this.tickCount - this.curStart < DART_ANIM;
        if (!hovering && !darting) {
            this.turnDir = 0;
            this.turnRecStart = -1;
            return PlayState.STOP;
        }
        float r = this.turnRate;
        if (hovering && Math.abs(r) >= TURN_LAYER_MIN) {
            int d = r < 0.0F ? -1 : 1;
            if (d != this.turnDir && DEV_TRACE && this.hasCustomName() && this.getCustomName().getString().contains("trace")) {
                LOG.info("ACUMNES_TURN {} t {} {} 回り {}", this.getId(), this.tickCount, d < 0 ? "turn_l" : "turn_r", String.format("%.1f", r));
            }
            this.turnDir = d;
            this.turnRecStart = -1;
            return state.setAndContinue(this.turnDir < 0 ? TURN_L : TURN_R);
        }
        if (this.turnDir == 0) return PlayState.STOP;
        if (this.turnRecStart < 0) this.turnRecStart = this.tickCount;
        if (this.tickCount - this.turnRecStart >= TURN_RECOVER_ANIM) {
            this.turnDir = 0;
            this.turnRecStart = -1;
            return PlayState.STOP;
        }
        return state.setAndContinue(this.turnDir < 0 ? TURN_L_RECOVER : TURN_R_RECOVER);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    /**
     * 全長 10.6(頭の前 3・針の先が後ろ 7.6)・翅の幅 5.5。止まった姿(案 A)は針の先が足元 +7.35、
     * dive_windup などで針が足元の 4.9 下まで下がる(Vault 5.6・5.7 の 11)ので、上 +7.5・下 −5。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        return new AABB(this.getX() - 8.0D, this.getY() - 5.0D, this.getZ() - 8.0D, this.getX() + 8.0D, this.getY() + 7.5D, this.getZ() + 8.0D);
    }

    // ------------------------------------------------------------------ テスト・計測

    public DartHover hover() {
        return this.hover;
    }

    /** テスト用: 今いる所で止まる。 */
    public void debugHoldHere(int ticks) {
        this.hover.holdAt(this.getX(), this.getY(), this.getZ());
        this.lastPhase = DartHover.Phase.HOLD;
        this.holdLeft = ticks;
        this.pendX = Double.NaN;
        this.lastOwnTick = this.tickCount;
    }

    public int usedCount() {
        return this.usedCount;
    }

    public boolean mustMove() {
        return this.mustMove;
    }

    /**
     * 死に際のアニメ death(1.5 秒)を最後まで流し、その後に膨らんで破裂する(11-15 ユーザー決定「アニメの後に破裂」)。
     * バニラの 20 tick では消さない。消すのは {@code corpse/PlantSideBurst}、ここは破裂が外されたときの控え。
     */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.deathTime >= com.yayuy.overgrownvegetation.spore.PlantSideRules.burstTick(this) + 2 && !this.level().isClientSide() && !this.isRemoved()) {
            this.level().broadcastEntityEvent(this, (byte) 60);
            this.remove(RemovalReason.KILLED);
        }
    }
}
