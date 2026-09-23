package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.adaptation.Adaptation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 「ファルキムネス」(Falcimnes)— <b>グループ5(適応種)</b>の陸の純粋な攻撃役(2026-09-23)。
 * カマキリの姿をした爬虫類。前脚の鎌({@code wepon} / {@code wepon2})で斬る。
 *
 * <p>適応種なので、倒された武器種への耐性が種ごとに付いていく({@link Adaptation}、{@code group_5.json} に所属)。
 *
 * <h2>行動(ユーザー指定)</h2>
 * <ul>
 *     <li><b>横薙ぎ</b>(基本攻撃): 鎌を横に振る。<b>鎌の先が正面中央に来た瞬間</b>({@link #ATTACK_HIT_TICK})に、
 *     前方の扇の中の相手全員へ攻撃力ぶん。左右の鎌を交互に使う。</li>
 *     <li><b>連撃</b>: 鎌を振りかざして溜め(ユーザー指定 0.8 秒 → 前準備のため 1.2 秒)、何度も振る。振りの間隔は {@link #FLURRY_INTERVALS} の順に
 *     <b>だんだん速くなる</b>。振っている間も遅い足で相手へ寄っていく。</li>
 *     <li><b>掴み</b>: 鎌を大きく上に振りかざし、前方へ大きく突進。当たった相手を掴み上げて噛みつき
 *     ({@link #BITE_DAMAGE}、防具貫通)、最後に<b>防具に関係なく即死</b>させる。掴まれている間にこちらへ
 *     {@link #BREAK_FREE_DAMAGE} 以上のダメージを入れれば離す。</li>
 *     <li><b>回避</b>: この種の適応値が {@link #DODGE_RESISTANCE} 以上の攻撃を、大きく後ろへ跳んでかわす
 *     (クールダウン 30 秒)。</li>
 *     <li><b>威嚇</b>: プレイヤーと対峙したとき、まれに立ち上がって咆哮する。</li>
 * </ul>
 *
 * <h2>時刻はアニメーションと対</h2>
 * <p>アニメーションは {@code tools/falcimnes-anim/FalcAnim.java} の生成物で、鎌の先の到達点を座標で解いてある。
 * ここの {@code *_TICKS} / {@code *_HIT_TICK} と生成器の時刻は<b>必ず揃える</b>(片方だけ変えると
 * 鎌が前に出ていないのに当たる)。tick は {@link #TRANSITION_TICKS}(動作の切り替えの補間)+ アニメの秒 × 20。
 *
 * <h2>掴んだ獲物は「乗せる」</h2>
 * <p>獲物はこの Mob に<b>乗客として乗せて</b>顎の前へ置く({@link #getPassengerAttachmentPoint})。
 * プレイヤーの位置はクライアントが決めるので、毎 tick テレポートさせるとガタつく —— 乗り物なら
 * バニラの同期がそのまま使える。自分から降りられないよう {@link Events#onMount} で止め、
 * 乗ったまま本体を殴れるよう {@link #canRiderInteract} を開けてある。
 */
public class FalcimnesEntity extends Monster implements GeoEntity, ParasitizedMob {

    // ------------------------------------------------------------------ 動作

    public static final int ACT_NONE = 0;
    public static final int ACT_ATTACK_R = 1;
    public static final int ACT_ATTACK_L = 2;
    public static final int ACT_FLURRY_WINDUP = 3;
    public static final int ACT_FLURRY_R = 4;
    public static final int ACT_FLURRY_L = 5;
    public static final int ACT_GRAB_WINDUP = 6;
    public static final int ACT_GRAB_DASH = 7;
    public static final int ACT_GRAB_HOLD = 8;
    public static final int ACT_DODGE = 9;
    public static final int ACT_THREAT = 10;
    /** 空振り・振りほどかれた直後の隙。アニメは grab_end(挟む構えから下ろす)。 */
    public static final int ACT_RECOVER = 11;
    /**
     * 連撃のあと、構えた鎌を下ろす間(アニメ flurry_end、0.6 秒)。構えから待機へ補間(4 tick)で直に戻すと
     * 鎌が地面と首を通り抜けるので、下ろす動きをアニメにしてある。
     */
    public static final int ACT_FLURRY_END = 12;
    /** 威嚇の途中で殴られたときに、振りかざした鎌を下ろす間(アニメ ikaku_end、0.6 秒)。待機へ直に補間すると鎌が地面を通る。 */
    public static final int ACT_THREAT_END = 13;

    /**
     * 動作を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * <b>それが終わってからアニメを 0 秒目から流す</b>(4.7.6 の {@code AnimationController#process})。
     * だから画面の上では、どのアニメもサーバーの動作開始より 4 tick 遅れて始まる。アニメの時刻に合わせる
     * tick(当たる瞬間・咆哮・噛みつき・動作の長さ)は、すべて「これ + アニメの秒 × 20」で数える。
     * 連撃の 1 振りだけは補間 0(1 振りが最短 3 tick なので、補間を挟むと振りが消える)。
     * 2026-09-23 のレビューで発覚: それまでは横薙ぎが「振りかぶって震えている最中」(画面の 0.50 秒)に当たっていた。
     */
    static final int TRANSITION_TICKS = 4;

    // --- 横薙ぎ(アニメ attack_r / attack_l は 1.1 秒、0.70 秒で鎌の先が正面中央) ---
    // 2026-09-23 に作り直し: 0.40 秒では振りかぶりを見てから避けられない(ユーザー指摘「前準備を」)。
    // 0.45 秒かけて振りかぶり、0.58 秒まで構えて震わせ、0.70 秒で当てる(tick は {@link #TRANSITION_TICKS} ぶん後ろへずらす)。
    static final int ATTACK_TICKS = TRANSITION_TICKS + 22;
    static final int ATTACK_HIT_TICK = TRANSITION_TICKS + 14;
    /** これより後は向きを変えない(当たる直前 0.15 秒は振り切るだけ。横へ逃げれば外れる)。 */
    static final int ATTACK_TRACK_UNTIL = ATTACK_HIT_TICK - 3;
    /** 振り終わってから次の横薙ぎまで。 */
    static final int ATTACK_COOLDOWN = 10;
    /** 横薙ぎが届く前方の距離(ブロック、体の中心から、体格 1.0 のとき)。鎌の先は正面で約 4.5 ブロック先。 */
    static final double SWEEP_REACH = 4.7D;
    /** 判定だけに足すゆとり(ブロック)。鎌の先が相手の体に届いて見えるのに外れる、を無くす。 */
    static final double SWEEP_MARGIN = 0.6D;
    /** 横薙ぎの左右の幅(片側、ブロック、体格 1.0 のとき)。 */
    static final double SWEEP_HALF_WIDTH = 2.4D;
    /**
     * 振りかぶりながら詰める間合い(届く距離に対する割合)。
     * 空振りの原因(ユーザー指摘「標的より手前で振って外れる」)は、届くぎりぎりで振り始めていたこと ——
     * 相手が少し下がるだけで刃が手前を通る。振りかぶりの間にここまで踏み込み、懐で当てる。
     */
    static final double ATTACK_IDEAL_RATIO = 0.6D;
    /** 振りかぶりながら踏み込む速さ(ブロック/tick、体格 1.0 のとき)。 */
    static final double ATTACK_STEP_SPEED = 0.16D;

    // --- 連撃 ---
    /** 溜め 1.2 秒(ユーザー指定は 0.8 秒 → 「対応できる秒数を」で 2026-09-23 に延長)。 */
    static final int FLURRY_WINDUP_TICKS = TRANSITION_TICKS + 24;
    /** 1 振りごとの間隔(tick)。だんだん速くなる。 */
    static final int[] FLURRY_INTERVALS = {12, 11, 10, 9, 8, 7, 6, 6, 5, 5, 4, 4, 4, 3, 3, 3};
    /** 連撃 1 振りのダメージ = 攻撃力 × これ。 */
    static final float FLURRY_DAMAGE_FACTOR = 0.3F;
    /** 連撃中の足の速さ(通常の何倍)。 */
    static final double FLURRY_MOVE_SPEED = 0.55D;
    static final double FLURRY_REACH = 4.4D;
    static final int FLURRY_COOLDOWN = 240;
    /** 連撃のあと鎌を下ろす間(アニメ flurry_end 0.6 秒)。 */
    static final int FLURRY_END_TICKS = TRANSITION_TICKS + 12;

    // --- 掴み ---
    /**
     * 突進までの前準備 2.5 秒(ユーザー指定 2026-09-23「スプリントの前に威嚇してため時間を」)。
     * 0.6 秒で立ち上がって {@link #GRAB_ROAR_TICK} に咆哮 → 1.6 秒から鎌を高く振りかざす → 2.5 秒で突進。
     * 咆哮が聞こえたら掴みが来る、という合図になる(単独の威嚇とは長さと最後の構えが違う)。
     */
    static final int GRAB_WINDUP_TICKS = TRANSITION_TICKS + 50;
    static final int GRAB_ROAR_TICK = TRANSITION_TICKS + 12;
    /** これより後は向きを変えない(突進の向きが決まる。横へ逃げる猶予)。 */
    static final int GRAB_TRACK_UNTIL = GRAB_WINDUP_TICKS - 10;
    static final int GRAB_DASH_MAX_TICKS = 22;
    /** 突進の速さ(ブロック/tick)。 */
    static final double GRAB_DASH_SPEED = 0.95D;
    /** 掴みに行く距離(ブロック、体格 1.0 のとき)。横薙ぎの届く距離より外。 */
    static final double GRAB_MIN_RANGE = 5.0D;
    static final double GRAB_MAX_RANGE = 12.0D;
    static final int GRAB_COOLDOWN = 400;
    /** 噛みつき 1 回(防具貫通)。 */
    static final float BITE_DAMAGE = 8.0F;
    /** 噛む時刻(掴んでからの tick)。アニメ grab_hold は 1 秒ループで 0.5 秒に噛む。 */
    static final int BITE_FIRST = TRANSITION_TICKS + 10;
    static final int BITE_INTERVAL = 20;
    /** 止め(即死)の時刻。5 回目の噛みつき。 */
    static final int EXECUTE_TICK = BITE_FIRST + 4 * BITE_INTERVAL;
    /** 掴みの終わり(獲物を放した・殺した・振りほどかれた・外した)に鎌を下ろす間(アニメ grab_end 0.9 秒)。これより短い隙は無い。 */
    static final int GRAB_END_TICKS = TRANSITION_TICKS + 18;
    /** 掴まれている間にこれだけ削られたら離す。 */
    static final float BREAK_FREE_DAMAGE = 15.0F;
    /** 掴んだ獲物の中心(体の中心から前へ・上へ、ブロック)。生成器の {@code PREY}(0, 30, −50)px と同じ点。 */
    static final double PREY_FORWARD = 50.0D / 16.0D;
    static final double PREY_UP = 30.0D / 16.0D;

    // --- 回避 ---
    /** この適応値以上の攻撃をかわす。 */
    static final double DODGE_RESISTANCE = 0.15D;
    static final int DODGE_COOLDOWN = 600;
    static final int DODGE_TICKS = TRANSITION_TICKS + 12;

    // --- 威嚇 ---
    static final int THREAT_TICKS = TRANSITION_TICKS + 60;
    /** 咆哮する時刻(アニメ ikaku の頂点 0.9 秒)。 */
    static final int THREAT_ROAR_TICK = TRANSITION_TICKS + 18;
    /** 威嚇を中断されたとき、鎌を下ろす間(アニメ ikaku_end 0.6 秒)。 */
    static final int THREAT_END_TICKS = TRANSITION_TICKS + 12;
    /** 新しいプレイヤーを狙ったときに威嚇する確率。 */
    static final float THREAT_CHANCE_ON_ENGAGE = 0.3F;
    static final int THREAT_COOLDOWN = 1800;
    /** 威嚇する間合い(体格 1.0 のとき。×getScale())。近すぎると顔が相手に埋まる(体格 1.8 で頭は中心の 6.4 ブロック前)。 */
    static final double THREAT_MIN_RANGE = 4.5D;
    static final double THREAT_MAX_RANGE = 12.0D;

    private static final EntityDataAccessor<Integer> DATA_ACTION =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.INT);
    /** 動作を始めるたびに 1 増える。同じアニメを頭から流し直す合図。 */
    private static final EntityDataAccessor<Integer> DATA_SEQ =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.INT);
    /** 連撃の今の 1 振りの長さ(tick)。クライアントはこれでアニメの速さを決める。 */
    private static final EntityDataAccessor<Integer> DATA_SLASH_TICKS =
            SynchedEntityData.defineId(FalcimnesEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation ATTACK_R = RawAnimation.begin().thenPlay("attack_r");
    private static final RawAnimation ATTACK_L = RawAnimation.begin().thenPlay("attack_l");
    private static final RawAnimation FLURRY_WINDUP = RawAnimation.begin().thenPlayAndHold("flurry_windup");
    private static final RawAnimation FLURRY_R = RawAnimation.begin().thenPlayAndHold("flurry_r");
    private static final RawAnimation FLURRY_L = RawAnimation.begin().thenPlayAndHold("flurry_l");
    private static final RawAnimation GRAB_WINDUP = RawAnimation.begin().thenPlayAndHold("grab_windup");
    private static final RawAnimation GRAB_DASH = RawAnimation.begin().thenLoop("grab_dash");
    private static final RawAnimation GRAB_HOLD = RawAnimation.begin().thenLoop("grab_hold");
    private static final RawAnimation FLURRY_END = RawAnimation.begin().thenPlayAndHold("flurry_end");
    private static final RawAnimation GRAB_END = RawAnimation.begin().thenPlayAndHold("grab_end");
    private static final RawAnimation THREAT_END = RawAnimation.begin().thenPlayAndHold("ikaku_end");
    private static final RawAnimation DODGE = RawAnimation.begin().thenPlay("dodge");
    private static final RawAnimation THREAT = RawAnimation.begin().thenPlay("ikaku");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // --- サーバー側の状態(保存しない。読み込み直したら何もしていない状態から) ---
    private int actionTicks;
    private int attackCooldown;
    private int flurryCooldown = 60;
    private int grabCooldown = 100;
    private int dodgeCooldown;
    private int threatCooldown;
    private int recoverTicks;
    private boolean nextSlashLeft;
    private int flurryIndex;
    private Vec3 dashDir = Vec3.ZERO;
    private float holdDamage;
    /** 自分で獲物を離すときだけ立てる({@link Events#onMount} が降車を止めないように)。 */
    private boolean releasing;
    @Nullable
    private UUID lastEngaged;

    /** クライアント: 最後に見た {@link #DATA_SEQ}。 */
    private int clientSeq = -1;

    public FalcimnesEntity(EntityType<? extends FalcimnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("falcimnes", 40);
        this.setPathfindingMalus(PathType.WATER, -1.0F);
        this.setPathfindingMalus(PathType.LEAVES, -1.0F);
        this.getNavigation().setCanFloat(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), "falcimnes");
    }

    /** 幅のある体でも段差の上に体が入るかを確かめる経路探索(他の大型種と同じ)。 */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new GroundPathNavigation(this, level) {
            @Override
            protected PathFinder createPathFinder(int maxVisitedNodes) {
                this.nodeEvaluator = new OvergrownNodeEvaluator();
                this.nodeEvaluator.setCanPassDoors(true);
                return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
            }
        };
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, ACT_NONE);
        builder.define(DATA_SEQ, 0);
        builder.define(DATA_SLASH_TICKS, 10);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new HuntGoal(this));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        ParasitizedTargetGoal.installTargeting(this, 2);
    }

    // ------------------------------------------------------------------ 状態

    public int getAction() {
        return this.entityData.get(DATA_ACTION);
    }

    public boolean isActing() {
        return getAction() != ACT_NONE;
    }

    public boolean isHoldingPrey() {
        return getAction() == ACT_GRAB_HOLD && !this.releasing;
    }

    private void startAction(int action) {
        this.entityData.set(DATA_ACTION, action);
        this.entityData.set(DATA_SEQ, this.entityData.get(DATA_SEQ) + 1);
        this.actionTicks = 0;
    }

    private void endAction() {
        this.entityData.set(DATA_ACTION, ACT_NONE);
        this.actionTicks = 0;
    }

    private void recover(int ticks) {
        this.recoverTicks = ticks;
        startAction(ACT_RECOVER);
    }

    // ------------------------------------------------------------------ 毎 tick

    /**
     * 動作は {@code aiStep} で進める({@code customServerAiStep} ではなく)。{@code NoAI} の個体でも動作だけは
     * 進むので、GameTest が AI を止めたまま {@link #forceAction} / {@link #grab} で動作を確かめられる。
     */
    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide() || !this.isAlive()) {
            return;
        }
        if (this.attackCooldown > 0) this.attackCooldown--;
        if (this.flurryCooldown > 0) this.flurryCooldown--;
        if (this.grabCooldown > 0) this.grabCooldown--;
        if (this.dodgeCooldown > 0) this.dodgeCooldown--;
        if (this.threatCooldown > 0) this.threatCooldown--;
        // 動作は保存しないが、乗客(掴んだ獲物)は保存される。読み込み直すと掴んでいない状態で獲物を抱えたままになるので放す
        // (獲物の Mob は自分では降りない)。噛みつきの最中以外に乗っているものは全部これ。
        if (getAction() != ACT_GRAB_HOLD && this.isVehicle()) {
            this.ejectPassengers();
        }
        if (isActing()) {
            this.actionTicks++;
            tickAction();
        }
    }

    private void tickAction() {
        LivingEntity target = this.getTarget();
        boolean hasTarget = target != null && target.isAlive();
        switch (getAction()) {
            case ACT_ATTACK_R, ACT_ATTACK_L -> {
                holdStill();
                if (this.actionTicks == 1) playSound(ModSounds.FALCIMNES_GROWL.get(), 1.0F, 1.15F);
                if (hasTarget && this.actionTicks <= ATTACK_TRACK_UNTIL) {
                    turnTo(target, 30.0F);
                    stepToward(target, SWEEP_REACH * this.getScale() * ATTACK_IDEAL_RATIO, ATTACK_STEP_SPEED);
                }
                if (this.actionTicks == ATTACK_HIT_TICK - 2) playSound(ModSounds.FALCIMNES_SLASH.get(), 1.0F, 1.0F);
                if (this.actionTicks == ATTACK_HIT_TICK) {
                    for (LivingEntity e : inSweep(SWEEP_REACH + SWEEP_MARGIN, SWEEP_HALF_WIDTH)) {
                        this.doHurtTarget(e);
                    }
                }
                if (this.actionTicks >= ATTACK_TICKS) {
                    this.attackCooldown = ATTACK_COOLDOWN;
                    endAction();
                }
            }
            case ACT_FLURRY_WINDUP -> {
                holdStill();
                if (this.actionTicks == 1) playSound(ModSounds.FALCIMNES_GROWL.get(), 1.4F, 0.85F);
                if (hasTarget) turnTo(target, 20.0F);
                if (this.actionTicks >= FLURRY_WINDUP_TICKS) {
                    this.flurryIndex = 0;
                    startSlash();
                }
            }
            case ACT_FLURRY_R, ACT_FLURRY_L -> {
                int interval = FLURRY_INTERVALS[this.flurryIndex];
                if (hasTarget) {
                    turnTo(target, 25.0F);
                    // 振りながら寄っていく(遅い足で)。近すぎれば止まる。
                    if (this.distanceTo(target) > 3.0F * this.getScale()) {
                        if (this.actionTicks == 1 || this.tickCount % 5 == 0) {
                            this.getNavigation().moveTo(target, FLURRY_MOVE_SPEED);
                        }
                    } else {
                        this.getNavigation().stop();
                    }
                }
                // 1 振りの真ん中(切り上げ)で当てる。切り捨てだと間隔 3 で刃がまだ頭の上にある(画面の 0.17 秒)。
                if (this.actionTicks == Math.max(1, (interval + 1) / 2)) {
                    playSound(ModSounds.FALCIMNES_SLASH.get(), 0.9F, 1.0F + this.flurryIndex * 0.03F);
                    float dmg = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * FLURRY_DAMAGE_FACTOR;
                    for (LivingEntity e : inSweep(FLURRY_REACH + SWEEP_MARGIN, SWEEP_HALF_WIDTH * 0.8D)) {
                        // 間隔が無敵時間(10 tick)より短くなるので、1 振りごとに無敵時間を切る。
                        e.invulnerableTime = 0;
                        if (e.hurt(this.damageSources().mobAttack(this), dmg)) {
                            e.knockback(0.15D, -forward().x, -forward().z);
                        }
                    }
                }
                if (this.actionTicks >= interval) {
                    this.flurryIndex++;
                    if (this.flurryIndex < FLURRY_INTERVALS.length) {
                        startSlash();
                    } else {
                        this.getNavigation().stop();
                        this.flurryCooldown = FLURRY_COOLDOWN;
                        this.attackCooldown = 10;
                        startAction(ACT_FLURRY_END);
                    }
                }
            }
            case ACT_GRAB_WINDUP -> {
                holdStill();
                // 最後の 0.5 秒は向きを固定する —— 突進の向きが見えてから横へ逃げられる。
                if (hasTarget && this.actionTicks <= GRAB_TRACK_UNTIL) turnTo(target, 12.0F);
                if (this.actionTicks == GRAB_ROAR_TICK) {
                    this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                            ModSounds.FALCIMNES_ROAR.get(), SoundSource.HOSTILE, 3.0F, 1.0F);
                }
                if (this.actionTicks >= GRAB_WINDUP_TICKS) {
                    this.dashDir = forward();
                    this.grabCooldown = GRAB_COOLDOWN;
                    playSound(ModSounds.FALCIMNES_SCREECH.get(), 1.6F, 1.0F);
                    startAction(ACT_GRAB_DASH);
                }
            }
            case ACT_GRAB_DASH -> tickDash();
            case ACT_GRAB_HOLD -> tickHold();
            case ACT_DODGE -> {
                if (this.actionTicks >= DODGE_TICKS) endAction();
            }
            case ACT_THREAT -> {
                holdStill();
                if (hasTarget) turnTo(target, 10.0F);
                if (this.actionTicks == THREAT_ROAR_TICK) {
                    this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                            ModSounds.FALCIMNES_ROAR.get(), SoundSource.HOSTILE, 3.0F, 1.0F);
                }
                if (this.actionTicks >= THREAT_TICKS) endAction();
            }
            case ACT_RECOVER -> {
                holdStill();
                if (this.actionTicks >= this.recoverTicks) endAction();
            }
            case ACT_FLURRY_END -> {
                holdStill();
                if (this.actionTicks >= FLURRY_END_TICKS) endAction();
            }
            case ACT_THREAT_END -> {
                holdStill();
                if (this.actionTicks >= THREAT_END_TICKS) endAction();
            }
            default -> endAction();
        }
    }

    private void startSlash() {
        int interval = FLURRY_INTERVALS[this.flurryIndex];
        this.entityData.set(DATA_SLASH_TICKS, interval);
        startAction(this.flurryIndex % 2 == 0 ? ACT_FLURRY_R : ACT_FLURRY_L);
    }

    private void tickDash() {
        float yaw = (float) (Mth.atan2(this.dashDir.z, this.dashDir.x) * Mth.RAD_TO_DEG) - 90.0F;
        setFacing(yaw);
        Vec3 v = this.getDeltaMovement();
        this.setDeltaMovement(this.dashDir.x * GRAB_DASH_SPEED, v.y, this.dashDir.z * GRAB_DASH_SPEED);
        this.hasImpulse = true;

        LivingEntity caught = findGrabbable();
        if (caught != null) {
            if (!grab(caught)) {
                // 持ち上げられない相手(大きすぎる・ボスなど)は突き飛ばすだけ。
                this.doHurtTarget(caught);
                this.setDeltaMovement(Vec3.ZERO);
                recover(GRAB_END_TICKS);
            }
            return;
        }
        if ((this.horizontalCollision && this.actionTicks > 3) || this.actionTicks >= GRAB_DASH_MAX_TICKS) {
            this.setDeltaMovement(Vec3.ZERO.add(0.0D, this.getDeltaMovement().y, 0.0D));
            // 壁に突っ込んだら長めに固まる。
            recover(this.horizontalCollision ? 40 : GRAB_END_TICKS + 2);
        }
    }

    private void tickHold() {
        holdStill();
        Entity prey = this.getFirstPassenger();
        if (!(prey instanceof LivingEntity victim) || !victim.isAlive()) {
            // 殺した(止め・噛みつき)か、獲物が消えた。死体を顎の前に吊ったまま歩かないよう落とし、鎌を下ろす。
            this.releasing = true;
            this.ejectPassengers();
            this.releasing = false;
            endHold();
            recover(GRAB_END_TICKS);
            return;
        }
        int t = this.actionTicks;
        if (t == EXECUTE_TICK) {
            playSound(ModSounds.FALCIMNES_BITE.get(), 1.6F, 0.7F);
            victim.invulnerableTime = 0;
            victim.hurt(ModDamageTypes.falcimnes((ServerLevel) this.level(), ModDamageTypes.FALCIMNES_EXECUTION, this),
                    Float.MAX_VALUE);
            // 生き延びた(トーテム・クリエイティブ)なら離す。
            if (victim.isAlive()) {
                releasePrey(false);
            }
            return;
        }
        if (t >= BITE_FIRST && (t - BITE_FIRST) % BITE_INTERVAL == 0) {
            playSound(ModSounds.FALCIMNES_BITE.get(), 1.2F, 0.9F + this.random.nextFloat() * 0.2F);
            victim.invulnerableTime = 0;
            victim.hurt(ModDamageTypes.falcimnes((ServerLevel) this.level(), ModDamageTypes.FALCIMNES_BITE, this),
                    BITE_DAMAGE);
            if (this.level() instanceof ServerLevel server) {
                Vec3 p = victim.position().add(0.0D, victim.getBbHeight() * 0.6D, 0.0D);
                server.sendParticles(ParticleTypes.DAMAGE_INDICATOR, p.x, p.y, p.z, 4, 0.2D, 0.2D, 0.2D, 0.1D);
            }
        }
    }

    /**
     * {@code prey} を掴み上げて噛みつきへ移る。持ち上げられない相手なら false(何もしない)。
     * 突進が当たったときに呼ぶ。GameTest からも直接呼ぶ。
     */
    public boolean grab(LivingEntity prey) {
        if (!canCarry(prey) || !prey.startRiding(this, true)) {
            return false;
        }
        this.holdDamage = 0.0F;
        this.releasing = false;
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        playSound(ModSounds.FALCIMNES_BITE.get(), 1.2F, 0.8F);
        startAction(ACT_GRAB_HOLD);
        return true;
    }

    /** 動作をいますぐ始める(GameTest・デバッグ用。溜めや間合いの条件は見ない)。 */
    public void forceAction(int action) {
        startAction(action);
    }

    /** 獲物を離す。{@code shaken} は振りほどかれたとき(長めの隙)。 */
    private void releasePrey(boolean shaken) {
        Entity prey = this.getFirstPassenger();
        this.releasing = true;
        this.ejectPassengers();
        this.releasing = false;
        if (prey != null) {
            Vec3 f = forward();
            prey.setDeltaMovement(f.x * 0.5D, 0.35D, f.z * 0.5D);
            prey.hurtMarked = true;
        }
        endHold();
        recover(shaken ? 30 : GRAB_END_TICKS);
    }

    private void endHold() {
        this.holdDamage = 0.0F;
        this.attackCooldown = 20;
    }

    // ------------------------------------------------------------------ 判定

    /** 前方の扇に入っている、斬ってよい相手。 */
    private List<LivingEntity> inSweep(double reach, double halfWidth) {
        double s = this.getScale();
        Vec3 f = forward();
        Vec3 r = new Vec3(-f.z, 0.0D, f.x);
        List<LivingEntity> out = new ArrayList<>();
        AABB box = this.getBoundingBox().inflate(reach * s + 1.0D, 3.0D * s, reach * s + 1.0D);
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, this::isFoe)) {
            Vec3 d = e.position().subtract(this.position());
            double fw = d.x * f.x + d.z * f.z;
            double lat = Math.abs(d.x * r.x + d.z * r.z);
            double er = e.getBbWidth() * 0.5D;
            double dy = e.getY() - this.getY();
            if (fw < 0.3D || fw > reach * s + er) continue;
            if (lat > halfWidth * s + er) continue;
            if (dy < -1.5D || dy > 3.0D * s) continue;
            out.add(e);
        }
        return out;
    }

    /** 突進の先(鎌の間)に居る、掴める相手。狙っている相手を優先する。 */
    @Nullable
    private LivingEntity findGrabbable() {
        double s = this.getScale();
        Vec3 c = this.position().add(forward().scale(PREY_FORWARD * s));
        AABB zone = new AABB(c.x - 1.3D * s, this.getY(), c.z - 1.3D * s,
                c.x + 1.3D * s, this.getY() + 2.8D * s, c.z + 1.3D * s);
        LivingEntity best = null;
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, zone, this::isFoe)) {
            if (e == this.getTarget()) return e;
            if (best == null || e.distanceToSqr(c) < best.distanceToSqr(c)) best = e;
        }
        return best;
    }

    /** 斬ってよい相手か(自分・乗客・汚染側の仲間・クリエイティブ/観戦者は除く)。 */
    private boolean isFoe(LivingEntity e) {
        if (e == this || !e.isAlive() || e.isPassengerOfSameVehicle(this) || e.getVehicle() == this) return false;
        if (e instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        if (e == this.getTarget()) return true;
        return MobGroup.of(e) == null;
    }

    /** 持ち上げられる相手か。 */
    private static boolean canCarry(LivingEntity e) {
        // 何かに乗っている相手は掴まない: grab は startRiding を force で呼ぶので、別の個体が掴んでいる獲物を奪うと
        // 両方の乗客の表に残ってしまう(レビューで発覚、2026-09-23)。
        return !e.getType().is(Tags.EntityTypes.BOSSES)
                && e.getBbWidth() <= 2.0F && e.getBbHeight() <= 3.0F
                && !e.isVehicle() && !e.isPassenger();
    }

    /** 体の向いている方向(水平の単位ベクトル)。 */
    private Vec3 forward() {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
    }

    private void turnTo(Entity target, float maxStep) {
        double dx = target.getX() - this.getX(), dz = target.getZ() - this.getZ();
        float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        setFacing(Mth.approachDegrees(this.getYRot(), want, maxStep));
        this.getLookControl().setLookAt(target, 30.0F, 30.0F);
    }

    private void setFacing(float yaw) {
        this.setYRot(yaw);
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
    }

    /**
 * {@code target} まで {@code ideal} ブロックより遠ければ、{@code speed}(体格 1.0 のとき)で踏み込む。
 * 振りかぶりの最中に使う —— 移動入力は止めてあるので速度で直接動かす。
 */
private void stepToward(Entity target, double ideal, double speed) {
    Vec3 d = target.position().subtract(this.position());
    d = new Vec3(d.x, 0.0D, d.z);
    double dist = d.length();
    Vec3 v = this.getDeltaMovement();
    if (dist > ideal && dist > 1.0E-3D) {
        double step = Math.min(speed * this.getScale(), dist - ideal);
        Vec3 m = d.scale(step / dist);
        this.setDeltaMovement(m.x, v.y, m.z);
    }
}

/** その場から動かない(溜め・威嚇・掴み中)。 */
    private void holdStill() {
        this.getNavigation().stop();
        this.setZza(0.0F);
        this.setXxa(0.0F);
    }

    // ------------------------------------------------------------------ 回避と被弾

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide() && tryDodge(source, amount)) {
            return false;
        }
        float before = this.getHealth();
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide()) {
            if (getAction() == ACT_GRAB_HOLD) {
                this.holdDamage += Math.max(0.0F, before - this.getHealth());
                if (this.holdDamage >= BREAK_FREE_DAMAGE && this.isAlive()) {
                    releasePrey(true);
                }
            } else if (getAction() == ACT_THREAT) {
                startAction(ACT_THREAT_END);
            }
        }
        return hurt;
    }

    /**
     * 適応値が {@link #DODGE_RESISTANCE} 以上の攻撃なら、大きく後ろへ跳んでかわす(当たらなかったことにする)。
     * 掴みの突進中・獲物を掴んでいる間はかわさない(身を投げ出している最中)。
     */
    private boolean tryDodge(DamageSource source, float amount) {
        if (this.dodgeCooldown > 0 || !this.isAlive() || !(this.level() instanceof ServerLevel server)) return false;
        int act = getAction();
        if (act == ACT_GRAB_HOLD || act == ACT_GRAB_DASH || act == ACT_DODGE) return false;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        if (source.getEntity() == null && source.getDirectEntity() == null) return false;
        // 無敵時間中で、どのみち通らない一撃に回避を使わない。
        if (this.invulnerableTime > 10 && amount <= this.lastHurt) return false;
        if (Adaptation.resistance(server.getServer(), this.getType(), source) < DODGE_RESISTANCE) return false;

        Entity from = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
        Vec3 away = this.position().subtract(from.position());
        away = new Vec3(away.x, 0.0D, away.z);
        away = away.lengthSqr() < 1.0E-4D ? forward().scale(-1.0D) : away.normalize();
        turnTo(from, 180.0F);
        this.getNavigation().stop();
        this.setDeltaMovement(away.x * 0.85D, 0.42D, away.z * 0.85D);
        this.hasImpulse = true;
        this.hurtMarked = true;
        this.dodgeCooldown = DODGE_COOLDOWN;
        playSound(ModSounds.FALCIMNES_SLASH.get(), 1.0F, 0.6F);
        server.sendParticles(ParticleTypes.POOF, this.getX(), this.getY() + 0.2D, this.getZ(), 12, 0.8D, 0.1D, 0.8D, 0.02D);
        startAction(ACT_DODGE);
        return true;
    }

    @Override
    public void die(DamageSource source) {
        this.releasing = true;
        super.die(source);
        // 倒れる演出(20 tick)の間ぶら下げたままにしない。
        this.ejectPassengers();
    }

    // ------------------------------------------------------------------ 移動・乗客

    @Override
    public void travel(Vec3 input) {
        switch (getAction()) {
            case ACT_NONE, ACT_FLURRY_R, ACT_FLURRY_L -> super.travel(input);
            // 突進・回避は速度だけで動く。それ以外はその場に留まる。
            default -> super.travel(Vec3.ZERO);
        }
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float partialTick) {
        double s = this.getScale();
        double up = PREY_UP * s - passenger.getBbHeight() * 0.5D + passenger.getVehicleAttachmentPoint(this).y;
        return new Vec3(0.0D, up, PREY_FORWARD * s).yRot(-this.getYRot() * Mth.DEG_TO_RAD);
    }

    /** 掴まれている獲物に操縦させない(ゾンビを掴むとゾンビがこちらを歩かせてしまう)。 */
    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }

    /** 掴まれたプレイヤーがこちらを殴れるように(乗り物は普通は狙えない)。 */
    @Override
    public boolean canRiderInteract() {
        return true;
    }

    /** 掴まれた獲物は座らせない(ぶら下がっている)。 */
    @Override
    public boolean shouldRiderSit() {
        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().isEmpty();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
    }

    /**
     * 首と鎌が当たり判定の外へ長く伸びているので、描画の打ち切りは広めに。上にも広げる —— 掴みの前準備で振りかざした鎌は
     * 足元から 8.2 ブロック(体格 1.8)に届き、見上げると本体の箱が画面の外に出て全体が消えていた。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        double s = this.getScale();
        return super.getBoundingBoxForCulling().inflate(3.5D * s, 3.0D * s, 3.5D * s);
    }

    @Override
    public int getMaxHeadXRot() {
        return 25;
    }

    // ------------------------------------------------------------------ 追跡と行動の選択

    /**
     * 相手が居る間(または何かの動作中)は常に動いている Goal。動作中は何もしない ——
     * 動作は {@link #tickAction} が動かしている。この Goal が MOVE / LOOK を握っているので、
     * 溜めや掴みの最中にぶらつき・見回しの Goal が割り込まない。
     */
    static final class HuntGoal extends net.minecraft.world.entity.ai.goal.Goal {
        private final FalcimnesEntity mob;
        private int repath;

        HuntGoal(FalcimnesEntity mob) {
            this.mob = mob;
            this.setFlags(java.util.EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = mob.getTarget();
            return mob.isActing() || (t != null && t.isAlive());
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            if (mob.isActing()) return;
            LivingEntity target = mob.getTarget();
            if (target == null || !target.isAlive()) return;
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            double d = Math.sqrt(mob.distanceToSqr(target.getX(), mob.getY(), target.getZ()));
            boolean sees = mob.getSensing().hasLineOfSight(target);

            // 威嚇: 新しいプレイヤーと対峙したときにまれに。
            if (target instanceof Player p && !p.getUUID().equals(mob.lastEngaged)) {
                mob.lastEngaged = p.getUUID();
                if (mob.threatCooldown <= 0 && sees && d >= THREAT_MIN_RANGE * mob.getScale() && d <= THREAT_MAX_RANGE * mob.getScale()
                        && mob.random.nextFloat() < THREAT_CHANCE_ON_ENGAGE) {
                    mob.threatCooldown = THREAT_COOLDOWN;
                    mob.startAction(ACT_THREAT);
                    return;
                }
            }
            // 掴み: 少し離れた相手へ。
            double s = mob.getScale();
            if (mob.grabCooldown <= 0 && sees && d >= GRAB_MIN_RANGE * s && d <= GRAB_MAX_RANGE * s
                    && mob.onGround() && mob.random.nextInt(20) == 0) {
                mob.startAction(ACT_GRAB_WINDUP);
                return;
            }
            // 連撃: 間合いの内側で。
            if (mob.flurryCooldown <= 0 && d <= 4.0D * s && mob.random.nextInt(12) == 0) {
                mob.startAction(ACT_FLURRY_WINDUP);
                return;
            }
            // 横薙ぎ。
            // 届くぎりぎりでは振り始めない(振りかぶりの間に踏み込んで懐で当てる)。
            double reach = SWEEP_REACH * s + target.getBbWidth() * 0.5D;
            if (mob.attackCooldown <= 0 && d <= reach * 0.85D && Math.abs(target.getY() - mob.getY()) < 3.0D * s) {
                mob.startAction(mob.nextSlashLeft ? ACT_ATTACK_L : ACT_ATTACK_R);
                mob.nextSlashLeft = !mob.nextSlashLeft;
                return;
            }
            // 追う(間合いの少し手前で止まる)。
            if (d > reach * ATTACK_IDEAL_RATIO) {
                if (--repath <= 0) {
                    repath = 8;
                    mob.getNavigation().moveTo(target, 1.0D);
                }
            } else {
                mob.getNavigation().stop();
            }
        }
    }

    // ------------------------------------------------------------------ 見た目

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, state -> {
            AnimationController<FalcimnesEntity> c = state.getController();
            int act = getAction();
            int seq = this.entityData.get(DATA_SEQ);
            if (seq != this.clientSeq) {
                this.clientSeq = seq;
                if (act != ACT_NONE) {
                    c.forceAnimationReset();
                }
            }
            boolean flurry = act == ACT_FLURRY_R || act == ACT_FLURRY_L;
            // 連撃は 1 振りが最短 3 tick なので、補間を挟むと振りが消える。
            c.transitionLength(flurry ? 0 : TRANSITION_TICKS);
            c.setAnimationSpeed(1.0D);
            switch (act) {
                case ACT_ATTACK_R: return state.setAndContinue(ATTACK_R);
                case ACT_ATTACK_L: return state.setAndContinue(ATTACK_L);
                case ACT_FLURRY_WINDUP: return state.setAndContinue(FLURRY_WINDUP);
                case ACT_FLURRY_R:
                case ACT_FLURRY_L:
                    // 1 振りのアニメは 0.5 秒(10 tick)。間隔に収まる速さで流す。
                    c.setAnimationSpeed(10.0D / Math.max(1, this.entityData.get(DATA_SLASH_TICKS)));
                    return state.setAndContinue(act == ACT_FLURRY_R ? FLURRY_R : FLURRY_L);
                case ACT_GRAB_WINDUP: return state.setAndContinue(GRAB_WINDUP);
                case ACT_GRAB_DASH: return state.setAndContinue(GRAB_DASH);
                case ACT_GRAB_HOLD: return state.setAndContinue(GRAB_HOLD);
                case ACT_FLURRY_END: return state.setAndContinue(FLURRY_END);
                case ACT_RECOVER: return state.setAndContinue(GRAB_END);
                case ACT_THREAT_END: return state.setAndContinue(THREAT_END);
                case ACT_DODGE: return state.setAndContinue(DODGE);
                case ACT_THREAT: return state.setAndContinue(THREAT);
                default:
                    if (MobAnimation.isWalking(state)) {
                        c.setAnimationSpeed(Mth.clamp(state.getLimbSwingAmount() / 0.45F, 0.6F, 1.8F));
                        return state.setAndContinue(WALK);
                    }
                    return state.setAndContinue(IDLE);
            }
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ------------------------------------------------------------------ 音

    @Override
    protected SoundEvent getAmbientSound() {
        return ModSounds.FALCIMNES_IDLE.get();
    }

    @Override
    public int getAmbientSoundInterval() {
        return 160;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.FALCIMNES_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.FALCIMNES_DEATH.get();
    }

    /**
     * 平常時・被弾・死亡の声の高さの個体差。バニラは ±20% ずらすが、合成した低い竜の声が 1.2 倍になると
     * 小さな生き物に聞こえてしまうので ±5% に抑える。
     */
    @Override
    public float getVoicePitch() {
        return 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.05F;
    }

    /** 足音は重い獣のもの(初版は蜘蛛の足音だった。見た目は虫でも種族は竜 —— ユーザー指定)。 */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.RAVAGER_STEP, 0.6F, 0.8F);
    }

    // ------------------------------------------------------------------ 掴まれた獲物は降りられない

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Events {
        @SubscribeEvent
        public static void onMount(EntityMountEvent event) {
            // 降車はサーバーでしか起きない(Player#rideTick)。クライアントで止めると、サーバーが離したときに食い違う。
            if (event.isDismounting() && !event.getLevel().isClientSide()
                    && event.getEntityBeingMounted() instanceof FalcimnesEntity f
                    && f.isAlive() && !f.isRemoved() && f.isHoldingPrey()) {
                event.setCanceled(true);
            }
        }

        private Events() {
        }
    }
}
