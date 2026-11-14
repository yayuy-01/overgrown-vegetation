package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.util.Mth;
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
 * クリペイムネス {@code clipeimnes}(G5・攻撃特化。11-14 に本名。仮名ハガネカワ、仮の id {@code g5_bulwark})。
 * 腕の盾で矢・剣を弾きながら詰め、腕の鉤で抱え込む、小回りの利く盾持ちの前衛(名簿「Mob の名簿と能力の刷新(11-04)」の G5 の表・
 * 注文書 g5_bulwark。能力は Vault「G4〜G6 の能力の決定(11-14)」の第 1 陣)。
 *
 * <h2>段 1(2026-11-15): 歩くところまで</h2>
 * 移動は「② 大型の地上を走る型」({@link LongBodyRunner})をそのまま使い回す。攻撃・能力はまだ無い。行動はうろつき(と、テストクライアントの navto)だけ。
 * 自然には湧かない(スポーンエッグ・コマンドだけ)。
 *
 * <h3>体の作り(ヴェナムネスとの違い)</h3>
 * 歩く脚は<b>4 本</b>(胸の中脚 1 対 + 腹の後脚 1 対)。どれも 3 骨(付け根 leg → すね shin → ふ節 foot)で横へ張り出す虫の脚。
 * 前の 1 対は盾の腕で、地面に着かない(足の置き場に入れない)。脚の IK は種に依らない {@code client/runner/SprawlLegRig}(11-15 に足した)。
 * 踏み替えは対角の組み(左中 + 右後 / 右中 + 左後)。
 *
 * <h3>クリペイムネスらしさ = 小回り【全部仮・Claude 11-15】</h3>
 * 回る速さ {@link #TURN_PER_TICK} 度/tick を早く出し({@link #TURN_SMOOTHING})、向きが {@link #MOVE_OFF} 度より大きくずれたら進まずに<b>その場で向き直る</b>
 * (ヴェナムネス 85・ファルキムネス 70。小さいほど弧を描かず、狭い所で向きを変えられる)。回る速さの上限は、後脚の足先(体の中心から約 2.9 ブロック)が
 * その場で回る間に踏み替えで付いていける速さから決めた(1 tick に 6 度で足先は 0.3 ブロック/tick 動く)。
 */
public class ClipeimnesEntity extends LongBodyRunner implements GeoEntity, ParasitizedMob {

    public static final String ID = "clipeimnes";

    /** 描画倍率(geo に焼き込まず描画で掛ける。テクスチャは 1 単位 2 テクセルなので、画素はブロックの 1 画素に揃う)。 */
    public static final float RENDER_SCALE = 2.0F;
    /** モデルの 1 単位(px)をブロックへ。 */
    static final double PX = RENDER_SCALE / 16.0D;

    // --- 小回り【仮】 ---
    /** 回る速さ(度/tick)・その場で向き直りだすずれ(度)・角速度を寄せる割合【仮】。 */
    public static final float TURN_PER_TICK = 6.0F, MOVE_OFF = 45.0F, TURN_SMOOTHING = 0.5F;
    /** うろつきの速さ(移動の速さに掛ける)【仮】。 */
    static final double STROLL_SPEED = 0.7D;
    /** 経路の長い体の重み【仮】: 幹の近さはファルキムネス (2.0, 1.0, 0.6, 1.5) と同じに、狭い所の急な曲がりは嫌わない(その場で向き直れる)。 */
    public static final OvergrownNodeEvaluator.LongBody LONG_BODY = new OvergrownNodeEvaluator.LongBody(2.0F, 1.0F, 0.2F, 0.6F);

    // --- 体の作り(モデル prac2_g5_bulwark_r7 = clipeimnes.geo.json の、休めの形の足先 × 描画倍率) ---
    /**
     * 足の置き場(ふ節の箱の一番下の角。ゲームの中で GeckoLib が箱の回転を掛けた所を {@code SprawlLegRig} が測った値 = geo の座標 px で 中左 (17.32, −6.51)・中右 (−16.17, −5.60)・後左 (14.37, 18.33)・後右 (−15.88, 17.36)。
     * geo の +X が体の左、−Z が前)。練習モデルの左右が少し違うので、そのままの値を使う。並びは 中左・中右・後左・後右。
     */
    static final double[] LEG_HOME_LEFT = {17.32D * PX, -16.17D * PX, 14.37D * PX, -15.88D * PX};
    static final double[] LEG_HOME_FWD = {6.51D * PX, 5.60D * PX, -18.33D * PX, -17.36D * PX};
    /** 対角の組み: 中左 + 後右 = 0、中右 + 後左 = 1。 */
    static final int[] LEG_GROUP = {0, 1, 1, 0};
    /**
     * 曲げの付け根: 前は首 neck(z −10.8)・頭 head(−14.4)の 2 つだけ(残り 2 つは骨に当てない。胸は中脚と盾の腕の親なので曲げない)、
     * 後ろは尾 tail1〜tail4(z 14.4・22.2・29.4・36)。腹は後脚の親なので曲げない。尾の先 tail5・tail6 は tail4 と一緒に動く。
     */
    static final double[] BEND_FRONT = {10.8D * PX, 14.4D * PX, 17.0D * PX, 19.0D * PX};
    static final double[] BEND_BACK = {14.4D * PX, 22.2D * PX, 29.4D * PX, 36.0D * PX};

    // --- 足の運び【仮・Claude 11-15】 ---
    /** 踏み替えを始めるずれ(ブロック): 止まりかけ {@link #STEP_MIN} + 速さ × {@link #STEP_PER}、{@link #STEP_MAX} まで。回っている間は {@link #STEP_TURN}。 */
    static final double STEP_MIN = 0.45D, STEP_PER = 4.0D, STEP_MAX = 0.95D, STEP_TURN = 0.55D;   // 11-15: 上限 1.3 では後脚が届く長さを 2 px 越えてから離れた(踏み替えの初めに足先が 0.3 ブロック跳ぶ)
    /** 踏み替えの長さ(tick): 歩き / 回っている間。 */
    static final int SWING_WALK = 7, SWING_TURN = 5;
    /** 先回りの上限(tick)と、回っている間の上限。 */
    static final double LEAD_MAX = 7.0D, LEAD_TURNING = 3.0D;

    // --- アニメの速さ ---
    /**
     * walk(1.4 秒 = 28 tick の手付けの歩き)を速さ 1.0 で流したときの地上速度(ブロック/tick)。遠く(足の置き場が効いていない)の個体だけが使う。
     * 着いている間の付け根の Y の振れ × 足先までの半径で測った(11-15 Claude): 中脚は 14 tick に 32 度・半径 13.7 px → 前後 6.9 px = 0.062 ブロック/tick、
     * 後脚は 14 tick に 26 度・半径 16.8 px → 5.0 px = 0.045 ブロック/tick。<b>手付けの walk は中脚と後脚で足の送りが合っていない</b>(どちらかが 15% ほど滑る)
     * ので、間を取った。近く(足の置き場 + IK)では使わない。
     */
    public static final double WALK_REF = 0.053D;
    static final double LOOP_SPEED_MIN = 0.3D, LOOP_SPEED_MAX = 2.5D;
    /** これ(ブロック/tick)以上前へ進んだ tick は歩き。 */
    static final double WALK_MIN_SPEED = 0.01D;
    /** その場で回っていても歩きを流す回る速さ(度/tick)。 */
    static final float TURN_ANIM_MIN = 1.0F;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ClipeimnesEntity(EntityType<? extends ClipeimnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 35);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    // ------------------------------------------------------------------ 型の値

    @Override
    protected double[] legHomeLeft() {
        return LEG_HOME_LEFT;
    }

    @Override
    protected double[] legHomeFwd() {
        return LEG_HOME_FWD;
    }

    @Override
    protected int[] legGroups() {
        return LEG_GROUP;
    }

    @Override
    protected double[] bendFront() {
        return BEND_FRONT;
    }

    @Override
    protected double[] bendBack() {
        return BEND_BACK;
    }

    @Override
    protected OvergrownNodeEvaluator.LongBody longBodyWeights() {
        return LONG_BODY;
    }

    @Override
    protected float maxTurnPerTick() {
        return TURN_PER_TICK;
    }

    @Override
    protected float turnSmoothing() {
        return TURN_SMOOTHING;
    }

    @Override
    protected float maxMoveOff() {
        return MOVE_OFF;
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    protected double cullingRadius() {
        return 6.2D * this.getScale();   // 尾の先が原点の 6.1 ブロック後ろ
    }

    // ------------------------------------------------------------------ 確かめ用

    /**
     * 確かめ用(テストクライアントだけ): 名前が {@code nostroll_run} で始まる個体は、経路が尽きるたびに 12〜20 ブロック先の陸へ速さ 1.0 で歩く
     * (10 体の重さを測る)。名前に {@code trace} を含む個体は 10 tick ごとに速さと回る速さを書く。
     */
    @Override
    protected void tickRunner() {
        if (!DEV_HOOKS || !this.hasCustomName()) return;
        String name = this.getCustomName().getString();
        if (name.startsWith("nostroll_run") && this.getNavigation().isDone()) {
            net.minecraft.world.phys.Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this, 20, 6);
            if (p != null && p.distanceToSqr(this.position()) > 12.0D * 12.0D) this.getNavigation().moveTo(p.x, p.y, p.z, 1.0D);
        }
        if (name.contains("trace") && this.tickCount % 10 == 0) {
            double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo));
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "CLIP_GAIT %s pos %.1f %.1f %.1f 速さ %.3f 回る %.1f 経路 %s", name, this.getX(), this.getY(), this.getZ(), v,
                    getTurnRate(), this.getNavigation().isDone() ? "なし" : "あり"));
        }
    }

    // ------------------------------------------------------------------ 脚

    @Override
    protected void configurePlanter(FootPlanter fp) {
        fp.lift = 0.45D;       // 宙の足を上げる高さ(ブロック)【仮】
        fp.probeUp = 1.3D;
        fp.probeDown = 2.0D;   // 付け根の高さ 1.5 + 少し
    }

    /** 速いほど大股に。回っている間は小股で速く踏み替える(後脚の足先が大きく振られるため)。 */
    @Override
    protected void tunePlanter(FootPlanter fp) {
        double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo)) / Math.max(this.getScale(), 0.05F);
        boolean turning = Math.abs(getTurnRate()) > 2.0F;
        double step = turning ? STEP_TURN : Mth.clamp(STEP_MIN + v * STEP_PER, STEP_MIN, STEP_MAX);
        fp.stepAt = step;
        fp.swingTicks = turning ? SWING_TURN : SWING_WALK;
        fp.restGap = 0;
        fp.lead = Mth.clamp(step * 0.5D / Math.max(v, 0.01D), 0.0D, turning ? LEAD_TURNING : LEAD_MAX);
    }

    private static double sq(double v) {
        return v * v;
    }

    // ------------------------------------------------------------------ アニメ

    /**
     * アニメは 1 つのコントローラ。前へ進むか回っていれば walk(盾の腕・胸・腹・尾の揺れも入っている)、止まっていれば idle。
     * 近くで足の置き場が効いている間も walk を流す(脚は IK が休めの形を基準に上書きする)。遠くの個体は walk を実際の速さに合わせて流す。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 4, this::mainState));
    }

    private PlayState mainState(AnimationState<ClipeimnesEntity> state) {
        AnimationController<ClipeimnesEntity> c = state.getController();
        c.setAnimationSpeed(1.0D);
        double ahead = clientForwardSpeed();
        boolean turning = Math.abs(getTurnRate()) >= TURN_ANIM_MIN;
        if (!this.isDeadOrDying() && (ahead >= WALK_MIN_SPEED || turning)) {
            double v = Math.max(ahead, turning ? WALK_REF * 0.6D : 0.0D);
            c.setAnimationSpeed(Mth.clamp(v / (WALK_REF * this.getScale()), LOOP_SPEED_MIN, LOOP_SPEED_MAX));
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
