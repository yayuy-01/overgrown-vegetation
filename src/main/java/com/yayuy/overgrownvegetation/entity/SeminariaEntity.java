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
 * descend・sow_*・flee は種まきと逃げる能力の段で使う(まだ流さない)。
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

    private static final RawAnimation FLY = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation DRIFT = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation PERCH_IDLE = RawAnimation.begin().thenLoop("perch_idle");
    private static final RawAnimation WINDUP = RawAnimation.begin().thenPlayAndHold("takeoff_windup");
    private static final RawAnimation TAKEOFF = RawAnimation.begin().thenPlay("takeoff").thenLoop("fly");
    private static final RawAnimation LAND = RawAnimation.begin().thenPlay("land_windup").thenLoop("land");
    private static final RawAnimation LAND_RECOVER = RawAnimation.begin().thenPlay("land_recover").thenLoop("perch_idle");

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
        float target = mode() == MODE_FLY ? gustFor(this.entityData.get(DATA_WIND)) : 0.0F;
        this.gustNow += Mth.clamp(target - this.gustNow, -0.05F, 0.05F);   // 1 秒で 0 ⇄ 1
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
        if (m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() == 0) {
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
        boolean chained = want == TAKEOFF || want == LAND || want == LAND_RECOVER || want == WINDUP;
        state.getController().transitionLength(chained && this.tickCount - this.curStart > this.curEntry ? 0 : this.curEntry);
        return state.setAndContinue(want);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
