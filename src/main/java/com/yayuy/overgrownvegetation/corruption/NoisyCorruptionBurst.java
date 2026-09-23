package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 「その場を一発でごっそり汚染する」共通処理 — {@link CorruptionSpreader} のように少しずつ広がる
 * のではなく、呼ばれたその瞬間に球状の範囲を汚染ブロックへ変える。
 *
 * <p>境界は<b>綺麗な球にならない</b>。方向ごとに低周波の正弦波ノイズ({@link #wobble})で
 * {@code noiseAmplitude} 分だけ波打たせ、さらに最外周 1 ブロックを半々で欠けさせるので、
 * 瘤とへこみの混じった輪郭になる(ユーザー要望:「完璧な円ではなくある程度のノイズ混じりに」)。
 *
 * <p>使い手:
 * <ul>
 *   <li>{@link com.yayuy.overgrownvegetation.effect.ParasitizedCreeperDetonation} — 過繁茂した
 *       クリーパーの爆発。</li>
 *   <li>{@link com.yayuy.overgrownvegetation.entity.AbstractOvergrownInvaderEntity} — グループ4
 *       (拡大種)の死亡時。</li>
 * </ul>
 */
public final class NoisyCorruptionBurst {

    /**
     * {@code center} を中心にした半径 {@code radius} の球を汚染する。
     *
     * @param noiseAmplitude 境界を方向ごとに波打たせる振幅(ブロック、±)。0 なら綺麗な球。
     * @return 実際に汚染したブロック数。
     */
    public static int sphere(ServerLevel level, Vec3 center, int radius, double noiseAmplitude) {
        if (radius <= 0) {
            return 0;
        }
        RandomSource random = level.random;
        // 呼び出しごとに違う波打ち方にするための位相。
        double seed = (center.x * 0.11D) + (center.y * 0.07D) + (center.z * 0.13D) + random.nextInt(1024);

        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);
        int scan = radius + (int) Math.ceil(noiseAmplitude) + 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LongOpenHashSet touchedChunks = new LongOpenHashSet();
        int corruptedBlocks = 0;

        for (int dx = -scan; dx <= scan; dx++) {
            for (int dy = -scan; dy <= scan; dy++) {
                for (int dz = -scan; dz <= scan; dz++) {
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double effectiveRadius = radius + wobble(dx, dy, dz, dist, seed) * noiseAmplitude;
                    if (dist > effectiveRadius) {
                        continue;
                    }
                    // 縁(外周1ブロック)は半々で欠けさせて、輪郭をさらにギザギザにする。
                    if (dist > effectiveRadius - 1.0D && random.nextFloat() < 0.5F) {
                        continue;
                    }

                    pos.set(cx + dx, cy + dy, cz + dz);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.is(ModTags.CORRUPTED) || !CorruptionRegistry.canCorrupt(level, pos, state)) {
                        continue;
                    }
                    BlockState corrupted = CorruptionRegistry.corrupt(level, pos, state, random);
                    if (CorruptionRegistry.noChange(state, corrupted)) {
                        continue;
                    }

                    BlockPos placed = pos.immutable();
                    CorruptionRegistry.placeCorrupted(level, placed, state, corrupted, Block.UPDATE_ALL);
                    corruptedBlocks++;
                    CorruptionSpreader.growSurfacePlants(level, placed, corrupted,
                            OvergrownVegetationConfig.COMMON.growthChance.get());
                    touchedChunks.add(new ChunkPos(placed).toLong());
                }
            }
        }

        CorruptionCounter.record(level, corruptedBlocks);

        for (long packedChunk : touchedChunks) {
            OvergrownBiomeConverter.maybeConvert(level, new ChunkPos(packedChunk));
        }
        return corruptedBlocks;
    }

    /**
     * 1 ブロックだけ汚染する(グループ4の「じわじわ広がる弱い浸食」用)。
     * 既に汚染済み・汚染できないブロックなら何もせず {@code false}。
     *
     * <p>{@link #sphere} の 1 マス分と同じ手順 — 変換・設置・地表の草生やし・カウント・
     * バイオーム変換の打診まで面倒を見る。呼ぶ側は「どこを 1 マス汚すか」だけ決めればよい。
     */
    public static boolean single(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(ModTags.CORRUPTED) || !CorruptionRegistry.canCorrupt(level, pos, state)) {
            return false;
        }
        BlockState corrupted = CorruptionRegistry.corrupt(level, pos, state, level.random);
        if (CorruptionRegistry.noChange(state, corrupted)) {
            return false;
        }
        BlockPos placed = pos.immutable();
        CorruptionRegistry.placeCorrupted(level, placed, state, corrupted, Block.UPDATE_ALL);
        CorruptionSpreader.growSurfacePlants(level, placed, corrupted,
                OvergrownVegetationConfig.COMMON.growthChance.get());
        CorruptionCounter.record(level, 1);
        OvergrownBiomeConverter.maybeConvert(level, new ChunkPos(placed));
        return true;
    }

    /**
     * 方向ごとの境界の波打ち量(おおよそ -1〜+1)。オフセットそのものを低周波の正弦波に通した
     * 3D の値ノイズで、なだらかな瘤ができる(方向を正規化しないので原点付近では 0 に近く、
     * 綺麗な球からの膨らみ/へこみとして効く)。
     */
    private static double wobble(int dx, int dy, int dz, double dist, double seed) {
        if (dist < 1.0e-4D) {
            return 0.0D;
        }
        double v = Math.sin(dx * 0.9D + seed)
                + Math.sin(dz * 0.9D + seed * 1.3D)
                + Math.sin(dy * 1.1D + seed * 0.7D)
                + Math.sin((dx + dz) * 0.5D + seed * 1.9D);
        return v * 0.25D;
    }

    private NoisyCorruptionBurst() {
    }
}
