package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.flyer.BalloonDrift;
import com.yayuy.overgrownvegetation.entity.flyer.FlightBody;
import com.yayuy.overgrownvegetation.entity.flyer.FlyerWind;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.spore.field.WindWeatherData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
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

/**
 * ペディキュラ {@code pedicula}(G4・漂う胞子の雲。熱気球)。Vault「Mob の能力/ペディキュラ」。
 *
 * <h2>段 1(2026-11-15): MOD に入れて漂うところまで</h2>
 * 【決定 11-14】攻撃しない。卓越風に流されて漂う。自分では高さしか変えられず、高さごとに違う風を選んで行き先へ向かう。
 * 高い所(地面 +40〜80)、昼は高く夜は冷えて低く。林冠には止まらず、休む動きは無い。
 * 真下の胞子濃度を上げる・射落とすと破れて胞子を降らせる、は段 2。自然には湧かない(卵・コマンドだけ)。
 *
 * <p>動きは「漂う型」の {@link BalloonDrift}(素の Java)と、飛ぶ型の土台の値の口 {@link FlightBody.Params}(風への任せ具合 {@code windFactor}・
 * 上下の速さ)と風 {@link FlyerWind#atHeight}。群れ・止まり木・羽ばたきが無いので {@code SkyFlyer} は継がない。Goal も使わない。
 *
 * <h2>読み込みの境と数【仮・Claude 11-15】</h2>
 * <ul>
 *   <li>流れていく先(体の縁の 2 ブロック先)が、エンティティの動くチャンク({@link ServerLevel#isPositionEntityTicking})でなければ:
 *       {@link #GONE_PLAYER_RANGE} 以内にプレイヤー(観戦を除く)がいなければ「風に乗って去った」として消える。いれば横へは進まず留まる。</li>
 *   <li>1 つの世界(読み込まれている所)に {@link #MAX_PER_LEVEL} 体まで。超えて出たものはすぐ消える({@link #canSpawnMore})。</li>
 *   <li>地面の高さは読み込み済みのチャンクだけ({@code getChunkNow})から読む(読めば読み込みが走る)。</li>
 * </ul>
 */
public class PediculaEntity extends Monster implements GeoEntity, ParasitizedMob {

    public static final String ID = "pedicula";

    /** 上下の動き【仮・Claude】と風への任せ具合(1 = 風そのまま)。前へ進む力は無いので巡航などは使わない。 */
    public static final FlightBody.Params PARAMS = new FlightBody.Params();

    static {
        PARAMS.windFactor = 1.0D;
        PARAMS.climbMax = 0.06D;    // 昇り 秒速 1.2(15 ブロックを 13 秒ほど)
        PARAMS.sinkMax = 0.04D;     // 降り 秒速 0.8(夕方に 25 ブロック下がるのに 30 秒ほど)
        PARAMS.vyAccel = 0.002D;
        PARAMS.climbGain = 0.02D;
    }

    /** 地面を見る間隔(tick)と、流れていく向きに先を見る距離(ブロック)【仮】。 */
    static final int GROUND_EVERY = 20;
    static final double[] GROUND_AHEAD = {0.0D, 16.0D, 32.0D};
    /** 高さを選び直す間隔(tick、+ 0〜19 のずれ)【仮】。 */
    static final int CHOOSE_EVERY = 100;
    /** 行き先: 今の場所から 300〜600 ブロック先の無作為な点。48 ブロック以内に来たか 2 日経ったら選び直す【仮。段 2 で決める】。 */
    static final double GOAL_MIN = 300.0D, GOAL_MAX = 600.0D, GOAL_REACHED = 48.0D;
    static final int GOAL_GIVE_UP = 48000;
    /** 何かにぶつかったら昇る分(ブロック)【仮】。 */
    static final double BUMP_CLIMB = 8.0D;
    /** 世界の天井からあける高さ。 */
    static final double CEILING_MARGIN = 8.0D;
    /** 読み込みの境で消える: この距離以内にプレイヤーがいなければ【仮】。 */
    public static final double GONE_PLAYER_RANGE = 128.0D;
    /** 境を見る先(体の縁から、ブロック)。 */
    static final double EDGE_LOOK = 2.0D;
    /** 1 つの世界の数の上限【仮】。 */
    public static final int MAX_PER_LEVEL = 12;
    /** 転がりの時計の進み(1 tick あたり): 凪のときの下限と、この横の速さ(ブロック/tick)で 1 になる【仮】。描画 {@link com.yayuy.overgrownvegetation.client.PediculaRenderer} が読む。 */
    static final float TUMBLE_CALM = 0.3F;
    static final double TUMBLE_REF_SPEED = 0.15D;
    /** 転がりの平均の速さ(度/時計。旧版の spinSpeed)と揺らぎの幅(度)。旧版の値【仮・旧版 09-23】。 */
    static final double TUMBLE_DEG = 0.5D;
    static final double TUMBLE_GUST_DEG = 25.0D;

    // --- 段 2 のアニメのつなぎ(2026-11-15。アニメはモデル制作の持ち場。Vault「ペディキュラ」7・8 節) ---
    /** 浮きの層の切り替え(上下の速さ vy、ブロック/tick)【仮・アニメ側の案のまま】: 昇る = 入る +0.03・戻る +0.01 / 降りる = 入る −0.02・戻る −0.007。 */
    static final double RISE_ON = 0.03D, RISE_OFF = 0.01D, SINK_ON = -0.02D, SINK_OFF = -0.007D;
    public static final int FLOAT = 0, RISE = 1, SINK = 2;
    /** 胞子をこぼす間隔(tick、下限 + 0〜幅)【仮】。「時々」(ユーザー決定 11-15)。名前に spill を含むと {@link #SPILL_DEBUG} ごと(確かめ用)。 */
    static final int SPILL_MIN = 1200, SPILL_SPAN = 2400, SPILL_DEBUG = 160;
    /** こぼす 3 本の長さ(tick。アニメの json と同じ): 前触れ 16・こぼす 10・隙 24。 */
    static final int SPILL_WINDUP_T = 16, SPILL_T = 10, SPILL_RECOVER_T = 24;
    static final int SPILL_TOTAL = SPILL_WINDUP_T + SPILL_T + SPILL_RECOVER_T;
    /** 破れて落ちる: 重さ(ブロック/tick²)・落ちる速さの上限・横の勢いの減り【仮】。着かなければこの tick で消す(奈落など)。 */
    static final double FALL_ACCEL = 0.03D, FALL_MAX = 0.6D, FALL_DRAG = 0.95D;
    static final int FALL_GIVE_UP = 600;
    // --- 能力(2026-11-15。Vault「G4〜G6 の能力の決定(11-14)」ペディキュラの節・「ペディキュラ」8 節) ---
    /** 上にいる間のそのチャンクの胞子濃度(%)。旧版のユーザー指定 09-23「いる時だけ 100%」・決定 11-14「いるチャンクは 100%」。 */
    public static final double PRESENCE_PERCENT = 100.0D;
    /** こぼした所(真下のチャンク)へ足す降り積もり(発芽のしきい値の %)【仮・Claude 11-15】(球果・爆弾の 15 より少し)。 */
    public static final double SPILL_DEPOSIT_PERCENT = 5.0D;
    /** 破れて落ちた所の降り積もり(発芽のしきい値の %)【仮・Claude 11-15】(球果・爆弾の 4 倍。拠点の近くで落とすと拠点が汚れる)。 */
    public static final double RUPTURE_DEPOSIT_PERCENT = 60.0D;
    /** 落ちた所の胞子の雲: 半径(ブロック)・漂う長さ(tick)・中にいる間の暴露量(%秒 / 秒)【仮・Claude 11-15】(爆弾 3.0・80 より大きい)。 */
    public static final float RUPTURE_CLOUD_RADIUS = 4.0F;
    public static final int RUPTURE_CLOUD_TICKS = 120;
    public static final double RUPTURE_CLOUD_DOSE = 100.0D;

    /** エンティティの知らせ(サーバー → クライアント): こぼし始め・着いて消える。 */
    static final byte EVENT_SPILL = 70, EVENT_LAND = 71;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation A_FLOAT = RawAnimation.begin().thenLoop("float");
    private static final RawAnimation A_RISE = RawAnimation.begin().thenLoop("rise");
    private static final RawAnimation A_SINK = RawAnimation.begin().thenLoop("sink");
    /** こぼし続ける {@code spill_loop} は使わない(ユーザー決定 11-15「時々」)。 */
    private static final RawAnimation A_SPILL = RawAnimation.begin().thenPlay("spill_windup").thenPlay("spill").thenPlay("spill_recover");
    /** 破れる → 落ちる間。着地の {@code rupture_land} は使わない(ユーザー決定 11-15: 殻は残さず、着いたら消す)。 */
    private static final RawAnimation A_RUPTURE = RawAnimation.begin().thenPlay("rupture").thenLoop("rupture_fall");

    private static final net.minecraft.network.syncher.EntityDataAccessor<Byte> DATA_FLOAT =
            SynchedEntityData.defineId(PediculaEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BYTE);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // --- サーバー ---
    public final BalloonDrift.State drift = new BalloonDrift.State();
    private final double[] wind = new double[2];
    /** 足元と先の地面のいちばん高い所(NaN = まだ読めていない)。 */
    private double ground = Double.NaN;
    /** 狙いの高さ(NaN = まだ)。 */
    private double targetY = Double.NaN;
    private double goalX, goalZ;
    private boolean hasGoal;
    private long goalPickedAt;
    private int chooseIn;
    private double bumpY = Double.NaN;
    /** 転がりの時計(クライアントだけ。風に流される速さで進む)。 */
    private float tumbleClock, tumbleClockO;
    private boolean capChecked;
    /** 次にこぼすまで(tick。サーバー)。 */
    private int spillIn = -1;
    /** こぼす瞬間(前触れの後)までの tick。降り積もりはその時に足す(サーバー。-1 = なし)。 */
    private int spillDepositIn = -1;
    /** 破れて落ちる速さ(サーバー)。 */
    private double fallVx, fallVy, fallVz;
    // --- クライアント ---
    /** こぼし始めた tick(tickCount。MIN_VALUE = まだ)と、何回目か(アニメを頭から流し直すため)。 */
    private int spillStart = Integer.MIN_VALUE, spillSerial, spillSeen;
    /** 口(lip)の世界の位置と吹く向き(骨の +y)。描画のたびに {@link com.yayuy.overgrownvegetation.client.PediculaRenderer} が書く。 */
    public double lipX, lipY, lipZ, lipDx, lipDy = -1.0D, lipDz;
    public int lipSeenTick = Integer.MIN_VALUE;
    /** テスト用: 行き先を決め打ちする(選び直さない)。 */
    public boolean debugFixedGoal;
    /** テスト用: 地面の高さを決め打ちする(NaN = ふつう。テスト場の高さ地図は天井のバリアを答えるため)。 */
    public double debugGround = Double.NaN;

    public PediculaEntity(EntityType<? extends PediculaEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.xpReward = MobStats.xpReward(ID, 5);
        this.chooseIn = this.random.nextInt(20);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_FLOAT, (byte) FLOAT);
    }

    /** 浮きの層に流すもの({@link #FLOAT}・{@link #RISE}・{@link #SINK})。サーバーが上下の速さから決めて送る。 */
    public int floatMode() {
        return this.entityData.get(DATA_FLOAT);
    }

    /** 浮きの層の切り替え(戻る線をずらして、境目でちらつかない)。 */
    public static int nextFloatMode(int current, double vy) {
        return switch (current) {
            case RISE -> vy < RISE_OFF ? (vy <= SINK_ON ? SINK : FLOAT) : RISE;
            case SINK -> vy > SINK_OFF ? (vy >= RISE_ON ? RISE : FLOAT) : SINK;
            default -> vy >= RISE_ON ? RISE : vy <= SINK_ON ? SINK : FLOAT;
        };
    }

    // ------------------------------------------------------------------ 数の上限

    /** この世界(読み込まれている所)にもう 1 体出してよいか。段 2 の湧きもここを通す。 */
    public static boolean canSpawnMore(ServerLevel level, EntityType<?> type) {
        return count(level, type, null) < MAX_PER_LEVEL;
    }

    static int count(ServerLevel level, EntityType<?> type, @Nullable Entity except) {
        int[] n = {0};
        level.getEntities(type, e -> e != except && e.isAlive()).forEach(e -> n[0]++);
        return n[0];
    }

    // ------------------------------------------------------------------ バニラの動きを止める

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                PediculaEntity.this.yBodyRot = PediculaEntity.this.getYRot();
                PediculaEntity.this.yHeadRot = PediculaEntity.this.getYRot();
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

    /** 気体の袋なので、ブロックに埋まっても窒息しない・溺れない(体力 0.1 なので、葉の中に湧いた瞬間に死んでいた。11-15 テストクライアント)。 */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL) || source.is(net.minecraft.world.damagesource.DamageTypes.DROWN)
                || super.isInvulnerableTo(source);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
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
        this.setPersistenceRequired();   // 段 1 は自然湧きしない: 卵・コマンドで出したものは消さない(読み込みの境で去るのは別)
        return super.finalizeSpawn(level, difficulty, reason, data);
    }

    // ------------------------------------------------------------------ サーバーの判断

    private static final boolean DEV = Boolean.getBoolean("ov.devbridge");
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(this.level() instanceof ServerLevel sl)) return;
        if (!this.capChecked) {
            this.capChecked = true;
            if (count(sl, this.getType(), this) >= MAX_PER_LEVEL) {
                LOG.info("PEDICULA 上限 {} 体を超えたので消した({} {} {})", MAX_PER_LEVEL, (int) this.getX(), (int) this.getY(), (int) this.getZ());
                this.discard();
                return;
            }
        }
        if (this.tickCount % GROUND_EVERY == 0 || Double.isNaN(this.ground)) readGround(sl);
        double base = Double.isNaN(this.ground) ? this.getY() - BalloonDrift.NIGHT_LOW : this.ground;

        long dayTime = sl.getDayTime();
        double w = sl.dimensionType().hasFixedTime() ? 1.0D : BalloonDrift.warmth(dayTime);
        double low = base + BalloonDrift.bandLow(w), high = base + BalloonDrift.bandHigh(w);
        double ceiling = sl.getMaxBuildHeight() - CEILING_MARGIN;
        high = Math.min(high, ceiling);
        low = Math.min(low, high);

        // 行き先
        if (!this.debugFixedGoal && (!this.hasGoal || sl.getGameTime() - this.goalPickedAt > GOAL_GIVE_UP
                || Mth.lengthSquared(this.goalX - this.getX(), this.goalZ - this.getZ()) < GOAL_REACHED * GOAL_REACHED)) {
            pickGoal(sl);
        }
        // 高さ選び(行き先へいちばん速く流れる高さ)
        if (--this.chooseIn <= 0 || Double.isNaN(this.targetY)) {
            this.chooseIn = CHOOSE_EVERY + this.random.nextInt(20);
            FlyerWind.now(sl, this.wind);
            double gx = this.goalX - this.getX(), gz = this.goalZ - this.getZ();
            double gl = Math.sqrt(gx * gx + gz * gz);
            if (!this.hasGoal || gl < 1e-6) gx = gz = 0.0D;
            else {
                gx /= gl;
                gz /= gl;
            }
            this.targetY = BalloonDrift.chooseHeight(WindWeatherData.windSeed(sl.getServer()), dayTime, this.wind[0], this.wind[1],
                    low, high, this.targetY, gx, gz);
        }
        this.targetY = Mth.clamp(this.targetY, low, high);   // 夕方・朝や地形で帯が動いたら付いていく
        // 時々、口から胞子をこぼす(見た目だけ。真下の胞子濃度を上げる能力は別に作る)
        boolean debugSpill = DevNames.has(this, "spill");
        if (this.spillIn < 0 || (debugSpill && this.spillIn > SPILL_DEBUG)) {
            this.spillIn = debugSpill ? SPILL_DEBUG : SPILL_MIN + this.random.nextInt(SPILL_SPAN);
        }
        if (--this.spillIn <= 0) {
            this.spillIn = debugSpill ? SPILL_DEBUG : SPILL_MIN + this.random.nextInt(SPILL_SPAN);
            sl.broadcastEntityEvent(this, EVENT_SPILL);
            this.spillDepositIn = SPILL_WINDUP_T + 1;
        }
        if (DEV) devLog(sl, low, high);
    }

    /** 足元と、流れていく向きの先の地面(葉・水面を含む)のいちばん高い所。読み込み済みのチャンクだけ。 */
    private void readGround(ServerLevel sl) {
        if (!Double.isNaN(this.debugGround)) {
            this.ground = this.debugGround;
            return;
        }
        double hx = this.drift.vx, hz = this.drift.vz, hl = Math.sqrt(hx * hx + hz * hz);
        if (hl < 1e-4) {
            FlyerWind.now(sl, this.wind);
            hx = this.wind[0];
            hz = this.wind[1];
            hl = Math.sqrt(hx * hx + hz * hz);
        }
        if (hl > 1e-6) {
            hx /= hl;
            hz /= hl;
        } else {
            hx = hz = 0.0D;
        }
        double best = Double.NaN;
        for (double d : GROUND_AHEAD) {
            double g = surface(sl, (int) Math.floor(this.getX() + hx * d), (int) Math.floor(this.getZ() + hz * d));
            if (!Double.isNaN(g) && (Double.isNaN(best) || g > best)) best = g;
        }
        if (!Double.isNaN(best)) this.ground = best;
    }

    /** (x, z) の地表の上の空いた高さ。読み込まれていなければ NaN({@code hasChunk} は読み込みの途中でも真なので {@code getChunkNow})。 */
    static double surface(ServerLevel sl, int x, int z) {
        LevelChunk c = sl.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (c == null) return Double.NaN;
        return c.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
    }

    private void pickGoal(ServerLevel sl) {
        double a = this.random.nextDouble() * Math.PI * 2.0D, d = Mth.lerp(this.random.nextDouble(), GOAL_MIN, GOAL_MAX);
        this.goalX = this.getX() + Math.cos(a) * d;
        this.goalZ = this.getZ() + Math.sin(a) * d;
        this.hasGoal = true;
        this.goalPickedAt = sl.getGameTime();
        this.chooseIn = 0;
    }

    private void devLog(ServerLevel sl, double low, double high) {
        if (!this.hasCustomName() || !this.getCustomName().getString().contains("trace") || this.tickCount % 20 != 0) return;
        FlyerWind.atHeight(sl, this.getY(), this.wind);
        LOG.info("PEDICULA_TRACE {} 位置 {} {} {} 地面 {} 帯 {}〜{} 狙い {} 風 {} {} 速さ {} {} {} 行き先まで {}", this.getId(),
                String.format("%.1f", this.getX()), String.format("%.1f", this.getY()), String.format("%.1f", this.getZ()),
                String.format("%.0f", this.ground), String.format("%.0f", low), String.format("%.0f", high), String.format("%.1f", this.targetY),
                String.format("%.3f", this.wind[0]), String.format("%.3f", this.wind[1]),
                String.format("%.3f", this.drift.vx), String.format("%.3f", this.drift.vy), String.format("%.3f", this.drift.vz),
                String.format("%.0f", Math.sqrt(Mth.lengthSquared(this.goalX - this.getX(), this.goalZ - this.getZ()))));
    }

    // ------------------------------------------------------------------ 動かす

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            // 風に弄ばれて転がる: 横に流される速さ(見えている位置の差)で時計を進める。凪の夜はゆっくり
            double speed = Math.sqrt(Mth.lengthSquared(this.getX() - this.xo, this.getZ() - this.zo));
            this.tumbleClockO = this.tumbleClock;
            // 破れて落ちる間も転がり続ける(ユーザー決定 11-15)。落ちる間は風に弄ばれる速さで
            this.tumbleClock += this.isDeadOrDying() ? 1.0F : tumbleRate(speed);
            clientParticles();
        }
    }

    // ------------------------------------------------------------------ こぼす・破れる(見た目)

    /**
     * 能力(サーバー): 上にいるチャンクの胞子濃度を、いる間だけ {@link #PRESENCE_PERCENT} にする(毎 tick 書く。立ち去る・破れる・消えると
     * 2 tick で元へ戻る。{@link com.yayuy.overgrownvegetation.spore.PediculaCover})。AI とは関係ないので NoAI でも効く。
     * こぼす瞬間には真下へ降り積もりを少し足す。
     */
    @Override
    public void aiStep() {
        super.aiStep();
        if (!(this.level() instanceof ServerLevel sl) || !this.isAlive()) return;
        com.yayuy.overgrownvegetation.spore.PediculaCover.mark(sl, this.chunkPosition().toLong(), PRESENCE_PERCENT);
        if (this.spillDepositIn >= 0 && --this.spillDepositIn < 0) {
            com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.depositPoint(sl, this.blockPosition(),
                    com.yayuy.overgrownvegetation.data.DesignData.sporeField().field().germThreshold * SPILL_DEPOSIT_PERCENT / 100.0D);
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_SPILL) {
            this.spillStart = this.tickCount;
            this.spillSerial++;
            if (DEV) System.out.println("[pedicula-spill] こぼし始め tick " + this.tickCount + " 口が見えている " + lipFresh());
        } else if (id == EVENT_LAND) {
            landBurst();
        } else {
            super.handleEntityEvent(id);
        }
    }

    /** 今こぼしている途中か(クライアント)。 */
    boolean spilling() {
        return this.spillStart != Integer.MIN_VALUE && this.tickCount - this.spillStart < SPILL_TOTAL && !this.isDeadOrDying();
    }

    /** 口の位置と向き。描画で取れていなければ(画面の外など)体の真ん中から真下。 */
    private boolean lipFresh() {
        return this.tickCount - this.lipSeenTick <= 2;
    }

    private void clientParticles() {
        Level lv = this.level();
        double cx = this.getX(), cy = this.getY() + this.getBbHeight() * 0.5D, cz = this.getZ();
        if (this.isDeadOrDying()) {
            // 破れる(rupture 0〜1): 膜の破片と胞子の大きな吹き出し / 落ちる間: 胞子の尾を引く
            if (this.deathTime == 1) {
                for (int i = 0; i < 14; i++) {
                    lv.addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_BURST_CLOUD.get(),
                            cx + (this.random.nextDouble() - 0.5D) * 2.4D, cy + (this.random.nextDouble() - 0.5D) * 2.4D, cz + (this.random.nextDouble() - 0.5D) * 2.4D,
                            (this.random.nextDouble() - 0.5D) * 0.25D, (this.random.nextDouble() - 0.5D) * 0.25D, (this.random.nextDouble() - 0.5D) * 0.25D);
                }
                for (int i = 0; i < 30; i++) {
                    lv.addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_JET.get(), cx, cy, cz,
                            (this.random.nextDouble() - 0.5D) * 0.6D, (this.random.nextDouble() - 0.5D) * 0.6D, (this.random.nextDouble() - 0.5D) * 0.6D);
                }
            } else if (this.deathTime > 1 && this.deathTime % 2 == 0) {
                lv.addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_BURST_CLOUD.get(),
                        cx + (this.random.nextDouble() - 0.5D), cy + 0.6D, cz + (this.random.nextDouble() - 0.5D), 0.0D, 0.02D, 0.0D);
            }
            return;
        }
        if (!spilling()) return;
        int age = this.tickCount - this.spillStart;
        boolean fresh = lipFresh();
        double px = fresh ? this.lipX : cx, py = fresh ? this.lipY : cy, pz = fresh ? this.lipZ : cz;
        double dx = fresh ? this.lipDx : 0.0D, dy = fresh ? this.lipDy : -1.0D, dz = fresh ? this.lipDz : 0.0D;
        int spillAt = SPILL_WINDUP_T, recoverAt = SPILL_WINDUP_T + SPILL_T;
        if (age >= 4 && age <= 14 && age % 2 == 0) {
            // 前触れ: 口のまわりに細かい胞子が少しにじむ
            lv.addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_JET.get(), px, py, pz,
                    dx * 0.03D + (this.random.nextDouble() - 0.5D) * 0.03D, dy * 0.03D, dz * 0.03D + (this.random.nextDouble() - 0.5D) * 0.03D);
        } else if (age == spillAt + 1) {
            if (DEV) System.out.println("[pedicula-spill] 吹く tick " + this.tickCount + " 口 " + px + " " + py + " " + pz + " 向き " + dx + " " + dy + " " + dz);
            // こぼす(spill 1〜2): 口の向きへまとめて吹く
            for (int i = 0; i < 20; i++) {
                double s = 0.2D + this.random.nextDouble() * 0.25D;
                lv.addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_JET.get(), px, py, pz,
                        dx * s + (this.random.nextDouble() - 0.5D) * 0.12D, dy * s + (this.random.nextDouble() - 0.5D) * 0.12D, dz * s + (this.random.nextDouble() - 0.5D) * 0.12D);
            }
            for (int i = 0; i < 4; i++) {
                lv.addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_BURST_CLOUD.get(), px + dx * (0.6D + i * 0.5D), py + dy * (0.6D + i * 0.5D), pz + dz * (0.6D + i * 0.5D),
                        dx * 0.05D, dy * 0.05D, dz * 0.05D);
            }
        } else if (age > spillAt + 1 && age < recoverAt) {
            // こぼれ続ける(下へ流れ落ちる)
            lv.addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_JET.get(), px, py, pz,
                    dx * 0.05D + (this.random.nextDouble() - 0.5D) * 0.04D, -0.08D, dz * 0.05D + (this.random.nextDouble() - 0.5D) * 0.04D);
            if (age % 3 == 0) {
                lv.addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_BURST_CLOUD.get(), px, py - 0.5D, pz, 0.0D, -0.04D, 0.0D);
            }
        } else if (age >= recoverAt && age <= recoverAt + 8 && age % 2 == 0) {
            // 隙: 残りがぱらぱら落ちる
            lv.addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_JET.get(), px, py, pz,
                    (this.random.nextDouble() - 0.5D) * 0.02D, -0.05D, (this.random.nextDouble() - 0.5D) * 0.02D);
        }
    }

    /** 着いた: 地面に沿って胞子が広がる(殻は残さない)。 */
    private void landBurst() {
        Level lv = this.level();
        for (int i = 0; i < 16; i++) {
            double a = i / 16.0D * Math.PI * 2.0D;
            lv.addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_BURST_CLOUD.get(), this.getX(), this.getY() + 0.3D, this.getZ(),
                    Math.cos(a) * 0.18D, 0.0D, Math.sin(a) * 0.18D);
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        // 破れた勢い: 漂っていた速さから落ち始める
        this.fallVx = this.drift.vx;
        this.fallVy = Math.min(this.drift.vy, 0.0D);
        this.fallVz = this.drift.vz;
    }

    /** 破れて落ちた所に胞子を降らせる(決定 11-14「射落とすと破れてその場に胞子を降らせる。どこで落とすかが大事」): 胞子の雲 + 降り積もり。 */
    void rupturedHere(ServerLevel sl) {
        Vec3 at = this.position();
        com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud.spawn(sl, at, RUPTURE_CLOUD_RADIUS, RUPTURE_CLOUD_TICKS, RUPTURE_CLOUD_DOSE,
                -1, 0, false, this, false);
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.depositPoint(sl, BlockPos.containing(at),
                com.yayuy.overgrownvegetation.data.DesignData.sporeField().field().germThreshold * RUPTURE_DEPOSIT_PERCENT / 100.0D);
    }

    /**
     * 破れて落ちる(バニラの「赤く傾いて 20 tick で消える」の代わり)。転がりながら落ち、地面・水に着いたら殻を残さず消える
     * (ユーザー決定 11-15)。着かなければ {@link #FALL_GIVE_UP} tick で消す。
     */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.level().isClientSide) return;
        if (this.isRemoved()) return;
        this.fallVx *= FALL_DRAG;
        this.fallVz *= FALL_DRAG;
        this.fallVy = Math.max(this.fallVy - FALL_ACCEL, -FALL_MAX);
        this.move(MoverType.SELF, new Vec3(this.fallVx, this.fallVy, this.fallVz));
        this.setDeltaMovement(this.fallVx, this.fallVy, this.fallVz);
        if (this.onGround() || this.isInWater() || this.isInLava() || this.verticalCollisionBelow || this.deathTime >= FALL_GIVE_UP) {
            if (this.deathTime < FALL_GIVE_UP && this.level() instanceof ServerLevel sl) rupturedHere(sl);
            this.level().broadcastEntityEvent(this, EVENT_LAND);
            this.remove(RemovalReason.KILLED);
        }
    }

    /** 転がりの時計の 1 tick の進み: 凪で {@link #TUMBLE_CALM}、横の速さ {@link #TUMBLE_REF_SPEED} 以上で 1。 */
    public static float tumbleRate(double horizontalSpeed) {
        return TUMBLE_CALM + (1.0F - TUMBLE_CALM) * (float) Math.min(1.0D, Math.max(0.0D, horizontalSpeed) / TUMBLE_REF_SPEED);
    }

    /**
     * 3 軸の転がりの角度(ラジアン、x・y・z の順で {@code out} へ)。旧版(09-23 ユーザー指定「x,y,z の回転すべてがランダムに働き、
     * 風に流されていく巨大な胞子の塊のような感じに」)の作りのまま: 軸ごとに個体で違う速さ(平均 {@link #TUMBLE_DEG} 度/時計、0.5〜1.5 倍)・
     * 向き・位相に、15〜30 秒周期(風に乗っているとき)の揺らぎ ±{@link #TUMBLE_GUST_DEG} 度(突風で回り方が変わる)を足す。個体の値は UUID から作るので、
     * どのクライアントでも同じ個体は同じに回る。
     */
    public static void tumbleAngles(java.util.UUID id, float clock, float[] out) {
        for (int axis = 0; axis < 3; axis++) {
            double rate = TUMBLE_DEG * (0.5D + individual(id, 10 + axis)) * (individual(id, 13 + axis) < 0.5D ? -1.0D : 1.0D);
            double gustRate = 0.01D + 0.01D * individual(id, 16 + axis);
            double deg = individual(id, 19 + axis) * 360.0D + rate * clock
                    + TUMBLE_GUST_DEG * Math.sin(clock * gustRate + individual(id, 22 + axis) * Math.PI * 2.0D);
            out[axis] = (float) Math.toRadians(deg % 360.0D);
        }
    }

    /** 個体ごとに決まった 0〜1 の値({@code salt} ごとに別。旧版と同じ式)。 */
    static double individual(java.util.UUID id, int salt) {
        long h = id.getMostSignificantBits() * 31L + id.getLeastSignificantBits() + salt * 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** 描画用: 転がりの時計(tick 相当)。 */
    public float tumbleClock(float partialTick) {
        return Mth.lerp(partialTick, this.tumbleClockO, this.tumbleClock);
    }

    @Override
    public void travel(Vec3 input) {
        if (this.isDeadOrDying()) return;   // 破れて落ちるのは tickDeath
        if (this.level().isClientSide || !this.isEffectiveAi() || !(this.level() instanceof ServerLevel sl)) return;
        double cy = this.getY() + this.getBbHeight() * 0.5D;
        FlyerWind.atHeight(sl, cy, this.wind);
        double ty = Double.isNaN(this.targetY) ? this.getY() : this.targetY;
        if (!Double.isNaN(this.bumpY)) {
            ty = Math.max(ty, this.bumpY);
            if (this.getY() >= this.bumpY - 0.5D) this.bumpY = Double.NaN;
        }
        BalloonDrift.step(PARAMS, this.drift, this.wind[0], this.wind[1], ty - this.getY());
        if (this.getY() >= sl.getMaxBuildHeight() - CEILING_MARGIN && this.drift.vy > 0.0D) this.drift.vy = 0.0D;

        // 読み込みの境
        double hl = Math.sqrt(this.drift.vx * this.drift.vx + this.drift.vz * this.drift.vz);
        if (hl > 1e-4) {
            double look = this.getBbWidth() * 0.5D + EDGE_LOOK;
            BlockPos ahead = BlockPos.containing(this.getX() + this.drift.vx / hl * look, cy, this.getZ() + this.drift.vz / hl * look);
            if (!sl.isPositionEntityTicking(ahead)) {
                if (!playerWithin(sl, GONE_PLAYER_RANGE)) {
                    LOG.info("PEDICULA 読み込みの境で風に乗って去った({} {} {})", (int) this.getX(), (int) this.getY(), (int) this.getZ());
                    this.discard();
                    return;
                }
                this.drift.vx = this.drift.vz = 0.0D;
            }
        }

        // 葉・地面に埋まっている(そこに湧いた・木が育った)間は、袋をすぼめて真上へすり抜ける
        boolean stuck = !sl.noCollision(this, this.getBoundingBox().deflate(0.05D));
        if (stuck) {
            this.drift.vx = this.drift.vz = 0.0D;
            this.drift.vy = Math.max(this.drift.vy, PARAMS.climbMax);
        }
        Vec3 v = new Vec3(this.drift.vx, this.drift.vy, this.drift.vz);
        this.noPhysics = stuck;
        this.move(MoverType.SELF, v);
        this.noPhysics = false;
        if (this.horizontalCollision) {
            // 山・大木にぶつかった: 横へは押し付けられたまま、昇って越える(浮くことしかできない)
            this.bumpY = this.getY() + BUMP_CLIMB;
        }
        if (this.verticalCollision && v.y > 0.0D) {
            if (onlyLeavesAbove(sl)) {
                // 林冠の下に押し付けられた: 葉の中へ入る(入れば上の「埋まっている間はすり抜ける」で抜ける)。石などの天井の下では留まる
                this.setPos(this.getX(), this.getY() + 0.1D, this.getZ());
            } else {
                this.drift.vy = 0.0D;
            }
        }
        this.setDeltaMovement(v);
        int mode = nextFloatMode(floatMode(), this.drift.vy);
        if (mode != floatMode()) this.entityData.set(DATA_FLOAT, (byte) mode);
    }

    /** 体の真上(頭の上 0.5 ブロック)でぶつかるブロックが葉だけか(1 つも無ければ false)。 */
    private boolean onlyLeavesAbove(ServerLevel sl) {
        AABB b = this.getBoundingBox();
        boolean leaves = false;
        for (BlockPos p : BlockPos.betweenClosed(Mth.floor(b.minX), Mth.floor(b.maxY + 0.01D), Mth.floor(b.minZ),
                Mth.floor(b.maxX), Mth.floor(b.maxY + 0.5D), Mth.floor(b.maxZ))) {
            net.minecraft.world.level.block.state.BlockState s = sl.getBlockState(p);
            if (s.getCollisionShape(sl, p).isEmpty()) continue;
            if (!s.is(net.minecraft.tags.BlockTags.LEAVES)) return false;
            leaves = true;
        }
        return leaves;
    }

    private boolean playerWithin(ServerLevel sl, double r) {
        for (ServerPlayer p : sl.players()) {
            if (!p.isSpectator() && p.distanceToSqr(this) < r * r) return true;
        }
        return false;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        // モデルは足元から −1.4〜+4.1 ブロック・幅 4.8(垂れた毛を含む)
        return new AABB(this.getX() - 3.0D, this.getY() - 2.0D, this.getZ() - 3.0D, this.getX() + 3.0D, this.getY() + 4.5D, this.getZ() + 3.0D);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        double r = 256.0D * getViewScale();   // 高い空に浮いているので遠くからも見える(追跡の距離 16 チャンクまで)
        return d < r * r;
    }

    // ------------------------------------------------------------------ 保存

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.hasGoal) {
            tag.putDouble("DriftGoalX", this.goalX);
            tag.putDouble("DriftGoalZ", this.goalZ);
        }
        if (!Double.isNaN(this.targetY)) tag.putDouble("DriftTargetY", this.targetY);
        if (this.capChecked) tag.putBoolean("DriftCounted", true);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("DriftGoalX")) {
            this.goalX = tag.getDouble("DriftGoalX");
            this.goalZ = tag.getDouble("DriftGoalZ");
            this.hasGoal = true;
        }
        if (tag.contains("DriftTargetY")) this.targetY = tag.getDouble("DriftTargetY");
        // 保存から戻ったものは数え直さない(上限は新しく出たものだけに効かせる)。summon も読み込みを通るので、保存した印で見分ける
        if (tag.getBoolean("DriftCounted")) this.capChecked = true;
    }

    // ------------------------------------------------------------------ テスト・計測

    public double targetY() {
        return this.targetY;
    }

    public double ground() {
        return this.ground;
    }

    /** テスト用: 行き先を決め打ちする。 */
    public void debugSetGoal(double x, double z) {
        this.goalX = x;
        this.goalZ = z;
        this.hasGoal = true;
        this.debugFixedGoal = true;
        this.chooseIn = 0;
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 層の順が大事: 後に登録した層が同じ骨の同じチャンネルで勝つ(GeckoLib 4.7.6)。浮きの層は main の息を上書きする
        controllers.add(new AnimationController<>(this, "main", 6, this::mainState));
        controllers.add(new AnimationController<>(this, "float", 30, this::floatState));
        controllers.add(new AnimationController<>(this, "mouth", 3, this::mouthState));
    }

    private PlayState mainState(AnimationState<PediculaEntity> state) {
        if (this.isDeadOrDying()) {
            state.getController().transitionLength(0);   // 頭の「はじけ」で段差を隠す
            return state.setAndContinue(A_RUPTURE);
        }
        return state.setAndContinue(IDLE);
    }

    /** 浮きの層: 生きている間は止めない(止めたコマに idle の値へ 1 コマで戻る)。破れたら止める(rupture の大きさを上書きしないように)。 */
    private PlayState floatState(AnimationState<PediculaEntity> state) {
        if (this.isDeadOrDying()) return PlayState.STOP;
        return state.setAndContinue(switch (floatMode()) {
            case RISE -> A_RISE;
            case SINK -> A_SINK;
            default -> A_FLOAT;
        });
    }

    /** 口の層: こぼすときだけ流す(前触れ → こぼす → 隙。隙の終わりは静止の姿なので止めても跳ばない)。 */
    private PlayState mouthState(AnimationState<PediculaEntity> state) {
        if (!spilling()) return PlayState.STOP;
        if (this.spillSeen != this.spillSerial) {
            this.spillSeen = this.spillSerial;
            state.getController().forceAnimationReset();
        }
        return state.setAndContinue(A_SPILL);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
