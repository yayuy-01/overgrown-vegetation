package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * G1 の 1 段目が噴く胞子の煙(2026-11-12、能力の第 1 回。共通の部品 —— 後の回の種も {@link #spawn} で使う)。
 * バニラの範囲効果雲(AreaEffectCloud)に倣った、見えない実体 1 つ。足元から上へ円柱(半径 {@link #radius()}・高さ = 半径 + 1)。
 *
 * <ul>
 *   <li>中にいる相手(植物側以外)へ {@link #DOSE_INTERVAL} tick ごとに 暴露量 = 1 秒あたりの量 × 間隔 を足す({@link G1SporeDose}。防護マスクで防げる)。</li>
 *   <li>鈍足(任意): 中にいる間かけ直し、出てからも {@code slowLinger} tick 残る(村人の霧 = 鈍足 I + 2 秒、11-09 ユーザー「イ」)。</li>
 *   <li>霞み(任意): 中にいるプレイヤーの視界が緑に霞む(チビゾンビの袋の煙 = 目くらまし、11-09 ユーザー)。描画は {@code client/G1SporeHaze}。</li>
 *   <li>見た目: クライアントがこの実体の tick で胞子の粒を円柱の中に出す(サーバーから粒を送らない)。初めの 1 tick に膨らむ雲の粒をまとめて。</li>
 *   <li>升の浮遊は上げない(煙は短い間の局所の害。浮遊を上げるのは破裂・巣・チビゾンビの屋根の下の鼓動)。地形は壊さない。</li>
 * </ul>
 * 数値は呼ぶ側が渡す(各種の【仮】の定数)。保存する(読み込み直しても残りの時間だけ漂う)。
 */
public class G1SporeCloud extends Entity {

    /** 暴露量・鈍足をかける間隔(tick)。【仮】 */
    static final int DOSE_INTERVAL = 10;

    private static final EntityDataAccessor<Float> RADIUS = SynchedEntityData.defineId(G1SporeCloud.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> HAZE = SynchedEntityData.defineId(G1SporeCloud.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DURATION = SynchedEntityData.defineId(G1SporeCloud.class, EntityDataSerializers.INT);

    private double dosePerSecond;
    private int slowAmplifier = -1;
    private int slowLinger;
    @Nullable
    private UUID owner;

    public G1SporeCloud(EntityType<? extends G1SporeCloud> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * 煙を出す(サーバー)。
     *
     * @param feet          円柱の底の中心
     * @param radius        半径(ブロック)
     * @param ticks         漂う長さ
     * @param dosePerSecond 中にいる間の暴露量(%秒 / 秒)
     * @param slowAmplifier 鈍足の強さ(0 = I)。-1 で無し
     * @param slowLinger    出てから鈍足が残る tick
     * @param haze          中にいるプレイヤーの視界を緑に霞ませるか
     * @param owner         出した Mob(この Mob には効かない。null 可)
     */
    public static G1SporeCloud spawn(ServerLevel level, Vec3 feet, float radius, int ticks, double dosePerSecond,
                                     int slowAmplifier, int slowLinger, boolean haze, @Nullable Entity owner) {
        return spawn(level, feet, radius, ticks, dosePerSecond, slowAmplifier, slowLinger, haze, owner, true);
    }

    /**
     * {@link #spawn} の音を選べる形(2026-11-14、監視塔フィロセンスの胞子が使う。音は種の側で決める決まりなので、音なしで出す)。
     *
     * @param sound 噴く音(イカの吐く音 + 火の消える音)を鳴らすか
     */
    public static G1SporeCloud spawn(ServerLevel level, Vec3 feet, float radius, int ticks, double dosePerSecond,
                                     int slowAmplifier, int slowLinger, boolean haze, @Nullable Entity owner, boolean sound) {
        G1SporeCloud c = new G1SporeCloud(ModG1.SPORE_CLOUD.get(), level);
        c.setPos(feet);
        c.entityData.set(RADIUS, radius);
        c.entityData.set(HAZE, haze);
        c.entityData.set(DURATION, ticks);
        c.dosePerSecond = dosePerSecond;
        c.slowAmplifier = slowAmplifier;
        c.slowLinger = slowLinger;
        c.owner = owner == null ? null : owner.getUUID();
        level.addFreshEntity(c);
        if (sound) {
            level.playSound(null, feet.x, feet.y + 0.5D, feet.z, SoundEvents.SQUID_SQUIRT, SoundSource.HOSTILE, 0.7F, 0.6F);
            level.playSound(null, feet.x, feet.y + 0.5D, feet.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 0.25F, 1.6F);
        }
        return c;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(RADIUS, 2.0F);
        builder.define(HAZE, false);
        builder.define(DURATION, 60);
    }

    public float radius() {
        return this.entityData.get(RADIUS);
    }

    public boolean haze() {
        return this.entityData.get(HAZE);
    }

    public int duration() {
        return this.entityData.get(DURATION);
    }

    public double dosePerSecond() {
        return dosePerSecond;
    }

    /** その点が煙の円柱の中か。 */
    public boolean contains(Vec3 p) {
        float r = radius();
        double dx = p.x - getX(), dz = p.z - getZ();
        return dx * dx + dz * dz <= r * r && p.y >= getY() - 0.5D && p.y <= getY() + r + 1.0D;
    }

    /** 煙の濃さ(0〜1。終わり 1/3 で薄れる)。霞みの強さに使う。 */
    public float thickness() {
        int d = duration();
        int left = d - tickCount;
        return Mth.clamp(left / (d / 3.0F), 0.0F, 1.0F);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            clientParticles();
            return;
        }
        if (tickCount > duration()) {
            discard();
            return;
        }
        if (tickCount % DOSE_INTERVAL == 1) {
            applyToInside();
        }
    }

    private void applyToInside() {
        float r = radius();
        AABB box = new AABB(getX() - r, getY() - 0.5D, getZ() - r, getX() + r, getY() + r + 1.0D, getZ() + r);
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (owner != null && owner.equals(e.getUUID())) continue;
            if (SporeExposureTracker.isPlantSide(e) || !contains(e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D))) continue;
            G1SporeDose.add(e, dosePerSecond * DOSE_INTERVAL / 20.0D);
            if (slowAmplifier >= 0 && G1SporeDose.canDose(e)) {
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, DOSE_INTERVAL + slowLinger + 1, slowAmplifier, false, true), this);
            }
        }
    }

    private void clientParticles() {
        RandomSource random = level().random;
        float r = radius();
        float thick = thickness();
        if (tickCount == 1) {
            // 噴いた瞬間の膨らむ雲(破裂と同じ粒)
            int puffs = Mth.ceil(3.0F * r * r);
            for (int i = 0; i < puffs; i++) {
                Vec3 p = randomInside(random, r * 0.6F);
                level().addParticle(ModParticles.SPORE_BURST_CLOUD.get(), p.x, p.y, p.z,
                        (random.nextDouble() - 0.5D) * 0.06D, 0.01D, (random.nextDouble() - 0.5D) * 0.06D);
            }
        }
        int dots = Mth.ceil(thick * 2.5F * r * r);
        for (int i = 0; i < dots; i++) {
            Vec3 p = randomInside(random, r);
            level().addParticle(ModParticles.SPORE_JET.get(), p.x, p.y, p.z,
                    (random.nextDouble() - 0.5D) * 0.02D, 0.005D, (random.nextDouble() - 0.5D) * 0.02D);
        }
        if (random.nextFloat() < thick * 0.15F * r) {
            Vec3 p = randomInside(random, r);
            level().addParticle(ParticleTypes.SPORE_BLOSSOM_AIR, p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
        }
    }

    private Vec3 randomInside(RandomSource random, float r) {
        double a = random.nextDouble() * Mth.TWO_PI;
        double d = r * Math.sqrt(random.nextDouble());
        double y = getY() + random.nextDouble() * (r * 0.8D + 0.6D);
        return new Vec3(getX() + Math.cos(a) * d, y, getZ() + Math.sin(a) * d);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(RADIUS, tag.getFloat("Radius"));
        this.entityData.set(HAZE, tag.getBoolean("Haze"));
        this.entityData.set(DURATION, tag.getInt("Duration"));
        this.tickCount = tag.getInt("Age");
        this.dosePerSecond = tag.getDouble("DosePerSecond");
        this.slowAmplifier = tag.getInt("SlowAmplifier");
        this.slowLinger = tag.getInt("SlowLinger");
        this.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("Radius", radius());
        tag.putBoolean("Haze", haze());
        tag.putInt("Duration", duration());
        tag.putInt("Age", tickCount);
        tag.putDouble("DosePerSecond", dosePerSecond);
        tag.putInt("SlowAmplifier", slowAmplifier);
        tag.putInt("SlowLinger", slowLinger);
        if (owner != null) tag.putUUID("Owner", owner);
    }
}
