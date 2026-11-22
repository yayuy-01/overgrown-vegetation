package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.GaitAnimationController;
import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * クリペイムネス {@code clipeimnes}(G3・攻撃特化。11-14 に本名。仮名ハガネカワ、仮の id {@code g5_bulwark})。
 * 腕の盾で矢・剣を弾きながら詰め、腕の鉤で抱え込む、小回りの利く盾持ちの前衛(名簿「Mob の名簿と能力の刷新(11-04)」の G3 の表・
 * 注文書 g5_bulwark。能力は Vault「G4〜G6 の能力の決定(11-14)」の第 1 陣)。
 *
 * <h2>段 1(2026-11-15): 歩くところまで</h2>
 * 移動は「② 大型の地上を走る型」({@link LongBodyRunner})をそのまま使い回す。攻撃・能力はまだ無い。行動はうろつき(と、テストクライアントの navto)だけ。
 * 自然には湧かない(スポーンエッグ・コマンドだけ)。
 *
 * <h3>体の作り(ヴェナムネスとの違い)</h3>
 * 歩く脚は<b>4 本</b>(胸の中脚 1 対 + 腹の後脚 1 対)。どれも 3 骨(付け根 leg → すね shin → ふ節 foot)で横へ張り出す虫の脚。
 * 前の 1 対は盾の腕で、地面に着かない(足の置き場に入れない)。脚の IK は種に依らない {@code client/runner/SprawlLegRig}(11-15 に足した)。
 * 踏み替えは対角の組み(左中 + 右後 / 右中 + 左後)。
 *
 * <h3>クリペイムネスらしさ = 小回り【全部仮・Claude 11-15】</h3>
 * 回る速さ {@link #TURN_PER_TICK} 度/tick を早く出し({@link #TURN_SMOOTHING})、向きが {@link #MOVE_OFF} 度より大きくずれたら進まずに<b>その場で向き直る</b>
 * (ヴェナムネス 85・ファルキムネス 70。小さいほど弧を描かず、狭い所で向きを変えられる)。回る速さの上限は、後脚の足先(体の中心から約 2.9 ブロック)が
 * その場で回る間に踏み替えで付いていける速さから決めた(1 tick に 6 度で足先は 0.3 ブロック/tick 動く)。
 * 盾の腕・頭・尾が幹にめり込むのは許す・回る速さはこのままでよい(11-15 ユーザー)。
 *
 * <h2>段 2 のアニメの組み込み(2026-11-15)</h2>
 * アニメ(正本 {@code clipeimnes.bbmodel})は段 2 で 19 本になった(Fable のセッション。Vault「クリペイムネス」6 節)。ここで流すのは walk・run・idle だけ:
 * 遅い間は walk、速い間は run({@link #RUN_ON}・{@link #RUN_OFF})。技のアニメ(盾打ち・抱え込み・溜めの解放・盾で詰める {@code guard_*})と光は、
 * 能力を入れる段で鳴らす(段の切り替えは補間 0、{@code guard_*} は main の後に登録する 2 つ目のコントローラ)。
 */
public class ClipeimnesEntity extends LongBodyRunner implements GeoEntity, ParasitizedMob, CaptorMount {

    public static final String ID = "clipeimnes";

    /** 描画倍率(geo に焼き込まず描画で掛ける。テクスチャは 1 単位 2 テクセルなので、画素はブロックの 1 画素に揃う)。 */
    public static final float RENDER_SCALE = 2.0F;
    /** モデルの 1 単位(px)をブロックへ。 */
    static final double PX = RENDER_SCALE / 16.0D;

    // --- 小回り【仮】 ---
    /** 回る速さ(度/tick)・その場で向き直りだすずれ(度)・角速度を寄せる割合【仮】。 */
    public static final float TURN_PER_TICK = 6.0F, MOVE_OFF = 45.0F, TURN_SMOOTHING = 0.5F;
    /** うろつきの速さ(移動の速さに掛ける)【仮】。 */
    static final double STROLL_SPEED = 0.7D;
    /** 経路の長い体の重み【仮】: 幹の近さはファルキムネス (2.0, 1.0, 0.6, 1.5) と同じに、狭い所の急な曲がりは嫌わない(その場で向き直れる)。 */
    public static final OvergrownNodeEvaluator.LongBody LONG_BODY = new OvergrownNodeEvaluator.LongBody(2.0F, 1.0F, 0.2F, 0.6F);

    // --- 体の作り(モデル prac2_g5_bulwark_r7 = clipeimnes.geo.json の、休めの形の足先 × 描画倍率) ---
    /**
     * 足の置き場(ふ節の箱の一番下の角。ゲームの中で GeckoLib が箱の回転を掛けた所を {@code SprawlLegRig} が測った値 = geo の座標 px で 中左 (17.32, −6.51)・中右 (−16.17, −5.60)・後左 (14.37, 18.33)・後右 (−15.88, 17.36)。
     * geo の +X が体の左、−Z が前)。練習モデルの左右が少し違うので、そのままの値を使う。並びは 中左・中右・後左・後右。
     */
    static final double[] LEG_HOME_LEFT = {17.32D * PX, -16.17D * PX, 14.37D * PX, -15.88D * PX};
    static final double[] LEG_HOME_FWD = {6.51D * PX, 5.60D * PX, -18.33D * PX, -17.36D * PX};
    /** 対角の組み: 中左 + 後右 = 0、中右 + 後左 = 1。 */
    static final int[] LEG_GROUP = {0, 1, 1, 0};
    /**
     * 曲げの付け根: 前は首 neck(z −10.8)・頭 head(−14.4)の 2 つだけ(残り 2 つは骨に当てない。胸は中脚と盾の腕の親なので曲げない)、
     * 後ろは尾 tail1〜tail4(z 14.4・22.2・29.4・36)。腹は後脚の親なので曲げない。尾の先 tail5・tail6 は tail4 と一緒に動く。
     */
    static final double[] BEND_FRONT = {10.8D * PX, 14.4D * PX, 17.0D * PX, 19.0D * PX};
    static final double[] BEND_BACK = {14.4D * PX, 22.2D * PX, 29.4D * PX, 36.0D * PX};

    // --- 足の運び【仮・Claude 11-15】 ---
    /** 踏み替えを始めるずれ(ブロック): 止まりかけ {@link #STEP_MIN} + 速さ × {@link #STEP_PER}、{@link #STEP_MAX} まで。回っている間は {@link #STEP_TURN}。 */
    static final double STEP_MIN = 0.45D, STEP_PER = 4.0D, STEP_MAX = 0.95D, STEP_TURN = 0.55D;   // 11-15: 上限 1.3 では後脚が届く長さを 2 px 越えてから離れた(踏み替えの初めに足先が 0.3 ブロック跳ぶ)
    /** 踏み替えの長さ(tick): 歩き / 回っている間。 */
    static final int SWING_WALK = 7, SWING_TURN = 5;
    /** 先回りの上限(tick)と、回っている間の上限。 */
    static final double LEAD_MAX = 7.0D, LEAD_TURNING = 3.0D;

    // --- アニメの速さ(段 2 のアニメ、11-15 Fable。Vault「クリペイムネス」6 節) ---
    /**
     * walk(24 tick で 1 巡・着く 15 tick・1 巡 1.27 ブロック)と run(16 tick・着く 9 tick・1 巡 1.98 ブロック)を速さ 1.0 で流したときの地上速度(ブロック/tick)。
     * どちらもゲームの IK と同じ変数で焼いてあり、4 本とも着いている間の送りがこの速さに揃っている。遠く(足の置き場が効いていない)の個体は、
     * 実際の速さ ÷ これ で流せば足が滑らない。近くでは脚は IK が置き、アニメは胴・盾・尾の揺れだけが効く。
     * どちらも<b>1 巡の 0 で中左 + 後右が着き、0.5 で中右 + 後左が着く</b>(胴の一番低い所はその 2〜3 tick 後)。
     */
    public static final double WALK_REF = 0.053D, RUN_REF = 0.124D;
    static final double LOOP_SPEED_MIN = 0.3D, LOOP_SPEED_MAX = 2.5D;
    /** run の再生の速さの範囲(0.37〜1.5 倍 = 0.046〜0.186 ブロック/tick)。 */
    static final double RUN_SPEED_MIN = 0.37D, RUN_SPEED_MAX = 1.5D;
    /** walk → run へ移る速さ / run → walk へ戻る速さ(ブロック/tick。幅を持たせて境で切り替えが続かないように)【仮】。うろつき 0.061・急ぎ 0.124。 */
    public static final double RUN_ON = 0.10D, RUN_OFF = 0.08D;
    /**
     * 近く(足の置き場が効いている間)の胴の揺れを踏み替えに合わせる: 中脚が着いた tick に、アニメの 1 巡の中の位置と着地の位置(中左 0・中右 0.5)の
     * ずれを測り、その後の再生を速め・遅めてずれを返す。1 tick に返す割合と、速さを変える幅の上限(基準の速さに対する割合)【仮】。
     */
    static final double SYNC_RATE = 0.25D, SYNC_MAX = 0.6D;
    /** これ(ブロック/tick)以上前へ進んだ tick は歩き。 */
    static final double WALK_MIN_SPEED = 0.01D;
    /** その場で回っていても歩きを流す回る速さ(度/tick)。 */
    static final float TURN_ANIM_MIN = 1.0F;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ClipeimnesEntity(EntityType<? extends ClipeimnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 35);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    // ------------------------------------------------------------------ 型の値

    @Override
    protected double[] legHomeLeft() {
        return LEG_HOME_LEFT;
    }

    @Override
    protected double[] legHomeFwd() {
        return LEG_HOME_FWD;
    }

    @Override
    protected int[] legGroups() {
        return LEG_GROUP;
    }

    @Override
    protected double[] bendFront() {
        return BEND_FRONT;
    }

    @Override
    protected double[] bendBack() {
        return BEND_BACK;
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
    protected float turnSmoothing() {
        return TURN_SMOOTHING;
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
        return 6.2D * this.getScale();   // 尾の先が原点の 6.1 ブロック後ろ
    }

    // ------------------------------------------------------------------ 技(11-15 段 3。進めるのは ClipeimnesHuntGoal)

    /** 技の番号(描画へ送る)。盾打ちの前触れ → 実行 → 隙、抱え込みの前触れ → 実行 → 締め、溜めの解放は「番号 + 1」が次の段。 */
    public static final int ACT_NONE = 0, ACT_BASH_R_WINDUP = 1, ACT_BASH_R = 2, ACT_BASH_R_RECOVER = 3,
            ACT_BASH_L_WINDUP = 4, ACT_BASH_L = 5, ACT_BASH_L_RECOVER = 6,
            ACT_GRAB_WINDUP = 7, ACT_GRAB = 8, ACT_HOLD = 9, ACT_HOLD_RECOVER = 10, ACT_GRAB_RECOVER = 11,
            ACT_RELEASE_WINDUP = 12, ACT_RELEASE = 13, ACT_RELEASE_RECOVER = 14;
    /** 盾の構え: 無し / 構える(guard_windup → guard)/ 受けた(guard_hit → guard)/ 下ろす(guard_recover)。 */
    public static final int GUARD_NONE = 0, GUARD_UP = 1, GUARD_HIT = 2, GUARD_DOWN = 3;
    /** 構えのアニメの長さ(tick): 構え始め・受け・下ろし。構え始めの間は弾かない(板が顔の前に来る前)。 */
    static final int GUARD_WINDUP_TICKS = 8, GUARD_HIT_TICKS = 8, GUARD_DOWN_TICKS = 8;
    /** 弾く向き(体の正面から ± 度)・溜めの段の上限・満ちない溜めが 1 段減るまでの tick【仮】。 */
    public static final float GUARD_DEG = 60.0F;
    public static final int CHARGE_MAX = 5;
    static final int CHARGE_DECAY = 600;
    /** 抱えられる相手の大きさ(幅・高さの上限、ブロック)【仮】。 */
    static final float HOLD_MAX_WIDTH = 1.2F, HOLD_MAX_HEIGHT = 2.2F;

    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_ACT =
            net.minecraft.network.syncher.SynchedEntityData.defineId(ClipeimnesEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
    /** 盾の構え(下 4 ビット)・溜めの段(次の 4 ビット)・構えが始まった回数(上)。 */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_GUARD =
            net.minecraft.network.syncher.SynchedEntityData.defineId(ClipeimnesEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    @org.jetbrains.annotations.Nullable
    private ClipeimnesHuntGoal hunt;
    private int actSeq, guardSeq;
    /** サーバー: 盾の構え。 */
    private boolean guardWanted;
    private int guardState = GUARD_NONE, guardTicks, charge, chargeIdle;
    /** 自分で抱えた相手を放す途中(降車を止めない)。 */
    private boolean releasing;

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACT, 0);
        builder.define(DATA_GUARD, 0);
    }

    /**
     * 段 1 の Goal(浮く・うろつく・見回す)に、襲う Goal({@link ClipeimnesHuntGoal})と標的を足す。
     * 標的はサルティムネス・ヴェナムネスと同じ: 殴った相手・プレイヤー(サバイバル・アドベンチャー)・植物側でない生き物
     * (G1〜G8・環境生物・感染体・植物側へ変わった生き物・防具立ては狙わない)。
     */
    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.hunt = new ClipeimnesHuntGoal(this);
        this.goalSelector.addGoal(3, this.hunt);
        this.targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new SeekGoal<>(this, net.minecraft.world.entity.player.Player.class, e -> true));
        this.targetSelector.addGoal(3, new SeekGoal<>(this, net.minecraft.world.entity.LivingEntity.class,
                e -> !(e instanceof net.minecraft.world.entity.player.Player) && PlantFoes.isFoe(this, e)));   // 陣営と除外は共通(①13)
    }

    /** 自分で標的を探す Goal(バニラの最寄り)。名前が {@code nostroll} で始まる個体は自分では探さない(確かめ・GameTest 用。標的はテストが置く)。 */
    static final class SeekGoal<T extends net.minecraft.world.entity.LivingEntity> extends net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<T> {
        SeekGoal(ClipeimnesEntity mob, Class<T> type, java.util.function.Predicate<net.minecraft.world.entity.LivingEntity> filter) {
            super(mob, type, 10, true, false, filter);
        }

        @Override
        public boolean canUse() {
            if (com.yayuy.overgrownvegetation.entity.DevNames.noStroll(this.mob)) return false;
            return super.canUse();
        }
    }

    /** 技の最中(全身の技。脚は手付け、体の曲げは止める)。クライアントでも同期した番号で答える。盾の構えは技に数えない(歩きに重ねる)。 */
    @Override
    public boolean isActing() {
        return act() != ACT_NONE;
    }

    /** 技を描画へ送る(番号と、同じ技の続けての始まりを見分ける回数)。 */
    void setAct(int act) {
        this.actSeq = (this.actSeq + 1) & 0xFFFF;
        this.entityData.set(DATA_ACT, act | (this.actSeq << 8));
        if (DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("acts")) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT, "CLIP_ACT t %d 技 %d 構え %d 溜め %d 抱え %s",
                    this.tickCount, act, this.guardState, this.charge, held() != null));
        }
    }

    /** 今の技の番号(サーバー・クライアントどちらでも)。 */
    public int act() {
        return this.entityData.get(DATA_ACT) & 0xFF;
    }

    // --- 盾の構えと溜め(サーバー)

    /** 盾を構える / 下ろす(下ろすときは guard_recover を流す)。 */
    void setGuard(boolean on) {
        this.guardWanted = on;
    }

    /** 全身の技の前: 盾の層をすぐ外す(guard_recover を流すと技の前触れの腕と重なる)。 */
    void dropGuardNow() {
        this.guardWanted = false;
        setGuardState(GUARD_NONE);
    }

    private void setGuardState(int s) {
        this.guardState = s;
        this.guardTicks = 0;
        this.guardSeq = (this.guardSeq + 1) & 0xFFFF;
        syncGuard();
    }

    private void syncGuard() {
        this.entityData.set(DATA_GUARD, this.guardState | (this.charge << 4) | (this.guardSeq << 8));
    }

    /** 弾ける構えか(構え始めの {@link #GUARD_WINDUP_TICKS} tick の後・受けた最中)。 */
    public boolean guardReady() {
        return this.guardState == GUARD_UP && this.guardTicks >= GUARD_WINDUP_TICKS || this.guardState == GUARD_HIT;
    }

    /** 溜めの段(0〜{@link #CHARGE_MAX})。サーバー・クライアントどちらでも。 */
    public int charge() {
        return this.level().isClientSide() ? (this.entityData.get(DATA_GUARD) >> 4) & 0xF : this.charge;
    }

    void resetCharge() {
        this.charge = 0;
        this.chargeIdle = 0;
        syncGuard();
    }

    private void tickGuard() {
        this.guardTicks++;
        if (this.guardWanted && (this.guardState == GUARD_NONE || this.guardState == GUARD_DOWN) && act() == ACT_NONE) {
            setGuardState(GUARD_UP);
        } else if (!this.guardWanted && (this.guardState == GUARD_UP || this.guardState == GUARD_HIT)) {
            setGuardState(GUARD_DOWN);
        } else if (this.guardState == GUARD_HIT && this.guardTicks >= GUARD_HIT_TICKS) {
            this.guardState = GUARD_UP;   // 受けの後は構えのまま(アニメは guard_hit → guard と続く。数え直さない)
            this.guardTicks = GUARD_WINDUP_TICKS;
            syncGuard();
        } else if (this.guardState == GUARD_DOWN && this.guardTicks >= GUARD_DOWN_TICKS) {
            setGuardState(GUARD_NONE);
        }
        if (this.charge > 0 && this.charge < CHARGE_MAX + 1 && ++this.chargeIdle >= CHARGE_DECAY) {
            this.charge--;
            this.chargeIdle = 0;
            syncGuard();
        }
    }

    /**
     * 盾で弾くか: 構えていて、傷の出どころ(矢の当たった所・殴った相手)が体の正面 ±{@link #GUARD_DEG} 度にあり、盾で受けられる種類
     * (爆発・炎・魔法・落下・盾を貫く傷でない。決定 11-15)。
     */
    boolean blocksFromFront(net.minecraft.world.damagesource.DamageSource source) {
        if (!guardReady() || act() != ACT_NONE) return false;
        if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_SHIELD) || source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)
                || source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) || source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)
                || source.is(net.minecraft.world.damagesource.DamageTypes.MAGIC) || source.is(net.minecraft.world.damagesource.DamageTypes.INDIRECT_MAGIC)) {
            return false;
        }
        net.minecraft.world.phys.Vec3 from = source.getSourcePosition();
        if (from == null) return false;
        float yaw = (float) (Mth.atan2(from.z - this.getZ(), from.x - this.getX()) * Mth.RAD_TO_DEG) - 90.0F;
        return Math.abs(Mth.wrapDegrees(yaw - this.getYRot())) <= GUARD_DEG;
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (!this.level().isClientSide() && blocksFromFront(source)) {
            // 盾で弾いた: 傷は通らない(矢はバニラの決まりで跳ね返る)。溜めが 1 段増え、受けの動き
            this.playSound(net.minecraft.sounds.SoundEvents.SHIELD_BLOCK, 1.0F, 0.7F + this.getRandom().nextFloat() * 0.2F);
            if (this.charge < CHARGE_MAX) this.charge++;
            this.chargeIdle = 0;
            setGuardState(GUARD_HIT);
            if (DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("acts")) {
                com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT, "CLIP_BLOCK t %d 弾いた %s 溜め %d", this.tickCount, source.getMsgId(), this.charge));
            }
            if (source.getEntity() instanceof net.minecraft.world.entity.LivingEntity l && l != this.getTarget() && this.getTarget() == null) this.setTarget(l);
            return false;
        }
        boolean r = super.hurt(source, amount);
        if (r && this.hunt != null && act() == ACT_HOLD && source.getEntity() != held()) this.hunt.holdDamage += amount;
        return r;
    }

    // --- 当たり(サーバー)

    /** 盾打ち: 体の前 {@code fMin}〜{@code fMax}・左右 {@code lat} にいる標的とプレイヤーを打ち、前へ弾き飛ばす。傷は攻撃の属性。 */
    void bash(double fMin, double fMax, double lat, double push, double lift) {
        float dmg = (float) this.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        hitInFront(fMin, fMax, lat, 0.0F, dmg, push, lift);
        this.playSound(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.0F, 0.6F);
    }

    /** 溜めの解放の衝撃波: 体の前 {@code reach} ブロック・± {@code deg} 度の扇。 */
    void shockwave(double reach, float deg, float damage, double push, double lift) {
        hitInFront(0.0D, reach, 0.0D, deg, damage, push, lift);
        this.playSound(net.minecraft.sounds.SoundEvents.ANVIL_LAND, 1.2F, 0.5F);
        if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            float r = this.getYRot() * Mth.DEG_TO_RAD;
            double fx = -Mth.sin(r), fz = Mth.cos(r);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK, this.getX() + fx * 2.7D, this.getY() + 1.6D, this.getZ() + fz * 2.7D, 3, 0.6D, 0.3D, 0.6D, 0.0D);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, this.getX() + fx * 4.0D, this.getY() + 0.3D, this.getZ() + fz * 4.0D, 24, 1.5D, 0.1D, 1.5D, 0.05D);
        }
    }

    /** 前の箱(lat > 0)か扇(deg > 0)の中の標的とプレイヤーに傷と弾きを与える。巻き込むのは標的とプレイヤーだけ(植物側は除く)。 */
    private void hitInFront(double fMin, double fMax, double lat, float deg, float damage, double push, double lift) {
        float r = this.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(r), fz = Mth.cos(r), rx = -Mth.cos(r), rz = -Mth.sin(r);
        double sc = this.getScale();
        net.minecraft.world.phys.AABB box = this.getBoundingBox().inflate(fMax * sc + 1.0D, 2.5D, fMax * sc + 1.0D);
        for (net.minecraft.world.entity.LivingEntity e : this.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                x -> x != this && x.isAlive() && !(x instanceof ParasitizedMob))) {
            double dx = e.getX() - this.getX(), dz = e.getZ() - this.getZ();
            double f = dx * fx + dz * fz, l = dx * rx + dz * rz;
            if (f < fMin * sc || f > fMax * sc || Math.abs(e.getY() - this.getY()) > 2.5D) continue;
            if (lat > 0.0D && Math.abs(l) > lat * sc) continue;
            if (deg > 0.0F && Math.abs(Math.toDegrees(Math.atan2(l, f))) > deg) continue;
            if (e instanceof net.minecraft.world.entity.player.Player p && (p.isCreative() || p.isSpectator())) continue;
            if (e != this.getTarget() && !(e instanceof net.minecraft.world.entity.player.Player)) continue;
            if (e == held()) continue;
            if (e.hurt(this.damageSources().mobAttack(this), damage)) {
                this.setLastHurtMob(e);
                double k = Math.max(0.0D, 1.0D - e.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE));
                e.setDeltaMovement(e.getDeltaMovement().add(fx * push * k, lift * k, fz * push * k));
                e.hurtMarked = true;
            }
        }
    }

    // --- 抱え込み(相手を鉤の輪の中に乗せる。サルティムネスの押さえ込みと同じ作り)

    /** 抱えられる相手か(ボス・大きすぎる・ほかに乗っている / 乗せている相手は抱えない)。 */
    static boolean canHold(net.minecraft.world.entity.LivingEntity prey) {
        return !prey.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES) && prey.getBbWidth() <= HOLD_MAX_WIDTH
                && prey.getBbHeight() <= HOLD_MAX_HEIGHT && !prey.isVehicle() && !prey.isPassenger();
    }

    /** 抱える。乗せられなければ false。 */
    boolean hold(net.minecraft.world.entity.LivingEntity prey) {
        if (!canHold(prey) || !this.getPassengers().isEmpty()) return false;
        if (!prey.startRiding(this, true)) return false;
        if (prey instanceof net.minecraft.server.level.ServerPlayer sp) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new com.yayuy.overgrownvegetation.network.CaptorPayload(
                    com.yayuy.overgrownvegetation.network.CaptorPayload.START, this.getId()));
        }
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        return true;
    }

    /** 抱えている相手(いなければ null)。 */
    @org.jetbrains.annotations.Nullable
    public net.minecraft.world.entity.LivingEntity held() {
        return this.getFirstPassenger() instanceof net.minecraft.world.entity.LivingEntity l ? l : null;
    }

    public boolean isHolding() {
        return held() != null;
    }

    /** 放す(相手を前へ {@code push} だけ押し出す)。 */
    void releaseHold(double push) {
        net.minecraft.world.entity.Entity prey = this.getFirstPassenger();
        this.releasing = true;
        this.ejectPassengers();
        this.releasing = false;
        if (prey != null) {
            float r = this.getYRot() * Mth.DEG_TO_RAD;
            prey.setDeltaMovement(-Mth.sin(r) * push, 0.25D, Mth.cos(r) * push);
            prey.hurtMarked = true;
        }
    }

    /** 締める(噛む)。 */
    void squeeze(net.minecraft.world.entity.LivingEntity v, float damage) {
        if (v instanceof net.minecraft.server.level.ServerPlayer sp) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new com.yayuy.overgrownvegetation.network.CaptorPayload(
                    com.yayuy.overgrownvegetation.network.CaptorPayload.HIT, this.getId()));
        }
        if (v.hurt(this.damageSources().mobAttack(this), damage)) this.setLastHurtMob(v);
        this.playSound(net.minecraft.sounds.SoundEvents.EVOKER_FANGS_ATTACK, 0.6F, 0.9F);
    }

    /** 抱えた相手は鉤の輪の中(前 {@link ClipeimnesHuntGoal#HOLD_FORWARD})に地面へ立たせる。 */
    @Override
    protected net.minecraft.world.phys.Vec3 getPassengerAttachmentPoint(net.minecraft.world.entity.Entity passenger, net.minecraft.world.entity.EntityDimensions dimensions, float partialTick) {
        double up = passenger.getVehicleAttachmentPoint(this).y;
        return new net.minecraft.world.phys.Vec3(0.0D, up, ClipeimnesHuntGoal.HOLD_FORWARD * this.getScale()).yRot(-this.getYRot() * Mth.DEG_TO_RAD);
    }

    /** 放した相手は抱えていた所に残す(バニラは芯の箱の上 = 背中に立たせることがある。サルティムネスと同じ)。 */
    @Override
    public net.minecraft.world.phys.Vec3 getDismountLocationForPassenger(net.minecraft.world.entity.LivingEntity passenger) {
        net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(passenger.getX(), this.getY(), passenger.getZ());
        if (this.level().noCollision(passenger, passenger.getDimensions(passenger.getPose()).makeBoundingBox(at))) return at;
        return super.getDismountLocationForPassenger(passenger);
    }

    @org.jetbrains.annotations.Nullable
    @Override
    public net.minecraft.world.entity.LivingEntity getControllingPassenger() {
        return null;
    }

    /** 抱えられたプレイヤーがこちらを殴れるように(殴れば放す)。 */
    @Override
    public boolean canRiderInteract() {
        return true;
    }

    @Override
    public boolean shouldRiderSit() {
        return false;
    }

    @Override
    protected boolean canAddPassenger(net.minecraft.world.entity.Entity passenger) {
        return this.getPassengers().isEmpty();
    }

    @net.neoforged.fml.common.EventBusSubscriber(modid = com.yayuy.overgrownvegetation.OvergrownVegetation.MODID, bus = net.neoforged.fml.common.EventBusSubscriber.Bus.GAME)
    public static final class Events {
        private Events() {
        }

        /** 抱えられた相手は自分から降りられず、ほかの乗り物にも乗れない(降車はサーバーでだけ止める)。降りたら本人へ知らせる。 */
        @net.neoforged.bus.api.SubscribeEvent
        public static void onMount(net.neoforged.neoforge.event.entity.EntityMountEvent event) {
            if (event.getLevel().isClientSide()) return;
            if (event.isDismounting()) {
                if (event.getEntityBeingMounted() instanceof ClipeimnesEntity c && c.isAlive() && !c.isRemoved() && !c.releasing && c.act() == ACT_HOLD) {
                    event.setCanceled(true);
                    return;
                }
                if (event.getEntityBeingMounted() instanceof ClipeimnesEntity c && event.getEntityMounting() instanceof net.minecraft.server.level.ServerPlayer sp) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new com.yayuy.overgrownvegetation.network.CaptorPayload(
                            com.yayuy.overgrownvegetation.network.CaptorPayload.END, c.getId()));
                }
                return;
            }
            if (event.isMounting() && event.getEntityMounting().getVehicle() instanceof ClipeimnesEntity c && c != event.getEntityBeingMounted()
                    && c.isAlive() && !c.isRemoved() && c.act() == ACT_HOLD) {
                event.setCanceled(true);
            }
        }

        /** 抱えられたままログアウトしたら先に放す(放さないとプレイヤーの乗り物ごと保存されて世界から消える)。 */
        @net.neoforged.bus.api.SubscribeEvent
        public static void onLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity().getVehicle() instanceof ClipeimnesEntity c && !c.level().isClientSide()) c.releaseHold(0.0D);
        }
    }

    // --- テスト用

    /** テスト用: 次の判断で出す技(ACT_BASH_R_WINDUP・ACT_GRAB_WINDUP・ACT_RELEASE_WINDUP)。 */
    public void forceActForTests(int act) {
        if (this.hunt != null) this.hunt.forceAct = act;
    }

    /** テスト用: 溜めの段を決める。 */
    public void setChargeForTests(int c) {
        this.charge = Mth.clamp(c, 0, CHARGE_MAX);
        syncGuard();
    }

    /** テスト用: 盾を今すぐ構えた(弾ける)ことにする。 */
    public void guardNowForTests() {
        this.guardWanted = true;
        setGuardState(GUARD_UP);
        this.guardTicks = GUARD_WINDUP_TICKS;
    }

    /** 確かめ用(テストクライアントだけ): 名前に grabber を含む個体は、届けば盾打ちより抱え込みを選ぶ。 */
    boolean devPreferGrab() {
        return DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("grabber");
    }

    /** 弾除けで相手と仲間の間へ割り込みに行っているか(サーバー)。 */
    public boolean isCovering() {
        return this.hunt != null && this.hunt.covering && this.hunt.act() == ACT_NONE;
    }

    public int guardState() {
        return this.level().isClientSide() ? this.entityData.get(DATA_GUARD) & 0xF : this.guardState;
    }

    // --- 光(クライアント): 抱え込みと溜めの解放だけ(決定 11-15)。盾打ち・盾で詰めるは溜めの段の光だけ

    private int clientAct = ACT_NONE, clientActKey = -1, clientActStart;
    private int clientGuardKey = -1;

    /** クライアント: 技の始まり・構えの変わり目を数える。 */
    private void tickClientAct() {
        if (DEV_HOOKS && this.gait != null && this.hasCustomName() && this.getCustomName().getString().contains("acts")) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT, "CLIP_ANIMNAME t %d 技 %d アニメ %s 状態 %s 光 %.2f",
                    this.tickCount, this.clientAct, this.gait.currentName(), this.gait.getAnimationState(), glow(0.0F)));
        }
        int v = this.entityData.get(DATA_ACT);
        if (v != this.clientActKey) {
            this.clientActKey = v;
            this.clientAct = v & 0xFF;
            this.clientActStart = this.tickCount;
        }
        int g = this.entityData.get(DATA_GUARD) & ~0xF0;   // 溜めの段の変化では数え直さない
        if (g != this.clientGuardKey) {
            this.clientGuardKey = g;
        }
    }

    public float clientActTicks(float pt) {
        return this.tickCount - this.clientActStart + pt;
    }

    /** 溜めの段の光の強さ(満ちて {@link #CHARGE_GLOW_FULL})【仮】。 */
    static final float CHARGE_GLOW_FULL = 0.6F;

    /**
     * 盾鱗の隙間の葉脈と T 字の口の光(0〜1)。時刻はアニメの一覧(Vault 6 節。前触れは補間 3 tick ぶん遅らせる): 抱え込みの前触れの 3 → 12 で点く・
     * 抱えて締める間は点いたまま・放す / 外すと 9 tick で消える / 解放の前触れの 3 → 11 で満ちる・打ち合わせ(2)で閃き 6 で消える。
     * それ以外は溜めの段に比例して灯る(盾で受けた数)。
     */
    public float glow(float pt) {
        float t = clientActTicks(pt);
        float act = switch (this.clientAct) {
            case ACT_GRAB_WINDUP -> Mth.clamp((t - ClipeimnesHuntGoal.LEAD - 3.0F) / 9.0F, 0.0F, 1.0F);
            case ACT_GRAB, ACT_HOLD -> 1.0F;
            case ACT_HOLD_RECOVER, ACT_GRAB_RECOVER -> Mth.clamp(1.0F - t / 9.0F, 0.0F, 1.0F);
            case ACT_RELEASE_WINDUP -> Mth.clamp((t - ClipeimnesHuntGoal.LEAD - 3.0F) / 8.0F, 0.0F, 1.0F);
            case ACT_RELEASE -> t <= 2.0F ? 1.0F : Mth.clamp(1.0F - (t - 2.0F) / 4.0F, 0.0F, 1.0F);
            default -> 0.0F;
        };
        float stored = this.clientAct == ACT_RELEASE || this.clientAct == ACT_RELEASE_RECOVER ? 0.0F : CHARGE_GLOW_FULL * charge() / CHARGE_MAX;
        return Math.max(act, stored);
    }

    // ------------------------------------------------------------------ 確かめ用

    /**
     * 確かめ用(テストクライアントだけ): 名前が {@code nostroll_run} で始まる個体は、経路が尽きるたびに 12〜20 ブロック先の陸へ速さ 1.0 で歩く
     * (10 体の重さを測る)。名前に {@code trace} を含む個体は 10 tick ごとに速さと回る速さを書く。
     */
    @Override
    protected void tickRunner() {
        tickGuard();
        CaptorMount.releaseStray(this, act() == ACT_HOLD, () -> releaseHold(0.0D));   // 読み込み直しで技が消え、獲物だけ乗っている(①1)
        if (!DEV_HOOKS || !this.hasCustomName()) return;
        String name = this.getCustomName().getString();
        if (name.contains("charged") && this.charge == 0 && act() == ACT_NONE) setChargeForTests(CHARGE_MAX);   // 確かめ用: 溜めを満たしておく
        if (name.startsWith("nostroll_run") && this.getNavigation().isDone()) {
            net.minecraft.world.phys.Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this, 20, 6);
            if (p != null && p.distanceToSqr(this.position()) > 12.0D * 12.0D) this.getNavigation().moveTo(p.x, p.y, p.z, 1.0D);
        }
        if (name.contains("trace") && this.tickCount % 10 == 0) {
            double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo));
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "CLIP_GAIT %s pos %.1f %.1f %.1f 速さ %.3f 回る %.1f 経路 %s", name, this.getX(), this.getY(), this.getZ(), v,
                    getTurnRate(), this.getNavigation().isDone() ? "なし" : "あり"));
        }
    }

    // ------------------------------------------------------------------ 脚

    @Override
    protected void configurePlanter(FootPlanter fp) {
        fp.lift = 0.45D;       // 宙の足を上げる高さ(ブロック)【仮】
        fp.probeUp = 1.3D;
        fp.probeDown = 2.0D;   // 付け根の高さ 1.5 + 少し
    }

    /** 速いほど大股に。回っている間は小股で速く踏み替える(後脚の足先が大きく振られるため)。 */
    @Override
    protected void tunePlanter(FootPlanter fp) {
        double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo)) / Math.max(this.getScale(), 0.05F);
        boolean turning = Math.abs(getTurnRate()) > 2.0F;
        double step = turning ? STEP_TURN : Mth.clamp(STEP_MIN + v * STEP_PER, STEP_MIN, STEP_MAX);
        fp.stepAt = step;
        fp.swingTicks = turning ? SWING_TURN : SWING_WALK;
        // 2 つの組みを半巡ずつずらす(アニメの胴の揺れは 0 と 0.5 で組みが着く作り)。空けないと、片方が着くとすぐもう片方が踏み替え、
        // 着地が 1 巡の 0.06 と 0.34 に寄った(11-15 測り)。空ける tick = (着いている時間 − 宙の時間) ÷ 2(ヴェナムネスと同じ式)
        double stance = v > 0.01D ? step / v : 40.0D;
        fp.restGap = turning || v < 0.02D ? 0 : Mth.clamp((int) Math.round((stance - fp.swingTicks) * 0.5D), 0, 8);
        fp.lead = Mth.clamp(step * 0.5D / Math.max(v, 0.01D), 0.0D, turning ? LEAD_TURNING : LEAD_MAX);
    }

    private static double sq(double v) {
        return v * v;
    }

    // ------------------------------------------------------------------ アニメ

    /** クライアント: 今 run を流しているか(速さのしきい値の幅のため)。 */
    private boolean running;
    /** クライアント: 歩きのコントローラ(1 巡の中の位置を読む)。 */
    @org.jetbrains.annotations.Nullable
    private GaitAnimationController<ClipeimnesEntity> gait;
    /** クライアント: 中脚 2 本の前の tick の「宙か」と、踏み替えに合わせるために返す残りのずれ(1 巡の割合、+ = アニメが先)。 */
    private final boolean[] midSwingBefore = new boolean[2];
    private double phaseDebt;

    /**
     * アニメは 1 つのコントローラ({@link GaitAnimationController}、グレガリアで足したもの)。前へ進むか回っていれば walk、速ければ run
     * (しきい値に幅 {@link #RUN_ON}・{@link #RUN_OFF})、止まっていれば idle。walk と run は同じ脚の並びなので、切り替えは 1 巡の中の位置を保って補間なしで移る。
     * どちらも実際の速さ ÷ 基準の速さで流す(遠くの個体の足が滑らない)。近くで足の置き場が効いている間は、脚は IK が上書きし、
     * 胴・盾・尾の揺れの位相を中脚の着地に合わせて少しずつ寄せる({@link #syncCorrection})。
     * その場で回る間は walk を基準の 0.6 倍で流す(回る用のアニメは無い。遠くでは足が滑る)。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new GaitAnimationController<>(this, "main", 4, this::mainState));
        // 盾で詰める(guard_*)は上半身だけの重ね: main の後に登録する(後のコントローラが同じ骨を上書きする)
        controllers.add(new software.bernie.geckolib.animation.AnimationController<>(this, "guard", 3, this::guardState));
    }

    private static final RawAnimation GUARD_UP_ANIM = RawAnimation.begin().thenPlay("guard_windup").thenLoop("guard");
    private static final RawAnimation GUARD_HIT_ANIM = RawAnimation.begin().thenPlay("guard_hit").thenLoop("guard");
    private static final RawAnimation GUARD_DOWN_ANIM = RawAnimation.begin().thenPlayAndHold("guard_recover");

    /**
     * 盾の構えの層: 構える = guard_windup → guard(繰り返し)、受けた = guard_hit → guard(受けるたびに頭から)、下ろす = guard_recover、無し = 止める。
     * 全身の技の間は止める(サーバーが技の前に構えを外す)。
     */
    private PlayState guardState(AnimationState<ClipeimnesEntity> state) {
        int g = this.entityData.get(DATA_GUARD);
        int s = g & 0xF;
        if (s == GUARD_NONE || act() != ACT_NONE || this.isDeadOrDying()) return PlayState.STOP;
        var c = state.getController();
        int key = g & ~0xF0;
        if (key != this.guardAnimKey) {
            this.guardAnimKey = key;
            if (s == GUARD_HIT) c.forceAnimationReset();   // 続けて受けたら頭から
        }
        return state.setAndContinue(s == GUARD_UP ? GUARD_UP_ANIM : s == GUARD_HIT ? GUARD_HIT_ANIM : GUARD_DOWN_ANIM);
    }

    private int guardAnimKey = -1;

    private static final RawAnimation BASH_R_WINDUP_ANIM = RawAnimation.begin().thenPlayAndHold("bash_r_windup");
    private static final RawAnimation BASH_R_ANIM = RawAnimation.begin().thenPlayAndHold("bash_r");
    private static final RawAnimation BASH_R_RECOVER_ANIM = RawAnimation.begin().thenPlayAndHold("bash_r_recover");
    private static final RawAnimation BASH_L_WINDUP_ANIM = RawAnimation.begin().thenPlayAndHold("bash_l_windup");
    private static final RawAnimation BASH_L_ANIM = RawAnimation.begin().thenPlayAndHold("bash_l");
    private static final RawAnimation BASH_L_RECOVER_ANIM = RawAnimation.begin().thenPlayAndHold("bash_l_recover");
    private static final RawAnimation GRAB_WINDUP_ANIM = RawAnimation.begin().thenPlayAndHold("grab_windup");
    private static final RawAnimation GRAB_ANIM = RawAnimation.begin().thenPlayAndHold("grab");
    private static final RawAnimation HOLD_ANIM = RawAnimation.begin().thenLoop("hold");
    private static final RawAnimation HOLD_RECOVER_ANIM = RawAnimation.begin().thenPlayAndHold("hold_recover");
    private static final RawAnimation GRAB_RECOVER_ANIM = RawAnimation.begin().thenPlayAndHold("grab_recover");
    private static final RawAnimation RELEASE_WINDUP_ANIM = RawAnimation.begin().thenPlayAndHold("release_windup");
    private static final RawAnimation RELEASE_ANIM = RawAnimation.begin().thenPlayAndHold("release");
    private static final RawAnimation RELEASE_RECOVER_ANIM = RawAnimation.begin().thenPlayAndHold("release_recover");

    /**
     * 技のアニメ(速さは全部 1.0)。前触れは歩き・待機から補間 {@link ClipeimnesHuntGoal#LEAD} tick、その後の段(実行・締め・隙)は補間 0
     * (Vault 6 節: 補間 4 のままだと 5〜6 tick の実行が始まる前に前触れの姿で固まる。つなぎ目は前の終わり = 次の頭)。技の段で無ければ null。
     */
    @org.jetbrains.annotations.Nullable
    private PlayState actState(AnimationState<ClipeimnesEntity> state, GaitAnimationController<ClipeimnesEntity> c) {
        RawAnimation a = switch (this.clientAct) {
            case ACT_BASH_R_WINDUP -> BASH_R_WINDUP_ANIM;
            case ACT_BASH_R -> BASH_R_ANIM;
            case ACT_BASH_R_RECOVER -> BASH_R_RECOVER_ANIM;
            case ACT_BASH_L_WINDUP -> BASH_L_WINDUP_ANIM;
            case ACT_BASH_L -> BASH_L_ANIM;
            case ACT_BASH_L_RECOVER -> BASH_L_RECOVER_ANIM;
            case ACT_GRAB_WINDUP -> GRAB_WINDUP_ANIM;
            case ACT_GRAB -> GRAB_ANIM;
            case ACT_HOLD -> HOLD_ANIM;
            case ACT_HOLD_RECOVER -> HOLD_RECOVER_ANIM;
            case ACT_GRAB_RECOVER -> GRAB_RECOVER_ANIM;
            case ACT_RELEASE_WINDUP -> RELEASE_WINDUP_ANIM;
            case ACT_RELEASE -> RELEASE_ANIM;
            case ACT_RELEASE_RECOVER -> RELEASE_RECOVER_ANIM;
            default -> null;
        };
        if (a == null) return null;
        boolean windup = this.clientAct == ACT_BASH_R_WINDUP || this.clientAct == ACT_BASH_L_WINDUP || this.clientAct == ACT_GRAB_WINDUP
                || this.clientAct == ACT_RELEASE_WINDUP;
        c.transitionLength(windup ? ClipeimnesHuntGoal.LEAD : 0);
        c.setAnimationSpeed(1.0D);
        this.running = false;
        this.phaseDebt = 0.0D;
        return state.setAndContinue(a);
    }

    private PlayState mainState(AnimationState<ClipeimnesEntity> state) {
        GaitAnimationController<ClipeimnesEntity> c = (GaitAnimationController<ClipeimnesEntity>) state.getController();
        this.gait = c;
        if (!this.isDeadOrDying()) {
            PlayState acting = actState(state, c);
            if (acting != null) return acting;
        }
        double s = this.getScale();
        double ahead = clientForwardSpeed();
        boolean turning = Math.abs(getTurnRate()) >= TURN_ANIM_MIN;
        if (!this.isDeadOrDying() && (ahead >= WALK_MIN_SPEED || turning)) {
            if (this.running ? ahead > RUN_OFF * s : ahead >= RUN_ON * s) {
                this.running = true;
                double base = Mth.clamp(ahead / (RUN_REF * s), RUN_SPEED_MIN, RUN_SPEED_MAX);
                c.setAnimationSpeed(Math.max(0.0D, base + syncCorrection(base, RUN_TICKS)));
                c.switchGait(RUN, "run", "walk");
                return state.setAndContinue(RUN);
            }
            this.running = false;
            double v = Math.max(ahead, turning ? WALK_REF * 0.6D : 0.0D);
            double base = Mth.clamp(v / (WALK_REF * s), LOOP_SPEED_MIN, LOOP_SPEED_MAX);
            c.setAnimationSpeed(Math.max(0.0D, base + syncCorrection(base, WALK_TICKS)));
            c.switchGait(WALK, "walk", "run");
            return state.setAndContinue(WALK);
        }
        this.running = false;
        this.phaseDebt = 0.0D;
        c.setAnimationSpeed(1.0D);
        c.switchGait(IDLE, "idle", "-");
        return state.setAndContinue(IDLE);
    }

    /** 1 巡の長さ(tick)。 */
    static final double WALK_TICKS = 24.0D, RUN_TICKS = 16.0D;

    /**
     * 踏み替えに合わせる再生の速さの足し分(アニメの tick / ゲームの tick): 残りのずれの {@link #SYNC_RATE} を 1 tick で返す。
     * 足し分は基準の速さの ±{@link #SYNC_MAX} まで(大きく変えると胴の揺れが急に速く・遅く見える)。
     */
    private double syncCorrection(double base, double loopTicks) {
        if (!proceduralLegs()) return 0.0D;
        double corr = -this.phaseDebt * SYNC_RATE * loopTicks;
        return Mth.clamp(corr, -SYNC_MAX * base, SYNC_MAX * base);
    }

    /**
     * クライアント: 中脚が着いた tick に、アニメの 1 巡の中の位置と着地の位置(中左 = 0、中右 = 0.5)のずれを測る。
     * 毎 tick、再生の速さで返した分だけずれを減らす(返す量は {@link #syncCorrection} と同じ式)。
     */
    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide()) return;
        tickClientAct();
        GaitAnimationController<ClipeimnesEntity> c = this.gait;
        FootPlanter fp = footPlanter();
        if (c == null || fp == null || !fp.ready()) return;
        String name = c.currentName();
        double loop = "run".equals(name) ? RUN_TICKS : "walk".equals(name) ? WALK_TICKS : 0.0D;
        if (loop > 0.0D && proceduralLegs()) {
            double base = c.getAnimationSpeed();   // 足し分を含む今の速さ。返した量は足し分 ÷ 1 巡の長さ
            double corr = syncCorrection(Math.max(base, 0.05D), loop);
            this.phaseDebt += corr / loop;
        } else {
            this.phaseDebt = 0.0D;
        }
        for (int i = 0; i < 2; i++) {
            boolean swinging = fp.swinging(i);
            if (this.midSwingBefore[i] && !swinging && loop > 0.0D && proceduralLegs()) {
                double target = i == 0 ? 0.0D : 0.5D;
                double err = c.fraction() - target;
                err -= Math.floor(err + 0.5D);   // −0.5〜0.5 へ
                this.phaseDebt = err;
                if (DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("gait")) {
                    com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                            "CLIP_SYNC t %d %s 中%s が着いた 1 巡の位置 %.3f(着地は %.1f)ずれ %+.3f 速さ %.2f", this.tickCount, name, i == 0 ? "左" : "右",
                            c.fraction(), target, err, c.getAnimationSpeed()));
                }
            }
            this.midSwingBefore[i] = swinging;
        }
        if (DEV_HOOKS && this.tickCount % 5 == 0 && this.hasCustomName() && this.getCustomName().getString().contains("gait") && loop > 0.0D) {
            double ref = "run".equals(name) ? RUN_REF : WALK_REF;
            double ahead = clientForwardSpeed();
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "CLIP_ANIM t %d %s 足の置き場 %s 前へ %.3f アニメの送り %.3f 滑り %+.3f ブロック/tick", this.tickCount, name, proceduralLegs(), ahead,
                    c.getAnimationSpeed() * ref * this.getScale(), ahead - c.getAnimationSpeed() * ref * this.getScale()));
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
