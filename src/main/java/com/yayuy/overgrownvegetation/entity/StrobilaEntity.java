package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.GaitAnimationController;
import com.yayuy.overgrownvegetation.entity.crawler.SmallCrawler;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * ストロビラ {@code strobila}(G2・軽い投手。11-14 に本名。仮名タマナゲ、練習の id {@code g4_lobber})。
 * アルボラの後ろから、動きながら尾の先の球果を山なりに投げる(Vault {@code Mob の能力/ストロビラ.md})。G2 の中で少し丈夫だが、詰められると弱い。
 * エネルギーは運動(尾の弾性。膨圧で張って一気に放す)。
 *
 * <h2>段 1(2026-11-15): うろつく・急ぐ</h2>
 * 移動は「小さな地這いの虫」の型({@link SmallCrawler})。群れの後ろに付いて歩くのは群れ v2 が戻ってから({@link #isSteered()} は型のまま)。
 *
 * <h2>段 2(2026-11-15): 走り・山なりの投げ</h2>
 * アニメは段 2 のもの(正本 Blockbench の {@code strobila.bbmodel}、モデル制作の持ち場。Java からは変えない)。
 * <ul>
 *   <li><b>歩き・走り</b>: 下の層 {@code main}({@link GaitAnimationController})。walk は 1 倍で {@link #WALK_REF}、run は {@link #RUN_REF}
 *       ブロック/tick で足が滑らない。{@link #RUN_ON} 以上で run、{@link #RUN_OFF} 以下で walk。walk ⇄ run は 1 巡の中の位置を保って移る。
 *       急ぎ(移動の速さ 0.36 × 1.0)は床で約 0.28 = アルボラの床の速さ(11-15 実測。ユーザー決定「アルボラに付いていける速さ」)。</li>
 *   <li><b>投げ</b>: 上の層 {@code throw}(頭・触角・大顎・背板・尾・苞・球果だけをキーする)。前触れ {@code throw_windup} 1 秒 → 投げ {@code throw} 0.6 秒
 *       → 隙 {@code throw_recover} 0.9 秒(ユーザー決定 11-15)。動きながら投げる(歩き・走りの層はそのまま)。前触れの中断は無い。
 *       弾({@link StrobilaCone})は {@code throw} の 4 tick 目、球果の位置(体の後ろの頭上)から的へ山なりに出す。投げは {@link #THROW_INTERVAL} tick おき。</li>
 *   <li>的: 見えたプレイヤーと、殴ってきた相手【仮】。届く所({@link #THROW_MIN}〜{@link #THROW_MAX})で、体の正面の左右 {@link #FRONT_HALF_ANGLE} 度の中にいれば投げる。
 *       遠ければ急いで寄り、{@link #HOLD_WITHIN} より近ければ止まって向き直る【仮】。横・後ろの近い相手に尾を振り回す技は、アニメができてから。</li>
 * </ul>
 */
public class StrobilaEntity extends SmallCrawler implements GeoEntity, ParasitizedMob {

    public static final String ID = "strobila";

    /** 描画倍率(テクスチャは 1 単位 1 テクセル = ブロックの 1 画素)。 */
    public static final float RENDER_SCALE = 1.0F;

    /** walk・run を 1 倍で流したときの地上速度(ブロック/tick。段 2 のアニメの確定値)。 */
    public static final double WALK_REF = 0.03D, RUN_REF = 0.28D;
    /** 再生速度の範囲(アニメ側の一覧の「覆える範囲」)【仮】。 */
    static final double WALK_SPEED_MIN = 0.4D, WALK_SPEED_MAX = 2.8D, RUN_SPEED_MIN = 0.3D, RUN_SPEED_MAX = 1.2D;
    /** これ以上で run、これ以下で walk(ブロック/tick。行き来でばたつかないよう差を付ける)【仮】。 */
    static final double RUN_ON = 0.088D, RUN_OFF = 0.080D;
    /** これ(ブロック/tick)以上進んだら歩き【仮】。 */
    static final double WALK_MIN_SPEED = 0.006D;

    /** 回る速さ(度/tick)【仮】。体が長く重い背板を負うので、グレガリア(30)の半分。 */
    static final float TURN_PER_TICK = 15.0F;
    /** うろつきの速さ: 移動の速さの属性に掛ける【仮】。地面で約 0.03 ブロック/tick(walk 1 倍)を的に。 */
    static final double STROLL_SPEED = 0.327D;   // 地面の速さ ≈ 2.16 × (0.36 × 0.327)² ≈ 0.030

    /** 描画の外接箱を芯の箱から広げる量(ブロック): 脚・触角・尾は芯からはみ出す(全体 3.6 × 2.8 × 5.5)。 */
    static final double CULL_SIDE = 2.4D, CULL_UP = 1.8D;

    // --- 投げ(段 2) ---
    /** 上の層に入るときの補間(tick)。アニメはこの後に頭から流れる(記憶 geckolib-transition-delays-animation)。 */
    static final int THROW_TRANSITION = 3;
    /** 3 本の長さ(tick。アニメの確定値)。 */
    static final int WINDUP_TICKS = 20, THROW_TICKS = 12, RECOVER_TICKS = 18;
    /** 球果が離れる {@code throw} の中の tick(アニメの確定値)。 */
    static final int RELEASE_IN_THROW = 4;
    /** 投げ始めから弾を出すまで・投げ全体の長さ(サーバーの tick。補間を足す)。 */
    public static final int RELEASE_AT = THROW_TRANSITION + WINDUP_TICKS + RELEASE_IN_THROW;
    public static final int ACTION_TICKS = THROW_TRANSITION + WINDUP_TICKS + THROW_TICKS + RECOVER_TICKS;
    /** 投げの間隔(投げ始めから次の投げ始めまで、tick)。ユーザー決定 11-15「球果は 20 秒おき」。 */
    public static final int THROW_INTERVAL = 400;
    /** 的に気付いてから最初の投げまでの間(tick)【仮】。 */
    static final int FIRST_THROW_DELAY = 20;
    /** 届く距離(ブロック、水平)【仮・Claude 11-15】。 */
    public static final double THROW_MIN = 8.0D, THROW_MAX = 46.0D;   // 11-18 夜 ユーザー「8〜46」(8 より近い相手には投げず、尾の払いだけ)。actions.json の strobila/cone の min・max   // 11-16 ユーザー「射程が短すぎる」で 16 → 32(2 倍)
    /** これより遠ければ急いで寄り、{@link #HOLD_WITHIN} より近ければ止まる(ブロック)【仮】。 */
    static double throwMin() {
        return com.yayuy.overgrownvegetation.data.DesignData.actions().num(ID, "cone", "min", THROW_MIN);
    }

    static double throwMax() {
        return com.yayuy.overgrownvegetation.data.DesignData.actions().num(ID, "cone", "max", THROW_MAX);
    }

    static final double APPROACH_ABOVE = 44.0D, HOLD_WITHIN = 34.0D;   // 11-18 夜 射程 46 に合わせた【仮】   // 射程に合わせて伸ばした(11-16。寄るのは射程の端 30 より遠いときだけ)
    /** 投げられる向き: 体の正面の左右(度)。ユーザー決定 11-15(これより外の近い相手は尾の振り回し = 後の段)。 */
    static final float FRONT_HALF_ANGLE = 60.0F;
    /** 球果が離れる所(足元から、体の向きを基準に。アニメの測り「throw 4 tick」): 上・後ろ(ブロック)。 */
    static final double RELEASE_UP = 2.57D, RELEASE_BACK = 1.78D;
    /** 山なりの飛ぶ時間(tick) = これ + 距離 × {@link #FLIGHT_PER_BLOCK}、{@link #FLIGHT_MIN}〜{@link #FLIGHT_MAX}【仮】。 */
    static final double FLIGHT_BASE = 22.0D, FLIGHT_PER_BLOCK = 1.0D;   // 32 ブロックで 54 tick・頂は約 9 ブロック上(11-16)
    static final int FLIGHT_MIN = 24, FLIGHT_MAX = 70;   // 11-18 夜 46 ブロックで 68 tick(22 + 46)まで飛べるように   // 近くても 24 tick(頂が 1 ブロック以上上がる)
    /** 球果の重さ・空気の抵抗(ThrowableProjectile の値)。 */
    static final double CONE_GRAVITY = 0.03D, CONE_DRAG = 0.99D;

    /** 投げの段(サーバー → クライアント。段の境で 1 回ずつ送る)。 */
    private static final EntityDataAccessor<Byte> DATA_THROW_PHASE = SynchedEntityData.defineId(StrobilaEntity.class, EntityDataSerializers.BYTE);
    static final byte PHASE_NONE = 0, PHASE_WINDUP = 1, PHASE_THROW = 2, PHASE_RECOVER = 3;
    /** 段の境(投げ始めからの tick): 前触れは補間のあと {@link #WINDUP_TICKS}、投げは {@link #THROW_TICKS}。 */
    static final int THROW_PHASE_AT = THROW_TRANSITION + WINDUP_TICKS, RECOVER_PHASE_AT = THROW_PHASE_AT + THROW_TICKS;

    // --- 尾の払い(段 2 の追い、11-15 夜) ---
    /** 払いの段(左 = {@code _l}・右 = {@code _r})。投げの段と同じ口で送る(投げと払いは同時には無い)。 */
    static final byte PHASE_SWEEP_WINDUP_L = 4, PHASE_SWEEP_L = 5, PHASE_SWEEP_RECOVER_L = 6,
            PHASE_SWEEP_WINDUP_R = 7, PHASE_SWEEP_R = 8, PHASE_SWEEP_RECOVER_R = 9;
    /** 3 本の長さ(tick。アニメの値【仮・strobila-anim】)。 */
    static final int SWEEP_WINDUP_TICKS = 12, SWEEP_TICKS = 8, SWEEP_RECOVER_TICKS = 16;
    /** 段の境(払い始めからの tick。入るときの補間 {@link #THROW_TRANSITION} を足す)。 */
    static final int SWEEP_PHASE_AT = THROW_TRANSITION + SWEEP_WINDUP_TICKS, SWEEP_RECOVER_AT = SWEEP_PHASE_AT + SWEEP_TICKS;
    public static final int SWEEP_ACTION_TICKS = SWEEP_RECOVER_AT + SWEEP_RECOVER_TICKS;
    /** 当たりを取る払いの中の tick(アニメの測り「尾が速く動くのは 2〜6 tick」)。 */
    static final int SWEEP_HIT_FROM = 2, SWEEP_HIT_TO = 6;
    /**
     * 払いの tick ごとの球果の方位(度。0 = 真後ろ・+ = 右・− = 左)と、尾・苞・球果のいちばん遠い角までの距離(px)。
     * Vault「Mob の能力/ストロビラ」9 節の表(MOD の json から測った値)。添字 = 払いの中の tick 0〜8。
     */
    static final float[] SWEEP_AZ_L = {41, 37, 23, -7, -45, -91, -100, -88, -76};
    static final float[] SWEEP_REACH_L = {51, 53, 56, 62, 59, 40, 31, 32, 33};
    static final float[] SWEEP_AZ_R = {-44, -40, -26, 4, 43, 87, 96, 84, 73};
    static final float[] SWEEP_REACH_R = {50, 52, 56, 62, 60, 42, 32, 33, 35};
    /** 当たる高さ(足元から、ブロック)。尾 1.4〜1.75・球果 0.7〜1.25(9 節)。 */
    static final double SWEEP_LOW = 0.7D, SWEEP_HIGH = 1.75D;
    /** 払う相手: 正面からこれ(度)より外。ユーザー決定 11-15「正面の左右 60° より外」。 */
    static final float SWEEP_OUTSIDE = 60.0F;
    /** 尾が届くのは正面からこれ(度)より外(9 節。{@code _r} は 84)。60〜これの相手には、払う前に体を相手から離す向きへ回す(ユーザー決定 11-15)。 */
    static final float SWEEP_REACHABLE = 82.0F;
    /** 払う前に回る長さの上限(tick)【仮】(回る速さ 15 度/tick を 1〜2 tick の目安。3 tick で打ち切って払う)。 */
    static final int SWEEP_TURN_MAX = 3;
    /** 払いを始める距離: 相手の方位での尾の届き(ブロック)× これ【仮】。 */
    static final double SWEEP_TRIGGER_SHARE = 0.9D;
    /** 傷(ゾンビの一撃の半分くらい【仮・ユーザーの目安】)と弾き飛ばしの強さ【仮】(バニラの近接の素の弾き 0.4)。 */
    public static final float SWEEP_DAMAGE = 17.25F;   // 11-18 夜 ユーザー決定「固定の技の傷は攻撃と同じ比で」: 攻撃 2 → 23 の比(× 11.5)。actions.json の strobila/sweep
    static final double SWEEP_KNOCKBACK = 1.5D;
    /** 払い終わってから次の払いまで(tick)【仮】。 */
    static final int SWEEP_COOLDOWN = 20;

    /** 投げの 3 本(windup・throw は json が「最後の姿で止まる」。段はサーバーが切り替える)。 */
    private static final RawAnimation WINDUP = RawAnimation.begin().thenPlayAndHold("throw_windup");
    private static final RawAnimation THROW = RawAnimation.begin().thenPlayAndHold("throw");
    private static final RawAnimation RECOVER = RawAnimation.begin().thenPlay("throw_recover");
    /** 尾の払いの 3 本 × 左右(windup・sweep は json が「最後の姿で止まる」)。 */
    private static final RawAnimation SWEEP_WINDUP_L = RawAnimation.begin().thenPlayAndHold("tail_sweep_windup_l");
    private static final RawAnimation SWEEP_L = RawAnimation.begin().thenPlayAndHold("tail_sweep_l");
    private static final RawAnimation SWEEP_RECOVER_L = RawAnimation.begin().thenPlay("tail_sweep_recover_l");
    private static final RawAnimation SWEEP_WINDUP_R = RawAnimation.begin().thenPlayAndHold("tail_sweep_windup_r");
    private static final RawAnimation SWEEP_R = RawAnimation.begin().thenPlayAndHold("tail_sweep_r");
    private static final RawAnimation SWEEP_RECOVER_R = RawAnimation.begin().thenPlay("tail_sweep_recover_r");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // --- サーバー: 投げの進み ---
    /** 投げ始めからの tick(投げていなければ -1)。 */
    private int throwTick = -1;
    /** 次の投げを始めてよいまでの残り(tick)。 */
    private int throwCooldown;
    /** 最後に狙った所(的を見失っても、前触れは止めずにそこへ投げる)。 */
    @Nullable
    private Vec3 aim;
    @Nullable
    private LivingEntity lastTarget;
    /** 払い始めからの tick(払っていなければ -1)・右へ払うか・払う前に回った tick(回っていなければ -1)・次の払いまでの残り。 */
    private int sweepTick = -1;
    private boolean sweepRight;
    private int sweepTurnTick = -1;
    private int sweepCooldown;
    /** この払いで当たった相手(1 回の払いで 1 度だけ)。 */
    private final java.util.Set<Integer> sweepHit = new java.util.HashSet<>();

    // --- クライアント ---
    private byte throwPhaseBefore = PHASE_NONE;
    @Nullable
    private AnimationController<StrobilaEntity> throwController;

    public StrobilaEntity(EntityType<? extends StrobilaEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 3);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_THROW_PHASE, PHASE_NONE);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(3, new KeepThrowRangeGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    protected float turnPerTick() {
        return TURN_PER_TICK;
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    /** 小走りは長め・止まるのも長め(グレガリアより落ち着いた虫)【仮】。 */
    @Override
    protected int[] dashTicks() {
        return new int[]{20, 50};
    }

    @Override
    protected int[] pauseTicks() {
        return new int[]{10, 40};
    }

    @Override
    protected double bodyHalfLength() {
        return 1.0D;   // 頭の先から胴の後ろまで約 2.2 ブロック(尾は上へ立つので数えない)
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        AABB b = this.getBoundingBox();
        return new AABB(b.minX - CULL_SIDE, b.minY - 0.5D, b.minZ - CULL_SIDE, b.maxX + CULL_SIDE, b.maxY + CULL_UP, b.maxZ + CULL_SIDE);
    }

    // ------------------------------------------------------------------ 投げ(サーバー)

    /** 投げている(前触れ〜隙)か。クライアントでも読める。 */
    public boolean isThrowing() {
        return this.entityData.get(DATA_THROW_PHASE) != PHASE_NONE;
    }

    /** 投げ始めからの tick(投げていなければ -1。サーバー・GameTest 用)。 */
    public int throwTick() {
        return this.throwTick;
    }

    /** 走っている Goal の名前(GameTest の失敗の文用)。 */
    public String runningGoalNames() {
        StringBuilder b = new StringBuilder();
        this.goalSelector.getAvailableGoals().stream().filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
                .forEach(w -> b.append(w.getGoal().getClass().getSimpleName()).append(' '));
        return b.toString().trim();
    }

    /** 次の投げまでの残り(tick。GameTest 用)。 */
    public int throwCooldown() {
        return this.throwCooldown;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        LivingEntity t = this.getTarget();
        if (t != null && !t.isAlive()) t = null;
        if (t != this.lastTarget) {
            // 新しい的に気付いた: 最初の投げは少し待つ(気付いた瞬間に投げない)
            if (t != null && this.throwCooldown < FIRST_THROW_DELAY) this.throwCooldown = FIRST_THROW_DELAY;
            this.lastTarget = t;
        }
        if (this.throwCooldown > 0) this.throwCooldown--;   // 投げている間も減らす(間隔は投げ始めから次の投げ始めまで)
        if (this.sweepCooldown > 0 && this.sweepTick < 0) this.sweepCooldown--;
        if (this.sweepTurnTick >= 0 || this.sweepTick >= 0) {
            tickSweep(t);
            return;
        }
        if (this.throwTick < 0) {
            // 優先は尾(ユーザー決定 11-15「寄られたら、まず払う。投げは 20 秒おきのまま待つ」)
            if (t != null && this.sweepCooldown <= 0 && wantsSweep(t)) {
                startSweepTurn(t);
                return;
            }
            if (this.throwCooldown <= 0 && t != null && canThrowAt(t)) startThrow(t);
            return;
        }
        this.throwTick++;
        if (t != null) this.aim = t.position().add(0.0D, t.getBbHeight() * 0.3D, 0.0D);
        if (this.throwTick == THROW_PHASE_AT) this.entityData.set(DATA_THROW_PHASE, PHASE_THROW);
        if (this.throwTick == RELEASE_AT && this.aim != null) releaseCone(this.aim);
        if (this.throwTick == RECOVER_PHASE_AT) this.entityData.set(DATA_THROW_PHASE, PHASE_RECOVER);
        if (this.throwTick >= ACTION_TICKS) {
            this.throwTick = -1;
            this.entityData.set(DATA_THROW_PHASE, PHASE_NONE);
        }
    }

    /** 届く距離で、体の正面の左右 {@link #FRONT_HALF_ANGLE} 度の中にいるか。 */
    boolean canThrowAt(LivingEntity t) {
        double dx = t.getX() - this.getX(), dz = t.getZ() - this.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < throwMin() || d > throwMax()) return false;
        float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        return Math.abs(Mth.wrapDegrees(want - this.getYRot())) <= FRONT_HALF_ANGLE;
    }

    private void startThrow(LivingEntity t) {
        this.throwTick = 0;
        this.throwCooldown = THROW_INTERVAL;
        this.aim = t.position().add(0.0D, t.getBbHeight() * 0.3D, 0.0D);
        this.entityData.set(DATA_THROW_PHASE, PHASE_WINDUP);
    }

    // ------------------------------------------------------------------ 尾の払い(サーバー)

    /** 投げか払いをしている(または払う前に回っている)。 */
    public boolean isActing() {
        return this.throwTick >= 0 || this.sweepTick >= 0 || this.sweepTurnTick >= 0;
    }

    /** 払っている(回っている間も含む。GameTest 用)・右へ払うか・払い始めからの tick。 */
    public boolean isSweeping() {
        return this.sweepTick >= 0 || this.sweepTurnTick >= 0;
    }

    public boolean sweepRight() {
        return this.sweepRight;
    }

    public int sweepTick() {
        return this.sweepTick;
    }

    /** {@code p} の方位(度): 0 = 真後ろ・+ = 右・− = 左(9 節の表と同じ言い方)。 */
    float azimuthOf(double px, double pz) {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        double dx = px - this.getX(), dz = pz - this.getZ();
        double back = dx * Mth.sin(yaw) - dz * Mth.cos(yaw);     // 後ろ = (sin, -cos)
        double right = -dx * Mth.cos(yaw) - dz * Mth.sin(yaw);   // 右 = (-cos, -sin)
        return (float) (Mth.atan2(right, back) * Mth.RAD_TO_DEG);
    }

    /** 方位 {@code az}(度)での尾の届き(ブロック、足元の中心から)。真後ろ〜45 度で 3.7、真横 100 度で 2.2(9 節)。 */
    static double sweepReach(float az) {
        float a = Math.abs(az);
        if (a <= 45.0F) return 3.7D;
        return Mth.lerp(Math.min(1.0F, (a - 45.0F) / 55.0F), 3.7D, 2.2D);
    }

    /** 尾で払う相手か: 正面から {@link #SWEEP_OUTSIDE} 度より外(方位で 180 − 60 = 120 度より後ろ)で、尾の届く近さ。 */
    public boolean wantsSweep(LivingEntity t) {
        float az = azimuthOf(t.getX(), t.getZ());
        if (Math.abs(az) >= 180.0F - SWEEP_OUTSIDE) return false;
        double dx = t.getX() - this.getX(), dz = t.getZ() - this.getZ();
        double d = Math.sqrt(dx * dx + dz * dz) - t.getBbWidth() * 0.5D;
        return d <= sweepReach(az) * SWEEP_TRIGGER_SHARE && Math.abs(t.getY() - this.getY()) < 2.0D;
    }

    /** 払う前: 相手が尾の届く帯(正面から {@link #SWEEP_REACHABLE} 度より外)に入るまで、体を相手から離す向きへ回す(ユーザー決定 11-15)。 */
    private void startSweepTurn(LivingEntity t) {
        this.sweepTurnTick = 0;
        this.getNavigation().stop();
        tickSweep(t);
    }

    private void tickSweep(@Nullable LivingEntity t) {
        if (this.sweepTurnTick >= 0) {
            float az = t == null ? 0.0F : azimuthOf(t.getX(), t.getZ());
            if (t == null || Math.abs(az) <= 180.0F - SWEEP_REACHABLE || this.sweepTurnTick >= SWEEP_TURN_MAX) {
                this.sweepTurnTick = -1;
                this.sweepTick = 0;
                this.sweepRight = az > 0.0F;   // 右にいれば右へ払う(真後ろはどちらでも)
                this.sweepHit.clear();
                this.entityData.set(DATA_THROW_PHASE, this.sweepRight ? PHASE_SWEEP_WINDUP_R : PHASE_SWEEP_WINDUP_L);
                return;
            }
            // 相手を後ろへ回す: 相手が右にいれば左へ回る(yaw が増えると右へ回る。方位を 0 へ寄せる向き)
            float turn = Math.min(TURN_PER_TICK, Math.abs(az) - (180.0F - SWEEP_REACHABLE) + 3.0F);
            float yaw = this.getYRot() + (az > 0.0F ? -turn : turn);
            this.setYRot(yaw);
            this.yBodyRot = yaw;
            this.sweepTurnTick++;
            return;
        }
        this.getNavigation().stop();
        this.sweepTick++;
        int k = this.sweepTick - SWEEP_PHASE_AT;   // 払いの中の tick
        if (this.sweepTick == SWEEP_PHASE_AT) this.entityData.set(DATA_THROW_PHASE, this.sweepRight ? PHASE_SWEEP_R : PHASE_SWEEP_L);
        if (k >= SWEEP_HIT_FROM && k <= SWEEP_HIT_TO) sweepHits(k);
        if (this.sweepTick == SWEEP_RECOVER_AT) this.entityData.set(DATA_THROW_PHASE, this.sweepRight ? PHASE_SWEEP_RECOVER_R : PHASE_SWEEP_RECOVER_L);
        if (this.sweepTick >= SWEEP_ACTION_TICKS) {
            this.sweepTick = -1;
            this.sweepCooldown = SWEEP_COOLDOWN;
            this.entityData.set(DATA_THROW_PHASE, PHASE_NONE);
        }
    }

    /**
     * 払いの {@code k} tick 目の当たり: 前の tick の球果の方位から今の方位までの扇(1 tick に 30 度以上進むので点でなく扇)、
     * 距離は 2 つの tick のいちばん遠い角まで、高さは {@link #SWEEP_LOW}〜{@link #SWEEP_HIGH}。植物側は叩かない。
     */
    private void sweepHits(int k) {
        float[] az = this.sweepRight ? SWEEP_AZ_R : SWEEP_AZ_L;
        float[] reach = this.sweepRight ? SWEEP_REACH_R : SWEEP_REACH_L;
        float a0 = Math.min(az[k - 1], az[k]), a1 = Math.max(az[k - 1], az[k]);
        double r = Math.max(reach[k - 1], reach[k]) / 16.0D;
        AABB box = this.getBoundingBox().inflate(r + 1.0D, 1.0D, r + 1.0D);
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, e -> PlantFoes.isFoe(this, e))) {   // 陣営と除外は共通(防具立て・創造も外す。①13)
            if (this.sweepHit.contains(e.getId())) continue;
            if (e instanceof net.minecraft.world.entity.player.Player p && (p.isCreative() || p.isSpectator())) continue;
            double dx = e.getX() - this.getX(), dz = e.getZ() - this.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            double half = e.getBbWidth() * 0.5D;
            if (d - half > r) continue;
            if (e.getBoundingBox().minY > this.getY() + SWEEP_HIGH || e.getBoundingBox().maxY < this.getY() + SWEEP_LOW) continue;
            float a = azimuthOf(e.getX(), e.getZ());
            float pad = (float) Math.toDegrees(Math.atan2(half, Math.max(d, 0.1D)));   // 体の幅のぶん扇を広げる
            if (a < a0 - pad || a > a1 + pad) continue;
            this.sweepHit.add(e.getId());
            e.hurt(this.damageSources().mobAttack(this), (float) com.yayuy.overgrownvegetation.data.DesignData.actions().num("strobila", "sweep", "damage", SWEEP_DAMAGE));
            e.knockback(SWEEP_KNOCKBACK, this.getX() - e.getX(), this.getZ() - e.getZ());
            e.hurtMarked = true;
        }
    }

    /** 球果を出す所: 体の後ろの頭上(アニメの throw 4 tick の球果の中心)。そこがブロックの中なら背の上から。 */
    Vec3 releasePoint() {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        // 体の向き(前) = (-sin yaw, cos yaw)。後ろはその逆
        Vec3 p = this.position().add(Mth.sin(yaw) * RELEASE_BACK, RELEASE_UP, -Mth.cos(yaw) * RELEASE_BACK);
        AABB box = AABB.ofSize(p, 0.3D, 0.3D, 0.3D);
        if (this.level().noCollision(box)) return p;
        return this.position().add(0.0D, this.getBbHeight() + 0.2D, 0.0D);
    }

    private void releaseCone(Vec3 target) {
        Vec3 from = releasePoint();
        StrobilaCone cone = new StrobilaCone(this.level(), this);
        cone.setPos(from.x, from.y, from.z);
        cone.setDeltaMovement(lobVelocity(from, target));
        this.level().addFreshEntity(cone);
    }

    /**
     * {@code from} から {@code to} へ山なりに届く初速(ブロック/tick)。飛ぶ時間を距離から決め({@link #FLIGHT_BASE} + 距離 × {@link #FLIGHT_PER_BLOCK})、
     * 投げ物の毎 tick「進む → 0.99 倍 → 重さ 0.03」の和が、その時間でちょうど届く速さを解く。
     */
    public static Vec3 lobVelocity(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        int n = Mth.clamp((int) Math.round(FLIGHT_BASE + h * FLIGHT_PER_BLOCK), FLIGHT_MIN, FLIGHT_MAX);
        double sum = (1.0D - Math.pow(CONE_DRAG, n)) / (1.0D - CONE_DRAG);   // Σ d^k(k = 0..n-1)
        double fall = CONE_GRAVITY / (1.0D - CONE_DRAG) * (n - sum);        // 重さで落ちる分
        return new Vec3(dx / sum, (dy + fall) / sum, dz / sum);
    }

    /**
     * 的との間合い(段 2【仮】): 遠ければ急いで寄り、近ければ止まって体を的へ向け直す(回る速さ {@link #TURN_PER_TICK})。投げそのものはこの Goal では
     * なく {@link #customServerAiStep}(動きながら投げるので、移動の印を取らない)。
     */
    static final class KeepThrowRangeGoal extends Goal {
        private final StrobilaEntity mob;
        private int repath;

        KeepThrowRangeGoal(StrobilaEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = mob.getTarget();
            return t != null && t.isAlive() && !mob.isSteered();
        }

        @Override
        public void start() {
            this.repath = 0;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity t = mob.getTarget();
            if (t == null) return;
            // 払っている間・払う相手(横・後ろの近い相手)には向き直らない(向き直ると尾が届かない。11-15 尾の払い)
            if (mob.isSweeping() || mob.wantsSweep(t)) return;
            double d = Math.sqrt(mob.distanceToSqr(t.getX(), mob.getY(), t.getZ()));
            if (d > APPROACH_ABOVE) {
                if (--this.repath <= 0 || mob.getNavigation().isDone()) {
                    this.repath = 10;
                    mob.getNavigation().moveTo(t, mob.rushSpeed());
                }
                return;
            }
            if (d <= HOLD_WITHIN && !mob.getNavigation().isDone()) mob.getNavigation().stop();
            if (mob.getNavigation().isDone()) {
                float want = (float) (Mth.atan2(t.getZ() - mob.getZ(), t.getX() - mob.getX()) * Mth.RAD_TO_DEG) - 90.0F;
                float yaw = mob.getYRot() + Mth.clamp(Mth.wrapDegrees(want - mob.getYRot()), -TURN_PER_TICK, TURN_PER_TICK);
                mob.setYRot(yaw);
            }
        }
    }

    // ------------------------------------------------------------------ 調べ

    // ------------------------------------------------------------------ 尾の背の光(クライアント)

    /** 光が前触れの何 tick 目から何 tick 目で 0 → 1 になるか(アニメの時刻。7 節「光と玉の時刻」【仮】)。 */
    static final float GLOW_RISE_FROM = 2.0F, GLOW_RISE_TO = 16.0F;
    /** 投げの何 tick 目から何 tick 目で 1 → 0 になるか(球果が離れる 4 tick から)【仮】。 */
    static final float GLOW_FALL_FROM = 4.0F, GLOW_FALL_TO = 6.0F;

    /** クライアント: 今の段と、その段に入った tick。 */
    private byte glowPhase = PHASE_NONE;
    private int glowPhaseStart;

    /** クライアント: 段が変わった tick を覚える(光の強さを段の中の経過から出すため)。 */
    private void tickGlowPhase() {
        byte p = this.entityData.get(DATA_THROW_PHASE);
        if (p != this.glowPhase) {
            this.glowPhase = p;
            this.glowPhaseStart = this.tickCount;
        }
    }

    /**
     * 尾の背の光の筋の強さ(0〜1)。投げの前触れで点き、球果が離れたら消える(能力の決定「前触れ = 背板が開き、尾の背に光の筋 約 1 秒」)。
     * 前触れのアニメは入るときの補間 {@link #THROW_TRANSITION} tick の後に頭から流れるので、その分を引いて数える。尾の払い・歩き・待機では 0。
     */
    public float tailGlow(float partialTick) {
        if (this.isDeadOrDying()) return 0.0F;
        float t = this.tickCount - this.glowPhaseStart + partialTick;
        if (this.glowPhase == PHASE_WINDUP) {
            return Mth.clamp((t - THROW_TRANSITION - GLOW_RISE_FROM) / (GLOW_RISE_TO - GLOW_RISE_FROM), 0.0F, 1.0F);
        }
        if (this.glowPhase == PHASE_THROW) {
            return Mth.clamp(1.0F - (t - GLOW_FALL_FROM) / (GLOW_FALL_TO - GLOW_FALL_FROM), 0.0F, 1.0F);
        }
        return 0.0F;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) tickGlowPhase();
        if (DEV_HOOKS && this.hasCustomName()) {
            String name = this.getCustomName().getString();
            if (!this.level().isClientSide()) devHooks(name);
            else if (name.contains("trace")) devClientTrace();
        }
    }

    /** テストクライアント用: 名前 nostroll_run… は 10〜20 ブロック先へ急ぎで歩き続け、…trace… は 10 tick ごとに速さを・投げの tick を書く。 */
    private void devHooks(String name) {
        if (name.startsWith("nostroll_run") && this.getNavigation().isDone()) {
            Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this, 20, 4);
            if (p != null && p.distanceToSqr(this.position()) > 10.0D * 10.0D) this.getNavigation().moveTo(p.x, p.y, p.z, 1.0D);
        }
        if (name.contains("trace")) {
            if (this.tickCount % 10 == 0) {
                double dx = this.getX() - this.xo, dz = this.getZ() - this.zo;
                com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                        "STROB_GAIT %s pos %.1f %.1f %.1f 速さ %.3f 経路 %s", name, this.getX(), this.getY(), this.getZ(),
                        Math.sqrt(dx * dx + dz * dz), this.getNavigation().isDone() ? "なし" : "あり"));
            }
            if (this.throwTick == 0 || this.throwTick == RELEASE_AT) {
                com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                        "STROB_THROW server gt %d 投げ %d", this.level().getGameTime(), this.throwTick));
            }
            if (this.sweepTurnTick >= 0 || (this.sweepTick >= 0 && this.sweepTick <= SWEEP_RECOVER_AT)) {
                com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                        "STROB_SWEEP server gt %d 回り %d 払い %d %s 向き %.0f", this.level().getGameTime(), this.sweepTurnTick, this.sweepTick,
                        this.sweepRight ? "右" : "左", this.getYRot()));
            }
        }
    }

    /** クライアント: 投げの層が流しているアニメの名前と、歩きの層の速さを書く(サーバーの tick と比べる)。 */
    private void devClientTrace() {
        AnimationController<StrobilaEntity> c = this.throwController;
        if (c != null && isThrowing()) {
            var q = c.getCurrentAnimation();
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "STROB_THROW client gt %d %s %s 光 %.2f", this.level().getGameTime(), q == null ? "-" : q.animation().name(), c.getAnimationState(),
                    tailGlow(0.0F)));
        }
        if (this.tickCount % 10 == 0) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "STROB_ANIM client 速さ %.3f %s", clientMoveSpeed(1.0F), this.gait.running() ? "run" : "walk/idle"));
        }
    }

    // ------------------------------------------------------------------ アニメ

    /**
     * 下の層 {@code main} = 歩き・走り・待機。上の層 {@code throw} = 投げの 3 本(後に登録するので、流している間はその骨だけ勝つ。
     * 投げていない間は {@link PlayState#STOP} = 下の層がそのまま見える。記憶 geckolib-two-layer-controllers)。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new GaitAnimationController<>(this, "main", 3, this::mainState));
        controllers.add(new AnimationController<>(this, "throw", THROW_TRANSITION, this::throwState));
    }

    private PlayState mainState(AnimationState<StrobilaEntity> state) {
        GaitAnimationController<StrobilaEntity> c = (GaitAnimationController<StrobilaEntity>) state.getController();
        double v = clientMoveSpeed(state.getPartialTick());
        return state.setAndContinue(this.gait.pick(c, v, this.getScale(), !this.isDeadOrDying(), false));
    }

    /** 歩き ⇄ 走りの選び方(共通の部品。数値はこの種のもの)。 */
    private final com.yayuy.overgrownvegetation.entity.crawler.WalkRunGait gait = new com.yayuy.overgrownvegetation.entity.crawler.WalkRunGait(
            WALK_MIN_SPEED, RUN_ON, RUN_OFF, WALK_REF, RUN_REF, WALK_SPEED_MIN, WALK_SPEED_MAX, RUN_SPEED_MIN, RUN_SPEED_MAX);

    /**
     * 投げの層: サーバーが送る段(前触れ・投げ・隙)ごとに 1 本を流す。json の windup・throw は「最後の姿で止まる」で、1 本の並び(thenPlay)に
     * しても止まったまま次へ進まない(11-15 クライアントで確かめた)ので、段の切り替えはサーバーの tick で決める。
     * 入るとき(前触れ)だけ {@link #THROW_TRANSITION} tick 補間し、段どうしは補間なし(前の段の終わりの姿 = 次の段の頭の姿)。
     */
    private PlayState throwState(AnimationState<StrobilaEntity> state) {
        AnimationController<StrobilaEntity> c = state.getController();
        this.throwController = c;
        byte phase = this.isDeadOrDying() ? PHASE_NONE : this.entityData.get(DATA_THROW_PHASE);
        byte before = this.throwPhaseBefore;
        this.throwPhaseBefore = phase;
        if (phase == PHASE_NONE) {
            c.transitionLength(THROW_TRANSITION);
            return PlayState.STOP;
        }
        if (phase != before) {
            c.transitionLength(before == PHASE_NONE ? THROW_TRANSITION : 0);
            if (before == PHASE_NONE) c.forceAnimationReset();   // 前の投げと同じ前触れを流し直す
        }
        return state.setAndContinue(switch (phase) {
            case PHASE_WINDUP -> WINDUP;
            case PHASE_THROW -> THROW;
            case PHASE_RECOVER -> RECOVER;
            case PHASE_SWEEP_WINDUP_L -> SWEEP_WINDUP_L;
            case PHASE_SWEEP_L -> SWEEP_L;
            case PHASE_SWEEP_RECOVER_L -> SWEEP_RECOVER_L;
            case PHASE_SWEEP_WINDUP_R -> SWEEP_WINDUP_R;
            case PHASE_SWEEP_R -> SWEEP_R;
            default -> SWEEP_RECOVER_R;
        });
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
