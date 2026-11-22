package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.corpse.G1DeathBurst;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.GaitAnimationController;
import com.yayuy.overgrownvegetation.entity.crawler.SmallCrawler;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * ヴェシカリア {@code vesicaria}(G2・自爆特攻。11-14 に本名。練習名ハゼブクロ {@code g4_burster})。
 * 突撃中に熱で袋を膨らませて黄緑に光り、触れた瞬間に破裂する。矢で射るとその場で破裂し、近くの仲間も連鎖する
 * (Vault {@code Mob の能力/G4〜G6 の能力の決定(11-14).md}・{@code Mob の能力/ヴェシカリア.md})。
 *
 * <h2>移動(段 1)</h2>
 * 「小さな地這いの虫」の型({@link SmallCrawler})。形は練習モデル {@code prac2_g4_burster_r7}(正本 Blockbench の {@code vesicaria.bbmodel})。
 * 袋が重い = 少しもたつく【仮・Claude 11-15】: グレガリアより回るのが遅い({@link #TURN_PER_TICK} 度/tick)・一時停止が長い・うろつきも急ぎも遅い。
 *
 * <h3>歩き・走りの速さ合わせ(11-15 段 2 のアニメに合わせて)</h3>
 * walk(24 tick で 1 巡)は 1 倍で地面 {@link #WALK_REF}、run(5 tick で 1 巡)は {@link #RUN_REF} ブロック/tick で足が滑らない
 * (モデル制作のセッション vesicaria-anim が IK で焼いた確定値)。再生は実際の速さ ÷ 基準の速さ、walk は {@link #WALK_SPEED_MIN}〜{@link #WALK_SPEED_MAX} 倍、
 * run は {@link #RUN_SPEED_MIN}〜{@link #RUN_SPEED_MAX} 倍【仮】。突撃の間は run に固定し、そのほかは {@link #RUN_ON} 以上で run・{@link #RUN_OFF} 以下で walk【仮】。
 * walk と run は同じ三脚の並びなので、切り替えは 1 巡の中の位置を保って移る({@link GaitAnimationController})。
 *
 * <h2>能力(段 2、2026-11-15。能力の決定 11-14・アニメに合わせた細部 11-15 ユーザー)</h2>
 * <ul>
 *   <li><b>狙う相手</b>: プレイヤーと、植物側以外の Mob(アクムネスと同じ {@link AcumnesEntity#isPrey})【仮】。プレイヤーを先に。</li>
 *   <li><b>突撃</b>: 相手が {@link #CHARGE_START_RANGE} ブロックに入ったら、走るプレイヤーくらい(地面 約 {@link #CHARGE_GROUND})で突っ込む。
 *       止まって溜めない。突撃しながら袋が {@link #WINDUP_TICKS} tick(1.5 秒)で膨らみ、黄緑に光る(前触れ)。遠いうちは急ぎ(walk)で寄る。</li>
 *   <li><b>破裂</b>: 相手に触れた瞬間(猶予なし)・<b>どの傷でも</b>(矢・剣・火・落下)その場で破裂して死ぬ。
 *       弱めのダメージ {@link #BURST_DAMAGE}(半径 {@link #BURST_RADIUS} で外へ減る)+ 弾き飛ばし + 胞子の雲(植物側の死の破裂と同じ {@link G1DeathBurst})。
 *       ダメージの元はこの Mob なので、殴られた相手の感染は全体の仕組み({@code ParasitizedAttackInfectionHandler})が受け持つ。
 *       植物側の死の破裂({@code PlantSideBurst})はタグ {@code death_burst_exempt} で外し、二重に弾けない。</li>
 *   <li><b>連鎖</b>(11-18 夜 ユーザーがなくした。{@link #chainRadius} が 0 なら起きない): 弾けると、半径 {@link #CHAIN_RADIUS} の仲間が {@link #CHAIN_DELAY_MIN}〜{@link #CHAIN_DELAY_MAX} tick 遅れてつられて弾ける【仮】。</li>
 *   <li><b>死んだ後</b>: {@code burst}(20 tick)でしぼんだ姿になり、{@link #REMOVE_TICKS} tick で消える(バニラの白い煙は出さない)。</li>
 * </ul>
 * 光・音・粒の時刻はアニメの表(ヴェシカリア.md 6 節)に合わせる。数値は全部【仮・Claude 11-15】。
 */
public class VesicariaEntity extends SmallCrawler implements GeoEntity, ParasitizedMob {

    public static final String ID = "vesicaria";

    /** 描画倍率(テクスチャは 1 単位 1 テクセル = ブロックの 1 画素)。 */
    public static final float RENDER_SCALE = 1.0F;

    /** walk・run を 1 倍で流したときの地上速度(ブロック/tick。アニメの確定値)。 */
    public static final double WALK_REF = 0.045D, RUN_REF = 0.28D;
    /** 再生速度の範囲【仮】(モデル制作の見立て: walk 0.018〜0.11・run 約 0.17〜0.35 を覆う)。 */
    public static final double WALK_SPEED_MIN = 0.4D, WALK_SPEED_MAX = 2.5D, RUN_SPEED_MIN = 0.6D, RUN_SPEED_MAX = 1.25D;
    /** これ以上で run、これ以下で walk(ブロック/tick。突撃していないとき。行き来でばたつかないよう差を付ける)【仮】。 */
    static final double RUN_ON = 0.14D, RUN_OFF = 0.13D;
    /** 段 1 で測った急ぎ・うろつきの地面の速さ(ブロック/tick。テストの的)。 */
    public static final double RUSH_GROUND = 0.075D, STROLL_GROUND = 0.045D;
    /** 突撃の地面の速さ(ブロック/tick。11-15 ユーザー「走るプレイヤーくらい」)。 */
    public static final double CHARGE_GROUND = 0.28D;
    /** これ(ブロック/tick)以上進んだら歩き【仮】。 */
    static final double WALK_MIN_SPEED = 0.006D;

    /** 回る速さ(度/tick)【仮】。袋が重いぶんグレガリア(30)より遅い。 */
    static final float TURN_PER_TICK = 20.0F;
    /** うろつき(小走り)の速さ: 移動の速さの属性に掛ける【仮】。地面で約 0.045 ブロック/tick を的に。 */
    public static final double STROLL_SPEED = 0.78D;
    /** 急ぎ(遠い相手へ寄る)の速さ: 移動の速さの属性に掛ける【仮】。地面で約 0.075。 */
    public static final double RUSH_SPEED = 1.0D;
    /** 突撃の速さ: 移動の速さの属性に掛ける【仮】。地面の速さ ≈ 2.16 × (0.186 × 1.94)² ≈ 0.28。 */
    public static final double CHARGE_SPEED = 1.94D;

    // ------------------------------------------------------------------ 能力の値【仮・Claude 11-15】

    /** 相手がこれ(ブロック)に入って見えていたら突撃を始める。0.28 で 1.5 秒膨らむと 8.4 ブロック走るので、膨らみ切ってから届く距離。 */
    public static final double CHARGE_START_RANGE = 16.0D;   // 11-18 夜 ユーザー「見つける範囲を上げる」: 12 → 16【仮】(追う範囲 followRange も 24 → 32)
    /** 突撃をあきらめる長さ(tick。届かない所の相手に走り続けない)と、その後また突撃するまでの間。 */
    static final int CHARGE_MAX_TICKS = 160, CHARGE_COOLDOWN = 60;
    /** 触れた = 体の箱をこれだけ広げて相手の箱と重なる(ブロック)。 */
    static final double CONTACT_REACH = 0.3D;
    /** これ(ブロック)より近い・ほぼ同じ高さなら、経路を使わず相手へまっすぐ向かう。 */
    static final double CLOSE_IN = 2.5D;
    /** 袋が膨らみ切るまで(tick。アニメ burst_windup の 26 tick で膨らみ切り 30 で止まる)。 */
    public static final int WINDUP_TICKS = 30;
    /** 破裂のダメージ(真ん中。外へ半分まで減る)と届く半径(ブロック)。 */
    public static final float BURST_DAMAGE = 10.0F;   // 11-18 夜 ユーザー「爆発ダメージ 10」: 4 → 10【仮】
    public static final double BURST_RADIUS = 2.5D;
    /** 弾き飛ばし(横・上。真ん中の値。外へ半分まで減る)。 */
    static final double KNOCK_HORIZONTAL = 0.8D, KNOCK_UP = 0.35D;
    /** 連鎖: この半径(ブロック)の仲間が、この幅(tick)遅れて弾ける。 */
    public static final double CHAIN_RADIUS = 0.0D;   // 11-18 夜 ユーザー「連鎖の破裂をなくす」: 3 → 0(0 = 連鎖しない)
    public static final int CHAIN_DELAY_MIN = 2, CHAIN_DELAY_MAX = 4;

    /** 破裂のダメージ・連鎖の半径・突撃を始める距離(actions.json の vesicaria/burst で直せる。無ければ上の定数)。 */
    public static float burstDamage() {
        return (float) com.yayuy.overgrownvegetation.data.DesignData.actions().num(ID, "burst", "damage", BURST_DAMAGE);
    }

    public static double chainRadius() {
        return com.yayuy.overgrownvegetation.data.DesignData.actions().num(ID, "burst", "chainRadius", CHAIN_RADIUS);
    }

    public static double chargeStartRange() {
        return com.yayuy.overgrownvegetation.data.DesignData.actions().num(ID, "burst", "chargeStartRange", CHARGE_START_RANGE);
    }
    /** 死んでから消えるまで(tick。burst は 20 tick でしぼんだ姿に止まる)。 */
    public static final int REMOVE_TICKS = 30;
    /** 1 tick・1 次元あたり、全部の粒で弾ける数。それより多い分は粒を減らし、音を鳴らさない(大きな連鎖の重さ)。 */
    static final int FULL_PER_TICK = 8, SOUNDS_PER_TICK = 6;
    /** 連鎖で弾ける数の上限(面ごと 1 tick)【仮】。 */
    static final int CHAIN_PER_TICK = 6;
    static final double REDUCED_DETAIL = 0.12D;

    private static final EntityDataAccessor<Boolean> DATA_CHARGING = SynchedEntityData.defineId(VesicariaEntity.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation BURST = RawAnimation.begin().thenPlayAndHold("burst");
    private static final RawAnimation WINDUP = RawAnimation.begin().thenPlay("burst_windup").thenLoop("burst_windup_loop");
    private static final RawAnimation WINDUP_CANCEL = RawAnimation.begin().thenPlayAndHold("burst_windup_cancel");
    /** 突撃をやめたとき、膨らんだ袋を元へ戻す補間(tick)【仮】。 */
    static final int CANCEL_BLEND = 9;

    // --- サーバー ---
    private boolean burst;
    private int chainTimer = -1;
    /** 弾けた数(テスト用)。 */
    public static int burstCount;
    private static final Map<ResourceKey<Level>, long[]> BUDGET = new HashMap<>();

    // --- クライアント ---
    /** 走りにしているか(速さのヒステリシス)。 */
    /** 突撃を始めた tick(クライアントの tickCount)。突撃していなければ -1。 */
    private int chargeStartClient = -1;
    /** 袋の層: 0 = 止める・1 = 膨らむ・2 = しぼむ。しぼみ始めた tick と、そのときの光。 */
    private int sacPhase;
    private int cancelStart;
    private float cancelGlow;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public VesicariaEntity(EntityType<? extends VesicariaEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 2);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_CHARGING, false);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(2, new ChargeGoal(this));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false, e -> hunts() && AcumnesEntity.isPrey(this, e)));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false,
                e -> hunts() && !(e instanceof Player) && AcumnesEntity.isPrey(this, e)));
    }

    /** 相手を探すか。名前に notarget を含む個体は探さない(GameTest で、隣のテストの Mob を狙って走り出さないように)。 */
    private boolean hunts() {
        return !DevNames.has(this, "notarget");
    }

    @Override
    protected float turnPerTick() {
        return TURN_PER_TICK;
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    public double rushSpeed() {
        return RUSH_SPEED;
    }

    @Override
    protected int[] pauseTicks() {
        return new int[]{12, 40};   // 一時停止はグレガリア(6〜24)より長い = もたつく【仮】
    }

    @Override
    protected double bodyHalfLength() {
        return 0.7D;   // 前脚の先から後脚の先まで約 1.5 ブロック
    }

    /** 突撃しているか(サーバーが決めてクライアントへ送る)。 */
    public boolean isCharging() {
        return this.entityData.get(DATA_CHARGING);
    }

    void setCharging(boolean on) {
        if (isCharging() != on) this.entityData.set(DATA_CHARGING, on);
    }

    /** テスト用: 今動いている行動の名前と経路。 */
    public String debugState() {
        StringBuilder sb = new StringBuilder();
        this.goalSelector.getAvailableGoals().forEach(g -> { if (g.isRunning()) sb.append(g.getGoal().getClass().getSimpleName()).append(' '); });
        sb.append("経路 ").append(this.getNavigation().getPath() == null ? "なし" : this.getNavigation().getPath().getNodeCount() + " 届く " + this.getNavigation().getPath().canReach());
        sb.append(" AI なし ").append(this.isNoAi());
        return sb.toString();
    }

    /** もう弾けたか(サーバー)。 */
    public boolean hasBurst() {
        return this.burst;
    }

    // ------------------------------------------------------------------ 破裂

    /** どの傷でも(矢・剣・火・落下)その場で弾ける: 傷が通ったら、生き残っても死なせる。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide() && this.isAlive() && !this.burst) {
            this.setHealth(0.0F);
            this.die(source);
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!this.burst && this.level() instanceof ServerLevel level) explode(level);
    }

    /** 触れた・連鎖で弾けさせる(サーバー)。死なせて {@link #die} で破裂する。 */
    public void burstNow() {
        if (this.burst || !this.isAlive() || this.level().isClientSide()) return;
        this.setHealth(0.0F);
        this.die(this.damageSources().explosion(this, this));
    }

    /** 破裂の効き目(サーバー): 胞子の雲・汚染・音、まわりへのダメージと弾き飛ばし、仲間の連鎖。 */
    private void explode(ServerLevel level) {
        this.burst = true;
        this.chainTimer = -1;
        setCharging(false);
        this.getNavigation().stop();
        this.setDeltaMovement(0.0D, Math.min(0.0D, this.getDeltaMovement().y), 0.0D);
        burstCount++;

        long[] used = budget(level);
        boolean full = used[0] < FULL_PER_TICK;
        boolean sound = used[1] < SOUNDS_PER_TICK;
        used[0]++;
        if (sound) used[1]++;
        Vec3 sac = sacCenter(1.0F);
        // 雲・汚染の球・升の浮遊・かけら・音は植物側の死の破裂と同じ(足元は袋の真下)
        G1DeathBurst.burst(level, new Vec3(sac.x, this.getY(), sac.z), this.getBbWidth(), this.getBbHeight(), full ? 1.0D : REDUCED_DETAIL, sound);

        // まわりへのダメージと弾き飛ばし(仲間・植物側には効かない。仲間は連鎖へ)
        AABB box = new AABB(sac, sac).inflate(Math.max(BURST_RADIUS, chainRadius()));
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e != this && e.isAlive())) {
            if (e instanceof VesicariaEntity v) {
                if (!v.burst && v.chainTimer < 0 && chainRadius() > 0.0D && v.distanceToSqr(sac) <= chainRadius() * chainRadius()) {
                    v.chainTimer = CHAIN_DELAY_MIN + this.random.nextInt(CHAIN_DELAY_MAX - CHAIN_DELAY_MIN + 1);
                }
                continue;
            }
            if (!hitsByBurst(e)) continue;
            Vec3 c = e.getBoundingBox().getCenter();
            double d = Math.sqrt(e.getBoundingBox().distanceToSqr(sac));
            if (d > BURST_RADIUS) continue;
            double share = 1.0D - 0.5D * (d / BURST_RADIUS);
            e.hurt(this.damageSources().explosion(this, this), (float) (burstDamage() * share));
            double resist = Mth.clamp(1.0D - e.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.0D, 1.0D);
            double dx = c.x - sac.x, dz = c.z - sac.z;
            double h = Math.sqrt(dx * dx + dz * dz);
            if (h < 1.0E-4D) {
                float a = this.getYRot() * Mth.DEG_TO_RAD;
                dx = -Mth.sin(a);
                dz = Mth.cos(a);
                h = 1.0D;
            }
            double k = KNOCK_HORIZONTAL * share * resist;
            e.push(dx / h * k, KNOCK_UP * share * resist, dz / h * k);
            e.hurtMarked = true;   // プレイヤーへ速度を送る
        }
    }

    /** 破裂のダメージを受ける相手か: 植物側(仲間を含む)・防具立てには効かない。 */
    static boolean hitsByBurst(LivingEntity e) {
        return !(e instanceof ArmorStand) && PlantFoes.isOtherSide(e);   // 陣営は共通(①13)
    }

    private static long[] budget(ServerLevel level) {
        long now = level.getGameTime();
        long[] b = BUDGET.computeIfAbsent(level.dimension(), k -> new long[4]);
        if (b[2] != now) {
            b[0] = 0;
            b[1] = 0;
            b[2] = now;
            b[3] = 0;   // 連鎖で弾けた数
        }
        return b;
    }

    /** 袋の真ん中(体の中心から後ろへ 約 0.5、高さ 約 0.55。膨らみ切ると 0.7)。アニメのセッションの値。 */
    public Vec3 sacCenter(float swell) {
        float a = this.yBodyRot * Mth.DEG_TO_RAD;
        double bx = Mth.sin(a) * 0.5D, bz = -Mth.cos(a) * 0.5D;   // 後ろ = 前 (−sin, cos) の逆
        return new Vec3(this.getX() + bx, this.getY() + Mth.lerp(swell, 0.55D, 0.7D), this.getZ() + bz);
    }

    /** 死んだ後: burst のしぼんだ姿のまま {@link #REMOVE_TICKS} tick 置いて消す(バニラの 20 tick 目の白い煙は出さない)。 */
    @Override
    protected void tickDeath() {
        ++this.deathTime;
        if (this.deathTime >= REMOVE_TICKS && !this.level().isClientSide() && !this.isRemoved()) {
            this.remove(RemovalReason.KILLED);
        }
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            tickClientSac();
            return;
        }
        if (this.chainTimer > 0 && --this.chainTimer == 0) {
            // 連鎖で弾けるのは面ごとに 1 tick CHAIN_PER_TICK 体まで(残りは次の tick へ。1 回の破裂ごとに箱の検索と雲・汚染があるので、
            // 密な群れの連鎖が 1 tick に集まらないように。11-16 見直し 第 3 便)
            long[] b = budget((ServerLevel) this.level());
            if (b[3] >= CHAIN_PER_TICK) {
                this.chainTimer = 1;
            } else {
                b[3]++;
                burstNow();
            }
        }
        if (DEV_HOOKS && this.hasCustomName()) devHooks(this.getCustomName().getString());
    }

    /**
     * テストクライアント用: 名前 nostroll_run… は 10〜20 ブロック先へ急ぎで歩き続け、nostroll_charge… は突撃の速さで同じことをする。
     * …trace… は 10 tick ごとに速さを書く。
     */
    private void devHooks(String name) {
        boolean charge = name.startsWith("nostroll_charge");
        if ((charge || name.startsWith("nostroll_run")) && this.getNavigation().isDone()) {
            Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this, 20, 4);
            if (p != null && p.distanceToSqr(this.position()) > 10.0D * 10.0D)
                this.getNavigation().moveTo(p.x, p.y, p.z, charge ? CHARGE_SPEED : this.rushSpeed());
        }
        if (name.contains("trace") && this.tickCount % 10 == 0) {
            double dx = this.getX() - this.xo, dz = this.getZ() - this.zo;
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "VESI_GAIT %s pos %.1f %.1f %.1f 速さ %.3f 経路 %s 突撃 %s", name, this.getX(), this.getY(), this.getZ(),
                    Math.sqrt(dx * dx + dz * dz), this.getNavigation().isDone() ? "なし" : "あり", isCharging()));
        }
    }

    // ------------------------------------------------------------------ 突撃

    /**
     * 相手へ寄って突撃する。遠いうちは急ぎ(walk)、{@link #CHARGE_START_RANGE} に入って見えたら突撃(run・袋が膨らむ)。
     * 触れたら弾ける。届かないまま {@link #CHARGE_MAX_TICKS} 走ったら突撃をやめ、{@link #CHARGE_COOLDOWN} 歩いて寄る。
     */
    static final class ChargeGoal extends Goal {
        private final VesicariaEntity mob;
        private int chargeTicks;
        private int cooldown;
        private int repath;

        ChargeGoal(VesicariaEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = this.mob.getTarget();
            return t != null && AcumnesEntity.isPrey(this.mob, t);
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity t = this.mob.getTarget();
            if (t == null || !AcumnesEntity.isPrey(this.mob, t) || t.level() != this.mob.level()) return false;
            double range = this.mob.getAttributeValue(Attributes.FOLLOW_RANGE) * 1.25D;
            return this.mob.distanceToSqr(t) <= range * range;
        }

        @Override
        public void start() {
            this.chargeTicks = 0;
            this.repath = 0;
        }

        @Override
        public void stop() {
            this.mob.setCharging(false);
            this.mob.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity t = this.mob.getTarget();
            if (t == null) return;
            if (this.mob.getBoundingBox().inflate(CONTACT_REACH).intersects(t.getBoundingBox())) {
                this.mob.burstNow();
                return;
            }
            if (this.cooldown > 0) this.cooldown--;
            boolean charging = this.mob.isCharging();
            if (!charging && this.cooldown == 0 && this.mob.distanceToSqr(t) <= chargeStartRange() * chargeStartRange()
                    && this.mob.getSensing().hasLineOfSight(t)) {
                this.mob.setCharging(true);
                charging = true;
                this.chargeTicks = 0;
                this.repath = 0;
            }
            if (charging && ++this.chargeTicks > CHARGE_MAX_TICKS) {
                this.mob.setCharging(false);
                charging = false;
                this.cooldown = CHARGE_COOLDOWN;
            }
            double speed = charging ? CHARGE_SPEED : RUSH_SPEED;
            if (this.mob.distanceToSqr(t) <= CLOSE_IN * CLOSE_IN && Math.abs(t.getY() - this.mob.getY()) < 1.0D) {
                // 近い: 経路は「1 ブロック以内で着いた」で止まり、触れずに手前で立ち止まる(11-15 テストクライアントで見た)。
                // 最後は相手そのものへまっすぐ向かう
                this.mob.getNavigation().stop();
                this.mob.getMoveControl().setWantedPosition(t.getX(), t.getY(), t.getZ(), speed);
                this.repath = 0;
                return;
            }
            if (--this.repath <= 0) {
                this.repath = charging ? 4 : 10;
                this.mob.getNavigation().moveTo(t, speed);
            }
        }
    }

    // ------------------------------------------------------------------ クライアント: 光・音・粒

    private void tickClientSac() {
        boolean charging = isCharging() && this.isAlive();
        if (charging && this.chargeStartClient < 0) this.chargeStartClient = this.tickCount;
        if (!charging && this.chargeStartClient >= 0) {
            this.cancelGlow = this.isAlive() ? chargeGlow(this.tickCount - this.chargeStartClient) : 0.0F;
            this.cancelStart = this.tickCount;
            this.chargeStartClient = -1;
        }
        if (!this.playerWithin(16.0D)) return;
        if (charging) {
            // 袋を温める音: 始めに低いしゅうっ、押しの山(約 8・15・21 tick)で一段ずつ高く【仮: バニラの火の消える音を低くして】
            int e = this.tickCount - this.chargeStartClient;
            float pitch = e == 0 ? 0.5F : e == 8 ? 0.6F : e == 15 ? 0.7F : e == 21 ? 0.8F : -1.0F;
            if (pitch > 0.0F) {
                this.level().playLocalSound(this.getX(), this.getY() + 0.5D, this.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE,
                        e == 0 ? 0.35F : 0.25F, pitch, false);
            }
        } else if (this.isDeadOrDying() && this.deathTime >= 3 && this.deathTime <= 10 && (this.deathTime & 1) == 1) {
            // 残りの胞子がしぼんだ袋から漏れる(少し)
            Vec3 s = sacCenter(0.0F);
            this.level().addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_JET.get(), s.x, s.y - 0.2D, s.z,
                    (this.random.nextDouble() - 0.5D) * 0.03D, 0.02D, (this.random.nextDouble() - 0.5D) * 0.03D);
        }
    }


    /** 突撃を始めてから e tick の光(アニメの表: 0〜6 は無し、6〜26 で 0 → 1、その後は 10 tick 周期で 0.9〜1)。 */
    static float chargeGlow(float e) {
        if (e <= 6.0F) return 0.0F;
        if (e < 26.0F) {
            float x = (e - 6.0F) / 20.0F;
            return x * x * (3.0F - 2.0F * x);
        }
        if (e < WINDUP_TICKS) return 1.0F;
        return 0.95F + 0.05F * Mth.cos((e - WINDUP_TICKS) * Mth.TWO_PI / 10.0F);
    }

    /** 描画: 袋の脈の光(0〜1)。突撃中は表どおり、やめたら {@link #CANCEL_BLEND} tick で消え、弾けた瞬間にいちばん明るく 2 tick で消える。 */
    public float sacGlow(float pt) {
        if (this.isDeadOrDying()) return Math.max(0.0F, 1.0F - (this.deathTime + pt) / 2.0F);
        if (this.chargeStartClient >= 0) return chargeGlow(this.tickCount - this.chargeStartClient + pt);
        float f = (this.tickCount - this.cancelStart + pt) / CANCEL_BLEND;
        return f >= 1.0F ? 0.0F : this.cancelGlow * (1.0F - f);
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new GaitAnimationController<>(this, "main", 4, this::mainState));
        controllers.add(new AnimationController<>(this, "sac", 1, this::sacState));   // main の後 = 袋の骨は後が勝つ
    }

    private PlayState mainState(AnimationState<VesicariaEntity> state) {
        GaitAnimationController<VesicariaEntity> c = (GaitAnimationController<VesicariaEntity>) state.getController();
        if (this.isDeadOrDying()) {
            // 弾けた: burst を頭から(補間なし)。足は地面のまま、しぼんだ姿で止まる
            this.gait.stop();
            c.setAnimationSpeed(1.0D);
            c.switchGait(BURST, "burst", "-");
            c.transitionLength(0);
            return state.setAndContinue(BURST);
        }
        double v = clientMoveSpeed(state.getPartialTick());
        RawAnimation a = this.gait.pick(c, v, this.getScale(), true, isCharging());   // 突撃の間は走りに固定
        if (a != com.yayuy.overgrownvegetation.entity.crawler.WalkRunGait.IDLE) devGait(c, v);
        return state.setAndContinue(a);
    }

    /** 歩き ⇄ 走りの選び方(共通の部品。数値はこの種のもの)。 */
    private final com.yayuy.overgrownvegetation.entity.crawler.WalkRunGait gait = new com.yayuy.overgrownvegetation.entity.crawler.WalkRunGait(
            WALK_MIN_SPEED, RUN_ON, RUN_OFF, WALK_REF, RUN_REF, WALK_SPEED_MIN, WALK_SPEED_MAX, RUN_SPEED_MIN, RUN_SPEED_MAX);

    /**
     * 袋の層({@code sac}・{@code sc0〜7}・{@code fl*} だけにキーがある): 突撃の間は膨らむ → 張って震える。
     * 途中でやめたら {@code burst_windup_cancel}(元の姿)へ {@link #CANCEL_BLEND} tick かけて戻してから止める(いきなり止めると 1 コマで縮む)。
     * 弾けたら同じ tick に止める({@code burst} が袋の骨も全部持つ)。再生倍率は 1 のまま。
     */
    private PlayState sacState(AnimationState<VesicariaEntity> state) {
        AnimationController<VesicariaEntity> c = state.getController();
        if (this.isDeadOrDying()) {
            this.sacPhase = 0;
            return PlayState.STOP;
        }
        if (isCharging()) {
            if (this.sacPhase != 1) {
                this.sacPhase = 1;
                c.transitionLength(1);
                c.forceAnimationReset();
            }
            return state.setAndContinue(WINDUP);
        }
        if (this.sacPhase == 1) {
            this.sacPhase = 2;
            this.cancelStart = this.tickCount;
            c.transitionLength(CANCEL_BLEND);
            c.forceAnimationReset();
        }
        if (this.sacPhase == 2) {
            if (this.tickCount - this.cancelStart < CANCEL_BLEND + 2) return state.setAndContinue(WINDUP_CANCEL);
            this.sacPhase = 0;
        }
        return PlayState.STOP;
    }

    /** テストクライアント用: 名前に gait を含む個体の、フレームごとの歩き方と 1 巡の中の位置。 */
    private void devGait(GaitAnimationController<VesicariaEntity> c, double v) {
        if (DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("gait")) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "VESI_ANIM t %d %s 位置 %.3f 速さ %.3f 突撃 %s", this.tickCount, c.currentName(), c.fraction(), v, isCharging()));
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
