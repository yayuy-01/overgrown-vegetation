package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.entity.crawler.SmallCrawler;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModCrawlers;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import com.yayuy.overgrownvegetation.spawn.EnemySpawns;
import com.yayuy.overgrownvegetation.spawn.SpawnTable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * ゲスタトリクス {@code gestatrix}(G6・歩く苗床。2026-11-17 ユーザー決定。正本は Vault「歩く苗床(産む G6)と苗床の生産(11-17)」)。
 * 元の生き物は子を背負うコモリグモ。背中いっぱいの胞子嚢の苗床から、小さな手先(グレガリア)を産む。
 *
 * <ul>
 *   <li><b>歩く</b>: 縁の帯から、拠点(送り込みで来たならその拠点・近くの認識した拠点)かプレイヤーのほうへゆっくり歩く。
 *   {@code settleBlocks} まで近づくか、{@code roamTicks} 歩いたら腰を据える。腰を据えたらもう立たない。</li>
 *   <li><b>産む</b>: 前触れ {@link #WINDUP_T} tick(背の嚢が膨らんで光る・胞子が漏れる)→ グレガリアを {@code broodMin〜broodMax} 匹、背から散らす
 *   → {@code restMin〜restMax} tick 休む → くり返す。産んだ手先は縁の上限とは別に、1 匹につき {@code broodCap} まで(超えるなら休みを延ばす)。</li>
 *   <li><b>止め方</b>: 前触れの間に背の嚢へ当たると、その回は産めない({@link #hitsSac})。産むたびに嚢が 1 つ減り、{@code birthsToRoot} 回産むと
 *   その場に根を下ろして苗床になる。倒せば苗床にならない。</li>
 *   <li><b>根付いた姿</b>(11-17 enemy-spawns が選んだ: G1 の苗床の型 {@code G1Nursery} は G1 の 11 種と素材の名前に結び付いているので広げず、
 *   この個体が動かなくなって苗床の働きをする): 萌芽体をただで生み(汚染に飲まれたら生まない)、司令塔へ「孵してよいか」を聞いて G4 以降を孵す。
 *   値は今の苗床({@link NurseryParasitized})と同じ design の値を共有する。役目を終えたら枯れる。</li>
 * </ul>
 * 数は design/spawns.json の {@code gestatrix}(どれも【仮】)。時刻(前触れ・産む瞬間など)はアニメと対なのでここに書く【仮】。
 * 見た目はモデル制作(Fable)が作っている間、グレガリアの形を大きくした仮のモデル(アニメは idle・walk だけ。{@link #FINAL_CLIPS})。
 */
public class GestatrixEntity extends SmallCrawler implements GeoEntity, ParasitizedMob {

    public static final String ID = "gestatrix";

    /** 仮のモデルの描画倍率(グレガリアの形を大きくしている)。 */
    public static final float RENDER_SCALE = 1.6F;
    /**
     * 本物のアニメ(settle・birth_windup・birth・birth_cancel・root・rooted_idle・death)が来たら true にする。
     * false の間は、仮のモデルにあるクリップ(idle・walk)だけを流す(無いクリップを流すと GeckoLib が毎フレーム警告を出す)。
     */
    public static final boolean FINAL_CLIPS = false;

    // ------------------------------------------------------------------ 状態
    public static final int ROAM = 0, SETTLE = 1, REST = 2, WINDUP = 3, BIRTH = 4, CANCEL = 5, ROOTING = 6, ROOTED = 7;

    /** 腰を据える・前触れ・産む(散らすのは BIRTH_AT)・取りやめ・根を下ろすの長さ(tick)【仮】。前触れはユーザー案の約 2 秒。 */
    public static final int SETTLE_T = 30, WINDUP_T = 40, BIRTH_T = 24, BIRTH_AT = 8, CANCEL_T = 20, ROOT_T = 60;
    /** 根付いた姿の芽・嚢の長さと、萌芽体・Mob を出す tick(G1 の苗床のクリップと同じ長さにしておく)【仮】。 */
    public static final int SPROUT_T = 32, SPROUT_AT = 12, HATCH_T = 60, HATCH_AT = 24;
    /** 背の嚢の高さ(体の高さに掛ける): これより上に当たった弾・ここより上から殴った手は嚢に当たる【仮】。 */
    public static final double SAC_LINE = 0.55D;
    /** 歩く: 行き先を引き直す間(tick)。 */
    static final int REPATH = 40;
    /** 回る速さ(度/tick)・うろつきの速さ(移動の速さの属性に掛ける)【仮】。 */
    static final float TURN_PER_TICK = 8.0F;
    static final double STROLL_SPEED = 0.6D;

    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(GestatrixEntity.class, EntityDataSerializers.INT);
    /** 残りの嚢の数(産むたびに減る。見た目で嚢を消す・しぼませる)。 */
    private static final EntityDataAccessor<Integer> SACS = SynchedEntityData.defineId(GestatrixEntity.class, EntityDataSerializers.INT);
    /** 根付いた姿で流している芽・嚢の番号(1 から。0 = 何もしていない。負 = 嚢)。 */
    private static final EntityDataAccessor<Integer> NURSE = SynchedEntityData.defineId(GestatrixEntity.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    private int stateLeft;
    private int stateAge;
    private int roamAge;
    private int repathIn;
    private int births;
    private int cancels;
    /** 送り込みで向かう拠点の真ん中(無ければ NaN)。 */
    private double baseX = Double.NaN, baseZ = Double.NaN;
    private final List<UUID> brood = new ArrayList<>();
    // 根付いた姿
    private int nurseLeft, nurseAge;
    private int sproutIn = -1, hatchAskIn = -1;
    private int sprouted, hatches;
    @Nullable
    private EntityType<? extends Mob> hatching;
    private float anchorYaw = Float.NaN;
    /** テスト用: 司令塔の代わりに孵す Mob。 */
    @Nullable
    private List<Supplier<? extends EntityType<? extends Mob>>> spawnPoolOverride;

    public GestatrixEntity(EntityType<? extends GestatrixEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 10);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    private static SpawnTable.Gestatrix table() {
        return DesignData.spawns().gestatrix;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STATE, ROAM);
        builder.define(SACS, 4);
        builder.define(NURSE, 0);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals(); // 浮く・うろつき(腰を据えたら isSteered で止まる)
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
    protected double bodyHalfLength() {
        return 0.9D;
    }

    /** 歩いていない間はうろつかない。 */
    @Override
    public boolean isSteered() {
        return state() != ROAM || super.isSteered();
    }

    public int state() {
        return this.entityData.get(STATE);
    }

    public int sacs() {
        return this.entityData.get(SACS);
    }

    public int births() {
        return births;
    }

    public int cancels() {
        return cancels;
    }

    public boolean rooted() {
        return state() == ROOTED;
    }

    private void enter(int state, int ticks) {
        this.entityData.set(STATE, state);
        this.stateLeft = ticks;
        this.stateAge = 0;
    }

    /** 送り込み(司令塔)で拠点へ向かわせる。 */
    public void sendTo(BaseMemory.Base b) {
        this.baseX = b.centerX();
        this.baseZ = b.centerZ();
    }

    // ------------------------------------------------------------------ 腰を据えたら動かない
    @Override
    public void travel(Vec3 input) {
        if (state() == ROAM) {
            super.travel(input);
            return;
        }
        Vec3 v = getDeltaMovement();
        setDeltaMovement(0.0D, v.y, 0.0D);
        super.travel(Vec3.ZERO);
        v = getDeltaMovement();
        setDeltaMovement(0.0D, v.y, 0.0D);
    }

    @Override
    public boolean isPushable() {
        return state() == ROAM && super.isPushable();
    }

    @Override
    public void knockback(double strength, double x, double z) {
        if (state() == ROAM) super.knockback(strength, x, z);
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        // 根付いた後は苗床と同じく、育った木・汚染ブロックに埋まっても窒息しない
        return rooted() && (source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.CRAMMING)) || super.isInvulnerableTo(source);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return !rooted() && super.removeWhenFarAway(distanceToClosestPlayer);
    }

    // ------------------------------------------------------------------ 弱点
    /** 前触れの間に背の嚢へ当たったか。弾は当たった高さ、手で殴ったのは殴った者の目の高さが嚢の線より上なら当たり【仮】。 */
    public boolean hitsSac(DamageSource source) {
        double line = getY() + getBbHeight() * SAC_LINE;
        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile) return direct.getY() + direct.getBbHeight() * 0.5D >= line;
        Entity by = source.getEntity();
        return by != null && direct == by && by.getEyeY() >= line;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide() && isAlive() && state() == WINDUP && hitsSac(source)) {
            cancels++;
            enter(CANCEL, CANCEL_T);
            playSound(ModSounds.G1_SAC_BURST.get(), 1.0F, 1.3F); // 嚢が割れて縮む。音・高さ【仮】
            if (level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, getX(), getY() + getBbHeight() * 0.8D, getZ(), 24, 0.5D, 0.2D, 0.5D, 0.02D);
            }
        }
        return hurt;
    }

    // ------------------------------------------------------------------ tick
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() || !(level() instanceof ServerLevel level) || !isAlive()) return;
        int state = state();
        if (state == ROOTED) {
            holdYaw();
            tickRooted(level);
            return;
        }
        if (state == ROAM) {
            tickRoam(level);
            return;
        }
        holdYaw();
        getNavigation().stop();
        stateAge++;
        if (state == WINDUP && (stateAge & 3) == 0) {
            // 前触れ: 背の嚢から胞子が漏れる(仮のモデルでも読めるように)
            level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, getX(), getY() + getBbHeight() * 0.9D, getZ(), 3, 0.4D, 0.1D, 0.4D, 0.0D);
        }
        if (state == BIRTH && stateAge == BIRTH_AT) giveBirth(level);
        if (--stateLeft > 0) return;
        switch (state) {
            case SETTLE, CANCEL -> rest();
            case REST -> {
                if (broodRoom(level) <= 0) {
                    stateLeft = 100; // 手先が上限まで生きている: 減るまで待つ
                } else {
                    enter(WINDUP, WINDUP_T);
                }
            }
            case WINDUP -> enter(BIRTH, BIRTH_T);
            case BIRTH -> {
                if (births >= table().birthsToRoot) {
                    enter(ROOTING, ROOT_T);
                } else {
                    rest();
                }
            }
            case ROOTING -> becomeRooted();
            default -> enter(ROAM, 0);
        }
    }

    private void rest() {
        SpawnTable.Gestatrix t = table();
        enter(REST, t.restMin + getRandom().nextInt(t.restMax - t.restMin + 1));
    }

    private void holdYaw() {
        if (Float.isNaN(anchorYaw)) anchorYaw = getYRot();
        setYRot(anchorYaw);
        this.yBodyRot = anchorYaw;
        this.yHeadRot = anchorYaw;
    }

    /** 歩く: 行き先(拠点かプレイヤー)へ寄り、近づくか歩き疲れたら腰を据える。 */
    private void tickRoam(ServerLevel level) {
        SpawnTable.Gestatrix t = table();
        roamAge++;
        Vec3 dest = destination(level, t);
        boolean near = dest != null && horizontalDistance(dest) <= t.settleBlocks;
        if (near || roamAge >= t.roamTicks) {
            settle();
            return;
        }
        if (dest != null && --repathIn <= 0) {
            repathIn = REPATH;
            getNavigation().moveTo(dest.x, dest.y, dest.z, 1.0D);
        }
    }

    private double horizontalDistance(Vec3 p) {
        double dx = p.x - getX(), dz = p.z - getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 行き先: 送り込まれた拠点 → 近くの認識した拠点 → approachBlocks の中のサバイバルのプレイヤー。無ければ null(うろつく)。 */
    @Nullable
    private Vec3 destination(ServerLevel level, SpawnTable.Gestatrix t) {
        if (!Double.isNaN(baseX)) return new Vec3(baseX, getY(), baseZ);
        BaseMemory.Base b = ForestDirector.nearestRecognized(level, getX(), getZ());
        if (b != null) return new Vec3(b.centerX(), getY(), b.centerZ());
        Player p = level.getNearestPlayer(getX(), getY(), getZ(), t.approachBlocks, e -> e instanceof Player pl && !pl.isSpectator() && !pl.isCreative());
        return p != null ? p.position() : null;
    }

    /** 腰を据える(もう立たない)。 */
    public void settle() {
        if (state() != ROAM) return;
        getNavigation().stop();
        anchorYaw = getYRot();
        enter(SETTLE, SETTLE_T);
    }

    // ------------------------------------------------------------------ 産む
    /** 今産める手先の数(上限 broodCap − 生きている手先)。 */
    public int broodRoom(ServerLevel level) {
        brood.removeIf(id -> {
            Entity e = level.getEntity(id);
            return e == null || !e.isAlive();
        });
        return table().broodCap - brood.size();
    }

    public int broodAlive(ServerLevel level) {
        broodRoom(level);
        return brood.size();
    }

    private void giveBirth(ServerLevel level) {
        SpawnTable.Gestatrix t = table();
        int n = Math.min(t.broodMin + getRandom().nextInt(t.broodMax - t.broodMin + 1), broodRoom(level));
        births++;
        this.entityData.set(SACS, Math.max(0, t.birthsToRoot - births));
        playSound(ModSounds.NURSERY_SPROUT.get(), 1.2F, 0.8F); // 背から散る。音・高さ【仮】
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, getX(), getY() + getBbHeight() * 0.9D, getZ(), 30, 0.6D, 0.3D, 0.6D, 0.05D);
        for (int i = 0; i < n; i++) {
            GregariaEntity g = ModCrawlers.GREGARIA.get().create(level);
            if (g == null) continue;
            double a = getRandom().nextDouble() * Math.PI * 2.0D;
            g.moveTo(getX() + Math.cos(a) * 0.4D, getY() + getBbHeight() * 0.8D, getZ() + Math.sin(a) * 0.4D, getRandom().nextFloat() * 360.0F, 0.0F);
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(g, level, level.getCurrentDifficultyAt(g.blockPosition()), MobSpawnType.BREEDING, null);
            g.setDeltaMovement(Math.cos(a) * 0.35D, 0.3D, Math.sin(a) * 0.35D); // 背から散らす
            if (level.addFreshEntity(g)) brood.add(g.getUUID()); // 群れ(CrawlerPack)には、グレガリアが近くの仲間と自分で入る
        }
        broodBorn += n;
    }

    /** 確かめ用: これまでに産んだ手先の数。 */
    public static long broodBorn;

    // ------------------------------------------------------------------ 根付いた姿(苗床の働き)
    private void becomeRooted() {
        enter(ROOTED, 0);
        setPersistenceRequired();
        // 縁で湧いた印を外す(根付いたら縁の上限に数えない・遠くても片付けない。苗床と同じく残る)
        removeTag(EnemySpawns.EDGE_TAG);
        sproutIn = nextSproutDelay();
        hatchAskIn = NurseryParasitized.hatchAskInterval();
    }

    private int nextSproutDelay() {
        return NurseryParasitized.SPROUT_INTERVAL_MIN + getRandom().nextInt(NurseryParasitized.SPROUT_INTERVAL_MAX - NurseryParasitized.SPROUT_INTERVAL_MIN + 1);
    }

    private void tickRooted(ServerLevel level) {
        getNavigation().stop();
        if (tickCount % com.yayuy.overgrownvegetation.entity.g1.G1Unbury.INTERVAL == 0) com.yayuy.overgrownvegetation.entity.g1.G1Unbury.liftIfBuried(this);
        if (sproutIn < 0) sproutIn = nextSproutDelay();
        if (hatchAskIn < 0) hatchAskIn = NurseryParasitized.hatchAskInterval();
        int nurse = this.entityData.get(NURSE);
        if (nurse != 0) {
            nurseAge++;
            if (nurse > 0 && nurseAge == SPROUT_AT) sproutFern(level);
            if (nurse < 0 && nurseAge == HATCH_AT) {
                playSound(ModSounds.G1_SAC_BURST.get(), 1.0F, 0.8F);
                hatch(level);
            }
            if (--nurseLeft <= 0) {
                this.entityData.set(NURSE, 0);
                if (spent()) kill();
            }
            return;
        }
        if (--hatchAskIn <= 0) {
            hatchAskIn = NurseryParasitized.hatchAskInterval();
            List<Supplier<? extends EntityType<? extends Mob>>> pool = spawnPoolOverride != null ? spawnPoolOverride : ForestDirector.nurserySpawns();
            EntityType<? extends Mob> bought = ForestDirector.get(level).buySpawn(level, getRandom(), pool, position());
            if (bought != null) {
                hatching = bought;
                startNurse(-1, HATCH_T);
                return;
            }
        }
        if (--sproutIn <= 0) {
            sproutIn = nextSproutDelay();
            boolean swallowed = level.dimension() == Level.OVERWORLD && com.yayuy.overgrownvegetation.phase.PhaseData.get(level)
                    .isOvergrownChunk(new net.minecraft.world.level.ChunkPos(blockPosition()));
            if (!swallowed && fernsNearby(level) < NurseryParasitized.FERN_LIMIT
                    && !com.yayuy.overgrownvegetation.director.DirectorGates.plantSpawnsSuppressed(level)) {
                startNurse(1, SPROUT_T);
                playSound(ModSounds.NURSERY_SPROUT.get(), 1.0F, 1.0F);
            }
        }
    }

    private void startNurse(int slot, int ticks) {
        this.entityData.set(NURSE, slot);
        nurseLeft = ticks;
        nurseAge = 0;
    }

    /** 役目を終えたか(今の苗床と同じ値: 萌芽体の数か孵した回数)。 */
    public boolean spent() {
        com.yayuy.overgrownvegetation.data.DirectorTable t = DesignData.director();
        return hatches >= t.nurseryLifeHatches || sprouted >= t.nurseryLifeSprouts;
    }

    public int hatches() {
        return hatches;
    }

    private int fernsNearby(ServerLevel level) {
        double r = NurseryParasitized.FERN_LIMIT_RADIUS;
        return level.getEntitiesOfClass(BlightFernEntity.class, getBoundingBox().inflate(r, 6.0D, r)).size();
    }

    @Nullable
    private BlockPos spotAround(ServerLevel level, double near, double far) {
        BlockPos base = blockPosition();
        for (int tries = 0; tries < 16; tries++) {
            double a = getRandom().nextDouble() * Math.PI * 2.0D;
            double d = near + getRandom().nextDouble() * (far - near);
            int x = (int) Math.floor(getX() + Math.cos(a) * d), z = (int) Math.floor(getZ() + Math.sin(a) * d);
            for (int dy = 1; dy >= -2; dy--) {
                BlockPos p = new BlockPos(x, base.getY() + dy, z);
                if (level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)
                        && level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                        && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                        && level.getFluidState(p).isEmpty()) {
                    return p;
                }
            }
        }
        return null;
    }

    private void sproutFern(ServerLevel level) {
        sprouted++;
        BlockPos p = spotAround(level, 2.0D, 4.5D);
        if (p != null) BlightFernPlacement.spawn(level, p, MobSpawnType.MOB_SUMMONED);
    }

    private void hatch(ServerLevel level) {
        EntityType<? extends Mob> type = hatching;
        hatching = null;
        hatches++;
        if (type == null) return;
        BlockPos p = spotAround(level, 1.5D, 3.0D);
        Mob mob = type.create(level);
        if (mob == null) return;
        Vec3 at = p != null ? Vec3.atBottomCenterOf(p) : position();
        mob.moveTo(at.x, at.y, at.z, getRandom().nextFloat() * 360.0F, 0.0F);
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.SPAWNER, null);
        level.addFreshEntity(mob);
        com.yayuy.overgrownvegetation.director.BaseRaids.onNurseryHatched(level, mob);
    }

    // ------------------------------------------------------------------ 保存
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("GState", state());
        tag.putInt("GStateLeft", stateLeft);
        tag.putInt("RoamAge", roamAge);
        tag.putInt("Births", births);
        tag.putInt("Cancels", cancels);
        if (!Double.isNaN(baseX)) {
            tag.putDouble("BaseX", baseX);
            tag.putDouble("BaseZ", baseZ);
        }
        ListTag list = new ListTag();
        for (UUID id : brood) list.add(NbtUtils.createUUID(id));
        tag.put("Brood", list);
        if (!Float.isNaN(anchorYaw)) tag.putFloat("AnchorYaw", anchorYaw);
        tag.putInt("SproutIn", sproutIn);
        tag.putInt("HatchAskIn", hatchAskIn);
        tag.putInt("Sprouted", sprouted);
        tag.putInt("Hatches", hatches);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        int st = tag.getInt("GState");
        // 途中の動き(前触れ・産む)は保存しない: 腰を据えていれば休みから、根付いていれば根付いたまま
        this.entityData.set(STATE, st == ROOTED ? ROOTED : st == ROAM ? ROAM : st == ROOTING ? ROOTING : REST);
        this.stateLeft = Math.max(1, tag.getInt("GStateLeft"));
        this.roamAge = tag.getInt("RoamAge");
        this.births = tag.getInt("Births");
        this.cancels = tag.getInt("Cancels");
        this.entityData.set(SACS, Math.max(0, table().birthsToRoot - births));
        if (tag.contains("BaseX")) {
            this.baseX = tag.getDouble("BaseX");
            this.baseZ = tag.getDouble("BaseZ");
        }
        brood.clear();
        for (Tag t : tag.getList("Brood", Tag.TAG_INT_ARRAY)) brood.add(NbtUtils.loadUUID(t));
        if (tag.contains("AnchorYaw")) anchorYaw = tag.getFloat("AnchorYaw");
        if (tag.contains("SproutIn")) sproutIn = tag.getInt("SproutIn");
        if (tag.contains("HatchAskIn")) hatchAskIn = tag.getInt("HatchAskIn");
        sprouted = tag.getInt("Sprouted");
        hatches = tag.getInt("Hatches");
    }

    // ------------------------------------------------------------------ アニメ(名前は依頼文の仮の名前。本物のモデルが来たら合わせる)
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final Map<String, RawAnimation> CLIPS = new HashMap<>();

    private static RawAnimation clip(String name, boolean loop) {
        return CLIPS.computeIfAbsent(name + (loop ? "#loop" : "#hold"),
                k -> loop ? RawAnimation.begin().thenLoop(name) : RawAnimation.begin().thenPlayAndHold(name));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, s -> {
            int st = state();
            if (!FINAL_CLIPS || st == ROAM) {
                return s.setAndContinue(st == ROAM && clientMoveSpeed(s.getPartialTick()) > 0.01F ? WALK : IDLE);
            }
            if (isDeadOrDying()) return s.setAndContinue(clip("death", false));
            int nurse = this.entityData.get(NURSE);
            return s.setAndContinue(switch (st) {
                case SETTLE -> clip("settle", false);
                case WINDUP -> clip("birth_windup", false);
                case BIRTH -> clip("birth", false);
                case CANCEL -> clip("birth_cancel", false);
                case ROOTING -> clip("root", false);
                case ROOTED -> nurse > 0 ? clip("sprout_" + nurse, false) : nurse < 0 ? clip("hatch_" + (-nurse), false) : clip("rooted_idle", true);
                default -> IDLE;
            });
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ------------------------------------------------------------------ テスト用
    /** 状態を飛ばす(前触れ・休み・根付きなど)。残りの tick も決める。 */
    public void forceStateForTests(int state, int ticks) {
        if (state == ROOTED) {
            becomeRooted();
            return;
        }
        if (state != ROAM && Float.isNaN(anchorYaw)) anchorYaw = getYRot();
        enter(state, ticks);
    }

    public void setBirthsForTests(int n) {
        this.births = n;
        this.entityData.set(SACS, Math.max(0, table().birthsToRoot - n));
    }

    public void setRootedTimersForTests(int sproutIn, int hatchAskIn) {
        this.sproutIn = sproutIn;
        this.hatchAskIn = hatchAskIn;
    }

    public void setSpawnPoolForTests(@Nullable List<Supplier<? extends EntityType<? extends Mob>>> pool) {
        this.spawnPoolOverride = pool;
    }
}
