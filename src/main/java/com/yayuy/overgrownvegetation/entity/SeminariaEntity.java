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
 * セミナリア {@code seminaria}(G4・空の斥候兼種まき。和名の元はフリマキ)。Vault「Mob の能力/セミナリア」。
 *
 * <h2>段 1(2026-11-15): 飛ぶ・ゆるい群れ・林冠に止まって休むまで</h2>
 * 動きは「飛ぶ型」の土台 {@link SkyFlyer}。【決定 11-14】風に半分任せる: 羽ばたいて向きは変えられるが、風下へは速く、風上へはほとんど進めない。
 * ひらひら不規則に揺れる。数体で飛ぶが V 字は組まない(ゆるくまとまる。{@code LooseFormation})。夜と雨は林冠のてっぺんに止まる。
 * 種まき(飛び地)とプレイヤーから逃げるのは次の段。自然には湧かない。
 * 形は練習モデル フリマキ r7 {@code prac2_g4_drifter_r7}(正本 {@code デスクトップ\blockbench\mobs\seminaria.bbmodel})。描画倍率 2。
 * アニメは今ある fly(羽ばたき)・idle(翅を水平に広げて漂う。滑空と止まる姿にも使う)だけ(新しいアニメは Fable のセッションが作る決まり)。
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

    private static final RawAnimation FLY = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation DRIFT = RawAnimation.begin().thenLoop("idle");

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
        return PART_CENTERS;
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 5, this::mainState));
    }

    private PlayState mainState(AnimationState<SeminariaEntity> state) {
        int m = mode();
        if (m == MODE_PERCHED) {
            return state.setAndContinue(DRIFT);
        }
        return state.setAndContinue(m == MODE_FLY && !flapping() ? DRIFT : FLY);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
