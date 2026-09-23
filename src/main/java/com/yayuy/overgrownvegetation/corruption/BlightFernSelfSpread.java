package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * 汚染シダ({@link BlightFernEntity})が自分の担当半径を汚染し終えて消える瞬間に、汚染し終えた
 * 範囲の外周付近へ次世代の汚染シダを数体スポーンさせる。これにより 1 体の汚染シダが役目を
 * 終えるたびに汚染域がひと回り外へ広がり、汚染が雪だるま式に拡大していく。
 *
 * <p>スポーン地点は起点(枯れた汚染シダの足元)から
 * {@code radius * blightFernFinishSpawnPerimeterFraction}〜{@code radius} の距離、ランダムな方位に
 * 探す(外周寄りに偏らせてある)。地表({@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES})の直下
 * ブロックが {@code #overgrown_vegetation:corrupted} である地点だけを採用するので、
 * 「汚染した範囲内・かつその外縁」に湧く。新しい汚染シダはそこからさらに自分の半径ぶん汚染を
 * 広げるので、次の完了時にはさらに外側へ……と広がり続ける。
 *
 * <p>各設定は {@link OvergrownVegetationConfig.Common} の {@code blightFernFinishSpawn*} 一式。
 * {@code blightFernFinishSpawnChance} を 0 にすると完全に無効化され、従来どおり汚染シダは
 * 役目を終えると何も残さず消えるだけになる。
 */
public final class BlightFernSelfSpread {

    private BlightFernSelfSpread() {
    }

    /**
     * 汚染完了時に {@link BlightFernEntity#tick()} から呼ぶ。
     *
     * @param origin              枯れた汚染シダの位置(スポーン地点を探す中心)
     * @param radius              その汚染シダが使っていた汚染半径({@code corruptionRadius})
     * @param corruptedByThisFern その汚染シダがこれまでに実際に新規汚染したブロック数
     *                            ({@link CorruptionSpreader#getCorruptedCount()})
     */
    public static void spawnNextGeneration(ServerLevel level, BlockPos origin, int radius, int corruptedByThisFern) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;

        double chance = cfg.blightFernFinishSpawnChance.get();
        if (chance <= 0.0D) {
            return;
        }

        // このシダが実際にはほとんど何も汚染しなかった = 既に汚染済みの領域の内側で生まれて、
        // やることが無いまま即座に「完了」しただけの個体。ここで増殖を許すと、汚染済み領域の
        // 中で一瞬で完了しては増える汚染シダが指数的に湧き続け、あちこちで点滅するバグになる。
        // 一定量以上を新規汚染した(=本当に汚染フロンティアで働いた)シダだけが次世代を残せる。
        if (corruptedByThisFern < cfg.blightFernFinishSpawnMinCorrupted.get()) {
            return;
        }

        RandomSource random = level.random;
        if (random.nextDouble() >= chance) {
            return;
        }

        // 起点の周囲に既に十分な数の汚染シダがいるなら、これ以上は増やさない(密度の上限)。
        // 完了間際のこのシダ自身もまだ discard() 前なのでこの数に含まれる。
        int maxNearby = cfg.blightFernFinishSpawnMaxNearby.get();
        if (maxNearby > 0) {
            int nearbyRadius = cfg.blightFernFinishSpawnNearbyRadius.get();
            AABB nearbyArea = new AABB(origin).inflate(nearbyRadius);
            if (level.getEntitiesOfClass(BlightFernEntity.class, nearbyArea).size() >= maxNearby) {
                return;
            }
        }

        int min = cfg.blightFernFinishSpawnMinCount.get();
        int max = Math.max(min, cfg.blightFernFinishSpawnMaxCount.get());
        int count = (max > min) ? min + random.nextInt(max - min + 1) : min;
        if (count <= 0) {
            return;
        }

        double fraction = Math.min(Math.max(cfg.blightFernFinishSpawnPerimeterFraction.get(), 0.0D), 1.0D);
        int outerDist = Math.max(1, radius);
        int innerDist = Math.min(outerDist, Math.max(1, (int) Math.round(outerDist * fraction)));
        int attempts = cfg.blightFernFinishSpawnAttempts.get();
        int minSeparation = cfg.blightFernFinishSpawnMinSeparation.get();
        int groundSearch = cfg.blightFernFinishSpawnCorruptedGroundSearch.get();

        for (int i = 0; i < count; i++) {
            trySpawnOne(level, origin, innerDist, outerDist, attempts, minSeparation, groundSearch, random);
        }
    }

    /** 条件を満たす地点が見つかれば汚染シダを 1 体湧かせて {@code true}。 */
    private static boolean trySpawnOne(ServerLevel level, BlockPos origin, int innerDist, int outerDist,
                                       int attempts, int minSeparation, int groundSearch, RandomSource random) {
        for (int attempt = 0; attempt < attempts; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            // sqrt を挟んで、一様乱数より外周寄りへ偏った距離を選ぶ(面積で見て外側が広いぶんの補正)。
            int span = outerDist - innerDist;
            int dist = innerDist + (span > 0 ? (int) Math.round(span * Math.sqrt(random.nextDouble())) : 0);
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * dist);

            // 選んだ (x, z) の地表の足場が汚染ブロックならそこ、外れていても水平 groundSearch
            // ブロック以内に汚染ブロックの足場があればそこへスナップする(まばらな汚染の筋の
            // 上に運悪く乗れなくても、近くに筋があれば拾えるようにして成功率を上げる)。
            BlockPos spawnPos = findCorruptedGroundSpot(level, x, z, groundSearch);
            if (spawnPos == null) {
                continue;
            }

            // 湧く 2 マスが「空気」または「水」で、当たり判定に埋まっていないこと。
            // 両方が水なら水中扱い(汚染水草になる)。片方だけ水など中途半端な場合は弾く。
            boolean atWater = level.getFluidState(spawnPos).is(FluidTags.WATER);
            boolean aboveWater = level.getFluidState(spawnPos.above()).is(FluidTags.WATER);
            boolean underwater = atWater && aboveWater;
            if ((atWater || aboveWater) && !underwater) {
                continue;
            }
            if (!level.getBlockState(spawnPos).getCollisionShape(level, spawnPos).isEmpty()
                    || !level.getBlockState(spawnPos.above()).getCollisionShape(level, spawnPos.above()).isEmpty()) {
                continue;
            }
            if (minSeparation > 0 && hasBlightFernNearby(level, spawnPos, minSeparation)) {
                continue;
            }

            // spawnPos は既に地表(地面ブロックの 1 つ上)なので Y オフセットは不要。
            // 水中なら汚染シダの代わりに汚染水草が湧く(BlightFernPlacement 参照)。
            if (BlightFernPlacement.spawn(level, spawnPos, MobSpawnType.MOB_SUMMONED) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code (x, z)} の列の地表(足場)が汚染ブロックならそのスポーン位置(足場の 1 つ上)を返す。
     * そうでなければ水平距離 {@code search} ブロックまでのマスを内側のリングから順に調べ、最初に
     * 見つかった「地表の足場が汚染ブロック」のマスのスポーン位置を返す。見つからなければ {@code null}。
     */
    @Nullable
    private static BlockPos findCorruptedGroundSpot(ServerLevel level, int x, int z, int search) {
        for (int r = 0; r <= search; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // 半径 r のリング上だけ(内側は前の周回で調べ済み)
                    }
                    int cx = x + dx;
                    int cz = z + dz;
                    if (!level.hasChunkAt(new BlockPos(cx, level.getMinBuildHeight(), cz))) {
                        continue;
                    }
                    // 水中の列は最上部が水面なので、海底(流体を除いた最上部)を見る。
                    int mb = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                    int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, cx, cz);
                    int surfaceY = Math.min(mb, floor);
                    BlockPos ground = new BlockPos(cx, surfaceY - 1, cz);
                    BlockState groundState = level.getBlockState(ground);
                    if (groundState.is(ModTags.CORRUPTED) && groundState.blocksMotion()) {
                        return new BlockPos(cx, surfaceY, cz);
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasBlightFernNearby(ServerLevel level, BlockPos pos, int distance) {
        AABB area = new AABB(pos).inflate(distance);
        return !level.getEntitiesOfClass(BlightFernEntity.class, area).isEmpty();
    }
}
