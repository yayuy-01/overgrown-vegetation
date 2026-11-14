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
 * ヴェロフィル {@code velophyll}(G4・空の爆撃。V 字の群れ)。Vault「Mob の能力/ヴェロフィル」。
 *
 * <h2>段 1(2026-11-15): 飛ぶ・V 字の群れ・林冠に止まって休むまで</h2>
 * 動きは「飛ぶ型」の土台 {@link SkyFlyer}。爆撃(群れの端から 1 体ずつ急降下して胞子爆弾)は次の段。自然には湧かない。
 * 形は作り直し案 re_velophyll_r5(正本 {@code デスクトップ\blockbench\mobs\velophyll.bbmodel})。アニメは今ある fly(羽ばたき)・glide(滑空)・
 * ground_idle(翼を畳んで止まる)だけを使う(新しいアニメは Fable のセッションが作る決まり)。
 * 【決定 11-15 ユーザー】巡航 0.7 ブロック/tick・旋回の半径 約 10・当たりは胴の芯 + 翼の叩ける部位・羽ばたき + 時々滑空・群れの上限 7。
 */
public class VelophyllEntity extends SkyFlyer implements GeoEntity, ParasitizedMob {

    public static final String ID = "velophyll";

    /** 飛び方【仮・Claude。巡航 0.7 はユーザー決定】。 */
    private static final FlightBody.Params PARAMS = new FlightBody.Params();

    static {
        PARAMS.cruise = 0.7D;
        PARAMS.minSpeed = 0.25D;
        PARAMS.maxSpeed = 0.95D;
        PARAMS.maxTurnDeg = 4.0D;
    }

    /** 林冠からの高さの層(ブロック)。【決定 11-14】林冠の少し上(+5〜15)。 */
    public static final double LAYER_MIN = 5.0D, LAYER_MAX = 15.0D;
    /** 群れの上限【決定 11-15 ユーザー】。 */
    public static final int FLOCK_MAX = 7;

    /**
     * 叩ける部位【仮】: 左右の翼(翼の付け根 x ±4・先 ±46 単位 → 中心 ±1.75 ブロック、幅 2.4)と頭(z −23〜−31 単位 → 前 1.6)。
     * 胴の芯(1.2 × 0.8)は本体の箱。左右は対称なので geo の x の向き(鏡写し)は気にしない。
     */
    static final String[] PARTS = {"wing_l", "wing_r", "head"};
    static final float[] PART_W = {2.4F, 2.4F, 0.8F};
    static final float[] PART_H = {0.6F, 0.6F, 0.6F};
    static final double[] PART_CENTERS = {
            0.3D, 1.75D, 0.65D,
            0.3D, -1.75D, 0.65D,
            1.6D, 0.0D, 0.6D};

    private static final RawAnimation FLY = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation GLIDE = RawAnimation.begin().thenLoop("glide");
    private static final RawAnimation PERCHED = RawAnimation.begin().thenLoop("ground_idle");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public VelophyllEntity(EntityType<? extends VelophyllEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 10);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
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
        controllers.add(new AnimationController<>(this, "main", 6, this::mainState));
    }

    private PlayState mainState(AnimationState<VelophyllEntity> state) {
        int m = mode();
        if (m == MODE_PERCHED) {
            return state.setAndContinue(PERCHED);
        }
        return state.setAndContinue(m == MODE_FLY && !flapping() ? GLIDE : FLY);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
