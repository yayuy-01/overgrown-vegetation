package com.yayuy.overgrownvegetation.entity.flyer;

import com.yayuy.overgrownvegetation.entity.parts.HitPartSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 「飛ぶ型」の土台(2026-11-15、ヴェロフィル段 1。後でセミナリア・アクムネス・ペディキュラも継ぐ)。種に依らない所だけを持つ。
 *
 * <h2>作り</h2>
 * <ul>
 *   <li>重力なしの Monster。移動は自前の {@link #travel}({@link FlightBody} の惰性・傾き・風)。FlyingMob・飛ぶ経路探索は使わない
 *       (記憶 flying-mob-ai-traps: 止めた FlyingMob は動かない・空の経路は重い)。Goal も使わず、判断は {@link #customServerAiStep}。</li>
 *   <li>高さ: 先の林冠({@link SkyProbe#canopyAhead}、4 tick ごと)+ 群れの高さの層 + 個体のずれ。</li>
 *   <li>枝・葉: 体の芯の高さで前・左右の線を 4 tick ごとに見て、塞がっていれば空いた側へ曲がって昇る。ぶつかったら大きく向きを変える。</li>
 *   <li>群れ: {@link Flock}(V 字・先頭の交代・止まり木)。卵などで 1 体で出た個体は、近くの群れ({@link #FLOCK_JOIN_RANGE} 以内・上限まで)に入る。</li>
 *   <li>止まる: 夜と雨({@link #wantsRoost})。止まり木へ降りる({@link #MODE_LANDING})→ 止まる({@link #MODE_PERCHED}、風上を向く)→
 *       飛び立つ({@link #MODE_TAKEOFF})。止まっている間にプレイヤーが近づく・叩かれる・足場が消えると群れごと一斉に飛び立つ。</li>
 *   <li>体の傾き・機首の上下は描画がクライアントで出す({@link #bank}・{@link #pitch})。</li>
 * </ul>
 * 叩ける部位(翼など)は種が {@link #partNames} などで渡す(ファルキムネスの {@code entity/parts/})。地形とぶつかるのは芯の箱だけ。
 */
public abstract class SkyFlyer extends Monster {

    public static final int MODE_FLY = 0, MODE_LANDING = 1, MODE_PERCHED = 2, MODE_TAKEOFF = 3;
    private static final int FLAP_BIT = 4;
    /** 送る状態の印(2026-11-15 ヴェロフィル段 2): 身構え(止まっていて相手に気付いた)・飛び立ちの溜め・飛び立ちの昇り。 */
    private static final int ALERT_BIT = 8, WINDUP_BIT = 16, CLIMB_BIT = 32;
    private static final EntityDataAccessor<Byte> DATA_STATE = SynchedEntityData.defineId(SkyFlyer.class, EntityDataSerializers.BYTE);

    /** 群れに入る距離(ブロック)【仮】。 */
    public static final double FLOCK_JOIN_RANGE = 48.0D;
    /** 林冠を先読みする距離(ブロック)【仮】。 */
    static final double[] CANOPY_AHEAD = {0.0D, 8.0D, 16.0D, 24.0D};
    /** 林冠の上の最低の余裕(後続にも効く)【仮】。 */
    public static final double MIN_CLEARANCE = 4.0D;
    /** 前を見る長さ・曲げて見る角度【仮】。 */
    static final double FEELER = 10.0D;
    static final double[] FEELER_SIDES = {30.0D, 60.0D, 90.0D};
    /** 止まっている群れを驚かせる距離(しゃがむと半分)【仮】。 */
    public static final double FLUSH_RANGE = 8.0D, FLUSH_RANGE_SNEAK = 4.0D;
    /** 降りる・飛び立つ速さ【仮】。 */
    static final double LAND_H = 0.15D, LAND_DOWN = 0.15D, TAKEOFF_UP = 0.25D, TAKEOFF_FWD_MAX = 0.4D;
    static final int TAKEOFF_TICKS = 18;
    /** 群れを率いる先頭の向きのずれの上限(度)【仮】。回る速さ = これ × 0.12 ≒ 1.2 度/tick(巡航で半径 約 33)。 */
    static final double LEADER_TURN_ERR = 10.0D;

    public final FlightBody body = new FlightBody();
    private final HitPartSet hitParts;

    // --- 群れ(サーバー) ---
    @Nullable
    Flock flock;
    @Nullable
    private UUID flockId;
    private int savedSlot = -1;
    @Nullable
    private BlockPos home;
    private double layer = Double.NaN;
    /** 個体ごとの高さのずれ(±1)。 */
    private double personalAlt;
    /** 割り当てられた止まり木(この上に立つ)。 */
    @Nullable
    BlockPos perch;
    int takeoffDelay;
    /** 飛び立ちの溜めの残り・昇った tick(サーバー)。 */
    private int windupLeft, climbTicks;

    // --- 動き(サーバー) ---
    private int mode = MODE_FLY;
    private int modeTicks;
    private double cmdHeading, cmdSpeed, cmdY;
    private double kinX, kinY, kinZ;
    private int avoidTicks;
    private double avoidHeading, climbBoost;
    private double canopyAhead = Double.NaN;
    private double scatterJitter;
    /** 計測: 隊列の自分の位置からのずれ(ブロック。先頭・群れの外は 0)。 */
    public double formationErr;
    private boolean flap = true;
    private boolean glidePhase;
    private int cycleTicks;
    private final double[] wind = new double[2];
    private final double[] tmp = new double[3];
    private final VFormation.Command cmd = new VFormation.Command();
    /** 個体ごとの揺れの位相(ひらひら・ゆるい群れの居場所。2026-11-15 セミナリアで足した)。 */
    private final double flutterPhase1, flutterPhase2, loosePhase;
    /** テスト用: 夜・雨の判断を上書きする(null = ふつう)。 */
    @Nullable
    public Boolean roostOverride;
    /** テスト用: ほかの群れへ移らない(並んで走るテストどうしが混ざらないように)。 */
    public boolean debugSolo;

    // --- 描画(クライアント) ---
    public float bank, bankO, pitch, pitchO;

    protected SkyFlyer(EntityType<? extends SkyFlyer> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.hitParts = new HitPartSet(this, partNames(), partWidths(), partHeights());
        this.setId(ENTITY_COUNTER.getAndAdd(this.hitParts.count() + 1) + 1);
        this.personalAlt = (this.random.nextDouble() * 2.0D - 1.0D);
        this.body.heading = this.getYRot();
        this.body.speed = params().cruise * 0.6D;
        this.cycleTicks = 40 + this.random.nextInt(40);
        this.flutterPhase1 = this.random.nextDouble() * Math.PI * 2.0D;
        this.flutterPhase2 = this.random.nextDouble() * Math.PI * 2.0D;
        this.loosePhase = this.random.nextDouble() * Math.PI * 2.0D;
    }

    // ------------------------------------------------------------------ 種が決めること

    /** 飛び方の値。 */
    protected abstract FlightBody.Params params();

    /** 林冠からの高さの層の範囲(群れごとにこの中から 1 つ)。 */
    protected abstract double layerMin();

    protected abstract double layerMax();

    /** 群れの上限。 */
    protected abstract int flockMax();

    /** 叩ける部位。中心は [前, 左, 上](ブロック、足元から)× 部位の数。 */
    protected abstract String[] partNames();

    protected abstract float[] partWidths();

    protected abstract float[] partHeights();

    protected abstract double[] partCenters();

    /** 後続が V 字を組まず、先頭のまわりにゆるくまとまる({@link LooseFormation})なら true(2026-11-15 セミナリアで足した。既定は V 字)。 */
    protected boolean looseFlock() {
        return false;
    }

    /**
     * 着かねばならない行き先(家へ戻る巡回・止まり木へ寄る)へ向かう間、風に逆らって進路を保つなら true(2026-11-15 セミナリアで足した。既定 false)。
     * true の種は、その間だけ「地面に対して行きたい速さ − 風」を空気に対する向きと速さにする(横風には斜めに構え、向かい風には上限まで羽ばたく)。
     * ふだんの巡回は風に任せたまま。
     */
    protected boolean fightsWind() {
        return false;
    }

    /**
     * 巡回の次の行き先を選ぶときの扇の真ん中の向き(度。2026-11-15 セミナリアで足した)。既定は今の向き(まっすぐ先寄り)。
     * 風に乗る種は風下を返す(行き先が風下寄りになり、家から離れすぎたら {@link #fightsWind} で戻る)。
     */
    protected double patrolFanCenter() {
        return this.body.heading;
    }

    /** 今の風の吹いていく向き(度、Minecraft の yaw)。凪(0.02 ブロック/tick 未満)なら NaN。種の判断用。 */
    protected final double downwindYaw() {
        if (!(this.level() instanceof ServerLevel sl)) return Double.NaN;
        readWind(sl);
        double s = Math.sqrt(this.wind[0] * this.wind[0] + this.wind[1] * this.wind[1]);
        return s < 0.02D ? Double.NaN : FlightBody.yawOf(this.wind[0], this.wind[1]);
    }

    /**
     * 種が自分で飛び方を決める口(2026-11-15 アクムネスで足した。既定は false = 土台の飛び方)。飛んでいる間({@link #MODE_FLY})、
     * 群れが止まり木へ向かっていない(ROOST でない)ときに毎 tick 呼ぶ。true を返した tick は土台の巡回・隊列・惰性({@link FlightBody})を飛ばし、
     * {@link #setOwnMotion} で渡した量だけそのまま動く(惰性・風なし。ピタッと止まる・短く鋭く飛ぶ型のため。{@link DartHover})。
     * 降りる・止まる・飛び立つは土台のまま。
     */
    protected boolean ownFlight(ServerLevel sl, Flock f) {
        return false;
    }

    /** {@link #ownFlight} の間、機首の上下を動く向きに合わせるなら true(2026-11-15 ヴェロフィルの急降下で足した。既定 false = 水平)。 */
    protected boolean ownFlightPitches() {
        return false;
    }

    /** {@link #ownFlight} の間に、この tick に動く量(ブロック)を渡す。 */
    protected final void setOwnMotion(double vx, double vy, double vz) {
        this.kinX = vx;
        this.kinY = vy;
        this.kinZ = vz;
    }

    /** 体を yaw(度)へ 1 tick あたり maxStep 度まで回す(飛ぶ向きも合わせる)。 */
    protected final void turnToward(double yaw, float maxStep) {
        faceToward(yaw, maxStep);
    }

    /** この tick に {@link #ownFlight} が動きを決めたか(サーバー)。 */
    private boolean ownFlightNow;

    /**
     * 飛び立つ前にその場で溜める tick(2026-11-15 ヴェロフィル段 2 で足した。既定 0 = 今まで通りすぐ昇る)。
     * 止まった姿から飛ぶとき(朝・驚いた)だけ。降りている途中から飛び立つときは溜めない。群れのずらし({@link #takeoffDelay})はその前。
     */
    protected int takeoffWindupTicks() {
        return 0;
    }

    /**
     * 止まっている間にプレイヤーに気付いて身構える距離(2026-11-15 ヴェロフィル段 2。既定 0 = 身構えない)。
     * 群れの誰かが気付くと群れ全員が身構え、気付いた相手の方を向く。飛び立つのは {@link #flushRange}(既定 {@link #FLUSH_RANGE})。
     */
    protected double alertRange() {
        return 0.0D;
    }

    /**
     * 止まり木の真上に空けておく高さ(2026-11-15 アクムネス段 2 で足した。既定 {@link SkyProbe#CLEAR_UP} = 6 = 今まで通り)。
     * 止まった姿が高い種(アクムネスの若枝 7.35)は大きくする。群れの止まり木は先頭の値で探す。
     */
    protected double perchClearUp() {
        return SkyProbe.CLEAR_UP;
    }

    /**
     * 止まっている群れが一斉に飛び立つ距離(2026-11-15 セミナリア段 2 で足した。既定 {@link #FLUSH_RANGE} = 今まで通り)。
     * しゃがんでいるときは同じ割合で縮める(既定 8 → 4)。
     */
    protected double flushRange() {
        return FLUSH_RANGE;
    }

    /** 群れが逃げている間({@link Flock.State#FLEE})に先頭が出す速さ(2026-11-16 セミナリアで足した。既定 = 巡航 = 今まで通り)。 */
    protected double fleeSpeed() {
        return params().cruise;
    }

    // ------------------------------------------------------------------ 状態

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STATE, (byte) 0);
    }

    /** 今の動き(サーバーは本当の値、クライアントは送られた値)。 */
    public int mode() {
        return this.level().isClientSide ? (this.entityData.get(DATA_STATE) & 3) : this.mode;
    }

    /** 羽ばたいているか(描画。滑空なら false)。 */
    public boolean flapping() {
        return (this.entityData.get(DATA_STATE) & FLAP_BIT) != 0;
    }

    /** 身構えているか(描画。止まっている間と、飛び立ちの溜めの前の待ちの間)。 */
    public boolean alerted() {
        return (this.entityData.get(DATA_STATE) & ALERT_BIT) != 0;
    }

    /** 飛び立ちの段(描画): 0 = 待ち(止まった姿のまま)、1 = 溜め、2 = 昇っている。{@link #MODE_TAKEOFF} の間だけ意味がある。 */
    public int takeoffPhase() {
        byte s = this.entityData.get(DATA_STATE);
        return (s & CLIMB_BIT) != 0 ? 2 : (s & WINDUP_BIT) != 0 ? 1 : 0;
    }

    @Nullable
    public Flock flock() {
        return this.flock;
    }

    @Nullable
    public BlockPos perchPos() {
        return this.perch;
    }

    /** テスト用: 群れを止まり木へ向かう段にして、この個体に止まり木を割り当てる(まだ飛んでいる = 着地前)。 */
    public void approachPerchForTests(BlockPos perch) {
        if (this.flock != null) this.flock.state = Flock.State.ROOST;
        this.perch = perch;
    }

    /** テスト用: この面の台帳にこの番号の群れがあるか。 */
    public static boolean flockKnownForTests(ServerLevel level, UUID id) {
        return Flock.find(level, id) != null;
    }

    public HitPartSet hitParts() {
        return this.hitParts;
    }

    /**
     * 強風の日に速く流される上乗せ(2026-11-16 weather-system。0 = 今までどおり {@link FlyerWind#now})。流される型(セミナリア)が上書きする。
     */
    protected double galeBoost() {
        return 0.0D;
    }

    /** 今の風を this.wind へ(強風の日の上乗せは {@link #galeBoost} の種だけ)。 */
    protected final void readWind(ServerLevel sl) {
        double b = galeBoost();
        if (b > 0.0D) FlyerWind.drift(sl, this.wind, b);
        else FlyerWind.now(sl, this.wind);
    }

    /** 夜(18:30〜5:30 ごろ)と雨は止まる。昼夜の無い次元では止まらない。 */
    public boolean wantsRoost() {
        if (this.roostOverride != null) return this.roostOverride;
        Level l = this.level();
        if (l.dimensionType().hasFixedTime()) return false;
        long t = Math.floorMod(l.getDayTime(), 24000L);
        return t >= 12500L && t < 23500L || l.isRaining();
    }

    private void setMode(int m) {
        this.mode = m;
        this.modeTicks = 0;
    }

    // ------------------------------------------------------------------ 部位

    @Override
    public void setId(int id) {
        super.setId(id);
        if (this.hitParts != null) this.hitParts.assignIds(id + 1);
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public net.neoforged.neoforge.entity.PartEntity<?>[] getParts() {
        return this.hitParts.parts();
    }

    // ------------------------------------------------------------------ バニラの動きを止める

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                SkyFlyer.this.yBodyRot = SkyFlyer.this.getYRot();
                SkyFlyer.this.yHeadRot = SkyFlyer.this.getYRot();
            }
        };
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, net.minecraft.world.level.block.state.BlockState state, BlockPos pos) {
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean onClimbable() {
        return false;
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason, @Nullable SpawnGroupData data) {
        this.setPersistenceRequired();   // 段 1 は自然湧きしない: 卵・コマンドで出したものは消さない
        return super.finalizeSpawn(level, difficulty, reason, data);
    }

    // ------------------------------------------------------------------ サーバーの判断

    /** 計測(サーバー): 判断と移動にかけた時間・回数。-Dov.devbridge のとき 200 tick ごとに FLYER_PERF を出す。 */
    public static volatile long perfNanos, perfCalls;
    private static long perfLastPrint = Long.MIN_VALUE, perfLastNanos, perfLastCalls;
    private static final boolean DEV = Boolean.getBoolean("ov.devbridge");
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    @Override
    protected void customServerAiStep() {
        if (!DEV) {   // 計測は開発用だけ(11-16 見直し 第 3 便)
            serverStep();
            return;
        }
        long t0 = System.nanoTime();
        try {
            serverStep();
        } finally {
            perfNanos += System.nanoTime() - t0;
            perfCalls++;
        }
    }

    private void devLog(ServerLevel sl) {
        long now = sl.getGameTime();
        if (perfLastPrint == Long.MIN_VALUE) perfLastPrint = now;
        if (now - perfLastPrint >= 200) {
            long dn = perfNanos - perfLastNanos, dc = perfCalls - perfLastCalls;
            LOG.info("FLYER_PERF {} tick 合計 {} ms/tick 平均・1 体 1 tick {} us({} 回)", now - perfLastPrint,
                    String.format("%.3f", dn / 1e6 / (now - perfLastPrint)), dc == 0 ? "0" : String.format("%.1f", dn / 1e3 / dc), dc);
            perfLastPrint = now;
            perfLastNanos = perfNanos;
            perfLastCalls = perfCalls;
        }
        if (this.tickCount % 10 == 0 && this.flock != null && com.yayuy.overgrownvegetation.entity.DevNames.has(this, "trace")) {
            double gs = Math.sqrt(this.body.vx * this.body.vx + this.body.vz * this.body.vz);
            LOG.info("FLYER_TRACE {} 並び {}/{} 群れ {} 動き {} 位置 {} {} {} 林冠 {} 速さ(空気) {} 地面 {} 向き {} 回る {}",
                    this.getId(), this.flock.slotOf(this), this.flock.size(), this.flock.state, this.mode,
                    String.format("%.1f", this.getX()), String.format("%.1f", this.getY()), String.format("%.1f", this.getZ()),
                    String.format("%.1f", this.canopyAhead), String.format("%.3f", this.body.speed), String.format("%.3f", gs),
                    String.format("%.1f", this.body.heading), String.format("%.2f", this.body.turnRate));
        }
        // 確かめ用: 先頭に名前 lead を付ける(コマンドで先頭だけを落とせるように。開発用クライアントだけ)
        if (this.flock != null && this.flock.leader() == this && this.flock.size() > 1 && !this.hasCustomName()) {
            this.setCustomName(net.minecraft.network.chat.Component.literal("lead"));
        }
        if (this.flock != null && this.flock.leader() == this && this.flock.size() > 1 && this.tickCount % 20 == 0) {
            StringBuilder sb = new StringBuilder();
            for (SkyFlyer m : this.flock.members) sb.append(String.format(" %.1f", m.formationErr)).append(m.mode == MODE_FLY ? "" : "m" + m.mode);
            LOG.info("FLYER_FLOCK {} {} 体 {} 隊列のずれ{} 向き {} 回る {} 高さ {} 林冠 {}", this.getId(), this.flock.size(), this.flock.state, sb,
                    String.format("%.0f", this.body.heading), String.format("%.2f", this.body.turnRate),
                    String.format("%.1f", this.getY()), String.format("%.1f", this.canopyAhead));
        }
    }

    private void serverStep() {
        super.customServerAiStep();
        if (!(this.level() instanceof ServerLevel sl)) return;
        if (DEV) devLog(sl);
        ensureFlock(sl);
        Flock f = this.flock;
        f.tick(sl);
        this.modeTicks++;
        if (this.home == null) this.home = this.blockPosition();
        switch (this.mode) {
            case MODE_PERCHED -> tickPerched(sl, f);
            case MODE_LANDING -> tickLanding(sl, f);
            case MODE_TAKEOFF -> tickTakeoff(sl, f);
            default -> {
                this.ownFlightNow = f.state != Flock.State.ROOST && ownFlight(sl, f);
                if (!this.ownFlightNow) tickFly(sl, f);
            }
        }
        if (this.mode != MODE_FLY) this.ownFlightNow = false;
        boolean flapNow = this.mode == MODE_LANDING || this.mode == MODE_TAKEOFF || (this.mode == MODE_FLY && (this.flap || this.ownFlightNow));
        boolean waiting = this.mode == MODE_PERCHED || this.mode == MODE_TAKEOFF && this.takeoffDelay > 0;
        boolean alertNow = waiting && alertRange() > 0.0D && f.alertTicks > 0;
        int phase = this.mode != MODE_TAKEOFF ? 0 : this.takeoffDelay > 0 ? 0 : this.windupLeft > 0 ? WINDUP_BIT : CLIMB_BIT;
        if (this.mode == MODE_TAKEOFF && phase != CLIMB_BIT) flapNow = false;
        byte st = (byte) (this.mode | (flapNow ? FLAP_BIT : 0) | (alertNow ? ALERT_BIT : 0) | phase);
        if (this.entityData.get(DATA_STATE) != st) this.entityData.set(DATA_STATE, st);
    }

    /** 群れに入っていなければ入る(保存された番号があればその群れへ。無ければ自分だけの群れを作る)。1 体の群れは近くの群れへ移る。 */
    private void ensureFlock(ServerLevel sl) {
        // 群れから外れている(群れが空になって忘れられた・別の口で外された)なら、番号で入り直す(先導役が null の群れを持ち続けない)
        if (this.flock != null && !this.flock.members.contains(this)) this.flock = null;
        if (this.flock == null) {
            if (this.flockId == null) this.flockId = UUID.randomUUID();
            if (Double.isNaN(this.layer)) this.layer = Mth.lerp(this.random.nextDouble(), layerMin(), layerMax());
            BlockPos h = this.home != null ? this.home : this.blockPosition();
            this.flock = Flock.obtain(sl, this.flockId, h, this.layer);
            this.flock.add(this, this.savedSlot);
            this.home = this.flock.home;
            this.layer = this.flock.layer;
        }
        // 小さい群れの先頭が、近くの群れと合わせて上限以内なら、群れごと移る(大きい方へ。同じ大きさなら番号の小さい方へ = 行き違いを防ぐ)
        Flock mine = this.flock;
        if (!this.debugSolo && mine.leader() == this && mine.size() < flockMax() && mine.state == Flock.State.PATROL
                && this.tickCount % 40 == 7 && this.tickCount > 20 && this.mode == MODE_FLY) {
            Flock join = null;
            double best = Double.MAX_VALUE;
            List<SkyFlyer> near = sl.getEntitiesOfClass(SkyFlyer.class, this.getBoundingBox().inflate(FLOCK_JOIN_RANGE),
                    o -> o != this && !o.debugSolo && o.getType() == this.getType() && o.flock != null && o.flock != mine && o.isAlive());
            for (SkyFlyer o : near) {
                Flock of = o.flock;
                if (of.size() + mine.size() > flockMax() || of.state != Flock.State.PATROL) continue;
                if (of.size() < mine.size() || of.size() == mine.size() && of.id.compareTo(mine.id) > 0) continue;
                double d = o.distanceToSqr(this);
                if (d < best) {
                    best = d;
                    join = of;
                }
            }
            if (join != null) {
                for (SkyFlyer m : new java.util.ArrayList<>(mine.members)) {
                    mine.remove(m);
                    m.flock = join;
                    m.flockId = join.id;
                    m.home = join.home;
                    m.layer = join.layer;
                    join.add(m, -1);
                }
                Flock.forget(sl, mine);
            }
        }
    }

    private void tickFly(ServerLevel sl, Flock f) {
        FlightBody.Params p = params();
        SkyFlyer leader = f.leader();
        if (leader == null) leader = this;   // 念のため(群れが空でも落ちない)
        boolean isLeader = leader == this;
        // 先の林冠(4 tick ごと)
        if (this.tickCount % 4 == 0 || Double.isNaN(this.canopyAhead)) {
            double c = SkyProbe.canopyAhead(sl, this.getX(), this.getZ(), this.body.heading, CANOPY_AHEAD);
            if (!Double.isNaN(c)) this.canopyAhead = c;
        }
        double floor = Double.isNaN(this.canopyAhead) ? this.getY() : this.canopyAhead;
        double cruiseY = floor + this.layer + this.personalAlt;
        boolean roostApproach = false;
        boolean mustReach = false;
        double needAlong = 0.0D;

        if (f.state == Flock.State.ROOST) {
            if (this.perch != null) {
                roostApproach = true;
                double tx = this.perch.getX() + 0.5D, ty = this.perch.getY() + 1.0D, tz = this.perch.getZ() + 0.5D;
                double dx = tx - this.getX(), dz = tz - this.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                this.cmdHeading = FlightBody.yawOf(dx, dz);
                if (d > 20.0D) {
                    this.cmdSpeed = p.cruise * 0.9D;
                    this.cmdY = Math.max(cruiseY, ty + 8.0D);
                } else {
                    this.cmdSpeed = Mth.lerp(d / 20.0D, p.minSpeed, p.cruise * 0.8D);
                    this.cmdY = ty + 1.5D + d * 0.4D;
                }
                mustReach = true;
                if (d < 2.5D && this.getY() - ty < perchClearUp() || d < 8.0D && this.modeTicks > 400) {
                    setMode(MODE_LANDING);
                    return;
                }
            } else {
                BlockPos c = f.roostCenter != null ? f.roostCenter : this.blockPosition();
                double dx = c.getX() + 0.5D - this.getX(), dz = c.getZ() + 0.5D - this.getZ();
                this.cmdHeading = FlightBody.yawOf(dx, dz) + (dx * dx + dz * dz > 400 ? 0.0D : 70.0D);
                this.cmdSpeed = p.cruise * 0.7D;
                this.cmdY = cruiseY;
            }
        } else if (isLeader || f.scatter > 0) {
            if (f.scatter > 0 && !isLeader) {
                this.cmdHeading = this.body.heading + this.scatterJitter * 0.3D;
            } else if (f.state == Flock.State.FLEE) {
                this.cmdHeading = f.fleeHeading;
            } else if (f.hasPatrol) {
                this.cmdHeading = FlightBody.yawOf(f.patrolX - this.getX(), f.patrolZ - this.getZ());
            } else {
                this.cmdHeading = this.body.heading;
            }
            if (isLeader && f.size() > 1 && f.scatter <= 0) {
                // 群れを率いている間はゆるく曲がる(目一杯だと外側の個体が置いて行かれる)。ずれを ±LEADER_TURN_ERR に丸める = 回る速さ 約 1.2 度/tick
                double e = FlightBody.wrapDeg(this.cmdHeading - this.body.heading);
                this.cmdHeading = this.body.heading + Mth.clamp(e, -LEADER_TURN_ERR, LEADER_TURN_ERR);
            }
            this.cmdSpeed = f.state == Flock.State.FLEE ? fleeSpeed() : p.cruise * 0.9D;
            mustReach = isLeader && f.state == Flock.State.PATROL && f.hasPatrol && f.returningHome;
            this.cmdY = cruiseY;
        } else {
            int k = f.slotOf(this);
            if (looseFlock()) {
                LooseFormation.slot(k, this.loosePhase, this.tickCount, this.tmp);
                LooseFormation.follow(leader.getX(), leader.getY(), leader.getZ(), leader.body.heading, leader.body.speed, leader.body.turnRate,
                        this.tmp, this.getX(), this.getZ(), p.maxSpeed, this.cmd);
            } else {
                VFormation.follow(leader.getX(), leader.getY(), leader.getZ(), leader.body.heading, leader.body.speed, leader.body.turnRate, k,
                        this.getX(), this.getZ(), p.maxSpeed, this.tmp, this.cmd);
            }
            this.cmdHeading = this.cmd.heading;
            this.cmdSpeed = this.cmd.speed;
            this.cmdY = Math.max(this.cmd.y, floor + MIN_CLEARANCE);
            needAlong = this.cmd.along;
            this.formationErr = this.cmd.dist;
        }

        // ひらひら(種が値を渡したときだけ。止まり木へ寄る終わりは弱める)
        if (p.flutterDeg > 0.0D || p.flutterBob > 0.0D) {
            double w = 2.0D * Math.PI * this.tickCount / Math.max(1.0D, p.flutterPeriod);
            double n1 = 0.65D * Math.sin(w + this.flutterPhase1) + 0.35D * Math.sin(w * 2.7D + this.flutterPhase2);
            double n2 = 0.6D * Math.sin(w * 1.3D + this.flutterPhase2) + 0.4D * Math.sin(w * 0.47D + this.flutterPhase1);
            double k = 1.0D;
            if (roostApproach && this.perch != null) {
                double dx = this.perch.getX() + 0.5D - this.getX(), dz = this.perch.getZ() + 0.5D - this.getZ();
                k = Mth.clamp(Math.sqrt(dx * dx + dz * dz) / 12.0D, 0.0D, 1.0D);
            }
            this.cmdHeading += p.flutterDeg * n1 * k;
            this.cmdY += p.flutterBob * n2 * k;
        }

        // 風に逆らって進路を保つ(種が選んだときだけ・着かねばならない行き先へ向かう間だけ): 空気に対する速さ = 地面に対して行きたい速さ − 風
        if (mustReach && fightsWind()) {
            readWind(sl);
            double h = Math.toRadians(this.cmdHeading);
            double ax = -Math.sin(h) * this.cmdSpeed - this.wind[0] * p.windFactor;
            double az = Math.cos(h) * this.cmdSpeed - this.wind[1] * p.windFactor;
            double a = Math.sqrt(ax * ax + az * az);
            if (a > 1e-4) {
                this.cmdHeading = FlightBody.yawOf(ax, az);
                this.cmdSpeed = Math.min(p.maxSpeed, a);
            }
        }

        // 枝・葉(4 tick ごと)
        if (this.avoidTicks > 0) {
            this.avoidTicks--;
            this.cmdHeading = this.avoidHeading;
        } else if (this.tickCount % 4 == 2) {
            feel(sl);
        }
        if (this.horizontalCollision) {
            double l = SkyProbe.blockedWithin(sl, this.getX(), this.getY() + 0.4D, this.getZ(), this.body.heading + 90.0D, FEELER);
            double r = SkyProbe.blockedWithin(sl, this.getX(), this.getY() + 0.4D, this.getZ(), this.body.heading - 90.0D, FEELER);
            this.avoidHeading = this.body.heading + (l >= r ? 120.0D : -120.0D);
            this.avoidTicks = 20;
            this.climbBoost = 4.0D;
            this.body.speed *= 0.5D;
        }
        if (this.climbBoost > 0.0D) {
            this.cmdY = Math.max(this.cmdY, this.getY() + this.climbBoost);
            this.climbBoost = Math.max(0.0D, this.climbBoost - 0.1D);
        }

        // 羽ばたき・滑空
        boolean need = this.body.vy > 0.03D || this.body.speed < this.cmdSpeed - 0.06D || Math.abs(needAlong) > 4.0D;
        if (--this.cycleTicks <= 0) {
            this.glidePhase = !this.glidePhase;
            this.cycleTicks = this.glidePhase ? 40 + this.random.nextInt(40) : 60 + this.random.nextInt(40);
        }
        this.flap = need || (!roostApproach && !this.glidePhase);
    }

    /** 前・左右の線を見て、塞がっていれば空いた側へ曲げて昇る。 */
    private void feel(ServerLevel sl) {
        double y = this.getY() + this.getBbHeight() * 0.5D;
        double h = this.cmdHeading;
        if (SkyProbe.blockedWithin(sl, this.getX(), y, this.getZ(), h, FEELER) == Double.POSITIVE_INFINITY) return;
        for (double a : FEELER_SIDES) {
            double l = SkyProbe.blockedWithin(sl, this.getX(), y, this.getZ(), h + a, FEELER);
            double r = SkyProbe.blockedWithin(sl, this.getX(), y, this.getZ(), h - a, FEELER);
            if (l == Double.POSITIVE_INFINITY || r == Double.POSITIVE_INFINITY) {
                this.avoidHeading = h + (l >= r ? a : -a);
                this.avoidTicks = 12;
                this.climbBoost = 3.0D;
                return;
            }
        }
        this.avoidHeading = h + 150.0D;
        this.avoidTicks = 16;
        this.climbBoost = 6.0D;
    }

    private void tickLanding(ServerLevel sl, Flock f) {
        if (this.perch == null || !SkyProbe.perchable(sl, this.perch) || f.state != Flock.State.ROOST) {
            startTakeoff();
            return;
        }
        double tx = this.perch.getX() + 0.5D, ty = this.perch.getY() + 1.0D, tz = this.perch.getZ() + 0.5D;
        double dx = tx - this.getX(), dy = ty - this.getY(), dz = tz - this.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        double hs = Math.min(LAND_H, d);
        this.kinX = d > 1e-4 ? dx / d * hs : 0.0D;
        this.kinZ = d > 1e-4 ? dz / d * hs : 0.0D;
        // 先に真上へ寄り(低ければ少し昇る)、真上に来てから真下へ降りる(止まり木の上は CLEAR_UP まで空いている)
        this.kinY = d > 0.3D ? Mth.clamp(dy, 0.0D, 0.05D) : Mth.clamp(dy, -LAND_DOWN, 0.05D);
        if (d > 0.5D) faceToward(FlightBody.yawOf(dx, dz), 6.0F);
        if (d < 0.05D && Math.abs(dy) < 0.05D) {
            this.setPos(tx, ty, tz);
            this.kinX = this.kinY = this.kinZ = 0.0D;
            this.body.speed = 0.0D;
            this.body.vy = 0.0D;
            setMode(MODE_PERCHED);
        }
        if (this.modeTicks > 200) {
            // 降りきれない(何かに阻まれた): 近くの別の止まり木へ替えてから飛び直す(同じ所へ降り続けない)
            this.perch = pickSparePerch(sl, f, this.perch, perchClearUp());
            startTakeoff();
        }
    }

    /** old の近く(半径 10)で、群れのほかの個体が使っていない止まり木。無ければ null(止まり木のまわりを回る)。 */
    @Nullable
    private static BlockPos pickSparePerch(ServerLevel sl, Flock f, @Nullable BlockPos old, double clearUp) {
        if (old == null) return null;
        for (BlockPos c : SkyProbe.findPerches(sl, old, 10, 12, Flock.PERCH_GAP, clearUp)) {
            if (c.equals(old)) continue;
            boolean used = false;
            for (SkyFlyer m : f.members) {
                if (m.perch != null && m.perch.distSqr(c) < Flock.PERCH_GAP * Flock.PERCH_GAP) {
                    used = true;
                    break;
                }
            }
            if (!used) return c;
        }
        return null;
    }

    private void tickPerched(ServerLevel sl, Flock f) {
        this.kinX = this.kinY = this.kinZ = 0.0D;
        faceWhileWaiting(sl, f);
        if (f.state == Flock.State.WAKE || f.state == Flock.State.PATROL) {
            if (this.takeoffDelay-- <= 0) {
                startTakeoff();
                return;
            }
        }
        if (this.tickCount % 20 == 0 && (this.perch == null || !SkyProbe.perchable(sl, this.perch))) {
            f.flush(sl, this.getX(), this.getZ());
            startTakeoff();
            return;
        }
        if (this.tickCount % 5 == 0) {
            for (ServerPlayer pl : sl.players()) {
                if (pl.isSpectator()) continue;
                double ar = alertRange();
                if (ar > 0.0D && pl.distanceToSqr(this) < ar * ar) f.alert(pl.getX(), pl.getZ());
                double r = pl.isShiftKeyDown() ? flushRange() * (FLUSH_RANGE_SNEAK / FLUSH_RANGE) : flushRange();
                if (pl.distanceToSqr(this) < r * r) {
                    f.flush(sl, pl.getX(), pl.getZ());
                    return;
                }
            }
        }
    }

    private void tickTakeoff(ServerLevel sl, Flock f) {
        if (this.takeoffDelay > 0) {
            this.takeoffDelay--;
            this.kinX = this.kinY = this.kinZ = 0.0D;
            faceWhileWaiting(sl, f);
            return;
        }
        if (this.windupLeft > 0) {
            // その場で溜める(止まった所から動かない)
            this.windupLeft--;
            this.kinX = this.kinY = this.kinZ = 0.0D;
            return;
        }
        this.climbTicks++;
        double fwd = Math.min(TAKEOFF_FWD_MAX, 0.04D * this.climbTicks);
        double h = Math.toRadians(this.getYRot());
        this.kinX = -Math.sin(h) * fwd;
        this.kinZ = Math.cos(h) * fwd;
        this.kinY = TAKEOFF_UP;
        if (this.climbTicks >= TAKEOFF_TICKS) {
            this.body.heading = this.getYRot();
            this.body.speed = Math.max(fwd, params().minSpeed);
            this.body.vy = 0.12D;
            this.body.turnRate = 0.0D;
            this.canopyAhead = Double.NaN;
            setMode(MODE_FLY);
        }
    }

    /** 止まっている・飛び立ちを待っている間の向き: 身構えていれば気付いた相手の方(6 度/tick)、ふだんは風上(1.5 度/tick)。 */
    private void faceWhileWaiting(ServerLevel sl, Flock f) {
        if (alertRange() > 0.0D && f.alertTicks > 0) {
            faceToward(FlightBody.yawOf(f.alertX - this.getX(), f.alertZ - this.getZ()), 6.0F);
            return;
        }
        readWind(sl);
        if (this.wind[0] * this.wind[0] + this.wind[1] * this.wind[1] > 1e-6) {
            faceToward(FlightBody.yawOf(-this.wind[0], -this.wind[1]), 1.5F);
        }
    }

    /** 飛び立つ(止まっている・降りている途中から)。 */
    void startTakeoff() {
        if (this.mode == MODE_FLY || this.mode == MODE_TAKEOFF) return;
        int delay = this.takeoffDelay;
        boolean fromPerch = this.mode == MODE_PERCHED;
        setMode(MODE_TAKEOFF);
        this.takeoffDelay = Math.max(0, delay);
        this.windupLeft = fromPerch ? takeoffWindupTicks() : 0;   // 降りている途中からは溜めない
        this.climbTicks = 0;
    }

    /** 先頭が落ちた: 向きを少しばらつかせる。 */
    void onScatter() {
        this.scatterJitter = (this.random.nextDouble() * 2.0D - 1.0D) * 30.0D;
    }

    private void faceToward(double yaw, float maxStep) {
        float cur = this.getYRot();
        float d = Mth.clamp(Mth.wrapDegrees((float) yaw - cur), -maxStep, maxStep);
        this.setYRot(cur + d);
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
        this.body.heading = this.getYRot();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hit = super.hurt(source, amount);
        if (hit && !this.level().isClientSide && this.flock != null && (this.mode == MODE_PERCHED || this.mode == MODE_LANDING)
                && this.level() instanceof ServerLevel sl) {
            Vec3 from = source.getSourcePosition() != null ? source.getSourcePosition() : this.position();
            this.flock.flush(sl, from.x, from.z);
        }
        return hit;
    }

    // ------------------------------------------------------------------ 動かす

    @Override
    public void travel(Vec3 input) {
        if (!DEV) {
            travelFlight();
            return;
        }
        long t0 = System.nanoTime();
        try {
            travelFlight();
        } finally {
            if (!this.level().isClientSide) perfNanos += System.nanoTime() - t0;
        }
    }

    private void travelFlight() {
        if (this.isDeadOrDying()) {
            this.setNoGravity(false);   // 落とされたら落ちる
            super.travel(Vec3.ZERO);
            return;
        }
        if (this.level().isClientSide || !this.isEffectiveAi()) {
            return;
        }
        Vec3 v;
        if (this.mode == MODE_FLY && !this.ownFlightNow) {
            if (this.level() instanceof ServerLevel sl) readWind(sl);
            this.body.step(params(), this.cmdHeading, this.cmdSpeed, this.cmdY - this.getY(), this.wind[0], this.wind[1]);
            v = new Vec3(this.body.vx, this.body.vy, this.body.vz);
            this.setYRot((float) this.body.heading);
            this.yBodyRot = this.getYRot();
            this.yHeadRot = this.getYRot();
            this.setXRot((float) -Math.toDegrees(Math.atan2(this.body.vy, Math.max(this.body.speed, 0.05D))));
        } else {
            v = new Vec3(this.kinX, this.kinY, this.kinZ);
            // 自前の飛び方で機首を動きに合わせる種(ヴェロフィルの急降下)は、動く向きから機首の上下を出す。ほかは今まで通り水平
            this.setXRot(this.ownFlightNow && ownFlightPitches()
                    ? (float) -Math.toDegrees(Math.atan2(this.kinY, Math.max(Math.sqrt(this.kinX * this.kinX + this.kinZ * this.kinZ), 0.05D))) : 0.0F);
        }
        this.move(MoverType.SELF, v);
        this.setDeltaMovement(v);
    }

    // ------------------------------------------------------------------ クライアント: 体の傾きと機首

    @Override
    public void tick() {
        super.tick();
        this.hitParts.place(this, this.getYRot(), partCenters());
        if (this.level().isClientSide) {
            this.bankO = this.bank;
            this.pitchO = this.pitch;
            float tb = 0.0F, tp = 0.0F;
            if (mode() == MODE_FLY) {
                double hs = Math.sqrt((this.getX() - this.xo) * (this.getX() - this.xo) + (this.getZ() - this.zo) * (this.getZ() - this.zo));
                float yawRate = Mth.wrapDegrees(this.getYRot() - this.yRotO);
                tb = (float) FlightBody.bankDeg(hs, yawRate);
                tp = this.getXRot();
            }
            this.bank += (tb - this.bank) * 0.2F;
            this.pitch += (tp - this.pitch) * 0.2F;
        }
    }

    public float bank(float pt) {
        return Mth.lerp(pt, this.bankO, this.bank);
    }

    public float pitch(float pt) {
        return Mth.lerp(pt, this.pitchO, this.pitch);
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return new AABB(this.getX() - 3.5D, this.getY() - 1.0D, this.getZ() - 3.5D, this.getX() + 3.5D, this.getY() + 2.0D, this.getZ() + 3.5D);
    }

    // ------------------------------------------------------------------ 保存

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.flockId != null) tag.putUUID("FlockId", this.flockId);
        if (this.flock != null) tag.putInt("FlockSlot", this.flock.slotOf(this));
        if (this.home != null) tag.put("FlockHome", NbtUtils.writeBlockPos(this.home));
        if (!Double.isNaN(this.layer)) tag.putDouble("FlockLayer", this.layer);
        tag.putInt("FlyMode", this.mode == MODE_PERCHED ? MODE_PERCHED : MODE_FLY);
        // 止まり木は止まっている個体だけでなく、止まり木へ向かう・降りる途中の個体も書く(前は着地前に保存すると止まり木を失い、
        // 群れの中で止まっている個体がいると止まり木を引き直さないので朝まで回り続けた。群れの一部だけの読み込み直しも同じ。11-16 見直し ①8)
        if (this.perch != null && (this.mode == MODE_PERCHED || this.flock != null && this.flock.state == Flock.State.ROOST)) {
            tag.put("Perch", NbtUtils.writeBlockPos(this.perch));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("FlockId")) this.flockId = tag.getUUID("FlockId");
        if (tag.contains("FlockSlot")) this.savedSlot = tag.getInt("FlockSlot");
        NbtUtils.readBlockPos(tag, "FlockHome").ifPresent(p -> this.home = p);
        if (tag.contains("FlockLayer")) this.layer = tag.getDouble("FlockLayer");
        NbtUtils.readBlockPos(tag, "Perch").ifPresent(p -> this.perch = p);
        if (tag.getInt("FlyMode") == MODE_PERCHED && this.perch != null) this.mode = MODE_PERCHED;
        this.body.heading = this.getYRot();
    }

    // ------------------------------------------------------------------ テスト・計測

    /** テスト用: 群れの判断を今すぐ回す。 */
    public void debugTickFlock() {
        if (this.level() instanceof ServerLevel sl) {
            ensureFlock(sl);
            this.flock.tick(sl);
        }
    }

    /**
     * テスト用: 群れを休ませ、止まり木を top に割り当てる(飛んで寄って降りるのは本来の動き。2026-11-15 セミナリアで足した)。
     * テスト場のまわりにはほかのテストの高い物があり、本来の止まり木探し(一番高い所を選ぶ)がそちらを選ぶことがあるため。
     */
    public void debugRoostAt(BlockPos top) {
        if (this.level() instanceof ServerLevel sl) {
            ensureFlock(sl);
            this.flock.tick(sl);
            this.flock.state = Flock.State.ROOST;
            this.flock.roostCenter = top;
            this.perch = top;
        }
    }

    /** テスト用: 止まり木へ直に置く。 */
    public void debugPerchAt(BlockPos top) {
        this.perch = top;
        this.setPos(top.getX() + 0.5D, top.getY() + 1.0D, top.getZ() + 0.5D);
        setMode(MODE_PERCHED);
    }

    /** 計測用: 群れの並び(0 = 先頭、群れに居なければ −1)。 */
    public int slot() {
        return this.flock == null ? -1 : this.flock.slotOf(this);
    }

}
