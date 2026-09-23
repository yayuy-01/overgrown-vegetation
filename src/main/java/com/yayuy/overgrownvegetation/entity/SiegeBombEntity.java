package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 超大型レイドボスが真下へ落とす<b>爆弾</b>。
 *
 * <h2>地形破壊は既定でゼロ</h2>
 * <p>爆発は {@link Level.ExplosionInteraction#NONE} で起こす — エンティティへのダメージとノックバック、
 * 演出(パーティクル・音)だけが発生し、ブロックは<b>一切壊れない</b>。
 * それでも多少はクレーターが欲しい場合のために {@code siegeBombCraterRadius}(既定 0 = 無効)を
 * 用意してあり、有効にしても「爆発耐性が低いブロックだけを、指定半径の球内で壊す」という
 * 明示的に限定された削り方しかしない(岩盤・黒曜石・オベリスク類や液体は対象外)。
 *
 * <p>爆弾はボスの現在位置が<b>ロード済みチャンク</b>にあるときだけ湧く
 * ({@link com.yayuy.overgrownvegetation.siege.SiegeData} 側で判定)。遠距離で誰も見ていない場所に
 * 爆弾エンティティを飛ばしても、チャンクがティックされないので落ちも爆ぜもしないためである。
 */
public class SiegeBombEntity extends Entity {

    /** 爆弾が自然消滅するまでの最大 tick 数(何にも当たらず落ち続けた場合の保険)。 */
    private static final int MAX_LIFETIME_TICKS = 600;
    /** 1 tick あたりの落下加速度。 */
    private static final double GRAVITY = 0.045D;
    /** 1 tick あたりの空気抵抗。 */
    private static final double DRAG = 0.995D;

    /** 描画時の回転に使う、生成時に決まる乱数の位相。 */
    private float spinSeed;

    /**
     * 既に爆発したか。{@link #detonate} の先頭で立てる。
     *
     * <p>{@link #discard()} は {@link #detonate} の最後 — つまり {@code explode()} を<b>抜けた後</b> —
     * にしか効かないため、{@code isRemoved()} では爆発処理中の再入を防げない。
     */
    private boolean exploded;

    public SiegeBombEntity(EntityType<? extends SiegeBombEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = false;
    }

    public SiegeBombEntity(Level level, Vec3 pos, Vec3 velocity) {
        this(com.yayuy.overgrownvegetation.registry.ModEntities.SIEGE_BOMB.get(), level);
        this.setPos(pos.x, pos.y, pos.z);
        this.setDeltaMovement(velocity);
        this.spinSeed = level.random.nextFloat() * 360.0F;
    }

    /** 描画用: 生成時に決まった回転の位相。 */
    public float spinSeed() {
        return spinSeed;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void tick() {
        super.tick();

        Vec3 velocity = this.getDeltaMovement().add(0.0D, -GRAVITY, 0.0D).scale(DRAG);
        this.setDeltaMovement(velocity);
        this.move(MoverType.SELF, velocity);

        if (this.level().isClientSide) {
            this.level().addParticle(ParticleTypes.SMOKE,
                    this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                    0.0D, 0.0D, 0.0D);
            return;
        }

        if (this.tickCount > MAX_LIFETIME_TICKS || this.getY() < this.level().getMinBuildHeight() - 8) {
            this.discard();
            return;
        }

        boolean hitTerrain = this.onGround() || this.verticalCollision || this.horizontalCollision;
        boolean hitEntity = !hitTerrain && !this.level()
                .getEntities(this, this.getBoundingBox().inflate(0.4D),
                        e -> e instanceof LivingEntity && e.isAlive() && !(e instanceof DorgaArchelausTitanicaEntity))
                .isEmpty();
        if (hitTerrain || hitEntity) {
            this.detonate((ServerLevel) this.level());
        }
    }

    private void detonate(ServerLevel level) {
        if (exploded) {
            return;
        }
        exploded = true;
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        float power = cfg.siegeBombPower.get().floatValue();

        // ExplosionInteraction.NONE = ブロックは一切壊さない。エンティティへのダメージと演出だけ。
        // 専用のダメージタイプを渡すのは「ボスの攻撃で死んだ」と後から分かるようにするため。
        level.explode(this, ModDamageTypes.siegeBomb(level), null, this.getX(), this.getY(), this.getZ(), power, false,
                Level.ExplosionInteraction.NONE);

        afterBlast(level, this.position());
        this.discard();
    }

    /**
     * 爆発のあとの後始末(クレーターと地表の汚染)。落とした爆弾と、ボスが曲射してくる胞子爆弾
     * ({@link com.yayuy.overgrownvegetation.siege.ShellTracker#KIND_SPORE})で共通。
     */
    private static void afterBlast(ServerLevel level, Vec3 at) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int craterRadius = cfg.siegeBombCraterRadius.get();
        if (craterRadius > 0) {
            carveMinimalCrater(level, BlockPos.containing(at), craterRadius,
                    cfg.siegeBombCraterMaxResistance.get().floatValue());
        }
        // 着弾点の地表を円形に汚染する(過繫茂したクリーパーの爆発と同じ変換)。輪郭は少し波打たせる。
        double corruptionRadius = cfg.siegeBombCorruptionRadius.get();
        if (corruptionRadius > 0.0D) {
            com.yayuy.overgrownvegetation.effect.ParasitizedCreeperDetonation.corruptSurfaceDisc(
                    level, at, corruptionRadius, cfg.siegeBombCorruptionNoise.get());
            // 同じ円盤に入った胞子除去装置は「胞子」で詰まって止まる(ユーザー要望 2026-09-17)。
            // 汚染を巻き戻せる唯一の設備なので、爆撃の下では黙らせる —— 壊しはしない。
            com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity.jamPurifiersInDisc(
                    level, at, corruptionRadius);
        }
    }

    /**
     * エンティティを介さない着弾の処理。ボスが曲射してくる胞子爆弾は
     * {@link com.yayuy.overgrownvegetation.siege.ShellTracker} が放物線の式で飛ばしているので、
     * 落下そのものはこのエンティティを通らない。着弾したときの「爆発 + 地表の汚染」だけをここで共有する。
     */
    public static void detonateAt(ServerLevel level, Vec3 at) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        level.explode(null, ModDamageTypes.siegeBomb(level), null, at.x, at.y, at.z,
                cfg.siegeBombPower.get().floatValue(), false, Level.ExplosionInteraction.NONE);
        afterBlast(level, at);
    }

    /**
     * 「かなり最小限」の地形破壊。指定半径の球の中で、爆発耐性が {@code maxResistance} 未満の
     * ブロックだけを壊す。石(耐性 6)より柔らかいものだけが対象になるので、既定値では
     * 土・草・砂・葉・草花程度しか消えない。液体・空気・岩盤などは最初から対象外。
     */
    public static void carveMinimalCrater(ServerLevel level, BlockPos center, int radius, float maxResistance) {
        int radiusSq = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) {
                        continue;
                    }
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || !state.getFluidState().isEmpty()) {
                        continue;
                    }
                    if (state.getBlock().getExplosionResistance() >= maxResistance) {
                        continue;
                    }
                    level.destroyBlock(pos, true);
                }
            }
        }
    }

    /**
     * <b>爆弾は他の爆弾の爆発では一切ダメージを受けない。</b>
     *
     * <p>これが無いと、同時に落とした爆弾どうしが誘爆し合って {@link StackOverflowError} で
     * サーバーが落ちる: A が爆発 → 巻き添えで B の {@link #hurt} が呼ばれる → B が爆発 →
     * その巻き添えに A が入る({@code Explosion} が除外するのは爆発の発生源だけで、
     * 直前に爆発した別のエンティティは除外されない)→ …… と {@code explode} が互いを
     * 呼び合って底なしに積み上がる。{@link #exploded} フラグだけでは
     * 「A→B→C→…」と別個体を辿る連鎖は止まらないため、根元のここで断ち切る。
     */
    @Override
    public boolean ignoreExplosion(net.minecraft.world.level.Explosion explosion) {
        return true;
    }

    /** 爆弾は矢などで撃ち落とせる(本実装で調整する余地を残すため当たり判定は有効)。 */
    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (this.level() instanceof ServerLevel serverLevel && !this.isRemoved() && !this.exploded) {
            this.detonate(serverLevel);
            return true;
        }
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.spinSeed = tag.getFloat("SpinSeed");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("SpinSeed", this.spinSeed);
    }

    /** 落下中の爆弾は遠くからでも見えてほしいので、描画距離の判定をゆるめる。 */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 256.0D * 256.0D;
    }

    /** 爆弾に押されて動くものは無い。 */
    @Override
    public boolean isPushable() {
        return false;
    }
}
