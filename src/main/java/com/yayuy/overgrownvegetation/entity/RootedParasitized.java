package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.director.G1Fate;
import com.yayuy.overgrownvegetation.entity.g1.G1Rooted;
import com.yayuy.overgrownvegetation.entity.g1.G1Rooted.Skill;
import com.yayuy.overgrownvegetation.entity.g1.G1TargetGoal;
import com.yayuy.overgrownvegetation.block.CorruptedEggBlock;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1FluffDrift;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1MilkPuddle;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeBlast;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeBolt;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeDose;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeLump;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1Tether;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * G1 の<b>根付き</b>(第 0 段からの根付き。2026-11-13、司令塔 第 1 段の区切り 2・3)。仕様の正本は Vault「Mob の能力/G1 根付きの共通」。
 * 司令塔が「根付き」に決めた段階 0 が、森から離れた所で入れ替わる({@link G1Fate})。動かなくなって植物化する代わりに、能力と攻撃が強い固定型の Mob。
 * 種の値は {@link G1Rooted}(今は蔦の柱の型 5 種: ゾンビ・チビゾンビ・村人・エンダーマン・スケルトン)。
 *
 * <h3>蔦の柱の型に共通</h3>
 * <ul>
 *   <li><b>動かない・向きを変えない</b>(11-13 ユーザー決定): 押されない・押し返されない・歩かない。体の向きは根付いたときのまま。落ちるのだけはする。</li>
 *   <li><b>薙ぎ払い</b>: 近づいた相手を、柱のまわりを 1 周する蔦で打つ。当たりは {@code lash} の間、蔦の通る順に 1 体 1 回。</li>
 *   <li><b>撃つ</b>: 離れた相手へ、胞子嚢から胞子の塊({@link G1SporeLump})を山なりに撃つ(スケルトンは弓の狙撃 = 種の能力)。</li>
 *   <li><b>生える</b>: 入れ替わった直後に {@code emerge}。その間は攻撃しない。倒せば止まる。</li>
 *   <li><b>枯れて崩れる</b>(11-13 ユーザー決定): 倒すと {@code death_wither} を流して、終わった所で消える。</li>
 *   <li><b>周りを少しずつ汚染し続ける</b>(11-12 Claude 案・ユーザー了承)。</li>
 * </ul>
 * <h3>種の能力(根付きの共通「種ごとの能力」【決定 11-12】。数は【仮】)</h3>
 * <ul>
 *   <li>ゾンビ {@link Skill#GRAB}: 蔦の腕を伸ばして絡め({@link G1Tether})、薙ぎ払いの間合いへ引き寄せる。</li>
 *   <li>チビゾンビ {@link Skill#SMOKE}: 叩かれると袋から胞子の煙幕({@link G1SporeCloud}。視界が霞む)。</li>
 *   <li>村人 {@link Skill#BELL}: 鐘が鳴って周りの動物・村人を引き寄せる + 周りは常に暴露量が上がる。<b>根の守り手はまだ</b>(守り手の素材待ち)。</li>
 *   <li>エンダーマン {@link Skill#REACH}: 見えている相手を転移で手元へ引き込む。相手のそばの Mob も数体一緒に引き込み、近くの G1 を数体呼び集める。</li>
 *   <li>スケルトン {@link Skill#BALLISTA}: 柱の上の弓から山なりの狙撃({@link G1SporeBolt}。射程は一番長い)。体は固定で、弓だけ回す。</li>
 * </ul>
 * 「コードが回す骨」(grab_swing・reach_shoulder・ballista_aim)の向きは、同期した角度(体の正面から左回りの度)でクライアントが入れる
 * ({@code client/G1GeoModel})。音は 1 段目で使っているものと、バニラの鐘・エンダーマンの転移(【仮】。ユーザーに確認する)。
 */
public class RootedParasitized extends Monster implements ParasitizedMob, GeoEntity, G1Fate.Emerging {

    // ------------------------------------------------------------------ 行動(同期)
    public static final int ACTION_NONE = 0;
    public static final int ACTION_EMERGE = 1;
    public static final int ACTION_LASH_WINDUP = 10, ACTION_LASH = 11, ACTION_LASH_RECOVER = 12;
    public static final int ACTION_TRAP_HOLD = 13;
    public static final int ACTION_SHOOT_WINDUP = 20, ACTION_SHOOT = 21, ACTION_SHOOT_RECOVER = 22;
    /** 種の能力の 3 本組(ゾンビの絡め取り・エンダーマンの引き込み)。 */
    public static final int ACTION_SKILL_WINDUP = 30, ACTION_SKILL = 31, ACTION_SKILL_RECOVER = 32;
    /** 種の能力の 1 本だけのクリップ(チビゾンビの煙幕・村人の鐘)。 */
    public static final int ACTION_SKILL_ONE = 33;
    /** 種の能力の繰り返しの段(豚の根の顎が締めている間 bind_hold)。 */
    public static final int ACTION_SKILL_HOLD = 34;

    /** 豚の根の顎の今の位置(体の左・前へのブロック数)と倍率。クライアントが骨 root_jaw_carry に入れる。 */
    private static final EntityDataAccessor<Float> JAW_LEFT =
            SynchedEntityData.defineId(RootedParasitized.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> JAW_FRONT =
            SynchedEntityData.defineId(RootedParasitized.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> JAW_SCALE =
            SynchedEntityData.defineId(RootedParasitized.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Integer> ACTION =
            SynchedEntityData.defineId(RootedParasitized.class, EntityDataSerializers.INT);
    /** コードが回す骨の向き(体の正面から左回りの度)。蔦の腕・長い腕・弓。 */
    private static final EntityDataAccessor<Integer> SEED =
            SynchedEntityData.defineId(RootedParasitized.class, EntityDataSerializers.INT);
    /** 村人の「守り手のもと」の状態: 無し(静止の姿)/ 守り手を出した殻(guardian_spawn を流して止める)/ 育ち直し(guardian_regrow)。 */
    public static final int SEED_NONE = 0, SEED_SHELL = 1, SEED_REGROW = 2;
    /** 村人の根の守り手【仮】: 守り手を出す tick(guardian_spawn の中)・育ち直しのアニメの長さ・倒されてから育ち直すまでの待ち・出す場所の柱からの長さ。 */
    static final int GUARDIAN_SPAWN_AT = 20;
    static final int GUARDIAN_REGROW = 40;
    static final int GUARDIAN_REGROW_WAIT = 600;
    static final double GUARDIAN_OFFSET = 1.4D;
    private static final EntityDataAccessor<Float> AIM_YAW =
            SynchedEntityData.defineId(RootedParasitized.class, EntityDataSerializers.FLOAT);

    // ------------------------------------------------------------------ 【仮】の数値(共通)
    /** 薙ぎ払い: 届く長さより少し内で振り出す・押し返し・次までの間。ダメージは属性 ATTACK_DAMAGE。届く長さは種の表。 */
    static final double LASH_TRIGGER_INSET = 0.2D;
    static final double LASH_KNOCK = 0.6D;
    static final int LASH_COOLDOWN = 30;
    /** 薙ぎ払いの蔦が回る角度: 前の左 21° から 325° 回る(書き出しの記録)。 */
    static final float LASH_START_DEG = 21.0F, LASH_SWEEP_DEG = 325.0F;
    /** 撃つ: 間合い・冷却・塊の速さ・暴露量・G1 への回復。 */
    static final double SHOOT_MIN = 4.0D, SHOOT_MAX = 24.0D;
    static final int SHOOT_COOLDOWN = 80;
    static final float SHOOT_SPEED = 1.2F;
    static final double SHOOT_DOSE = 300.0D;
    static final float SHOOT_HEAL = 4.0F;
    /** 塊が落ちるぶん上を狙う係数(重力 0.03・速さ 1.2 → 0.5 × 0.03 ÷ 1.2² × 距離²)。 */
    static final double SHOOT_DROP = 0.0104D;
    /** 周りの汚染: この間(tick)ごとに、半径の中のブロックを 1 つ試す。 */
    static final int CORRUPT_INTERVAL = 100;
    static final int CORRUPT_RADIUS = 6;
    /** 見つける距離(スケルトンは狙撃の射程まで)。 */
    static final double FOLLOW_RANGE = 24.0D;
    /** 標的を選び直す間(tick)。 */
    static final int RETARGET_INTERVAL = 20;

    // ------------------------------------------------------------------ 【仮】の数値(種の能力)
    /** ゾンビの絡め取り: 間合い・冷却・絡めている長さ・引く速さ・鈍足・振りほどける距離・狙いの幅(度)。 */
    static final double GRAB_MIN = 2.6D, GRAB_MAX = 10.0D;
    static final int GRAB_COOLDOWN = 160;
    static final int GRAB_HOLD = 60;
    static final double GRAB_PULL = 0.12D;
    static final int GRAB_SLOW = 2;
    static final double GRAB_BREAK = 13.0D;
    static final float GRAB_CONE = 25.0F;
    /** チビゾンビの煙幕: 煙を出す tick・半径・漂う長さ・暴露量(%秒 / 秒)・冷却。 */
    static final int SMOKE_AT = 5;
    static final float SMOKE_RADIUS = 2.5F;
    static final int SMOKE_TICKS = 80;
    static final double SMOKE_DOSE_PER_SECOND = 100.0D;
    static final int SMOKE_COOLDOWN = 120;
    /** 村人の鐘: 鳴らす間隔・鳴る tick・引き寄せる半径と長さ。暴露の輪: 半径と 1 秒ごとの量(%秒)。 */
    static final int BELL_INTERVAL = 600;
    static final int[] BELL_AT = {3, 9, 15, 21};
    static final double BELL_RADIUS = 16.0D;
    static final int BELL_PULL_TICKS = 200;
    static final double AURA_RADIUS = 8.0D;
    static final double AURA_DOSE_PER_SECOND = 40.0D;
    /** エンダーマンの引き込み: 間合い・冷却・手元の距離・一緒に引き込む Mob(相手のそば何ブロック・何体)・呼び集める G1(何ブロック・何体)。 */
    static final double REACH_MIN = 4.0D, REACH_MAX = 20.0D;
    static final int REACH_COOLDOWN = 200;
    static final double REACH_LAND = 1.9D;
    static final double REACH_EXTRA_RADIUS = 5.0D;
    static final int REACH_EXTRA_COUNT = 3;
    static final double REACH_CALL_RADIUS = 24.0D;
    static final int REACH_CALL_COUNT = 3;
    /** 呼んだ G1 を出す輪の半径(柱から)。 */
    static final double REACH_CALL_RING = 3.2D;
    /** 長い腕を向けられる範囲(度。体の正面から左回り。右へは宿主の体を横切るので −10 まで)。見た目だけ。 */
    static final float REACH_AIM_MIN = -10.0F, REACH_AIM_MAX = 100.0F;
    /** スケルトンの狙撃: 間合い・見つける距離・冷却・弾の速さ・ばらつき・ダメージ・暴露量・弓の回る速さ(度 / tick)。 */
    static final double BALLISTA_MIN = 5.0D, BALLISTA_MAX = 40.0D;
    static final double BALLISTA_FOLLOW = 40.0D;
    static final int BALLISTA_COOLDOWN = 60;
    static final float BALLISTA_SPEED = 1.8F;
    static final float BALLISTA_SPREAD = 1.0F;
    static final float BALLISTA_DAMAGE = 4.0F;
    static final double BALLISTA_DOSE = 500.0D;
    static final float BALLISTA_TURN = 8.0F;

    // ------------------------------------------------------------------ 【仮】の数値(背から咲く型。区切り 4)
    /** 胞子の雲: 獲物がこの半径に入ると房を膨らませる・雲の半径・漂う長さ・暴露量(%秒 / 秒)・冷却。 */
    static final double PUFF_TRIGGER = 5.0D;
    static final float PUFF_RADIUS = 4.5F;
    static final int PUFF_TICKS = 100;
    static final double PUFF_DOSE_PER_SECOND = 120.0D;
    static final int PUFF_COOLDOWN = 160;
    /** 牛の乳だまり: 間隔・1 回に広げる数・置く範囲(柱からの長さ)・半径・残る長さ。 */
    static final int MILK_INTERVAL = 300;
    static final int MILK_PUDDLES = 4;
    static final double MILK_NEAR = 1.5D, MILK_FAR = 5.0D;
    static final float MILK_RADIUS_MIN = 1.0F, MILK_RADIUS_MAX = 1.5F;
    static final int MILK_TICKS = 400;
    /** 羊の綿毛: 間隔・1 回に飛ばす数・着いた升の降り積もり(発芽のしきい値の %)。 */
    static final int LOOSE_INTERVAL = 1200;
    static final int LOOSE_COUNT = 3;
    static final double LOOSE_DEPOSIT_PERCENT = 15.0D;
    /** 鶏の卵: 間隔(最小〜最大)・置く範囲・周りの卵の上限(1 段目の鶏と同じ数え方)。 */
    static final int LAY_INTERVAL_MIN = 1200, LAY_INTERVAL_MAX = 2400;
    static final int LAY_RADIUS = 3;
    static final int LAY_LIMIT = 6, LAY_LIMIT_RADIUS = 16, LAY_LIMIT_HEIGHT = 4;
    /** 豚の根の顎: 縛る半径(11-12 ユーザー: 4)・抜けるのに要る殴りの数(同: 4 回)・縛っていられる上限・縛っている間の暴露量(%秒 / 秒)・
     *  抜けた相手を巻かない長さ・冷却・噛み合う瞬間に顎から離れていてよい長さ・縛っている間の顎の倍率。 */
    static final double BIND_RADIUS = 4.0D;
    static final int BIND_HITS = 4;
    static final int BIND_MAX_TICKS = 200;
    static final double BIND_DOSE_PER_SECOND = 60.0D;
    static final int BIND_IMMUNE_TICKS = 100;
    static final int BIND_COOLDOWN = 60;
    static final double BIND_MISS = 1.2D;
    static final float BIND_JAW_SCALE = 3.5F;
    /** 豚の根の顎(骨 root_jaw_carry)の静止の位置: 体の左 0.6875・前 0.5375 ブロック(モデルの (−11, ·, −8.6))。 */
    public static final float JAW_REST_LEFT = 0.6875F, JAW_REST_FRONT = 0.5375F;
    /** bind_recover のうち、顎が地面の下にいる tick(ここで顎を元の場所へ戻す)。 */
    static final int BIND_RESET_AT = 10;

    private static final Map<String, RawAnimation> HOLD = new HashMap<>();
    private static final Map<String, RawAnimation> LOOP = new HashMap<>();

    private static RawAnimation loop(String name) {
        return LOOP.computeIfAbsent(name, n -> RawAnimation.begin().thenLoop(n));
    }
    private static final Map<String, RawAnimation> ONCE = new HashMap<>();
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");

    private static RawAnimation hold(String name) {
        return HOLD.computeIfAbsent(name, n -> RawAnimation.begin().thenPlayAndHold(n));
    }

    private static RawAnimation once(String name) {
        return ONCE.computeIfAbsent(name, n -> RawAnimation.begin().thenPlay(n));
    }

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private G1Rooted rooted;

    /** 今の行動の残り(tick。サーバー)と、その行動に入ってからの tick。 */
    private int actionLeft;
    private int actionAge;
    private int lashReadyIn, shootReadyIn, skillReadyIn;
    /** 今の薙ぎ払いでもう打った相手。 */
    private final Set<Integer> lashHit = new HashSet<>();
    @Nullable
    private G1Tether tether;
    /** 根付いたときの向き(度)。ここから変えない。NaN = まだ決めていない。 */
    private float anchorYaw = Float.NaN;
    private int corruptIn = CORRUPT_INTERVAL;
    /** チビゾンビ: 叩かれたので、手が空いたら煙幕を出す。 */
    private boolean smokePending;
    /** 村人: 鐘で引き寄せている残り(tick)。 */
    private int bellPullLeft;
    /** 村人: 根の守り手(生きている 1 体)・守り手のもとの段取りの時計・育ち直しまでの残り。 */
    @Nullable
    private java.util.UUID guardian;
    private int seedTimer;
    private int guardianRegrowIn;
    @Nullable
    private LivingEntity guardianFoe;

    public RootedParasitized(EntityType<? extends RootedParasitized> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public G1Rooted rooted() {
        if (rooted == null) {
            rooted = G1Rooted.of(getType());
            if (rooted == null) rooted = G1Rooted.ZOMBIE;
        }
        return rooted;
    }

    public static AttributeSupplier.Builder attributes(G1Rooted r) {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, r.health)
                .add(Attributes.ATTACK_DAMAGE, 6.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.FOLLOW_RANGE, r.skill == Skill.BALLISTA ? BALLISTA_FOLLOW : FOLLOW_RANGE);
    }

    private double followRange() {
        return getAttributeValue(Attributes.FOLLOW_RANGE);
    }

    @Override
    protected void registerGoals() {
        // 動かないので行動の Goal は無い(攻撃は tick の中の段取り)。標的だけ選ぶ: 殴ってきた相手 > 段階 0 と同じ「どの生き物も」
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, false, false,
                e -> G1TargetGoal.isPrey(this, e)));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ACTION, ACTION_NONE);
        builder.define(AIM_YAW, 0.0F);
        builder.define(SEED, SEED_NONE);
        builder.define(JAW_LEFT, JAW_REST_LEFT);
        builder.define(JAW_FRONT, JAW_REST_FRONT);
        builder.define(JAW_SCALE, 1.0F);
    }

    public int action() {
        return this.entityData.get(ACTION);
    }

    /** コードが回す骨の向き(体の正面から左回りの度)。 */
    public float aimYaw() {
        return this.entityData.get(AIM_YAW);
    }

    private void begin(int action, int ticks) {
        this.entityData.set(ACTION, action);
        actionLeft = ticks;
        actionAge = 0;
    }

    // ------------------------------------------------------------------ 動かない
    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public void knockback(double strength, double x, double z) {
    }

    @Override
    public void push(double x, double y, double z) {
        // 爆発・水流・釣り竿などの押しも受けない
    }

    @Override
    public void travel(Vec3 input) {
        Vec3 v = getDeltaMovement();
        setDeltaMovement(0.0D, v.y, 0.0D); // 横へは動かない。落ちるのだけはする
        super.travel(Vec3.ZERO);
        v = getDeltaMovement();
        setDeltaMovement(0.0D, v.y, 0.0D);
    }

    /**
     * 森に飲まれても枯れない: 動けないので、育った木・汚染ブロックに体が埋まる(本物の地形で、苗床 3 体が 1 日のうちに木の幹に埋まって
     * 窒息していた。11-14)。埋まり・押し合いのダメージは受けない。
     */
    @Override
    public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) {
        return source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL) || source.is(net.minecraft.world.damagesource.DamageTypes.CRAMMING)
                || super.isInvulnerableTo(source);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public int getMaxHeadYRot() {
        return 0;
    }

    private void holdYaw() {
        if (Float.isNaN(anchorYaw)) anchorYaw = getYRot();
        setYRot(anchorYaw);
        this.yRotO = anchorYaw;
        this.yBodyRot = anchorYaw;
        this.yBodyRotO = anchorYaw;
        this.yHeadRot = anchorYaw;
        this.yHeadRotO = anchorYaw;
    }

    // ------------------------------------------------------------------ 生える
    @Override
    public void startEmerge() {
        begin(ACTION_EMERGE, G1Rooted.EMERGE);
        if (!level().isClientSide()) playSound(ModSounds.G1_PLANT_EMERGE.get(), 1.0F, 1.0F); // 音量【仮】
    }

    public boolean isEmerging() {
        return action() == ACTION_EMERGE;
    }

    // ------------------------------------------------------------------ tick
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            this.yBodyRot = getYRot();
            this.yHeadRot = getYRot();
            return;
        }
        holdYaw();
        if (isAlive() && tickCount % com.yayuy.overgrownvegetation.entity.g1.G1Unbury.INTERVAL == 0) com.yayuy.overgrownvegetation.entity.g1.G1Unbury.liftIfBuried(this); // 木の幹に埋まったら上へ出る(R6-7)
        if (!isAlive()) return;
        if (tether != null && !tether.tick()) tether = null;
        pushOutIntruders();
        if (lashReadyIn > 0) lashReadyIn--;
        if (shootReadyIn > 0) shootReadyIn--;
        if (skillReadyIn > 0) skillReadyIn--;
        if (--corruptIn <= 0) {
            corruptIn = CORRUPT_INTERVAL;
            corruptNearby();
        }
        if (rooted().skill == Skill.BELL) {
            tickVillagerAura();
            tickGuardian();
        }
        int action = action();
        if (action != ACTION_NONE) {
            tickAction(action);
            return;
        }
        if (tickCount % RETARGET_INTERVAL == 0) retargetNearest();
        if (tryPassiveSkill()) return;
        if (rooted().predator()) {
            predatorIdle(); // 待ち伏せ: 標的は要らない(葉の輪に入った獲物を捕まえる)
            return;
        }
        LivingEntity target = getTarget();
        if (target == null || !target.isAlive()) return;
        chooseAttack(target);
    }

    /**
     * 動けないので、最初に選んだ遠い相手を持ち続けると、後から近くへ来た相手を放っておくことになる(標的の Goal は今の標的が有効な間は選び直さない)。
     * 行動していない間、一定の間ごとに「見えていて、いちばん近い獲物」へ切り替える【仮】。標的の Goal を外した個体(テスト)では何もしない。
     */
    private void retargetNearest() {
        if (this.targetSelector.getAvailableGoals().isEmpty()) return;
        double range = followRange();
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(range, 8.0D, range))) {
            if (!G1TargetGoal.isPrey(this, e)) continue;
            double d = distanceToSqr(e);
            if (d < bestSq && d <= range * range && getSensing().hasLineOfSight(e)) {
                best = e;
                bestSq = d;
            }
        }
        if (best != null && best != getTarget()) setTarget(best);
    }

    /**
     * 柱に重なった生き物を外へ押し出す。根付きは押されない(= バニラの押し合いをしない)ので、何もしないと、絡めて引き寄せた獲物が
     * 勢いで柱の中まで入って埋まって見える(テストクライアントで見つけた、11-13)。重なっている間だけ、芯から外へ少しずつ押す【仮】。
     */
    private void pushOutIntruders() {
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(0.15D, 0.0D, 0.15D))) {
            if (e == this || e == bound || e.isSpectator() || e.noPhysics) continue;
            double dx = e.getX() - getX(), dz = e.getZ() - getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 1.0E-3D) {
                dx = 1.0D;
                dz = 0.0D;
                d = 1.0D;
            }
            e.push(dx / d * 0.12D, 0.0D, dz / d * 0.12D);
            e.hurtMarked = true; // プレイヤーの速度はクライアントが持つので送る
        }
    }

    /** 柱の芯から相手の体の端までの水平の距離。 */
    private double reachTo(LivingEntity e) {
        double dx = e.getX() - getX(), dz = e.getZ() - getZ();
        return Math.max(0.0D, Math.sqrt(dx * dx + dz * dz) - e.getBbWidth() * 0.5D);
    }

    /** 相手のいる向き(体の正面から左回りの度、0〜360)。 */
    private float leftAngleTo(Entity e) {
        float yaw = Float.isNaN(anchorYaw) ? getYRot() : anchorYaw;
        double rad = yaw * Mth.DEG_TO_RAD;
        double fx = -Math.sin(rad), fz = Math.cos(rad); // 正面
        double lx = Math.cos(rad), lz = Math.sin(rad);  // 左
        double dx = e.getX() - getX(), dz = e.getZ() - getZ();
        double deg = Math.toDegrees(Math.atan2(dx * lx + dz * lz, dx * fx + dz * fz));
        return (float) ((deg % 360.0D + 360.0D) % 360.0D);
    }

    private boolean sameLevel(LivingEntity e) {
        return e.getY() < getY() + 2.5D && e.getY() + e.getBbHeight() > getY() - 0.5D;
    }

    private void aimAt(Entity e) {
        this.entityData.set(AIM_YAW, leftAngleTo(e));
    }

    /** 弓: 今の向きから相手の向きへ、1 tick に決まった角度ずつ回す。 */
    private void turnAimToward(Entity e, float maxStep) {
        float cur = aimYaw();
        float want = leftAngleTo(e);
        this.entityData.set(AIM_YAW, cur + Mth.clamp(Mth.wrapDegrees(want - cur), -maxStep, maxStep));
    }

    /** 標的に関わらない種の能力(煙幕・鐘)。始めたら true。 */
    private boolean tryPassiveSkill() {
        G1Rooted r = rooted();
        if (r.skill == Skill.SMOKE && smokePending && skillReadyIn <= 0) {
            smokePending = false;
            begin(ACTION_SKILL_ONE, r.skillWindup);
            return true;
        }
        // 背から咲く型の、標的に向けない能力(乳だまり・綿毛・卵)。間が空いたら出し続ける
        if (skillReadyIn <= 0 && (r.skill == Skill.MILK || r.skill == Skill.LOOSE || r.skill == Skill.LAY)) {
            if (r.skill == Skill.LOOSE && level() instanceof ServerLevel level
                    && !com.yayuy.overgrownvegetation.spore.SporeExposureTracker.underSky(level, this)) {
                skillReadyIn = 200; // 空の下でないと綿毛は風に乗らない(1 段目の羊と同じ)。10 秒後にまた見る
                return false;
            }
            begin(ACTION_SKILL_WINDUP, r.skillWindup);
            return true;
        }
        if (r.skill == Skill.BELL && skillReadyIn <= 0) {
            // 引き寄せる相手(動物・村人)が届く所にいるときだけ鳴らす。いなければ 5 秒後にまた見る
            boolean any = !level().getEntitiesOfClass(PathfinderMob.class, getBoundingBox().inflate(BELL_RADIUS, 6.0D, BELL_RADIUS),
                    m -> (m instanceof Animal || m instanceof AbstractVillager) && G1TargetGoal.isPrey(this, m) && !tooFarForTests(m)).isEmpty();
            if (!any) {
                skillReadyIn = 100;
                return false;
            }
            begin(ACTION_SKILL_ONE, r.skillWindup);
            return true;
        }
        return false;
    }

    public float jawLeft() {
        return this.entityData.get(JAW_LEFT);
    }

    public float jawFront() {
        return this.entityData.get(JAW_FRONT);
    }

    public float jawScale() {
        return this.entityData.get(JAW_SCALE);
    }

    private void chooseAttack(LivingEntity target) {
        double reach = reachTo(target);
        G1Rooted r = rooted();
        if (r.bloom()) {
            chooseBloomAttack(r, target, reach);
            return;
        }
        if (r.skill == Skill.BALLISTA) turnAimToward(target, BALLISTA_TURN); // 弓は手が空いている間も標的を追う
        if (reach <= r.lashRange - LASH_TRIGGER_INSET && sameLevel(target)) {
            if (lashReadyIn <= 0) begin(ACTION_LASH_WINDUP, G1Rooted.LASH_WINDUP);
            return; // 間合いの中にいる間は、薙ぎ払いを待つ(撃たない)
        }
        if (!getSensing().hasLineOfSight(target)) return;
        if (r.skill == Skill.GRAB && skillReadyIn <= 0 && tether == null && reach >= GRAB_MIN && reach <= GRAB_MAX) {
            aimAt(target);
            begin(ACTION_SKILL_WINDUP, r.skillWindup);
            playSound(ModSounds.G1_VINE_LASH.get(), 1.0F, 0.6F);
            return;
        }
        if (r.skill == Skill.REACH && skillReadyIn <= 0 && reach >= REACH_MIN && reach <= REACH_MAX) {
            this.entityData.set(AIM_YAW, reachAim(target));
            begin(ACTION_SKILL_WINDUP, r.skillWindup);
            return;
        }
        if (shootReadyIn > 0) return;
        if (r.skill == Skill.BALLISTA) {
            if (reach >= BALLISTA_MIN && reach <= BALLISTA_MAX) begin(ACTION_SHOOT_WINDUP, r.shootWindup);
        } else if (reach >= SHOOT_MIN && reach <= SHOOT_MAX) {
            begin(ACTION_SHOOT_WINDUP, r.shootWindup);
        }
    }

    private void tickAction(int action) {
        G1Rooted r = rooted();
        LivingEntity target = getTarget();
        boolean hasTarget = target != null && target.isAlive();
        switch (action) {
            case ACTION_LASH -> {
                if (r.predator()) {
                    if (actionAge == TRAP_CATCH_AT) catchPrey();
                } else {
                    lashSweep(actionAge, G1Rooted.LASH);
                }
            }
            case ACTION_TRAP_HOLD -> holdTrapped(actionAge);
            case ACTION_SKILL_WINDUP -> {
                // 溜めの間は腕が標的を追う(伸ばす段からは向きを止める = 横へ逃げれば外れる)
                if (hasTarget) this.entityData.set(AIM_YAW, r.skill == Skill.REACH ? reachAim(target) : leftAngleTo(target));
                if (r.predator() && bound != null) holdTrapped(-1); // クリーパー: 爆ぜる前の溜めの間も、捕まえた獲物を留める
            }
            case ACTION_SHOOT_WINDUP -> {
                if (hasTarget && r.skill == Skill.BALLISTA) turnAimToward(target, BALLISTA_TURN);
            }
            case ACTION_SKILL_ONE -> tickOneClipSkill(r, actionAge);
            case ACTION_SKILL -> tickBloomSkill(r, actionAge);
            case ACTION_SKILL_HOLD -> {
                if (tickBindHold()) return; // 締めている間は、繰り返しの頭まで続ける
            }
            case ACTION_SKILL_RECOVER -> {
                if (r.skill == Skill.BIND && actionAge == BIND_RESET_AT) resetJaw(); // 顎が地面の下にいる間に元の場所へ
            }
            default -> {
            }
        }
        actionAge++;
        if (--actionLeft > 0) return;
        switch (action) {
            case ACTION_EMERGE -> begin(ACTION_NONE, 0);
            case ACTION_LASH_WINDUP -> {
                if (r.predator()) {
                    begin(ACTION_LASH, TRAP);
                    playSound(SoundEvents.CAVE_VINES_PLACE, 1.0F, 0.5F);
                    return;
                }
                lashHit.clear();
                begin(ACTION_LASH, G1Rooted.LASH);
                playSound(ModSounds.G1_VINE_LASH.get(), 1.0F, 0.8F);
            }
            case ACTION_LASH -> {
                if (r.predator()) {
                    // 葉が閉じ切った: 捕まえていれば締める段へ、空振りなら開いて戻る
                    if (bound != null) {
                        trapLoops = 0;
                        begin(ACTION_TRAP_HOLD, TRAP_HOLD);
                    } else {
                        begin(ACTION_LASH_RECOVER, TRAP_RECOVER);
                    }
                    return;
                }
                lashSweep(G1Rooted.LASH, G1Rooted.LASH); // 最後の刻み(前の右まで)
                begin(ACTION_LASH_RECOVER, G1Rooted.LASH_RECOVER);
            }
            case ACTION_TRAP_HOLD -> endTrapLoop(r);
            case ACTION_LASH_RECOVER -> {
                lashReadyIn = r.predator() ? TRAP_COOLDOWN : LASH_COOLDOWN;
                begin(ACTION_NONE, 0);
            }
            case ACTION_SHOOT_WINDUP -> {
                begin(ACTION_SHOOT, r.shoot);
                if (r.bloom()) {
                    puffCloud(); // 胞子の雲を出すのは puff の頭(全周)
                } else if (hasTarget) { // 塊・弾を出すのは shoot の頭
                    if (r.skill == Skill.BALLISTA) shootBolt(target);
                    else shootLump(target);
                }
            }
            case ACTION_SHOOT -> begin(ACTION_SHOOT_RECOVER, r.shootRecover);
            case ACTION_SHOOT_RECOVER -> {
                shootReadyIn = r.bloom() ? PUFF_COOLDOWN : r.skill == Skill.BALLISTA ? BALLISTA_COOLDOWN : SHOOT_COOLDOWN;
                begin(ACTION_NONE, 0);
            }
            case ACTION_SKILL_WINDUP -> {
                if (r.skill == Skill.BIND) placeJaw(hasTarget ? target : null); // 顎が全部地面の下にいる瞬間に、標的の足元へ
                begin(ACTION_SKILL, r.skillLength);
            }
            case ACTION_SKILL -> {
                // 掴む・引き込む・噛み合うのは実行の段の終わり(腕が伸び切る・指が閉じる・顎が閉じる)
                if (r.skill == Skill.BIND) {
                    if (hasTarget && tryBind(target)) {
                        begin(ACTION_SKILL_HOLD, BIND_HOLD_LOOP);
                        return;
                    }
                } else if (hasTarget) {
                    if (r.skill == Skill.GRAB) tryGrab(target);
                    else if (r.skill == Skill.REACH) pullIn(target);
                }
                begin(ACTION_SKILL_RECOVER, r.skillRecover);
            }
            case ACTION_SKILL_HOLD -> {
                // 繰り返しの頭: まだ締めているなら次の 1 周、放すなら戻りへ(頭で抜けると姿の段差が無い)
                if (bound != null) begin(ACTION_SKILL_HOLD, BIND_HOLD_LOOP);
                else begin(ACTION_SKILL_RECOVER, r.skillRecover);
            }
            case ACTION_SKILL_RECOVER -> {
                skillReadyIn = switch (r.skill) {
                    case REACH -> REACH_COOLDOWN;
                    case MILK -> MILK_INTERVAL;
                    case LOOSE -> LOOSE_INTERVAL;
                    case LAY -> LAY_INTERVAL_MIN + random.nextInt(LAY_INTERVAL_MAX - LAY_INTERVAL_MIN + 1);
                    case BIND -> BIND_COOLDOWN;
                    case WEB -> WEB_INTERVAL;
                    case BURST -> 0;
                    default -> GRAB_COOLDOWN;
                };
                begin(ACTION_NONE, 0);
            }
            case ACTION_SKILL_ONE -> {
                skillReadyIn = r.skill == Skill.BELL ? BELL_INTERVAL : SMOKE_COOLDOWN;
                begin(ACTION_NONE, 0);
            }
            default -> begin(ACTION_NONE, 0);
        }
    }

    // ------------------------------------------------------------------ 背から咲く型(区切り 4): 胞子の雲と 4 種の能力
    /** bind_hold の 1 周(tick)。 */
    static final int BIND_HOLD_LOOP = 20;
    /** 豚: 今縛っている相手と、縛った場所・縛ってからの tick・相手が殴った数・前の tick に腕を振っていたか。 */
    @Nullable
    private LivingEntity bound;
    private Vec3 boundAt = Vec3.ZERO;
    private int boundTicks;
    private int boundHits;
    private boolean boundWasSwinging;
    /** 豚: 抜けたばかりの相手(しばらく巻かない)と、その期限(tickCount)。 */
    private int bindImmuneId = -1;
    private int bindImmuneUntil;

    /** 背から咲く型の攻撃選び: 縛る(豚)> 胞子の雲。薙ぎ払い・塊は無い。 */
    private void chooseBloomAttack(G1Rooted r, LivingEntity target, double reach) {
        if (r.skill == Skill.BIND && skillReadyIn <= 0 && reach <= BIND_RADIUS && sameLevel(target)
                && !(target.getId() == bindImmuneId && tickCount < bindImmuneUntil)) {
            begin(ACTION_SKILL_WINDUP, r.skillWindup);
            return;
        }
        if (shootReadyIn <= 0 && reach <= PUFF_TRIGGER) begin(ACTION_SHOOT_WINDUP, r.shootWindup);
    }

    /** 房がしぼむ瞬間に、周りへ胞子の雲(全周。中にいると暴露が積まれ、視界が霞む)。 */
    private void puffCloud() {
        if (!(level() instanceof ServerLevel level)) return;
        G1SporeCloud.spawn(level, position(), PUFF_RADIUS, PUFF_TICKS, PUFF_DOSE_PER_SECOND, -1, 0, true, this);
        level.playSound(null, getX(), getY() + 1.0D, getZ(), ModSounds.G1_SAC_LUMP_HIT.get(), SoundSource.HOSTILE, 1.0F, 0.5F);
    }

    /** 牛・羊・鶏の実行の段(標的に向けない能力)。出すのは段の頭。 */
    private void tickBloomSkill(G1Rooted r, int age) {
        if (!(level() instanceof ServerLevel level)) return;
        switch (r.skill) {
            case MILK -> {
                if (age != 0) return;
                // 乳だまりの沼を周りに広げる(踏むと良い効果が消える。1 段目の牛の乳だまりと同じもの)
                for (int i = 0; i < MILK_PUDDLES; i++) {
                    double a = random.nextDouble() * Math.PI * 2.0D;
                    double d = MILK_NEAR + random.nextDouble() * (MILK_FAR - MILK_NEAR);
                    float radius = MILK_RADIUS_MIN + random.nextFloat() * (MILK_RADIUS_MAX - MILK_RADIUS_MIN);
                    G1MilkPuddle.spawn(level, position().add(Math.cos(a) * d, 0.0D, Math.sin(a) * d), radius, MILK_TICKS);
                }
                level.playSound(null, getX(), getY() + 0.3D, getZ(), ModSounds.G1_SAC_LUMP_HIT.get(), SoundSource.HOSTILE, 0.9F, 0.4F);
            }
            case LOOSE -> {
                if (age >= LOOSE_COUNT) return;
                // 綿毛を風に乗せる(着く所の降り積もりは放った瞬間に足す。1 段目の羊と同じ部品)。小さい玉が tick 0・1・2 に 1 つずつ
                Vec3 from = position().add(0.0D, 3.0D, 0.0D);
                G1FluffDrift.loose(level, from, LOOSE_DEPOSIT_PERCENT, random);
                if (age == 0) level.playSound(null, from.x, from.y, from.z, SoundEvents.WOOL_BREAK, SoundSource.HOSTILE, 1.2F, 0.6F);
            }
            case LAY -> {
                if (age != 0) return;
                layEgg(level);
            }
            case WEB -> {
                if (age == 0) spinWebs(level); // 網のブロックを置くのは web の頭から
            }
            case BURST -> {
                if (age == 0) burstPods(level); // 爆ぜるのは burst の頭
            }
            default -> {
            }
        }
    }

    /** 鶏: 根元のまわりに汚染された卵を 1 つ産み落とす(周りの卵が上限なら産まない)。 */
    private void layEgg(ServerLevel level) {
        BlockPos base = blockPosition();
        if (CorruptedEggBlock.countAround(level, base, LAY_LIMIT_RADIUS, LAY_LIMIT_HEIGHT) >= LAY_LIMIT) return;
        for (int tries = 0; tries < 12; tries++) {
            BlockPos p = base.offset(random.nextInt(LAY_RADIUS * 2 + 1) - LAY_RADIUS, random.nextInt(3) - 1,
                    random.nextInt(LAY_RADIUS * 2 + 1) - LAY_RADIUS);
            if (p.equals(base) || !CorruptedEggBlock.canLayAt(level, p)) continue;
            if (CorruptedEggBlock.layAt(level, p, com.yayuy.overgrownvegetation.registry.ModG1.CORRUPTED_EGG.get())) {
                level.playSound(null, p, ModSounds.G1_SAC_SWELL.get(), SoundSource.HOSTILE, 0.8F, 0.8F);
                return;
            }
        }
    }

    // ---- 豚: 根の顎で縛る
    /** 顎を標的の足元へ動かす(同期する位置 = 体の左・前へのブロック数)。標的がいなければ静止の場所のまま。 */
    private void placeJaw(@Nullable LivingEntity target) {
        if (target == null) return;
        float yaw = Float.isNaN(anchorYaw) ? getYRot() : anchorYaw;
        double rad = yaw * Mth.DEG_TO_RAD;
        double fx = -Math.sin(rad), fz = Math.cos(rad), lx = Math.cos(rad), lz = Math.sin(rad);
        double dx = target.getX() - getX(), dz = target.getZ() - getZ();
        this.entityData.set(JAW_LEFT, (float) (dx * lx + dz * lz));
        this.entityData.set(JAW_FRONT, (float) (dx * fx + dz * fz));
        boundAt = new Vec3(target.getX(), target.getY(), target.getZ());
    }

    private void resetJaw() {
        this.entityData.set(JAW_LEFT, JAW_REST_LEFT);
        this.entityData.set(JAW_FRONT, JAW_REST_FRONT);
        this.entityData.set(JAW_SCALE, 1.0F);
    }

    /** 顎が噛み合う瞬間に、相手がまだ顎の出た所の近くにいれば縛る(横へ逃げていれば外れる)。 */
    private boolean tryBind(LivingEntity target) {
        double dx = target.getX() - boundAt.x, dz = target.getZ() - boundAt.z;
        if (dx * dx + dz * dz > BIND_MISS * BIND_MISS || Math.abs(target.getY() - boundAt.y) > 1.5D) return false;
        bound = target;
        boundAt = target.position();
        boundTicks = 0;
        boundHits = 0;
        boundWasSwinging = target.swinging;
        this.entityData.set(JAW_SCALE, BIND_JAW_SCALE);
        playSound(SoundEvents.CAVE_VINES_PLACE, 1.0F, 0.6F);
        return true;
    }

    /**
     * 縛っている間(毎 tick): 相手をその場に留め、1 秒ごとに暴露を積む。相手が腕を {@link #BIND_HITS} 回振る(根を殴る)か、
     * 上限の長さを超えるか、相手がいなくなったら放す(放すのは繰り返しの頭)。
     *
     * @return まだ締めている途中で、この tick の残りの段取りを飛ばしてよいか(false = いつもどおり段を進める)
     */
    private boolean tickBindHold() {
        LivingEntity e = bound;
        if (e == null) return false;
        if (!e.isAlive() || e.isRemoved() || e.level() != level()) {
            release();
            return false;
        }
        boundTicks++;
        // その場に留める(落ちるのだけは許す)
        Vec3 v = e.getDeltaMovement();
        e.setDeltaMovement(0.0D, Math.min(v.y, 0.0D), 0.0D);
        double dx = e.getX() - boundAt.x, dz = e.getZ() - boundAt.z;
        if (dx * dx + dz * dz > 0.01D) e.teleportTo(boundAt.x, e.getY(), boundAt.z);
        e.hurtMarked = true;
        if (boundTicks % 20 == 0) G1SporeDose.add(e, BIND_DOSE_PER_SECOND);
        // 根を殴る = 縛られている相手が腕を振る(振り始めを 1 回と数える)
        if (e.swinging && !boundWasSwinging) boundHits++;
        boundWasSwinging = e.swinging;
        if (boundHits >= BIND_HITS || boundTicks >= BIND_MAX_TICKS) release();
        return false;
    }

    private void release() {
        if (bound != null) {
            bindImmuneId = bound.getId();
            bindImmuneUntil = tickCount + BIND_IMMUNE_TICKS;
        }
        bound = null;
    }

    public boolean isBinding() {
        return bound != null;
    }

    /** 縛られている相手が根を殴った数(テスト用)。 */
    public int bindHits() {
        return boundHits;
    }

    // ------------------------------------------------------------------ 地面の捕食植物(区切り 5): 葉の輪で捕まえる・クモの網・クリーパーの爆ぜ
    /** 捕まえる動きの長さ(tick。書き出しの記録): 葉が張り付く 6・閉じる 5(閉じ切るのは tick 4)・締める 1 周 20・開いて戻る 28。 */
    static final int TRAP_WINDUP = 6, TRAP = 5, TRAP_HOLD = 20, TRAP_RECOVER = 28, TRAP_CATCH_AT = 4;
    /** 【仮】捕まえた獲物を置く所(中心からの長さ)・締める周の数(クモ)・1 周に脈打つ tick と 1 回のダメージ・締めている間の暴露量(%秒 / 秒)・次に捕まえるまで。 */
    static final double TRAP_HOLD_AT = 1.1D;
    static final int TRAP_LOOPS = 3;
    static final int[] TRAP_SQUEEZE_AT = {5, 15};
    static final float TRAP_SQUEEZE_DAMAGE = 1.0F;
    static final double TRAP_DOSE_PER_SECOND = 80.0D;
    static final int TRAP_COOLDOWN = 60;
    /** 【仮】クモの網: 張る間隔・1 回に置く数・最初の半径と広がりきった半径(11-12 ユーザー: 7)・網の上の暴露量(%秒 / 秒。同: 毎 tick +1〜2)。 */
    static final int WEB_INTERVAL = 400;
    static final int WEB_PER_ACTION = 6;
    static final int WEB_RADIUS_START = 2, WEB_RADIUS_MAX = 7;
    static final double WEB_DOSE_PER_SECOND = 30.0D;
    /** 【仮】クリーパーの爆ぜ(1 段目のクリーパーの爆ぜと同じ数): 汚染で覆う半径・揺らぎ・浮遊の上げ幅・暴露(中心 / 縁)・届く半径・ダメージ・押し返し。 */
    static final int BURST_CORRUPT_RADIUS = 3;
    static final double BURST_NOISE = 1.0D, BURST_AIR_PERCENT = 40.0D;
    static final double BURST_DOSE_CENTER = 600.0D, BURST_DOSE_EDGE = 200.0D, BURST_DOSE_RADIUS = 4.0D;
    static final float BURST_DAMAGE = 4.0F;
    static final double BURST_KNOCKBACK = 0.6D;

    private int trapLoops;
    /** クモ: 今の網の半径と、張った網のマス(倒されたら枯らす)。 */
    private int webRadius = WEB_RADIUS_START;
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet webs = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** 葉の輪の上(中心から {@code lashRange} まで)にいる、いちばん近い獲物。 */
    @Nullable
    private LivingEntity preyOnRing() {
        double ring = rooted().lashRange;
        LivingEntity best = null;
        double bestReach = Double.MAX_VALUE;
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(ring + 1.0D, 0.5D, ring + 1.0D))) {
            if (!G1TargetGoal.isPrey(this, e) || !sameLevel(e)) continue;
            if (e.getId() == bindImmuneId && tickCount < bindImmuneUntil) continue;
            double reach = reachTo(e);
            if (reach <= ring && reach < bestReach) {
                best = e;
                bestReach = reach;
            }
        }
        return best;
    }

    /** 待ち伏せ: 輪を踏んだ獲物がいれば捕まえる動きへ。いなければ、クモは間が空いたら網を張る。 */
    private void predatorIdle() {
        G1Rooted r = rooted();
        if (r.skill == Skill.WEB && tickCount % 20 == 0) doseOnWebs();
        if (lashReadyIn <= 0 && preyOnRing() != null) {
            begin(ACTION_LASH_WINDUP, TRAP_WINDUP);
            return;
        }
        if (r.skill == Skill.WEB && skillReadyIn <= 0) begin(ACTION_SKILL_WINDUP, r.skillWindup);
    }

    /** 葉が閉じ切る瞬間: まだ輪の上にいる、いちばん近い獲物を捕まえ、蕾のそばへ寄せて留める。 */
    private void catchPrey() {
        LivingEntity e = preyOnRing();
        if (e == null) return;
        double dx = e.getX() - getX(), dz = e.getZ() - getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1.0E-3D) {
            dx = 1.0D;
            dz = 0.0D;
            d = 1.0D;
        }
        boundAt = new Vec3(getX() + dx / d * TRAP_HOLD_AT, e.getY(), getZ() + dz / d * TRAP_HOLD_AT);
        bound = e;
        boundTicks = 0;
        if (e.isPassenger()) e.stopRiding();
        e.teleportTo(boundAt.x, boundAt.y, boundAt.z);
        e.setDeltaMovement(Vec3.ZERO);
        e.hurtMarked = true;
    }

    /** 締めている間(毎 tick): その場に留め、1 秒ごとに暴露、1 周に 2 回脈打って少し傷つける。 */
    private void holdTrapped(int age) {
        LivingEntity e = bound;
        if (e == null) return;
        if (!e.isAlive() || e.isRemoved() || e.level() != level()) {
            release();
            return;
        }
        boundTicks++;
        // 脈打って傷つけるのは、留める前に(打撃の押し返しで動かさない)
        for (int at : TRAP_SQUEEZE_AT) {
            if (age == at) e.hurt(damageSources().mobAttack(this), TRAP_SQUEEZE_DAMAGE);
        }
        e.setDeltaMovement(0.0D, Math.min(e.getDeltaMovement().y, 0.0D), 0.0D);
        double dx = e.getX() - boundAt.x, dz = e.getZ() - boundAt.z;
        if (dx * dx + dz * dz > 0.01D) e.teleportTo(boundAt.x, e.getY(), boundAt.z);
        e.hurtMarked = true;
        if (boundTicks % 20 == 0) G1SporeDose.add(e, TRAP_DOSE_PER_SECOND);
    }

    /**
     * 締める 1 周の終わり(繰り返しの頭 = ここで抜けると姿の段差が無い)。
     * クモ: 決まった周の数を締めたら放して開く。クリーパー: 1 周締めたら、捕まえたまま莢を爆ぜさせる(爆ぜた勢いで蕾が開いて放す)。
     */
    private void endTrapLoop(G1Rooted r) {
        trapLoops++;
        if (bound == null) {
            begin(ACTION_LASH_RECOVER, TRAP_RECOVER);
        } else if (r.skill == Skill.BURST) {
            begin(ACTION_SKILL_WINDUP, r.skillWindup);
        } else if (trapLoops >= TRAP_LOOPS) {
            release();
            begin(ACTION_LASH_RECOVER, TRAP_RECOVER);
        } else {
            begin(ACTION_TRAP_HOLD, TRAP_HOLD);
        }
    }

    /** クリーパー: 蕾の中で莢が爆ぜる(1 段目のクリーパーの爆ぜと同じ部品。ブロックは壊さない)。捕まえていた獲物はここで放す。 */
    private void burstPods(ServerLevel level) {
        G1SporeBlast.blast(level, this, position(), BURST_CORRUPT_RADIUS, BURST_NOISE, BURST_AIR_PERCENT, BURST_DOSE_CENTER, BURST_DOSE_EDGE,
                BURST_DOSE_RADIUS, BURST_DAMAGE, BURST_KNOCKBACK);
        level.playSound(null, getX(), getY() + 0.6D, getZ(), ModSounds.G1_SAC_BURST.get(), SoundSource.HOSTILE, 1.3F, 0.6F);
        release();
        lashReadyIn = TRAP_COOLDOWN + r_burstRecover();
    }

    private int r_burstRecover() {
        return rooted().skillLength + rooted().skillRecover;
    }

    /** クモ: 今の半径の中の地面に網(根糸の巣)を張り、半径を 1 つ広げる(7 まで)。 */
    private void spinWebs(ServerLevel level) {
        BlockPos base = blockPosition();
        int placed = 0;
        for (int tries = 0; tries < WEB_PER_ACTION * 5 && placed < WEB_PER_ACTION; tries++) {
            int dx = random.nextInt(webRadius * 2 + 1) - webRadius, dz = random.nextInt(webRadius * 2 + 1) - webRadius;
            if (dx * dx + dz * dz > webRadius * webRadius || (Math.abs(dx) <= 1 && Math.abs(dz) <= 1)) continue; // 葉の輪の真ん中は空ける
            for (int dy = 1; dy >= -1; dy--) {
                BlockPos p = base.offset(dx, dy, dz);
                if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) continue;
                if (com.yayuy.overgrownvegetation.block.RootsilkWebBlock.placeAt(level, p)) {
                    webs.add(p.asLong());
                    placed++;
                }
                break;
            }
        }
        if (webRadius < WEB_RADIUS_MAX) webRadius++;
    }

    /** クモ: 網の上にいる生き物の暴露を積む(1 秒ごと)。 */
    private void doseOnWebs() {
        net.minecraft.world.level.block.Block web = com.yayuy.overgrownvegetation.registry.ModG1.ROOTSILK_WEB.get();
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(WEB_RADIUS_MAX + 1.0D, 2.0D, WEB_RADIUS_MAX + 1.0D))) {
            if (e != this && level().getBlockState(e.blockPosition()).is(web)) G1SporeDose.add(e, WEB_DOSE_PER_SECOND);
        }
    }

    /** クモ: 倒されると、張った網は枯れて消える(11-12 ユーザー)。 */
    private void witherWebs() {
        if (webs.isEmpty() || !(level() instanceof ServerLevel level)) return;
        net.minecraft.world.level.block.Block web = com.yayuy.overgrownvegetation.registry.ModG1.ROOTSILK_WEB.get();
        for (long w : webs) {
            BlockPos p = BlockPos.of(w);
            if (level.isLoaded(p) && level.getBlockState(p).is(web)) level.removeBlock(p, false);
        }
        webs.clear();
    }

    public boolean isTrapping() {
        return bound != null && rooted().predator();
    }

    public int webRadius() {
        return webRadius;
    }

    // ------------------------------------------------------------------ 薙ぎ払い
    /**
     * 蔦が通り過ぎた所にいる相手を打つ。{@code tick} = {@code lash} に入ってからの tick(0〜{@code length})。
     * 蔦は前の左 21° から左回りに 325° 回るので、相手の向き(左回りの度)が蔦の進んだ角度までに入っていれば当たり。1 体 1 回。
     */
    private void lashSweep(int tick, int length) {
        double range = rooted().lashRange;
        float swept = LASH_START_DEG + LASH_SWEEP_DEG * Mth.clamp(tick / (float) Math.max(1, length), 0.0F, 1.0F);
        boolean last = tick >= length;
        double box = range + 2.0D;
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, new AABB(getX() - box, getY() - 0.5D, getZ() - box,
                getX() + box, getY() + 2.5D, getZ() + box))) {
            if (e == this || !e.isAlive() || lashHit.contains(e.getId()) || !G1TargetGoal.isPrey(this, e)) continue;
            if (reachTo(e) > range || !sameLevel(e)) continue;
            if (!last && leftAngleTo(e) > swept) continue; // 蔦がまだ来ていない
            lashHit.add(e.getId());
            if (e.hurt(damageSources().mobAttack(this), (float) getAttributeValue(Attributes.ATTACK_DAMAGE))) {
                double dx = e.getX() - getX(), dz = e.getZ() - getZ();
                e.knockback(LASH_KNOCK, -dx, -dz); // 柱から外へ
            }
        }
    }

    // ------------------------------------------------------------------ 撃つ
    private void shootLump(LivingEntity target) {
        double height = rooted().shootHeight;
        G1SporeLump lump = new G1SporeLump(level(), this);
        lump.setLoad(SHOOT_DOSE, SHOOT_HEAL);
        lump.setPos(getX(), getY() + height, getZ());
        double dx = target.getX() - getX();
        double dz = target.getZ() - getZ();
        double dy = target.getY(0.5D) - lump.getY();
        double h = Math.sqrt(dx * dx + dz * dz);
        lump.shoot(dx, dy + SHOOT_DROP * h * h, dz, SHOOT_SPEED, 1.0F);
        level().addFreshEntity(lump);
        level().playSound(null, getX(), getY() + height, getZ(), ModSounds.G1_SAC_LUMP_HIT.get(), SoundSource.HOSTILE, 0.9F, 0.7F);
    }

    /** スケルトン: 柱の上の弓から、山なりの弾(1 段目の植物の弓と同じ弾。当たればダメージ + 暴露、外れれば着弾した所を汚染)。 */
    private void shootBolt(LivingEntity target) {
        aimAt(target);
        Vec3 from = new Vec3(getX(), getY() + rooted().shootHeight, getZ());
        Vec3 dir = G1SporeBolt.launch(from, target.getBoundingBox().getCenter(), BALLISTA_SPEED);
        G1SporeBolt bolt = new G1SporeBolt(level(), this).setLoad(BALLISTA_DOSE, BALLISTA_DAMAGE);
        bolt.setPos(from.x, from.y, from.z);
        bolt.shoot(dir.x, dir.y, dir.z, BALLISTA_SPEED, BALLISTA_SPREAD);
        level().addFreshEntity(bolt);
        level().playSound(null, from.x, from.y, from.z, SoundEvents.SKELETON_SHOOT, SoundSource.HOSTILE, 1.0F, 0.5F);
        level().playSound(null, from.x, from.y, from.z, ModSounds.G1_SAC_SWELL.get(), SoundSource.HOSTILE, 0.6F, 0.9F);
    }

    // ------------------------------------------------------------------ ゾンビ: 絡め取り
    /** 伸ばした向き(溜めの終わりに止めた向き)の ±25° に相手がいて、届いて、見えていれば絡める。 */
    private void tryGrab(LivingEntity target) {
        double reach = reachTo(target);
        float off = Math.abs(Mth.wrapDegrees(leftAngleTo(target) - aimYaw()));
        if (reach > GRAB_MAX + 0.5D || off > GRAB_CONE || !getSensing().hasLineOfSight(target)) return;
        tether = new G1Tether(this, target, GRAB_HOLD, GRAB_PULL, GRAB_SLOW, GRAB_BREAK);
        playSound(SoundEvents.CAVE_VINES_PLACE, 1.0F, 1.2F);
    }

    public boolean isTethering() {
        return tether != null;
    }

    // ------------------------------------------------------------------ チビゾンビ: 煙幕 / 村人: 鐘
    private void tickOneClipSkill(G1Rooted r, int age) {
        if (!(level() instanceof ServerLevel level)) return;
        if (r.skill == Skill.SMOKE) {
            if (age == SMOKE_AT) {
                G1SporeCloud.spawn(level, position(), SMOKE_RADIUS, SMOKE_TICKS, SMOKE_DOSE_PER_SECOND, -1, 0, true, this);
                level.playSound(null, getX(), getY() + 0.7D, getZ(), ModSounds.G1_SAC_LUMP_HIT.get(), SoundSource.HOSTILE, 0.8F, 0.5F);
            }
        } else if (r.skill == Skill.BELL) {
            for (int at : BELL_AT) {
                if (age == at) {
                    level.playSound(null, getX(), getY() + 3.9D, getZ(), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 1.6F, 0.6F);
                }
            }
            if (age == BELL_AT[0]) bellPullLeft = BELL_PULL_TICKS;
        }
    }

    /**
     * 村人: 柱のまわりは常に暴露量が上がる(1 秒ごと)。鐘が鳴った後しばらくは、周りの動物・村人を柱の方へ歩かせる
     * (寄ってきた生き物が感染して新しい G1 になる)。
     */
    private void tickVillagerAura() {
        if (tickCount % 20 != 0) return;
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(AURA_RADIUS, 2.0D, AURA_RADIUS))) {
            if (e != this && distanceToSqr(e) <= AURA_RADIUS * AURA_RADIUS && !tooFarForTests(e)) G1SporeDose.add(e, AURA_DOSE_PER_SECOND);
        }
        if (bellPullLeft <= 0) return;
        bellPullLeft -= 20;
        for (PathfinderMob m : level().getEntitiesOfClass(PathfinderMob.class, getBoundingBox().inflate(BELL_RADIUS, 6.0D, BELL_RADIUS))) {
            if (!(m instanceof Animal || m instanceof AbstractVillager) || !G1TargetGoal.isPrey(this, m)) continue;
            if (m.distanceToSqr(this) > BELL_RADIUS * BELL_RADIUS || m.distanceToSqr(this) < 9.0D || tooFarForTests(m)) continue;
            m.getNavigation().moveTo(getX(), getY(), getZ(), 1.0D);
        }
    }

    public boolean isBellPulling() {
        return bellPullLeft > 0;
    }

    // ------------------------------------------------------------------ 村人: 根の守り手を育てる
    public int seedState() {
        return this.entityData.get(SEED);
    }

    /** 生きている守り手。いなければ null。 */
    @Nullable
    public RootGuardian guardian() {
        if (guardian == null || !(level() instanceof ServerLevel level)) return null;
        return level.getEntity(guardian) instanceof RootGuardian g && g.isAlive() ? g : null;
    }

    /**
     * 攻撃された: 守り手がいなくて育っていれば、足元の「守り手のもと」が膨らみ始める(guardian_spawn。tick 20 に守り手が立ち上がる)。
     * もう守り手がいれば、殴ってきた相手へ向かわせる。
     */
    private void onVillagerAttacked(LivingEntity foe) {
        guardianFoe = foe;
        RootGuardian g = guardian();
        if (g != null) {
            if (g.getTarget() == null && G1TargetGoal.isPrey(g, foe)) g.setTarget(foe);
            return;
        }
        if (seedState() == SEED_NONE && guardianRegrowIn <= 0) {
            this.entityData.set(SEED, SEED_SHELL);
            seedTimer = 0;
        }
    }

    private void tickGuardian() {
        if (!(level() instanceof ServerLevel level)) return;
        int seed = seedState();
        if (seed == SEED_SHELL) {
            seedTimer++;
            if (seedTimer == GUARDIAN_SPAWN_AT) {
                spawnGuardian(level);
            } else if (seedTimer > GUARDIAN_SPAWN_AT && tickCount % 20 == 0 && guardian() == null && guardianAreaTicking(level)) {
                // 守り手が倒された: 殻のまましばらく待ってから育ち直す。「いない」と決めるのは、守り手の持ち場(柱から 12 ブロック)が
                // 読み込まれて動いているときだけ(境目で見えないだけの間に 2 体目を育てない)
                guardian = null;
                if (guardianRegrowIn <= 0) guardianRegrowIn = GUARDIAN_REGROW_WAIT;
            }
            if (guardianRegrowIn > 0 && guardian == null && --guardianRegrowIn <= 0) {
                this.entityData.set(SEED, SEED_REGROW);
                seedTimer = 0;
            }
        } else if (seed == SEED_REGROW && ++seedTimer >= GUARDIAN_REGROW) {
            this.entityData.set(SEED, SEED_NONE);
        }
    }

    /** 守り手の持ち場(柱から {@link RootGuardian#GUARD_RADIUS} ブロック)の四隅と真ん中が、読み込まれて動いているか。 */
    private boolean guardianAreaTicking(ServerLevel level) {
        int r = (int) RootGuardian.GUARD_RADIUS + 1;
        BlockPos c = blockPosition();
        return level.isPositionEntityTicking(c) && level.isPositionEntityTicking(c.offset(r, 0, r)) && level.isPositionEntityTicking(c.offset(-r, 0, r))
                && level.isPositionEntityTicking(c.offset(r, 0, -r)) && level.isPositionEntityTicking(c.offset(-r, 0, -r));
    }

    /** 守り手を、柱の左前の足元(守り手のもとの側)に出して立ち上がらせる。 */
    private void spawnGuardian(ServerLevel level) {
        RootGuardian g = com.yayuy.overgrownvegetation.registry.ModG1.ROOT_GUARDIAN.get().create(level);
        if (g == null) return;
        float yaw = Float.isNaN(anchorYaw) ? getYRot() : anchorYaw;
        double rad = yaw * Mth.DEG_TO_RAD;
        // 守り手のもと(骨 guardian_seed)は柱の左前: 左 0.63・前 0.34 の向きへ、柱と重ならない長さだけ離す
        double fx = -Math.sin(rad), fz = Math.cos(rad), lx = Math.cos(rad), lz = Math.sin(rad);
        double ox = lx * 0.88D + fx * 0.47D, oz = lz * 0.88D + fz * 0.47D;
        g.moveTo(getX() + ox * GUARDIAN_OFFSET, getY(), getZ() + oz * GUARDIAN_OFFSET, yaw, 0.0F);
        g.yBodyRot = yaw;
        g.yHeadRot = yaw;
        g.guard(this);
        level.addFreshEntity(g);
        guardian = g.getUUID();
        if (guardianFoe != null && guardianFoe.isAlive() && G1TargetGoal.isPrey(g, guardianFoe)) g.setTarget(guardianFoe);
    }

    // ------------------------------------------------------------------ エンダーマン: 引き込み
    /** 長い腕を向ける角度(見た目)。向けられる範囲に切る。 */
    private float reachAim(Entity e) {
        return Mth.clamp(Mth.wrapDegrees(leftAngleTo(e)), REACH_AIM_MIN, REACH_AIM_MAX);
    }

    /**
     * 見えている相手を転移で手元(薙ぎ払いの間合いの中)へ引き込む。相手のそばの Mob も数体一緒に引き込み、近くの G1 を数体呼び集める
     * (11-12 ユーザー決定「両方」)。「呼び集める」は、G1 も転移で手元へ呼ぶ(11-13 ユーザー決定 Q7)。
     */
    private void pullIn(LivingEntity target) {
        if (!(level() instanceof ServerLevel level)) return;
        if (reachTo(target) > REACH_MAX + 1.0D || !getSensing().hasLineOfSight(target)) return;
        Vec3 from = target.position();
        double dx = from.x - getX(), dz = from.z - getZ();
        double d = Math.max(1.0E-3D, Math.sqrt(dx * dx + dz * dz));
        double ux = dx / d, uz = dz / d;
        List<Mob> near = level.getEntitiesOfClass(Mob.class, target.getBoundingBox().inflate(REACH_EXTRA_RADIUS),
                m -> m != this && m != target && m.isAlive() && !(m instanceof RootedParasitized) && !tooFarForTests(m));
        if (!bring(level, target, getX() + ux * REACH_LAND, getZ() + uz * REACH_LAND)) return;
        int extra = 0;
        for (Mob m : near) {
            if (extra >= REACH_EXTRA_COUNT) break;
            // 手元のまわりに散らす(相手の左右)
            double side = (extra % 2 == 0 ? 1.0D : -1.0D) * (1.0D + extra / 2);
            if (bring(level, m, getX() + ux * (REACH_LAND + 0.6D) - uz * side, getZ() + uz * (REACH_LAND + 0.6D) + ux * side)) extra++;
        }
        int called = 0;
        for (AbstractParasitizedMonster g : level.getEntitiesOfClass(AbstractParasitizedMonster.class,
                getBoundingBox().inflate(REACH_CALL_RADIUS, 8.0D, REACH_CALL_RADIUS))) {
            if (called >= REACH_CALL_COUNT) break;
            if (!g.isAlive() || g.isPassenger() || g.isVehicle() || g.isRising() || tooFarForTests(g)) continue;
            // 11-13 ユーザー決定(Q7 = 2): G1 も転移で手元へ呼ぶ(引き込んだ瞬間に囲まれる)。出す場所は、柱から 3.2 ブロックの輪の上で、
            // 引き込んだ相手の向きから ±50°・±100°…と左右へ振る【仮】。体が入らない所は飛ばす
            double turn = Math.toRadians(50.0D * (1 + called / 2)) * (called % 2 == 0 ? 1.0D : -1.0D);
            double cx = ux * Math.cos(turn) - uz * Math.sin(turn), cz = ux * Math.sin(turn) + uz * Math.cos(turn);
            if (!bring(level, g, getX() + cx * REACH_CALL_RING, getZ() + cz * REACH_CALL_RING)) continue;
            g.setTarget(target);
            called++;
        }
        level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 0.8F);
        level.playSound(null, getX(), getY() + 1.0D, getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 0.8F);
    }

    /** 相手を (x, z) の、柱の足元と同じ高さへ転移させる。そこに体が入らなければ何もしない。 */
    private boolean bring(ServerLevel level, LivingEntity e, double x, double z) {
        double y = getY();
        AABB moved = e.getBoundingBox().move(x - e.getX(), y - e.getY(), z - e.getZ());
        if (!level.noCollision(e, moved)) return false;
        if (e.isPassenger()) e.stopRiding();
        e.teleportTo(x, y, z);
        e.setDeltaMovement(Vec3.ZERO);
        e.hurtMarked = true;
        e.fallDistance = 0.0F;
        return true;
    }

    // ------------------------------------------------------------------ 殴られたとき
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide()) {
            if (tether != null && source.getEntity() instanceof LivingEntity attacker) {
                tether.onHolderHurtBy(attacker); // 絡めた相手に殴られると蔦が切れる(1 段目のゾンビと同じ)
            }
            if (rooted().skill == Skill.SMOKE && source.getEntity() != null) smokePending = true; // 叩かれると煙幕
            if (rooted().skill == Skill.BELL && source.getEntity() instanceof LivingEntity foe && foe != this) onVillagerAttacked(foe);
        }
        return hurt;
    }

    // ------------------------------------------------------------------ 周りの汚染
    private void corruptNearby() {
        if (!(level() instanceof ServerLevel level)) return;
        BlockPos base = blockPosition();
        for (int tries = 0; tries < 6; tries++) {
            BlockPos p = base.offset(random.nextInt(CORRUPT_RADIUS * 2 + 1) - CORRUPT_RADIUS,
                    random.nextInt(5) - 2, random.nextInt(CORRUPT_RADIUS * 2 + 1) - CORRUPT_RADIUS);
            if (CorruptionSpreader.corruptSingle(level, p)) return;
        }
    }

    // ------------------------------------------------------------------ 枯れて崩れる
    @Override
    protected void tickDeath() {
        if (deathTime == 0) {
            bound = null;
            witherWebs();
            if (!level().isClientSide()) playSound(ModSounds.G1_PLANT_WITHER.get(), 1.0F, 1.0F); // 音量【仮】
        }
        if (deathTime == 0 && tether != null) {
            tether.cut(false);
            tether = null;
        }
        ++deathTime;
        if (deathTime >= G1Rooted.WITHER && !level().isClientSide() && !isRemoved()) {
            remove(RemovalReason.KILLED);
        }
    }

    // ------------------------------------------------------------------ 保存
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (!Float.isNaN(anchorYaw)) tag.putFloat("AnchorYaw", anchorYaw);
        if (guardian != null) tag.putUUID("Guardian", guardian);
        tag.putInt("Seed", seedState());
        tag.putInt("SeedTimer", seedTimer);
        tag.putInt("GuardianRegrowIn", guardianRegrowIn);
        tag.putInt("WebRadius", webRadius);
        tag.putLongArray("Webs", webs.toLongArray());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("AnchorYaw")) anchorYaw = tag.getFloat("AnchorYaw");
        if (tag.hasUUID("Guardian")) guardian = tag.getUUID("Guardian");
        this.entityData.set(SEED, tag.getInt("Seed"));
        seedTimer = tag.getInt("SeedTimer");
        guardianRegrowIn = tag.getInt("GuardianRegrowIn");
        if (tag.contains("WebRadius")) webRadius = tag.getInt("WebRadius");
        webs.clear();
        for (long w : tag.getLongArray("Webs")) webs.add(w);
    }

    // ------------------------------------------------------------------ アニメ
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "act", 5, state -> {
            AnimationController<RootedParasitized> ctl = state.getController();
            if (isDeadOrDying()) {
                ctl.transitionLength(2);
                return state.setAndContinue(hold("death_wither"));
            }
            int a = action();
            RawAnimation clip = clipFor(a);
            if (clip != null) {
                // 3 本組は前の終わりの姿から始まるので段の間は補間 0。待機から溜め・1 本のクリップへは 2。生えるは 0(入れ替わりの瞬間から)
                boolean fromIdle = a == ACTION_LASH_WINDUP || a == ACTION_SHOOT_WINDUP || a == ACTION_SKILL_WINDUP || a == ACTION_SKILL_ONE;
                ctl.transitionLength(fromIdle ? 2 : 0);
                return state.setAndContinue(clip);
            }
            ctl.transitionLength(5);
            return state.setAndContinue(IDLE);
        }));
        // 村人の「守り手のもと」(足元の根のかたまり)だけを動かす 2 つ目の段取り。守り手が出ている間は殻の姿で止め、
        // 育ち直しで静止へ戻す。薙ぎ払い・鐘などの体の動きとは別に流す(後に足した方が、自分の骨を上書きする)
        controllers.add(new AnimationController<>(this, "seed", 0, state -> {
            int seed = seedState();
            if (seed == SEED_SHELL) return state.setAndContinue(hold("guardian_spawn"));
            if (seed == SEED_REGROW) return state.setAndContinue(once("guardian_regrow"));
            return software.bernie.geckolib.animation.PlayState.STOP;
        }));
    }

    @Nullable
    private RawAnimation clipFor(int action) {
        String skill = rooted().skill.clip;
        String shootClip = rooted().bloom() ? "puff" : "shoot"; // 背から咲く型の「撃つ」は胞子の雲
        String lashClip = rooted().predator() ? "trap" : "lash";
        return switch (action) {
            case ACTION_EMERGE -> hold("emerge");
            case ACTION_LASH_WINDUP -> hold(lashClip + "_windup");
            case ACTION_LASH -> hold(lashClip);
            case ACTION_LASH_RECOVER -> once(lashClip + "_recover");
            case ACTION_TRAP_HOLD -> loop("trap_hold");
            case ACTION_SHOOT_WINDUP -> hold(shootClip + "_windup");
            case ACTION_SHOOT -> hold(shootClip);
            case ACTION_SHOOT_RECOVER -> once(shootClip + "_recover");
            case ACTION_SKILL_WINDUP -> skill == null ? null : hold(skill + "_windup");
            case ACTION_SKILL -> skill == null ? null : hold(skill);
            case ACTION_SKILL_RECOVER -> skill == null ? null : once(skill + "_recover");
            case ACTION_SKILL_ONE -> skill == null ? null : once(skill);
            case ACTION_SKILL_HOLD -> skill == null ? null : loop(skill + "_hold");
            default -> null;
        };
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ------------------------------------------------------------------ テスト用
    /** 周りの汚染を止める(テスト場の外・隣の場へ汚染を漏らさない)。 */
    /** テスト用: 周りへ届く能力(鐘・暴露の輪・引き込みの巻き添え・G1 を呼ぶ)を、この長さの中だけにする(隣のテスト場の Mob を巻き込まない)。負 = 制限なし。 */
    private double testRange = -1.0D;

    public void setRangeLimitForTests(double blocks) {
        this.testRange = blocks;
    }

    private boolean tooFarForTests(Entity e) {
        return testRange >= 0.0D && e.distanceToSqr(this) > testRange * testRange;
    }

    public void stopCorruptingForTests() {
        corruptIn = Integer.MAX_VALUE;
    }

    /** 次に使えるまでの残り(tick)を決める(テスト・確かめ用)。3 つ目は種の能力。 */
    public void setCooldownsForTests(int lash, int shoot, int skill) {
        lashReadyIn = lash;
        shootReadyIn = shoot;
        skillReadyIn = skill;
    }
}
