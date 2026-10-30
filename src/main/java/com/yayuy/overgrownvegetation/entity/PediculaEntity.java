package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStat;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.PediculaCover;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 「ペディキュラ」— 上空を漂うだけの巨大な胞子の塊(グループ4・拡大種、2026-09-23)。
 *
 * <h2>ユーザー指定</h2>
 * <ul>
 *     <li>上空を<b>無重力空間を漂うように</b>ふわふわと、<b>過繫茂バイオームが無い方へ</b>漂う
 *         ({@link #steer} / {@link #drift})。</li>
 *     <li><b>いるチャンクの胞子濃度を、いる間だけ 100% にする</b>({@link #coverChunk})。</li>
 *     <li>x・y・z の回転が<b>すべてランダムに</b>効く、風に流される胞子の塊のような<b>ゆっくりした回転</b>
 *         (見た目だけ。{@code client/PediculaRenderer})。</li>
 *     <li>モデルの sub / sub2 / sub3 が<b>個体ごとに付いたり付かなかったり</b>する({@link #subMask})。</li>
 *     <li><b>撃ち落とすと</b>小さな胞子爆弾を 4 つ周りへ散らし、<b>着弾点に汚染シダが湧く</b>({@link #burst})。</li>
 *     <li>グループ4・体力 0.1・防御 0(体力は Minecraft の下限 1 に丸められる。どの一撃でも落ちる)。</li>
 * </ul>
 *
 * <h2>胞子濃度 —— いる間だけ 100%</h2>
 * チャンクには何も積まない(ユーザー指定 2026-09-23「いる時だけ 100% にしてほしい」。最初は
 * チャンクの加算欄へ永続で積んでいたが、通り道がそのまま繁茂バイオームになっていくので改めた)。
 * 毎 tick {@link PediculaCover} へ「このチャンクの上にいる」と書き、胞子濃度はそれを<b>読み取るときに</b>
 * {@code drift.presencePercent}(既定 100)として扱う。立ち去れば 2 tick で元の濃度へ戻る。
 * <ul>
 *     <li>いる間 100% として効くもの: 生き物の胞子感染・装備の汚染・除去装置の稼働・胞子濃度計の表示・
 *     <b>汚染シダの自然発生</b>(真下にときどき湧く。ユーザー指定「シダの自然発生は残してほしい」)。</li>
 *     <li><b>効かせないもの</b>: 繁茂バイオーム化だけ
 *     ({@link com.yayuy.overgrownvegetation.spore.SporeConcentration#lastingPercentAt})。
 *     いる間の 100% でこれが走ると、立ち去ったあとに通り道が点々と繁茂バイオームのまま残ってしまう。</li>
 *     <li>胞子除去装置は<b>いつもどおり効く</b>(100% から装置のぶんを引く。中心なら下げきり、縁では下げきれない)。</li>
 * </ul>
 *
 * <h2>向きの決め方</h2>
 * {@value #HEADING_DIRECTIONS} 方向へ最大 128 ブロック先までバイオームを見て、
 * <b>過繫茂の濃い側から押し出される向き</b>へ少しずつ舵を切る({@link #calmestHeading})。
 * 周りに過繫茂が無ければ(= 外へ出きった)今の向きのまま流れ続ける。
 * 読み込まれていないチャンクは読まない(読むと生成が走る)。<b>判らない向きは「外」ではなく
 * 「平均並み」</b>として扱う —— 外扱いにすると描画距離の縁がいつもいちばん薄く見えて、
 * プレイヤーから遠ざかる向きへ流れてしまう({@link InvaderPackMarch} と同じ教訓)。
 *
 * <h2>AI は持たない</h2>
 * 経路探索も Goal も使わず、毎 tick 速度を直接決める(漂うだけなので)。
 * {@link net.minecraft.world.entity.PathfinderMob} ではなく {@link Mob} を継いでいるのもそのため ——
 * 汚染 Mob へ狩り・水場・胞子感染の Goal を足して回る注入処理(PathfinderMob だけが対象)に拾われない。
 * 消え方はほかのグループ4 と同じ {@link InvaderDespawn}(遠すぎたら消える。暇そうでは消えない)。
 */
public class PediculaEntity extends Mob implements GeoEntity {

    /** sub / sub2 / sub3 のビット(1 = sub、2 = sub2、4 = sub3)。全部付き。 */
    public static final int SUB_ALL = 0b111;
    /** 付いたり付かなかったりする骨の数。 */
    public static final int SUB_BONE_COUNT = 3;

    /** 向きを測る方向の数。 */
    public static final int HEADING_DIRECTIONS = 16;
    /** 1 方向あたりの見る距離(ブロック)。 */
    private static final double[] SAMPLE_DISTANCES = {16.0D, 32.0D, 48.0D, 64.0D, 96.0D, 128.0D};
    /** その重み(近いほど重い)。 */
    private static final double[] SAMPLE_WEIGHTS = {1.0D, 0.8D, 0.65D, 0.5D, 0.35D, 0.25D};
    /** これより弱い押し出しは無視する(四方が同じ = どちらへ流れても同じ)。 */
    private static final double PUSH_DEADBAND = 0.3D;
    /** 1 回の見直しで向きを変えてよい角度(度)。風向きが一瞬で変わらないように。 */
    private static final double MAX_TURN_DEGREES = 40.0D;

    /** 風で進路が揺れる幅(度)。一直線に流れないように。 */
    private static final double MEANDER_DEGREES = 20.0D;
    /** 進路の揺れの速さ(ラジアン/tick)。0.004 で 1 周期およそ 80 秒。 */
    private static final double MEANDER_RATE = 0.004D;
    /** ふわふわ上下する速さ(ラジアン/tick)。0.03 で 1 周期およそ 10 秒。 */
    private static final double BOB_RATE = 0.03D;
    /** 先の地形を見る距離(ブロック)。山や大木にぶつかる前に上がり始める。 */
    private static final double[] LOOK_AHEAD = {6.0D, 12.0D};
    /** 狙いの高さとの差 1 ブロックあたりの上下の速さ。 */
    private static final double CLIMB_GAIN = 0.03D;
    /** 上がる速さの上限(ブロック/tick)。 */
    private static final double MAX_RISE = 0.08D;
    /** 下がる速さの上限(ブロック/tick)。上がるよりゆっくり降りてくる。 */
    private static final double MAX_SINK = 0.03D;
    /** 地形や水にぶつかったときに上へ逃げる速さ(ブロック/tick)。 */
    private static final double OBSTACLE_RISE = 0.1D;
    /** 速度を狙いへ寄せる割合(1 tick あたり)。小さいほどゆったり曲がる = 漂っている感じになる。 */
    private static final double RESPONSE = 0.05D;
    /** ぶつかっている間の寄せ方。のんびり上がっていると壁に張り付いて見える。 */
    private static final double RESPONSE_BLOCKED = 0.25D;
    /** 描画を間引く箱の余白(ブロック)。sub は当たり判定から最大 0.87 はみ出す。 */
    private static final double CULL_MARGIN = 1.0D;

    private static final double TAU = Math.PI * 2.0D;

    /** 付いている sub(ビット)。描画が骨を出し分けるので同期する。 */
    private static final EntityDataAccessor<Byte> DATA_SUBS =
            SynchedEntityData.defineId(PediculaEntity.class, EntityDataSerializers.BYTE);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** 流れていく向き(ラジアン。0 = +X、π/2 = +Z)。 */
    private double heading;
    /**
     * 漂う速度(狙いの速度へ少しずつ寄せていく途中の値)。{@code getDeltaMovement} とは別に持つ ——
     * バニラの {@code LivingEntity#aiStep} は毎 tick、速度の各成分が 0.003 未満なら 0 に切り捨てるので、
     * そちらから寄せると止まった状態からの 1 歩(漂う速さ 0.05 × {@value #RESPONSE} = 0.0025)が毎回消え、
     * 横へは永久に動き出せなかった(2026-09-26 ユーザー報告「湧いてから全く移動しない」)。
     */
    private Vec3 driftVelocity = Vec3.ZERO;
    /** 次に向きを測り直すまで(tick)。0 から始まるので、湧いた直後に 1 回測る。 */
    private int thinkTimer;

    public PediculaEntity(EntityType<? extends PediculaEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.xpReward = MobStats.xpReward("pedicula", 5);
        // 最初の向きは個体ごとにばらばら。湧いた直後の見直しで過繫茂の外へ向け直す。
        this.heading = individual(0) * TAU;
        if (!level.isClientSide()) {
            // セーブから読むときは readAdditionalSaveData が上書きする。
            this.entityData.set(DATA_SUBS, rollSubs(this.random, MobStats.drift("pedicula").subChance()));
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "pedicula");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        // 既定は全部付き。スポーンエッグの絵(クライアントで作る見本)はこれで描かれる。
        builder.define(DATA_SUBS, (byte) SUB_ALL);
    }

    // --- 個体差 ---

    /** sub / sub2 / sub3 を、それぞれ {@code chance} の確率で付ける。 */
    public static byte rollSubs(RandomSource random, double chance) {
        int mask = 0;
        for (int i = 0; i < SUB_BONE_COUNT; i++) {
            if (random.nextDouble() < chance) {
                mask |= 1 << i;
            }
        }
        return (byte) mask;
    }

    /** 付いている sub のビット(1 = sub、2 = sub2、4 = sub3)。 */
    public int subMask() {
        return this.entityData.get(DATA_SUBS) & SUB_ALL;
    }

    public void setSubMask(int mask) {
        this.entityData.set(DATA_SUBS, (byte) (mask & SUB_ALL));
    }

    /**
     * 個体ごとに決まった 0〜1 の値({@code salt} ごとに別の値)。UUID から作るので、
     * セーブを挟んでも・どのクライアントから見ても同じ値になる。
     * 回転の速さ・高度・進路の揺れの位相を個体ごとにずらすのに使う。
     */
    public double individual(int salt) {
        long h = this.getUUID().getMostSignificantBits() * 31L
                + this.getUUID().getLeastSignificantBits()
                + salt * 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    // --- 漂う ---

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        MobStat.Drift cfg = MobStats.drift("pedicula");
        if (--this.thinkTimer <= 0) {
            this.thinkTimer = cfg.thinkIntervalTicks();
            steer(level);
        }
        drift(level, cfg);
    }

    /** 過繫茂の濃い側から押し出される向きへ、少しずつ舵を切る。 */
    private void steer(ServerLevel level) {
        double target = calmestHeading(this.getX(), this.getZ(), (x, z) -> overgrowthAt(level, x, z));
        if (Double.isNaN(target)) {
            return;
        }
        double turn = Mth.wrapDegrees(Math.toDegrees(target - this.heading));
        this.heading += Math.toRadians(Mth.clamp(turn, -MAX_TURN_DEGREES, MAX_TURN_DEGREES));
    }

    /**
     * 狙いの速度を決めて、今の速度を少しずつそちらへ寄せる。重力は無い。
     * 横は向き + 風の揺れ、縦は「地表 + 巡航高度 + ふわふわ」の高さへ。
     */
    private void drift(ServerLevel level, MobStat.Drift cfg) {
        double t = this.tickCount;
        double angle = this.heading
                + Math.toRadians(MEANDER_DEGREES) * Math.sin(t * MEANDER_RATE + individual(1) * TAU);
        double dx = Math.cos(angle);
        double dz = Math.sin(angle);
        double speed = cfg.driftSpeed();

        double altitude = cfg.cruiseAltitude() + (individual(2) * 2.0D - 1.0D) * cfg.altitudeJitter();
        double targetY = groundAhead(level, dx, dz) + altitude
                + cfg.bobAmplitude() * Math.sin(t * BOB_RATE + individual(3) * TAU);
        double vy = Mth.clamp((targetY - this.getY()) * CLIMB_GAIN, -MAX_SINK, MAX_RISE);
        boolean blocked = this.horizontalCollision || this.isInWater() || this.isInLava();
        if (blocked) {
            vy = Math.max(vy, OBSTACLE_RISE);
        }

        Vec3 want = new Vec3(dx * speed, vy, dz * speed);
        this.driftVelocity = this.driftVelocity.add(
                want.subtract(this.driftVelocity).scale(blocked ? RESPONSE_BLOCKED : RESPONSE));
        this.setDeltaMovement(this.driftVelocity);
    }

    /** 足元と進む先の地表(葉・水面を含む)のうち、いちばん高いところ。 */
    private int groundAhead(ServerLevel level, double dx, double dz) {
        int ground = surfaceAt(level, this.getBlockX(), this.getBlockZ());
        for (double ahead : LOOK_AHEAD) {
            // 読み込まれていない先は最低の高さが返るだけなので、max に効かない。
            ground = Math.max(ground, surfaceAt(level,
                    Mth.floor(this.getX() + dx * ahead), Mth.floor(this.getZ() + dz * ahead)));
        }
        return ground;
    }

    /**
     * {@code (x, z)} 列の地表の高さ({@code Level#getHeight} と同じ値)。<b>読み込み済みのチャンクだけ</b>を読む ——
     * {@code Level#getHeight} は {@code hasChunk} で見てから読むが、{@code hasChunk} は読み込み・生成の途中でも
     * true なので、そのまま読むとメインスレッドが生成を待って止まる(2026-09-24 の重さの監査)。
     * 読み込まれていなければ最低の高さを返す。
     */
    private static int surfaceAt(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        return chunk == null ? level.getMinBuildHeight()
                : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
    }

    /**
     * 自分で動くので、{@code move} は自前で呼ぶ。サーバー側(= 操作している側)だけが動かし、
     * クライアントは送られてきた位置へ補間する({@link VelophyllEntity#travel} と同じ理由 ——
     * 両方で動かすと震える)。
     */
    @Override
    public void travel(Vec3 travelVector) {
        if (this.isControlledByLocalInstance()) {
            this.move(MoverType.SELF, this.getDeltaMovement());
        }
        this.calculateEntityAnimation(false);
    }

    /**
     * {@code (x, z)} から見て、<b>過繫茂の濃い側から押し出される向き</b>(ラジアン。0 = +X、π/2 = +Z)。
     * 押し出しが弱すぎる(四方が同じ・周りに過繫茂が無い)なら {@code NaN} —— 今の向きのままでよい。
     *
     * <p>方向ごとに「見えた地点の過繫茂の度合いの重み付き平均」を出し、<b>全方向の平均との差</b>ぶん
     * 逆向きに押す。読み込まれていない地点({@code sampler} が {@code NaN})から先は読まず、
     * 1 つも読めなかった向きは平均並み(= 押さない)として扱う。
     */
    public static double calmestHeading(double x, double z, OvergrowthSampler sampler) {
        double[] density = new double[HEADING_DIRECTIONS];
        boolean[] known = new boolean[HEADING_DIRECTIONS];
        double sum = 0.0D;
        int knownCount = 0;
        for (int i = 0; i < HEADING_DIRECTIONS; i++) {
            double angle = i * TAU / HEADING_DIRECTIONS;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            double weighted = 0.0D;
            double weights = 0.0D;
            for (int k = 0; k < SAMPLE_DISTANCES.length; k++) {
                double v = sampler.at(Mth.floor(x + dx * SAMPLE_DISTANCES[k]), Mth.floor(z + dz * SAMPLE_DISTANCES[k]));
                if (Double.isNaN(v)) {
                    break; // 判らない。ここから先は読まない
                }
                weighted += v * SAMPLE_WEIGHTS[k];
                weights += SAMPLE_WEIGHTS[k];
            }
            if (weights > 0.0D) {
                density[i] = weighted / weights;
                known[i] = true;
                sum += density[i];
                knownCount++;
            }
        }
        if (knownCount == 0) {
            return Double.NaN;
        }
        double mean = sum / knownCount;
        double px = 0.0D;
        double pz = 0.0D;
        for (int i = 0; i < HEADING_DIRECTIONS; i++) {
            if (!known[i]) {
                continue;
            }
            double angle = i * TAU / HEADING_DIRECTIONS;
            double excess = density[i] - mean;
            px -= Math.cos(angle) * excess;
            pz -= Math.sin(angle) * excess;
        }
        if (px * px + pz * pz < PUSH_DEADBAND * PUSH_DEADBAND) {
            return Double.NaN;
        }
        return Math.atan2(pz, px);
    }

    /** {@link #calmestHeading} が見る、地点ごとの過繫茂の度合い。 */
    @FunctionalInterface
    public interface OvergrowthSampler {
        /** 1 = 過繫茂(極相を含む)、0.5 = 繁茂、0 = それ以外、{@code NaN} = 判らない(未読み込み)。 */
        double at(int x, int z);
    }

    /**
     * ワールドの {@code (x, z)} 列の過繫茂の度合い({@link OvergrowthSampler} の約束どおり)。
     *
     * <p><b>読み込み済みのチャンクだけ</b>を読む({@code getChunkNow})。{@code hasChunk} は読み込み・生成の途中でも
     * true になり、そのままバイオームを読むとメインスレッドが生成を待って止まる(2026-09-24 の重さの監査)。
     * ここは 128 ブロック先 = 描画距離の縁まで見るので、まさにその途中のチャンクに当たる。
     * バイオームは {@code Level#getBiome} ではなくチャンクから直接読む —— {@code getBiome} は
     * 隣のチャンクもぼかしのために覗くので、隣が途中だと同じことが起きる。
     */
    public static double overgrowthAt(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return Double.NaN;
        }
        Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(level.getSeaLevel()),
                QuartPos.fromBlock(z));
        if (ModBiomes.isFullyOvergrown(biome)) {
            return 1.0D;
        }
        return ModBiomes.isOvergrowthStage(biome) ? 0.5D : 0.0D;
    }

    // --- いる間だけの胞子濃度 ---

    /**
     * いまいるチャンク(体の中心の真下)の胞子濃度を、<b>この tick だけ</b> {@code drift.presencePercent} にする。
     * 毎 tick 書き直すので、立ち去る・倒される・消えると書かれなくなり、すぐ元の濃度へ戻る
     * ({@link PediculaCover})。チャンクには何も残さない。
     *
     * <p>AI とは関係ない(NoAI で浮かべておいても胞子の塊には違いない)ので {@link #aiStep} から呼ぶ。
     */
    private void coverChunk(ServerLevel level) {
        double percent = MobStats.drift("pedicula").presencePercent();
        if (percent > 0.0D) {
            PediculaCover.mark(level, this.chunkPosition().toLong(), percent);
        }
    }

    // --- 撃ち落とされたとき ---

    @Override
    public void die(DamageSource source) {
        boolean wasDead = this.dead;
        super.die(source);
        // 死が取り消される(トーテム・他 MOD)こともあるので、本当に死んだときだけ弾ける。
        if (!wasDead && this.dead && this.level() instanceof ServerLevel level) {
            burst(level);
        }
    }

    /**
     * 弾ける。小さな胞子爆弾を周りへ散らし(着弾点に汚染シダが湧く)、胞子の粉をまく。
     * 爆弾は等間隔の向きに少しだけ乱れを入れて投げる —— まとまって落ちると 1 株と変わらないので。
     */
    private void burst(ServerLevel level) {
        MobStat.Drift cfg = MobStats.drift("pedicula");
        Vec3 center = this.position().add(0.0D, this.getBbHeight() * 0.5D, 0.0D);
        int count = cfg.bomblets();
        float size = (float) cfg.bombletSize();
        double baseAngle = this.random.nextDouble() * TAU;
        for (int i = 0; i < count; i++) {
            double angle = baseAngle + TAU * i / count + (this.random.nextDouble() - 0.5D) * 0.5D;
            double speed = cfg.scatterSpeed() * (0.8D + this.random.nextDouble() * 0.4D);
            Vec3 velocity = new Vec3(Math.cos(angle) * speed, 0.15D, Math.sin(angle) * speed);
            // 爆弾は体の縁から出す(中心から出すと 1 tick 目に隣の爆弾と重なって見える)。
            Vec3 from = center.add(Math.cos(angle) * 0.6D, 0.0D, Math.sin(angle) * 0.6D);
            level.addFreshEntity(new VelophyllBombEntity(level, from, velocity, this, size).plantingFern());
        }
        double r = this.getBbWidth() * 0.5D;
        // 粒は短命版(長命版は地割れ専用。2026-09-25、全体監査 #479)。
        level.sendParticles(ModParticles.OVERGROWN_SPORE_SHORT.get(), center.x, center.y, center.z,
                90, r * 0.6D, r * 0.6D, r * 0.6D, 0.12D);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, center.x, center.y, center.z,
                40, r, r, r, 0.0D);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.MOSS_BREAK,
                SoundSource.HOSTILE, 2.0F, 0.6F);
    }

    /**
     * 弾けたらその場で消える。バニラの「20 tick 横倒しになってから煙」は使わない ——
     * 胞子の塊が倒れるのは変だし、爆弾は {@link #die} で散らしてある。
     */
    @Override
    protected void tickDeath() {
        if (!this.level().isClientSide() && !this.isRemoved()) {
            this.remove(RemovalReason.KILLED);
        }
    }

    // --- 毎 tick ---

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.isAlive()) {
            return;
        }
        if (this.level() instanceof ServerLevel level) {
            coverChunk(level);
        } else {
            shedSpores();
        }
    }

    /** 体から細かい胞子がこぼれ落ちる。胞子を撒きながら流れているのが下から見えるように。 */
    private void shedSpores() {
        if (this.random.nextInt(3) != 0) {
            return;
        }
        double r = this.getBbWidth() * 0.5D;
        double x = this.getX() + (this.random.nextDouble() * 2.0D - 1.0D) * r;
        double y = this.getY() + this.getBbHeight() * (0.2D + this.random.nextDouble() * 0.6D);
        double z = this.getZ() + (this.random.nextDouble() * 2.0D - 1.0D) * r;
        this.level().addParticle(ModParticles.FINE_SPORE.get(), x, y, z, 0.0D, -0.03D, 0.0D);
    }

    /** 上空に浮かんでいるので、遠くからでも見えていてほしい({@link VelophyllEntity} と同じ距離)。 */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 192.0D * 192.0D;
    }

    /** sub は当たり判定からはみ出すので、そのぶん広い箱で「画面に入っているか」を見る。 */
    @Override
    public AABB getBoundingBoxForCulling() {
        return this.getBoundingBox().inflate(CULL_MARGIN);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // アニメーションは無い。回転は描画側で付ける(client/PediculaRenderer)。
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // --- その他 ---

    /** グループ4 共通: 遠すぎたら消える。暇そうでは消えない(真上 30 ブロックに浮いているだけでバニラの 32 を超える)。 */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return InvaderDespawn.removeWhenFarAway(this, distanceToClosestPlayer);
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
    }

    /** 押されない・水に流されない(自分で漂っている)。 */
    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    /** 触れたものを押しのけない。 */
    @Override
    protected void doPush(Entity entity) {
    }

    /** リードで繋いで連れ回せると、除去装置の圏内まで引っ張ってきて無力化できてしまう。 */
    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.MOSS_HIT;
    }

    /** 弾ける音。風の塊が破裂する音を低くして、胞子の塊が割れた感じにする。 */
    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.WIND_CHARGE_BURST.value();
    }

    /** 上空で鳴るので大きめに。 */
    @Override
    protected float getSoundVolume() {
        return 2.0F;
    }

    @Override
    public float getVoicePitch() {
        return 0.55F + this.random.nextFloat() * 0.1F;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Subs", (byte) subMask());
        tag.putDouble("Heading", this.heading);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Subs")) {
            setSubMask(tag.getByte("Subs"));
        }
        if (tag.contains("Heading")) {
            this.heading = tag.getDouble("Heading");
        }
        // 保存された速度(Motion は先に読まれている)から続けて漂う。
        this.driftVelocity = this.getDeltaMovement();
    }
}
