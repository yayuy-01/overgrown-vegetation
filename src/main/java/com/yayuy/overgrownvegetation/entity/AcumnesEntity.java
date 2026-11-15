package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.flyer.DartHover;
import com.yayuy.overgrownvegetation.entity.flyer.FlightBody;
import com.yayuy.overgrownvegetation.entity.flyer.Flock;
import com.yayuy.overgrownvegetation.entity.flyer.FlyerWind;
import com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer;
import com.yayuy.overgrownvegetation.entity.flyer.SkyProbe;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * アクムネス {@code acumnes}(G5・空の狙撃)。Vault「Mob の能力/アクムネス」。
 *
 * <h2>段 1(2026-11-15): 飛ぶ・止まる・移るまで</h2>
 * 動きは「飛ぶ型」の土台 {@link SkyFlyer} の上に、トンボの「止まっては飛ぶ」({@link DartHover})を {@link #ownFlight} で載せる。
 * <ul>
 *   <li>空中でピタッと止まり(実体は動かさない。わずかな揺れはアニメ)、相手を向く。次の場所へは、まず向きを変えてから短く鋭く飛んで、また止まる。</li>
 *   <li>高さ: 相手の足元の 8〜20 ブロック上【決定 11-14】。相手から水平に 5〜14、見える所(射線が通る所)を優先【仮】。
 *       相手がいないときは家のまわり 16 の林冠 +3〜8【仮】。</li>
 *   <li>撃った後は必ず場所を移す口 {@link #relocate()}(今いる所を「使った場所」に入れ、そこから 4 ブロック以内は選ばない)。
 *       段 1 は撃たないので、相手がいる間は止まるたびに(30〜60 tick)これを呼ぶ。段 2 の狙撃はここを撃った後に呼ぶ。</li>
 *   <li>休む: 相手がいないまま 200 tick【仮】経つと、近くの一番高い木のてっぺんに止まって待つ(土台の止まり木。巨大樹の枝を選ぶ仕組みはまだ)。
 *       近づきすぎる(8、しゃがむと 4)・叩かれると飛び立つ(土台)。止まっている間は叩いた相手にだけ気付く。</li>
 * </ul>
 * 技(狙撃・急降下の刺突・散弾・縫い止め)は段 2。自然には湧かない。アニメは r8 のまま(新しいアニメは Fable のセッションが作る決まり)。
 */
public class AcumnesEntity extends SkyFlyer implements GeoEntity, ParasitizedMob {

    public static final String ID = "acumnes";

    /** 止まり木へ寄るときの飛び方(土台の惰性の飛び方。ほかはトンボの動き)【仮】。 */
    private static final FlightBody.Params PARAMS = new FlightBody.Params();
    /** 止まっては飛ぶ【仮】。 */
    public static final DartHover.Params DART = new DartHover.Params();
    /** 止まる場所の選び方【仮。高さの帯 8〜20 は決定】。 */
    public static final DartHover.SpotRules SPOTS = new DartHover.SpotRules();

    static {
        PARAMS.cruise = 0.6D;
        PARAMS.minSpeed = 0.2D;
        PARAMS.maxSpeed = 1.0D;
        PARAMS.maxTurnDeg = 6.0D;
    }

    /** 止まる長さ(tick)。相手がいるとき・いないとき【仮】。 */
    public static final int HOLD_AIM_MIN = 30, HOLD_AIM_MAX = 60, HOLD_IDLE_MIN = 40, HOLD_IDLE_MAX = 100;
    /** 飛ぶ前に向きを変える速さ(度/tick)と、飛び出してよい向きのずれ(度)・止まっている間に相手へ向く速さ【仮】。 */
    public static final float PIVOT_DEG = 15.0F, PIVOT_OK = 25.0F, AIM_TURN_DEG = 8.0F;
    /** 相手がいないまま止まり木へ向かうまで(tick)【仮】。 */
    public static final int IDLE_BEFORE_PERCH = 200;
    /** 覚えておく「使った場所」の数【仮】。 */
    public static final int USED_MEMORY = 4;
    /** 相手を見失う距離(追跡の距離に掛ける)【仮】。 */
    public static final double LOSE_FACTOR = 1.25D;
    /** 体の芯の中ほど・目(棘の付け根)の高さ(ブロック、足元から)。モデルの胸 y 39.6 単位。 */
    public static final double SIGHT_Y = 2.5D;

    /**
     * 叩ける部位【仮】: 左右の翅(付け根 x ±4.5・先 ±43.6 単位 → 中心 ±1.6 ブロック、幅 2.2、胸の上 y 44 単位)・頭(z −9〜−16 → 前 0.8)・
     * 腹(z 9〜50 → 後ろ 1.0 と 2.4 の 2 つ)。体の芯(1.2 × 3.0、垂らした脚まで)は本体の箱。細い針(産卵管)は叩けない。
     */
    static final String[] PARTS = {"wing_l", "wing_r", "head", "gaster_a", "gaster_b"};
    static final float[] PART_W = {2.2F, 2.2F, 0.8F, 1.0F, 1.0F};
    static final float[] PART_H = {0.5F, 0.5F, 0.8F, 0.8F, 0.8F};
    static final double[] PART_CENTERS = {
            0.0D, 1.6D, 2.75D,
            0.0D, -1.6D, 2.75D,
            0.8D, 0.0D, 2.5D,
            -1.0D, 0.0D, 2.25D,
            -2.4D, 0.0D, 2.05D};

    /** 動きの見せ方(描画へ送る): 止まる・相手を狙って止まる・飛ぶ。 */
    public static final int MOTION_HOLD = 0, MOTION_AIM = 1, MOTION_DART = 2;
    private static final EntityDataAccessor<Byte> DATA_MOTION = SynchedEntityData.defineId(AcumnesEntity.class, EntityDataSerializers.BYTE);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation HOVER = RawAnimation.begin().thenLoop("hover");
    private static final RawAnimation FLY = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("death");
    private static final RawAnimation BUZZ = RawAnimation.begin().thenLoop("wings_buzz");
    // 段 2 のアニメ(2026-11-15 モデル制作。Vault アクムネス 5.3・5.7)
    private static final RawAnimation PERCH_IDLE = RawAnimation.begin().thenLoop("perch_idle");
    private static final RawAnimation WINDUP = RawAnimation.begin().thenPlayAndHold("takeoff_windup");
    private static final RawAnimation TAKEOFF = RawAnimation.begin().thenPlay("takeoff").thenLoop("idle");
    private static final RawAnimation LAND = RawAnimation.begin().thenPlay("land_windup").thenLoop("land");
    private static final RawAnimation LAND_RECOVER = RawAnimation.begin().thenPlay("land_recover").thenLoop("perch_idle");
    private static final RawAnimation DART_FLY = RawAnimation.begin().thenPlay("dart").thenLoop("fly");
    private static final RawAnimation RECOVER_IDLE = RawAnimation.begin().thenPlay("dart_recover").thenLoop("idle");
    private static final RawAnimation RECOVER_HOVER = RawAnimation.begin().thenPlay("dart_recover").thenLoop("hover");
    private static final RawAnimation TURN_L = RawAnimation.begin().thenPlayAndHold("turn_l");
    private static final RawAnimation TURN_R = RawAnimation.begin().thenPlayAndHold("turn_r");
    private static final RawAnimation TURN_L_RECOVER = RawAnimation.begin().thenPlay("turn_l_recover");
    private static final RawAnimation TURN_R_RECOVER = RawAnimation.begin().thenPlay("turn_r_recover");

    /** 飛び立ちの溜め takeoff_windup(8 tick)と、溜めへ入る補間(その分だけ溜めを長くして、溜めの終わりと昇り始めを揃える)。 */
    public static final int WINDUP_TICKS = 8, ENTRY_SHORT = 3;
    /** 途中から入る所の補間(tick)・空中の dart へ入る補間【仮】。 */
    static final int ENTRY = 4, DART_ENTRY = 2;
    /** アニメの長さ(tick): dart・dart_recover・takeoff・turn_*_recover。 */
    static final int DART_ANIM = 5, DART_RECOVER_ANIM = 12, TAKEOFF_ANIM = 18, TURN_RECOVER_ANIM = 9;
    /** 向き直りの層を出す回りの速さ(度/tick)【仮・5.7 の勧め】。 */
    static final float TURN_LAYER_MIN = 3.0F;
    /** 止まっている間(案 A「若枝」: 翅を背の後ろへ畳み、腹を立てる。Vault 5.7 の 10)の叩ける部位の置き場【仮】。 */
    static final double[] PART_CENTERS_PERCHED = {
            -1.2D, 0.5D, 1.05D,
            -1.2D, -0.5D, 1.05D,
            0.75D, 0.0D, 0.85D,
            -1.0D, 0.0D, 1.0D,
            -1.4D, 0.0D, 2.0D};
    /** 送る動き: 土台の飛び方で止まり木へ寄っている(ただの fly。dart の放ちは流さない)。 */
    public static final int MOTION_APPROACH = 3;

    /** クライアント: 今流しているつなぎ・流し始めた tick・入りの補間・この tick の向きの回り(度)。 */
    private RawAnimation curAnim;
    private int curStart, curEntry;
    private float turnRate;
    /** クライアント: 向き直りの層(−1 = 左・1 = 右・0 = 止めている)と、揺れ戻しを始めた tick(−1 = まだ)。 */
    private int turnDir, turnRecStart = -1;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // --- サーバー ---
    private final DartHover hover = new DartHover();
    private DartHover.Phase lastPhase = DartHover.Phase.HOLD;
    private int holdLeft;
    /** 向きを変え終えたら飛ぶ行き先(無ければ NaN)。 */
    private double pendX = Double.NaN, pendY, pendZ;
    /** 場所を移さなければならない(選べなかったので待っている)。 */
    private boolean mustMove;
    private final double[] used = new double[USED_MEMORY * 3];
    private int usedCount, usedNext;
    private int idleTicks;
    private final double[] windNow = new double[2];
    /** 相手がいないときに飛び回る中心(最初に飛んだ所。保存しない)。 */
    private double homeX = Double.NaN, homeZ = Double.NaN;
    private int lastOwnTick = Integer.MIN_VALUE;
    /** 計測: 場所を移した回数(テスト用)。 */
    public int relocations;

    public AcumnesEntity(EntityType<? extends AcumnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 40);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    // ------------------------------------------------------------------ 土台へ渡す値

    @Override
    protected FlightBody.Params params() {
        return PARAMS;
    }

    /** 止まり木へ寄る間の高さ(林冠 + これ)。トンボの動きの間は使わない【仮】。 */
    @Override
    protected double layerMin() {
        return 3.0D;
    }

    @Override
    protected double layerMax() {
        return 8.0D;
    }

    /** 1 体ずつ(群れは組まない。G5 は 1 体ずつ湧く)。 */
    @Override
    protected int flockMax() {
        return 1;
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

    /** 止まった姿のままか(止まっている・飛び立ちの待ちと溜め)。送られた状態で決めるのでサーバーもクライアントも同じ。 */
    private boolean perchedPose() {
        int m = mode();
        return m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() < 2;
    }

    /**
     * 木から飛び立つ前の溜め【決定 11-15 ユーザー: 溜めるのは枝(今は木のてっぺん)から飛び立つときだけ】。
     * 空中の「止まっては飛ぶ」は溜めずに dart で鋭く移る。
     */
    @Override
    protected int takeoffWindupTicks() {
        return WINDUP_TICKS + ENTRY_SHORT;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MOTION, (byte) MOTION_HOLD);
    }

    public int motion() {
        return this.entityData.get(DATA_MOTION);
    }

    /** 相手がいないまま {@link #IDLE_BEFORE_PERCH} tick 経ったら止まり木で待つ(夜・雨は関係ない)。 */
    @Override
    public boolean wantsRoost() {
        if (this.roostOverride != null) return this.roostOverride;
        return this.getTarget() == null && this.idleTicks > IDLE_BEFORE_PERCH;
    }

    // ------------------------------------------------------------------ 相手

    @Override
    protected void customServerAiStep() {
        if (this.level() instanceof ServerLevel sl) tickTargeting(sl);
        super.customServerAiStep();
        // 止まり木へ寄る間(土台の飛び方)は飛ぶ姿に
        // (昇り終えた最初の tick も土台の飛び方になるが、そこは止まり木へ寄るのではないので fly を挟まない)
        if (mode() == MODE_FLY && this.lastOwnTick != this.tickCount && wantsRoost() && this.entityData.get(DATA_MOTION) != MOTION_APPROACH) {
            this.entityData.set(DATA_MOTION, (byte) MOTION_APPROACH);
        } else if (mode() != MODE_FLY && this.entityData.get(DATA_MOTION) != MOTION_HOLD) {
            this.entityData.set(DATA_MOTION, (byte) MOTION_HOLD);   // 降りる・止まる・飛び立つ間は土台の段で決める。昇り終えた所で寄る印が残らないように
        }
    }

    /**
     * 狙ってよい相手か【決定 11-15 ユーザー】: 植物側の Mob 以外すべて(プレイヤー・村人・同盟軍・ゴーレム・動物・ほかの敵対 Mob)。
     * 植物側(G1〜G10・萌芽体・ハイヴノード・汎用の感染体 = {@link SporeExposureTracker#isPlantSide})・創造/観戦のプレイヤー・防具立て・無敵の相手は狙わない。
     * 狙っている相手が感染して植物側になったら、そこで忘れる。
     */
    public static boolean isPrey(LivingEntity self, LivingEntity e) {
        return e != self && e.isAlive() && !e.isRemoved() && !(e instanceof ArmorStand) && e.canBeSeenAsEnemy()
                && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(e)
                && !(e instanceof ParasitizedMob) && !SporeExposureTracker.isPlantSide(e);
    }

    /**
     * 相手の選び方【仮・Claude 11-15】: 叩いた相手 → 見えるプレイヤー → 見えるほかの生き物(近い順)。
     * プレイヤー以外を狙っている間も、見えるプレイヤーが現れたらそちらへ替える(狙撃手はまずプレイヤーを狙う)。
     */
    private void tickTargeting(ServerLevel sl) {
        LivingEntity t = this.getTarget();
        double range = this.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (t != null && (t.level() != this.level() || !isPrey(this, t)
                || this.distanceToSqr(t) > range * range * LOSE_FACTOR * LOSE_FACTOR)) {
            this.setTarget(null);
            t = null;
        }
        // 止まっている間は、叩かれる・近づかれて飛び立つ(土台)まで気付かない
        boolean looking = this.tickCount % 10 == 0 && mode() != MODE_PERCHED && mode() != MODE_LANDING;
        if (t == null) {
            LivingEntity h = this.getLastHurtByMob();
            if (h != null && h.level() == this.level() && isPrey(this, h) && this.distanceToSqr(h) < range * range) {
                this.setTarget(h);
                t = h;
            } else if (looking) {
                t = findPrey(sl, range);
                if (t != null) this.setTarget(t);
            }
        } else if (looking && !(t instanceof Player) && t != this.getLastHurtByMob()) {
            Player p = sl.getNearestPlayer(preyConditions(range), this);
            if (p != null) {
                this.setTarget(p);
                t = p;
            }
        }
        this.idleTicks = t == null ? this.idleTicks + 1 : 0;
    }

    private TargetingConditions preyConditions(double range) {
        return TargetingConditions.forCombat().range(range).selector(e -> isPrey(this, e));
    }

    /** 見える相手: プレイヤーを先に、いなければ近い生き物。 */
    private LivingEntity findPrey(ServerLevel sl, double range) {
        TargetingConditions c = preyConditions(range);
        Player p = sl.getNearestPlayer(c, this);
        if (p != null) return p;
        return sl.getNearestEntity(LivingEntity.class, c, this, this.getX(), this.getEyeY(), this.getZ(),
                this.getBoundingBox().inflate(range, range, range));
    }

    // ------------------------------------------------------------------ 止まっては飛ぶ

    @Override
    protected boolean ownFlight(ServerLevel sl, Flock f) {
        LivingEntity t = this.getTarget();
        double x = this.getX(), y = this.getY(), z = this.getZ();
        if (Double.isNaN(this.homeX)) {
            this.homeX = x;
            this.homeZ = z;
        }
        if (this.lastOwnTick != this.tickCount - 1) {
            // 飛び立った直後・止まり木へ寄るのをやめた直後: その場でピタッと止まってから選び直す
            this.hover.holdAt(x, y, z);
            this.lastPhase = DartHover.Phase.HOLD;
            this.holdLeft = 10;
            this.pendX = Double.NaN;
            this.idleTicks = 0;   // 飛び立った直後にまた同じ木へ降りない(相手がいなくても 200 tick は飛び回る)
        }
        this.lastOwnTick = this.tickCount;
        if (this.hover.holding()) {
            if (!Double.isNaN(this.pendX)) {
                // 飛ぶ前に向きを変える(その場で回る)
                double yaw = FlightBody.yawOf(this.pendX - x, this.pendZ - z);
                double hd = Math.sqrt((this.pendX - x) * (this.pendX - x) + (this.pendZ - z) * (this.pendZ - z));
                if (hd > 1.0D) turnToward(yaw, PIVOT_DEG);
                if (hd <= 1.0D || Math.abs(Mth.wrapDegrees((float) yaw - this.getYRot())) < PIVOT_OK) {
                    this.hover.dartTo(this.pendX, this.pendY, this.pendZ);
                    this.pendX = Double.NaN;
                }
            } else {
                if (t != null) turnToward(FlightBody.yawOf(t.getX() - x, t.getZ() - z), AIM_TURN_DEG);
                if (this.hover.settled() && --this.holdLeft <= 0) {
                    if (this.mustMove) {
                        chooseNext(sl, true);   // 前に選べなかった移動の選び直し
                    } else if (t != null) {
                        relocate();   // 段 1: 撃たないが、止まり終えたら撃った後と同じく場所を移す
                    } else {
                        chooseNext(sl, false);
                    }
                }
            }
        } else {
            double hd = Math.sqrt((this.hover.tx - x) * (this.hover.tx - x) + (this.hover.tz - z) * (this.hover.tz - z));
            if (hd > 1.0D) turnToward(FlightBody.yawOf(this.hover.tx - x, this.hover.tz - z), PIVOT_DEG);
        }
        FlyerWind.now(sl, this.windNow);
        this.hover.step(DART, x, y, z, this.windNow[0], this.windNow[1], this.horizontalCollision || this.verticalCollision);
        if (this.hover.phase != this.lastPhase) {
            if (this.hover.phase == DartHover.Phase.HOLD) {
                this.holdLeft = t != null ? Mth.nextInt(this.random, HOLD_AIM_MIN, HOLD_AIM_MAX) : Mth.nextInt(this.random, HOLD_IDLE_MIN, HOLD_IDLE_MAX);
            }
            this.lastPhase = this.hover.phase;
        }
        setOwnMotion(this.hover.vx, this.hover.vy, this.hover.vz);
        // 飛ぶ前にその場で向きを変える間は止まった姿のまま(向き直りの層が体をひねる)
        int m = this.hover.holding() ? (t != null ? MOTION_AIM : MOTION_HOLD) : MOTION_DART;
        if (this.entityData.get(DATA_MOTION) != m) this.entityData.set(DATA_MOTION, (byte) m);
        if (DEV_TRACE && this.hasCustomName() && this.getCustomName().getString().contains("trace") && this.tickCount % 10 == 0) {
            LOG.info("ACUMNES_TRACE {} 段 {} 位置 {} {} {} 速さ {} 相手 {} 相手の上 {} 移った {}", this.getId(), this.hover.phase,
                    String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z), String.format("%.3f", this.hover.speed()),
                    t == null ? "-" : t.getName().getString(), t == null ? "-" : String.format("%.1f", y - t.getY()), this.relocations);
        }
        return true;
    }

    private static final boolean DEV_TRACE = Boolean.getBoolean("ov.devbridge");
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /**
     * 場所を移す(段 2 の技から、撃った後に呼ぶ口)。今いる所を「使った場所」に入れ({@link #USED_MEMORY} か所まで覚える)、
     * そこから {@link DartHover.SpotRules#reuseGap} 以内ではない次の場所へ、向きを変えてから鋭く飛ぶ。選べなければ少し待って選び直す。
     */
    public void relocate() {
        rememberUsed(this.getX(), this.getY(), this.getZ());
        this.mustMove = true;
        if (this.level() instanceof ServerLevel sl) chooseNext(sl, true);
    }

    private void rememberUsed(double x, double y, double z) {
        this.used[this.usedNext * 3] = x;
        this.used[this.usedNext * 3 + 1] = y;
        this.used[this.usedNext * 3 + 2] = z;
        this.usedNext = (this.usedNext + 1) % USED_MEMORY;
        this.usedCount = Math.min(USED_MEMORY, this.usedCount + 1);
    }

    private void chooseNext(ServerLevel sl, boolean forced) {
        LivingEntity t = this.getTarget();
        double hx = this.homeX(), hz = this.homeZ();
        double[] s = DartHover.pickSpot(SPOTS, new WorldSpace(sl, t), this.random::nextDouble, this.getX(), this.getY(), this.getZ(),
                t != null, t == null ? 0.0D : t.getX(), t == null ? 0.0D : t.getY(), t == null ? 0.0D : t.getZ(), hx, hz, this.used, this.usedCount);
        if (s == null) {
            this.holdLeft = 10;   // どこも選べない: 少し待って選び直す(移らねばならないなら、そのまま覚えておく)
            return;
        }
        this.pendX = s[0];
        this.pendY = s[1];
        this.pendZ = s[2];
        this.mustMove = false;
        if (forced) this.relocations++;
    }

    private double homeX() {
        return Double.isNaN(this.homeX) ? this.getX() : this.homeX;
    }

    private double homeZ() {
        return Double.isNaN(this.homeZ) ? this.getZ() : this.homeZ;
    }

    /** 世界を {@link DartHover.Space} として見る。 */
    private final class WorldSpace implements DartHover.Space {
        private final ServerLevel sl;
        private final LivingEntity target;

        WorldSpace(ServerLevel sl, LivingEntity target) {
            this.sl = sl;
            this.target = target;
        }

        @Override
        public boolean fits(double x, double y, double z) {
            if (!this.sl.hasChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4)) return false;
            if (y < this.sl.getMinBuildHeight() + 1 || y + AcumnesEntity.this.getBbHeight() > this.sl.getMaxBuildHeight()) return false;
            AABB box = AcumnesEntity.this.getDimensions(AcumnesEntity.this.getPose()).makeBoundingBox(x, y, z);
            return this.sl.noCollision(AcumnesEntity.this, box) && !this.sl.containsAnyLiquid(box);
        }

        @Override
        public boolean clearPath(double x0, double y0, double z0, double x1, double y1, double z1) {
            double h = AcumnesEntity.this.getBbHeight();
            for (double oy : new double[]{0.25D, h * 0.5D, h - 0.25D}) {
                if (this.sl.clip(new ClipContext(new Vec3(x0, y0 + oy, z0), new Vec3(x1, y1 + oy, z1),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, AcumnesEntity.this)).getType() != HitResult.Type.MISS) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean canSee(double x, double y, double z) {
            if (this.target == null) return false;
            return this.sl.clip(new ClipContext(new Vec3(x, y + SIGHT_Y, z), this.target.getEyePosition(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, AcumnesEntity.this)).getType() == HitResult.Type.MISS;
        }

        @Override
        public double floorAt(double x, double z) {
            return SkyProbe.canopy(this.sl, Mth.floor(x), Mth.floor(z));
        }
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", ENTRY, this::mainState));
        controllers.add(new AnimationController<>(this, "buzz", 2, this::buzzState));
        // 向き直りの層(main の後。骨は pitch・needle_aim・翅の付け根 wing_f/h_* だけ。うなりの層の骨とは重ならない)
        controllers.add(new AnimationController<>(this, "turn", 2, this::turnState));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) this.turnRate = Mth.wrapDegrees(this.getYRot() - this.yRotO);
    }

    /**
     * つなぎを選ぶ(Vault 5.7。作りはヴェロフィルと同じ): 入りの補間は流し始めにだけ使い、過ぎたら 0 にする(つないだ段の間は差 0。
     * GeckoLib は段の間でも補間の長さを使うので)。
     * <ul>
     *   <li>止まる = perch_idle。降りてきたら止まった tick に land_recover → perch_idle。セーブから止まった姿で読み込んだ個体は直に perch_idle。</li>
     *   <li>飛び立つ = その場で takeoff_windup → 昇り始めに takeoff → idle。降りている途中からは溜めずに takeoff。</li>
     *   <li>降りる = land_windup → land(繰り返し)。止まり木へ寄る間は fly。</li>
     *   <li>空中で移る = 溜めずに dart → fly → 止まった tick に dart_recover → idle(相手がいれば hover)【決定 11-15: 溜めは枝から飛び立つときだけ】。</li>
     * </ul>
     */
    private PlayState mainState(AnimationState<AcumnesEntity> state) {
        if (this.isDeadOrDying()) {
            this.curAnim = DEATH;
            state.getController().transitionLength(ENTRY);
            return state.setAndContinue(DEATH);
        }
        int m = mode();
        RawAnimation cur = this.curAnim;
        int age = this.tickCount - this.curStart;
        RawAnimation want;
        int entry;
        if (m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() == 0) {
            if (cur == LAND || cur == LAND_RECOVER) {
                want = LAND_RECOVER;   // 止まった tick に掴む(land の途中から入るので短く補間)
                entry = cur == LAND ? ENTRY_SHORT : this.curEntry;
            } else {
                want = PERCH_IDLE;
                entry = ENTRY;
            }
        } else if (m == MODE_TAKEOFF && takeoffPhase() == 1) {
            want = WINDUP;
            entry = cur == WINDUP ? this.curEntry : ENTRY_SHORT;
        } else if (m == MODE_TAKEOFF) {
            want = TAKEOFF;
            entry = cur == WINDUP ? 0 : (cur == TAKEOFF ? this.curEntry : ENTRY);   // 溜めの終わり = takeoff の 0 秒(差 0)
        } else if (m == MODE_LANDING) {
            want = LAND;
            entry = cur == LAND ? this.curEntry : ENTRY;
        } else {
            int mo = motion();
            RawAnimation loop = mo == MOTION_AIM ? HOVER : IDLE;
            if (mo == MOTION_APPROACH) {
                want = FLY;
                entry = cur == FLY ? this.curEntry : ENTRY;
            } else if (mo == MOTION_DART) {
                want = DART_FLY;
                entry = cur == DART_FLY ? this.curEntry : DART_ENTRY;
            } else if (cur == DART_FLY) {
                want = mo == MOTION_AIM ? RECOVER_HOVER : RECOVER_IDLE;   // 着いて止まった tick に(fly → dart_recover は差 0)
                entry = 0;
            } else if ((cur == RECOVER_IDLE || cur == RECOVER_HOVER) && (age < DART_RECOVER_ANIM || (cur == RECOVER_IDLE) == (loop == IDLE))) {
                want = cur;   // 揺れ戻しは最後まで。その後は繰り返しの方が合っていればそのまま
                entry = this.curEntry;
            } else if (cur == TAKEOFF && (age < TAKEOFF_ANIM || loop == IDLE)) {
                want = cur;   // takeoff の後はそのまま idle を繰り返している
                entry = this.curEntry;
            } else {
                want = loop;
                entry = cur == loop ? this.curEntry : ENTRY;
            }
        }
        if (want != cur) {
            if (DEV_TRACE && this.hasCustomName() && this.getCustomName().getString().contains("trace")) {
                LOG.info("ACUMNES_ANIM {} t {} {} -> {} 補間 {} 動き {} 段 {}", this.getId(), this.tickCount, animName(cur), animName(want), entry, m, motion());
            }
            this.curAnim = want;
            this.curStart = this.tickCount;
            this.curEntry = entry;
            age = 0;
        }
        boolean chained = want == TAKEOFF || want == LAND || want == LAND_RECOVER || want == WINDUP
                || want == DART_FLY || want == RECOVER_IDLE || want == RECOVER_HOVER;
        state.getController().transitionLength(chained && age > this.curEntry ? 0 : this.curEntry);
        return state.setAndContinue(want);
    }

    /**
     * 翅のうなり(別の層)。止まっている間・飛び立ちの待ちと溜めの間・死ぬときは止める。昇り始める tick(takeoff)から入れ、
     * 降りて止まった tick(land_recover の 0 tick)に止める。
     */
    private PlayState buzzState(AnimationState<AcumnesEntity> state) {
        int m = mode();
        if (this.isDeadOrDying() || m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() < 2) return PlayState.STOP;
        return state.setAndContinue(BUZZ);
    }

    /**
     * 向き直りの層(Vault 5.7 の 5): 浮いて止まっている間(main が idle・hover・dart_recover)に向きが回っている間 turn_l か turn_r
     * (終わりで止まる)、回り終えたら turn_*_recover(9 tick)を流して止める。1 tick に {@link #TURN_LAYER_MIN} 度より小さい回りでは出さない。
     * 左 = yaw が減る向き(ゲーム内で逆に見えたら入れ替える。未確認)。
     * 飛ぶ前の向き直りが終わるとすぐ dart で飛び出すので、揺れ戻しは dart の 5 tick の間だけ続け、fly へ入る所で止める
     * (止める tick に翅の付け根などが main の値へ跳ぶ。ゲーム内で目立つならアニメの側で直す = モデル制作へ)。
     */
    private static String animName(RawAnimation a) {
        if (a == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (RawAnimation.Stage s : a.getAnimationStages()) {
            if (sb.length() > 0) sb.append('>');
            sb.append(s.animationName());
        }
        return sb.toString();
    }

    private PlayState turnState(AnimationState<AcumnesEntity> state) {
        int m = mode();
        RawAnimation cur = this.curAnim;
        boolean hovering = m == MODE_FLY && !this.isDeadOrDying()
                && (cur == IDLE || cur == HOVER || cur == RECOVER_IDLE || cur == RECOVER_HOVER || cur == TAKEOFF && this.tickCount - this.curStart >= TAKEOFF_ANIM);
        boolean darting = m == MODE_FLY && cur == DART_FLY && this.tickCount - this.curStart < DART_ANIM;
        if (!hovering && !darting) {
            this.turnDir = 0;
            this.turnRecStart = -1;
            return PlayState.STOP;
        }
        float r = this.turnRate;
        if (hovering && Math.abs(r) >= TURN_LAYER_MIN) {
            int d = r < 0.0F ? -1 : 1;
            if (d != this.turnDir && DEV_TRACE && this.hasCustomName() && this.getCustomName().getString().contains("trace")) {
                LOG.info("ACUMNES_TURN {} t {} {} 回り {}", this.getId(), this.tickCount, d < 0 ? "turn_l" : "turn_r", String.format("%.1f", r));
            }
            this.turnDir = d;
            this.turnRecStart = -1;
            return state.setAndContinue(this.turnDir < 0 ? TURN_L : TURN_R);
        }
        if (this.turnDir == 0) return PlayState.STOP;
        if (this.turnRecStart < 0) this.turnRecStart = this.tickCount;
        if (this.tickCount - this.turnRecStart >= TURN_RECOVER_ANIM) {
            this.turnDir = 0;
            this.turnRecStart = -1;
            return PlayState.STOP;
        }
        return state.setAndContinue(this.turnDir < 0 ? TURN_L_RECOVER : TURN_R_RECOVER);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    /**
     * 全長 10.6(頭の前 3・針の先が後ろ 7.6)・翅の幅 5.5。止まった姿(案 A)は針の先が足元 +7.35、
     * dive_windup などで針が足元の 4.9 下まで下がる(Vault 5.6・5.7 の 11)ので、上 +7.5・下 −5。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        return new AABB(this.getX() - 8.0D, this.getY() - 5.0D, this.getZ() - 8.0D, this.getX() + 8.0D, this.getY() + 7.5D, this.getZ() + 8.0D);
    }

    // ------------------------------------------------------------------ テスト・計測

    public DartHover hover() {
        return this.hover;
    }

    /** テスト用: 今いる所で止まる。 */
    public void debugHoldHere(int ticks) {
        this.hover.holdAt(this.getX(), this.getY(), this.getZ());
        this.lastPhase = DartHover.Phase.HOLD;
        this.holdLeft = ticks;
        this.pendX = Double.NaN;
        this.lastOwnTick = this.tickCount;
    }

    public int usedCount() {
        return this.usedCount;
    }

    public boolean mustMove() {
        return this.mustMove;
    }

    /**
     * 死に際のアニメ death(1.5 秒)を最後まで流し、その後に膨らんで破裂する(11-15 ユーザー決定「アニメの後に破裂」)。
     * バニラの 20 tick では消さない。消すのは {@code corpse/PlantSideBurst}、ここは破裂が外されたときの控え。
     */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.deathTime >= com.yayuy.overgrownvegetation.spore.PlantSideRules.burstTick(this) + 2 && !this.level().isClientSide() && !this.isRemoved()) {
            this.level().broadcastEntityEvent(this, (byte) 60);
            this.remove(RemovalReason.KILLED);
        }
    }
}
