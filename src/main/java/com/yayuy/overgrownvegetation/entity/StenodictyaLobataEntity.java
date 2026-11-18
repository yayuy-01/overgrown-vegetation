package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.flyer.FlightBody;
import com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
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
 * ステノディクティア {@code stenodictya_lobata}(G2・原生生産種の成虫。翅 2 対、翅は汚染シダの色)。Vault「Mob の能力/ステノディクティア」。
 *
 * <h2>段 1(2026-11-16): 飛ぶ・ゆるい群れ・葉の下面にぶら下がって休むまで</h2>
 * 動きは「飛ぶ型」の土台 {@link SkyFlyer}(セミナリアと同じ、風に半分任せる)。【決定 11-16 全体管理・ユーザー】林冠 +2〜8 の低め・風の倍率 1.5・
 * 1〜3 匹のゆるい群れ・**夜と雨は葉の層の下面に逆さにぶら下がって止まる**(10-10 のユーザー指定の姿。土台の口 {@link #perchUnder})。
 * G2・G3 は生息しているだけの生き物(胞子を運ばない・亜種なし)。植物側({@link ParasitizedMob}・タグ group_2)。自然には湧かない
 * (生態系の台帳 {@code ecosystem/EcoSpawns} へ後でつなぐ)。食べる・逃げるなどの行動と幼虫 {@code stenodictya_nymph} からの羽化は後の段。
 * 形・骨・アニメは 10-10 製のまま、絵だけ 1/16 画素に塗り直した(正本 {@code デスクトップ\blockbench\mobs\stenodictya_lobata.bbmodel})。
 * アニメ: fly・rest(逆さ)・takeoff と、11-16 にモデル制作が足した下面の 4 本(perch_approach・perch_land・perch_takeoff_windup・perch_takeoff)。
 * 正立 ⇄ 逆さの返しはアニメの胴の骨(z 0 ⇄ 180)が持つ。hover・perch・eat は今は使わない。
 */
public class StenodictyaLobataEntity extends SkyFlyer implements GeoEntity, ParasitizedMob {

    public static final String ID = "stenodictya_lobata";

    /**
     * 飛び方【仮・Claude 11-16】。巡航 0.35・上限 0.55(セミナリア 0.3・0.45 より少し速い軽い虫)。風の倍率 1.5【決定 11-16】
     * (ふだんの午後の風 = 0.15 ブロック/tick で、巡航なら風下 0.5・風上 0.2 = セミナリアほど流されない)。小回りが利く(巡航で 8 度/tick)。
     * ひらひらは小さめ(トンボのように進路は割とまっすぐ)。
     */
    private static final FlightBody.Params PARAMS = new FlightBody.Params();

    static {
        PARAMS.cruise = 0.35D;
        PARAMS.minSpeed = 0.12D;
        PARAMS.maxSpeed = 0.55D;
        PARAMS.accel = 0.03D;
        PARAMS.decel = 0.04D;
        PARAMS.maxTurnDeg = 8.0D;
        PARAMS.maxTurnCapDeg = 16.0D;
        PARAMS.turnAccelDeg = 1.5D;
        PARAMS.turnGain = 0.2D;
        PARAMS.climbMax = 0.12D;
        PARAMS.sinkMax = 0.10D;
        PARAMS.vyAccel = 0.015D;
        PARAMS.climbGain = 0.1D;
        PARAMS.windFactor = 1.5D;
        PARAMS.flutterDeg = 8.0D;
        PARAMS.flutterBob = 0.3D;
        PARAMS.flutterPeriod = 26.0D;
    }

    /** 林冠からの高さの層(ブロック)【決定 11-16・数値は仮】。林冠 +2〜8 の低め(梢の近くを飛ぶ)。 */
    public static final double LAYER_MIN = 2.0D, LAYER_MAX = 8.0D;
    /** 群れの上限【決定 11-16: 1〜3 匹のゆるい群れ】。 */
    public static final int FLOCK_MAX = 3;

    /** 叩ける部位は無い(小さな虫。当たりは胴の芯 0.6 × 0.4 だけ)。 */
    static final String[] PARTS = {};
    static final float[] PART_W = {};
    static final float[] PART_H = {};
    static final double[] PART_CENTERS = {};

    private static final RawAnimation FLY = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation REST = RawAnimation.begin().thenLoop("rest");
    /** 寄りの途中(まだ正立)から飛び立つときだけ使う前からの飛び立ち。 */
    private static final RawAnimation TAKEOFF = RawAnimation.begin().thenPlay("takeoff").thenLoop("fly");
    // 葉の下面へ止まる・飛び立つ(2026-11-16 モデル制作 stenodictya-anim。Vault「ステノディクティア」5 節)
    private static final RawAnimation APPROACH = RawAnimation.begin().thenLoop("perch_approach");
    private static final RawAnimation LAND = RawAnimation.begin().thenPlay("perch_land").thenLoop("rest");
    private static final RawAnimation WINDUP = RawAnimation.begin().thenPlayAndHold("perch_takeoff_windup");
    private static final RawAnimation PERCH_TAKEOFF = RawAnimation.begin().thenPlay("perch_takeoff").thenLoop("fly");
    /** 梢の繭から羽化する(2026-11-18 生態系・アニメ担当 stenodictya-anim: 50 tick、終わりは rest と差 0・入りの補間 0)。 */
    private static final RawAnimation EMERGE = RawAnimation.begin().thenPlay("emerge").thenLoop("rest");
    public static final int EMERGE_TICKS = 50;
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> DATA_EMERGING =
            net.minecraft.network.syncher.SynchedEntityData.defineId(StenodictyaLobataEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    private int emergeUntil;

    /** 溜め(perch_takeoff_windup 4 tick)と、その入りの補間 1 tick【モデル制作の測った値】。 */
    public static final int WINDUP_TICKS = 4, WINDUP_ENTRY = 1;
    /**
     * 入りの補間(tick)【モデル制作の測った値】: 寄り → perch_land 2・途中から perch_takeoff 3・ふつう 5・
     * perch_land の途中で驚いた → 溜め 4(返しの前は胴の z が最大 180 度違うので、補間の間に逆さへ回り切らせる)。
     */
    static final int ENTRY_LAND = 2, ENTRY_TAKEOFF_MID = 3, ENTRY = 5, ENTRY_WINDUP_MID_LAND = 4;
    /** perch_land の長さ(tick)と、止まった tick から入りの補間を足した長さ(この間に驚いたら溜めを長くする)。 */
    static final int LAND_TICKS = 16;

    /** サーバー: 止まった tick(下面に着いた所。perch_land の流し始め)。 */
    private int perchedAt = Integer.MIN_VALUE;
    private int lastMode = -1;

    /** クライアント: 今流しているつなぎ・流し始めた tick・入りの補間。 */
    private RawAnimation curAnim;
    private int curStart, curEntry;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public StenodictyaLobataEntity(EntityType<? extends StenodictyaLobataEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 3);
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

    /** 葉の層の下面に逆さにぶら下がって止まる(10-10 ユーザー指定・11-16 決定 A)。 */
    @Override
    protected boolean perchUnder() {
        return true;
    }

    /**
     * 家へ戻る・止まり木へ寄る間だけ風に逆らって進路を保つ(セミナリアと同じ)。無いと口(林冠の縁の外)の手前で遅くなったとき、
     * 風(倍率 1.5 で最大 0.225)に押し戻されて口へ着けないことがあった(11-17 軽量化のセッションの GameTest で 5 回中 4 回)。上限 0.55 は風より速い。
     */
    @Override
    protected boolean fightsWind() {
        return true;
    }

    /** 葉の下面を脚で掴んでいるので、ぶら下がっている間は向きを変えない【仮・Claude 11-16。モデル制作の頼み】。 */
    @Override
    protected boolean turnsWhilePerched() {
        return false;
    }

    /**
     * 飛び立つ前に下面で溜める(perch_takeoff_windup 4 + 入りの補間 1)。この間は動かない。口へ抜けるのはその後。
     * 止まってすぐ(perch_land の途中)に驚いたときは、入りの補間を 4 にして 4 + 4 = 8【モデル制作の測った値・見た目は未確認】。
     */
    @Override
    protected int takeoffWindupTicks() {
        boolean midLand = this.tickCount - this.perchedAt < ENTRY_LAND + LAND_TICKS;
        return WINDUP_TICKS + (midLand ? ENTRY_WINDUP_MID_LAND : WINDUP_ENTRY);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_EMERGING, false);
    }

    /** 梢の繭から出た: 繭の升(葉のすぐ下)にぶら下がった姿で始め、emerge を流す間は飛び立たない。 */
    public void emergeFrom(net.minecraft.core.BlockPos cocoon) {
        startPerchedAt(cocoon.above(), EMERGE_TICKS);
        this.entityData.set(DATA_EMERGING, true);
        this.emergeUntil = this.tickCount + EMERGE_TICKS;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;
        if (this.entityData.get(DATA_EMERGING) && this.tickCount >= this.emergeUntil) this.entityData.set(DATA_EMERGING, false);
        int m = mode();
        if (m == MODE_PERCHED && this.lastMode == MODE_LANDING) this.perchedAt = this.tickCount;   // 読み込み直しは寄りから来ないので数えない
        this.lastMode = m;
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

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 5, this::mainState));
    }

    /**
     * つなぎを選ぶ(セミナリアと同じ作り。体の返しはアニメの胴の骨が持つので、描画では回さない)。
     * 寄る = perch_approach / 止まった tick から perch_land → rest(寄りから来たときだけ。読み込み直しは rest へ直に)/
     * 溜め = perch_takeoff_windup / 口へ抜ける段から perch_takeoff → fly / 寄りの途中(正立)から飛び立つ = takeoff → fly。
     * 入りの補間は流し始めにだけ使い、それを過ぎたら 0(つないだ段の間は差 0)。
     */
    private PlayState mainState(AnimationState<StenodictyaLobataEntity> state) {
        int m = mode();
        RawAnimation want;
        int entry;
        if (this.entityData.get(DATA_EMERGING) || m == MODE_PERCHED && this.curAnim == EMERGE) {
            want = EMERGE;   // 羽化(入りの補間 0)。終わると rest を繰り返す
            entry = 0;
        } else if (m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() == 0) {
            if (this.curAnim == APPROACH || this.curAnim == LAND) {
                want = LAND;   // 止まった tick に返して掴む
                entry = this.curAnim == APPROACH ? ENTRY_LAND : this.curEntry;
            } else {
                want = REST;
                entry = this.curAnim == REST ? this.curEntry : ENTRY;
            }
        } else if (m == MODE_TAKEOFF && takeoffPhase() == 1) {
            want = WINDUP;
            entry = this.curAnim == WINDUP ? this.curEntry : (this.curAnim == LAND ? ENTRY_WINDUP_MID_LAND : WINDUP_ENTRY);
        } else if (m == MODE_TAKEOFF) {
            if (this.curAnim == APPROACH || this.curAnim == TAKEOFF) {
                want = TAKEOFF;   // 寄りの途中から(まだ正立): 前からの飛び立ち
                entry = this.curAnim == TAKEOFF ? this.curEntry : ENTRY;
            } else {
                want = PERCH_TAKEOFF;
                entry = this.curAnim == WINDUP ? 0 : (this.curAnim == PERCH_TAKEOFF ? this.curEntry : ENTRY_TAKEOFF_MID);
            }
        } else if (m == MODE_LANDING) {
            want = APPROACH;
            entry = this.curAnim == APPROACH ? this.curEntry : ENTRY;
        } else if ((this.curAnim == PERCH_TAKEOFF || this.curAnim == TAKEOFF) && flapping()) {
            want = this.curAnim;   // 飛び立ちの後はそのまま fly を繰り返している
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
        boolean chained = want == LAND || want == WINDUP || want == PERCH_TAKEOFF || want == TAKEOFF;
        state.getController().transitionLength(chained && this.tickCount - this.curStart > this.curEntry ? 0 : this.curEntry);
        return state.setAndContinue(want);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
