package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.stilt.StiltParams;
import com.yayuy.overgrownvegetation.entity.stilt.StiltPlanner;
import com.yayuy.overgrownvegetation.entity.stilt.StiltWalker;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.EnumSet;
import java.util.Set;

/**
 * 「フィロセンス」(G6・森の目)— 竹馬型の 1 体目(2026-11-10 段 1: 歩くだけ)。仕様の正本は Vault「Mob の能力/フィロセンス」。
 *
 * <p>長い 4 本脚で胴を地面から 2.5〜6.5 ブロックの高さに運び、足を 1 本ずつ置いて歩く。エンティティの位置と当たり判定は<b>胴</b>
 * (1.5 × 1.5、重力なし)。脚は {@link StiltWalker} がサーバーで置き場を決め、描画が IK で伸ばす。
 *
 * <p>段 1 の行動は「うろつく」だけ({@link StiltStrollGoal})。索敵・覗き込み・脚の当たり判定・攻撃はまだ無い。旧フィロセンス(長い首の小さな多脚)の
 * コードは {@code overgrown/parked/} にあり、別の Mob(洞窟の目)になる予定。
 */
public class PhyllosensEntity extends Monster implements GeoEntity, ParasitizedMob, StiltWalker.Holder {

    public static final String ID = "phyllosens";
    private static final boolean DEV_HOOKS = Boolean.getBoolean("ov.devbridge");
    private static final EntityDataAccessor<CompoundTag> DATA_GAIT = SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** 段 2(11-14): 頭を置く点・見る点(エンティティからの差)と、倒す角・首の効き・芯の光(PhyllosensGaze#packed)。 */
    private static final EntityDataAccessor<org.joml.Vector3f> DATA_HEAD = SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<org.joml.Vector3f> DATA_LOOK = SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Integer> DATA_NECK = SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.INT);
    /** 段 2: 行動の状態(PhyllosensBrain.Mode の番号 | 見つけた 0x100)。アニメを選ぶ。 */
    private static final EntityDataAccessor<Integer> DATA_MODE = SynchedEntityData.defineId(PhyllosensEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation DISGUISE = RawAnimation.begin().thenLoop("idle_disguise");
    private static final RawAnimation WATCH = RawAnimation.begin().thenPlay("watch_open").thenLoop("watch_sweep");
    private static final RawAnimation LOCK = RawAnimation.begin().thenPlay("watch_lock");
    /** 歩きのアニメの基準の速さ(ブロック/tick)。r2 の生成器: 歩幅 28 単位・周期 2.0 秒。 */
    static final double WALK_REF = 0.058D;
    /** 描画: root を下げる量(モデル単位)。モデルの root の pivot は y 104(= 6.5 ブロック)、胴の中心はエンティティの位置 + 0.75(= 12)。 */
    public static final float ROOT_SHIFT = 104.0F - 12.0F;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final StiltWalker walker;
    private final StiltWalker.Client clientFeet;
    /** 描画の歩きの速さ(クライアント、なめらかにしたもの)。 */
    private double clientWalk;
    /** 首と頭(段 2)。 */
    private final PhyllosensGaze gaze = new PhyllosensGaze();
    private final PhyllosensBrain brain;
    private final com.yayuy.overgrownvegetation.entity.parts.HitPartSet hitParts;

    public PhyllosensEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        StiltParams p = StiltParams.phyllosens();
        this.walker = new StiltWalker(this, this, p);
        this.clientFeet = new StiltWalker.Client(p.homeLeft.length, p.swingLift);
        this.setNoGravity(true);
        this.xpReward = 12;
        // 段 2(11-14): 見る行動と、叩ける頭(部位の id は本体の続き番号。ファルキムネスと同じ作り)
        this.brain = new PhyllosensBrain(this, this.walker);
        this.hitParts = new com.yayuy.overgrownvegetation.entity.parts.HitPartSet(this, new String[]{"head"}, new float[]{p.headSize}, new float[]{p.headSize});
        this.hitParts.onHurt((part, src) -> this.brain.onHeadHit());
        this.setId(ENTITY_COUNTER.getAndAdd(this.hitParts.count() + 1) + 1);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_GAIT, new CompoundTag());
        builder.define(DATA_HEAD, new org.joml.Vector3f());
        builder.define(DATA_LOOK, new org.joml.Vector3f());
        builder.define(DATA_NECK, 1000);
        builder.define(DATA_MODE, 0);
    }

    public PhyllosensGaze gaze() {
        return this.gaze;
    }

    public PhyllosensBrain brain() {
        return this.brain;
    }

    // ------------------------------------------------------------------ 叩ける頭(段 2、11-14。段 3 で脚 4 本を足す)

    @Override
    public void setId(int id) {
        super.setId(id);
        if (this.hitParts != null) {
            this.hitParts.assignIds(id + 1);   // コンストラクタの setId より前(Entity のコンストラクタの中)には呼ばれない
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public net.neoforged.neoforge.entity.PartEntity<?>[] getParts() {
        return this.hitParts.parts();
    }

    public com.yayuy.overgrownvegetation.entity.parts.HitPartSet hitParts() {
        return this.hitParts;
    }

    /** 頭の部位を花(つぼみ)の真ん中へ置く(両側。クライアントは照準の当たりに使う)。 */
    private void placeHead() {
        if (this.hitParts == null) return;
        net.minecraft.world.phys.Vec3 c = this.gaze.eyeWorld(this);
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        double dx = c.x - this.getX(), dz = c.z - this.getZ();
        double fwd = dx * -Mth.sin(yaw) + dz * Mth.cos(yaw), left = dx * Mth.cos(yaw) + dz * Mth.sin(yaw);
        this.hitParts.place(this, this.getYRot(), new double[]{fwd, left, c.y - this.getY()});
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            this.gaze.clientTick(this.entityData.get(DATA_HEAD), this.entityData.get(DATA_LOOK), this.entityData.get(DATA_NECK));
        } else {
            this.gaze.serverTick(this);
            this.entityData.set(DATA_HEAD, this.gaze.headData());
            this.entityData.set(DATA_LOOK, this.gaze.lookData());
            this.entityData.set(DATA_NECK, this.gaze.packed());
            this.entityData.set(DATA_MODE, this.brain.mode().ordinal() | (this.brain.locked() ? 0x100 : 0));
        }
        placeHead();
    }

    @Override
    public StiltWalker stiltWalker() {
        return this.walker;
    }

    @Override
    public void sendGait(CompoundTag tag) {
        this.entityData.set(DATA_GAIT, tag);
    }

    public StiltWalker.Client clientFeet() {
        return this.clientFeet;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_GAIT.equals(key) && this.level().isClientSide()) {
            CompoundTag t = this.entityData.get(DATA_GAIT);
            if (t.contains("f")) {
                this.clientFeet.accept(t, this.tickCount, 0.0F);
            }
        }
    }

    // ------------------------------------------------------------------ 動き(胴は浮いている。動かすのは StiltWalker だけ)

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(6, new StiltStrollGoal(this));
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (this.isAlive()) {
            this.brain.tick();
            this.walker.tick();
        }
    }

    @Override
    public void travel(Vec3 input) {
        // 胴は StiltWalker が動かす(重力・摩擦・押し流しは使わない)
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new StiltNavigation(this, level);
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                PhyllosensEntity.this.yBodyRot = PhyllosensEntity.this.getYRot();
                PhyllosensEntity.this.yHeadRot = PhyllosensEntity.this.getYRot();
            }
        };
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, net.minecraft.world.level.block.state.BlockState state, BlockPos pos) {
    }

    /** かがんで葉の下をくぐる胴が窒息しないように(胴の箱は経路が葉・枝に当てないが、目の点は箱の縁に近い)。 */
    @Override
    public boolean isInWall() {
        return false;
    }

    /** 胴は小さいが、脚は 6 ブロック下の地面まで・首と花は 6 ブロック上まである。画面の外に胴があっても脚を描く。 */
    @Override
    public AABB getBoundingBoxForCulling() {
        return this.getBoundingBox().expandTowards(0.0D, -7.0D, 0.0D).expandTowards(0.0D, 7.5D, 0.0D).inflate(3.0D, 0.0D, 3.0D);
    }

    /** この個体から {@code range} ブロック以内にプレイヤー(観戦も含む)がいるか。 */
    boolean playerWithin(double range) {
        double r2 = range * range;
        for (Player pl : this.level().players()) {
            if (pl.distanceToSqr(this) <= r2) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 見た目

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 6, this::mainState));
    }

    private PlayState mainState(AnimationState<PhyllosensEntity> state) {
        // 歩きの速さ: その tick に胴が横へ動いた量 / 基準。止まると 0(歩きの形のまま止まる。待機の「枯れ木に化ける」は段 2)。
        // 脚は IK なので足は滑らない。ここで合わせるのは胴・首・花の揺れの速さ(記憶 walk-anim-match-move-speed)
        double dx = this.getX() - this.xo, dz = this.getZ() - this.zo;
        double v = Math.sqrt(dx * dx + dz * dz) / WALK_REF;
        this.clientWalk = Mth.lerp(0.2D, this.clientWalk, Mth.clamp(v, 0.0D, 3.0D));
        // 段 2(11-14): 化ける・覗く(つぼみ。首は手続き)は idle_disguise、見張りは watch_open → watch_sweep、見つけたら watch_lock
        int m = this.entityData.get(DATA_MODE);
        PhyllosensBrain.Mode mode = PhyllosensBrain.Mode.values()[Mth.clamp(m & 0xFF, 0, PhyllosensBrain.Mode.values().length - 1)];
        switch (mode) {
            case DISGUISE, PEEK, PEER -> {
                state.getController().setAnimationSpeed(1.0D);
                return state.setAndContinue(DISGUISE);
            }
            case WATCH -> {
                state.getController().setAnimationSpeed(1.0D);
                return state.setAndContinue((m & 0x100) != 0 ? LOCK : WATCH);
            }
            default -> {
            }
        }
        state.getController().setAnimationSpeed(this.clientWalk);
        return state.setAndContinue(WALK);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    // ------------------------------------------------------------------ 経路(バニラの経路探索の代わり)

    /**
     * バニラの {@code PathNavigation} の口を竹馬の経路へつなぐ。{@code moveTo} の行き先を {@link StiltWalker#goTo} へ渡し、
     * バニラの経路は作らない({@code createPath} は null)。うろつき・よその Goal の {@code isDone} はここで答える。
     */
    static final class StiltNavigation extends GroundPathNavigation {
        private final PhyllosensEntity mob;

        StiltNavigation(PhyllosensEntity mob, Level level) {
            super(mob, level);
            this.mob = mob;
        }

        @Override
        public boolean moveTo(double x, double y, double z, double speed) {
            this.mob.walker.goTo(x, y, z, speed);
            return true;
        }

        @Override
        public boolean moveTo(Entity entity, double speed) {
            this.mob.walker.goTo(entity.getX(), entity.getY(), entity.getZ(), speed);
            return true;
        }

        @Override
        public boolean moveTo(@Nullable Path path, double speed) {
            if (path == null) return false;
            BlockPos t = path.getTarget();
            this.mob.walker.goTo(t.getX() + 0.5D, t.getY(), t.getZ() + 0.5D, speed);
            return true;
        }

        @Nullable
        @Override
        public Path createPath(BlockPos pos, int accuracy) {
            return null;
        }

        @Nullable
        @Override
        public Path createPath(Entity entity, int accuracy) {
            return null;
        }

        @Nullable
        @Override
        protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy, float followRange) {
            return null;
        }

        @Override
        public boolean isDone() {
            return !this.mob.walker.moving();
        }

        @Override
        public boolean isInProgress() {
            return this.mob.walker.moving();
        }

        @Override
        public void stop() {
            this.mob.walker.stop();
        }

        @Override
        public void tick() {
        }
    }

    // ------------------------------------------------------------------ うろつき

    /** うろつきを始める、プレイヤーからの距離(ブロック)【仮】。遠くの個体は動かさない(重さのため。ファルキムネスと同じ 64)。 */
    static final double STROLL_RANGE = 64.0D;
    /** うろつきの間隔(平均の tick)と行き先の範囲(横、ブロック)【仮】。 */
    static final int STROLL_INTERVAL = 100, STROLL_XZ = 14;

    /**
     * 標的のいない間、歩き回る(段 1)。バニラの {@code RandomStrollGoal} は「動きの無い時間」を見るので、クリエイティブ・観戦で見ていると
     * 始まらない(記憶 vanilla-stroll-stops-near-creative)。ここでは {@link #STROLL_RANGE} 以内にだれかいるときだけ、
     * まわりの角を数個引いて、胴が立てる所({@link StiltPlanner#evaluate})へ歩く。
     */
    static final class StiltStrollGoal extends Goal {
        private final PhyllosensEntity mob;

        StiltStrollGoal(PhyllosensEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            // 確かめ用(テストクライアント・GameTest): 名前が nostroll で始まる個体はうろつかない(navto の行き先を上書きしないように)
            if (this.mob.hasCustomName() && this.mob.getCustomName().getString().startsWith("nostroll")) return false;
            if (this.mob.walker.moving() || !this.mob.walker.ready()) return false;
            if (this.mob.brain.mode() != PhyllosensBrain.Mode.WALK) return false;   // 段 2: 化けている・見張り・覗きの間はうろつかない
            if (this.mob.getRandom().nextInt(STROLL_INTERVAL) != 0) return false;
            return this.mob.playerWithin(STROLL_RANGE);
        }

        @Override
        public void start() {
            if (!(this.mob.level() instanceof ServerLevel level)) return;
            for (int k = 0; k < 6; k++) {
                int x = Mth.floor(this.mob.getX() + 0.5D) + this.mob.getRandom().nextInt(STROLL_XZ * 2 + 1) - STROLL_XZ;
                int z = Mth.floor(this.mob.getZ() + 0.5D) + this.mob.getRandom().nextInt(STROLL_XZ * 2 + 1) - STROLL_XZ;
                double ref = this.mob.getY() - 5.0D;
                StiltPlanner.Node nd = StiltPlanner.evaluate(level, this.mob.walker.p, x, z, ref);
                if (nd == null) continue;
                this.mob.getNavigation().moveTo(x, nd.b(), z, 1.0D);
                return;
            }
        }

        @Override
        public boolean canContinueToUse() {
            return this.mob.walker.moving();
        }

        @Override
        public void stop() {
            this.mob.walker.stop();
        }
    }
}
