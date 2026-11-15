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
 * 形は作り直し案 re_velophyll_r5(正本 {@code デスクトップ\blockbench\mobs\velophyll.bbmodel})。
 * <h2>段 2 のアニメのつなぎ(2026-11-15。アニメはモデル制作の持ち場で作ったもの、Vault 6.3・6.6)</h2>
 * 止まる = perch_idle(気付くと perch_kamae)/ 飛び立つ = その場で takeoff_windup → 昇り始めに takeoff → fly /
 * 降りる = land_windup → land(繰り返し)→ 止まった tick に land_recover → perch_idle。差 0 でつながる段の間は補間 0、
 * 途中から入る所だけ {@link #ENTRY_SHORT}〜{@link #ENTRY} tick(補間の分だけ溜めを長くして、溜めの終わりと昇り始めを揃える)。
 * 身構え【決定 11-15 全体管理・ユーザー「異論なし」】: 12 ブロックで身構えて相手を向き、8 ブロックで一斉に飛び立つ。驚いたときも溜め 7 tick を入れる。
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
    /**
     * 止まっている間(閉じた葉: 翼は背の上で合わせて立つ。左右 ±0.6・高さ 0.4〜2.8 ブロック)の部位の置き場。
     * 翼の 2 つを背の上に縦に重ねる(下 1.0・上 2.2 を中心に高さ 0.6 ずつ)。頭は低く前へ伏せる。
     */
    static final double[] PART_CENTERS_PERCHED = {
            0.0D, 0.0D, 1.0D,
            0.0D, 0.0D, 2.2D,
            1.6D, 0.0D, 0.4D};

    /** 飛び立ちの溜め【決定 11-15: 7 tick】と、溜めへ入る補間(その分だけ溜めを長くする)。 */
    public static final int WINDUP_TICKS = 7, ENTRY_SHORT = 3;
    /** 途中から入る所の補間(tick)。fly ↔ glide は今まで通り {@link #ENTRY_FLY}。 */
    static final int ENTRY = 4, ENTRY_FLY = 6;
    /** 身構える距離【仮・決定 11-15 全体管理】。 */
    public static final double ALERT_RANGE = 12.0D;

    private static final RawAnimation FLY = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation GLIDE = RawAnimation.begin().thenLoop("glide");
    private static final RawAnimation PERCH_IDLE = RawAnimation.begin().thenLoop("perch_idle");
    private static final RawAnimation KAMAE = RawAnimation.begin().thenLoop("perch_kamae");
    private static final RawAnimation WINDUP = RawAnimation.begin().thenPlayAndHold("takeoff_windup");
    private static final RawAnimation TAKEOFF = RawAnimation.begin().thenPlay("takeoff").thenLoop("fly");
    private static final RawAnimation LAND = RawAnimation.begin().thenPlay("land_windup").thenLoop("land");
    private static final RawAnimation LAND_RECOVER = RawAnimation.begin().thenPlay("land_recover").thenLoop("perch_idle");

    /** クライアント: 今流しているつなぎ・流し始めた tick・入りの補間。 */
    private RawAnimation curAnim;
    private int curStart, curEntry;

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
    protected double alertRange() {
        return ALERT_RANGE;
    }

    /** 止まった姿は翼が足元 +2.8 ブロック、降りる構えは +3.3 まで上がる(Vault 6.4)ので、上を +3.5 に。 */
    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        return new net.minecraft.world.phys.AABB(this.getX() - 3.5D, this.getY() - 1.0D, this.getZ() - 3.5D,
                this.getX() + 3.5D, this.getY() + 3.5D, this.getZ() + 3.5D);
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", ENTRY_FLY, this::mainState));
    }

    /**
     * つなぎを選ぶ。入りの補間は流し始めにだけ使い、それを過ぎたら 0 にする(つないだ段の間は差 0。GeckoLib は段の間でも補間の長さを使うので)。
     */
    private PlayState mainState(AnimationState<VelophyllEntity> state) {
        int m = mode();
        RawAnimation want;
        int entry;
        if (m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() == 0) {
            if (alerted()) {
                want = KAMAE;
                entry = ENTRY;
            } else if (this.curAnim == LAND || this.curAnim == LAND_RECOVER) {
                want = LAND_RECOVER;   // 止まった tick に掴む(制動の羽ばたきの途中から入るので短く補間)
                entry = this.curAnim == LAND ? ENTRY_SHORT : this.curEntry;
            } else {
                want = PERCH_IDLE;   // セーブから止まった姿で読み込んだ個体は land_recover を通さず直に
                entry = ENTRY;
            }
        } else if (m == MODE_TAKEOFF && takeoffPhase() == 1) {
            want = WINDUP;
            entry = ENTRY_SHORT;
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
            want = flapping() ? FLY : GLIDE;
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
