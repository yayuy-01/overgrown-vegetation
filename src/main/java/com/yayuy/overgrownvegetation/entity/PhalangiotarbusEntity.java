package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.effect.FoliageConcealment;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「ファランギオターブス」— 8 本脚の大型寄生節足動物。背中の樹皮模様を活かして
 * 巨大樹の原木・過繁茂した木/原木に張り付き、擬態しながら獲物を待ち伏せる。
 *
 * <h2>擬態(待ち伏せ)モード</h2>
 * 以下を<b>同時に</b>満たすときだけ擬態する({@link #isMimicryEnvironment}):
 * <ul>
 *     <li>足元のバイオームが過繁茂バイオーム({@link ModBiomes#OVERGROWN})である</li>
 *     <li>半径 {@link PhalangiotarbusAmbushGoal#PERCH_SEARCH_RADIUS} 以内に
 *     {@link ModTags#PHALANGIOTARBUS_PERCH}(巨大樹の原木・過繁茂した木/原木・
 *     {@code #minecraft:logs})のブロックがある</li>
 * </ul>
 * このとき {@link PhalangiotarbusAmbushGoal} が原木のそばへ移動してじっと張り付き
 * ({@code cling})、獲物が近くを通ると飛びかかって({@code pounce})取り付く。
 * 取り付いている間は {@link PhalangiotarbusLatchGoal} が対象へ継続ダメージを与え続け、
 * 対象が死ぬまで離れない({@code latch})。
 *
 * <h2>通常(狩り)モード</h2>
 * 擬態条件を満たさないとき、または一度攻撃を受けたあとは、他の敵対 Mob と同様に
 * {@link MeleeAttackGoal} で対象を追いかけて殴る。移動速度({@code MOVEMENT_SPEED} 0.39)は
 * プレイヤーのダッシュ(0.13)より明確に速く、逃げると追いつかれる。
 *
 * <h2>個体差</h2>
 * 湧いた時に体格({@code SCALE} 基礎値)を {@code mobs.json} の {@code scaleMin}〜{@code scaleMax}
 * (既定 0.6〜1.0)の範囲でランダムに決める。見た目と当たり判定の両方に効き、個体ごとに保存される
 * (全 Mob 共通の {@link com.yayuy.overgrownvegetation.entity.MobSizeVariance})。
 *
 * <h2>索敵</h2>
 * グループ2の Mob と「過繁茂した Mob」(いずれかの {@link MobGroup} に属する種)・
 * {@link ParasitizedMob}・{@link BlightFernEntity}・{@link OvergrownNexusEntity}、および
 * {@link Predation#isLandTarget 水中・水面にいる相手}は狙わない({@link #isHuntable})。
 * それ以外の生物(プレイヤー含む)はすべて獲物。
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code walk} / {@code run} / {@code cling} / {@code pounce} / {@code latch}。
 * この6つの名前を維持すれば {@code geo/phalangiotarbus.geo.json} /
 * {@code animations/phalangiotarbus.animation.json} / {@code textures/entity/phalangiotarbus.png}
 * の上書きだけで見た目を差し替えられる(Java 変更不要)。
 */
public class PhalangiotarbusEntity extends Monster implements GeoEntity, CritterAction.Holder, ParasitizedMob, PredatorMob {

    /** 通常(idle / walk / run を移動状態で切替)。 */
    public static final int STATE_NONE = 0;
    /** 原木に張り付いて擬態中。 */
    public static final int STATE_CLING = 1;
    /** 獲物へ飛びかかっている最中。 */
    public static final int STATE_POUNCE = 2;
    /** 獲物に取り付いて継続ダメージを与えている最中。 */
    public static final int STATE_LATCH = 3;
    /** 木の側面をよじ登っている最中。 */
    public static final int STATE_CLIMB = 4;

    private static final EntityDataAccessor<Integer> DATA_ACTION_STATE =
            SynchedEntityData.defineId(PhalangiotarbusEntity.class, EntityDataSerializers.INT);
    /** 木の側面に張り付いている(登り/静止どちらも)。クライアントは体を90度傾ける描画に使う。 */
    private static final EntityDataAccessor<Boolean> DATA_CLIMBING =
            SynchedEntityData.defineId(PhalangiotarbusEntity.class, EntityDataSerializers.BOOLEAN);
    /** 張り付いている壁の方向(水平)。{@link Direction#get3DDataValue()}。 */
    private static final EntityDataAccessor<Byte> DATA_CLIMB_FACING =
            SynchedEntityData.defineId(PhalangiotarbusEntity.class, EntityDataSerializers.BYTE);
    /** 天井に張り付いて逆さまに這っている。クライアントは体を180度回す描画に使う。 */
    private static final EntityDataAccessor<Boolean> DATA_ON_CEILING =
            SynchedEntityData.defineId(PhalangiotarbusEntity.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN_ANIM = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation CLING_ANIM = RawAnimation.begin().thenLoop("cling");
    private static final RawAnimation CLIMB_ANIM = RawAnimation.begin().thenLoop("climb");
    // 1 回だけ流して最後のコマで止める。ループだと補間込み 13 tick で空中で踏み切りの姿勢へ戻り、
    // それより長く飛ぶ飛びかかり(木からは 15〜19 tick)では着地の前に構え直して見えた。
    private static final RawAnimation POUNCE_ANIM = RawAnimation.begin().thenPlayAndHold("pounce");
    private static final RawAnimation LATCH_ANIM = RawAnimation.begin().thenLoop("latch");

    /**
     * 動作を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>(4.7.6 の {@code AnimationController#process})。
     * だから画面の上では、どのアニメも状態を切り替えた tick より 3 tick 遅れて始まる。
     * アニメに合わせる tick は「これ + アニメの秒 × 20」で数える。
     */
    static final int TRANSITION_TICKS = 3;
    /**
     * 飛びかかりで跳び出す tick(状態を {@link #STATE_POUNCE} にしてから)。アニメ {@code pounce} の 0 秒目は
     * 体を起こして脚を後ろへ蹴った<b>踏み切り</b>の姿勢なので、それが画面に出る tick に跳ぶ。それまでは足を止めて構える。
     * 2026-09-23 までは状態を切り替えた tick に跳んでいて、補間の 3 tick は前の姿勢のまま宙を飛んでいた。
     */
    static final int POUNCE_LAUNCH_TICK = TRANSITION_TICKS;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /**
     * true の間は {@code climbing} フラグをゴール側が強制する(位置固定や登り駆動を自前で行うため、
     * 壁との接触が一瞬切れても擬態/登りを維持する)。{@link PhalangiotarbusAmbushGoal} が管理。
     */
    private boolean wallLocked;

    /** 体の傾き(0=水平／地上、1=垂直／壁)の補間値。描画専用。 */
    private float climbAmount;
    private float climbAmountO;
    /** 天井への逆さ張り付き(0=通常、1=完全に逆さ)の補間値。描画専用。 */
    private float ceilingAmount;
    private float ceilingAmountO;
    /** 天井が途切れて張り付きを解除するまでの猶予。 */
    private int ceilingGraceTicks;

    /** 取り付いている対象。{@link PhalangiotarbusLatchGoal} が管理する(サーバー専用、NBT保存しない)。 */
    private LivingEntity latchTarget;

    /**
     * 生物から攻撃を受けた最後の tick。直後 {@link #COMBAT_MIMICRY_COOLDOWN} tick は擬態を
     * 開始しない(殴られている最中に原木へ走り出さないため)。獲物を狩り終えたあとは
     * また原木に登って隠れ直す。
     */
    private int lastCombatTick = -10000;
    /** 直近で攻撃を受けてから擬態を再開できるようになるまでの猶予。 */
    private static final int COMBAT_MIMICRY_COOLDOWN = 100;

    /** {@link #isMimicryEnvironment} の結果を数tickキャッシュする(重いバイオーム/ブロック走査の間引き)。 */
    private int mimicryCheckStamp = Integer.MIN_VALUE;
    private boolean mimicryCached;

    /** 捕食したグループ2 Mob(プロトファスマ・アースロプレウラ)の累計({@link PredatorMob})。 */
    private int preyKillCount;

    public PhalangiotarbusEntity(EntityType<? extends PhalangiotarbusEntity> type, Level level) {
        super(type, level);
        // 岸で止まる(自分から入水しない)。葉は当たり判定が 1 ブロックまるごとあるので、
        // 「通れる」扱いにすると葉へ突っ込む経路が引かれて詰まる(2026-09-14)。
        this.setPathfindingMalus(PathType.WATER, -1.0F);
        this.setPathfindingMalus(PathType.LEAVES, -1.0F);
        this.xpReward = MobStats.xpReward("phalangiotarbus", 8);
    }

    /**
     * クモと同じく壁をよじ登れる経路探索。段差の判定だけは {@link OvergrownNodeEvaluator} に差し替える
     * (幅 1.0 ちょうどなのでバニラだと「体が入るか」の確認が省略される)。
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new WallClimberNavigation(this, level) {
            @Override
            protected PathFinder createPathFinder(int maxVisitedNodes) {
                this.nodeEvaluator = new OvergrownNodeEvaluator();
                this.nodeEvaluator.setCanPassDoors(true);
                return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
            }
        };
    }

    public static AttributeSupplier.Builder createAttributes() {
        // MOVEMENT_SPEED はプレイヤーのダッシュ(0.13)より明確に速く設定してある(mobs.json)。
        return MobStats.apply(Monster.createMonsterAttributes(), "phalangiotarbus");
    }

    // 体格の個体差(旧 MIN_SIZE_SCALE / MAX_SIZE_SCALE)は全 Mob 共通の
    // {@link com.yayuy.overgrownvegetation.entity.MobSizeVariance} へ移行し、
    // mobs.json の scaleMin / scaleMax(既定 0.6 / 1.0)で調整できる。

    /** 今している行動(食べる・休む・驚いて逃げ出す など)。アニメの再生の口だけ(行動の Goal は後の段)。2026-10-22。 */
    private static final EntityDataAccessor<Byte> DATA_CRITTER_ACTION =
            SynchedEntityData.defineId(PhalangiotarbusEntity.class, EntityDataSerializers.BYTE);

    @Override
    public CritterAction getCritterAction() {
        return CritterAction.byId(this.entityData.get(DATA_CRITTER_ACTION));
    }

    @Override
    public void setCritterAction(CritterAction action) {
        this.entityData.set(DATA_CRITTER_ACTION, (byte) action.ordinal());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION_STATE, STATE_NONE);
        builder.define(DATA_CRITTER_ACTION, (byte) 0);
        builder.define(DATA_CLIMBING, false);
        builder.define(DATA_CLIMB_FACING, (byte) Direction.NORTH.get3DDataValue());
        builder.define(DATA_ON_CEILING, false);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new PhalangiotarbusLatchGoal(this));
        this.goalSelector.addGoal(2, new PhalangiotarbusAmbushGoal(this));
        // 大きく前方へ跳んで追跡中の相手に飛びつく(近接より優先。射程外/クールダウン中は近接へ)。
        this.goalSelector.addGoal(3, new PhalangiotarbusLeapGoal(this));
        // 追跡は基礎移動速度そのまま(倍率1.0)。MOVEMENT_SPEED でプレイヤーより速い設定にしてある。
        this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.0D, true));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.7D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        // 視線を無視する索敵（茂った過繁茂バイオームでも見失わない。OvergrownHunterTargetGoal 参照）。
        // 水中・水面にいる相手は狙わない（OvergrownLandTargetGoal / Predation#isLandTarget）。
        this.targetSelector.addGoal(2, new OvergrownLandTargetGoal(this, this::isHuntable));
    }

    // --- 状態 ---

    public int getActionState() {
        return this.entityData.get(DATA_ACTION_STATE);
    }

    public void setActionState(int state) {
        this.entityData.set(DATA_ACTION_STATE, state);
    }

    public boolean isBusy() {
        return getActionState() != STATE_NONE;
    }

    LivingEntity getLatchTarget() {
        return latchTarget;
    }

    void setLatchTarget(LivingEntity target) {
        this.latchTarget = target;
    }

    /** 直近で攻撃を受けていて、まだ擬態を再開しない状態か。 */
    boolean isInCombatCooldown() {
        return this.tickCount - this.lastCombatTick < COMBAT_MIMICRY_COOLDOWN;
    }

    private void markCombat() {
        this.lastCombatTick = this.tickCount;
    }

    // --- 擬態の成立条件 ---

    /** 足元が過繁茂バイオームで、かつ近くに原木があるか(数tickキャッシュ)。 */
    public boolean isMimicryEnvironment() {
        if (this.tickCount - mimicryCheckStamp < 20 && mimicryCheckStamp != Integer.MIN_VALUE) {
            return mimicryCached;
        }
        mimicryCheckStamp = this.tickCount;
        mimicryCached = computeMimicryEnvironment();
        return mimicryCached;
    }

    private boolean computeMimicryEnvironment() {
        if (!ModBiomes.isOvergrownLike(this.level().getBiome(this.blockPosition()))) {
            return false;
        }
        return hasPerchLog();
    }

    /**
     * {@link #findPerchLog()} が {@code null} 以外を返すか(= 範囲に原木が 1 つでもあるか)。
     * 擬態の判定には場所が要らないので、<b>最初の 1 つが見つかった時点でブロックを読むのを打ち切る</b>(2026-09-24、重さ対策)。
     * 走査の範囲・順番・判定は findPerchLog と同じ(見つかるまでの読みは 1 マスずつ {@link Level#getBlockState} で)。
     *
     * <p>findPerchLog は見つけた後も残りのマスを読み続け、その読みがチャンクを引く
     * ({@link Level#getChunk}: 未ロードなら同期ロード、チャンクソースのキャッシュ更新と一時チケット)。
     * それを変えないよう、見つけた後はブロックは読まずに<b>チャンクを引く呼び出しだけ</b>を同じ順番で続ける。
     * 同じチャンクが続く分は 1 回にまとめる(続けて引いても 2 回目以降は何も起きないので)。
     */
    private boolean hasPerchLog() {
        int r = PhalangiotarbusAmbushGoal.PERCH_SEARCH_RADIUS;
        BlockPos origin = this.blockPosition();
        Level level = this.level();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean found = false;
        // 直前にチャンクを引いた(= 高さ範囲内のマスを読んだ)列のチャンク座標。
        boolean touched = false;
        int lastCx = 0;
        int lastCz = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                int cx = SectionPos.blockToSectionCoord(x);
                int cz = SectionPos.blockToSectionCoord(z);
                for (int dy = -2; dy <= r; dy++) {
                    int y = origin.getY() + dy;
                    // 高さ範囲外のマスは Level#getBlockState がチャンクを引かずに VOID_AIR を返す。
                    boolean inHeight = !level.isOutsideBuildHeight(y);
                    if (!found) {
                        found = level.getBlockState(cursor.set(x, y, z)).is(ModTags.PHALANGIOTARBUS_PERCH);
                    } else if (inHeight && (!touched || cx != lastCx || cz != lastCz)) {
                        level.getChunk(cx, cz);
                    }
                    if (inHeight) {
                        touched = true;
                        lastCx = cx;
                        lastCz = cz;
                    }
                }
            }
        }
        return found;
    }

    /**
     * 半径 {@link PhalangiotarbusAmbushGoal#PERCH_SEARCH_RADIUS} 以内で最も近い
     * {@link ModTags#PHALANGIOTARBUS_PERCH} ブロック(原木類)。無ければ {@code null}。
     */
    BlockPos findPerchLog() {
        // 2026-09-24 軽量化(報告 1725)。元は 13×13×9 を dx → dz → dy の順に全部読み、距離の 2 乗が<b>真に</b>小さいときだけ
        // 入れ替えていた = 答えは「いちばん近く、同じ距離なら走査順で先」の原木。マスを PERCH_SCAN(距離 → 走査順)の順に読めば
        // 最初に当たったものがその答えなので、そこで打ち切る。
        // チャンクを引く呼び出し(未ロードなら同期ロード、チャンクソースのキャッシュ更新と一時チケット)は、hasPerchLog と同じく
        // 元の走査順のまま先に済ませる(同じチャンクが続く分は 1 回にまとめる —— 続けて引いても 2 回目以降は何も起きない)。
        int r = PhalangiotarbusAmbushGoal.PERCH_SEARCH_RADIUS;
        BlockPos origin = this.blockPosition();
        Level level = this.level();
        int minCx = SectionPos.blockToSectionCoord(origin.getX() - r);
        int minCz = SectionPos.blockToSectionCoord(origin.getZ() - r);
        int spanZ = SectionPos.blockToSectionCoord(origin.getZ() + r) - minCz + 1;
        int spanX = SectionPos.blockToSectionCoord(origin.getX() + r) - minCx + 1;
        LevelChunk[] chunks = new LevelChunk[spanX * spanZ];
        boolean touched = false;
        int lastCx = 0;
        int lastCz = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = SectionPos.blockToSectionCoord(origin.getX() + dx);
                int cz = SectionPos.blockToSectionCoord(origin.getZ() + dz);
                for (int dy = -2; dy <= r; dy++) {
                    // 高さ範囲外のマスは Level#getBlockState がチャンクを引かずに VOID_AIR を返す。
                    if (level.isOutsideBuildHeight(origin.getY() + dy)) {
                        continue;
                    }
                    if (!touched || cx != lastCx || cz != lastCz) {
                        chunks[(cx - minCx) * spanZ + (cz - minCz)] = level.getChunk(cx, cz);
                        touched = true;
                        lastCx = cx;
                        lastCz = cz;
                    }
                }
            }
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < PERCH_SCAN.length; i += 3) {
            int x = origin.getX() + PERCH_SCAN[i];
            int y = origin.getY() + PERCH_SCAN[i + 1];
            int z = origin.getZ() + PERCH_SCAN[i + 2];
            // Level#getBlockState と同じ読み方(高さ範囲外は VOID_AIR、それ以外はそのマスのチャンクから)。
            BlockState state = level.isOutsideBuildHeight(y)
                    ? Blocks.VOID_AIR.defaultBlockState()
                    : chunks[(SectionPos.blockToSectionCoord(x) - minCx) * spanZ
                            + (SectionPos.blockToSectionCoord(z) - minCz)].getBlockState(cursor.set(x, y, z));
            if (state.is(ModTags.PHALANGIOTARBUS_PERCH)) {
                return new BlockPos(x, y, z);
            }
        }
        return null;
    }

    /**
     * {@link #findPerchLog} が読むマスのずらし(dx, dy, dz の 3 つ組)を、<b>距離の 2 乗の小さい順、同じなら元の走査順
     * (dx 昇順 → dz 昇順 → dy 昇順)</b>に並べた表(2026-09-24 軽量化、報告 1725)。クラスを読み込むときに 1 回だけ作る。
     */
    private static final int[] PERCH_SCAN = buildPerchScan();

    private static int[] buildPerchScan() {
        int r = PhalangiotarbusAmbushGoal.PERCH_SEARCH_RADIUS;
        List<int[]> cells = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -2; dy <= r; dy++) {
                    // {dx, dy, dz, 距離の 2 乗, 元の走査順の番号}
                    cells.add(new int[] {dx, dy, dz, dx * dx + dy * dy + dz * dz, cells.size()});
                }
            }
        }
        cells.sort(Comparator.<int[]>comparingInt(c -> c[3]).thenComparingInt(c -> c[4]));
        int[] scan = new int[cells.size() * 3];
        for (int i = 0; i < cells.size(); i++) {
            int[] c = cells.get(i);
            scan[i * 3] = c[0];
            scan[i * 3 + 1] = c[1];
            scan[i * 3 + 2] = c[2];
        }
        return scan;
    }

    /**
     * グループ2・過繁茂した Mob は基本的に狙わないが、<b>捕食対象</b>(プロトファスマ・アースロプレウラ、
     * {@link Predation})だけは例外的に獲物にする。
     *
     * <p>{@link Predation#isLandTarget 水中・水面にいる相手}も狙わない(自分が水に落ちている間も同じ) —
     * 索敵({@link OvergrownLandTargetGoal})だけでなく待ち伏せ({@link PhalangiotarbusAmbushGoal})の
     * 獲物選びもこのメソッドを通るので、ここで一括して弾く。
     */
    boolean isHuntable(LivingEntity candidate) {
        if (candidate == this || !candidate.isAlive()) {
            return false;
        }
        if (candidate instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return false;
        }
        if (!Predation.isLandTarget(this, candidate)) {
            return false;
        }
        boolean prey = Predation.isPredatorOf(this, candidate);
        if (!prey) {
            if (candidate instanceof ParasitizedMob
                    || candidate instanceof BlightFernEntity
                    || candidate instanceof OvergrownNexusEntity) {
                return false;
            }
            // いずれかのグループに属する種(グループ2含む「過繁茂した Mob」全般)は狙わない。
            if (MobGroup.of(candidate) != null) { // Entity 版: 寄生植物装備のプレイヤーも狙わない
                return false;
            }
        }
        return !FoliageConcealment.isConcealed(candidate);
    }

    // --- 捕食数(PredatorMob) ---

    @Override
    public int getPreyKillCount() {
        return this.preyKillCount;
    }

    @Override
    public void setPreyKillCount(int count) {
        this.preyKillCount = count;
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (getCritterAction() != CritterAction.NONE) {
            tag.putString(CritterAction.NBT, getCritterAction().name().toLowerCase(java.util.Locale.ROOT));
        }
        tag.putInt("PreyKills", this.preyKillCount);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(CritterAction.NBT)) {
            setCritterAction(CritterAction.byName(tag.getString(CritterAction.NBT)));
        }
        this.preyKillCount = tag.getInt("PreyKills");
        PredatorMob.applyPreyKillBonus(this, this.preyKillCount);
    }

    // --- 移動・当たり判定 ---

    @Override
    public void travel(Vec3 travelVector) {
        int state = getActionState();
        // 張り付き・取り付き・天井這い中は自力移動しない(位置/速度は tick/aiStep 側が決める)。
        if (state == STATE_CLING || state == STATE_LATCH || isOnCeiling()) {
            super.travel(Vec3.ZERO);
        } else {
            super.travel(travelVector);
        }
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide()) {
            boolean ceiling = detectCeiling();
            boolean climb = !ceiling && detectWallClimb();

            if (isOnCeiling() != ceiling) {
                setOnCeiling(ceiling);
            }
            if (isClimbing() != climb) {
                setClimbing(climb);
            }
            // 壁登り/天井這い中は重力を切って張り付きを安定させる。
            boolean wantNoGravity = this.wallLocked || ceiling || climb;
            if (this.isNoGravity() != wantNoGravity) {
                this.setNoGravity(wantNoGravity);
            }
        }

        // 体勢の補間(サーバー/クライアント両方 — 同期フラグに追従)。
        this.climbAmountO = this.climbAmount;
        this.climbAmount = Mth.approach(this.climbAmount, isClimbing() ? 1.0F : 0.0F, 0.2F);
        this.ceilingAmountO = this.ceilingAmount;
        this.ceilingAmount = Mth.approach(this.ceilingAmount, isOnCeiling() ? 1.0F : 0.0F, 0.2F);
    }

    /** 追跡中に「ぶつかった壁は何でもよじ登る」判定。壁方向を {@link #setClimbFacing} に記録する。 */
    private boolean detectWallClimb() {
        if (this.wallLocked) {
            // 擬態ゴールが駆動している間は原木の側面判定を維持する。
            return isAgainstAnyPerchWall(true);
        }
        // 跳躍・取り付きなど専用行動の最中は壁登りへ移らない。
        if (getActionState() == STATE_NONE && this.horizontalCollision && this.getTarget() != null) {
            Direction wall = collidingWallDirection();
            if (wall != null) {
                setClimbFacing(wall);
                return true;
            }
        }
        return false;
    }

    /**
     * 天井這い(逆さ張り付き)の判定。追跡中に、頭上に固い天井があり、
     * かつ「既に天井にいる/壁を登っている/頭を天井にぶつけた」ときに成立する。
     * 天井が1マス途切れる程度なら {@link #ceilingGraceTicks} の猶予で渡る。
     */
    private boolean detectCeiling() {
        if (this.wallLocked || this.getTarget() == null) {
            this.ceilingGraceTicks = 0;
            return false;
        }
        // 天井這いは「壁を登っていて頭上に天井がある」ときに移行し、その後は天井が続く限り継続。
        boolean solidAbove = ceilingWithin(isOnCeiling() ? 0.6D : 0.45D);
        if (solidAbove && (isOnCeiling() || isClimbing())) {
            this.ceilingGraceTicks = 6;
            return true;
        }
        if (isOnCeiling() && this.ceilingGraceTicks > 0) {
            this.ceilingGraceTicks--;
            return true;
        }
        return false;
    }

    /** 頭上 {@code gap} ブロック以内に固いブロック(天井)があるか。 */
    private boolean ceilingWithin(double gap) {
        double headY = this.getY() + this.getBbHeight();
        return this.level().getBlockState(
                BlockPos.containing(this.getX(), headY + gap, this.getZ())).blocksMotion();
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide()) {
            return;
        }
        if (getActionState() == STATE_POUNCE) {
            this.fallDistance = 0.0F;
        }

        if (isOnCeiling()) {
            tickCeilingCrawl();
        } else if (isClimbing()) {
            tickWallClimbPose();
        }
    }

    /** 天井を逆さで這って対象へ向かう。真下に来たら落下して奇襲する。 */
    private void tickCeilingCrawl() {
        this.fallDistance = 0.0F;
        LivingEntity tgt = this.getTarget();
        double hx = 0.0D;
        double hz = 0.0D;
        double hDistSq = Double.MAX_VALUE;
        if (tgt != null) {
            hx = tgt.getX() - this.getX();
            hz = tgt.getZ() - this.getZ();
            hDistSq = hx * hx + hz * hz;
            double len = Math.sqrt(hDistSq);
            if (len > 1.0E-3D) {
                hx /= len;
                hz /= len;
            } else {
                hx = 0.0D;
                hz = 0.0D;
            }
        }

        // 対象がほぼ真下にいて自分より低ければ、天井から落ちて飛びかかる。
        if (tgt != null && hDistSq < 2.25D && tgt.getY() < this.getY()) {
            setOnCeiling(false);
            this.setNoGravity(false);
            this.setDeltaMovement(hx * 0.18D, -0.4D, hz * 0.18D);
            this.hasImpulse = true;
            this.playSound(ModSounds.PHALANGIOTARBUS_POUNCE.get(), 0.5F, 1.1F);
            return;
        }

        double spd = 0.14D;
        // +Y で天井へ押し付ける(当たり判定が受け止めるので浮かない)。
        this.setDeltaMovement(hx * spd, 0.1D, hz * spd);
        this.hasImpulse = true;
        if (hx * hx + hz * hz > 1.0E-4D) {
            float yaw = (float) (Mth.atan2(hz, hx) * (180.0D / Math.PI)) - 90.0F;
            this.setYRot(yaw);
            this.yRotO = yaw;
            this.yBodyRot = yaw;
            this.yBodyRotO = yaw;
        }
    }

    /** 壁登り中の姿勢固定＋登り駆動。 */
    private void tickWallClimbPose() {
        float wallYaw = getClimbFacing().toYRot();
        this.setYRot(wallYaw);
        this.yRotO = wallYaw;
        this.yBodyRot = wallYaw;
        this.yBodyRotO = wallYaw;
        this.fallDistance = 0.0F;
        // 追跡中(擬態ゴール外)は、対象が上方にいるなら壁へ押し付けて確実に登る。
        if (!this.wallLocked && this.getTarget() != null
                && this.getTarget().getY() > this.getY() + 0.3D) {
            Direction f = getClimbFacing();
            net.minecraft.world.phys.Vec3 dm = this.getDeltaMovement();
            this.setDeltaMovement(f.getStepX() * 0.12D, Math.max(dm.y, 0.08D), f.getStepZ() * 0.12D);
        }
    }

    @Override
    public boolean onClimbable() {
        return isClimbing() || isOnCeiling();
    }

    @Override
    public boolean isPushable() {
        return getActionState() == STATE_NONE && !isClimbing() && !isOnCeiling() && super.isPushable();
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        if (getActionState() == STATE_POUNCE || getActionState() == STATE_LATCH
                || isClimbing() || isOnCeiling()) {
            return false;
        }
        return super.causeFallDamage(distance, multiplier, source);
    }

    // --- 壁のぼり / 天井這い / 姿勢 ---

    public boolean isOnCeiling() {
        return this.entityData.get(DATA_ON_CEILING);
    }

    public void setOnCeiling(boolean onCeiling) {
        this.entityData.set(DATA_ON_CEILING, onCeiling);
    }

    /** 描画用: 天井への逆さ度合い(0=通常, 1=完全に逆さ)。 */
    public float getCeilingAmount(float partialTick) {
        return Mth.lerp(partialTick, this.ceilingAmountO, this.ceilingAmount);
    }

    public boolean isClimbing() {
        return this.entityData.get(DATA_CLIMBING);
    }

    public void setClimbing(boolean climbing) {
        this.entityData.set(DATA_CLIMBING, climbing);
    }

    public Direction getClimbFacing() {
        return Direction.from3DDataValue(this.entityData.get(DATA_CLIMB_FACING));
    }

    private void setClimbFacing(Direction dir) {
        this.entityData.set(DATA_CLIMB_FACING, (byte) dir.get3DDataValue());
    }

    boolean isWallLocked() {
        return wallLocked;
    }

    void setWallLocked(boolean locked) {
        this.wallLocked = locked;
        if (!locked) {
            this.setNoGravity(false);
        }
    }

    /** 描画用: 体の傾き(0=水平, 1=垂直)。 */
    public float getClimbAmount(float partialTick) {
        return Mth.lerp(partialTick, this.climbAmountO, this.climbAmount);
    }

    /**
     * 水平4方向の隣に原木({@link ModTags#PHALANGIOTARBUS_PERCH})があるか。
     * あれば {@link #setClimbFacing} でその方向を記録する。
     */
    boolean isAgainstAnyPerchWall(boolean updateFacing) {
        BlockPos base = this.blockPosition();
        Direction current = getClimbFacing();
        // 既に張り付いている方向を優先的に確認(ちらつき防止)。
        if (isPerchWall(base, current)) {
            return true;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (d == current) {
                continue;
            }
            if (isPerchWall(base, d)) {
                if (updateFacing) {
                    setClimbFacing(d);
                }
                return true;
            }
        }
        return false;
    }

    private boolean isPerchWall(BlockPos base, Direction d) {
        for (int dy = 0; dy <= 1; dy++) {
            if (this.level().getBlockState(base.above(dy).relative(d)).is(ModTags.PHALANGIOTARBUS_PERCH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 今ぶつかっている壁(何のブロックでも可)の水平方向。進行方向に最も沿った面を返す。
     * 隣に固い面が無ければ {@code null}(＝登れない)。
     */
    private Direction collidingWallDirection() {
        net.minecraft.world.phys.Vec3 v = this.getDeltaMovement();
        double dirX;
        double dirZ;
        if (v.x * v.x + v.z * v.z > 1.0E-4D) {
            dirX = v.x;
            dirZ = v.z;
        } else {
            net.minecraft.world.phys.Vec3 look = this.getLookAngle();
            dirX = look.x;
            dirZ = look.z;
        }
        BlockPos base = this.blockPosition();
        Direction best = null;
        double bestDot = -2.0D;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!hasSolidNeighbor(base, d)) {
                continue;
            }
            double dot = d.getStepX() * dirX + d.getStepZ() * dirZ;
            if (dot > bestDot) {
                bestDot = dot;
                best = d;
            }
        }
        return best;
    }

    private boolean hasSolidNeighbor(BlockPos base, Direction d) {
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos p = base.above(dy).relative(d);
            if (this.level().getBlockState(p).blocksMotion()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean took = super.hurt(source, amount);
        if (took && source.getEntity() instanceof LivingEntity) {
            markCombat();
        }
        return took;
    }

    // --- GeckoLib ---

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "action", TRANSITION_TICKS, state -> {
            // 今している行動(食べる・休む・驚いて逃げ出す など)があればそのアニメ(CritterAction、2026-10-22)
            RawAnimation critterAct = CritterAction.animation(this);
            if (critterAct != null) {
                state.getController().setAnimationSpeed(1.0D);
                return state.setAndContinue(critterAct);
            }
            state.getController().setAnimationSpeed(1.0D);
            switch (getActionState()) {
                case STATE_LATCH:
                    return state.setAndContinue(LATCH_ANIM);
                case STATE_POUNCE:
                    return state.setAndContinue(POUNCE_ANIM);
                case STATE_CLIMB:
                    return state.setAndContinue(CLIMB_ANIM);
                case STATE_CLING:
                    return state.setAndContinue(CLING_ANIM);
                default:
                    // 天井を這っている(逆さ)。脚を動かす climb アニメを流用し、傾きは描画側で180度回す。
                    if (isOnCeiling()) {
                        return state.setAndContinue(CLIMB_ANIM);
                    }
                    // 通常の狩りで壁をよじ登っている最中(state は NONE のまま)。
                    if (isClimbing()) {
                        return state.setAndContinue(MobAnimation.isWalking(state) ? CLIMB_ANIM : CLING_ANIM);
                    }
                    if (MobAnimation.isWalking(state)) {
                        return state.setAndContinue(
                                MobAnimation.isFasterThan(state, 0.14D) ? RUN_ANIM : WALK_ANIM);
                    }
                    return state.setAndContinue(IDLE_ANIM);
            }
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // --- サウンド ---

    @Override
    protected SoundEvent getAmbientSound() {
        return ModSounds.PHALANGIOTARBUS_IDLE.get();
    }

    @Override
    public int getAmbientSoundInterval() {
        // 虫の鳴き声。少し間隔を空けて、擬態中でも時々鳴くようにする。
        return 160;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.PHALANGIOTARBUS_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.PHALANGIOTARBUS_DEATH.get();
    }

    @Override
    protected float getSoundVolume() {
        // 鳴き声(idle / hurt / death)の音量を控えめに。
        return 0.5F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        // 壁・天井に張り付いている間は足音を出さない。
        if (isClimbing() || isOnCeiling() || getActionState() == STATE_LATCH) {
            return;
        }
        this.playSound(ModSounds.PHALANGIOTARBUS_STEP.get(), 0.08F, 0.9F + this.random.nextFloat() * 0.2F);
    }

}
