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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * メガネウラ {@code meganeura}(G3・原生消費種。大きなトンボ・狩る側)。Vault「Mob の能力/メガネウラ」。
 *
 * <h2>段 1(2026-11-16): 飛ぶ・止まる・急ぐまで</h2>
 * 動きは「飛ぶ型」の土台 {@link SkyFlyer} に、トンボの飛び方を自前の飛び方({@link #ownFlight})で載せる。
 * 【案・Claude 11-16(全体管理「トンボらしく、低めで速く、急に向きを変えて飛ぶ」)】3 つの動きを行き来する。数値はどれも【仮】:
 * <ul>
 *   <li>{@link #MOOD_CRUISE 巡航}: 林冠のすぐ上(+{@link #LAYER_MIN}〜{@link #LAYER_MAX})を速く飛び、{@link #TURN_EVERY_MIN}〜{@link #TURN_EVERY_MAX} tick ごとに
 *       急に向きを変える(±40〜150 度。小回りの利く {@link FlightBody} で、巡航で半径 約 2 ブロック)。家から {@link #HOME_R} より離れたら家の方へ。</li>
 *   <li>{@link #MOOD_HOVER 止まっては飛ぶ}: 空中でピタッと止まり、{@link #HOP_MIN}〜{@link #HOP_MAX} ブロック先へ鋭く飛んでまた止まる({@link DartHover}、アクムネスの部品)を 2〜4 回。</li>
 *   <li>{@link #MOOD_DASH 急ぐ}: 速さ {@link #DASH_SPEED} でまっすぐ。叩かれたら叩いた相手から離れる向きへ、飛び立った直後と巡航のたまにも。</li>
 * </ul>
 * 夜と雨は木のてっぺんに止まる(土台のまま。1 匹ずつ = 群れを作らない)。風はあまり受けない(強く飛ぶ虫)。
 * G2・G3 は生息しているだけの生き物(胞子を運ばない・亜種なし)。植物側({@link ParasitizedMob}・タグ group_3 = ほかの Mob から敵対される)。
 * 自然には湧かない(生態系の台帳 {@code ecosystem/EcoSpawns} へ後でつなぐ)。狩り(chase・bite・chew)は後の段。
 * 形は 9 月製のまま、絵は MOD の色に塗り直し(翅の黄色は残す。ユーザー決定 11-16)。正本 {@code デスクトップ\blockbench\mobs\meganeura.bbmodel}。
 */
public class MeganeuraEntity extends SkyFlyer implements GeoEntity, ParasitizedMob {

    public static final String ID = "meganeura";

    /** 小回りの利く飛び方【仮・Claude 11-16】。巡航 0.5(秒速 10)・急ぐ 1.0・巡航で 14 度/tick(半径 約 2)。 */
    private static final FlightBody.Params PARAMS = new FlightBody.Params();

    static {
        PARAMS.cruise = 0.5D;
        PARAMS.minSpeed = 0.15D;
        PARAMS.maxSpeed = 1.05D;
        PARAMS.accel = 0.06D;
        PARAMS.decel = 0.08D;
        PARAMS.maxTurnDeg = 14.0D;
        PARAMS.maxTurnCapDeg = 24.0D;
        PARAMS.turnAccelDeg = 5.0D;
        PARAMS.turnGain = 0.5D;
        PARAMS.climbMax = 0.25D;
        PARAMS.sinkMax = 0.2D;
        PARAMS.vyAccel = 0.04D;
        PARAMS.climbGain = 0.15D;
        PARAMS.stallLoss = 0.05D;
        PARAMS.climbCost = 0.02D;
        PARAMS.windFactor = 0.3D;
    }

    /** 止まっては飛ぶ【仮】: 鋭く飛ぶ速さ 0.9・ピタッと止まる。 */
    private static final DartHover.Params DART = new DartHover.Params();

    static {
        DART.dartMax = 0.9D;
        DART.dartAccel = 0.25D;
        DART.dartBrake = 0.25D;
        DART.stopDecel = 0.35D;
        DART.windFactor = 0.2D;
    }

    /** 林冠からの高さ(ブロック)【仮】。トンボは低め(梢のすぐ上)。 */
    public static final double LAYER_MIN = 1.5D, LAYER_MAX = 4.0D;
    /** 家(湧いた所)からの半径(ブロック)【仮】。 */
    public static final double HOME_R = 40.0D;
    /** 巡航で向きを変える間隔(tick)・変える角度(度)【仮】。 */
    public static final int TURN_EVERY_MIN = 15, TURN_EVERY_MAX = 45;
    public static final double TURN_MIN = 40.0D, TURN_MAX = 150.0D;
    /** 巡航の長さ(tick)・その後に止まっては飛ぶに入る割合【仮】。 */
    public static final int CRUISE_MIN = 80, CRUISE_MAX = 200;
    public static final double HOVER_CHANCE = 0.6D;
    /** 止まっては飛ぶ: 止まる長さ(tick)・跳ぶ距離(ブロック)・回数【仮】。 */
    public static final int HOLD_MIN = 15, HOLD_MAX = 45;
    public static final double HOP_MIN = 4.0D, HOP_MAX = 10.0D;
    public static final int HOPS_MIN = 2, HOPS_MAX = 4;
    /** 急ぐ: 速さ(ブロック/tick)・長さ(tick)・巡航中に始める割合(1 tick あたり)【仮】。 */
    public static final double DASH_SPEED = 1.0D;
    public static final int DASH_TICKS = 30;
    public static final double DASH_CHANCE = 0.004D;

    public static final int MOOD_CRUISE = 0, MOOD_HOVER = 1, MOOD_DASH = 2;
    private static final EntityDataAccessor<Byte> DATA_MOOD = SynchedEntityData.defineId(MeganeuraEntity.class, EntityDataSerializers.BYTE);

    /** 叩ける部位は無い(当たりは胴の芯だけ。翅は厚み 0 の板)。 */
    static final String[] PARTS = {};
    static final float[] PART_W = {};
    static final float[] PART_H = {};
    static final double[] PART_CENTERS = {};

    // 段 2 のアニメ(11-17 meganeura-anim。Vault メガネウラ 5 節): 差 0 でつながる段の間は補間 0、途中から入る所だけ ENTRY_SHORT〜ENTRY
    private static final RawAnimation FLY = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation CHASE = RawAnimation.begin().thenLoop("chase");
    /** 空中で止まる(体を 12 度起こす。止まっている間はコードの機首の上下が 0 なので二重にならない)。 */
    private static final RawAnimation HOVER = RawAnimation.begin().thenLoop("hover");
    private static final RawAnimation REST = RawAnimation.begin().thenLoop("rest");
    private static final RawAnimation WINDUP = RawAnimation.begin().thenPlayAndHold("takeoff_windup");
    private static final RawAnimation TAKEOFF = RawAnimation.begin().thenPlay("takeoff").thenLoop("fly");
    private static final RawAnimation LAND = RawAnimation.begin().thenPlay("land_windup").thenLoop("land");
    private static final RawAnimation LAND_RECOVER = RawAnimation.begin().thenPlay("land_recover").thenLoop("rest");
    /** 補間(tick): 途中から入る所 / 速さの違う繰り返しどうし。 */
    static final int ENTRY_SHORT = 3, ENTRY = 4;
    /** 飛び立ちの溜め(takeoff_windup 3 tick + 入りの補間)。【決定 11-17 ユーザー】自分から飛び立つときだけ溜め、驚いたときは溜めない。 */
    public static final int WINDUP_TICKS = 3 + ENTRY_SHORT;
    /** 送る動き: 止まっては飛ぶの「飛んでいる」間(サーバーの動きは {@link #MOOD_HOVER} のまま)。 */
    public static final int MOOD_HOP = 3;

    /** クライアント: 今流しているつなぎ・流し始めた tick・入りの補間。 */
    private RawAnimation curAnim;
    private int curStart, curEntry;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // --- 自前の飛び方(サーバー) ---
    private final FlightBody agile = new FlightBody();
    private final DartHover dart = new DartHover();
    private int mood = MOOD_CRUISE, moodTicks, moodLen, turnTimer, hopsLeft, holdLen;
    private double wantHeading = Double.NaN, myLayer, homeX = Double.NaN, homeZ, canopyY = Double.NaN;
    private double dashHeading;
    private boolean wasFlying;
    private final double[] wind = new double[2];

    public MeganeuraEntity(EntityType<? extends MeganeuraEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 8);
        this.myLayer = Mth.lerp(this.random.nextDouble(), LAYER_MIN, LAYER_MAX);
        this.moodLen = CRUISE_MIN + this.random.nextInt(CRUISE_MAX - CRUISE_MIN);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    /** 計測・テスト用: 飛び方の値(書き換えない)。 */
    public static FlightBody.Params flightParams() {
        return PARAMS;
    }

    /** 計測・テスト用: 止まっては飛ぶの値(書き換えない)。 */
    public static DartHover.Params dartParams() {
        return DART;
    }

    @Override
    protected FlightBody.Params params() {
        return PARAMS;
    }

    @Override
    protected double layerMin() {
        return LAYER_MIN;
    }

    @Override
    protected double layerMax() {
        return LAYER_MAX;
    }

    /** 群れを作らない(1 匹ずつ)。 */
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
        return PART_CENTERS;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MOOD, (byte) MOOD_CRUISE);
    }

    /** 今の動き(描画も読む)。 */
    public int mood() {
        return this.level().isClientSide ? this.entityData.get(DATA_MOOD) : this.mood;
    }

    private void setMood(int m, int len) {
        this.mood = m;
        this.moodTicks = 0;
        this.moodLen = len;
        this.entityData.set(DATA_MOOD, (byte) m);
    }

    // ------------------------------------------------------------------ 飛び方

    @Override
    protected boolean ownFlightPitches() {
        return true;
    }

    @Override
    protected boolean ownFlight(ServerLevel sl, Flock f) {
        if (Double.isNaN(this.homeX)) {
            this.homeX = this.getX();
            this.homeZ = this.getZ();
        }
        if (!this.wasFlying) {
            // 飛び立った直後(朝・驚いた): 急いで離れる。土台の飛び方の向きと速さを引き継ぐ
            this.wasFlying = true;
            this.agile.heading = this.getYRot();
            this.agile.speed = Math.max(this.body.speed, PARAMS.minSpeed);
            startDash(this.getYRot());
        }
        if (this.tickCount % 4 == 0 || Double.isNaN(this.canopyY)) {
            double c = SkyProbe.canopy(sl, Mth.floor(this.getX()), Mth.floor(this.getZ()));
            if (!Double.isNaN(c)) this.canopyY = c;
        }
        double floorY = Double.isNaN(this.canopyY) ? this.getY() : this.canopyY;
        double wantY = floorY + this.myLayer;
        FlyerWind.now(sl, this.wind);
        this.moodTicks++;
        switch (this.mood) {
            case MOOD_HOVER -> tickHover(sl, wantY);
            case MOOD_DASH -> tickAgile(sl, this.dashHeading, DASH_SPEED, wantY);
            default -> tickCruise(sl, wantY);
        }
        if (this.mood == MOOD_DASH && this.moodTicks >= this.moodLen) {
            setMood(MOOD_CRUISE, CRUISE_MIN + this.random.nextInt(CRUISE_MAX - CRUISE_MIN));
        }
        return true;
    }

    private void tickCruise(ServerLevel sl, double wantY) {
        if (--this.turnTimer <= 0 || Double.isNaN(this.wantHeading)) {
            double hx = this.homeX - this.getX(), hz = this.homeZ - this.getZ();
            if (hx * hx + hz * hz > HOME_R * HOME_R) {
                this.wantHeading = FlightBody.yawOf(hx, hz) + (this.random.nextDouble() * 2.0D - 1.0D) * 30.0D;
            } else {
                double turn = Mth.lerp(this.random.nextDouble(), TURN_MIN, TURN_MAX) * (this.random.nextBoolean() ? 1.0D : -1.0D);
                this.wantHeading = this.agile.heading + turn;
            }
            this.turnTimer = TURN_EVERY_MIN + this.random.nextInt(TURN_EVERY_MAX - TURN_EVERY_MIN);
        }
        tickAgile(sl, this.wantHeading, PARAMS.cruise, wantY);
        if (this.moodTicks >= this.moodLen) {
            if (this.random.nextDouble() < HOVER_CHANCE) {
                this.hopsLeft = HOPS_MIN + this.random.nextInt(HOPS_MAX - HOPS_MIN + 1);
                this.dart.vx = this.agile.vx;
                this.dart.vy = this.agile.vy;
                this.dart.vz = this.agile.vz;
                this.dart.holdAt(this.getX(), this.getY(), this.getZ());
                this.holdLen = HOLD_MIN + this.random.nextInt(HOLD_MAX - HOLD_MIN);
                setMood(MOOD_HOVER, 0);
            } else {
                setMood(MOOD_CRUISE, CRUISE_MIN + this.random.nextInt(CRUISE_MAX - CRUISE_MIN));
            }
        } else if (this.random.nextDouble() < DASH_CHANCE) {
            startDash(this.agile.heading + (this.random.nextDouble() * 2.0D - 1.0D) * 60.0D);
        }
    }

    /** 小回りの利く惰性の飛び方で 1 tick(巡航・急ぐ)。前が塞がっていれば横へ逃げて昇る。 */
    private void tickAgile(ServerLevel sl, double heading, double speed, double wantY) {
        if (this.tickCount % 3 == 0 || this.horizontalCollision) {
            double y = this.getY() + 0.25D;
            if (SkyProbe.blockedWithin(sl, this.getX(), y, this.getZ(), this.agile.heading, 4.0D) != Double.POSITIVE_INFINITY || this.horizontalCollision) {
                double l = SkyProbe.blockedWithin(sl, this.getX(), y, this.getZ(), this.agile.heading + 90.0D, 6.0D);
                double r = SkyProbe.blockedWithin(sl, this.getX(), y, this.getZ(), this.agile.heading - 90.0D, 6.0D);
                this.wantHeading = this.agile.heading + (l >= r ? 110.0D : -110.0D);
                heading = this.wantHeading;
                wantY += 2.0D;
                this.turnTimer = Math.max(this.turnTimer, 10);
            }
        }
        this.agile.step(PARAMS, heading, speed, wantY - this.getY(), this.wind[0], this.wind[1]);
        setOwnMotion(this.agile.vx, this.agile.vy, this.agile.vz);
        turnToward(this.agile.heading, 360.0F);
    }

    private void tickHover(ServerLevel sl, double wantY) {
        boolean blocked = this.horizontalCollision || this.verticalCollision;
        this.dart.step(DART, this.getX(), this.getY(), this.getZ(), this.wind[0], this.wind[1], blocked);
        setOwnMotion(this.dart.vx, this.dart.vy, this.dart.vz);
        byte show = (byte) (this.dart.holding() ? MOOD_HOVER : MOOD_HOP);
        if (this.entityData.get(DATA_MOOD) != show) this.entityData.set(DATA_MOOD, show);
        if (!this.dart.holding()) {
            double dx = this.dart.tx - this.getX(), dz = this.dart.tz - this.getZ();
            if (dx * dx + dz * dz > 0.04D) turnToward(FlightBody.yawOf(dx, dz), 45.0F);
            return;
        }
        if (this.dart.settled() && this.dart.phaseTicks >= this.holdLen) {
            if (this.hopsLeft-- <= 0) {
                this.agile.heading = this.getYRot();
                this.agile.speed = PARAMS.minSpeed;
                this.agile.vy = 0.0D;
                this.agile.turnRate = 0.0D;
                setMood(MOOD_CRUISE, CRUISE_MIN + this.random.nextInt(CRUISE_MAX - CRUISE_MIN));
                return;
            }
            // 次の止まり点: 4〜10 ブロック先(家から離れていれば家の方)、林冠からの高さは同じ層
            double a = Math.toRadians(this.getYRot() + (this.random.nextDouble() * 2.0D - 1.0D) * 120.0D);
            double hx = this.homeX - this.getX(), hz = this.homeZ - this.getZ();
            if (hx * hx + hz * hz > HOME_R * HOME_R) a = Math.toRadians(FlightBody.yawOf(hx, hz));
            double d = Mth.lerp(this.random.nextDouble(), HOP_MIN, HOP_MAX);
            double nx = this.getX() - Math.sin(a) * d, nz = this.getZ() + Math.cos(a) * d;
            double c = SkyProbe.canopy(sl, Mth.floor(nx), Mth.floor(nz));
            double ny = Double.isNaN(c) ? wantY : c + this.myLayer;
            if (SkyProbe.blockedWithin(sl, this.getX(), this.getY() + 0.25D, this.getZ(), Math.toDegrees(a) , d) != Double.POSITIVE_INFINITY) {
                ny = Math.max(ny, this.getY() + 2.0D);
            }
            this.dart.dartTo(nx, ny, nz);
            this.holdLen = HOLD_MIN + this.random.nextInt(HOLD_MAX - HOLD_MIN);
        } else {
            // 止まっている間は少しずつ向きを変えて見回す
            if (this.dart.phaseTicks % 10 == 0) this.wantHeading = this.getYRot() + (this.random.nextDouble() * 2.0D - 1.0D) * 40.0D;
            if (!Double.isNaN(this.wantHeading)) turnToward(this.wantHeading, 4.0F);
        }
    }

    private void startDash(double heading) {
        this.dashHeading = heading;
        setMood(MOOD_DASH, DASH_TICKS);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && mode() != MODE_FLY) {
            this.wasFlying = false;
            if (this.mood != MOOD_CRUISE) setMood(MOOD_CRUISE, CRUISE_MIN);
        }
    }

    /** 叩かれたら叩いた相手から離れる向きへ急ぐ。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hit = super.hurt(source, amount);
        if (hit && !this.level().isClientSide && mode() == MODE_FLY) {
            Entity e = source.getEntity();
            double h = e != null ? FlightBody.yawOf(this.getX() - e.getX(), this.getZ() - e.getZ()) : this.getYRot() + 180.0D;
            this.agile.heading = this.getYRot();
            this.agile.speed = Math.max(this.agile.speed, PARAMS.cruise);
            startDash(h);
        }
        return hit;
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, this::mainState));
    }

    @Override
    protected int takeoffWindupTicks() {
        return WINDUP_TICKS;
    }

    /** 驚いて飛び立つときは溜めない(rest → takeoff を補間 3 tick)【決定 11-17 ユーザー】。 */
    @Override
    protected int startledWindupTicks() {
        return 0;
    }

    /**
     * つなぎを選ぶ。止まる = rest(降りてきたら land_recover → rest)/ 飛び立つ = (自分からなら takeoff_windup →)takeoff → fly /
     * 降りる = land_windup → land / 空中で止まる = hover・鋭く飛ぶ = fly / 急ぐ = chase / 巡航 = fly。
     * 入りの補間は流し始めにだけ使い、過ぎたら 0 にする(つないだ段の間は差 0。GeckoLib は段の間でも補間の長さを使う。ヴェロフィルと同じ作り)。
     */
    private PlayState mainState(AnimationState<MeganeuraEntity> state) {
        int m = mode();
        int md = mood();
        RawAnimation want;
        int entry;
        if (m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() == 0) {
            if (this.curAnim == LAND || this.curAnim == LAND_RECOVER) {
                want = LAND_RECOVER;   // 止まった tick に掴む(降りる繰り返しの途中から入る)
                entry = this.curAnim == LAND ? ENTRY_SHORT : this.curEntry;
            } else {
                want = REST;   // セーブから止まった姿で読み込んだ個体は直に
                entry = ENTRY;
            }
        } else if (m == MODE_TAKEOFF && takeoffPhase() == 1) {
            want = WINDUP;
            entry = this.curAnim == WINDUP ? this.curEntry : ENTRY_SHORT;
        } else if (m == MODE_TAKEOFF) {
            want = TAKEOFF;
            // 溜めの終わり = takeoff の 0 秒(差 0)/ 驚いて rest から直に(補間 3)/ 降りている途中から(補間 4)
            entry = this.curAnim == TAKEOFF ? this.curEntry : this.curAnim == WINDUP ? 0 : this.curAnim == LAND ? ENTRY : ENTRY_SHORT;
        } else if (m == MODE_LANDING) {
            want = LAND;
            entry = this.curAnim == LAND ? this.curEntry : ENTRY_SHORT;
        } else if (md == MOOD_DASH) {
            want = CHASE;
            entry = ENTRY;
        } else if (md == MOOD_HOVER) {
            want = HOVER;
            entry = ENTRY_SHORT;
        } else if (md == MOOD_HOP) {
            want = FLY;
            entry = ENTRY_SHORT;
        } else if (this.curAnim == TAKEOFF) {
            want = TAKEOFF;   // takeoff の後はそのまま fly を繰り返している
            entry = this.curEntry;
        } else {
            want = FLY;
            entry = ENTRY;
        }
        if (want != this.curAnim) {
            this.curAnim = want;
            this.curStart = this.tickCount;
            this.curEntry = entry;
        }
        boolean chained = want == TAKEOFF || want == LAND || want == LAND_RECOVER || want == WINDUP;
        state.getController().transitionLength(chained && this.tickCount - this.curStart > this.curEntry ? 0 : this.curEntry);
        return state.setAndContinue(want);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
