package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.leap.AmbushSpots;
import com.yayuy.overgrownvegetation.entity.leap.GroundFit;
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
 *       脚の手続き(足の置き場)・体の曲げは使わない(手付けの walk を実際の速さに合わせて流す)。坂・段差では体の傾きと脚の上げ下げを地面に合わせる({@link GroundFit}、11-15 段 2)。</li>
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
     * (平らな所の 6 ブロックの跳びは頂点 2.5・宙 16 tick)。跳びのアニメ(11-15 段 2)は体ごとの移動を持たない姿だけなので、弧の高さ・長さに依らず合う。
     * 体は芯の中心から鼻先まで 20.9 px・腹の先まで 25.4 px(prac2_g5_leaper_r6 の外接)。身を沈める 10 tick = 補間 3 tick + {@code leap_windup} 7 tick。
     * 着地 8 tick = {@code leap_recover} の長さ。
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
     * walk を速さ 1.0 で流したときの地上速度(ブロック/tick)。11-15 段 2 のアニメ(Fable)で、中脚・後脚の 4 本が着いている間この速さで
     * 地面に止まるよう IK で焼き直してある(滑り 0.0007 px 以下)。前脚は畳んだまま(歩くのは 4 本。ユーザー了承)。
     */
    public static final double WALK_REF = 0.03D;
    static final double LOOP_SPEED_MIN = 0.3D, LOOP_SPEED_MAX = 2.5D, WALK_MIN_SPEED = 0.005D;
    /** 補間(tick): ふだん / 歩き → 待機(歩幅の途中で止まっても足がゆっくり戻る)【仮】。 */
    static final int BLEND = 3, BLEND_STOP = 8;
    /** 跳びのアニメの長さ(tick): 上り {@code leap_rise}・下り {@code leap_fall}。下りは着く「長さ + 補間」前に始める。 */
    static final int RISE_TICKS = 6, FALL_TICKS = 6;
    /** 落ちている(弧をやめた)間、地面までこれ(ブロック)より近ければ下りの姿へ。 */
    static final double FALL_POSE_GROUND = 1.5D;

    // --- 伏せ【仮】 ---
    /** 伏せたまま動かない長さ(tick): 1〜3 分。 */
    public static final int HIDE_MIN = 1200, HIDE_MAX = 3600;
    /** 確かめ用 hop: 跳ぶ間隔(tick)と距離(ブロック)。 */
    public static final int HOP_EVERY = 200;
    static final double HOP_MIN = 5.0D, HOP_MAX = 9.0D;

    private static final EntityDataAccessor<Integer> DATA_LEAP = SynchedEntityData.defineId(SaltimnesEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation LEAP_WINDUP = RawAnimation.begin().thenPlayAndHold("leap_windup");
    private static final RawAnimation LEAP_RISE = RawAnimation.begin().thenPlayAndHold("leap_rise");
    private static final RawAnimation LEAP_AIR = RawAnimation.begin().thenLoop("leap_air");
    private static final RawAnimation LEAP_FALL = RawAnimation.begin().thenPlayAndHold("leap_fall");
    private static final RawAnimation LEAP_RECOVER = RawAnimation.begin().thenPlayAndHold("leap_recover");

    /** クライアント: 跳びの段が変わった tick(段ごとの経過から上り・宙・下りを選ぶ)。 */
    private int clientLeapPhase = LeapMotion.NONE, clientLeapStart;
    /** クライアント: 地面に合わせる体の傾き・脚の上げ下げ({@link GroundFit})。 */
    private final GroundFit groundFit = new GroundFit();

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private final LeapMotion leap = new LeapMotion(LEAP);
    @Nullable
    private AmbushGoal ambush;

    public SaltimnesEntity(EntityType<? extends SaltimnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 30);
        this.groundFit.configure(LEAP.bodyFront(), LEAP.bodyBack(), FEET);
    }

    /**
     * 歩く 4 本の足の置き場 {x(+ = 右), z(+ = 後ろ)}(ブロック): 左中・右中・左後・右後。待機の足先(中脚の先 x ±13.5・z 2.6 px、
     * 後脚の足首 x ±10.5・z 9 px)× 描画倍率。前脚は畳んで宙(歩きでも着かない)。
     */
    public static final double[][] FEET = {{-13.5D * PX, 2.6D * PX}, {13.5D * PX, 2.6D * PX}, {-10.5D * PX, 9.0D * PX}, {10.5D * PX, 9.0D * PX}};

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            int phase = LeapMotion.decodePhase(this.entityData.get(DATA_LEAP));
            if (phase != this.clientLeapPhase) {
                this.clientLeapPhase = phase;
                this.clientLeapStart = this.tickCount;
            }
            boolean airborne = phase == LeapMotion.AIR || phase == LeapMotion.FALL;
            this.groundFit.tick(this, !airborne && !this.isDeadOrDying() && !this.isInWater());
            // 宙: 体の前後の傾きを弧の向き(上り = 鼻上げ・下り = 鼻下げ)へ
            this.airPitchO = this.airPitch;
            double target = 0.0D;
            if (airborne) {
                double vh = Math.sqrt((this.getX() - this.xo) * (this.getX() - this.xo) + (this.getZ() - this.zo) * (this.getZ() - this.zo));
                target = Mth.clamp(Math.atan2(this.getY() - this.yo, Math.max(vh, 0.05D)) * AIR_PITCH_GAIN, -AIR_PITCH_MAX, AIR_PITCH_MAX);
            }
            this.airPitch += (target - this.airPitch) * 0.5D;
        }
    }

    /** 宙の体の傾き: 弧の向きの何割を体に移すか・上限(ラジアン)【仮】。アニメ自体の傾きは ±5 度だけ。 */
    static final double AIR_PITCH_GAIN = 0.5D, AIR_PITCH_MAX = Math.toRadians(25.0D);   // 11-15: 0.8・40 度では蹴った直後に体が縦に近く立った(テストクライアント)
    private double airPitch, airPitchO;

    /** クライアント: 宙の体の前後の傾き(ラジアン、+ で鼻が上がる)。 */
    public double airPitch(float pt) {
        return Mth.lerp(pt, this.airPitchO, this.airPitch);
    }

    /** クライアント: 地面に合わせる体の傾き・脚の上げ下げ(描画のモデルが読む)。 */
    public GroundFit groundFit() {
        return this.groundFit;
    }

    /** クライアント: 今の跳びの段({@link LeapMotion} の段)。 */
    public int clientLeapPhase() {
        return this.clientLeapPhase;
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

    /**
     * 跳び(11-15 段 2 のアニメ): 身を沈める {@code leap_windup}(補間 3)→ 蹴った tick に {@code leap_rise}(補間 0。補間が入ると宙で溜めの姿のまま固まる)
     * → 宙が長ければ {@code leap_air}(繰り返し)→ 着く {@link #FALL_TICKS} + 補間 3 tick 前に {@code leap_fall} → 着いた tick に {@code leap_recover}(補間 0。
     * 補間が入ると地面に入った足が戻るまで残る)。速さはどれも 1.0(宙の長さは leap_air の繰り返しで受ける)。弧をやめて落ちている間は leap_air、地面が近づいたら leap_fall。
     * 歩き: 前へ進んでいる間だけ walk を実際の速さに合わせて流す。その場で回るだけの間は待機(回る用のアニメは無く、walk だと足が必ず滑る)。
     * 歩き → 待機は補間 {@link #BLEND_STOP}(歩幅の途中で止まっても足がゆっくり戻る)。
     */
    private PlayState mainState(AnimationState<SaltimnesEntity> state) {
        AnimationController<SaltimnesEntity> c = state.getController();
        c.setAnimationSpeed(1.0D);
        c.transitionLength(BLEND);
        int v = this.entityData.get(DATA_LEAP);
        int phase = LeapMotion.decodePhase(v);
        if (phase != LeapMotion.NONE && !this.isDeadOrDying()) {
            int ticks = Math.max(1, LeapMotion.decodeTicks(v));
            int el = this.tickCount - this.clientLeapStart;
            switch (phase) {
                case LeapMotion.CROUCH -> {
                    return state.setAndContinue(LEAP_WINDUP);
                }
                case LeapMotion.AIR -> {
                    if (el < RISE_TICKS && el < ticks - FALL_TICKS - BLEND) {
                        c.transitionLength(0);
                        return state.setAndContinue(LEAP_RISE);
                    }
                    return state.setAndContinue(el >= ticks - FALL_TICKS - BLEND ? LEAP_FALL : LEAP_AIR);
                }
                case LeapMotion.FALL -> {
                    boolean near = this.getY() - GroundFit.groundBelow(this) <= FALL_POSE_GROUND;
                    return state.setAndContinue(near ? LEAP_FALL : LEAP_AIR);
                }
                default -> {   // LAND
                    c.transitionLength(0);
                    return state.setAndContinue(LEAP_RECOVER);
                }
            }
        }
        double ahead = clientForwardSpeed();
        if (!this.isDeadOrDying() && ahead >= WALK_MIN_SPEED) {
            c.setAnimationSpeed(Mth.clamp(ahead / (WALK_REF * this.getScale()), LOOP_SPEED_MIN, LOOP_SPEED_MAX));
            return state.setAndContinue(WALK);
        }
        if (c.getCurrentRawAnimation() == WALK) c.transitionLength(BLEND_STOP);
        return state.setAndContinue(IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
