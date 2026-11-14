package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.leap.AmbushSpots;
import com.yayuy.overgrownvegetation.entity.leap.LeapMotion;
import com.yayuy.overgrownvegetation.entity.leap.LeapPlan;
import com.yayuy.overgrownvegetation.entity.leap.LeapPlanner;
import com.yayuy.overgrownvegetation.entity.leap.LeapSpec;
import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.EnumSet;

/**
 * サルティムネス {@code saltimnes}(G5・跳ぶ型・待ち伏せ。11-14 に本名。仮名ハネフセ、仮の id {@code g5_leaper})。
 * 地面の起伏に化けて伏せ、水圧(オジギソウの葉枕)で一気に跳ぶ。柵・低い塀は跳び越える(「G4〜G6 の能力の決定(11-14)」)。
 *
 * <h2>段 1(2026-11-14): 歩く・伏せる・跳ぶまで</h2>
 * 攻撃(跳び抱え・蹴り・連続跳躍)と構え {@code kamae}・発光はまだ無い。自然には湧かない(スポーンエッグ・コマンドだけ)。
 * <ul>
 *   <li><b>伏せる</b>({@link AmbushGoal}): まわり 16 ブロックから伏せる場所を選び({@link AmbushSpots})、歩いて行き、通り道へ顔を向けて
 *       {@link #HIDE_MIN}〜{@link #HIDE_MAX} tick 動かない。伏せた姿は待機のアニメ {@code idle}(地面の起伏の姿。腹が 4 秒ごとに息をするように膨らむ)。</li>
 *   <li><b>歩く</b>: 低い這い歩き。体の向きと経路は「② 大型の地上を走る型」の土台({@link LongBodyRunner}: 向きは少しずつ回す・長い体の経路)。
 *       脚の手続き(IK)・体の曲げは使わない(手付けの walk を実際の速さに合わせて流す)。</li>
 *   <li><b>跳ぶ</b>(「跳ぶ型」{@code entity/leap/}): 伏せ場所への経路が柵・塀・高い段差・水の溝で切れた / 大回りになるときは、その向こうへ跳ぶ。</li>
 *   <li>確かめ用: 名前が {@code hop} の個体は {@link #HOP_EVERY} tick ごとに近くへ跳ぶ(ユーザーのクライアントでも効く)。{@code nostroll} で始まる個体は伏せ場所を探さない。</li>
 * </ul>
 */
public class SaltimnesEntity extends LongBodyRunner implements GeoEntity, ParasitizedMob {

    public static final String ID = "saltimnes";

    /** 描画倍率(geo に焼き込まず描画で掛ける。テクスチャは 1 単位 2.5 テクセル = 画素はブロックの 1 画素)。 */
    public static final float RENDER_SCALE = 2.5F;
    /** モデルの 1 単位(px)をブロックへ。 */
    static final double PX = RENDER_SCALE / 16.0D;

    /**
     * 跳びの値【仮・Claude 11-14、ユーザー了承の案】: 水平 3〜10 ブロック・上へ 3・下へ 10。頂点は高いほうの端から 2.5〜5.5 ブロック上げる
     * (平らな所の 6 ブロックの跳びは頂点 2.5・宙 16 tick。練習モデルの leap は 6.25 ブロック先・頂点 3.4・宙 21 tick で、重力 0.08 なら頂点 3.4 は宙 18 tick)。
     * 体は芯の中心から鼻先まで 20.9 px・腹の先まで 25.4 px(prac2_g5_leaper_r6 の外接)。身を沈める 10 tick = 補間 3 tick + leap の蹴るまで 0.35 秒。着地 8 tick。
     */
    public static final LeapSpec LEAP = new LeapSpec(3.0D, 10.0D, 3.0D, 10.0D, 2.5D, 5.5D,
            20.9D * PX, 25.4D * PX, 0.6D, 0.08D, 10, 8);

    // --- 歩き【仮】 ---
    /** 回る速さ(度/tick)・その場で回りだすずれ(度)。体長 7 ブロックの這う体なのでゆっくり。 */
    static final float TURN_PER_TICK = 4.0F, MOVE_OFF = 60.0F;
    /** うろつき・伏せ場所へ行く速さ(移動の速さに掛ける)。地面で約 0.045 ブロック/tick の見込み。 */
    static final double STROLL_SPEED = 0.72D;
    /** 経路の長い体の重み【仮】(ヴェナムネスと同じ)。 */
    static final OvergrownNodeEvaluator.LongBody LONG_BODY = new OvergrownNodeEvaluator.LongBody(1.8F, 0.8F, 0.35F, 1.3F);
    /**
     * walk を速さ 1.0 で流したときの地上速度(ブロック/tick)【測り 11-14】: 練習モデルの walk(2.4 秒)を FK で見て、
     * 地面に着いている中脚が後ろへ送られる速さ 0.018〜0.034(左右で違う)の中ほど。前脚は宙に上げたまま、後脚はほとんど送らない手付けなので、どこかの脚は少し滑る。
     */
    public static final double WALK_REF = 0.03D;
    static final double LOOP_SPEED_MIN = 0.3D, LOOP_SPEED_MAX = 2.5D, WALK_MIN_SPEED = 0.005D;
    static final float TURN_ANIM_MIN = 1.0F;
    /** leap の宙の部分(0.35〜1.4 秒)の長さ(tick)。実際の宙の tick 数に合わせて流す速さを変える。 */
    static final double LEAP_ANIM_AIR_TICKS = 21.0D;

    // --- 伏せ【仮】 ---
    /** 伏せたまま動かない長さ(tick): 1〜3 分。 */
    public static final int HIDE_MIN = 1200, HIDE_MAX = 3600;
    /** 確かめ用 hop: 跳ぶ間隔(tick)と距離(ブロック)。 */
    public static final int HOP_EVERY = 200;
    static final double HOP_MIN = 5.0D, HOP_MAX = 9.0D;

    private static final EntityDataAccessor<Integer> DATA_LEAP = SynchedEntityData.defineId(SaltimnesEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation LEAP_ANIM = RawAnimation.begin().thenPlayAndHold("leap");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private final LeapMotion leap = new LeapMotion(LEAP);
    @Nullable
    private AmbushGoal ambush;

    public SaltimnesEntity(EntityType<? extends SaltimnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 30);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    // ------------------------------------------------------------------ 型の値(脚の手続き・曲げは使わないので形だけ)

    private static final double[] NO_LEG = {0.0D};
    private static final int[] NO_GROUP = {0};
    private static final double[] NO_BEND = {0.5D, 1.0D, 1.5D, 2.0D};

    @Override
    protected double[] legHomeLeft() {
        return NO_LEG;
    }

    @Override
    protected double[] legHomeFwd() {
        return NO_LEG;
    }

    @Override
    protected int[] legGroups() {
        return NO_GROUP;
    }

    @Override
    protected double[] bendFront() {
        return NO_BEND;
    }

    @Override
    protected double[] bendBack() {
        return NO_BEND;
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
        return 4.5D * this.getScale();
    }

    /** 脚の手続き・体の曲げは使わない(固い体。脚は手付けのアニメ)。 */
    @Override
    protected boolean legsFree() {
        return false;
    }

    @Override
    public boolean isActing() {
        return this.leap.active();
    }

    // ------------------------------------------------------------------ 登録

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_LEAP, 0);
    }

    /** 浮く・確かめ用の hop・伏せ場所を探して伏せる、だけ(段 1)。辺りを見回さない(伏せている間に体ごと回らない)。 */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(4, new HopGoal());
        this.ambush = new AmbushGoal();
        this.goalSelector.addGoal(5, this.ambush);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void aiStep() {
        boolean server = !this.level().isClientSide();
        if (server) {
            this.leap.preMove(this, this.getYRot());
            if (this.leap.phase() == LeapMotion.CROUCH) wantFacing(this.leap.yaw(), TURN_ACTION);
        }
        super.aiStep();
        if (server) {
            this.leap.postMove(this);
            this.entityData.set(DATA_LEAP, leapSyncValue());
        }
    }

    /** 身を沈める段は、向きが揃って数え始めてから送る(回っている間は歩きのアニメ)。 */
    private int leapSyncValue() {
        int v = this.leap.encode();
        if (this.leap.phase() == LeapMotion.CROUCH && !this.leapCounting()) v &= ~7;
        return v;
    }

    private boolean leapCounting() {
        return Math.abs(Mth.wrapDegrees(this.leap.yaw() - this.getYRot())) <= LeapMotion.ALIGN_DEG;
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        if (this.leap.active()) return false;   // 自分の跳びでは落下ダメージを受けない
        return super.causeFallDamage(fallDistance, multiplier, source);
    }

    // ------------------------------------------------------------------ 跳ぶ(外から・テストから)

    public LeapMotion leapMotion() {
        return this.leap;
    }

    /** {@code aim} の近くへ跳ぶ(跳べる弧が見つかれば true)。 */
    public boolean leapToward(Vec3 aim) {
        if (this.leap.active() || !this.onGround()) return false;
        LeapPlan p = LeapPlanner.plan(this, aim, LEAP);
        if (p == null) return false;
        this.leap.start(this, p);
        return true;
    }

    /** 伏せているか(段 1 では見た目は待機のアニメのまま。次の段の跳び抱えが使う)。 */
    public boolean isHidden() {
        return this.ambush != null && this.ambush.state == AmbushGoal.HIDE && this.ambush.aligned;
    }

    /** テスト用: 伏せ場所をこの場所に決めて向かわせる。 */
    public void setAmbushSpotForTests(Vec3 feet, float yaw) {
        if (this.ambush != null) this.ambush.goTo(new AmbushSpots.Spot(feet, yaw, 0.0D));
    }

    /** 名前(確かめ用の口)。 */
    private String devName() {
        return this.hasCustomName() ? this.getCustomName().getString() : "";
    }

    // ------------------------------------------------------------------ Goal

    /** 確かめ用: 名前が {@code hop} の個体は {@link #HOP_EVERY} tick ごとに、前寄り(左右 90 度まで)の 5〜9 ブロック先へ跳ぶ。 */
    final class HopGoal extends Goal {
        private int wait = 40;

        HopGoal() {
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            return devName().equals("hop");
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            SaltimnesEntity m = SaltimnesEntity.this;
            if (m.leap.active() || --this.wait > 0) return;
            this.wait = HOP_EVERY;
            for (int i = 0; i < 8; i++) {
                float yaw = m.getYRot() + (m.getRandom().nextFloat() * 2.0F - 1.0F) * 90.0F;
                double d = HOP_MIN + m.getRandom().nextDouble() * (HOP_MAX - HOP_MIN);
                float r = yaw * Mth.DEG_TO_RAD;
                Vec3 aim = m.position().add(-Mth.sin(r) * d, 0.0D, Mth.cos(r) * d);
                if (m.leapToward(aim)) return;
            }
            this.wait = 20;   // どこへも跳べなかった: すぐもう一度
        }
    }

    /**
     * 伏せ場所を選ぶ → 歩いて行く(経路が切れる・大回りなら跳ぶ)→ 通り道へ顔を向けて伏せる → しばらくして別の場所へ。
     * プレイヤー(創造・観戦も)が {@link LongBodyRunner#STROLL_RANGE} 以内にいる間だけ動く(重さ)。
     */
    final class AmbushGoal extends Goal {
        static final int PICK = 0, GO = 1, HIDE = 2;
        /** 着いたとみなす距離(ブロック)・歩いても進まないとみなす間(tick)と距離・行くのをあきらめる長さ(tick)【仮】。 */
        static final double ARRIVE = 2.0D, STUCK_MOVE = 0.6D;
        static final int STUCK_TICKS = 60, GO_GIVE_UP = 1200;
        /** 経路の長さがまっすぐの距離のこれ倍 + 8 を超えたら大回り = 跳んで越える。 */
        static final double DETOUR_RATIO = 2.0D;

        int state = PICK;
        @Nullable
        AmbushSpots.Spot spot;
        int pickWait = 40, goTicks, hideLeft, failedLeaps;
        boolean aligned;
        private Vec3 checkPos = Vec3.ZERO;
        private int checkTick;
        private boolean pathChecked;

        AmbushGoal() {
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            String n = devName();
            if (n.startsWith("nostroll") || n.equals("hop")) return false;
            return SaltimnesEntity.this.playerWithin(STROLL_RANGE) || this.state != PICK;   // 行く・伏せるは途中でやめない
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        void goTo(AmbushSpots.Spot s) {
            SaltimnesEntity m = SaltimnesEntity.this;
            this.spot = s;
            this.state = GO;
            this.goTicks = 0;
            this.aligned = false;
            this.failedLeaps = 0;
            this.pathChecked = false;
            this.checkPos = m.position();
            this.checkTick = 0;
            m.getNavigation().moveTo(s.feet().x, s.feet().y, s.feet().z, STROLL_SPEED);
        }

        @Override
        public void tick() {
            SaltimnesEntity m = SaltimnesEntity.this;
            if (m.leap.active()) {
                this.pathChecked = false;   // 着いたら経路を引き直す
                return;
            }
            switch (this.state) {
                case PICK -> {
                    if (--this.pickWait > 0) return;
                    AmbushSpots.Spot s = AmbushSpots.find(m, LEAP);
                    if (s == null) {
                        this.pickWait = 100;
                        return;
                    }
                    goTo(s);
                }
                case GO -> tickGo(m);
                case HIDE -> {
                    if (!this.aligned) {
                        m.wantFacing(this.spot.yaw(), TURN_HUNT);
                        this.aligned = Math.abs(Mth.wrapDegrees(this.spot.yaw() - m.getYRot())) < 3.0F;
                    }
                    if (--this.hideLeft <= 0) {
                        this.state = PICK;
                        this.pickWait = 1;
                    }
                }
                default -> {
                }
            }
        }

        private void tickGo(SaltimnesEntity m) {
            AmbushSpots.Spot s = this.spot;
            this.goTicks++;
            double dx = s.feet().x - m.getX(), dz = s.feet().z - m.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < ARRIVE && Math.abs(s.feet().y - m.getY()) < 1.5D) {
                m.getNavigation().stop();
                this.state = HIDE;
                this.aligned = false;
                this.hideLeft = HIDE_MIN + m.getRandom().nextInt(HIDE_MAX - HIDE_MIN + 1);
                return;
            }
            if (this.goTicks > GO_GIVE_UP || this.failedLeaps >= 3) {
                giveUp(m);
                return;
            }
            if (!this.pathChecked) {
                this.pathChecked = true;
                Path path = m.getNavigation().getPath();
                if (path == null || path.isDone()) {
                    m.getNavigation().moveTo(s.feet().x, s.feet().y, s.feet().z, STROLL_SPEED);
                    path = m.getNavigation().getPath();
                }
                if (path == null || !path.canReach() || pathLength(m, path) > dist * DETOUR_RATIO + 8.0D) {
                    // 経路が切れている・大回り(柵の囲い・塀・高い段差・水の溝)→ 向こうへ跳ぶ
                    tryLeap(m, s, dist);
                    return;
                }
            }
            if (++this.checkTick >= STUCK_TICKS) {
                this.checkTick = 0;
                boolean stuck = m.position().distanceTo(this.checkPos) < STUCK_MOVE;
                this.checkPos = m.position();
                if (stuck || m.getNavigation().isDone()) tryLeap(m, s, dist);
            }
        }

        /** 伏せ場所の方へ跳ぶ(遠ければ届く所まで)。跳べなければ数え、3 回でその場所をあきらめる。 */
        private void tryLeap(SaltimnesEntity m, AmbushSpots.Spot s, double dist) {
            Vec3 from = m.position();
            Vec3 dir = new Vec3(s.feet().x - from.x, 0.0D, s.feet().z - from.z).normalize();
            double[] tries = dist <= LEAP.maxDist() ? new double[]{dist} : new double[]{LEAP.maxDist() * 0.9D, LEAP.maxDist() * 0.7D, LEAP.maxDist() * 0.5D};
            for (double d : tries) {
                Vec3 aim = d == dist ? s.feet() : new Vec3(from.x + dir.x * d, s.feet().y, from.z + dir.z * d);
                if (m.leapToward(aim)) return;
            }
            this.failedLeaps++;
            this.pathChecked = false;
            m.getNavigation().moveTo(s.feet().x, s.feet().y, s.feet().z, STROLL_SPEED);
        }

        private void giveUp(SaltimnesEntity m) {
            m.getNavigation().stop();
            this.state = PICK;
            this.pickWait = 20;
        }

        private double pathLength(SaltimnesEntity m, Path path) {
            double len = 0.0D;
            Vec3 prev = m.position();
            for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
                Vec3 p = Vec3.atBottomCenterOf(path.getNodePos(i));
                len += Math.sqrt((p.x - prev.x) * (p.x - prev.x) + (p.z - prev.z) * (p.z - prev.z));
                prev = p;
            }
            return len;
        }
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 3, this::mainState));
    }

    private PlayState mainState(AnimationState<SaltimnesEntity> state) {
        AnimationController<SaltimnesEntity> c = state.getController();
        c.setAnimationSpeed(1.0D);
        int v = this.entityData.get(DATA_LEAP);
        int phase = LeapMotion.decodePhase(v);
        if (phase != LeapMotion.NONE && !this.isDeadOrDying()) {
            // leap: 0〜0.35 秒(身を沈めて蹴る)は速さ 1、宙の 0.35〜1.4 秒は実際の宙の tick 数に合わせ、着地はそのまま最後のコマで止める
            int ticks = Math.max(1, LeapMotion.decodeTicks(v));
            if (phase == LeapMotion.AIR || phase == LeapMotion.FALL) c.setAnimationSpeed(LEAP_ANIM_AIR_TICKS / ticks);
            return state.setAndContinue(LEAP_ANIM);
        }
        double ahead = clientForwardSpeed();
        boolean turning = Math.abs(getTurnRate()) >= TURN_ANIM_MIN;
        if (!this.isDeadOrDying() && (ahead >= WALK_MIN_SPEED || turning)) {
            double sp = Math.max(ahead, turning ? WALK_REF * 0.6D : 0.0D);
            c.setAnimationSpeed(Mth.clamp(sp / (WALK_REF * this.getScale()), LOOP_SPEED_MIN, LOOP_SPEED_MAX));
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
