package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * ファルキムネスの特殊技<b>地走りの光の帯</b>(2026-11-05)。鎌を刺した所(この Entity の位置)から相手の居た所へ、
 * 地面に光の筋がまっすぐ走り({@code lineSpeed} ブロック/tick)、伸びきって {@code bandDelay} tick 後に筋の上へ高さ
 * {@code bandHeight} の光の帯が {@code bandTicks} tick 立つ。帯に触れた相手へ 1 度だけダメージと上への打ち上げ。
 * 数値は {@code actions.json} の {@code falcimnes/rift}(仮)。
 * <ul>
 *     <li>筋の向きは走り始めた時の相手の位置へまっすぐ(後から曲がらない)。長さは相手の足元の {@code overshoot} 先まで(最大 {@code lineMax})。</li>
 *     <li>跳んでも避けられない(帯の高さ 3)。<b>横へ出るのが答え</b>。防具は効く・盾では防げない(足元から来る。ダメージの種類のタグ)。</li>
 *     <li>筋は地面に沿う: 0.5 ブロックごとに、刺した高さから上下 4 の範囲で地面を探す(サーバーとクライアントが同じ式で引く)。</li>
 * </ul>
 * 持ち主(ファルキムネス)の動作は筋の長さから帯が消えるまでの時間を {@link #totalTicks} で知り、刺したまま待つ。
 * 見た目は {@code client/FalcimnesRiftRenderer}(地面の上 0.02 に貼る光る筋と、立ち上がる帯)。
 */
public class FalcimnesRiftEntity extends Entity {

    private static final EntityDataAccessor<Float> DATA_LENGTH =
            SynchedEntityData.defineId(FalcimnesRiftEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_WIDTH =
            SynchedEntityData.defineId(FalcimnesRiftEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SPEED =
            SynchedEntityData.defineId(FalcimnesRiftEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_DELAY =
            SynchedEntityData.defineId(FalcimnesRiftEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_BAND =
            SynchedEntityData.defineId(FalcimnesRiftEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_HEIGHT =
            SynchedEntityData.defineId(FalcimnesRiftEntity.class, EntityDataSerializers.FLOAT);
    /** 円く噴き出す形(近くの相手、11-05 夜)。このとき {@link #length} は円の半径、{@link #reached} は広がった半径。 */
    private static final EntityDataAccessor<Boolean> DATA_BURST =
            SynchedEntityData.defineId(FalcimnesRiftEntity.class, EntityDataSerializers.BOOLEAN);

    /** 帯が消えてから筋が薄れて消えるまで(tick)。 */
    public static final int FADE_TICKS = 10;
    /** 地面を探す上下の幅(ブロック)。 */
    static final int GROUND_SEARCH = 4;

    @Nullable
    private UUID ownerId;
    @Nullable
    private Entity cachedOwner;
    private float damage = 14.0F;
    private double lift = 0.6D;
    private final Set<UUID> hit = new HashSet<>();

    public FalcimnesRiftEntity(EntityType<? extends FalcimnesRiftEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** サーバーで出す。{@code start} は刺した所(地面の高さ)、{@code yaw} は筋の向き。 */
    public static FalcimnesRiftEntity spawn(ServerLevel level, LivingEntity owner, Vec3 start, float yaw, double length, double width,
                                            double speed, int delay, int band, double height, float damage, double lift) {
        FalcimnesRiftEntity r = new FalcimnesRiftEntity(ModEntities.FALCIMNES_RIFT.get(), level);
        r.ownerId = owner.getUUID();
        r.cachedOwner = owner;
        r.damage = damage;
        r.lift = lift;
        r.entityData.set(DATA_LENGTH, (float) length);
        r.entityData.set(DATA_WIDTH, (float) width);
        r.entityData.set(DATA_SPEED, (float) Math.max(0.05D, speed));
        r.entityData.set(DATA_DELAY, Math.max(0, delay));
        r.entityData.set(DATA_BAND, Math.max(1, band));
        r.entityData.set(DATA_HEIGHT, (float) height);
        r.moveTo(start.x, start.y, start.z, yaw, 0.0F);
        level.addFreshEntity(r);
        return r;
    }

    /**
     * 円く噴き出す形で出す(11-05 夜、ユーザー指定「刺した所で円く噴き出す」)。刺した所から地面の下を光が {@code speed} ブロック/tick で
     * 半径 {@code radius} まで広がり、{@code delay} tick 後に円の中が {@code band} tick 噴き出す。
     */
    public static FalcimnesRiftEntity spawnBurst(ServerLevel level, LivingEntity owner, Vec3 start, float yaw, double radius,
                                                 double speed, int delay, int band, double height, float damage, double lift) {
        FalcimnesRiftEntity r = new FalcimnesRiftEntity(ModEntities.FALCIMNES_RIFT.get(), level);
        r.ownerId = owner.getUUID();
        r.cachedOwner = owner;
        r.damage = damage;
        r.lift = lift;
        r.entityData.set(DATA_BURST, true);
        r.entityData.set(DATA_LENGTH, (float) radius);
        r.entityData.set(DATA_WIDTH, (float) (radius * 2.0D));
        r.entityData.set(DATA_SPEED, (float) Math.max(0.05D, speed));
        r.entityData.set(DATA_DELAY, Math.max(0, delay));
        r.entityData.set(DATA_BAND, Math.max(1, band));
        r.entityData.set(DATA_HEIGHT, (float) height);
        r.moveTo(start.x, start.y, start.z, yaw, 0.0F);
        level.addFreshEntity(r);
        return r;
    }

    /** 円く噴き出す形か(近くの相手)。 */
    public boolean burst() {
        return this.entityData.get(DATA_BURST);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_LENGTH, 8.0F);
        builder.define(DATA_WIDTH, 1.5F);
        builder.define(DATA_SPEED, 1.2F);
        builder.define(DATA_DELAY, 12);
        builder.define(DATA_BAND, 8);
        builder.define(DATA_HEIGHT, 3.0F);
        builder.define(DATA_BURST, false);
    }

    public float length() {
        return this.entityData.get(DATA_LENGTH);
    }

    public float width() {
        return this.entityData.get(DATA_WIDTH);
    }

    public float bandHeight() {
        return this.entityData.get(DATA_HEIGHT);
    }

    /** 筋が伸びきるまで(tick)。 */
    public int lineTicks() {
        return Math.max(1, Mth.ceil(length() / this.entityData.get(DATA_SPEED)));
    }

    /** 帯が立つ tick(出してからの経過)。 */
    public int bandStart() {
        return lineTicks() + this.entityData.get(DATA_DELAY);
    }

    /** 帯が消える tick。 */
    public int bandEnd() {
        return bandStart() + this.entityData.get(DATA_BAND);
    }

    /** 出してから消えるまで(tick)。 */
    public int totalTicks() {
        return bandEnd() + FADE_TICKS;
    }

    /** 帯が消えるまで(持ち主が刺したまま待つ長さ)を、出す前に数える。 */
    public static int bandEndTicks(double length, double speed, int delay, int band) {
        return Math.max(1, Mth.ceil(length / Math.max(0.05D, speed))) + Math.max(0, delay) + Math.max(1, band);
    }

    /** 筋の向き(水平の単位ベクトル)。 */
    public Vec3 dir() {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
    }

    /** いま筋が伸びている長さ(ブロック)。 */
    public float reached(float partialTick) {
        return Math.min(length(), (this.tickCount + partialTick) * this.entityData.get(DATA_SPEED));
    }

    /**
     * 筋の {@code dist} ブロック先の地面の高さ(上の面の y)。刺した高さから上下 {@link #GROUND_SEARCH} の範囲で、上が空いている
     * いちばん近い固いブロックの上の面。見つからなければ刺した高さ。
     */
    public double groundAt(BlockGetter level, double dist) {
        Vec3 p = this.position().add(dir().scale(dist));
        return groundAt(level, p.x, p.z);
    }

    /** {@code (px, pz)} の地面の高さ(探し方は {@link #groundAt(BlockGetter, double)} と同じ)。円く噴き出す形で使う。 */
    public double groundAt(BlockGetter level, double px, double pz) {
        int x = Mth.floor(px), z = Mth.floor(pz), base = Mth.floor(this.getY() - 0.01D);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int k = 0; k <= GROUND_SEARCH * 2; k++) {
            int dy = (k % 2 == 0 ? 1 : -1) * ((k + 1) / 2);
            int y = base + dy;
            m.set(x, y, z);
            if (level.getBlockState(m).isCollisionShapeFullBlock(level, m)) {
                m.set(x, y + 1, z);
                if (!level.getBlockState(m).isCollisionShapeFullBlock(level, m)) {
                    return y + 1.0D;
                }
            }
        }
        return this.getY();
    }

    @Nullable
    public Entity getOwner() {
        if (this.cachedOwner != null && !this.cachedOwner.isRemoved()) return this.cachedOwner;
        if (this.ownerId != null && this.level() instanceof ServerLevel server) this.cachedOwner = server.getEntity(this.ownerId);
        return this.cachedOwner;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            clientParticles();
            return;
        }
        int t = this.tickCount;
        if (t == bandStart()) {
            Vec3 mid = burst() ? this.position() : this.position().add(dir().scale(length() * 0.5D));
            // 帯が立つ: 低い地鳴りをもう一度(ユーザー決定 11-05「光の帯は低い地鳴り」)。
            this.level().playSound(null, mid.x, mid.y, mid.z, ModSounds.FALCIMNES_RIFT_RUMBLE.get(), SoundSource.HOSTILE, 3.0F, 0.75F);
        }
        if (t >= bandStart() && t < bandEnd()) {
            hitBand();
        }
        if (t >= totalTicks()) {
            this.discard();
        }
    }

    /** 地面の中の点 {@code s}(筋なら刺した所からの距離、円なら {@code (s, side)} を極座標の半径と角度として)を世界の位置へ。 */
    public Vec3 groundPoint(double s, double side) {
        if (burst()) {
            return this.position().add(Math.cos(side) * s, 0.0D, Math.sin(side) * s);
        }
        Vec3 d = dir();
        return this.position().add(d.x * s - d.z * side, 0.0D, d.z * s + d.x * side);
    }

    /**
     * 粒(クライアント、11-05 夜「地面に力を注ぎ込んで噴き出す」)。広がる間は光の先から火の粉が少し、帯が立つ前は筋のあちこちから
     * 土がぱらぱら跳ね(地面が膨らむ)、噴き出した瞬間に土の塊と光の粒が噴き上がる。
     */
    private void clientParticles() {
        Level level = this.level();
        var rnd = this.random;
        int t = this.tickCount;
        int bandStart = bandStart();
        float reached = reached(0.0F);
        double width = width();
        var dust = new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(0.83F, 1.0F, 0.23F), 1.2F);
        if (t < lineTicks()) {
            // 広がる先(筋の先端・円の縁)から火の粉。
            for (int i = 0; i < (burst() ? 3 : 1); i++) {
                Vec3 p = burst() ? groundPoint(reached, rnd.nextDouble() * Math.PI * 2.0D)
                        : groundPoint(reached, (rnd.nextDouble() - 0.5D) * width * 0.5D);
                double g = groundAt(level, p.x, p.z);
                level.addParticle(dust, p.x, g + 0.1D, p.z, 0.0D, 0.05D, 0.0D);
            }
        } else if (t < bandStart) {
            // 噴き出す前: 地面が膨らみ、土がぱらぱら跳ねる。帯が近づくほど多く。
            int n = 1 + (int) (3.0F * (t - lineTicks()) / Math.max(1, bandStart - lineTicks()));
            for (int i = 0; i < n; i++) {
                Vec3 p = randomInside(rnd);
                double g = groundAt(level, p.x, p.z);
                BlockPos below = BlockPos.containing(p.x, g - 0.5D, p.z);
                level.addParticle(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                        level.getBlockState(below)), p.x, g + 0.05D, p.z, 0.0D, 0.15D + rnd.nextDouble() * 0.15D, 0.0D);
                if (rnd.nextInt(3) == 0) level.addParticle(dust, p.x, g + 0.1D, p.z, 0.0D, 0.1D, 0.0D);
            }
        } else if (t < bandStart + 3) {
            // 噴き出す: 土の塊と光の粒が高く噴き上がる。
            double area = burst() ? Math.PI * length() * length() : length() * width;
            int n = Mth.clamp((int) (area * 1.5D), 8, 60);
            for (int i = 0; i < n; i++) {
                Vec3 p = randomInside(rnd);
                double g = groundAt(level, p.x, p.z);
                BlockPos below = BlockPos.containing(p.x, g - 0.5D, p.z);
                double up = 0.35D + rnd.nextDouble() * 0.45D;
                level.addParticle(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                        level.getBlockState(below)), p.x, g + 0.1D, p.z, (rnd.nextDouble() - 0.5D) * 0.3D, up, (rnd.nextDouble() - 0.5D) * 0.3D);
                level.addParticle(dust, p.x, g + 0.2D + rnd.nextDouble() * bandHeight(), p.z, 0.0D, 0.2D + rnd.nextDouble() * 0.2D, 0.0D);
            }
        }
    }

    /** 当たる場所(筋の帯・円の中)の中の、ばらけた 1 点。 */
    private Vec3 randomInside(net.minecraft.util.RandomSource rnd) {
        if (burst()) {
            return groundPoint(Math.sqrt(rnd.nextDouble()) * length(), rnd.nextDouble() * Math.PI * 2.0D);
        }
        return groundPoint(rnd.nextDouble() * length(), (rnd.nextDouble() - 0.5D) * width());
    }

    /** 帯に入っている相手へ 1 度だけ(ダメージ + 上への打ち上げ)。 */
    private void hitBand() {
        if (burst()) {
            hitBurst();
            return;
        }
        Vec3 d = dir();
        Vec3 side = new Vec3(-d.z, 0.0D, d.x);
        double half = width() * 0.5D, len = length(), h = bandHeight();
        Vec3 a = this.position(), b = a.add(d.scale(len));
        AABB box = new AABB(a, b).inflate(half + 1.0D, h + GROUND_SEARCH, half + 1.0D);
        Entity owner = getOwner();
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != owner)) {
            if (this.hit.contains(e.getUUID())) continue;
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            if (owner instanceof FalcimnesEntity f ? !f.isFoeForWave(e) : !PlantFoes.isFoe(null, e)) continue;   // 持ち主が消えても植物側には当てない(①9)
            Vec3 c = e.position().subtract(a);
            double along = c.dot(d), er = e.getBbWidth() * 0.5D;
            if (along < -er || along > len + er) continue;
            if (Math.abs(c.dot(side)) > half + er) continue;
            double g = groundAt(this.level(), Mth.clamp(along, 0.0D, len));
            // 帯の中(地面から高さ h)に足か体が入っているか。跳んでも高さ 3 の帯からは出られない。
            if (e.getBoundingBox().minY > g + h || e.getBoundingBox().maxY < g - 0.5D) continue;
            strike(e, owner);
        }
    }

    /** 円く噴き出す形: 刺した所から水平に半径 {@code length} 以内(体の幅の半分まで含む)に居る相手へ。 */
    private void hitBurst() {
        double r = length(), h = bandHeight();
        Vec3 a = this.position();
        AABB box = new AABB(a, a).inflate(r + 1.0D, h + GROUND_SEARCH, r + 1.0D);
        Entity owner = getOwner();
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != owner)) {
            if (this.hit.contains(e.getUUID())) continue;
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            if (owner instanceof FalcimnesEntity f ? !f.isFoeForWave(e) : !PlantFoes.isFoe(null, e)) continue;   // 持ち主が消えても植物側には当てない(①9)
            double dx = e.getX() - a.x, dz = e.getZ() - a.z, er = e.getBbWidth() * 0.5D;
            if (dx * dx + dz * dz > (r + er) * (r + er)) continue;
            double g = groundAt(this.level(), e.getX(), e.getZ());
            if (e.getBoundingBox().minY > g + h || e.getBoundingBox().maxY < g - 0.5D) continue;
            strike(e, owner);
        }
    }

    private void strike(LivingEntity e, @Nullable Entity owner) {
        this.hit.add(e.getUUID());
        if (e.hurt(ModDamageTypes.falcimnesRift((ServerLevel) this.level(), this, owner), this.damage)) {
            Vec3 v = e.getDeltaMovement();
            e.setDeltaMovement(v.x * 0.3D, Math.max(v.y, this.lift), v.z * 0.3D);
            e.hurtMarked = true;
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 128.0D * 128.0D;
    }

    /** 描画の打ち切りは筋の全長を含む箱で。 */
    @Override
    public AABB getBoundingBoxForCulling() {
        if (burst()) return new AABB(this.position(), this.position()).inflate(length() + 1.0D, bandHeight() + GROUND_SEARCH, length() + 1.0D);
        Vec3 a = this.position(), b = a.add(dir().scale(length()));
        return new AABB(a, b).inflate(width() + 1.0D, bandHeight() + GROUND_SEARCH, width() + 1.0D);
    }
}
