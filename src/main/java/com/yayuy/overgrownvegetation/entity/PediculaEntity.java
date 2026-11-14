package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.flyer.BalloonDrift;
import com.yayuy.overgrownvegetation.entity.flyer.FlightBody;
import com.yayuy.overgrownvegetation.entity.flyer.FlyerWind;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.spore.field.WindWeatherData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
    /** 体の向きのゆっくりした回り(度/tick、個体ごとに ± この幅)【仮】。正面の無い塊なので向きは風と関係なく回る。 */
    static final float SPIN_MAX = 0.25F;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");

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
    private final float spin;
    private boolean capChecked;
    /** テスト用: 行き先を決め打ちする(選び直さない)。 */
    public boolean debugFixedGoal;
    /** テスト用: 地面の高さを決め打ちする(NaN = ふつう。テスト場の高さ地図は天井のバリアを答えるため)。 */
    public double debugGround = Double.NaN;

    public PediculaEntity(EntityType<? extends PediculaEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.xpReward = MobStats.xpReward(ID, 5);
        this.spin = (this.random.nextFloat() * 2.0F - 1.0F) * SPIN_MAX;
        this.chooseIn = this.random.nextInt(20);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
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
    public void travel(Vec3 input) {
        if (this.isDeadOrDying()) {
            this.setDeltaMovement(Vec3.ZERO);   // 破れて落ちる・胞子を降らせるのは段 2
            return;
        }
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
        if (this.verticalCollision && this.drift.vy > 0.0D) this.drift.vy = 0.0D;
        this.setDeltaMovement(v);
        this.setYRot(this.getYRot() + this.spin);
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
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
        controllers.add(new AnimationController<>(this, "main", 6, this::mainState));
    }

    private PlayState mainState(AnimationState<PediculaEntity> state) {
        return state.setAndContinue(IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
