package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.flyer.FlightBody;
import com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.entity.flyer.FlyerWind;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
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
 * セミナリア {@code seminaria}(G4・空の斥候兼種まき。和名の元はフリマキ)。Vault「Mob の能力/セミナリア」。
 *
 * <h2>段 1(2026-11-15): 飛ぶ・ゆるい群れ・林冠に止まって休むまで</h2>
 * 動きは「飛ぶ型」の土台 {@link SkyFlyer}。【決定 11-14】風に半分任せる: 羽ばたいて向きは変えられるが、風下へは速く、風上へはほとんど進めない。
 * ひらひら不規則に揺れる。数体で飛ぶが V 字は組まない(ゆるくまとまる。{@code LooseFormation})。夜と雨は林冠のてっぺんに止まる。
 * 種まき(飛び地)とプレイヤーから逃げるのは次の段。自然には湧かない。
 * 形は練習モデル フリマキ r7 {@code prac2_g4_drifter_r7}(正本 {@code デスクトップ\blockbench\mobs\seminaria.bbmodel})。描画倍率 2。
 * <h2>段 2 のアニメのつなぎ(2026-11-15。アニメはモデル制作の持ち場で作ったもの、Vault 6.3・6.6。ヴェロフィルと同じ作り)</h2>
 * 止まる = perch_idle / 飛び立つ = その場で takeoff_windup → 昇り始めに takeoff → fly / 降りる = land_windup → land(繰り返し)→
 * 止まった tick に land_recover → perch_idle。飛ぶ間は fly(羽ばたき)と idle(翅を広げて漂う)。
 * 【決定 11-15 全体管理・ユーザー】止まっている群れは身構えない。10 ブロック【仮】まで来ると一斉に飛び立つ。驚いたときも溜め 7 tick。
 * 風に揉まれる層(翅のひねり・体と冠毛の揺れ)は飛ぶ間だけ描画が足す({@code SeminariaModel}。強さは {@link #gust})。
 * <h2>能力(2026-11-16。Vault「G4〜G6 の能力の決定(11-14)」セミナリアの節・「セミナリア」末尾)</h2>
 * 種まき: 群れの先頭が前線の風下の先の升を選んで向かい、着いたら全員で舞い降りて(descend)、浮いたまま前触れ(sow_windup)→ 落とす(sow。3 tick 目に種
 * {@link SeminariaSeed})→ 隙(sow_recover)で昇り直す。落とす間だけ筋が光る({@link #sowGlow})。攻撃しない。飛んでいる間もプレイヤーが近いと
 * 群れで逃げる(flee。風下へ寄せて上限まで羽ばたく)。射落とせば種は落ちない。
 */
public class SeminariaEntity extends SkyFlyer implements GeoEntity, ParasitizedMob {

    public static final String ID = "seminaria";

    /**
     * 飛び方【仮・Claude】。巡航 0.3(ヴェロフィル 0.7 の半分弱)・風の倍率 2.5(強さ 1 の風 = 0.25 ブロック/tick)で、
     * 巡航のとき風下 0.55・風上 0.05(ほとんど進めない)。上限 0.45 は強めた風(上限 1.5 → 0.375)より速い = 家・止まり木へは必ず戻れる。
     * 軽いので小回りが利く(巡航で 6 度/tick、半径 約 3)。
     */
    private static final FlightBody.Params PARAMS = new FlightBody.Params();

    static {
        PARAMS.cruise = 0.3D;
        PARAMS.minSpeed = 0.1D;
        PARAMS.maxSpeed = 0.45D;
        PARAMS.accel = 0.02D;
        PARAMS.decel = 0.03D;
        PARAMS.maxTurnDeg = 6.0D;
        PARAMS.maxTurnCapDeg = 14.0D;
        PARAMS.turnAccelDeg = 1.2D;
        PARAMS.turnGain = 0.2D;
        PARAMS.climbMax = 0.12D;
        PARAMS.sinkMax = 0.10D;
        PARAMS.vyAccel = 0.015D;
        PARAMS.climbGain = 0.1D;
        PARAMS.windFactor = 2.5D;
        PARAMS.flutterDeg = 18.0D;
        PARAMS.flutterBob = 0.6D;
        PARAMS.flutterPeriod = 36.0D;
    }

    /** 林冠からの高さの層(ブロック)。【決定 11-14】林冠の少し上(+5〜15)。 */
    public static final double LAYER_MIN = 5.0D, LAYER_MAX = 15.0D;
    /** 群れの上限【仮】(決定は「数体」)。 */
    public static final int FLOCK_MAX = 5;

    /**
     * 叩ける部位【仮】(描画倍率 2 = 1 単位 1/8 ブロック、描画で 18.8 単位下げて脚の先を足元に置く):
     * 左右の翅(付け根 x ±2.5・先 ±19 単位 → 中心 ±1.4 ブロック、幅 1.6)と腹の松かさ(z 2.7〜16 単位 → 後ろ 1.2)。
     * 胸の芯(0.8 × 1.0)は本体の箱。左右は対称なので geo の x の向き(鏡写し)は気にしない。
     */
    static final String[] PARTS = {"wing_l", "wing_r", "abdomen"};
    static final float[] PART_W = {1.6F, 1.6F, 0.7F};
    static final float[] PART_H = {0.5F, 0.5F, 0.6F};
    static final double[] PART_CENTERS = {
            -0.2D, 1.4D, 0.9D,
            -0.2D, -1.4D, 0.9D,
            -1.2D, 0.0D, 0.65D};
    /**
     * 止まっている・溜めている間(寝かせた翼果: 4 枚の翅は腹の上へ後ろへ伏せる。左右 ±1.25・後ろ 0〜2.25・高さ 0.2〜0.7 ブロック、Vault 6.6 の 7)
     * の部位の置き場【仮】。翅の 2 つは後ろ 1.0・左右 ±0.6・高さ 0.45、腹は伏せた翅の下で後ろ 1.5・高さ 0.4。部位の大きさは飛ぶ間のまま。
     */
    static final double[] PART_CENTERS_PERCHED = {
            -1.0D, 0.6D, 0.45D,
            -1.0D, -0.6D, 0.45D,
            -1.5D, 0.0D, 0.4D};

    /** 飛び立ちの溜め【決定 11-15: 7 tick】と、溜めへ入る補間(その分だけ溜めを長くする)。 */
    public static final int WINDUP_TICKS = 7, ENTRY_SHORT = 3;
    /** 途中から入る所の補間(tick)。fly ↔ idle は段 1 のまま {@link #ENTRY_FLY}。 */
    static final int ENTRY = 4, ENTRY_FLY = 5;
    /** 一斉に飛び立つ距離【仮・決定 11-15 全体管理】(逃げる種なのでヴェロフィルの 8 より早い。しゃがむと 5)。 */
    public static final double FLUSH_RANGE_SEMINARIA = 10.0D;

    /**
     * 風に揉まれる層の強さ【仮・Claude。遊んで詰める】: 卓越風の強さ(1 = ふだんの午後)が {@link #GUST_FROM} から揉まれ始め、
     * {@link #GUST_FULL} で表の値いっぱい(Vault 6.5b)。飛ぶ間だけ。止まる・降りる・飛び立つ間は 1 秒かけて 0 へ戻す。
     */
    public static final double GUST_FROM = 0.4D, GUST_FULL = 1.3D;

    /** サーバー → 描画: 今の卓越風の強さ(1 = ふだんの午後。上限 {@link FlyerWind#STRENGTH_CAP})。20 tick おきに送る。 */
    private static final EntityDataAccessor<Float> DATA_WIND = SynchedEntityData.defineId(SeminariaEntity.class, EntityDataSerializers.FLOAT);

    // ------------------------------------------------------------------ 能力(2026-11-16。種まき・逃げる)
    // 数値はどれも【仮・Claude 11-16】(決まっているのは種まきの 3 段の長さと種の離れる時刻・位置・浮いたまま落とすこと)。

    /** 種まきの段(サーバーで進め、描画へ送る)。 */
    public static final int SOW_NONE = 0, SOW_DESCEND = 1, SOW_WINDUP = 2, SOW_DROP = 3, SOW_RECOVER = 4;
    /** 送る印: 群れが逃げている(flee を流す)。 */
    private static final int FLEE_BIT = 8;
    private static final EntityDataAccessor<Byte> DATA_SOW = SynchedEntityData.defineId(SeminariaEntity.class, EntityDataSerializers.BYTE);

    /** 種まきの 3 段の長さ(tick)【決定 11-15 のまま仮】: 前触れ sow_windup 16 → 落とす sow 10 → 隙 sow_recover 24。前触れは入りの補間の分だけ長い。 */
    public static final int SOW_WINDUP_TICKS = 16, SOW_TICKS = 10, SOW_RECOVER_TICKS = 24;
    /** 舞い降りる descend から前触れへ入る補間(tick。差は最大 25 度)。 */
    static final int SOW_ENTRY = ENTRY;
    /** 種が離れる: sow の 3 tick 目【決定 11-15】。 */
    public static final int SEED_AT = 3;
    /** 種が離れる所: 足元から後ろ 1.0・下 0.2(体の向きに対して)【決定 11-15(アニメから測った値)】。 */
    public static final double SEED_BACK = 1.0D, SEED_DOWN = 0.2D;
    /** 落とす高さ: 足元が真下の面(葉を含む)から何ブロック上か。決定「2 ブロック以上」(冠毛が足元の 1.7 下まで垂れる)に余裕を足して 3。 */
    public static final double SOW_ABOVE = 3.0D;
    /** 舞い降りる速さ(ブロック/tick)と、舞い降りるのをやめる長さ(tick)。 */
    public static final double DESCEND_SPEED = 0.08D;
    public static final int DESCEND_MAX = 160;
    /** 群れの中で舞い降り始めをずらす間(tick。1 体目 0、2 体目 8 …)。 */
    public static final int SOW_STAGGER = 8;
    /** 群れが種まきへ出る間隔(tick。5 分 = 昼の間に 2 回ほど)と、湧いて・読み込んでから最初の種まきまでの幅。 */
    public static final int SORTIE_COOLDOWN = 6000, FIRST_SORTIE_MIN = 600, FIRST_SORTIE_MAX = 2400;
    /**
     * 種を落とす升の選び方: 群れの先頭のまわり {@link #SEARCH_CHUNKS} チャンクのうち、まわり 1 チャンク(3×3)に過繁茂の台帳の升が無く、
     * 風上へ {@link #FRONT_GAP}〜{@link #FRONT_AHEAD} チャンク戻ると台帳の升がある所(= 前線から 1 升以上離れた風下の先 = 飛び地)。
     * いちばん近い升を選ぶ。凪なら向きを問わない(まわり {@link #FRONT_AHEAD} の中に台帳の升がある所)。
     */
    public static final int SEARCH_CHUNKS = 4, FRONT_GAP = 2, FRONT_AHEAD = 4, TARGET_EVERY = 40;
    /** 追い払われて種まきをやめたときの、次の種まきまで(tick。1 分)。 */
    public static final int RETRY_AFTER_SCARE = 1200;
    /** 選んだ升の真ん中にこれより近づいたら群れで舞い降りる(ブロック)。 */
    public static final double START_NEAR = 12.0D;
    /** 飛んでいる間にプレイヤーがこれより近いと群れで逃げる(ブロック)。止まっている間は {@link #FLUSH_RANGE_SEMINARIA}。 */
    public static final double FLEE_RANGE = 16.0D;
    /** 逃げる向き: 相手から離れる向きを、風下から ±{@link #FLEE_MAX_OFF_WIND} 度の中へ寄せる(風上へは進めないので)。 */
    public static final double FLEE_MAX_OFF_WIND = 90.0D;
    /** 光の点け消し(sow_windup の 4 → 12 tick で点く・sow_recover の 0 → 18 tick で消える【決定 11-15 のまま仮】)・やめたときに消える長さ。 */
    static final int GLOW_ON_FROM = 4, GLOW_ON_TO = 12, GLOW_OFF_TICKS = 18, GLOW_ABORT_TICKS = 5;

    /** 種まき(この個体。サーバー)。 */
    private int sowPhase, sowTicks, sowWait = -1;
    private double sowVx, sowVz;
    /** 群れの種まきまでの残り(先頭のものを使う)・選んだ升(Long.MIN_VALUE = 無し)。 */
    private int sortieCooldown = -1;
    private long sowTarget = Long.MIN_VALUE;

    /** クライアント: 光の強さ・送られた段が替わった tick。 */
    private float glowO, glowNow;
    private int shownSow = -1, shownSince;
    /** クライアント: 今の種まきで種を落とす段まで進んだか(進んでいれば、隙の後も SOW のつなぎの fly をそのまま流す)。 */
    private boolean sowReachedDrop;

    private static final RawAnimation FLY = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation DRIFT = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation PERCH_IDLE = RawAnimation.begin().thenLoop("perch_idle");
    private static final RawAnimation WINDUP = RawAnimation.begin().thenPlayAndHold("takeoff_windup");
    private static final RawAnimation TAKEOFF = RawAnimation.begin().thenPlay("takeoff").thenLoop("fly");
    private static final RawAnimation LAND = RawAnimation.begin().thenPlay("land_windup").thenLoop("land");
    private static final RawAnimation LAND_RECOVER = RawAnimation.begin().thenPlay("land_recover").thenLoop("perch_idle");
    private static final RawAnimation DESCEND = RawAnimation.begin().thenLoop("descend");
    private static final RawAnimation SOW = RawAnimation.begin().thenPlay("sow_windup").thenPlay("sow").thenPlay("sow_recover").thenLoop("fly");
    private static final RawAnimation FLEE = RawAnimation.begin().thenLoop("flee");

    /** クライアント: 今流しているつなぎ・流し始めた tick・入りの補間。 */
    private RawAnimation curAnim;
    private int curStart, curEntry;
    /** クライアント: 風に揉まれる層の強さ(0〜1)の前の tick と今。 */
    private float gustO, gustNow;
    private final double[] windTmp = new double[2];

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public SeminariaEntity(EntityType<? extends SeminariaEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 5);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    /** 計測・テスト用: 飛び方の値(書き換えない)。 */
    public static FlightBody.Params flightParams() {
        return PARAMS;
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

    @Override
    protected int flockMax() {
        return FLOCK_MAX;
    }

    @Override
    protected boolean looseFlock() {
        return true;
    }

    /** 家へ戻る・止まり木へ寄るときだけ、風に逆らって進路を保つ(横風には斜めに構え、向かい風には上限まで羽ばたく)。ふだんは風に任せる。 */
    @Override
    protected boolean fightsWind() {
        return true;
    }

    /** 巡回の行き先は風下寄り(風下 ±50 度の扇)。凪のときは今の向きのまま。 */
    @Override
    protected double patrolFanCenter() {
        double d = downwindYaw();
        return Double.isNaN(d) ? super.patrolFanCenter() : d;
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

    /** 止まった姿のままか(止まっている・飛び立ちの待ちと溜め)。部位の置き場に使う(サーバーもクライアントも送られた状態で)。 */
    private boolean perchedPose() {
        int m = mode();
        return m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() < 2;
    }

    @Override
    protected int takeoffWindupTicks() {
        return WINDUP_TICKS + ENTRY_SHORT;
    }

    @Override
    protected double flushRange() {
        return FLUSH_RANGE_SEMINARIA;
    }

    /** 新しいアニメの広がり(上 +2.3・下 −1.7・左右 ±2.4・前 1.1〜後ろ 3.8 ブロック、Vault 6.6 の 8)を収める。 */
    @Override
    public AABB getBoundingBoxForCulling() {
        return new AABB(this.getX() - 4.0D, this.getY() - 2.0D, this.getZ() - 4.0D,
                this.getX() + 4.0D, this.getY() + 2.6D, this.getZ() + 4.0D);
    }

    // ------------------------------------------------------------------ 風に揉まれる層

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_WIND, 0.0F);
        builder.define(DATA_SOW, (byte) 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            if (this.tickCount % 20 == 0 && this.level() instanceof ServerLevel sl) {
                FlyerWind.now(sl, this.windTmp);
                float s = (float) (Math.sqrt(this.windTmp[0] * this.windTmp[0] + this.windTmp[1] * this.windTmp[1]) / FlyerWind.BLOCKS_PER_STRENGTH);
                if (Math.abs(this.entityData.get(DATA_WIND) - s) > 0.02F) this.entityData.set(DATA_WIND, s);
            }
            return;
        }
        this.gustO = this.gustNow;
        // 揉まれる層は、ふだんの飛ぶ姿(fly・idle・flee)の間だけ。種まきの間は 0 へ
        float target = mode() == MODE_FLY && sowPhase() == SOW_NONE ? gustFor(this.entityData.get(DATA_WIND)) : 0.0F;
        this.gustNow += Mth.clamp(target - this.gustNow, -0.05F, 0.05F);   // 1 秒で 0 ⇄ 1
        tickGlowClient();
    }

    /** 卓越風の強さ → 揉まれる層の強さ(0〜1)【仮】。 */
    public static float gustFor(double windStrength) {
        return (float) Mth.clamp((windStrength - GUST_FROM) / (GUST_FULL - GUST_FROM), 0.0D, 1.0D);
    }

    /** 描画: 風に揉まれる層の強さ(0〜1)。 */
    public float gust(float pt) {
        return Mth.lerp(pt, this.gustO, this.gustNow);
    }

    /** 描画: 個体ごとの位相(ラジアン)。 */
    public double gustPhase() {
        return (this.getUUID().getLeastSignificantBits() & 0xFFFFL) / 65536.0D * Math.PI * 2.0D;
    }

    // ------------------------------------------------------------------ 能力: 種まき・逃げる(サーバー)

    /** 種まきの段(描画も読む)。 */
    public int sowPhase() {
        return this.entityData.get(DATA_SOW) & 7;
    }

    /** 群れが逃げているか(描画も読む)。 */
    public boolean fleeing() {
        return (this.entityData.get(DATA_SOW) & FLEE_BIT) != 0;
    }

    @Override
    protected double fleeSpeed() {
        return PARAMS.maxSpeed;   // 逃げるときは上限まで羽ばたく
    }

    @Override
    protected void customServerAiStep() {
        if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) tickAbility(sl);
        super.customServerAiStep();
        com.yayuy.overgrownvegetation.entity.flyer.Flock f = flock();
        boolean fl = f != null && f.state() == com.yayuy.overgrownvegetation.entity.flyer.Flock.State.FLEE && mode() == MODE_FLY;
        byte st = (byte) (this.sowPhase | (fl ? FLEE_BIT : 0));
        if (this.entityData.get(DATA_SOW) != st) this.entityData.set(DATA_SOW, st);
    }

    private void tickAbility(net.minecraft.server.level.ServerLevel sl) {
        com.yayuy.overgrownvegetation.entity.flyer.Flock f = flock();
        if (f == null) return;
        if (this.sortieCooldown < 0) this.sortieCooldown = FIRST_SORTIE_MIN + this.random.nextInt(FIRST_SORTIE_MAX - FIRST_SORTIE_MIN);
        // 止まり木へ向かう・止まった・飛び立つ間は自前の飛び方が呼ばれないので、途中の種まきはここで片付ける(朝に続きから落とさない)
        if ((this.sowPhase != SOW_NONE || this.sowWait >= 0)
                && (mode() != MODE_FLY || f.state() == com.yayuy.overgrownvegetation.entity.flyer.Flock.State.ROOST)) {
            this.sowWait = -1;
            if (this.sowPhase != SOW_NONE) endSow();
        }
        // プレイヤーから逃げる(飛んでいる間。止まっている間は土台の 10 ブロックの飛び立ち)
        if (mode() == MODE_FLY && (this.tickCount + getId()) % 10 == 0) {
            for (net.minecraft.server.level.ServerPlayer pl : sl.players()) {
                if (pl.isSpectator() || !pl.isAlive() || !PlantFoes.isOtherSide(pl) || pl.distanceToSqr(this) > FLEE_RANGE * FLEE_RANGE) continue;   // 死んだ・植物側のプレイヤーからは逃げない(①13)
                scare(sl, f, pl.getX(), pl.getZ());
                break;
            }
        }
        // 群れの種まき(先頭が升を選び、着いたら全員で舞い降りる)
        if (f.leader() == this) {
            if (this.sortieCooldown > 0) this.sortieCooldown--;
            boolean anySowing = false;
            for (com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer m : f.membersView()) {
                if (m instanceof SeminariaEntity s && (s.sowPhase != SOW_NONE || s.sowWait >= 0)) anySowing = true;
            }
            if (this.sortieCooldown <= 0 && !anySowing && mode() == MODE_FLY && f.state() == com.yayuy.overgrownvegetation.entity.flyer.Flock.State.PATROL
                    && !wantsRoost()) {
                if (this.tickCount % TARGET_EVERY == 0) {
                    FlyerWind.now(sl, this.windTmp);
                    this.sowTarget = findSowTarget(com.yayuy.overgrownvegetation.phase.PhaseData.get(sl), this.getBlockX() >> 4, this.getBlockZ() >> 4,
                            this.windTmp[0], this.windTmp[1]);
                }
                if (this.sowTarget != Long.MIN_VALUE) {
                    double tx = (net.minecraft.world.level.ChunkPos.getX(this.sowTarget) << 4) + 8.0D;
                    double tz = (net.minecraft.world.level.ChunkPos.getZ(this.sowTarget) << 4) + 8.0D;
                    if (this.tickCount % TARGET_EVERY == 0) f.steerPatrol(tx, tz);
                    double dx = tx - this.getX(), dz = tz - this.getZ();
                    if (dx * dx + dz * dz < START_NEAR * START_NEAR) startSortie(f);
                }
            }
        }
        if (this.sowWait >= 0 && --this.sowWait < 0 && mode() == MODE_FLY
                && f.state() == com.yayuy.overgrownvegetation.entity.flyer.Flock.State.PATROL) {
            setSow(SOW_DESCEND);
        }
    }

    /** 群れの全員に舞い降りを割り当てる(先頭から順にずらす)。次の種まきまで {@link #SORTIE_COOLDOWN}。 */
    private void startSortie(com.yayuy.overgrownvegetation.entity.flyer.Flock f) {
        int i = 0;
        for (com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer m : f.membersView()) {
            if (!(m instanceof SeminariaEntity s)) continue;
            s.sortieCooldown = SORTIE_COOLDOWN;
            if (s.mode() == MODE_FLY) s.sowWait = i++ * SOW_STAGGER;
        }
        if (DEV) LOG.info("SEMI_SORTIE 群れ {} 体が升 {} {} へ舞い降りる(先頭 {} {} {})", i, net.minecraft.world.level.ChunkPos.getX(this.sowTarget),
                net.minecraft.world.level.ChunkPos.getZ(this.sowTarget), String.format("%.1f", this.getX()), String.format("%.1f", this.getY()), String.format("%.1f", this.getZ()));
        this.sowTarget = Long.MIN_VALUE;
    }

    /** 驚いた: 群れで逃げる(相手から離れる向きを風下へ寄せる)。まだ種を落としていない個体は種まきをやめる。 */
    private void scare(net.minecraft.server.level.ServerLevel sl, com.yayuy.overgrownvegetation.entity.flyer.Flock f, double fromX, double fromZ) {
        double away = com.yayuy.overgrownvegetation.entity.flyer.FlightBody.yawOf(this.getX() - fromX, this.getZ() - fromZ);
        double down = downwindYaw();
        double heading = Double.isNaN(down) ? away
                : down + Mth.clamp(com.yayuy.overgrownvegetation.entity.flyer.FlightBody.wrapDeg(away - down), -FLEE_MAX_OFF_WIND, FLEE_MAX_OFF_WIND);
        f.spook(heading);
        boolean cancelled = false;
        for (com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer m : f.membersView()) {
            if (m instanceof SeminariaEntity s) cancelled |= s.abortSowBeforeSeed();
        }
        // 種を落とす前に追い払われた: 次の種まきを早める(5 分待たせない)
        if (cancelled) {
            for (com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer m : f.membersView()) {
                if (m instanceof SeminariaEntity s) s.sortieCooldown = Math.min(s.sortieCooldown, RETRY_AFTER_SCARE);
            }
        }
    }

    /** 種を落とす前(待ち・舞い降り・前触れ)ならやめて列へ戻る。落とした後(落とす・隙)はそのまま続ける。 */
    private boolean abortSowBeforeSeed() {
        boolean was = this.sowWait >= 0 || this.sowPhase == SOW_DESCEND || this.sowPhase == SOW_WINDUP;
        this.sowWait = -1;
        if (this.sowPhase == SOW_DESCEND || this.sowPhase == SOW_WINDUP) endSow();
        return was;
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean hit = super.hurt(source, amount);
        if (hit && this.level() instanceof net.minecraft.server.level.ServerLevel sl && mode() == MODE_FLY && flock() != null) {
            net.minecraft.world.phys.Vec3 from = source.getSourcePosition() != null ? source.getSourcePosition() : this.position();
            scare(sl, flock(), from.x, from.z);
        }
        return hit;
    }

    private void setSow(int phase) {
        this.sowPhase = phase;
        this.sowTicks = 0;
    }

    /** 種まきを終えて列へ戻る: 土台の飛び方に今の向きと速さを渡す(隊列から離れていれば土台がまっすぐ追いつく)。 */
    private void endSow() {
        this.sowPhase = SOW_NONE;
        this.sowTicks = 0;
        this.body.heading = this.getYRot();
        this.body.speed = PARAMS.cruise;
        this.body.vy = 0.1D;
        this.body.turnRate = 0.0D;
    }

    /** 種まきの間だけ自前で飛ぶ(舞い降りる → 浮いたまま前触れ → 落とす → 隙で昇り直す)。 */
    @Override
    protected boolean ownFlight(net.minecraft.server.level.ServerLevel sl, com.yayuy.overgrownvegetation.entity.flyer.Flock f) {
        if (this.sowPhase == SOW_NONE) return false;
        this.sowTicks++;
        FlyerWind.now(sl, this.windTmp);
        double wm = this.debugNoSowDrift ? 0.0D : PARAMS.windFactor;
        double wx = this.windTmp[0] * wm, wz = this.windTmp[1] * wm;
        double vx, vy, vz;
        switch (this.sowPhase) {
            case SOW_DESCEND -> {
                // 落ち葉のように風に流されながら舞い降りる(流される分は飛ぶときの 4 割)
                double h = Math.toRadians(this.getYRot());
                vx = -Math.sin(h) * 0.05D + wx * 0.4D;
                vz = Math.cos(h) * 0.05D + wz * 0.4D;
                vy = -DESCEND_SPEED;
                double surface = groundBelow(sl, this.position());
                if (this.getY() - surface <= SOW_ABOVE || this.verticalCollision || this.sowTicks >= DESCEND_MAX) {
                    setSow(SOW_WINDUP);
                    vy = 0.0D;
                }
            }
            case SOW_WINDUP, SOW_DROP -> {
                // 浮いたまま(風に少しだけ流される)
                vx = wx * 0.1D;
                vz = wz * 0.1D;
                vy = 0.0D;
                if (this.sowPhase == SOW_WINDUP && this.sowTicks >= SOW_WINDUP_TICKS + SOW_ENTRY) {
                    setSow(SOW_DROP);
                } else if (this.sowPhase == SOW_DROP) {
                    if (this.sowTicks == SEED_AT) dropSeed(sl);
                    if (this.sowTicks >= SOW_TICKS) setSow(SOW_RECOVER);
                }
            }
            case SOW_RECOVER -> {
                // 重い羽ばたきで昇り直す
                double h = Math.toRadians(this.getYRot());
                double k = Math.min(1.0D, this.sowTicks / 10.0D);
                vx = -Math.sin(h) * 0.12D * k + wx * 0.2D;
                vz = Math.cos(h) * 0.12D * k + wz * 0.2D;
                vy = 0.1D * k;
                if (this.sowTicks >= SOW_RECOVER_TICKS) {
                    endSow();
                    return false;
                }
            }
            default -> {
                endSow();
                return false;
            }
        }
        // 群れが休みに入った・逃げ出したら、落とす前はやめる
        if (f.state() != com.yayuy.overgrownvegetation.entity.flyer.Flock.State.PATROL && (this.sowPhase == SOW_DESCEND || this.sowPhase == SOW_WINDUP)) {
            endSow();
            return false;
        }
        this.sowVx = vx;
        this.sowVz = vz;
        setOwnMotion(vx, vy, vz);
        return true;
    }

    /** 舞い降りる先の面: 足元から真下へ {@link #GROUND_SCAN} ブロックまで見て、最初の動きを止めるブロック(葉を含む)の上面の高さ。無ければ見た所の底。 */
    public static int groundBelow(net.minecraft.world.level.Level level, net.minecraft.world.phys.Vec3 feet) {
        net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos(Mth.floor(feet.x), Mth.floor(feet.y), Mth.floor(feet.z));
        int bottom = p.getY() - GROUND_SCAN;
        for (int y = p.getY(); y > bottom; y--) {
            p.setY(y);
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
            if (s.blocksMotion() || s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock || !s.getFluidState().isEmpty()) return y + 1;
        }
        return bottom;
    }

    /** {@link #groundBelow} が真下を見る深さ(ブロック)。舞い降り始める高さ(林冠 +5〜15)より深く。 */
    static final int GROUND_SCAN = 48;

    /** 腹の松かさの鱗の間から種を 1 粒落とす(足元から後ろ {@link #SEED_BACK}・下 {@link #SEED_DOWN})。 */
    private void dropSeed(net.minecraft.server.level.ServerLevel sl) {
        double h = Math.toRadians(this.getYRot());
        net.minecraft.world.phys.Vec3 at = this.position().add(Math.sin(h) * SEED_BACK, -SEED_DOWN, -Math.cos(h) * SEED_BACK);
        SeminariaSeed seed = new SeminariaSeed(sl, this, at);
        seed.setDeltaMovement(this.sowVx * 0.5D, -0.05D, this.sowVz * 0.5D);
        sl.addFreshEntity(seed);
        seedsDropped++;
        if (DEV) LOG.info("SEMI_SEED {} が落とした: {} {} {}(足元から地面まで {})", getId(), String.format("%.1f", at.x), String.format("%.1f", at.y), String.format("%.1f", at.z),
                String.format("%.2f", this.getY() - groundBelow(sl, this.position())));
    }

    private static final boolean DEV = Boolean.getBoolean("ov.devbridge");
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** 計測・テスト用: 落とした種の数(サーバー全体)。 */
    public static volatile int seedsDropped;

    /**
     * 種を落とす升を選ぶ(素の計算。GameTest からも呼ぶ)。(cx0, cz0) のまわり {@link #SEARCH_CHUNKS} の升のうち、まわり 1 チャンクに台帳の升が無く、
     * 風上へ {@link #FRONT_GAP}〜{@link #FRONT_AHEAD} チャンク戻ると台帳に載った升があるもの。いちばん近い升(同じ近さなら最初に見つけたもの)。無ければ Long.MIN_VALUE。
     * 風が無いときは、まわり {@link #FRONT_AHEAD} の中に台帳の升があれば向きを問わない。
     */
    public static long findSowTarget(com.yayuy.overgrownvegetation.phase.PhaseData pd, int cx0, int cz0, double windX, double windZ) {
        double wl = Math.sqrt(windX * windX + windZ * windZ);
        double ux = wl > 1e-6 ? windX / wl : 0.0D, uz = wl > 1e-6 ? windZ / wl : 0.0D;
        long best = Long.MIN_VALUE;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -SEARCH_CHUNKS; dx <= SEARCH_CHUNKS; dx++) {
            for (int dz = -SEARCH_CHUNKS; dz <= SEARCH_CHUNKS; dz++) {
                int d = dx * dx + dz * dz;
                if (d >= bestD) continue;
                int cx = cx0 + dx, cz = cz0 + dz;
                if (pd.isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) continue;
                if (behindFront(pd, cx, cz, ux, uz, wl > 1e-6)) {
                    best = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
                    bestD = d;
                }
            }
        }
        return best;
    }

    private static boolean behindFront(com.yayuy.overgrownvegetation.phase.PhaseData pd, int cx, int cz, double ux, double uz, boolean windy) {
        // 前線に接した升には落とさない(まわり 1 チャンクに台帳の升があれば外す)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (pd.isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz))) return false;
            }
        }
        if (!windy) {
            for (int dx = -FRONT_AHEAD; dx <= FRONT_AHEAD; dx++) {
                for (int dz = -FRONT_AHEAD; dz <= FRONT_AHEAD; dz++) {
                    if (pd.isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz))) return true;
                }
            }
            return false;
        }
        for (int k = FRONT_GAP; k <= FRONT_AHEAD; k++) {
            int bx = cx - (int) Math.round(ux * k), bz = cz - (int) Math.round(uz * k);
            if (pd.isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(bx, bz))) return true;
        }
        return false;
    }

    /** テスト用: 種まきの間に風に流されない(テスト場から隣のテストの上へ流されて、別の高さを地面と読まないように)。 */
    public boolean debugNoSowDrift;

    /** テスト用: この個体だけ今すぐ舞い降りて種を落とす。 */
    public void debugBeginSow() {
        this.sowWait = -1;
        setSow(SOW_DESCEND);
    }

    // ------------------------------------------------------------------ 光る筋(描画)

    /** クライアント: 送られた種まきの段から光の強さを進める(点くのは前触れの 4 → 12 tick、消えるのは隙の 0 → 18 tick)。 */
    private void tickGlowClient() {
        int ph = sowPhase();
        if (ph != this.shownSow) {
            this.shownSow = ph;
            this.shownSince = this.tickCount;
        }
        int t = this.tickCount - this.shownSince;
        float target;
        switch (ph) {
            case SOW_WINDUP -> {
                float at = t - SOW_ENTRY;   // アニメの時刻(入りの補間の後に sow_windup の 0 秒)
                target = Mth.clamp((at - GLOW_ON_FROM) / (float) (GLOW_ON_TO - GLOW_ON_FROM), 0.0F, 1.0F);
            }
            case SOW_DROP -> target = 1.0F;
            case SOW_RECOVER -> target = Mth.clamp(1.0F - t / (float) GLOW_OFF_TICKS, 0.0F, 1.0F);
            default -> target = -1.0F;
        }
        this.glowO = this.glowNow;
        this.glowNow = target >= 0.0F ? target : Math.max(0.0F, this.glowNow - 1.0F / GLOW_ABORT_TICKS);
    }

    /** 描画: 種を落とす間の光る筋の強さ(0〜1)。 */
    public float sowGlow(float pt) {
        return Mth.lerp(pt, this.glowO, this.glowNow);
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", ENTRY_FLY, this::mainState));
    }

    /**
     * つなぎを選ぶ(ヴェロフィルと同じ)。入りの補間は流し始めにだけ使い、それを過ぎたら 0 にする
     * (つないだ段の間は差 0。GeckoLib は段の間でも補間の長さを使うので)。
     */
    private PlayState mainState(AnimationState<SeminariaEntity> state) {
        int m = mode();
        RawAnimation want;
        int entry;
        int sp = sowPhase();
        if (sp == SOW_DROP || sp == SOW_RECOVER) this.sowReachedDrop = true;
        else if (sp == SOW_DESCEND || sp == SOW_WINDUP) this.sowReachedDrop = false;
        if (m == MODE_FLY && sp == SOW_DESCEND) {
            want = DESCEND;   // 舞い降りる(飛ぶ途中から入る)
            entry = this.curAnim == DESCEND ? this.curEntry : ENTRY;
        } else if (m == MODE_FLY && (sp != SOW_NONE || this.curAnim == SOW && this.sowReachedDrop && flapping() && !fleeing())) {
            want = SOW;   // 前触れ → 落とす → 隙 → fly(差 0 でつながる。舞い降りの途中から入るので入りだけ補間)
            entry = this.curAnim == SOW ? this.curEntry : SOW_ENTRY;
        } else if (m == MODE_FLY && fleeing()) {
            want = FLEE;
            entry = this.curAnim == FLEE ? this.curEntry : ENTRY_FLY;
        } else if (m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() == 0) {
            if (this.curAnim == LAND || this.curAnim == LAND_RECOVER) {
                want = LAND_RECOVER;   // 止まった tick に掴む(制動の羽ばたきの途中から入るので短く補間)
                entry = this.curAnim == LAND ? ENTRY_SHORT : this.curEntry;
            } else {
                want = PERCH_IDLE;   // セーブから止まった姿で読み込んだ個体は land_recover を通さず直に
                entry = this.curAnim == PERCH_IDLE ? this.curEntry : ENTRY;
            }
        } else if (m == MODE_TAKEOFF && takeoffPhase() == 1) {
            want = WINDUP;
            entry = this.curAnim == WINDUP ? this.curEntry : ENTRY_SHORT;
        } else if (m == MODE_TAKEOFF) {
            want = TAKEOFF;
            entry = this.curAnim == WINDUP ? 0 : (this.curAnim == TAKEOFF ? this.curEntry : ENTRY);   // 溜めの終わり = takeoff の 0 秒(差 0)
        } else if (m == MODE_LANDING) {
            want = LAND;
            entry = this.curAnim == LAND ? this.curEntry : ENTRY;
        } else if (this.curAnim == TAKEOFF && flapping()) {
            want = TAKEOFF;   // takeoff の後はそのまま fly を繰り返している
            entry = this.curEntry;
        } else {
            want = flapping() ? FLY : DRIFT;
            entry = ENTRY_FLY;
        }
        if (want != this.curAnim) {
            this.curAnim = want;
            this.curStart = this.tickCount;
            this.curEntry = entry;
        }
        boolean chained = want == TAKEOFF || want == LAND || want == LAND_RECOVER || want == WINDUP || want == SOW;
        state.getController().transitionLength(chained && this.tickCount - this.curStart > this.curEntry ? 0 : this.curEntry);
        return state.setAndContinue(want);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
