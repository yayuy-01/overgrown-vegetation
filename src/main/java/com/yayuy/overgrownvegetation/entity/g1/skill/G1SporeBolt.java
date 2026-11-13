package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 植物の弓が射る胞子の塊(2026-11-13、能力の第 3 回。スケルトンの 1 段目の (b)。仕様 Vault「G1 スケルトン(1 段目)」3 節【決定 11-09・数は仮】)。
 * 山なりに飛ぶ遅い弾(バニラの投げ物と同じ重力 0.03・空気抵抗 0.99)。見た目は胞子嚢のアイテムの絵に、胞子の粒の尾。
 *
 * <ul>
 *   <li><b>当たれば</b>: {@code damage} のダメージ + 暴露量 +{@code dose}({@link G1SporeDose} = マスクで防げる)。
 *   <b>盾で受ければ暴露なし</b>(盾で防いだかは、ダメージを与える前に {@link LivingEntity#isDamageSourceBlocked} で見る。無敵時間で
 *   ダメージが入らなかっただけのときは暴露量を足す)。植物側の Mob・落ちた莢はすり抜ける。</li>
 *   <li><b>外れたら</b>(ブロックに当たる): 着弾したブロックを汚染し、周りの 3 × 3 を {@value #SPLASH_CHANCE} の率で汚染する。その升の浮遊を
 *   +{@value #MISS_AIR_PERCENT}%。汚染が止まっている・胞子除去装置の中では何もしない。</li>
 * </ul>
 * 量は射る側 {@link G1BowShotGoal} が {@link #setLoad} で決める。
 */
public class G1SporeBolt extends ThrowableItemProjectile {

    /** 外れたとき、着弾点の周りの 3 × 3 の各マスを汚染する率。仕様「周りの 3×3 に 30%」。【仮】 */
    static final float SPLASH_CHANCE = 0.3F;
    /** 外れたときの升の浮遊の上げ幅(%。100% = fullAt)。仕様「浮遊を少し上げる」。【仮】 */
    static final double MISS_AIR_PERCENT = 1.0D;
    /** バニラの投げ物の重力・空気抵抗({@link #launch} の見積もりに使う)。 */
    static final double GRAVITY = 0.03D;
    static final double DRAG = 0.99D;

    private double dose;
    private float damage;

    public G1SporeBolt(EntityType<? extends G1SporeBolt> type, Level level) {
        super(type, level);
    }

    public G1SporeBolt(Level level, LivingEntity shooter) {
        super(ModG1.SPORE_BOLT.get(), shooter, level);
    }

    /** 当たったときの量(暴露量 %秒・ダメージ)。 */
    public G1SporeBolt setLoad(double dose, float damage) {
        this.dose = dose;
        this.damage = damage;
        return this;
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.SPORE_SAC.get();
    }

    /**
     * {@code from} から {@code to} へ届く低い山なりの向き(長さ 1)。速さ {@code speed}・重力 {@value #GRAVITY}・空気抵抗 {@value #DRAG} で
     * 1 tick ずつ進めて、的の水平距離での高さが合う仰角を二分で探す(仰角は 50° まで。届かなければ 45° = いちばん遠くへ)。
     */
    public static Vec3 launch(Vec3 from, Vec3 to, double speed) {
        double dx = to.x - from.x, dz = to.z - from.z, dy = to.y - from.y;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-4D) return new Vec3(0.0D, dy >= 0.0D ? 1.0D : -1.0D, 0.0D);
        double lo = Math.atan2(dy, dist), hi = Math.toRadians(50.0D);
        double angle;
        if (lo >= hi) {
            angle = lo;
        } else if (heightAt(dist, hi, speed) < dy) {
            angle = Math.toRadians(45.0D);
        } else {
            for (int i = 0; i < 16; i++) {
                double mid = (lo + hi) * 0.5D;
                if (heightAt(dist, mid, speed) < dy) lo = mid;
                else hi = mid;
            }
            angle = (lo + hi) * 0.5D;
        }
        double h = Math.cos(angle);
        return new Vec3(dx / dist * h, Math.sin(angle), dz / dist * h);
    }

    /** 仰角 {@code angle} で射た弾が、水平に {@code dist} 進んだ所の高さ(届かなければ大きな負の数)。 */
    static double heightAt(double dist, double angle, double speed) {
        double vx = Math.cos(angle) * speed, vy = Math.sin(angle) * speed;
        double x = 0.0D, y = 0.0D;
        for (int t = 0; t < 200; t++) {
            double nx = x + vx, ny = y + vy;
            if (nx >= dist) {
                return y + (ny - y) * (dist - x) / (nx - x);
            }
            x = nx;
            y = ny;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            if (vx < 1.0E-3D) break;
        }
        return -1.0E6D;
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (target instanceof G1SeedPod) return false;
        if (target instanceof LivingEntity living && SporeExposureTracker.isPlantSide(living)) return false;
        return super.canHitEntity(target);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() && tickCount > 1) {
            // 胞子の粒の尾(クライアントだけ)
            level().addParticle(ModParticles.SPORE_JET.get(), getX(), getY() + 0.1D, getZ(),
                    (random.nextDouble() - 0.5D) * 0.02D, 0.01D, (random.nextDouble() - 0.5D) * 0.02D);
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (!(result.getEntity() instanceof LivingEntity living) || living == getOwner()) return;
        DamageSource source = getOwner() instanceof LivingEntity owner ? damageSources().mobProjectile(this, owner) : damageSources().thrown(this, null);
        boolean blocked = living.isDamageSourceBlocked(source);
        // 暴露量を先に足す(ダメージの中で G1 の攻撃の感染の抽選が走り、当たると「感染中は積まない」で暴露量が足されなくなる。G1SporeBlast と同じ順)
        if (!blocked) G1SporeDose.add(living, dose);
        if (damage > 0.0F) living.hurt(source, damage);
    }

    /** その相手に当たったことにする(GameTest: 世界に入っていない偽プレイヤーへの当たり)。 */
    public void hitForTests(LivingEntity target) {
        onHitEntity(new EntityHitResult(target));
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (!(level() instanceof ServerLevel level)) return;
        BlockPos pos = result.getBlockPos();
        if (CorruptionSwitch.isOff() || SporeDeviceData.inhibits(level, pos, SporeDeviceData.Inhibit.CORRUPTION)) return;
        LongOpenHashSet touched = new LongOpenHashSet();
        int n = NoisyCorruptionBurst.single(level, pos, touched) ? 1 : 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx == 0 && dz == 0) || level.random.nextFloat() >= SPLASH_CHANCE) continue;
                BlockPos p = pos.offset(dx, 0, dz);
                if (level.getBlockState(p).isAir()) p = p.below(); // 段の下
                if (NoisyCorruptionBurst.single(level, p, touched)) n++;
            }
        }
        if (n > 0) {
            CorruptionCounter.record(level, n);
            for (long chunk : touched) OvergrownBiomeConverter.maybeConvert(level, new ChunkPos(chunk));
        }
        if (level.dimension() == Level.OVERWORLD || level.dimension() == Level.NETHER) {
            SporeFieldServer.emitPoint(level, pos, MISS_AIR_PERCENT / 100.0D * DesignData.sporeField().fullAt());
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level() instanceof ServerLevel level) {
            level.sendParticles(ModParticles.SPORE_JET.get(), getX(), getY(), getZ(), 24, 0.25D, 0.25D, 0.25D, 0.04D);
            level.sendParticles(ModParticles.SPORE_BURST_CLOUD.get(), getX(), getY() + 0.2D, getZ(), 2, 0.2D, 0.1D, 0.2D, 0.01D);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 0.9F, 0.6F);
            discard();
        }
    }
}
