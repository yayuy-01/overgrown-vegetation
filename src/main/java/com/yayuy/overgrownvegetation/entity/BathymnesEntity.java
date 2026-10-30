package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「バティムネス」— グループ5(適応種)の海の種。カメロケラス(直角殻のオウムガイ類)の姿で、長さ 11 ブロック(2026-10-01)。
 *
 * <h2>ユーザーが決めたこと</h2>
 * <ul>
 *     <li>採用の案(2026-09-24〜26、{@code tools/g5-models/DESIGN.md}): <b>水面のボートを一撃で壊し、プレイヤーを水中へ引きずり込む。
 *     陸に上げるとほぼ動けない</b>。潰す戦術 = ボート移動・泳いで逃げること。対処 = 岸へ釣り出す。</li>
 *     <li>モデル・テクスチャ・アニメは低キューブ版(2026-09-30、ユーザーが見本を見て「よし」)。光の筋なし。G5 の攻撃は前準備を 0.4 秒以上見せる。</li>
 *     <li>G5 の共通: 特殊攻撃は交戦直後に出さず、通常攻撃に時々混ぜる({@link SpecialPacing})。group_5 で武器への適応が効く。</li>
 * </ul>
 * それ以外の数値・細かい動き(速さ・旋回・当たりの半径・時間・ダメージ・湧く深さ)は<b>すべて仮</b>(DEV_NOTES の下書き
 * {@code tools/g5-impl/notes/bathymnes.md} に表)。形とアニメの時刻は {@code tools/g5-models/out/bathymnes/NOTES.md} の実測({@link BathymnesBody})。
 *
 * <h2>流れ</h2>
 * <ul>
 *     <li><b>泳ぐ</b>: 経路探索は使わず、行き先へ体の向き(yaw・ピッチ)を少しずつ回して前へ進む(長さ 11 ブロックなので旋回はゆっくり)。
 *     標的が居なければ深い水の中を漂う({@link WanderGoal})。水面から体を出さない。</li>
 *     <li><b>標的</b>: 水中・水面の、過繁茂側以外の全部(プレイヤー・魚・イカ・動物・敵対 Mob。2026-10-01 夜に「敵対 Mob とプレイヤーだけ」から広げた)。
 *     プレイヤーが居れば先に狙う(仮決め)。ボートに乗った相手も水面の相手に数える({@link WaterTargetGoal})。
 *     陸へ上がった相手は 5 秒だけ追い続ける —— 追って浅瀬に乗り上げれば「岸へ釣り出す」対処になる。殴られたら陸の相手にも反撃する。</li>
 *     <li><b>ram(通常攻撃)</b>: 殻の切っ先を 60° 起こして真下から突き上げる。相手を体の<b>後ろ斜め上</b>に置く位置(相手の下を通り過ぎた先)へ
 *     回り込んでから出す。当たりの線分(殻の手前 15% 〜 切っ先の先)に入った<b>ボートは一撃で壊れ</b>(乗り手は水へ落ちる)、相手は打ち上げられる。</li>
 *     <li><b>grab → drag(特殊攻撃)</b>: 触腕で掴んで({@code startRiding} で乗せる。ファルキムネスの掴みと同じ作り)、獲物を乗せたまま深く潜る。
 *     締めの拍ごとに噛んで息を絞り出す。振りほどき({@link #BREAK_FREE_DAMAGE})・時間切れ・水から出る・死で放す。ボートの乗り手も引き剥がす。</li>
 *     <li><b>陸</b>(体の半分より浅い水・水の外が {@link #BEACH_ENTER_TICKS} tick 続く): beached のループで、水の方へごく遅く這う
 *     ({@link #CRAWL_SPEED}、アニメの腕の先が止まって見える速さ)だけ。攻撃は、掻き寄せの拍に目の前を叩く程度。</li>
 *     <li><b>死</b>: 水中は death(海底まで沈めてから消す)、陸は death_beached。</li>
 * </ul>
 *
 * <h2>当たり判定</h2>
 * 本体の箱(2.5 × 2.5)+ 頭・腕の束・殻の後ろ半分の部位 5 つ({@link BathymnesPart}、エンダードラゴンと同じマルチパート)。
 * 部位は体の向き(yaw・ピッチ)から両側で同じ式({@link BathymnesBody#toWorld})で置く。どこを殴っても体力は共通。
 * 同じ tick の同じダメージ源は 1 回にまとめる(爆発が本体と部位の全部に当たって何倍にもならないように。攻城戦ボスと同じ)。
 *
 * <h2>同期</h2>
 * 動作の番号・始めた時刻・陸にいるかを {@link SynchedEntityData} で送り、アニメ・掴んだ獲物の置き場所はそこから両側で出す。
 * 泳ぐ上下の向きは {@code xRot}(バニラが送る)。動作は {@code aiStep} で進める(AI を止めた GameTest でも動作は進む)。
 */
public class BathymnesEntity extends Monster implements GeoEntity, ParasitizedMob, AquaticOvergrownMob, OvergrownSpawnCheck {

    // ------------------------------------------------------------------ 動作

    public static final int ACT_NONE = 0;
    public static final int ACT_RAM = 1;
    public static final int ACT_GRAB = 2;
    public static final int ACT_DRAG = 3;
    public static final int ACT_DEATH = 4;
    public static final int ACT_DEATH_BEACHED = 5;

    /** 動作を切り替えるときの補間(tick)。GeckoLib は補間が終わってから 0 秒目を流すので、当たりの tick = これ + 秒 × 20。 */
    public static final int TRANSITION_TICKS = 4;
    /** grab の止め → drag の補間(仮)。相手が約 3 ブロック下へ引き下ろされるのを見せる(4 tick だと一瞬で消えて見える)。 */
    public static final int DRAG_TRANSITION_TICKS = 8;

    /** ram(2.4 秒)の長さ。 */
    public static final int RAM_TICKS = TRANSITION_TICKS + 48;
    /** 殻を起こしきる 0.7 秒までは相手に合わせて位置を寄せ続ける。 */
    public static final int RAM_TRACK_UNTIL = TRANSITION_TICKS + 14;
    /** 突き上げの始まり(0.95 秒)。音を鳴らす。 */
    static final int RAM_THRUST_TICK = TRANSITION_TICKS + 19;
    /** 当たり(1.1 秒。切っ先は 1.07 秒で当たりの高さに着き、1.12 秒で最高点)。 */
    public static final int RAM_HIT_TICK = TRANSITION_TICKS + 22;
    /** grab の溜め(0.5 秒)の間は相手に向きと位置を合わせる。 */
    static final int GRAB_TRACK_UNTIL = TRANSITION_TICKS + 10;
    /** grab の当たり(0.7 秒)。 */
    public static final int GRAB_HIT_TICK = TRANSITION_TICKS + 14;
    /** grab の止め(0.92 秒)。掴んでいればここで drag へ。 */
    public static final int GRAB_HOLD_TICK = TRANSITION_TICKS + 18;
    /** grab を外したときの終わり(1.3 秒)。 */
    public static final int GRAB_TICKS = TRANSITION_TICKS + 26;
    /** drag の周期(1.6 秒)と、最初の締めの山(drag の 0.35 秒)。 */
    public static final int DRAG_CYCLE_TICKS = 32;
    public static final int DRAG_FIRST_SQUEEZE = DRAG_TRANSITION_TICKS + 7;
    /** drag の長さの上限(締め 5 回、約 8.7 秒。仮)。 */
    public static final int DRAG_MAX_TICKS = DRAG_FIRST_SQUEEZE + DRAG_CYCLE_TICKS * 5;
    /** beached の周期(2.4 秒)と掻き寄せの山(1.25 秒)。 */
    static final int BEACHED_CYCLE_TICKS = 48;
    static final int BEACHED_SWEEP_AT = 25;
    /** death(2.6 秒)+ 少しの余韻。 */
    static final int DEATH_TICKS = TRANSITION_TICKS + 52 + 8;
    /** death_beached(1.8 秒)+ 少しの余韻。 */
    static final int DEATH_BEACHED_TICKS = TRANSITION_TICKS + 36 + 6;
    /** death の最後の噴射(〜0.8 秒)が終わってから沈める。 */
    static final int DEATH_SINK_FROM = TRANSITION_TICKS + 16;
    /** 沈みきらなくてもこれで消す(深い海で死体が残り続けないように。仮)。 */
    static final int DEATH_MAX_TICKS = 200;

    // ------------------------------------------------------------------ 数値(すべて仮)

    /** 追う速さ = 移動の速さ(mobs.json 0.9)× これ ≒ 0.405 ブロック/tick(約 8 ブロック/秒。ボートの最高速と同じくらい)。 */
    static final double CHASE_SPEED_FACTOR = 0.45D;
    /** 漂う速さ(同 ≒ 0.11 ブロック/tick)。 */
    static final double WANDER_SPEED_FACTOR = 0.12D;
    /** 動作の溜めの間に相手へ寄せる速さの上限(ブロック/tick)。 */
    static final double TRACK_SPEED = 0.15D;
    /** drag で潜る速さ(ブロック/tick)。 */
    static final double DRAG_DIVE_SPEED = 0.08D;
    /** 陸で這う速さ(ブロック/tick、体格 2 のとき)。NOTES の「接地した腕の先が止まって見える速さ」0.021。GameTest も引く。 */
    public static final double CRAWL_SPEED = 0.021D;
    /** 速度を狙いの速度へ寄せる割合(1 tick)。大きな体なので加減速はゆっくり。 */
    static final double SWIM_ACCEL = 0.12D;
    /** 泳ぐ向きの変化の上限(度/tick)。長さ 11 ブロックなので毎秒 70 度。 */
    static final float SWIM_YAW_TURN = 3.5F;
    static final float SWIM_PITCH_TURN = 2.0F;
    /** 泳ぐ上下の向きの上限(度)。 */
    static final float MAX_SWIM_PITCH = 40.0F;
    /** 動作の溜めの間の旋回(度/tick)。grab は相手へ向き直る。 */
    static final float ACTION_YAW_TURN = 1.5F;
    /** 陸で水の方へ向き直る速さ(度/tick)。 */
    static final float CRAWL_YAW_TURN = 0.6F;
    /** 攻撃の後、次の攻撃へ寄り始めるまで(tick)。 */
    static final int COOLDOWN_AFTER_RAM = 30;
    static final int COOLDOWN_AFTER_MISS = 40;
    static final int COOLDOWN_AFTER_DRAG = 60;
    /** grab を出してから次の grab まで(tick)。 */
    static final int GRAB_COOLDOWN = 200;
    /** 掴まれている間にこれだけ体力を削られたら放す(ファルキムネスは体力 50 に 15。体力 160 に 25)。 */
    public static final float BREAK_FREE_DAMAGE = 25.0F;
    /** drag の間、獲物の頭が固いブロックに埋まったままこれだけ続いたら放す(tick。窒息させ続けない)。 */
    static final int PREY_IN_WALL_RELEASE = 10;
    /** 締めの噛みつき = 攻撃力 × これ(14 × 0.4 = 5.6)。 */
    static final float BITE_DAMAGE_FACTOR = 0.4F;
    /** 締めの拍ごとに獲物の息をこれだけ絞り出す(tick。プレイヤーの息は 300)。締め 4 回で溺れ始める。 */
    static final int SQUEEZE_AIR_DRAIN = 60;
    /** 陸で目の前を叩く = 攻撃力 × これ。 */
    static final float BEACHED_SWEEP_FACTOR = 0.5F;
    /** ram で打ち上げる速さ(ブロック/tick)。 */
    static final double RAM_LAUNCH_UP = 0.6D;
    /** 陸に上がったとみなす水の深さ(体の高さに対する割合)と、泳ぎに戻る深さ。体格 2 で 0.9 / 1.3 ブロック。 */
    static final double BEACH_ENTER_RATIO = 0.36D;
    static final double BEACH_EXIT_RATIO = 0.52D;
    /** 浅い水・水の外がこれだけ続いたら陸(一瞬の浅瀬・水面の揺れで切り替えない)。GameTest も引く。 */
    public static final int BEACH_ENTER_TICKS = 10;
    /** 陸へ上がった標的をこれだけ追い続ける(tick)。 */
    static final int OUT_OF_WATER_GRACE = 100;
    /** 寄り続けてこれだけ攻撃を出せなかったら、通常攻撃を 1 回出したとして数える(SpecialPacing。ファルキムネスの 5 秒と同じ)。 */
    static final int PURSUIT_COUNTS_AS_NORMAL = 100;
    /** 位置取りの計画(ram / grab と回り込む向き)を立て直す間隔。 */
    static final int PLAN_MAX_TICKS = 200;
    /** 海底を探す深さ(ブロック)。 */
    static final int FLOOR_SCAN = 24;
    /** 陸で水を探す半径(ブロック)と間隔(tick)。 */
    static final int WATER_SEARCH_RADIUS = 10;
    static final int WATER_SEARCH_INTERVAL = 40;
    /** 泳ぐアニメを流し始める 1 tick の移動(ブロック)と、速さ 1 で流す移動。 */
    static final double SWIM_ANIM_MIN = 0.03D;
    static final double SWIM_ANIM_REF = 0.2D;

    static final int PLAN_RAM = 0;
    static final int PLAN_GRAB = 1;

    private static final EntityDataAccessor<Integer> DATA_ACTION =
            SynchedEntityData.defineId(BathymnesEntity.class, EntityDataSerializers.INT);
    /** 同じ動作を出し直したことをクライアントへ知らせる番号。 */
    private static final EntityDataAccessor<Integer> DATA_SEQ =
            SynchedEntityData.defineId(BathymnesEntity.class, EntityDataSerializers.INT);
    /** 動作を始めたゲーム時刻(アニメと掴んだ獲物の置き場所を両側で同じに出す)。 */
    private static final EntityDataAccessor<Integer> DATA_ACTION_TIME =
            SynchedEntityData.defineId(BathymnesEntity.class, EntityDataSerializers.INT);
    /** 陸に上がっているか。 */
    private static final EntityDataAccessor<Boolean> DATA_BEACHED =
            SynchedEntityData.defineId(BathymnesEntity.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation SWIM = RawAnimation.begin().thenLoop("swim");
    private static final RawAnimation RAM = RawAnimation.begin().thenPlayAndHold("ram");
    private static final RawAnimation GRAB = RawAnimation.begin().thenPlayAndHold("grab");
    private static final RawAnimation DRAG = RawAnimation.begin().thenLoop("drag");
    private static final RawAnimation BEACHED = RawAnimation.begin().thenLoop("beached");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("death");
    private static final RawAnimation DEATH_BEACHED = RawAnimation.begin().thenPlayAndHold("death_beached");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private final BathymnesPart[] parts;
    private final SpecialPacing pacing = SpecialPacing.standard();
    /**
     * 水の相手の索敵(サーバー)。<b>初期値を書かない</b> —— {@code registerGoals} は親のコンストラクタの中で呼ばれるので、
     * 初期値のあるフィールドだと、その後のこのクラスの初期化で null に戻される。
     */
    @Nullable
    private WaterTargetGoal waterTargetGoal;

    /** サーバー: 今の動作の経過 tick。 */
    private int actionTicks;
    private int attackCooldown = 40;
    private int grabCooldown = 100;
    /** 掴んでいる間に削られた体力。 */
    private float holdDamage;
    /** 自分で獲物を放すときだけ立てる({@link Events#onMount} が降車を止めないように)。 */
    private boolean releasing;
    /** drag の間、獲物の頭が壁に埋まっている tick。 */
    private int preyInWallTicks;
    private int lowWaterTicks;
    private int beachedTicks;

    // 泳ぎの舵(サーバー。Goal と動作がその tick に決め、travel が使う)
    @Nullable
    private Vec3 steerGoal;
    private double steerSpeed;
    private float steerFaceYaw = Float.NaN;
    private float steerTurn = SWIM_YAW_TURN;
    private boolean steerDirect;
    private boolean steerSet;
    /**
     * 泳ぐ速度(サーバー)。{@code getDeltaMovement} の外に持つ —— バニラは aiStep の頭で 0.003 未満の成分を 0 にするので、
     * 少しずつ寄せる速度はそこで消える(メモリ vanilla-velocity-cutoff)。
     */
    private Vec3 swimVel = Vec3.ZERO;
    private int blockedTicks;

    // 位置取りの計画(サーバー)
    private int planKind = PLAN_RAM;
    private float planYaw;
    private int planAge = PLAN_MAX_TICKS + 1;
    private int pursuitTicks;
    private double planFloor = Double.NaN;
    private double diveFloor = Double.NaN;
    /** 追跡中に詰まったときの逃げ先と残り tick。 */
    private Vec3 detourGoal = Vec3.ZERO;
    private int detourTicks;

    // 陸(サーバー)
    @Nullable
    private Vec3 crawlTarget;
    private int crawlSearch;

    // 同じ tick の同じダメージ源を 1 回にまとめる(サーバー)
    @Nullable
    private DamageSource lastHitSource;
    private long lastHitTick = Long.MIN_VALUE;

    /** クライアント: 見えている動作の番号(同じ動作の出し直しでアニメを最初から流す)。 */
    private int clientSeq;
    /** クライアント: いま swim を流しているか(idle との切り替えに幅を持たせる)。 */
    private boolean clientSwimming;

    public BathymnesEntity(EntityType<? extends BathymnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("bathymnes", 60);
        this.lookControl = new FixedLookControl(this);
        this.parts = new BathymnesPart[BathymnesBody.PARTS.length];
        for (int i = 0; i < this.parts.length; i++) {
            this.parts[i] = new BathymnesPart(this, i, (float) (BathymnesBody.PARTS[i][3] * 2.0D / 16.0D));
        }
        // 部位の id を本体の続きに取っておく(エンダードラゴンと同じ。NeoForge の MC-158205 の直し)。
        this.setId(ENTITY_COUNTER.getAndAdd(this.parts.length + 1) + 1);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), "bathymnes");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, ACT_NONE);
        builder.define(DATA_SEQ, 0);
        builder.define(DATA_ACTION_TIME, 0);
        builder.define(DATA_BEACHED, false);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new HuntGoal(this));
        this.goalSelector.addGoal(5, new WanderGoal(this));

        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        this.waterTargetGoal = new WaterTargetGoal(this);
        this.targetSelector.addGoal(2, this.waterTargetGoal);
    }

    /**
     * 水の相手を探す箱(サーバー。GameTest 用)。上下も水平と同じだけ広い({@link WaterTargetGoal#getTargetSearchArea})。
     * クライアントでは Goal を作らないので null。
     */
    @Nullable
    public AABB targetSearchArea() {
        return this.waterTargetGoal == null ? null : this.waterTargetGoal.searchArea();
    }

    /** 体は常に {@code yRot} の向き。バニラは動く向きへ体を回してしまう。 */
    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                Mob mob = BathymnesEntity.this;
                mob.yBodyRot = mob.getYRot();
                mob.yHeadRot = mob.getYRot();
            }
        };
    }

    // ------------------------------------------------------------------ 部位(マルチパート)

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public PartEntity<?>[] getParts() {
        return this.parts;
    }

    /** GameTest 用(書き換えない)。 */
    public BathymnesPart[] getBodyParts() {
        return this.parts;
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        if (this.parts != null) {
            for (int i = 0; i < this.parts.length; i++) {
                this.parts[i].setId(id + i + 1);
            }
        }
    }

    /** 部位を体の向きから置き直す(サーバー・クライアントの両方の tick で。式は {@link BathymnesBody#toWorld})。 */
    private void positionParts() {
        double s = this.getScale();
        float yaw = this.getYRot();
        float pitch = this.getXRot();
        for (int i = 0; i < this.parts.length; i++) {
            double[] p = BathymnesBody.PARTS[i];
            float edge = (float) (p[3] * s / 16.0D);
            BathymnesPart part = this.parts[i];
            part.setSize(edge);
            Vec3 off = BathymnesBody.toWorld(p[0], p[1], p[2], yaw, pitch, s);
            double x = this.getX() + off.x;
            double y = this.getY() + off.y - edge * 0.5D;
            double z = this.getZ() + off.z;
            part.xo = part.getX();
            part.yo = part.getY();
            part.zo = part.getZ();
            part.xOld = part.getX();
            part.yOld = part.getY();
            part.zOld = part.getZ();
            part.setPos(x, y, z);
        }
    }

    @Override
    public void tick() {
        super.tick();
        positionParts();
    }

    // ------------------------------------------------------------------ 状態

    public int getAction() {
        return this.entityData.get(DATA_ACTION);
    }

    public boolean isActing() {
        int a = getAction();
        return a == ACT_RAM || a == ACT_GRAB || a == ACT_DRAG;
    }

    public boolean isBeached() {
        return this.entityData.get(DATA_BEACHED);
    }

    /** 獲物を掴んでいるか(grab の当たりの後〜drag)。自分で放している最中は false。 */
    public boolean isHoldingPrey() {
        int a = getAction();
        return (a == ACT_GRAB || a == ACT_DRAG) && this.isVehicle() && !this.releasing;
    }

    /** 大技の出し方の勘定(GameTest 用。書き換えない)。 */
    public SpecialPacing getSpecialPacing() {
        return this.pacing;
    }

    /** 今の動作を始めてからの経過(tick、partialTick 込み)。両側で使う。 */
    public float actionAge(float partialTick) {
        // int どうしで引く(ゲーム時刻が int を超えても、送った int と同じところで折り返すので差は正しい)
        return (float) ((int) this.level().getGameTime() - this.entityData.get(DATA_ACTION_TIME)) + partialTick;
    }

    private void startAction(int action) {
        switch (action) {
            case ACT_RAM -> this.pacing.onNormalAttack();
            case ACT_GRAB -> this.pacing.onSpecial();
            default -> {
            }
        }
        this.entityData.set(DATA_ACTION_TIME, (int) this.level().getGameTime());
        this.entityData.set(DATA_ACTION, action);
        this.entityData.set(DATA_SEQ, this.entityData.get(DATA_SEQ) + 1);
        this.actionTicks = 0;
        this.pursuitTicks = 0;
    }

    private void endAction(int cooldown) {
        this.entityData.set(DATA_ACTION, ACT_NONE);
        this.actionTicks = 0;
        this.attackCooldown = cooldown;
        this.planAge = PLAN_MAX_TICKS + 1;
    }

    // ------------------------------------------------------------------ 毎 tick

    /**
     * 動作は {@code aiStep} で進める({@code customServerAiStep} ではなく)ので、AI を止めた個体(GameTest)でも動作は進む。
     * 動作は AI(Goal)と移動({@link #travel})より<b>前</b>に進める —— 動作が決めた舵を同じ tick の移動で使うため。
     */
    @Override
    public void aiStep() {
        if (!this.level().isClientSide() && this.isAlive()) {
            updateBeached();
            if (this.attackCooldown > 0) this.attackCooldown--;
            if (this.grabCooldown > 0) this.grabCooldown--;
            // 交戦の勘定(標的が替わってもやり直さない。10 秒見失ったらやり直す —— SpecialPacing の javadoc)。
            this.pacing.tick(this.getTarget());
            // 動作は保存しないが、乗客(掴んだ獲物)は保存される。読み込み直すと掴んでいない状態で乗せたままになるので降ろす。
            // 掴んでいる動作の外で乗っているものは全部これ(スポーンエッグの乗り物遊び・/ride も)。
            if (!(getAction() == ACT_GRAB || getAction() == ACT_DRAG) && this.isVehicle()) {
                releaseAll();
            }
            if (isActing()) {
                this.actionTicks++;
                tickAction();
            } else if (isBeached()) {
                tickBeached();
            }
        }
        super.aiStep();
    }

    /** 陸に上がったか(浅い水・水の外が続いたら)。泳ぎに戻るのは体の半分ほどの深さに戻ってから(行ったり来たりしない)。 */
    private void updateBeached() {
        double fluid = this.getFluidTypeHeight(NeoForgeMod.WATER_TYPE.value());
        double h = this.getBbHeight();
        if (!isBeached()) {
            if (fluid < h * BEACH_ENTER_RATIO) {
                if (++this.lowWaterTicks >= BEACH_ENTER_TICKS) {
                    setBeached(true);
                }
            } else {
                this.lowWaterTicks = 0;
            }
        } else if (fluid >= h * BEACH_EXIT_RATIO) {
            setBeached(false);
        }
    }

    private void setBeached(boolean beached) {
        this.entityData.set(DATA_BEACHED, beached);
        this.lowWaterTicks = 0;
        this.beachedTicks = 0;
        this.crawlSearch = 0;
        this.crawlTarget = null;
        if (beached) {
            // 陸では何もできない。掴んでいれば放す。
            int act = getAction();
            if (act == ACT_DRAG || (act == ACT_GRAB && this.isVehicle())) {
                releasePrey(false);
            } else if (act == ACT_RAM || act == ACT_GRAB) {
                endAction(COOLDOWN_AFTER_MISS);
            }
        }
    }

    private void tickAction() {
        switch (getAction()) {
            case ACT_RAM -> tickRam();
            case ACT_GRAB -> tickGrab();
            case ACT_DRAG -> tickDrag();
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ ram(通常攻撃)

    /** テスト・デバッグ用: 標的を決めて、すぐ ram を始める(位置取り・間合いの条件は見ない)。 */
    public void forceRam(@Nullable LivingEntity target) {
        if (target != null) {
            this.setTarget(target);
        }
        startAction(ACT_RAM);
        this.playSound(SoundEvents.ELDER_GUARDIAN_AMBIENT, 2.0F, 0.5F);
    }

    private void startRam() {
        startAction(ACT_RAM);
        // 溜めの合図(低いうなり)。前準備は殻を起こす 0.7 秒 + 沈む 0.25 秒。
        this.playSound(SoundEvents.ELDER_GUARDIAN_AMBIENT, 2.0F, 0.5F);
    }

    private void tickRam() {
        int t = this.actionTicks;
        LivingEntity target = this.getTarget();
        if (t <= RAM_TRACK_UNTIL && target != null && target.isAlive()) {
            // 殻を起こしている間は、動く相手(進むボート)に合わせて位置を寄せる。向きは変えない。
            Vec3 o = ramOriginFor(target, this.getYRot());
            steer(o, TRACK_SPEED, this.getYRot(), true, 0.0F);
        } else {
            hold();
        }
        if (t == RAM_THRUST_TICK) {
            this.playSound(SoundEvents.TRIDENT_RIPTIDE_1.value(), 2.0F, 0.6F);
        }
        if (t == RAM_HIT_TICK) {
            ramHit();
        }
        if (t >= RAM_TICKS) {
            endAction(COOLDOWN_AFTER_RAM);
        }
    }

    /**
     * 当たり: 殻の線分({@link BathymnesBody#ramHitStart} 〜 {@link BathymnesBody#ramHitEnd})の近くのボートを壊し(乗り手は水へ落ちる)、
     * 相手を打ち上げる。この 1 tick だけ箱で探す。
     */
    private void ramHit() {
        double s = this.getScale();
        float yaw = this.getYRot();
        Vec3 a = this.position().add(BathymnesBody.ramHitStart(yaw, s));
        Vec3 b = this.position().add(BathymnesBody.ramHitEnd(yaw, s));
        double r = BathymnesBody.ramHitRadius(s);
        AABB box = new AABB(a, b).inflate(r + 2.0D);
        Set<Entity> struck = new HashSet<>();
        boolean brokeBoat = false;
        List<Boat> boats = this.level().getEntitiesOfClass(Boat.class, box,
                boat -> boat.isAlive() && BathymnesBody.segmentDistance(center(boat), a, b) <= r + boat.getBbWidth() * 0.5D);
        for (Boat boat : boats) {
            List<Entity> riders = new ArrayList<>(boat.getPassengers());
            // 一撃で壊す(VehicleEntity は削れ ×10 が 40 を超えると壊れる。落とし物はバニラの規則どおり)。
            boat.hurt(this.damageSources().mobAttack(this), 1000.0F);
            brokeBoat |= !boat.isAlive();
            for (Entity rider : riders) {
                if (rider instanceof LivingEntity living && isFoe(living) && struck.add(living)) {
                    strike(living);
                } else {
                    launch(rider);
                }
            }
        }
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> isFoe(e) && BathymnesBody.segmentDistance(center(e), a, b) <= r + e.getBbWidth() * 0.5D)) {
            if (struck.add(e)) {
                strike(e);
            }
        }
        if (this.level() instanceof ServerLevel server) {
            for (int i = 0; i <= 6; i++) {
                Vec3 p = a.lerp(b, i / 6.0D);
                server.sendParticles(ParticleTypes.BUBBLE, p.x, p.y, p.z, 6, 0.4D, 0.4D, 0.4D, 0.05D);
            }
            server.sendParticles(ParticleTypes.SPLASH, b.x, b.y, b.z, 30, 1.0D, 0.3D, 1.0D, 0.2D);
        }
        this.level().playSound(null, b.x, b.y, b.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 2.0F, 0.6F);
        if (brokeBoat) {
            this.level().playSound(null, b.x, b.y, b.z, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 1.5F, 0.8F);
        }
    }

    private void strike(LivingEntity e) {
        this.doHurtTarget(e);
        launch(e);
    }

    /** 殻の向き(後ろ上)へ打ち上げる。 */
    private void launch(Entity e) {
        Vec3 f = BathymnesBody.forward(this.getYRot());
        e.setDeltaMovement(e.getDeltaMovement().add(-f.x * 0.3D, RAM_LAUNCH_UP, -f.z * 0.3D));
        e.hurtMarked = true;
    }

    /** 今の位置・向きから ram を出したら当たるか(当たりの tick にも同じ線分で見る。動く相手のぶん少し甘く)。 */
    boolean ramWouldHit(LivingEntity target) {
        double s = this.getScale();
        float yaw = this.getYRot();
        Vec3 a = this.position().add(BathymnesBody.ramHitStart(yaw, s));
        Vec3 b = this.position().add(BathymnesBody.ramHitEnd(yaw, s));
        Entity body = target.getVehicle() instanceof Boat boat ? boat : target;
        return BathymnesBody.segmentDistance(center(body), a, b)
                <= BathymnesBody.ramHitRadius(s) + body.getBbWidth() * 0.5D - 0.2D;
    }

    /** ram の位置取りの原点(相手を体の後ろ斜め上に置く。海底より下げない)。GameTest からも使う。 */
    public Vec3 ramOriginFor(LivingEntity target, float yaw) {
        Vec3 aim = aimPoint(target);
        Vec3 f = BathymnesBody.forward(yaw);
        double back = BathymnesBody.RAM_TIP_BACK * BathymnesBody.RAM_AIM_U * this.getScale() / 16.0D;
        if (Double.isNaN(this.planFloor) || this.tickCount % 5 == 0) {
            this.planFloor = scanFloor(aim.x + f.x * back, Math.min(aim.y, this.getY() + 1.0D), aim.z + f.z * back);
        }
        return BathymnesBody.ramOrigin(aim, yaw, this.getScale(), this.planFloor + 0.2D);
    }

    // ------------------------------------------------------------------ grab → drag(特殊攻撃)

    private void startGrab() {
        startAction(ACT_GRAB);
        this.grabCooldown = GRAB_COOLDOWN;
        this.playSound(SoundEvents.SQUID_SQUIRT, 2.0F, 0.5F);
    }

    private void tickGrab() {
        int t = this.actionTicks;
        Entity prey = this.getFirstPassenger();
        if (prey == null) {
            LivingEntity target = this.getTarget();
            if (t <= GRAB_TRACK_UNTIL && target != null && target.isAlive()) {
                float face = yawTo(target);
                steer(grabOriginFor(target, face), TRACK_SPEED, face, true, ACTION_YAW_TURN);
            } else {
                hold();
            }
            if (t == GRAB_HIT_TICK) {
                LivingEntity caught = findGrabbable();
                if (caught != null && catchPrey(caught)) {
                    return;
                }
            }
            if (t >= GRAB_TICKS) {
                endAction(COOLDOWN_AFTER_MISS);
            }
            return;
        }
        hold();
        if (t >= GRAB_HOLD_TICK) {
            startAction(ACT_DRAG);
            this.diveFloor = Double.NaN;
        }
    }

    /** grab の当たり: 掌の中ほどの中点の近くの、掴める相手(狙っている相手を優先)。 */
    @Nullable
    private LivingEntity findGrabbable() {
        double s = this.getScale();
        Vec3 palm = this.position().add(BathymnesBody.grabPoint(this.getYRot(), s));
        double r = BathymnesBody.grabHitRadius(s);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, new AABB(palm, palm).inflate(r + 2.0D),
                e -> isFoe(e) && canCarry(e))) {
            double d = center(e).distanceTo(palm) - e.getBbWidth() * 0.5D;
            if (d > r) {
                continue;
            }
            if (e == this.getTarget()) {
                return e;
            }
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    boolean grabWouldHit(LivingEntity target) {
        if (Math.abs(Mth.wrapDegrees(yawTo(target) - this.getYRot())) > 25.0F || !canCarry(target)) {
            return false;
        }
        double s = this.getScale();
        Vec3 palm = this.position().add(BathymnesBody.grabPoint(this.getYRot(), s));
        return center(target).distanceTo(palm) - target.getBbWidth() * 0.5D <= BathymnesBody.grabHitRadius(s) - 0.2D;
    }

    Vec3 grabOriginFor(LivingEntity target, float yaw) {
        Vec3 aim = center(target);
        Vec3 off = BathymnesBody.grabPoint(yaw, this.getScale());
        if (Double.isNaN(this.planFloor) || this.tickCount % 5 == 0) {
            this.planFloor = scanFloor(aim.x - off.x, Math.min(aim.y, this.getY() + 1.0D), aim.z - off.z);
        }
        return BathymnesBody.grabOrigin(aim, yaw, this.getScale(), this.planFloor + 0.2D);
    }

    /** grab が届く深さか(相手の下に体を置ける)。 */
    boolean grabFeasible(LivingEntity target) {
        if (!canCarry(target)) {
            return false;
        }
        Vec3 aim = center(target);
        double below = BathymnesBody.GRAB_HIT_UP * this.getScale() / 16.0D;
        return aim.y - below >= scanFloor(aim.x, aim.y, aim.z) + 0.2D;
    }

    /**
     * {@code prey} を掴んで乗せる。掴めない相手なら false(何もしない)。ボートの乗り手はボートから引き剥がす
     * ({@code startRiding} が今の乗り物から降ろす)。
     */
    private boolean catchPrey(LivingEntity prey) {
        if (!canCarry(prey) || !prey.startRiding(this, true)) {
            return false;
        }
        this.holdDamage = 0.0F;
        this.preyInWallTicks = 0;
        this.releasing = false;
        this.playSound(SoundEvents.GENERIC_SPLASH, 1.6F, 0.6F);
        return true;
    }

    /**
     * {@code prey} をいますぐ掴んで引き込み(drag)を始める(GameTest・デバッグ用。溜めや間合いの条件は見ない)。掴めない相手なら false。
     */
    public boolean forceGrab(LivingEntity prey) {
        if (!catchPrey(prey)) {
            return false;
        }
        startAction(ACT_DRAG);
        this.diveFloor = Double.NaN;
        return true;
    }

    private void tickDrag() {
        Entity p = this.getFirstPassenger();
        if (!(p instanceof LivingEntity victim) || !victim.isAlive()
                || (victim instanceof Player player && (player.isCreative() || player.isSpectator()))) {
            releasePrey(false);
            return;
        }
        int t = this.actionTicks;
        // 潜る: 海底の少し上まで(獲物の足が海底にめり込まない高さ)。向きは変えない(drag のアニメが鼻先を 16° 下げている)。
        if (Double.isNaN(this.diveFloor) || t % 10 == 0) {
            this.diveFloor = diveTargetY(victim);
        }
        steer(new Vec3(this.getX(), this.diveFloor, this.getZ()), DRAG_DIVE_SPEED, this.getYRot(), true, 0.0F);
        // 獲物は嘴の前 4 ブロックに抱えるので、前の岩・張り出しに頭が埋まることがある(潜る先は海底の高さしか見ていない)。
        // 埋まったまま窒息させ続けない(SPEC の注意。ファルキムネスの既知の問題と同じ種類)。1 tick に数マス読むだけ。
        if (victim.isInWall()) {
            if (++this.preyInWallTicks >= PREY_IN_WALL_RELEASE) {
                releasePrey(false);
                return;
            }
        } else {
            this.preyInWallTicks = 0;
        }
        if (t >= DRAG_FIRST_SQUEEZE && (t - DRAG_FIRST_SQUEEZE) % DRAG_CYCLE_TICKS == 0) {
            squeeze(victim);
            if (!victim.isAlive()) {
                releasePrey(false);
                return;
            }
        }
        if (t >= DRAG_MAX_TICKS) {
            releasePrey(false);
        }
    }

    /** 潜る先の高さ: 本体の下の海底の上、かつ獲物の足が獲物の下の海底より上に来る高さ。 */
    private double diveTargetY(LivingEntity victim) {
        double s = this.getScale();
        double feetOffset = BathymnesBody.DRAG_UP_LOOSE * s / 16.0D - BathymnesBody.PREY_TORSO_RATIO * victim.getBbHeight();
        double floorSelf = scanFloor(this.getX(), this.getY() + 0.5D, this.getZ());
        double floorPrey = scanFloor(victim.getX(), victim.getY() + 0.5D, victim.getZ());
        return Math.max(floorSelf + 0.3D, floorPrey + 0.15D - feetOffset);
    }

    /** 締めの山: 噛んで、息を絞り出す。 */
    private void squeeze(LivingEntity victim) {
        victim.invulnerableTime = 0;
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * BITE_DAMAGE_FACTOR;
        victim.hurt(this.damageSources().mobAttack(this), damage);
        victim.setAirSupply(Math.max(-19, victim.getAirSupply() - SQUEEZE_AIR_DRAIN));
        this.playSound(SoundEvents.EVOKER_FANGS_ATTACK, 1.2F, 0.6F);
        if (this.level() instanceof ServerLevel server) {
            Vec3 c = center(victim);
            server.sendParticles(ParticleTypes.BUBBLE, c.x, c.y, c.z, 16, 0.3D, 0.4D, 0.3D, 0.1D);
            server.sendParticles(ParticleTypes.DAMAGE_INDICATOR, c.x, c.y, c.z, 3, 0.2D, 0.2D, 0.2D, 0.1D);
        }
    }

    /** 獲物を放す。{@code shaken} は振りほどかれたとき(長めの隙)。 */
    private void releasePrey(boolean shaken) {
        Entity prey = this.getFirstPassenger();
        releaseAll();
        if (prey != null) {
            Vec3 f = BathymnesBody.forward(this.getYRot());
            prey.setDeltaMovement(f.x * 0.3D, 0.3D, f.z * 0.3D);
            prey.hurtMarked = true;
        }
        this.holdDamage = 0.0F;
        endAction(shaken ? COOLDOWN_AFTER_DRAG + 20 : COOLDOWN_AFTER_DRAG);
    }

    private void releaseAll() {
        this.releasing = true;
        this.ejectPassengers();
        this.releasing = false;
    }

    /** 掴める相手か(ボス・大きすぎる相手・何かを乗せている相手は掴まない。乗っている相手はボートの乗り手だけ)。 */
    static boolean canCarry(LivingEntity e) {
        return !e.getType().is(Tags.EntityTypes.BOSSES)
                && e.getBbWidth() <= 1.4F && e.getBbHeight() <= 2.4F
                && !e.isVehicle() && (!e.isPassenger() || e.getVehicle() instanceof Boat);
    }

    // ------------------------------------------------------------------ 陸

    /** 陸(サーバー、動作していないとき): 水を探して這う向きを決め、掻き寄せの拍に目の前を叩く。 */
    private void tickBeached() {
        this.beachedTicks++;
        if (--this.crawlSearch <= 0) {
            this.crawlTarget = findWater();
            this.crawlSearch = WATER_SEARCH_INTERVAL;
        }
        int t = this.beachedTicks - TRANSITION_TICKS;
        if (t > 0 && t % BEACHED_CYCLE_TICKS == BEACHED_SWEEP_AT) {
            this.playSound(SoundEvents.ELDER_GUARDIAN_FLOP, 1.2F, 0.6F);
            beachedSweep();
        }
    }

    /** 掻き寄せる腕の届く所(頭の前 1.5〜6 ブロック・左右 2.6 ブロック@体格 2)の相手を叩く。 */
    private void beachedSweep() {
        double k = this.getScale() / 2.0D;
        Vec3 f = BathymnesBody.forward(this.getYRot());
        Vec3 c = this.position().add(f.scale(3.75D * k)).add(0.0D, 0.8D * k, 0.0D);
        AABB box = new AABB(c, c).inflate(3.0D * k, 1.8D * k, 3.0D * k);
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * BEACHED_SWEEP_FACTOR;
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, this::isFoe)) {
            Vec3 d = e.position().subtract(this.position());
            double fw = d.x * f.x + d.z * f.z;
            double lat = Math.abs(d.x * -f.z + d.z * f.x);
            if (fw < 1.5D * k || fw > 6.0D * k || lat > 2.6D * k + e.getBbWidth() * 0.5D) {
                continue;
            }
            e.hurt(this.damageSources().mobAttack(this), damage);
            e.knockback(0.6D, -f.x, -f.z);
        }
    }

    /** 近くの水(半径 {@link #WATER_SEARCH_RADIUS} を 2 マスおきに。足元の高さ ±2)。深い所を少し優先。見つからなければ null。 */
    @Nullable
    private Vec3 findWater() {
        BlockPos base = this.blockPosition();
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        int r = WATER_SEARCH_RADIUS;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r || d2 == 0) {
                    continue;
                }
                for (int dy : new int[]{0, -1, 1, -2}) {
                    pos.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    if (!this.level().getFluidState(pos).is(FluidTags.WATER)) {
                        continue;
                    }
                    boolean deep = this.level().getFluidState(pos.move(0, -1, 0)).is(FluidTags.WATER);
                    double score = d2 - (deep ? 12.0D : 0.0D);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Vec3(base.getX() + dx + 0.5D, base.getY() + dy, base.getZ() + dz + 0.5D);
                    }
                    break;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 位置取り(HuntGoal から)

    /** 標的を追っている間の 1 tick(サーバー、動作していない・水中のとき)。 */
    void tickHunt(LivingEntity target) {
        double s = this.getScale();
        double chase = this.getAttributeValue(Attributes.MOVEMENT_SPEED) * CHASE_SPEED_FACTOR;
        Vec3 aim = aimPoint(target);
        if (this.attackCooldown > 0) {
            // 攻撃の後: 相手の下を通り過ぎた先へ抜けてから向き直る(大きな体なのでその場では回れない)。
            Vec3 f = BathymnesBody.forward(this.planYaw);
            double k = s / 2.0D;
            Vec3 past = aim.add(f.scale(9.0D * k)).subtract(0.0D, 4.0D * k, 0.0D);
            double floor = scanFloorCached(past);
            steer(new Vec3(past.x, Math.max(past.y, floor + 0.5D), past.z), chase * 0.6D, Float.NaN, false, SWIM_YAW_TURN);
            return;
        }
        if (++this.planAge > PLAN_MAX_TICKS) {
            newPlan(target);
        }
        // 岩・海底の斜面に押し付けたまま 2 秒: 1.5 秒だけ横上へ逃げてから寄り直す(経路探索を使わない代わり)
        if (this.detourTicks > 0) {
            this.detourTicks--;
            steer(this.detourGoal, chase * 0.6D, Float.NaN, false, SWIM_YAW_TURN);
            return;
        }
        if (this.blockedTicks > 40) {
            this.blockedTicks = 0;
            Vec3 side = BathymnesBody.forward(this.getYRot() + (this.random.nextBoolean() ? 90.0F : -90.0F));
            this.detourGoal = this.position().add(side.scale(6.0D)).add(0.0D, 3.0D, 0.0D);
            this.detourTicks = 30;
            return;
        }
        if (this.planKind == PLAN_GRAB) {
            float face = yawTo(target);
            steer(grabOriginFor(target, face), chase, face, false, SWIM_YAW_TURN);
            if (this.isInWater() && grabWouldHit(target)) {
                startGrab();
                return;
            }
        } else {
            steer(ramOriginFor(target, this.planYaw), chase, Float.NaN, false, SWIM_YAW_TURN);
            if (this.isInWater() && ramWouldHit(target)) {
                startRam();
                return;
            }
        }
        // 寄り続けても出せない(陸・高い所の相手、振り切ろうとするボート)ときも、通常攻撃を出そうとした 1 回として数える。
        if (++this.pursuitTicks >= PURSUIT_COUNTS_AS_NORMAL) {
            this.pursuitTicks = 0;
            this.pacing.onNormalAttack();
        }
    }

    /** 計画を立て直す: 回り込む向き(今の位置から相手へ = 相手の下を通り過ぎて振り返らずに突ける向き)と、ram / grab。 */
    private void newPlan(LivingEntity target) {
        this.planAge = 0;
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        this.planYaw = dx * dx + dz * dz > 4.0D ? yawTo(target) : this.getYRot();
        boolean grab = this.grabCooldown <= 0 && grabFeasible(target) && this.pacing.roll(this.random);
        this.planKind = grab ? PLAN_GRAB : PLAN_RAM;
        this.planFloor = Double.NaN;
    }

    private double scanFloorCached(Vec3 p) {
        if (Double.isNaN(this.planFloor) || this.tickCount % 5 == 0) {
            this.planFloor = scanFloor(p.x, p.y + 4.0D, p.z);
        }
        return this.planFloor;
    }

    /**
     * {@code (x, y, z)} から下へ {@link #FLOOR_SCAN} マスまで見て、最初の固いブロックの上面の高さ。見つからなければ {@code y − FLOOR_SCAN}。
     */
    double scanFloor(double x, double y, double z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        for (int i = 0; i < FLOOR_SCAN; i++) {
            BlockState state = this.level().getBlockState(pos);
            if (!state.getCollisionShape(this.level(), pos).isEmpty()) {
                return pos.getY() + 1.0D;
            }
            pos.move(0, -1, 0);
        }
        return Mth.floor(y) - FLOOR_SCAN;
    }

    // ------------------------------------------------------------------ 舵と移動

    /**
     * その tick の舵を決める。{@code direct} は動作の間(行き先へまっすぐ寄せ、向きは {@code faceYaw} へ {@code turn} 度/tick まで、
     * 上下の向きは 0 へ)。そうでなければ泳ぎ(体の向きを行き先へ回しながら前へ進む。{@code faceYaw} は近くで向きたい向き)。
     */
    void steer(@Nullable Vec3 goal, double speed, float faceYaw, boolean direct, float turn) {
        this.steerGoal = goal;
        this.steerSpeed = speed;
        this.steerFaceYaw = faceYaw;
        this.steerDirect = direct;
        this.steerTurn = turn;
        this.steerSet = true;
    }

    /** その場に留まる(動作の溜め・当たりの後)。 */
    private void hold() {
        steer(this.position(), 0.0D, this.getYRot(), true, 0.0F);
    }

    /**
     * 水中は自前で泳ぐ(重力なし・速度は {@link #swimVel})。陸・浅瀬はバニラの移動(重力・摩擦)に、這う速さだけ渡す。
     * 死んだら水中では海底まで沈める。<b>{@code isControlledByLocalInstance()} の囲みは必須</b>(アクムネスと同じ。無いとクライアントで震える)。
     */
    @Override
    public void travel(Vec3 travelVector) {
        if (!this.isControlledByLocalInstance()) {
            super.travel(travelVector);
            return;
        }
        try {
            if (this.isDeadOrDying()) {
                if (this.isInWater() && getAction() != ACT_DEATH_BEACHED) {
                    sinkTravel();
                    this.calculateEntityAnimation(false);
                } else {
                    super.travel(Vec3.ZERO);
                }
                return;
            }
            if (!isBeached() && this.isInWater()) {
                swimTravel();
                this.calculateEntityAnimation(false);
            } else {
                landTravel();
            }
        } finally {
            this.steerSet = false;
        }
    }

    private void swimTravel() {
        float yaw = this.getYRot();
        float pitch = this.getXRot();
        Vec3 want = Vec3.ZERO;
        Vec3 goal = this.steerSet ? this.steerGoal : null;
        if (goal != null) {
            Vec3 d = goal.subtract(this.position());
            double dist = d.length();
            if (this.steerDirect) {
                if (!Float.isNaN(this.steerFaceYaw)) {
                    yaw = Mth.approachDegrees(yaw, this.steerFaceYaw, this.steerTurn);
                }
                pitch = Mth.approach(pitch, 0.0F, SWIM_PITCH_TURN * 1.5F);
                if (dist > 0.05D && this.steerSpeed > 0.0D) {
                    want = d.scale(Math.min(this.steerSpeed, dist * 0.25D) / dist);
                }
            } else if (dist > 0.3D) {
                double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
                float goalYaw = horiz > 0.5D ? (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F : yaw;
                if (!Float.isNaN(this.steerFaceYaw) && dist < 3.0D) {
                    goalYaw = this.steerFaceYaw;
                }
                float goalPitch = Mth.clamp((float) (-Mth.atan2(d.y, Math.max(horiz, 1.0D)) * Mth.RAD_TO_DEG),
                        -MAX_SWIM_PITCH, MAX_SWIM_PITCH);
                yaw = Mth.approachDegrees(yaw, goalYaw, this.steerTurn);
                pitch = Mth.approachDegrees(pitch, goalPitch, SWIM_PITCH_TURN);
                double speed = Math.min(this.steerSpeed, 0.03D + dist * 0.08D);
                // 向きが大きくずれている間は遅く(長い体でその場を大回りしない)
                double align = Math.cos(Mth.wrapDegrees(goalYaw - yaw) * Mth.DEG_TO_RAD);
                speed *= Mth.clamp(align, 0.25D, 1.0D);
                Vec3 heading = Vec3.directionFromRotation(pitch, yaw);
                // 近くでは行き先へ直接寄せる(旋回が遅いので、そのままだと行き先の周りを回り続ける)
                double w = dist < 4.0D ? 0.6D : 0.15D;
                want = heading.scale(1.0D - w).add(d.scale(w / dist)).normalize().scale(speed);
            }
        } else {
            pitch = Mth.approach(pitch, 0.0F, 1.5F);
        }
        // 水面から体を出さない(体の上の少し上が水でなければ上へは進まない)
        if (want.y > 0.0D && !this.level().getFluidState(BlockPos.containing(this.getX(), this.getY() + this.getBbHeight() + 0.2D,
                this.getZ())).is(FluidTags.WATER)) {
            want = new Vec3(want.x, 0.0D, want.z);
        }
        this.swimVel = this.swimVel.add(want.subtract(this.swimVel).scale(SWIM_ACCEL));
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
        this.setDeltaMovement(this.swimVel);
        this.move(MoverType.SELF, this.swimVel);
        if (this.horizontalCollision) {
            this.swimVel = new Vec3(this.swimVel.x * 0.3D, this.swimVel.y, this.swimVel.z * 0.3D);
            this.blockedTicks++;
        } else {
            this.blockedTicks = Math.max(0, this.blockedTicks - 1);
        }
        if (this.verticalCollision) {
            this.swimVel = new Vec3(this.swimVel.x, 0.0D, this.swimVel.z);
        }
        this.setDeltaMovement(this.swimVel);
    }

    /** 陸・浅瀬: 水の方へ向き直りながら、ごく遅く這う。重力・摩擦はバニラ。 */
    private void landTravel() {
        float yaw = this.getYRot();
        Vec3 dm = this.getDeltaMovement();
        Vec3 toWater = this.crawlTarget == null ? null : this.crawlTarget.subtract(this.position());
        // 這う先(水)に着いたら止まる(浅い水たまりしか無ければそこで待つ。深い水へ戻れば泳ぎに戻る)
        if (toWater != null && !isActing() && toWater.horizontalDistanceSqr() > 1.0D) {
            float want = (float) (Mth.atan2(toWater.z, toWater.x) * Mth.RAD_TO_DEG) - 90.0F;
            yaw = Mth.approachDegrees(yaw, want, CRAWL_YAW_TURN);
            // 水の方(±45°)を向くまではその場で向き直るだけ(逆向きのまま這って水から離れないように)
            boolean facing = Math.abs(Mth.wrapDegrees(want - yaw)) < 45.0F;
            Vec3 f = BathymnesBody.forward(yaw);
            double speed = facing ? CRAWL_SPEED * this.getScale() / 2.0D : 0.0D;
            this.setDeltaMovement(f.x * speed, dm.y, f.z * speed);
        } else {
            this.setDeltaMovement(0.0D, dm.y, 0.0D);
        }
        this.setYRot(yaw);
        this.setXRot(Mth.approach(this.getXRot(), 0.0F, 3.0F));
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
        super.travel(Vec3.ZERO);
        this.swimVel = this.getDeltaMovement();
    }

    /** 水中で死んだ: 最後の噴射(〜0.8 秒)の後、海底まで沈める(death は足元 = 海底の前提で手足を寝かせる。NOTES の 4)。 */
    private void sinkTravel() {
        Vec3 v = this.getDeltaMovement();
        double vy = this.deathTime < DEATH_SINK_FROM ? 0.0D : Math.max(v.y - 0.01D, -0.2D);
        Vec3 nv = new Vec3(v.x * 0.8D, vy, v.z * 0.8D);
        this.setXRot(Mth.approach(this.getXRot(), 0.0F, 3.0F));
        this.setDeltaMovement(nv);
        this.move(MoverType.SELF, nv);
        if (this.verticalCollision) {
            nv = new Vec3(nv.x, 0.0D, nv.z);
        }
        this.setDeltaMovement(nv);
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean onClimbable() {
        return false;
    }

    /** 陸の足音は鳴らさない(這う音は掻き寄せの拍に ELDER_GUARDIAN_FLOP)。 */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
    }

    // ------------------------------------------------------------------ 被弾・倒れる

    /**
     * 同じ tick の同じダメージ源は 1 回にまとめる(爆発は 1 つのダメージ源で本体と部位の全部を殴る。攻城戦ボスの {@code hurtFromHitbox} と同じ)。
     * 掴んでいる間に削られた量が {@link #BREAK_FREE_DAMAGE} に届いたら放す(中から殴っても外から殴っても数える)。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide()) {
            long now = this.level().getGameTime();
            if (now == this.lastHitTick && source == this.lastHitSource) {
                return false;
            }
            this.lastHitTick = now;
            this.lastHitSource = source;
        }
        float before = this.getHealth();
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide() && isHoldingPrey()) {
            this.holdDamage += Math.max(0.0F, before - this.getHealth());
            if (this.holdDamage >= BREAK_FREE_DAMAGE && this.isAlive()) {
                releasePrey(true);
            }
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        boolean wasDead = this.dead;
        super.die(source);
        // LivingDeathEvent で取り消された(ほかの MOD など)ときは何もしない —— 動作を死に替えると生きたまま death が流れる。
        if (!this.dead || wasDead) {
            return;
        }
        if (!this.level().isClientSide()) {
            startAction(isBeached() ? ACT_DEATH_BEACHED : ACT_DEATH);
        }
        // 倒れるアニメの間ぶら下げたままにしない。
        releaseAll();
    }

    /** 倒れるアニメの間は消さない。水中は海底に着くまで(上限 {@link #DEATH_MAX_TICKS})。横倒しの回転は描画で切ってある。 */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.level().isClientSide() || this.isRemoved()) {
            return;
        }
        boolean beached = getAction() == ACT_DEATH_BEACHED;
        int need = beached ? DEATH_BEACHED_TICKS : DEATH_TICKS;
        boolean settled = beached || this.onGround() || !this.isInWater() || this.verticalCollisionBelow;
        if ((this.deathTime >= need && settled) || this.deathTime >= DEATH_MAX_TICKS) {
            this.level().broadcastEntityEvent(this, (byte) 60);
            this.remove(RemovalReason.KILLED);
        }
    }

    // ------------------------------------------------------------------ 乗客(掴んだ獲物)

    /**
     * 掴んだ獲物の胴を、grab の掌の中点 → drag の嘴の前へ置く(NOTES の実測。{@link BathymnesBody#preyTorso})。
     * 両側で同期した動作の時刻から同じ式で出す。獲物は乗客なので、プレイヤーを毎 tick テレポートさせずに済む。
     */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float partialTick) {
        double s = this.getScale();
        boolean dragging = getAction() == ACT_DRAG;
        double[] torso = BathymnesBody.preyTorso(dragging, actionAge(0.0F), GRAB_HIT_TICK, GRAB_HOLD_TICK, DRAG_TRANSITION_TICKS);
        Vec3 p = BathymnesBody.toWorld(0.0D, torso[1], -torso[0], this.getYRot(), this.getXRot(), s);
        double up = p.y - BathymnesBody.PREY_TORSO_RATIO * passenger.getBbHeight() + passenger.getVehicleAttachmentPoint(this).y;
        return new Vec3(p.x, up, p.z);
    }

    /** 掴まれている獲物に操縦させない。 */
    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }

    /** 掴まれたプレイヤーが中から殴れるように(乗り物は普通は狙えない)。 */
    @Override
    public boolean canRiderInteract() {
        return true;
    }

    /** 掴まれた獲物は座らせない(抱えられている)。 */
    @Override
    public boolean shouldRiderSit() {
        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().isEmpty();
    }

    /**
     * 放した獲物は抱えていた所(嘴の前)に残す。バニラの既定は乗り物の箱の上の真ん中で、長い体だと嘴の前から
     * 4 ブロック後ろの背中の上へ跳んで見える。抱えていた所が岩に埋まっているとき(drag の途中で壁に埋まって放した)だけ既定へ。
     */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if (this.level().noCollision(passenger, passenger.getBoundingBox())) {
            return passenger.position();
        }
        return super.getDismountLocationForPassenger(passenger);
    }

    // ------------------------------------------------------------------ 判定の小物

    /** 攻撃してよい相手か(自分・乗客・汚染側の仲間・クリエイティブ/観戦者・防具立ては除く)。体当たり・掴み・陸の掻き寄せの当たりに使う。 */
    public boolean isFoe(LivingEntity e) {
        if (e == this || !e.isAlive() || e.getVehicle() == this || e instanceof ArmorStand) {
            return false;
        }
        if (e instanceof Player p && (p.isCreative() || p.isSpectator())) {
            return false;
        }
        if (e == this.getTarget()) {
            return true;
        }
        return isPrey(this, e);
    }

    /**
     * 狙う・当ててよい相手の種類(水にいるかは見ない): 過繁茂側(グループ 1〜10・汚染シダ・レイドボス)以外の全部
     * ({@link Predation#isInvaderTarget}。陸の G5 の {@code ParasitizedTargetGoal} と同じ範囲)。防具立ては除く。
     * 2026-10-01 夜にユーザー「バティムネスが他のmobを攻撃しない」: 前はプレイヤーと敵対 Mob({@link Predation#isIntruder})だけで、
     * 魚・イカ・イルカ・水に入った動物や村人を狙わなかった。
     */
    public static boolean isPrey(LivingEntity self, LivingEntity candidate) {
        return !(candidate instanceof ArmorStand) && Predation.isInvaderTarget(self, candidate);
    }

    /** 狙う点: ボートの乗り手ならボートの船体、それ以外は胴。 */
    public static Vec3 aimPoint(LivingEntity target) {
        if (target.getVehicle() instanceof Boat boat) {
            return center(boat);
        }
        return center(target);
    }

    static Vec3 center(Entity e) {
        return e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
    }

    /**
     * 標的にする「水の相手」か: 水中・水面(体の一部が水か、足元・その 1 つ下が水)。ボートの乗り手はボートで見る
     * (乗り手は水に触れていない扱いになるので。陸に置いたボートの乗り手は数えない)。
     */
    static boolean inWaterForHunt(LivingEntity e) {
        if (!(e.getVehicle() instanceof Boat boat)) {
            return AquaticOvergrownMob.inWaterOrAtSurface(e);
        }
        if (boat.isInWater()) {
            return true;
        }
        BlockPos feet = boat.blockPosition();
        return boat.level().getFluidState(feet).is(FluidTags.WATER)
                || boat.level().getFluidState(feet.below()).is(FluidTags.WATER);
    }

    private float yawTo(Entity target) {
        return (float) (Mth.atan2(target.getZ() - this.getZ(), target.getX() - this.getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }

    // ------------------------------------------------------------------ 湧き

    /**
     * 深い水にだけ湧く(自然湧きの最後の関門)。体が入る深さと広さの水か {@link BathymnesBody#spawnHeight} で見て、
     * 水の中ほど(底寄り)へ置き直す。当たり判定にはまだ体格が掛かっていないので、体格は属性から取る({@link #getScale})。
     */
    @Override
    public boolean acceptOvergrownSpawn(ServerLevel level) {
        double feet = BathymnesBody.spawnHeight(level, this.getX(), Mth.floor(this.getY()), this.getZ(), this.getScale());
        if (Double.isNaN(feet)) {
            return false;
        }
        // 置き直した高さでも体の箱(育った大きさ = 湧き判定の箱)が空いているか(湧かせ役の SpawnFit は元の高さで見ている)
        if (!level.noCollision(this.getType().getSpawnAABB(this.getX(), feet, this.getZ()))) {
            return false;
        }
        this.moveTo(this.getX(), feet, this.getZ(), this.getYRot(), 0.0F);
        // 体格込みの大きさにしてから世界に足す(2026-10-01 点検)。作った直後の箱は素の大きさ(1.25)で、最初の tick に体格 2 が効いて
        // 育つと、バニラの fudgePositionAfterSizeChange が箱の中心を保とうとして足元を 0.625 下げる —— ここで決めた高さがずれる。
        // まだ世界に居ない(firstTick)ので、ここで育てても押し下げは起きない(MobSizeVariance が参加のときに育てるのと同じ)。
        this.refreshDimensions();
        return true;
    }

    // ------------------------------------------------------------------ 音(仮: バニラのエルダーガーディアンを低く鳴らす)

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return isBeached() ? SoundEvents.ELDER_GUARDIAN_AMBIENT_LAND : SoundEvents.ELDER_GUARDIAN_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return isBeached() ? SoundEvents.ELDER_GUARDIAN_HURT_LAND : SoundEvents.ELDER_GUARDIAN_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return isBeached() ? SoundEvents.ELDER_GUARDIAN_DEATH_LAND : SoundEvents.ELDER_GUARDIAN_DEATH;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 200;
    }

    @Override
    public float getVoicePitch() {
        return 0.55F + (this.random.nextFloat() - 0.5F) * 0.1F;
    }

    @Override
    protected float getSoundVolume() {
        return 1.5F;
    }

    // ------------------------------------------------------------------ 描画まわり

    /**
     * 体は長さ 11 ブロックで、当たり判定の箱(2.5)の前へ 4.4・後ろへ 4.1 はみ出す。ram の切っ先は原点の上 7.1、腕は足元の下 3.1 まで、
     * 泳ぐ上下の向き 40° で頭が 3.6 上下する(体格 2 のとき。NOTES)。描画の打ち切りはその外まで広げる。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        double k = this.getScale() / 2.0D;
        AABB box = super.getBoundingBoxForCulling().inflate(4.6D * k, 0.0D, 4.6D * k);
        return box.expandTowards(0.0D, 5.5D * k, 0.0D).expandTowards(0.0D, -4.2D * k, 0.0D);
    }

    // ------------------------------------------------------------------ 保存

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("BathymnesAttackCooldown", this.attackCooldown);
        tag.putInt("BathymnesGrabCooldown", this.grabCooldown);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.attackCooldown = tag.getInt("BathymnesAttackCooldown");
        this.grabCooldown = tag.getInt("BathymnesGrabCooldown");
        // Entity#load は Motion を先に読む(泳ぐ速度をそこから続ける)
        this.swimVel = this.getDeltaMovement();
    }

    // ------------------------------------------------------------------ アニメ

    /**
     * 層は 1 つ(脚が無く、動作の間は泳がない —— ram・grab は溜めの間に少し寄せるだけで、アニメが体ごと動かす)。
     * コントローラは {@link PhaseKeepingAnimationController}: 泳ぐループの速さを実際に進んだ量に合わせる(噴射の拍が速く泳ぐほど速い)。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", TRANSITION_TICKS, this::mainState));
    }

    private PlayState mainState(AnimationState<BathymnesEntity> state) {
        AnimationController<BathymnesEntity> c = state.getController();
        int act = getAction();
        c.transitionLength(act == ACT_DRAG ? DRAG_TRANSITION_TICKS : TRANSITION_TICKS);
        c.setAnimationSpeed(1.0D);
        int seq = this.entityData.get(DATA_SEQ);
        if (seq != this.clientSeq) {
            this.clientSeq = seq;
            if (act != ACT_NONE) {
                c.forceAnimationReset();
            }
        }
        if (this.isDeadOrDying() || act == ACT_DEATH || act == ACT_DEATH_BEACHED) {
            boolean beached = act == ACT_DEATH_BEACHED || (act != ACT_DEATH && isBeached());
            return state.setAndContinue(beached ? DEATH_BEACHED : DEATH);
        }
        switch (act) {
            case ACT_RAM:
                return state.setAndContinue(RAM);
            case ACT_GRAB:
                return state.setAndContinue(GRAB);
            case ACT_DRAG:
                return state.setAndContinue(DRAG);
            default:
                break;
        }
        if (isBeached()) {
            return state.setAndContinue(BEACHED);
        }
        // クライアントの速度は当てにならないので位置の差で見る(メモリ geckolib-ismoving-broken)
        double moved = Math.sqrt(Mth.square(this.getX() - this.xo) + Mth.square(this.getY() - this.yo)
                + Mth.square(this.getZ() - this.zo));
        // 行き先の近くでは 1 tick の移動が 0.03 前後を行き来するので、出入りの値を分けて swim と idle を交互に切り替えない(点検で足した)
        this.clientSwimming = moved > (this.clientSwimming ? SWIM_ANIM_MIN * 0.5D : SWIM_ANIM_MIN);
        if (this.clientSwimming) {
            c.setAnimationSpeed(Mth.clamp(moved / SWIM_ANIM_REF, 0.6D, 1.6D));
            return state.setAndContinue(SWIM);
        }
        return state.setAndContinue(IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ================================================================== AI

    /** 頭だけ回すバニラの見回しを止める(向き・泳ぐ上下の向きは {@link #travel} だけが決める。既定の LookControl は毎 tick xRot を 0 に戻す)。 */
    static final class FixedLookControl extends LookControl {
        FixedLookControl(Mob mob) {
            super(mob);
        }

        @Override
        public void tick() {
        }
    }

    /** 標的を追い、位置を取って ram / grab を出す(動作の最中・陸では動かない)。 */
    static final class HuntGoal extends Goal {
        private final BathymnesEntity mob;

        HuntGoal(BathymnesEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = this.mob.getTarget();
            return t != null && t.isAlive() && !this.mob.isBeached() && !this.mob.isActing();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            this.mob.setAggressive(true);
            this.mob.planAge = PLAN_MAX_TICKS + 1;
        }

        @Override
        public void stop() {
            this.mob.setAggressive(false);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity t = this.mob.getTarget();
            if (t != null && !this.mob.isActing()) {
                this.mob.tickHunt(t);
            }
        }
    }

    /** 標的が居ない間: 深い水の中をゆっくり漂う(行き先は水の中の点だけ)。 */
    static final class WanderGoal extends Goal {
        private final BathymnesEntity mob;
        @Nullable
        private Vec3 point;
        private int ticks;

        WanderGoal(BathymnesEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (this.mob.getTarget() != null || this.mob.isActing() || this.mob.isBeached() || !this.mob.isInWater()) {
                return false;
            }
            if (this.mob.getRandom().nextInt(60) != 0) {
                return false;
            }
            this.point = pick();
            return this.point != null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.point != null && this.mob.getTarget() == null && !this.mob.isActing() && !this.mob.isBeached()
                    && this.ticks < 400 && this.mob.blockedTicks < 30
                    && this.mob.position().distanceTo(this.point) > 2.5D;
        }

        @Override
        public void start() {
            this.ticks = 0;
            this.mob.blockedTicks = 0;
        }

        @Override
        public void stop() {
            this.point = null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            this.ticks++;
            if (this.point != null) {
                double speed = this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * WANDER_SPEED_FACTOR;
                this.mob.steer(this.point, speed, Float.NaN, false, SWIM_YAW_TURN);
            }
        }

        /** 水の中の点(道の 1/3・2/3 と行き先の上下も水)。10 回試して無ければ null。 */
        @Nullable
        private Vec3 pick() {
            Level level = this.mob.level();
            double k = this.mob.getScale() / 2.0D;
            Vec3 from = this.mob.position().add(0.0D, this.mob.getBbHeight() * 0.5D, 0.0D);
            for (int i = 0; i < 10; i++) {
                double dx = (this.mob.getRandom().nextDouble() - 0.5D) * 28.0D;
                double dz = (this.mob.getRandom().nextDouble() - 0.5D) * 28.0D;
                double dy = (this.mob.getRandom().nextDouble() - 0.5D) * 8.0D;
                Vec3 to = from.add(dx, dy, dz);
                if (!water(level, to) || !water(level, to.add(0.0D, 1.5D * k, 0.0D)) || !water(level, to.add(0.0D, -1.5D * k, 0.0D))
                        || !water(level, from.lerp(to, 1.0D / 3.0D)) || !water(level, from.lerp(to, 2.0D / 3.0D))) {
                    continue;
                }
                return to.subtract(0.0D, this.mob.getBbHeight() * 0.5D, 0.0D);
            }
            return null;
        }

        private static boolean water(Level level, Vec3 p) {
            BlockPos pos = BlockPos.containing(p);
            return level.isLoaded(pos) && level.getFluidState(pos).is(FluidTags.WATER)
                    && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
        }
    }

    /**
     * 水中・水面の相手(過繁茂側以外の全部。{@link #isPrey})を狙う。ボートに乗った相手も水面の相手。
     * 追っている相手が陸へ上がったら {@link #OUT_OF_WATER_GRACE} tick だけ追い続けてから見失う(浅瀬へ追って乗り上げる = 「岸へ釣り出す」)。
     * プレイヤーが水の中・水面に居れば、ほかの Mob より先に狙う({@link #findTarget}、仮決め)。
     *
     * <p>G5 の共通の索敵 {@code ParasitizedTargetGoal} は使わない —— あれは陸の種の規則で、水中・水面の相手を狙わない
     * ({@link Predation#isLandTarget})。水生の捕食者の {@link OvergrownWaterborneTargetGoal} は G1〜3 も獲物にするので、それも使わない。
     * 狙う相手の範囲は 2026-10-01 夜にプレイヤー・敵対 Mob だけ({@link Predation#isIntruder})から広げた({@link #isPrey})。
     */
    static final class WaterTargetGoal extends OvergrownHunterTargetGoal {
        /** バニラの索敵の箱の上下の幅(ブロック。{@code NearestAttackableTargetGoal#getTargetSearchArea})。 */
        private static final double VANILLA_SEARCH_UP = 4.0D;
        /** 標的が水から出た tick({@code tickCount})。水にいれば −1。canContinueToUse は 2 tick に 1 回なので回数ではなく時刻で数える。 */
        private int outSince = -1;

        WaterTargetGoal(BathymnesEntity mob) {
            super(mob, (self, candidate) -> isPrey(self, candidate) && inWaterForHunt(candidate));
        }

        /**
         * 近い順に選んだ後、それがプレイヤーでなく、水の中・水面にプレイヤー(ボートの乗り手も)が居れば、そちらを狙う
         * (仮決め 2026-10-01。G5 = 対人。海は魚・イカが多く、近い順だけだと魚を追い回している間にボートが素通りする)。
         * プレイヤーにも同じ条件({@code targetConditions} の選び方 = 種類・水・距離)が掛かる。
         */
        @Override
        protected void findTarget() {
            super.findTarget();
            if (!(this.target instanceof Player)) {
                Player player = this.mob.level().getNearestPlayer(this.targetConditions, this.mob,
                        this.mob.getX(), this.mob.getEyeY(), this.mob.getZ());
                if (player != null) {
                    this.target = player;
                }
            }
        }

        /**
         * 上下も水平と同じだけ見る。バニラの {@code NearestAttackableTargetGoal} は上下を 4 ブロックしか見ないので、
         * 深い所を漂っていると真上の水面のボート・泳ぐ相手が一生入ってこない(ヴェロフィルの索敵と同じ直し)。
         * 候補は水中・水面の相手だけ({@link #inWaterForHunt})なので、上下に広げても陸・洞窟の相手は拾わない。
         * 実際の許容距離は親の {@code isValidTarget} が球で見る。
         */
        @Override
        protected AABB getTargetSearchArea(double targetDistance) {
            AABB flat = super.getTargetSearchArea(targetDistance);
            double range = (flat.getXsize() - this.mob.getBbWidth()) * 0.5D;
            return flat.inflate(0.0D, Math.max(0.0D, range - VANILLA_SEARCH_UP), 0.0D);
        }

        /** 索敵の箱(GameTest 用)。 */
        AABB searchArea() {
            return getTargetSearchArea(getFollowDistance());
        }

        @Override
        public void start() {
            this.outSince = -1;
            super.start();
        }

        @Override
        public boolean canContinueToUse() {
            if (!super.canContinueToUse()) {
                return false;
            }
            LivingEntity t = this.mob.getTarget();
            if (t != null && !inWaterForHunt(t)) {
                if (this.outSince < 0) {
                    this.outSince = this.mob.tickCount;
                }
                return this.mob.tickCount - this.outSince <= OUT_OF_WATER_GRACE;
            }
            this.outSince = -1;
            return true;
        }
    }

    // ------------------------------------------------------------------ 掴まれた獲物は降りられない

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Events {
        @SubscribeEvent
        public static void onMount(EntityMountEvent event) {
            // 降車はサーバーでしか起きない(Player#rideTick)。クライアントで止めると、サーバーが放したときに食い違う。
            if (event.getLevel().isClientSide()) {
                return;
            }
            if (event.isDismounting()) {
                if (event.getEntityBeingMounted() instanceof BathymnesEntity b
                        && b.isAlive() && !b.isRemoved() && b.isHoldingPrey()) {
                    event.setCanceled(true);
                }
                return;
            }
            // 掴まれている獲物が別の乗り物(引き剥がしたボート・岸の馬)に乗るのも止める(2026-10-01 点検)。
            // Entity#startRiding は今の乗り物から降りる stopRiding が上で止められても先へ進み、新しい乗り物にも載せてしまう ——
            // 獲物が両方の乗客の表に残り、放しても表から消えない(放す ejectPassengers は獲物の今の乗り物 = 新しい方から降ろすため)。
            // ほかの種が掴んでいる獲物を横取りしようとする startRiding もここで断られる。
            if (event.isMounting()
                    && event.getEntityMounting().getVehicle() instanceof BathymnesEntity b
                    && b != event.getEntityBeingMounted()
                    && b.isAlive() && !b.isRemoved() && b.isHoldingPrey()) {
                event.setCanceled(true);
            }
        }

        /**
         * 掴まれたままプレイヤーがログアウトしたら、先に放す(2026-10-01 点検)。放さないと、バニラはプレイヤーの乗り物ごと
         * (= このバティムネスごと)プレイヤーのデータに保存して世界から消す({@code PlayerList#remove})。ログインすると隣に戻って
         * 次の tick に放す(動作は保存しない)ので、どのみち放すことになる —— それならバティムネスは世界に残しておく。
         * {@code PlayerLoggedOutEvent} は {@code PlayerList#remove} の頭(保存より前)で来る。
         */
        @SubscribeEvent
        public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            releaseHeldRider(event.getEntity());
        }

        /** {@code rider} を掴んでいるバティムネスがいれば、その場(嘴の前)で放させる(ログアウト・GameTest 用)。放したら true。 */
        public static boolean releaseHeldRider(Entity rider) {
            if (rider.getVehicle() instanceof BathymnesEntity b && !b.level().isClientSide() && b.isHoldingPrey()) {
                b.releasePrey(false);
                return true;
            }
            return false;
        }

        private Events() {
        }
    }
}
