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
 * アニメは今ある fly・hover・perch・rest・takeoff・eat だけを使う(perch・rest は体を z で 180 度回した逆さの姿)。
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
    private static final RawAnimation HOVER = RawAnimation.begin().thenLoop("hover");
    private static final RawAnimation REST = RawAnimation.begin().thenLoop("rest");
    private static final RawAnimation TAKEOFF = RawAnimation.begin().thenPlay("takeoff").thenLoop("fly");

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
     * 止まっている間 = rest(逆さにぶら下がって翅を畳む)/ 下面へ寄る間 = hover / 飛び立つ = takeoff → fly / 飛ぶ = fly。
     * 寄る・飛び立つ姿の逆さ ⇄ 正立は補間 5 tick で回る(専用のアニメはまだ無い。モデル制作へ頼む)。
     */
    private PlayState mainState(AnimationState<StenodictyaLobataEntity> state) {
        int m = mode();
        if (m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() == 0) return state.setAndContinue(REST);
        if (m == MODE_LANDING) return state.setAndContinue(HOVER);
        if (m == MODE_TAKEOFF) return state.setAndContinue(TAKEOFF);
        return state.setAndContinue(FLY);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
