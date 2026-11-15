package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.flyer.FlightBody;
import com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * ヴェロフィル {@code velophyll}(G4・空の爆撃。V 字の群れ)。Vault「Mob の能力/ヴェロフィル」。
 *
 * <h2>段 1(2026-11-15): 飛ぶ・V 字の群れ・林冠に止まって休むまで</h2>
 * 動きは「飛ぶ型」の土台 {@link SkyFlyer}。爆撃(群れの端から 1 体ずつ急降下して胞子爆弾)は次の段。自然には湧かない。
 * 形は作り直し案 re_velophyll_r5(正本 {@code デスクトップ\blockbench\mobs\velophyll.bbmodel})。
 * <h2>段 2 のアニメのつなぎ(2026-11-15。アニメはモデル制作の持ち場で作ったもの、Vault 6.3・6.6)</h2>
 * 止まる = perch_idle(気付くと perch_kamae)/ 飛び立つ = その場で takeoff_windup → 昇り始めに takeoff → fly /
 * 降りる = land_windup → land(繰り返し)→ 止まった tick に land_recover → perch_idle。差 0 でつながる段の間は補間 0、
 * 途中から入る所だけ {@link #ENTRY_SHORT}〜{@link #ENTRY} tick(補間の分だけ溜めを長くして、溜めの終わりと昇り始めを揃える)。
 * 身構え【決定 11-15 全体管理・ユーザー「異論なし」】: 12 ブロックで身構えて相手を向き、8 ブロックで一斉に飛び立つ。驚いたときも溜め 7 tick を入れる。
 * 【決定 11-15 ユーザー】巡航 0.7 ブロック/tick・旋回の半径 約 10・当たりは胴の芯 + 翼の叩ける部位・羽ばたき + 時々滑空・群れの上限 7。
 * <h2>爆撃(2026-11-15、能力の段)</h2>
 * 【決定 11-14】昼に V 字で飛び、群れから 1 体ずつ傾いて叫び(前触れ・地面に影)急降下 → 胞子爆弾を落とす(胞子の雲 + 小さな汚れ)→ 羽ばたいて列に戻る。
 * 群れの先頭が相手を見つけて上空を回り({@link Flock#steerPatrol})、{@link #BOMB_INTERVAL} ごとに群れの端の 1 体を急降下させる。
 * 急降下する個体は土台の「自前の飛び方」({@link #ownFlight})で列を外れ、前触れ {@code dive_windup} → 落ちる {@code dive} → 放す {@code dive_recover}
 * (爆弾は 2 tick 目 = 翼の裏の胞子嚢が弾ける)→ 引き起こして列へ戻る。機首下げは動く向きから、地面の影は描画({@link com.yayuy.overgrownvegetation.client.VelophyllRenderer})。
 * 夜と雨は止まり木にいるので襲わない(土台のまま)。数値は【仮・Claude】(未決は全体管理へ問い合わせ中)。
 */
public class VelophyllEntity extends SkyFlyer implements GeoEntity, ParasitizedMob {

    public static final String ID = "velophyll";

    /** 飛び方【仮・Claude。巡航 0.7 はユーザー決定】。 */
    private static final FlightBody.Params PARAMS = new FlightBody.Params();

    static {
        PARAMS.cruise = 0.7D;
        PARAMS.minSpeed = 0.25D;
        PARAMS.maxSpeed = 0.95D;
        PARAMS.maxTurnDeg = 4.0D;
    }

    /** 林冠からの高さの層(ブロック)。【決定 11-14】林冠の少し上(+5〜15)。 */
    public static final double LAYER_MIN = 5.0D, LAYER_MAX = 15.0D;
    /** 群れの上限【決定 11-15 ユーザー】。 */
    public static final int FLOCK_MAX = 7;

    /**
     * 叩ける部位【仮】: 左右の翼(翼の付け根 x ±4・先 ±46 単位 → 中心 ±1.75 ブロック、幅 2.4)と頭(z −23〜−31 単位 → 前 1.6)。
     * 胴の芯(1.2 × 0.8)は本体の箱。左右は対称なので geo の x の向き(鏡写し)は気にしない。
     */
    static final String[] PARTS = {"wing_l", "wing_r", "head"};
    static final float[] PART_W = {2.4F, 2.4F, 0.8F};
    static final float[] PART_H = {0.6F, 0.6F, 0.6F};
    static final double[] PART_CENTERS = {
            0.3D, 1.75D, 0.65D,
            0.3D, -1.75D, 0.65D,
            1.6D, 0.0D, 0.6D};
    /**
     * 止まっている間(閉じた葉: 翼は背の上で合わせて立つ。左右 ±0.6・高さ 0.4〜2.8 ブロック)の部位の置き場。
     * 翼の 2 つを背の上に縦に重ねる(下 1.0・上 2.2 を中心に高さ 0.6 ずつ)。頭は低く前へ伏せる。
     */
    static final double[] PART_CENTERS_PERCHED = {
            0.0D, 0.0D, 1.0D,
            0.0D, 0.0D, 2.2D,
            1.6D, 0.0D, 0.4D};

    /** 飛び立ちの溜め【決定 11-15: 7 tick】と、溜めへ入る補間(その分だけ溜めを長くする)。 */
    public static final int WINDUP_TICKS = 7, ENTRY_SHORT = 3;
    /** 途中から入る所の補間(tick)。fly ↔ glide は今まで通り {@link #ENTRY_FLY}。 */
    static final int ENTRY = 4, ENTRY_FLY = 6;
    /** 身構える距離【仮・決定 11-15 全体管理】。 */
    public static final double ALERT_RANGE = 12.0D;

    private static final RawAnimation FLY = RawAnimation.begin().thenLoop("fly");
    private static final RawAnimation GLIDE = RawAnimation.begin().thenLoop("glide");
    private static final RawAnimation PERCH_IDLE = RawAnimation.begin().thenLoop("perch_idle");
    private static final RawAnimation KAMAE = RawAnimation.begin().thenLoop("perch_kamae");
    private static final RawAnimation WINDUP = RawAnimation.begin().thenPlayAndHold("takeoff_windup");
    private static final RawAnimation TAKEOFF = RawAnimation.begin().thenPlay("takeoff").thenLoop("fly");
    private static final RawAnimation LAND = RawAnimation.begin().thenPlay("land_windup").thenLoop("land");
    private static final RawAnimation LAND_RECOVER = RawAnimation.begin().thenPlay("land_recover").thenLoop("perch_idle");

    /** クライアント: 今流しているつなぎ・流し始めた tick・入りの補間。 */
    private RawAnimation curAnim;
    private int curStart, curEntry;

    // ------------------------------------------------------------------ 爆撃【仮・Claude 11-15】
    /** 相手を探す範囲: 群れの先頭から水平・下・上(ブロック)。空が見えている相手だけ(屋根・林冠の下は見つけない)。 */
    public static final double SEARCH_H = 40.0D, SEARCH_DOWN = 40.0D, SEARCH_UP = 10.0D;
    /** 相手を探す間隔(tick。広い箱の検索は重いので先頭だけが間をあけて)。 */
    public static final int SEARCH_EVERY = 20;
    /** 見失う: 先頭からこれより離れる・この tick 見えない。 */
    public static final double LOSE_RANGE = 64.0D;
    public static final int LOSE_UNSEEN = 400;
    /** 先頭が相手の上空を回る半径(ブロック)。 */
    public static final double CIRCLE_R = 25.0D;
    /** 群れで急降下する間隔(tick)。 */
    public static final int BOMB_INTERVAL = 120;
    /** 前触れ(dive_windup 20 tick + 入りの補間)・落ちる上限・引き起こし(dive_recover 24 tick)の長さ(tick)。 */
    public static final int DIVE_WINDUP = 20 + ENTRY, DIVE_MAX = 80, DIVE_RECOVER = 24;
    /** 爆弾を放す: 引き起こしの何 tick 目か(dive_recover の 2 tick 目 + 入りの補間 3)。 */
    public static final int BOMB_AT = 2 + ENTRY_SHORT;
    /** 相手の頭上何ブロックで放すか・落ちる速さの上限と増え方(ブロック/tick)。 */
    public static final double RELEASE_ABOVE = 5.0D, DIVE_SPEED = 1.2D, DIVE_ACCEL = 0.06D;
    /** 見つける相手は群れの先頭よりこれ以上下にいること(空を飛ぶ相手・高い所の相手には急降下できない)・放すときの水平の近さ(ブロック)。 */
    public static final double MIN_BELOW = 3.0D, RELEASE_NEAR = 3.0D;
    /** 前触れの間の速さ・回る速さ(度/tick)。 */
    static final double WINDUP_SPEED = 0.55D;
    static final float WINDUP_TURN = 4.0F;

    public static final int DIVE_NONE = 0, DIVE_WINDUP_P = 1, DIVE_FALL = 2, DIVE_RECOVER_P = 3;
    private static final net.minecraft.network.syncher.EntityDataAccessor<Byte> DATA_DIVE =
            net.minecraft.network.syncher.SynchedEntityData.defineId(VelophyllEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BYTE);
    /** 影を落とす相手(エンティティの番号。−1 = 無し)。 */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_DIVE_TARGET =
            net.minecraft.network.syncher.SynchedEntityData.defineId(VelophyllEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    /** 群れの相手(先頭だけが持つ)・最後に見えた tick・叩かれた相手か(替えない)。 */
    @org.jetbrains.annotations.Nullable
    private net.minecraft.world.entity.LivingEntity flockTarget;
    private long targetSeen;
    private boolean targetIsAttacker;
    private int bombCooldown = BOMB_INTERVAL / 2;
    private double circleAngle;
    /** 急降下(この個体)。 */
    private int dive, diveTicks;
    @org.jetbrains.annotations.Nullable
    private net.minecraft.world.entity.LivingEntity diveTarget;
    private double diveSpeed, dvx, dvy, dvz;

    private static final RawAnimation DIVE_WINDUP_A = RawAnimation.begin().thenPlayAndHold("dive_windup");
    private static final RawAnimation DIVE_A = RawAnimation.begin().thenLoop("dive");
    private static final RawAnimation DIVE_RECOVER_A = RawAnimation.begin().thenPlay("dive_recover").thenLoop("fly");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public VelophyllEntity(EntityType<? extends VelophyllEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 10);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected FlightBody.Params params() {
        return PARAMS;
    }

    @Override
    protected double layerMin() {
        return LAYER_MIN;
    }

    @Override
    protected double layerMax() {
        return LAYER_MAX;
    }

    @Override
    protected int flockMax() {
        return FLOCK_MAX;
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

    /** 止まった姿のままか(止まっている・飛び立ちの待ちと溜め)。部位の置き場に使う(サーバーもクライアントも送られた状態で)。 */
    private boolean perchedPose() {
        int m = mode();
        return m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() < 2;
    }

    @Override
    protected int takeoffWindupTicks() {
        return WINDUP_TICKS + ENTRY_SHORT;
    }

    @Override
    protected double alertRange() {
        return ALERT_RANGE;
    }

    /** 止まった姿は翼が足元 +2.8 ブロック、降りる構えは +3.3 まで上がる(Vault 6.4)ので、上を +3.5 に。 */
    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(this.getX() - 3.5D, this.getY() - 1.0D, this.getZ() - 3.5D,
                this.getX() + 3.5D, this.getY() + 3.5D, this.getZ() + 3.5D);
        // 急降下の前触れ・落ちる間は地面の相手に影を描くので、相手の足元まで広げる(本体が画面の外でも影が消えないように)
        net.minecraft.world.entity.Entity t = shadowTarget();
        return t == null ? box : box.minmax(t.getBoundingBox().inflate(2.0D));
    }

    // ------------------------------------------------------------------ 爆撃

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_DIVE, (byte) DIVE_NONE);
        builder.define(DATA_DIVE_TARGET, -1);
    }

    /** 急降下の段(描画も読む)。 */
    public int diveState() {
        return this.entityData.get(DATA_DIVE);
    }

    /** 影を落とす相手(前触れと落ちる間だけ。描画用)。 */
    @org.jetbrains.annotations.Nullable
    public net.minecraft.world.entity.Entity shadowTarget() {
        int d = diveState();
        if (d != DIVE_WINDUP_P && d != DIVE_FALL) return null;
        int id = this.entityData.get(DATA_DIVE_TARGET);
        return id < 0 ? null : this.level().getEntity(id);
    }

    /** 群れの相手(先頭が持つ。テスト・計測用)。 */
    @org.jetbrains.annotations.Nullable
    public net.minecraft.world.entity.LivingEntity flockTarget() {
        net.minecraft.world.entity.LivingEntity t = leaderOrSelf().flockTarget;
        return t != null && t.isAlive() ? t : null;
    }

    private VelophyllEntity leaderOrSelf() {
        com.yayuy.overgrownvegetation.entity.flyer.Flock f = flock();
        return f != null && f.leader() instanceof VelophyllEntity l ? l : this;
    }

    /**
     * 空が見えている相手か(真上に屋根・林冠があれば見つけない)。葉を含む高さ地図で見る(空の明るさは置いたばかりの屋根に追いつかないため)。
     * ガラスの屋根も屋根として数える。
     */
    public static boolean exposedToSky(net.minecraft.world.entity.LivingEntity t) {
        int x = net.minecraft.util.Mth.floor(t.getX()), z = net.minecraft.util.Mth.floor(t.getZ());
        return t.getEyeY() >= t.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    private boolean validTarget(net.minecraft.world.entity.LivingEntity t) {
        return t.level() == this.level() && AcumnesEntity.isPrey(this, t);
    }

    @Override
    protected void customServerAiStep() {
        if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) tickBombing(sl);
        super.customServerAiStep();
    }

    /** 群れの先頭だけ: 相手を探す・上空を回る・端の 1 体を急降下させる。 */
    private void tickBombing(net.minecraft.server.level.ServerLevel sl) {
        com.yayuy.overgrownvegetation.entity.flyer.Flock f = flock();
        // 急降下の途中で止まり木へ向かう(夜・雨)・飛んでいない: 急降下をやめる(自前の飛び方は ROOST の間は呼ばれない)
        if (this.dive != DIVE_NONE && (mode() != MODE_FLY || f == null || f.state() == com.yayuy.overgrownvegetation.entity.flyer.Flock.State.ROOST)) {
            endDive();
        }
        if (f == null || f.leader() != this) return;
        boolean flying = mode() == MODE_FLY && f.state() == com.yayuy.overgrownvegetation.entity.flyer.Flock.State.PATROL;
        net.minecraft.world.entity.LivingEntity t = this.flockTarget;
        if (t != null) {
            if (!validTarget(t) || this.distanceToSqr(t) > LOSE_RANGE * LOSE_RANGE || !flying) {
                t = null;
            } else if (exposedToSky(t)) {
                this.targetSeen = sl.getGameTime();
            } else if (sl.getGameTime() - this.targetSeen > LOSE_UNSEEN) {
                t = null;
            }
            if (t == null) {
                this.flockTarget = null;
                this.targetIsAttacker = false;
            }
        }
        if (!flying) return;
        if (t == null && this.tickCount % SEARCH_EVERY == 0) {
            t = findTarget(sl);
            if (t != null) {
                this.flockTarget = t;
                this.targetSeen = sl.getGameTime();
                this.targetIsAttacker = false;
                this.circleAngle = Math.atan2(this.getZ() - t.getZ(), this.getX() - t.getX());
            }
        }
        if (t == null) return;
        // 相手の上空を回る(回る向きは反時計回り。半径 CIRCLE_R の円の少し先を行き先にする)
        if (this.tickCount % 10 == 0) {
            this.circleAngle = Math.atan2(this.getZ() - t.getZ(), this.getX() - t.getX()) + 0.6D;
            f.steerPatrol(t.getX() + Math.cos(this.circleAngle) * CIRCLE_R, t.getZ() + Math.sin(this.circleAngle) * CIRCLE_R);
        }
        // 端の 1 体ずつ急降下(見えている間だけ)
        if (--this.bombCooldown <= 0 && exposedToSky(t)) {
            java.util.List<com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer> ms = f.membersView();
            for (int i = ms.size() - 1; i >= 0; i--) {
                if (ms.get(i) instanceof VelophyllEntity v && v.mode() == MODE_FLY && v.dive == DIVE_NONE
                        && (ms.size() == 1 || v != this) && v.distanceToSqr(t) < LOSE_RANGE * LOSE_RANGE) {
                    v.startDive(t);
                    this.bombCooldown = BOMB_INTERVAL;
                    break;
                }
            }
        }
    }

    /** 相手を探す【仮】: 見えるプレイヤー(近い順)→ ほかの生き物(近い順)。植物側・創造・観戦は狙わない。 */
    @org.jetbrains.annotations.Nullable
    private net.minecraft.world.entity.LivingEntity findTarget(net.minecraft.server.level.ServerLevel sl) {
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(this.getX() - SEARCH_H, this.getY() - SEARCH_DOWN, this.getZ() - SEARCH_H,
                this.getX() + SEARCH_H, this.getY() + SEARCH_UP, this.getZ() + SEARCH_H);
        net.minecraft.world.entity.LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        boolean bestPlayer = false;
        for (net.minecraft.world.entity.LivingEntity e : sl.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                e -> validTarget(e) && exposedToSky(e) && e.getY() < this.getY() - MIN_BELOW)) {
            boolean pl = e instanceof net.minecraft.world.entity.player.Player;
            double d = e.distanceToSqr(this);
            if (pl && !bestPlayer || pl == bestPlayer && d < bestD) {
                best = e;
                bestD = d;
                bestPlayer = pl;
            }
        }
        return best;
    }

    /** 急降下を始める(先頭が選ぶ)。 */
    public void startDive(net.minecraft.world.entity.LivingEntity t) {
        if (DEV) LOG.info("VELO_DIVE {} が {} へ急降下を始める(高さの差 {})", this.getId(), t.getName().getString(), String.format("%.1f", this.getY() - t.getY()));
        this.dive = DIVE_WINDUP_P;
        this.diveTicks = 0;
        this.diveTarget = t;
        this.diveSpeed = WINDUP_SPEED;
        this.entityData.set(DATA_DIVE, (byte) this.dive);
        this.entityData.set(DATA_DIVE_TARGET, t.getId());
    }

    private void setDive(int d) {
        this.dive = d;
        this.diveTicks = 0;
        this.entityData.set(DATA_DIVE, (byte) d);
    }

    @Override
    protected boolean ownFlightPitches() {
        return true;
    }

    /** 急降下の間だけ自前で飛ぶ(土台の巡回・隊列・惰性を外れる)。 */
    @Override
    protected boolean ownFlight(net.minecraft.server.level.ServerLevel sl, com.yayuy.overgrownvegetation.entity.flyer.Flock f) {
        if (this.dive == DIVE_NONE) return false;
        this.diveTicks++;
        net.minecraft.world.entity.LivingEntity t = this.diveTarget;
        boolean lost = t == null || !validTarget(t);
        switch (this.dive) {
            case DIVE_WINDUP_P -> {
                // 列を外れて傾きながら相手へ向き直る(叫び)。高さは保つ
                if (lost) {
                    endDive();
                    return false;
                }
                turnToward(com.yayuy.overgrownvegetation.entity.flyer.FlightBody.yawOf(t.getX() - this.getX(), t.getZ() - this.getZ()), WINDUP_TURN);
                double h = Math.toRadians(this.getYRot());
                this.dvx = -Math.sin(h) * WINDUP_SPEED;
                this.dvz = Math.cos(h) * WINDUP_SPEED;
                this.dvy = 0.0D;
                if (this.diveTicks >= DIVE_WINDUP) setDive(DIVE_FALL);
            }
            case DIVE_FALL -> {
                if (lost) {
                    setDive(DIVE_RECOVER_P);
                    break;
                }
                // 相手の頭上 RELEASE_ABOVE へまっすぐ、だんだん速く
                double rx = t.getX() - this.getX(), ry = t.getEyeY() + RELEASE_ABOVE - this.getY(), rz = t.getZ() - this.getZ();
                double d = Math.sqrt(rx * rx + ry * ry + rz * rz);
                this.diveSpeed = Math.min(DIVE_SPEED, this.diveSpeed + DIVE_ACCEL);
                double s = Math.min(this.diveSpeed, d);
                this.dvx = d > 1e-4 ? rx / d * s : 0.0D;
                this.dvy = d > 1e-4 ? ry / d * s : 0.0D;
                this.dvz = d > 1e-4 ? rz / d * s : 0.0D;
                turnToward(com.yayuy.overgrownvegetation.entity.flyer.FlightBody.yawOf(rx, rz), 12.0F);
                double hd = Math.sqrt(rx * rx + rz * rz);
                if (d < 1.5D || hd < RELEASE_NEAR && this.getY() <= t.getEyeY() + RELEASE_ABOVE + 1.0D || this.horizontalCollision || this.verticalCollision
                        || this.diveTicks >= DIVE_MAX) {
                    setDive(DIVE_RECOVER_P);
                }
            }
            case DIVE_RECOVER_P -> {
                // 胞子嚢が弾けて爆弾を放す → 翼を開いて引き起こし、羽ばたいて昇る
                if (this.diveTicks == BOMB_AT) dropBomb(sl);
                double h = Math.toRadians(this.getYRot());
                double k = Math.min(1.0D, this.diveTicks / 8.0D);
                this.dvx = Mth.lerp(k, this.dvx, -Math.sin(h) * 0.6D);
                this.dvz = Mth.lerp(k, this.dvz, Math.cos(h) * 0.6D);
                this.dvy = Mth.lerp(k, this.dvy, 0.25D);
                if (this.diveTicks >= DIVE_RECOVER) {
                    endDive();
                    return false;
                }
            }
            default -> {
                endDive();
                return false;
            }
        }
        setOwnMotion(this.dvx, this.dvy, this.dvz);
        return true;
    }

    /** 列へ戻る: 土台の飛び方に今の向きと速さを渡す(隊列から離れていれば土台がまっすぐ追いつく)。 */
    private void endDive() {
        if (this.dive == DIVE_FALL || this.dive == DIVE_WINDUP_P) {
            // 放さないまま終わる(見失った)ときは爆弾を出さない
        }
        this.dive = DIVE_NONE;
        this.diveTicks = 0;
        this.diveTarget = null;
        this.entityData.set(DATA_DIVE, (byte) DIVE_NONE);
        this.entityData.set(DATA_DIVE_TARGET, -1);
        this.body.heading = this.getYRot();
        this.body.speed = 0.6D;
        this.body.vy = 0.1D;
        this.body.turnRate = 0.0D;
    }

    /** 翼の裏の胞子嚢から爆弾を放す(真下へ、今の速さの半分を持って落ちる)。 */
    private void dropBomb(net.minecraft.server.level.ServerLevel sl) {
        VelophyllBomb b = new VelophyllBomb(sl, this, this.position().add(0.0D, 0.2D, 0.0D));
        b.setDeltaMovement(this.dvx * 0.5D, Math.min(this.dvy, 0.0D) * 0.5D - 0.2D, this.dvz * 0.5D);
        sl.addFreshEntity(b);
        bombsDropped++;
        lastBombAt = b.position();
        if (DEV) LOG.info("VELO_BOMB {} が放した: 位置 {} {} {}・相手との水平の距離 {}", this.getId(), String.format("%.1f", this.getX()), String.format("%.1f", this.getY()), String.format("%.1f", this.getZ()),
                this.diveTarget == null ? "-" : String.format("%.1f", Math.sqrt(Math.pow(this.diveTarget.getX() - this.getX(), 2) + Math.pow(this.diveTarget.getZ() - this.getZ(), 2))));
    }

    private static final boolean DEV = Boolean.getBoolean("ov.devbridge");
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** 計測・テスト用: 放した爆弾の数(サーバー全体)。 */
    public static volatile int bombsDropped;
    /** 計測・テスト用: 最後に爆弾を放した所。 */
    public static volatile net.minecraft.world.phys.Vec3 lastBombAt = net.minecraft.world.phys.Vec3.ZERO;

    /** 叩かれたら叩いた相手を群れで狙う(止まり木で叩かれて飛び立ったときも)。 */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean hit = super.hurt(source, amount);
        if (hit && !this.level().isClientSide && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity a && validTarget(a)) {
            VelophyllEntity l = leaderOrSelf();
            if (!l.targetIsAttacker || l.flockTarget == null) {
                l.flockTarget = a;
                l.targetIsAttacker = true;
                l.targetSeen = this.level().getGameTime();
            }
        }
        return hit;
    }

    /** 死んだら急降下をやめる(影を消す)。 */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        if (this.dive != DIVE_NONE) {
            this.dive = DIVE_NONE;
            this.entityData.set(DATA_DIVE, (byte) DIVE_NONE);
            this.entityData.set(DATA_DIVE_TARGET, -1);
        }
        super.die(source);
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", ENTRY_FLY, this::mainState));
    }

    /**
     * つなぎを選ぶ。入りの補間は流し始めにだけ使い、それを過ぎたら 0 にする(つないだ段の間は差 0。GeckoLib は段の間でも補間の長さを使うので)。
     */
    private PlayState mainState(AnimationState<VelophyllEntity> state) {
        int m = mode();
        RawAnimation want;
        int entry;
        int dv = diveState();
        if (m == MODE_FLY && dv == DIVE_WINDUP_P) {
            want = DIVE_WINDUP_A;   // 叫び(飛んでいる途中から入る)
            entry = this.curAnim == DIVE_WINDUP_A ? this.curEntry : ENTRY;
        } else if (m == MODE_FLY && dv == DIVE_FALL) {
            want = DIVE_A;
            entry = this.curAnim == DIVE_WINDUP_A ? 0 : (this.curAnim == DIVE_A ? this.curEntry : ENTRY);   // 叫びの終わり = dive の 0 秒(差 0)
        } else if (m == MODE_FLY && (dv == DIVE_RECOVER_P || this.curAnim == DIVE_RECOVER_A && flapping())) {
            want = DIVE_RECOVER_A;   // 落ちる繰り返しの途中から引き起こしへ(短く補間)。その後は fly を繰り返す
            entry = this.curAnim == DIVE_RECOVER_A ? this.curEntry : ENTRY_SHORT;
        } else if (m == MODE_PERCHED || m == MODE_TAKEOFF && takeoffPhase() == 0) {
            if (alerted()) {
                want = KAMAE;
                entry = ENTRY;
            } else if (this.curAnim == LAND || this.curAnim == LAND_RECOVER) {
                want = LAND_RECOVER;   // 止まった tick に掴む(制動の羽ばたきの途中から入るので短く補間)
                entry = this.curAnim == LAND ? ENTRY_SHORT : this.curEntry;
            } else {
                want = PERCH_IDLE;   // セーブから止まった姿で読み込んだ個体は land_recover を通さず直に
                entry = ENTRY;
            }
        } else if (m == MODE_TAKEOFF && takeoffPhase() == 1) {
            want = WINDUP;
            entry = ENTRY_SHORT;
        } else if (m == MODE_TAKEOFF) {
            want = TAKEOFF;
            entry = this.curAnim == WINDUP ? 0 : (this.curAnim == TAKEOFF ? this.curEntry : ENTRY);   // 溜めの終わり = takeoff の 0 秒(差 0)
        } else if (m == MODE_LANDING) {
            want = LAND;
            entry = this.curAnim == LAND ? this.curEntry : ENTRY;
        } else if (this.curAnim == TAKEOFF && flapping()) {
            want = TAKEOFF;   // takeoff の後はそのまま fly を繰り返している
            entry = this.curEntry;
        } else {
            want = flapping() ? FLY : GLIDE;
            entry = ENTRY_FLY;
        }
        if (want != this.curAnim) {
            this.curAnim = want;
            this.curStart = this.tickCount;
            this.curEntry = entry;
        }
        boolean chained = want == TAKEOFF || want == LAND || want == LAND_RECOVER || want == WINDUP
                || want == DIVE_WINDUP_A || want == DIVE_A || want == DIVE_RECOVER_A;
        state.getController().transitionLength(chained && this.tickCount - this.curStart > this.curEntry ? 0 : this.curEntry);
        return state.setAndContinue(want);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
