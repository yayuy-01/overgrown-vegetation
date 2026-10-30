package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.ParasitizedCreeper;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * 「過繁茂したクリーパー」({@link ParasitizedCreeper})の爆発を、地形破壊ありのバニラ爆発から
 * 「地形は壊さず、周囲を胞子感染させて汚染する」独自の爆発へ差し替える(ユーザー要望)。
 *
 * <p>クリーパーはバニラの導火線 Goal({@code SwellGoal})と膨張演出をそのまま使い、いざ爆発する
 * ({@code Creeper#explodeCreeper} → {@code Level#explode})瞬間に発生する {@code ExplosionEvent.Start}
 * を発生源で判定してキャンセルする。キャンセルしてもバニラ側は続けて {@code discard()} まで進むので、
 * クリーパー自体は通常どおり消滅する。キャンセルした代わりに、この場で:
 * <ul>
 *   <li>半径 {@link OvergrownVegetationConfig.Common#parasitizedCreeperInfectionRadius} 以内の Mob
 *       (プレイヤー・{@link BlightFernEntity}・{@link com.yayuy.overgrownvegetation.phase.ParasitizedMob}
 *       は除く。後者2つは {@link SporeInfectionEffects#infect} 側でも弾かれる)へ「胞子感染1」。</li>
 *   <li>半径 {@link OvergrownVegetationConfig.Common#parasitizedCreeperCorruptionRadius} 以内の
 *       ブロックを汚染ブロックへ({@link CorruptionRegistry})。境界は方向ごとに
 *       {@link OvergrownVegetationConfig.Common#parasitizedCreeperCorruptionNoise} 分だけ波打たせ、
 *       さらに縁を確率で欠けさせるので綺麗な球にはならない。</li>
 * </ul>
 * 帯電クリーパー({@code Creeper#isPowered})はどちらの半径も 1.5 倍。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ParasitizedCreeperDetonation {

    private static final float POWERED_MULTIPLIER = 1.5F;

    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity source = event.getExplosion().getDirectSourceEntity();
        if (!(source instanceof ParasitizedCreeper creeper)) {
            return;
        }

        // バニラの爆発(地形破壊・ノックバック・炎)は一切起こさない。
        event.setCanceled(true);

        Vec3 center = event.getExplosion().center();
        float powerMul = creeper.isPowered() ? POWERED_MULTIPLIER : 1.0F;

        double infectRadius = OvergrownVegetationConfig.COMMON.parasitizedCreeperInfectionRadius.get() * powerMul;
        applyInfection(level, creeper, center, infectRadius);

        int corruptRadius = Math.round(
                OvergrownVegetationConfig.COMMON.parasitizedCreeperCorruptionRadius.get() * powerMul);
        double noise = OvergrownVegetationConfig.COMMON.parasitizedCreeperCorruptionNoise.get();
        corrupt(level, center, corruptRadius, noise);

        playEffects(level, center);
    }

    private static void applyInfection(ServerLevel level, ParasitizedCreeper creeper, Vec3 center, double radius) {
        if (radius <= 0.0D) {
            return;
        }
        double radiusSq = radius * radius;
        AABB box = new AABB(center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (entity == creeper || entity instanceof Player || entity instanceof BlightFernEntity) {
                continue;
            }
            if (entity.distanceToSqr(center) <= radiusSq) {
                SporeInfectionEffects.infect(entity, 0);
            }
        }
    }

    /** 球状の汚染は {@link NoisyCorruptionBurst} と共通(グループ4の死亡時汚染と同じ処理)。 */
    private static void corrupt(ServerLevel level, Vec3 center, int radius, double noiseAmplitude) {
        NoisyCorruptionBurst.sphere(level, center, radius, noiseAmplitude);
    }

    /**
     * 地表を円形に汚染する(攻城戦ボスの爆弾 {@link com.yayuy.overgrownvegetation.entity.SiegeBombEntity} 用)。
     * クリーパーの球形の {@link #corrupt} と同じ変換規則だが、半径 32 の球だと数十万ブロックを走査して
     * しまうので、こちらは (x, z) の円盤だけを走査し、各列の地表ブロック({@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES}
     * の直下)から深さ 3 ブロックまでを汚染する。輪郭は {@code noiseAmplitude} ぶん波打たせ、縁は半々で欠けさせる。
     * 着弾面から上下に 24 ブロック以上離れた地表(崖の上など)は対象外。
     */
    public static void corruptSurfaceDisc(ServerLevel level, Vec3 center, double radius, double noiseAmplitude) {
        if (radius <= 0.0D) {
            return;
        }
        RandomSource random = level.random;
        double seed = (center.x * 0.11D) + (center.z * 0.13D) + random.nextInt(1024);
        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);
        int scan = (int) Math.ceil(radius + noiseAmplitude) + 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LongOpenHashSet touchedChunks = new LongOpenHashSet();
        int corruptedBlocks = 0;

        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                double effectiveRadius = radius + wobbleDisc(dx, dz, seed) * noiseAmplitude;
                if (dist > effectiveRadius) {
                    continue;
                }
                if (dist > effectiveRadius - 1.5D && random.nextFloat() < 0.5F) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                pos.set(x, cy, z);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (Math.abs(top - cy) > 24) {
                    continue;
                }
                for (int depth = 0; depth < 3; depth++) {
                    pos.set(x, top - depth, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || state.is(ModTags.CORRUPTED) || !CorruptionRegistry.canCorrupt(level, pos, state)) {
                        continue;
                    }
                    BlockState corrupted = CorruptionRegistry.corrupt(level, pos, state, random);
                    if (CorruptionRegistry.noChange(state, corrupted)) {
                        if (depth == 0) { // 覆っただけのマスにも植物(2026-10-10)
                            com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.growPlantsIfNewlyCovered(level, pos, state);
                        }
                        continue;
                    }
                    BlockPos placed = pos.immutable();
                    CorruptionRegistry.placeCorrupted(level, placed, state, corrupted, Block.UPDATE_ALL);
                    corruptedBlocks++;
                    if (depth == 0) {
                        CorruptionSpreader.growSurfacePlants(level, placed, corrupted,
                                OvergrownVegetationConfig.COMMON.growthChance.get());
                    }
                    touchedChunks.add(ChunkPos.asLong(x >> 4, z >> 4));
                }
            }
        }

        CorruptionCounter.record(level, corruptedBlocks);
        for (long packedChunk : touchedChunks) {
            OvergrownBiomeConverter.maybeConvert(level, new ChunkPos(packedChunk));
        }
    }

    /** 円盤の輪郭の波打ち量(おおよそ -1〜+1)。半径 32 向けの低周波。 */
    private static double wobbleDisc(int dx, int dz, double seed) {
        double v = Math.sin(dx * 0.35D + seed)
                + Math.sin(dz * 0.35D + seed * 1.3D)
                + Math.sin((dx + dz) * 0.2D + seed * 1.9D);
        return v / 3.0D;
    }


    private static void playEffects(ServerLevel level, Vec3 center) {
        level.playSound(null, center.x, center.y, center.z,
                SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.HOSTILE, 1.6F, 0.7F);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                center.x, center.y, center.z, 120, 2.2D, 1.6D, 2.2D, 0.02D);
        level.sendParticles(ParticleTypes.SNEEZE,
                center.x, center.y, center.z, 40, 1.5D, 1.0D, 1.5D, 0.05D);
    }

    private ParasitizedCreeperDetonation() {
    }
}
