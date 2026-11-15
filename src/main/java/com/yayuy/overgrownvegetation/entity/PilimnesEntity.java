package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import com.yayuy.overgrownvegetation.entity.lizard.LizardRunner;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
 * ピリムネス {@code pilimnes}(G5・地上の遠距離。11-14 に本名。仮名ナゲトゲ、仮の id {@code g5_lancer}。pilum = 投槍)。
 * 人の弓に対抗して、背の葉を棘に変え、葉枕(水圧の関節)で弾き飛ばして離れた人の足を止め、近づいて T 字の口で仕留める(注文書 g5_lancer)。
 * 体の土台は背に棘の帆を持つ這う爬虫類(エダフォサウルス)。
 *
 * <h2>段 1(2026-11-15): 歩く・走るところまで</h2>
 * 移動は「トカゲ型」({@link LizardRunner}。この Mob のために作った)。低く這い、横へ張った 4 本の脚を足の置き場 + IK で置き、背骨を左右にくねらせ、
 * <b>3〜6 ブロック走っては 0.5〜1.5 秒止まって見回す</b>。止まる所は幹・岩の脇へ寄せる。2 ブロックの段は跳び上がる。地上だけ(幹へ張り付かない)。
 * 攻撃・能力(棘の弾き・扇の散らし撃ち・噛みつき・棘の雨)はまだ無い。行動はうろつき(と、テストクライアントの navto)だけ。自然には湧かない。
 *
 * <h3>アニメ(段 2: 11-15 にアニメのセッションが walk を直し、run・look_around・look_around_r を足した。ユーザーが Blockbench で了承)</h3>
 * kamae・idle・walk・run・look_around・look_around_r。近く(足の置き場が効いている)では、歩いている間も idle を流し、脚・胴の高さ・
 * 背骨のくねりはコードが足す。遠くで進む間は、走り出すときに決めた walk か run を実際の速さに合わせて流す。止まっている間は look_around
 * (近くも遠くも。脚・胸にキーが無い)。見回しをアニメとコードのどちらでするかは {@link #LOOK_BY_ANIMATION}。
 */
public class PilimnesEntity extends LizardRunner implements GeoEntity, ParasitizedMob {

    public static final String ID = "pilimnes";

    /** 描画倍率(geo に焼き込まず描画で掛ける。テクスチャは 1 単位 2 テクセルなので、画素はブロックの 1 画素に揃う)。 */
    public static final float RENDER_SCALE = 2.0F;
    /** モデルの 1 単位(px)をブロックへ。 */
    static final double PX = RENDER_SCALE / 16.0D;

    // --- 速さ・旋回【仮・Claude 11-15】 ---
    /** 回る速さ(度/tick)・その場で回りだすずれ(度)。這う体はその場で素早く向きを変える。 */
    static final float TURN_PER_TICK = 14.0F, MOVE_OFF = 60.0F;
    /** うろつきの速さ(移動の速さ 0.30 に掛ける。地面の速さ ≈ 2.16 × (0.30 × 0.85)² ≈ 0.14 ブロック/tick)。 */
    static final double STROLL_SPEED = 0.85D;
    /** 経路の長い体の重み(ヴェナムネスと同じ)。 */
    public static final OvergrownNodeEvaluator.LongBody LONG_BODY = new OvergrownNodeEvaluator.LongBody(1.8F, 0.8F, 0.35F, 1.3F);
    /** 跳ぶ力(2 ブロックの段を跳び上がる。高さ ≈ 2.5)。 */
    public static final double JUMP_STRENGTH = 0.62D;

    // --- 体の作り(モデル prac2_g5_lancer_r7 の骨の位置 × 描画倍率) ---
    /**
     * 足の置き場(左前・右前・左後ろ・右後ろ): 前の爪は左右 ±12 px・前 12 px(休めの爪の位置)、後ろの足は左右 ±13 px・後ろ 8.9 px。
     */
    static final double[] LEG_HOME_LEFT = {12.0D * PX, -12.0D * PX, 13.0D * PX, -13.0D * PX};
    static final double[] LEG_HOME_FWD = {12.0D * PX, 12.0D * PX, -8.9D * PX, -8.9D * PX};
    /** 曲げの付け根: 首 neck0・neck1・neck2・頭 head(z −10・−17・−20・−23)、尾 tail0〜tail3(z 12・19・25.5・31.5)。 */
    static final double[] BEND_FRONT = {10.0D * PX, 17.0D * PX, 20.0D * PX, 23.0D * PX};
    static final double[] BEND_BACK = {12.0D * PX, 19.0D * PX, 25.5D * PX, 31.5D * PX};

    // --- 足の運び【仮・Claude 11-15】 ---
    /** 着いている足が流れる距離(ブロック)= 下限 + 速さ × 係数、上限まで。脚は横へ張っているので前後へ届くのは ±0.8 ほど。 */
    static final double STEP_MIN = 0.3D, STEP_PER = 3.0D, STEP_MAX = 0.75D;
    /** 踏み替えの長さ(tick)= 着いている時間 × 割合、下限〜上限。 */
    static final double SWING_SHARE = 0.6D;
    static final int SWING_MIN = 3, SWING_MAX = 7;
    /** 先回りの上限(tick)と、回っている間の上限。 */
    static final double LEAD_MAX = 6.0D, LEAD_TURNING = 2.0D;

    // --- アニメの速さ ---
    /** walk・run を速さ 1.0 で流したときの地上速度(ブロック/tick)。11-15 段 2 のアニメがこの値に合わせて作られた(walk 20 tick・run 12 tick で 1 巡)。 */
    public static final double WALK_REF = 0.10D, RUN_REF = 0.194D;
    /**
     * 止まっている間の見回しを look_around(アニメ)でするか、コードの見回し({@code SpineWave})でするか【仮・11-15 段 2】。
     * 【決定 11-15 ユーザー】アニメ版を残す(true で確定)。コードの見回しは使わない・切り替え用に残してある(二重にならないよう片方だけが効く)。
     */
    public static final boolean LOOK_BY_ANIMATION = true;
    static final double LOOP_SPEED_MIN = 0.3D, LOOP_SPEED_MAX = 3.0D;
    /** これ(ブロック/tick)以上前へ進んだ tick は歩き。 */
    static final double WALK_MIN_SPEED = 0.01D;
    static final float TURN_ANIM_MIN = 1.5F;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    /** 見回し(首を上げ、さっと左 → 右 → 正面。最後のコマで止まる)と、その左右逆。 */
    private static final RawAnimation LOOK = RawAnimation.begin().thenPlay("look_around");
    private static final RawAnimation LOOK_R = RawAnimation.begin().thenPlay("look_around_r");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public PilimnesEntity(EntityType<? extends PilimnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 30);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID).add(Attributes.JUMP_STRENGTH, JUMP_STRENGTH);
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
    protected float maxMoveOff() {
        return MOVE_OFF;
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    protected double cullingRadius() {
        return 5.0D * this.getScale();
    }

    // ------------------------------------------------------------------ 脚

    @Override
    protected void configurePlanter(FootPlanter fp) {
        fp.lift = 0.3D;        // 宙の足を上げる高さ(ブロック)【仮】。這う脚は低く運ぶ
        fp.probeUp = 1.1D;
        fp.probeDown = 1.8D;   // 胴の高さ 1.4 + 少し
    }

    /** 速いほど大きく・速く踏み替える(対角の 2 本ずつ)。 */
    @Override
    protected void tunePlanter(FootPlanter fp) {
        double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo)) / Math.max(this.getScale(), 0.05F);
        boolean turning = Math.abs(getTurnRate()) > 3.0F;
        double step = Mth.clamp(STEP_MIN + v * STEP_PER, STEP_MIN, STEP_MAX);
        double stance = v > 0.01D ? step * 2.0D / v : 40.0D;   // 足が着いている tick(着いてから離れるまでに 2 × step 流れる)
        fp.stepAt = step;
        fp.swingTicks = Mth.clamp((int) Math.round(stance * SWING_SHARE), SWING_MIN, turning ? SWING_MIN : SWING_MAX);
        fp.lead = Mth.clamp(step / Math.max(v, 0.01D), 0.0D, turning ? LEAD_TURNING : LEAD_MAX);
    }

    // ------------------------------------------------------------------ アニメ

    /**
     * アニメは 1 つのコントローラ。
     * <ul>
     *   <li>止まって見回す間({@link #isPaused()}、近くも遠くも): {@link #lookByAnimation()} なら look_around か look_around_r を頭から 1 回
     *       (最後のコマで止まる。止まる長さ 10〜30 tick のどこで切ってもよい作り)。左右は個体の id と止まった回数で交互に。</li>
     *   <li>近く(足の置き場が効いている): idle(脚・胴の高さ・背骨のくねりはコードが足す)。</li>
     *   <li>遠くで進む間: 走り出すときに決めた 1 本({@link #isDashRun()} なら run、でなければ walk)を、実際の速さに合わせて流す
     *       (位相を積み上げるコントローラなので、速さを変えても巻き戻らない)。走り出し・止まり際も同じ 1 本のまま倍率が速さに付いていく。</li>
     * </ul>
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 4, this::mainState));
    }

    private PlayState mainState(AnimationState<PilimnesEntity> state) {
        AnimationController<PilimnesEntity> c = state.getController();
        c.setAnimationSpeed(1.0D);
        if (!this.isDeadOrDying() && isPaused() && lookByAnimation()) {
            return state.setAndContinue(((this.getId() + pauseCount()) & 1) == 0 ? LOOK : LOOK_R);
        }
        double ahead = clientForwardSpeed();
        boolean turning = Math.abs(getTurnRate()) >= TURN_ANIM_MIN;
        if (!this.isDeadOrDying() && !proceduralLegs() && (ahead >= WALK_MIN_SPEED || turning)) {
            if (isDashRun() && ahead >= WALK_MIN_SPEED) {
                c.setAnimationSpeed(Mth.clamp(ahead / (RUN_REF * this.getScale()), LOOP_SPEED_MIN, LOOP_SPEED_MAX));
                return state.setAndContinue(RUN);
            }
            double v = Math.max(ahead, turning ? WALK_REF * 0.5D : 0.0D);
            c.setAnimationSpeed(Mth.clamp(v / (WALK_REF * this.getScale()), LOOP_SPEED_MIN, LOOP_SPEED_MAX));
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    /**
     * 見回しをアニメ(look_around)でするか。既定は {@link #LOOK_BY_ANIMATION}。確かめ用(テストクライアントだけ): 名前に {@code codelook} を含む個体は
     * コードの見回し、{@code animlook} を含む個体はアニメの見回し。
     */
    @Override
    public boolean lookByAnimation() {
        if (DEV_HOOKS && this.hasCustomName()) {
            String n = this.getCustomName().getString();
            if (n.contains("codelook")) return false;
            if (n.contains("animlook")) return true;
        }
        return LOOK_BY_ANIMATION;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
