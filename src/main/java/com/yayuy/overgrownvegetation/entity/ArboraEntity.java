package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.action.ActionDef;
import com.yayuy.overgrownvegetation.entity.action.ActionPicker;
import com.yayuy.overgrownvegetation.entity.action.ActionTiming;
import com.yayuy.overgrownvegetation.entity.cling.ClingMover;
import com.yayuy.overgrownvegetation.entity.cling.ClingPose;
import com.yayuy.overgrownvegetation.entity.cling.SurfaceNode;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.EnumSet;
import java.util.List;

/**
 * 「アルボラ」({@code arbora}) — G2(拡大種)の地上の大群・前衛(2026-11-05 作り直して MOD に戻した。前の作りは {@code parked/}).
 *
 * <p>カマキリ型。形の芯と能力の芯は変えない(ユーザー決定)。今回戻したのは<b>移動・追跡・鎌の叩きつけ・飛びつき</b>まで
 * (仕様書 Vault {@code Mob の能力/アルボラ.md})。胞子の裂け目・鎌の暴露・坑道掘り・群れの流れの場は後の段。
 *
 * <h2>移動 —— 全面に張り付く(層 1 の型 ①、{@link ClingMover})</h2>
 * 壁・天井・床を区別せず、ブロックの面そのものを歩く。天井では逆さまのまま這って追う(落ちて襲わない、11-05 決定)。
 * 葉の中は潜って 3 次元に動き、葉の粒と擦れる音が居場所の手がかり。水の中では張り付かない。
 * 張り付いている間に強く叩かれると剥がれて落ちる({@link #knockback}・{@link #hurt}。数値は【仮】)。
 *
 * <h2>行動 —— 前触れ → 実行 → 隙(層 2、{@link ActionPicker})</h2>
 * <ul>
 *     <li>{@code scythe} 鎌の叩きつけ: 鎌を振り上げる(前触れ 0.4 秒)→ 叩きつけ → 戻り。数値は {@code design/actions.json}。</li>
 *     <li>{@code leap} 飛びつき: 腰を一瞬落とす(0.2 秒・光なし、11-05 ユーザー決定 a)→ 跳ぶ(空中で触れたら 1 回殴る)→ 着地の隙。
 *     床からだけ跳ぶ。盾で弾き返される仕様({@code ShieldParry})は外した(11-04 決定)。</li>
 * </ul>
 *
 * <h2>アニメ</h2>
 * {@code idle} / {@code walk} / {@code run} / {@code attack} / {@code leap_windup}(11-05 追加)/ {@code leap} / {@code spore}(今は使わない)。
 */
public class ArboraEntity extends Monster implements GeoEntity, ParasitizedMob, ClingMover.Body {

    public static final String ID = "arbora";

    /** GeckoLib の切り替えの補間(tick)。どのアニメも状態を切り替えてからこれだけ遅れて始まる。 */
    static final int TRANSITION_TICKS = 4;

    /** 鎌: attack は 0.6 秒、0.4 秒で叩きつける。前触れ(補間 4 + 振り上げ 0.4 秒)→ 実行 0.1 秒 → 隙 0.4 秒(戻り 0.1 + 構え直し 0.3)。 */
    public static final ActionTiming SCYTHE = ActionTiming.ofSeconds(TRANSITION_TICKS, 0.4D, 0.1D, 0.4D);
    /** 飛びつき: 前触れは補間の 4 tick で腰を落としきる(0.2 秒、11-05 決定)。実行は着地まで(下の LEAP_MAX_AIR)。隙は着地から 0.4 秒【仮】。 */
    public static final ActionTiming LEAP = ActionTiming.ofSeconds(TRANSITION_TICKS, 0.0D, 0.0D, 0.4D);
    /** 跳んでいる長さの上限(tick)。 */
    static final int LEAP_MAX_AIR = 24;

    /** 鎌が届く、体の箱どうしの隙間(ブロック)。バニラの近接(攻撃の箱は横に約 0.83 膨らむ)に合わせた【仮】。 */
    public static final double MELEE_GAP = 0.85D;
    /** 経路の行き先: 相手の箱との隙間がこれ以下のマス。 */
    static final double PATH_REACH = 0.5D;

    /** 張り付いている間、これ以上のノックバックで剥がれる(普通の一撃は 0.4、走りながら・ノックバック I で 0.5 が足される)。【仮】 */
    public static final double KNOCKOFF_STRENGTH = 0.5D;
    /** 張り付いている間、1 秒の間に最大体力のこの割合を受けたら剥がれる。【仮】 */
    public static final float KNOCKOFF_DAMAGE_FRACTION = 0.5F;
    /** 剥がれてからまた掴むまで(tick)。【仮】 */
    static final int KNOCKOFF_GRAB_COOLDOWN = 10;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("attack");
    private static final RawAnimation LEAP_WINDUP = RawAnimation.begin().thenPlayAndHold("leap_windup");
    private static final RawAnimation LEAP_AIR = RawAnimation.begin().thenLoop("leap");

    static final int ACT_NONE = 0;
    static final int ACT_SCYTHE = 1;
    static final int ACT_LEAP_WINDUP = 2;
    static final int ACT_LEAP = 3;
    static final int ACT_LEAP_RECOVER = 4;

    private static final EntityDataAccessor<Byte> DATA_ANCHOR =
            SynchedEntityData.defineId(ArboraEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Vector3f> DATA_FORWARD =
            SynchedEntityData.defineId(ArboraEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Byte> DATA_ACTION =
            SynchedEntityData.defineId(ArboraEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DATA_SPEED =
            SynchedEntityData.defineId(ArboraEntity.class, EntityDataSerializers.FLOAT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private final ClingMover mover = new ClingMover(this, this);
    private final ActionPicker picker = new ActionPicker();
    /** クライアント: 見た目の向き。 */
    private final ClingPose pose = new ClingPose();

    private int action = ACT_NONE;
    private int actionTicks;
    private int leapAirTicks;
    private boolean leapHit;
    private float recentDamage;
    private int recentDamageTicks;

    public ArboraEntity(EntityType<? extends ArboraEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 10);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ANCHOR, (byte) SurfaceNode.NONE);
        builder.define(DATA_FORWARD, new Vector3f(0.0F, 0.0F, 1.0F));
        builder.define(DATA_ACTION, (byte) ACT_NONE);
        builder.define(DATA_SPEED, 0.0F);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new HuntGoal(this));
        // 暇なときの動き(うろつき・うずくまって胞子)は仕様の答え待ち —— 今は見回すだけ。
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false,
                e -> !(e instanceof Player) && PlantFoes.isFoe(this, e)));   // 陣営と除外は共通(防具立ても外す。①11・①13)
    }

    // ------------------------------------------------------------------ 張り付き

    public ClingMover mover() {
        return mover;
    }

    /** 壁・天井・葉の中に張り付いている(GameTest・描画用)。 */
    public boolean isClinging() {
        return mover.mode() == ClingMover.Mode.CLING;
    }

    /** 同期された面(クライアントでも読める)。 */
    public int syncedAnchor() {
        return this.entityData.get(DATA_ANCHOR);
    }

    public ClingPose pose() {
        return pose;
    }

    @Override
    public void setClingAnchor(int anchor) {
        this.entityData.set(DATA_ANCHOR, (byte) anchor);
    }

    @Override
    public void setClingForward(Vec3 forward) {
        Vector3f cur = this.entityData.get(DATA_FORWARD);
        Vector3f f = new Vector3f((float) forward.x, (float) forward.y, (float) forward.z);
        if (cur.distanceSquared(f) > 0.01F) {
            this.entityData.set(DATA_FORWARD, f);
        }
        if (forward.horizontalDistanceSqr() > 1.0E-4D) {
            float yaw = (float) (Mth.atan2(forward.z, forward.x) * Mth.RAD_TO_DEG) - 90.0F;
            this.setYRot(yaw);
            this.yBodyRot = yaw;
        }
    }

    @Override
    public void setMoveSpeed(float blocksPerTick) {
        float cur = this.entityData.get(DATA_SPEED);
        if (Math.abs(cur - blocksPerTick) > 0.01F || (blocksPerTick == 0.0F && cur != 0.0F)) {
            this.entityData.set(DATA_SPEED, blocksPerTick);
        }
    }

    public float syncedSpeed() {
        return this.entityData.get(DATA_SPEED);
    }

    @Override
    public void travel(Vec3 input) {
        if (!this.level().isClientSide && mover.handlesTravel()) {
            // 張り付いている間はバニラの重力・押し出しを走らせない(位置は ClingMover が置く)。
            return;
        }
        super.travel(input);
    }

    @Override
    public boolean isPushable() {
        return !isClinging() && super.isPushable();
    }

    @Override
    public void push(Entity entity) {
        if (!isClinging()) {
            super.push(entity);
        }
    }

    /**
     * 張り付いている間のノックバック: 弱ければ踏ん張って受けない、{@link #KNOCKOFF_STRENGTH} 以上なら剥がれて飛ばされる
     * (11-05 ユーザー決定「強く叩かれたら剥がれて落ちる」)。葉の中では受けない(葉に包まれている)。床ではバニラのまま。
     */
    @Override
    public void knockback(double strength, double x, double z) {
        if (!this.level().isClientSide) {
            if (isClinging()) {
                if (mover.inFoliage() || strength < KNOCKOFF_STRENGTH) {
                    return;
                }
                mover.release(KNOCKOFF_GRAB_COOLDOWN);
            } else if (mover.onFloor()) {
                mover.release(4);
            }
        }
        super.knockback(strength, x, z);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide && isClinging() && !mover.inFoliage() && !this.isInvulnerableTo(source)) {
            recentDamage += amount;
            recentDamageTicks = 20;
            if (recentDamage >= this.getMaxHealth() * KNOCKOFF_DAMAGE_FRACTION) {
                mover.release(KNOCKOFF_GRAB_COOLDOWN);
            }
        }
        return super.hurt(source, amount);
    }

    // ------------------------------------------------------------------ 毎 tick

    @Override
    public boolean isInWall() {
        // クライアントでは使われない処理を省く(大群の軽量化 C、11-18。ClientTickTrim)
        return !ClientTickTrim.skip(this) && super.isInWall();
    }

    @Override
    protected boolean updateInWaterStateAndDoFluidPushing() {
        // 画面側で、どのプレイヤーからも遠い間は水の判定を省き、前の状態のまま(大群の軽量化 C、11-18 ユーザー了承)
        if (com.yayuy.overgrownvegetation.entity.ClientTickTrim.farOnClient(this)) return this.isInWater();
        return super.updateInWaterStateAndDoFluidPushing();
    }

    @Override
    protected void removeFrost() {
        if (!ClientTickTrim.skip(this)) super.removeFrost();
    }

    @Override
    protected void tryAddFrost() {
        if (!ClientTickTrim.skip(this)) super.tryAddFrost();
    }

    @Override
    protected void pushEntities() {
        // クライアントのバニラはプレイヤーだけを押す。触れているプレイヤーがいなければ探さない(結果は同じ)
        if (ClientTickTrim.skip(this) && !ClientTickTrim.anyPlayerTouching(this)) return;
        super.pushEntities();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            Vector3f f = this.entityData.get(DATA_FORWARD);
            pose.tick(syncedAnchor(), new Vec3(f.x, f.y, f.z), this.yBodyRot);
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (recentDamageTicks > 0 && --recentDamageTicks == 0) {
            recentDamage = 0.0F;
        }
        picker.tick();
        tickAction();
        mover.tick();
        if (mover.inFoliage() && syncedSpeed() > 0.01F) {
            foliageCue();
        }
    }

    /** 葉の中を進む間: 葉の粒が落ちて、葉の擦れる音がする(11-05 決定「葉が揺れる・粒が落ちるのを居場所の手がかりに」)。 */
    private void foliageCue() {
        if (!(this.level() instanceof ServerLevel sl)) {
            return;
        }
        BlockPos cell = this.blockPosition();
        BlockState leaf = sl.getBlockState(cell);
        if (this.tickCount % 3 == 0) {
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, leaf),
                    this.getX(), this.getY() + 0.3D, this.getZ(), 2, 0.45D, 0.45D, 0.45D, 0.0D);
        }
        if (this.tickCount % 10 == 0) {
            sl.playSound(null, this.getX(), this.getY(), this.getZ(), leaf.getSoundType(sl, cell, this).getStepSound(),
                    SoundSource.HOSTILE, 0.5F, 0.8F + this.random.nextFloat() * 0.3F);
        }
    }

    // ------------------------------------------------------------------ 行動(層 2)

    private ActionDef def(String id) {
        return DesignData.actions().def(ID, id);
    }

    public int action() {
        return action;
    }

    public boolean isActing() {
        return action != ACT_NONE;
    }

    /** 飛びつきの最中(前触れ・空中・着地の隙)。 */
    public boolean isLeaping() {
        return action == ACT_LEAP_WINDUP || action == ACT_LEAP || action == ACT_LEAP_RECOVER;
    }

    private void setAction(int a) {
        this.action = a;
        this.actionTicks = 0;
        this.entityData.set(DATA_ACTION, (byte) a);
    }

    public int syncedAction() {
        return this.entityData.get(DATA_ACTION);
    }

    /** 箱どうしの隙間(ブロック)。 */
    static double boxGap(AABB a, AABB b) {
        double dx = Math.max(0.0D, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dy = Math.max(0.0D, Math.max(a.minY - b.maxY, b.minY - a.maxY));
        double dz = Math.max(0.0D, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    boolean inMeleeReach(LivingEntity target, double slack) {
        return boxGap(this.getBoundingBox(), target.getBoundingBox()) <= MELEE_GAP + slack && this.hasLineOfSight(target);
    }

    /** 飛びつきの届く距離と高さ(勢いから逆算、{@link LeapArc})。 */
    double[] leapReach() {
        ActionDef d = def("leap");
        return LeapArc.reach(d.get("horizontalSpeed", 1.15D), d.get("lift", 0.55D), LEAP_MAX_AIR);
    }

    /** 技を選んで始める(HuntGoal から毎 tick)。始めたら true。 */
    boolean tryStartAction(LivingEntity target) {
        double dist = Math.sqrt(this.distanceToSqr(target.getX(), this.getY(), target.getZ()));
        boolean reach = inMeleeReach(target, 0.0D);
        double[] lr = leapReach();
        double dy = target.getY() - this.getY();
        ActionDef pick = picker.pick(List.of(def("scythe"), def("leap")), dist, 1.0D, Double.MAX_VALUE, d -> {
            if (d.id().equals("scythe")) {
                return reach;
            }
            return !reach && mover.onFloor() && this.onGround() && !this.isInWater()
                    && dist <= lr[0] * 0.95D && dy <= lr[1] * 0.9D && dy >= -3.5D
                    && this.getSensing().hasLineOfSight(target);
        }, this.random);
        if (pick == null) {
            return false;
        }
        boolean leap = pick.id().equals("leap");
        if (leap) {
            // 頭上の空き(低い天井)を見て弧を決める。低くしても届かなければ跳ばずに歩いて詰める(11-06)。
            double lift = leapLiftFor(target, dist, dy);
            if (lift <= 0.0D) {
                picker.setCooldown("leap", LEAP_RETRY_TICKS);
                return false;
            }
            leapLift = lift;
        }
        picker.used(pick);
        mover.stop();
        setAction(leap ? ACT_LEAP_WINDUP : ACT_SCYTHE);
        leapHit = false;
        leapAirTicks = 0;
        return true;
    }

    /** 低い天井で跳べなかったとき、次に飛びつきを考えるまで(tick)。 */
    static final int LEAP_RETRY_TICKS = 10;
    /** 弧の頂と天井の間に残す隙間(ブロック)。 */
    static final double LEAP_HEAD_CLEARANCE = 0.1D;
    /** 弧を低くするときの最低の上向きの勢い。これより低くしないと届かないなら跳ばない。 */
    static final double LEAP_MIN_LIFT = 0.2D;

    /** 今度の飛びつきの上向きの勢い({@link #leapLiftFor} で決めた値)。 */
    private double leapLift;

    /**
     * 飛びつきの上向きの勢い(11-06、ユーザー報告「天井の低い所で跳ぶと天井に引っ付いて攻撃できない」)。
     * 相手までの道筋の上の<b>頭上の空き</b>を測り、いつもの弧({@code lift})の頂が天井に当たるなら、当たらない高さまで弧を下げる。
     * 下げた弧で相手に届かなければ 0 を返す(跳ばずに歩いて詰める)。
     */
    double leapLiftFor(LivingEntity target, double dist, double dy) {
        ActionDef d = def("leap");
        double h = d.get("horizontalSpeed", 1.15D);
        double lift = d.get("lift", 0.55D);
        double room = headroomToward(target) - this.getBbHeight() - LEAP_HEAD_CLEARANCE;
        if (LeapArc.reach(h, lift, LEAP_MAX_AIR)[1] <= room) {
            return lift;
        }
        // 箱どうしが触れれば当たるので、届く距離は相手の箱の手前まででよい。
        double need = Math.max(0.5D, dist - (this.getBbWidth() + target.getBbWidth()) * 0.5D);
        for (double l = lift - 0.05D; l >= LEAP_MIN_LIFT - 1.0E-6D; l -= 0.05D) {
            if (LeapArc.reach(h, l, LEAP_MAX_AIR)[1] <= room && LeapArc.canReach(h, l, LEAP_MAX_AIR, need, Math.min(dy, room))) {
                return l;
            }
        }
        return 0.0D;
    }

    /** 足元から、相手までの道筋の上でいちばん低い天井までの高さ(ブロック、上限 6)。体の幅の 4 隅と真ん中で測る。 */
    double headroomToward(LivingEntity target) {
        Level level = this.level();
        double x0 = this.getX();
        double z0 = this.getZ();
        double dx = target.getX() - x0;
        double dz = target.getZ() - z0;
        double len = Math.sqrt(dx * dx + dz * dz);
        int samples = Math.max(1, (int) Math.ceil(len / 0.5D));
        double feet = this.getY();
        double best = 6.0D;
        double half = this.getBbWidth() * 0.5D - 0.02D;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double cx = x0 + dx * t;
            double cz = z0 + dz * t;
            for (int c = 0; c < 5; c++) {
                double sx = c == 0 ? cx : cx + ((c & 1) == 0 ? half : -half);
                double sz = c == 0 ? cz : cz + ((c & 2) == 0 ? half : -half);
                for (int k = 1; k <= 6; k++) {
                    p.set(Mth.floor(sx), Mth.floor(feet) + k, Mth.floor(sz));
                    var shape = level.getBlockState(p).getCollisionShape(level, p);
                    if (!shape.isEmpty()) {
                        best = Math.min(best, p.getY() + shape.min(net.minecraft.core.Direction.Axis.Y) - feet);
                        break;
                    }
                }
            }
        }
        return best;
    }

    private void tickAction() {
        if (action == ACT_NONE) {
            return;
        }
        actionTicks++;
        LivingEntity target = this.getTarget();
        if (target != null && target.isAlive()) {
            this.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        switch (action) {
            case ACT_SCYTHE -> {
                if (actionTicks == SCYTHE.executeStart() && target != null && target.isAlive() && inMeleeReach(target, 0.3D)) {
                    this.doHurtTarget(target);
                }
                if (actionTicks >= SCYTHE.total()) {
                    setAction(ACT_NONE);
                }
            }
            case ACT_LEAP_WINDUP -> {
                if (actionTicks >= LEAP.executeStart()) {
                    if (target == null || !target.isAlive() || !mover.onFloor()) {
                        setAction(ACT_NONE);
                        return;
                    }
                    launchLeap(target);
                    setAction(ACT_LEAP);
                }
            }
            case ACT_LEAP -> {
                leapAirTicks++;
                if (!leapHit && target != null && target.isAlive()
                        && this.getBoundingBox().inflate(0.3D).intersects(target.getBoundingBox())) {
                    this.doHurtTarget(target);
                    leapHit = true;
                }
                boolean landed = leapAirTicks > 3 && (this.onGround() || mover.mode() != ClingMover.Mode.FREE || this.isInWater());
                if (landed || leapAirTicks > LEAP_MAX_AIR) {
                    setAction(ACT_LEAP_RECOVER);
                }
            }
            case ACT_LEAP_RECOVER -> {
                mover.stop();
                if (actionTicks >= LEAP.recover()) {
                    setAction(ACT_NONE);
                }
            }
            default -> setAction(ACT_NONE);
        }
    }

    /** GameTest: 乱数を待たずに飛びつきを始める(床に居るときだけ。届くか・頭上の空きの判定は通常と同じ)。始めたら true。 */
    public boolean startLeapForTests(LivingEntity target) {
        if (isActing() || !mover.onFloor() || !this.onGround()) {
            return false;
        }
        double dist = Math.sqrt(this.distanceToSqr(target.getX(), this.getY(), target.getZ()));
        double lift = leapLiftFor(target, dist, target.getY() - this.getY());
        if (lift <= 0.0D) {
            return false;
        }
        picker.used(def("leap"));
        mover.stop();
        leapLift = lift;
        setAction(ACT_LEAP_WINDUP);
        leapHit = false;
        leapAirTicks = 0;
        return true;
    }

    private void launchLeap(LivingEntity target) {
        ActionDef d = def("leap");
        double h = d.get("horizontalSpeed", 1.15D);
        double lift = leapLift > 0.0D ? leapLift : d.get("lift", 0.55D);
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        Vec3 dir = len > 1.0E-4D ? new Vec3(dx / len, 0.0D, dz / len) : this.getLookAngle();
        // 跳んでいる間は天井に張り付かない(床・壁には付いてよい)。天井に当たったら落ちるだけ(11-06)。
        mover.release(4, false);
        this.setDeltaMovement(dir.x * h, lift, dir.z * h);
        this.hasImpulse = true;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        this.setYRot(yaw);
        this.yBodyRot = yaw;
    }

    /** 相手を追い、届けば技を出す。移動は {@link ClingMover}、技は {@link #tryStartAction}。 */
    static final class HuntGoal extends Goal {
        private final ArboraEntity mob;

        HuntGoal(ArboraEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = mob.getTarget();
            return t != null && t.isAlive();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            mob.mover.stop();
        }

        @Override
        public void tick() {
            LivingEntity t = mob.getTarget();
            if (t == null || mob.isActing()) {
                return;
            }
            mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
            if (mob.tryStartAction(t)) {
                return;
            }
            if (mob.inMeleeReach(t, 0.0D)) {
                mob.mover.stop(); // 届いている: 次の鎌まで待つ
            } else {
                mob.mover.follow(t, 1.0D, PATH_REACH);
            }
        }
    }

    // ------------------------------------------------------------------ 見た目

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", TRANSITION_TICKS, this::mainState));
    }

    /** 歩き・走りの再生速度 1.0 のときの体の速さ(ブロック/tick)。【仮 11-05 Claude】runClient で足が滑らないか確かめる。 */
    static final double WALK_NOMINAL = 0.12D;
    static final double RUN_NOMINAL = 0.28D;
    static final double RUN_ABOVE = 0.14D;

    private PlayState mainState(AnimationState<ArboraEntity> state) {
        AnimationController<ArboraEntity> c = state.getController();
        c.setAnimationSpeed(1.0D);
        switch (syncedAction()) {
            case ACT_SCYTHE -> {
                return state.setAndContinue(ATTACK);
            }
            case ACT_LEAP_WINDUP -> {
                return state.setAndContinue(LEAP_WINDUP);
            }
            case ACT_LEAP -> {
                return state.setAndContinue(LEAP_AIR);
            }
            default -> {
            }
        }
        double v = syncedSpeed();
        if (v > 0.01D) {
            boolean run = v > RUN_ABOVE;
            c.setAnimationSpeed(Mth.clamp(v / (run ? RUN_NOMINAL : WALK_NOMINAL), 0.4D, 2.0D));
            return state.setAndContinue(run ? RUN : WALK);
        }
        return state.setAndContinue(IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ------------------------------------------------------------------ 音

    @Override
    protected SoundEvent getAmbientSound() {
        return ModSounds.ARBORA_IDLE.get();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.ARBORA_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.ARBORA_DEATH.get();
    }

    @Override
    protected float getSoundVolume() {
        return 0.6F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SPIDER_STEP, 0.12F, 1.1F);
    }
}
