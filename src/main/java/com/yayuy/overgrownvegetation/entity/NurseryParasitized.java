package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.G1Fate;
import com.yayuy.overgrownvegetation.entity.g1.G1Nursery;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * G1 の<b>苗床</b>(2026-11-13、司令塔 第 1 段の区切り 6)。司令塔が「苗床」に決めた段階 0 が、森から離れた所で入れ替わる({@link G1Fate})。
 * 種の値は {@link G1Nursery}(11 種。動きは共通)。仕様の正本は Vault「森の知性(司令塔)」。
 *
 * <ul>
 *   <li><b>戦わない・動かない</b>: 攻撃も標的も無い。押されない。向きは変わらない。</li>
 *   <li><b>萌芽体をただで生む</b>(D-9): 一定の間ごとに芽を 1 つ育て({@code sprout_N})、近くに萌芽体({@link BlightFernEntity})を出す。予算は払わない。
 *   <b>その場所が汚染に飲まれたら生まなくなる</b>(11-13 ユーザー「汚染に飲まれたら萌芽体を生まなくなる」)。「飲まれた」= 苗床のいる升が
 *   過繁茂の台帳に入った【仮】。</li>
 *   <li><b>スポナーの口</b>(D-9・D-14): 一定の間ごとに司令塔へ「孵してよいか」を聞く({@link ForestDirector#buySpawn})。司令塔が予算を払えば、
 *   大きな嚢を 1 つ割って({@code hatch_N})G4〜G8 を 1 体出す。汚染に飲まれていても・いなくても働く(11-13 ユーザー)。
 *   <b>今は出せる Mob が登録されていない</b>(G4 以降は退避中)ので、何も孵さず予算も払わない。</li>
 *   <li><b>弱らない</b>(11-13 ユーザー「B 案」): 明かり・乾きで弱る仕組みは無い。</li>
 *   <li>生える {@code emerge}(入れ替わった直後。その間は何も生まない)・枯れて崩れる {@code death_wither}(倒すと消える。ドロップなし【仮】)。</li>
 * </ul>
 * 数(間隔・萌芽体の上限・出す場所)はどれも【仮】。音は足していない。
 */
public class NurseryParasitized extends Monster implements ParasitizedMob, GeoEntity, G1Fate.Emerging {

    public static final int ACTION_NONE = 0, ACTION_EMERGE = 1, ACTION_SPROUT = 2, ACTION_HATCH = 3;

    private static final EntityDataAccessor<Integer> ACTION =
            SynchedEntityData.defineId(NurseryParasitized.class, EntityDataSerializers.INT);
    /** 今流している芽・嚢の番号(1 から)。 */
    private static final EntityDataAccessor<Integer> SLOT =
            SynchedEntityData.defineId(NurseryParasitized.class, EntityDataSerializers.INT);

    // ------------------------------------------------------------------ 【仮】の数値
    /** 萌芽体を生む間隔(最小〜最大 tick)。 */
    static final int SPROUT_INTERVAL_MIN = 1200, SPROUT_INTERVAL_MAX = 2400;
    /** 周りにこの数の萌芽体がいたら生まない(半径)。 */
    static final int FERN_LIMIT = 3;
    static final double FERN_LIMIT_RADIUS = 16.0D;
    /** 萌芽体を出す所: 苗床からこの長さの輪の上(最小〜最大)。 */
    static final double FERN_NEAR = 1.5D, FERN_FAR = 4.0D;
    /** 司令塔へ「孵してよいか」を聞く間隔(tick)。 */
    static final int HATCH_ASK_INTERVAL = 1200;
    /** 飲まれたかを見直す間隔(tick)。 */
    static final int SWALLOWED_CHECK = 100;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation EMERGE = RawAnimation.begin().thenPlayAndHold("emerge");
    private static final RawAnimation WITHER = RawAnimation.begin().thenPlayAndHold("death_wither");
    private static final Map<String, RawAnimation> ONCE = new HashMap<>();

    private static RawAnimation once(String name) {
        return ONCE.computeIfAbsent(name, n -> RawAnimation.begin().thenPlay(n));
    }

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private G1Nursery nursery;
    private int actionLeft;
    private int actionAge;
    private int sproutIn = -1;
    private int hatchAskIn = HATCH_ASK_INTERVAL;
    /** 汚染に飲まれたか(飲まれたら萌芽体を生まない)。見直しは {@link #SWALLOWED_CHECK} ごと。 */
    private boolean swallowed;
    /** 孵す Mob(司令塔が払った分)。hatch の tick 24 に出す。 */
    @Nullable
    private EntityType<? extends Mob> hatching;
    private float anchorYaw = Float.NaN;
    /** テスト用の差し替え: 飲まれたか・出せる Mob。 */
    @Nullable
    private Boolean swallowedOverride;
    @Nullable
    private List<Supplier<? extends EntityType<? extends Mob>>> spawnPoolOverride;

    public NurseryParasitized(EntityType<? extends NurseryParasitized> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public G1Nursery nursery() {
        if (nursery == null) {
            nursery = G1Nursery.of(getType());
            if (nursery == null) nursery = G1Nursery.ZOMBIE;
        }
        return nursery;
    }

    public static AttributeSupplier.Builder attributes(G1Nursery n) {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, n.health)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Override
    protected void registerGoals() {
        // 戦わない・動かない: Goal は無い
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ACTION, ACTION_NONE);
        builder.define(SLOT, 1);
    }

    public int action() {
        return this.entityData.get(ACTION);
    }

    public int slot() {
        return this.entityData.get(SLOT);
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
    }

    @Override
    public void travel(Vec3 input) {
        Vec3 v = getDeltaMovement();
        setDeltaMovement(0.0D, v.y, 0.0D);
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

    // ------------------------------------------------------------------ 生える
    @Override
    public void startEmerge() {
        begin(ACTION_EMERGE, G1Nursery.EMERGE);
        if (!level().isClientSide()) playSound(ModSounds.G1_PLANT_EMERGE.get(), 1.0F, 1.0F); // 音量【仮】
    }

    public boolean isEmerging() {
        return action() == ACTION_EMERGE;
    }

    // ------------------------------------------------------------------ 飲まれたか
    /** その場所が汚染に飲まれたか(= 萌芽体を生まなくなる)。台帳はオーバーワールドのもの。 */
    public boolean swallowed() {
        return swallowedOverride != null ? swallowedOverride : swallowed;
    }

    private void checkSwallowed(ServerLevel level) {
        swallowed = level.dimension() == Level.OVERWORLD && PhaseData.get(level).isOvergrownChunk(new ChunkPos(blockPosition()));
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
        if (Float.isNaN(anchorYaw)) anchorYaw = getYRot();
        setYRot(anchorYaw);
        this.yRotO = anchorYaw;
        this.yBodyRot = anchorYaw;
        this.yBodyRotO = anchorYaw;
        this.yHeadRot = anchorYaw;
        this.yHeadRotO = anchorYaw;
        if (!isAlive() || !(level() instanceof ServerLevel level)) return;
        if (tickCount % com.yayuy.overgrownvegetation.entity.g1.G1Unbury.INTERVAL == 0) com.yayuy.overgrownvegetation.entity.g1.G1Unbury.liftIfBuried(this); // 木の幹に埋まったら上へ出る(R6-7)
        if (tickCount % SWALLOWED_CHECK == 1) checkSwallowed(level);
        if (sproutIn < 0) sproutIn = nextSproutDelay();
        int action = action();
        if (action != ACTION_NONE) {
            if (action == ACTION_SPROUT && actionAge == G1Nursery.SPROUT_AT) sproutFern(level);
            if (action == ACTION_HATCH && actionAge == G1Nursery.HATCH_AT) {
                playSound(ModSounds.G1_SAC_BURST.get(), 1.0F, 0.8F); // 大きな嚢が割れる。音量・高さ【仮】
                hatch(level);
            }
            actionAge++;
            if (--actionLeft <= 0) begin(ACTION_NONE, 0);
            return;
        }
        // スポナーの口: 飲まれていても・いなくても働く。司令塔が払ったときだけ孵す
        if (--hatchAskIn <= 0) {
            hatchAskIn = HATCH_ASK_INTERVAL;
            List<Supplier<? extends EntityType<? extends Mob>>> pool = spawnPoolOverride != null ? spawnPoolOverride : ForestDirector.nurserySpawns();
            EntityType<? extends Mob> bought = ForestDirector.get(level).buySpawn(level, random, pool);
            if (bought != null) {
                hatching = bought;
                this.entityData.set(SLOT, 1 + random.nextInt(nursery().pods));
                begin(ACTION_HATCH, G1Nursery.HATCH);
                return;
            }
        }
        // 萌芽体: ただで生む。飲まれたら生まない
        if (--sproutIn <= 0) {
            sproutIn = nextSproutDelay();
            if (!swallowed() && fernsNearby(level) < FERN_LIMIT) {
                this.entityData.set(SLOT, 1 + random.nextInt(nursery().buds));
                begin(ACTION_SPROUT, G1Nursery.SPROUT);
                playSound(ModSounds.NURSERY_SPROUT.get(), 1.0F, 1.0F); // 弾けるのは音の頭から 12 tick = SPROUT_AT。音量【仮】
            }
        }
    }

    private int nextSproutDelay() {
        return SPROUT_INTERVAL_MIN + random.nextInt(SPROUT_INTERVAL_MAX - SPROUT_INTERVAL_MIN + 1);
    }

    private int fernsNearby(ServerLevel level) {
        return level.getEntitiesOfClass(BlightFernEntity.class, getBoundingBox().inflate(FERN_LIMIT_RADIUS, 6.0D, FERN_LIMIT_RADIUS)).size();
    }

    /** 苗床のまわりの、立てる所(下が固く、そこと上が空いている)を 1 つ。無ければ null。 */
    @Nullable
    private BlockPos spotAround(ServerLevel level, double near, double far) {
        BlockPos base = blockPosition();
        for (int tries = 0; tries < 16; tries++) {
            double a = random.nextDouble() * Math.PI * 2.0D;
            double d = near + random.nextDouble() * (far - near);
            int x = (int) Math.floor(getX() + Math.cos(a) * d), z = (int) Math.floor(getZ() + Math.sin(a) * d);
            for (int dy = 1; dy >= -2; dy--) {
                BlockPos p = new BlockPos(x, base.getY() + dy, z);
                if (level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)
                        && level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                        && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                        && level.getFluidState(p).isEmpty()) {
                    return p;
                }
            }
        }
        return null;
    }

    /** 芽の頭が抜ける瞬間: 近くに萌芽体を 1 体出す(予算は払わない)。 */
    private void sproutFern(ServerLevel level) {
        BlockPos p = spotAround(level, FERN_NEAR, FERN_FAR);
        if (p != null) BlightFernPlacement.spawn(level, p, MobSpawnType.MOB_SUMMONED);
    }

    /** 大きな嚢が割れる瞬間: 司令塔が払った Mob を 1 体出す。 */
    private void hatch(ServerLevel level) {
        EntityType<? extends Mob> type = hatching;
        hatching = null;
        if (type == null) return;
        BlockPos p = spotAround(level, 1.0D, 2.5D);
        Mob mob = type.create(level);
        if (mob == null) return;
        Vec3 at = p != null ? Vec3.atBottomCenterOf(p) : position();
        mob.moveTo(at.x, at.y, at.z, random.nextFloat() * 360.0F, 0.0F);
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.SPAWNER, null);
        level.addFreshEntity(mob);
    }

    // ------------------------------------------------------------------ 枯れて崩れる
    @Override
    protected void tickDeath() {
        if (deathTime == 0 && !level().isClientSide()) playSound(ModSounds.G1_PLANT_WITHER.get(), 1.0F, 1.0F); // 音量【仮】
        ++deathTime;
        if (deathTime >= G1Nursery.WITHER && !level().isClientSide() && !isRemoved()) {
            remove(RemovalReason.KILLED);
        }
    }

    // ------------------------------------------------------------------ 保存
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (!Float.isNaN(anchorYaw)) tag.putFloat("AnchorYaw", anchorYaw);
        tag.putInt("SproutIn", sproutIn);
        tag.putInt("HatchAskIn", hatchAskIn);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("AnchorYaw")) anchorYaw = tag.getFloat("AnchorYaw");
        if (tag.contains("SproutIn")) sproutIn = tag.getInt("SproutIn");
        if (tag.contains("HatchAskIn")) hatchAskIn = tag.getInt("HatchAskIn");
    }

    // ------------------------------------------------------------------ アニメ
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "act", 5, state -> {
            AnimationController<NurseryParasitized> ctl = state.getController();
            if (isDeadOrDying()) {
                ctl.transitionLength(2);
                return state.setAndContinue(WITHER);
            }
            switch (action()) {
                case ACTION_EMERGE -> {
                    ctl.transitionLength(0);
                    return state.setAndContinue(EMERGE);
                }
                case ACTION_SPROUT -> {
                    // sprout_N・hatch_N は静止の姿で始まって静止の姿で終わるので、待機との間は短い補間でつなぐ
                    ctl.transitionLength(2);
                    return state.setAndContinue(once("sprout_" + slot()));
                }
                case ACTION_HATCH -> {
                    ctl.transitionLength(2);
                    return state.setAndContinue(once("hatch_" + slot()));
                }
                default -> {
                }
            }
            ctl.transitionLength(5);
            return state.setAndContinue(IDLE);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ------------------------------------------------------------------ 確かめ・テスト用
    /** 次に萌芽体を生むまで・次に司令塔へ聞くまでの残り(tick)を決める。 */
    public void setTimersForTests(int sproutIn, int hatchAskIn) {
        this.sproutIn = sproutIn;
        this.hatchAskIn = hatchAskIn;
    }

    public void setSwallowedForTests(@Nullable Boolean swallowed) {
        this.swallowedOverride = swallowed;
    }

    public void setSpawnPoolForTests(@Nullable List<Supplier<? extends EntityType<? extends Mob>>> pool) {
        this.spawnPoolOverride = pool;
    }
}
