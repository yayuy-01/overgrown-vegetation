package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.forest.ForestMap;
import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.server.level.ServerLevel;
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
 * ヴェナムネス {@code venamnes}(G5・攻撃特化。11-14 に本名。仮名オイカケ、仮の id {@code g5_pursuer})。G5 の原型: 逃げる人を速く走って追い詰め、口で噛む。
 * ファルキムネスより脆く、長く追う「足の速さ」の Mob(名簿「Mob の名簿と能力の刷新(11-04)」の G5 の表・注文書 g5_pursuer)。
 *
 * <h2>段 1(2026-11-14): 歩くところまで</h2>
 * 移動は「② 大型の地上を走る型」({@link LongBodyRunner}。ファルキムネスの作りの写し)を使い回す。攻撃・能力はまだ無い(次の段で相談)。
 * 行動はうろつき(と、テストクライアントの navto)だけ。自然には湧かない(スポーンエッグ・コマンドだけ)。
 *
 * <h3>ヴェナムネスらしさ = 速さ【全部仮・Claude 11-14】</h3>
 * <ul>
 *   <li><b>開けた所で速い</b>: 森の地図({@link ForestMap})で体の前 0・4 ブロックの幹までの距離を 5 tick ごとに見て、開けているほど移動の速さに
 *       最大 {@link #SPRINT_OPEN} 倍を掛ける(回っている間は弱める)。うろつき(経路の速さ倍率 1 未満)では掛けない。</li>
 *   <li><b>森の中でも足が止まりにくい</b>: 回る速さ {@link #TURN_PER_TICK} 度/tick(ファルキムネス 7)、その場で回りだす向きのずれ
 *       {@link #MOVE_OFF} 度(同 70)、向きがずれていても前へ {@link #TURN_ADVANCE} は進む(同 0)。経路の長い体の重みは軽め({@link #LONG_BODY})。</li>
 * </ul>
 *
 * <h3>脚</h3>
 * 二足(太もも → すね → ふ節 → 足)。近くで描かれていて遅い間({@link #IK_MAX_SPEED} 未満)は足の置き場({@link FootPlanter})+ IK、
 * 速く走る間は手付けの歩き(walk)を実際の速さに合わせて流す(足の置き場は二足の宙に浮く走りを持たないので)。
 */
public class VenamnesEntity extends LongBodyRunner implements GeoEntity, ParasitizedMob {

    public static final String ID = "venamnes";

    /** 描画倍率(geo に焼き込まず描画で掛ける。テクスチャは 1 単位 2.5 テクセルなので、画素はブロックの 1 画素に揃う)。 */
    public static final float RENDER_SCALE = 2.5F;
    /** モデルの 1 単位(px)をブロックへ。 */
    static final double PX = RENDER_SCALE / 16.0D;

    // --- 速さ【仮】 ---
    /** 開けた所で移動の速さに掛ける倍率の上限【仮】。 */
    public static final double SPRINT_OPEN = 1.45D;
    /** 幹までの距離がこれ以下なら倍率 1、{@link #OPEN_CLEAR} 以上で上限(ブロック)【仮】。森の地図の上限は 6。 */
    static final double FOREST_CLEAR = 2.0D, OPEN_CLEAR = 5.0D;
    /** 倍率が目標へ寄る速さ(1 tick あたり)【仮】。 */
    static final double SPRINT_RATE = 0.04D;
    /** 回っている間の倍率の弱め: 1 tick にこの角度回っていれば倍率 1 へ戻す(度)【仮】。 */
    static final double SPRINT_TURN_DROP = 8.0D;
    /** 回る速さ(度/tick)・その場で回りだすずれ(度)・ずれていても進む割合【仮】。 */
    static final float TURN_PER_TICK = 10.0F, MOVE_OFF = 85.0F, TURN_ADVANCE = 0.35F;
    /** うろつきの速さ(移動の速さに掛ける)【仮】。 */
    static final double STROLL_SPEED = 0.55D;
    /** 経路の長い体の重み【仮】: ファルキムネス (2.0, 1.0, 0.6, 1.5) より軽く、曲がりを嫌わない。 */
    public static final OvergrownNodeEvaluator.LongBody LONG_BODY = new OvergrownNodeEvaluator.LongBody(1.8F, 0.8F, 0.35F, 1.3F);

    // --- 体の作り(モデル prac2_g5_pursuer_r9 の骨の位置 × 描画倍率) ---
    /** 足の置き場: 左右 ±4.5 px、前 0.87 px(足の裏の中心)。 */
    static final double[] LEG_HOME_LEFT = {4.5D * PX, -4.5D * PX};
    static final double[] LEG_HOME_FWD = {0.87D * PX, 0.87D * PX};
    /** 0 = 左脚、1 = 右脚(交互に踏み替える)。 */
    static final int[] LEG_GROUP = {0, 1};
    /** 曲げの付け根: 首 neck0・neck1・neck2・頭 head(z −10・−13.5・−17・−20)、尾 tail0〜tail3(z 4・9・14・18.5)。 */
    static final double[] BEND_FRONT = {10.0D * PX, 13.5D * PX, 17.0D * PX, 20.0D * PX};
    static final double[] BEND_BACK = {4.0D * PX, 9.0D * PX, 14.0D * PX, 18.5D * PX};

    // --- 脚の手続きと手付けの切り替え・アニメの速さ ---
    /** これより速い(ブロック/tick)と手付けの歩き(走り)にする / これより遅くなったら足の置き場へ戻す【仮】。 */
    public static final double IK_MAX_SPEED = 0.20D, IK_BACK_SPEED = 0.16D;
    /**
     * walk を速さ 1.0 で流したときの地上速度(ブロック/tick)。FK で踏む足の動きを測った値: 着いている間に 6.1 px を 0.225 秒で
     * 後ろへ送る(平均 0.21、真ん中 0.255)。【測り 11-14 Claude】
     */
    public static final double WALK_REF = 0.22D;
    static final double LOOP_SPEED_MIN = 0.3D, LOOP_SPEED_MAX = 2.5D;
    /** これ(ブロック/tick)以上前へ進んだ tick は歩き。 */
    static final double WALK_MIN_SPEED = 0.01D;
    /** その場で回っていても歩きを流す回る速さ(度/tick)。旋回の足踏みのアニメはまだ無い。 */
    static final float TURN_ANIM_MIN = 1.5F;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** 開けた所の倍率(サーバー)。 */
    private double sprint = 1.0D;
    /** 倍率の目標(森の地図を聞いた tick に決め、間の tick は使い回す)。 */
    private double sprintGoal = 1.0D;
    /** 森の地図を聞く間隔(tick)【仮】。 */
    static final int SPRINT_LOOK_EVERY = 5;
    /** クライアント: 手付けの走りにしているか(速さのヒステリシス)。 */
    private boolean keyedRun;
    /** 計測用(サーバー): 速さの倍率を求めた時間。 */
    public static volatile long sprintNanos, sprintCalls;

    public VenamnesEntity(EntityType<? extends VenamnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 30);
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
    protected float maxMoveOff() {
        return MOVE_OFF;
    }

    @Override
    protected float minTurnAdvance() {
        return TURN_ADVANCE;
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    protected double cullingRadius() {
        return 4.5D * this.getScale();
    }

    @Override
    protected double gaitBoost() {
        return this.sprint;
    }

    public double sprintFactor() {
        return this.sprint;
    }

    // ------------------------------------------------------------------ 速さ

    /** 開けた所の倍率を目標へ寄せる(サーバー、移動の後)。 */
    @Override
    protected void tickRunner() {
        long t0 = System.nanoTime();
        double target = 1.0D;
        boolean hurrying = currentSpeedModifier() >= 1.0D && this.onGround();
        if (hurrying && this.tickCount % SPRINT_LOOK_EVERY != 0) {
            target = this.sprintGoal;   // 森の地図を聞くのは 5 tick に 1 回(裏のスレッドを混ませない)
        } else if (hurrying && this.level() instanceof ServerLevel sl) {
            ForestMap map = ForestMap.of(sl);
            float yaw = this.getYRot() * Mth.DEG_TO_RAD;
            double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
            int y = this.getBlockY();
            double clear = Double.MAX_VALUE;
            boolean known = false;
            for (double ahead = 0.0D; ahead <= 4.0D; ahead += 4.0D) {
                float c = map.clearance(Mth.floor(this.getX() + fx * ahead), y, Mth.floor(this.getZ() + fz * ahead));
                if (Float.isNaN(c)) continue;
                known = true;
                clear = Math.min(clear, c);
            }
            if (known) {
                target = sprintTarget(clear, getTurnRate());
            } else {
                target = this.sprint;   // 地図がまだ無い: 今のまま
            }
        }
        this.sprintGoal = target;
        this.sprint += Mth.clamp(target - this.sprint, -SPRINT_RATE * 2.0D, SPRINT_RATE);   // 落とすのは速く
        sprintNanos += System.nanoTime() - t0;
        sprintCalls++;
        if (DEV_HOOKS && this.hasCustomName()) devHooks(this.getCustomName().getString());
    }

    /**
     * 確かめ用(テストクライアントだけ): 名前が {@code nostroll_run} で始まる個体は、経路が尽きるたびに 16〜28 ブロック先の陸へ
     * 速さ 1.0 で走る(10 体の重さを測る・走る姿を撮る)。名前に {@code trace} を含む個体は 10 tick ごとに速さと倍率を書く。
     */
    private void devHooks(String name) {
        if (name.startsWith("nostroll_run") && this.getNavigation().isDone()) {
            net.minecraft.world.phys.Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this, 28, 6);
            if (p != null && p.distanceToSqr(this.position()) > 16.0D * 16.0D) this.getNavigation().moveTo(p.x, p.y, p.z, 1.0D);
        }
        if (name.contains("trace") && this.tickCount % 10 == 0) {
            double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo));
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "VEN_GAIT %s pos %.1f %.1f %.1f 速さ %.3f 倍率 %.2f 回る %.1f 経路 %s", name, this.getX(), this.getY(), this.getZ(), v, this.sprint,
                    getTurnRate(), this.getNavigation().isDone() ? "なし" : "あり"));
        }
    }

    /**
     * 開けた所の倍率の目標: 体の前の幹までの一番近い距離 {@code clear}(ブロック)が {@link #FOREST_CLEAR} 以下で 1、{@link #OPEN_CLEAR} 以上で
     * {@link #SPRINT_OPEN}(間はなめらかに)。1 tick に {@link #SPRINT_TURN_DROP} 度回っていれば 1。
     */
    public static double sprintTarget(double clear, float turnRate) {
        double open = Mth.clamp((clear - FOREST_CLEAR) / (OPEN_CLEAR - FOREST_CLEAR), 0.0D, 1.0D);
        open = open * open * (3.0D - 2.0D * open);
        double turning = Mth.clamp(1.0D - Math.abs(turnRate) / SPRINT_TURN_DROP, 0.0D, 1.0D);
        return 1.0D + (SPRINT_OPEN - 1.0D) * open * turning;
    }

    // ------------------------------------------------------------------ 脚

    @Override
    protected void configurePlanter(FootPlanter fp) {
        fp.lift = 0.45D;       // 宙の足を上げる高さ(ブロック)【仮】
        fp.probeUp = 1.3D;
        fp.probeDown = 2.2D;   // 腰の高さ 1.6 + 少し
    }

    @Override
    protected void tunePlanter(FootPlanter fp) {
        double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo));
        // 速いほど大股・早めに踏み替える【仮】。二足なので片足が宙の間はもう片足が支える
        fp.stepAt = 0.35D + v * 2.0D;
        fp.lead = 5.0D;
    }

    private static double sq(double v) {
        return v * v;
    }

    @Override
    protected boolean legsFree() {
        if (!super.legsFree()) return false;
        if (!this.level().isClientSide()) return true;
        // 速く走る間は手付けの歩き(足の置き場は二足の宙に浮く走りを持たない)。ヒステリシスで切り替える
        double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo));
        if (this.keyedRun) {
            if (v < IK_BACK_SPEED) this.keyedRun = false;
        } else if (v > IK_MAX_SPEED) {
            this.keyedRun = true;
        }
        return !this.keyedRun;
    }

    // ------------------------------------------------------------------ アニメ

    /**
     * アニメは 1 つのコントローラ(脚・首・尾が同じアニメに入っている)。前へ進んでいれば walk を実際の速さに合わせて流し
     * (位相を積み上げるコントローラなので速さを変えても巻き戻らない)、止まっていれば idle。脚の IK が効いている間も walk を流す
     * (首・尾の揺れはそのまま、脚は IK が休めの形を基準に上書きする)。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 4, this::mainState));
    }

    private PlayState mainState(AnimationState<VenamnesEntity> state) {
        AnimationController<VenamnesEntity> c = state.getController();
        c.setAnimationSpeed(1.0D);
        double ahead = clientForwardSpeed();
        boolean turning = Math.abs(getTurnRate()) >= TURN_ANIM_MIN;
        if (!this.isDeadOrDying() && (ahead >= WALK_MIN_SPEED || turning)) {
            double v = Math.max(ahead, turning ? WALK_REF * 0.5D : 0.0D);
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
