package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ブロックが汚染された際、その周囲へ {@link ModBlocks#OVERGROWN_BUSH}(過繁茂した茂み)の
 * いびつな塊を生成する。
 *
 * <p>形は水平方向に大きく広がる平たい塊(厚み 1〜2 ブロック)。外周ほど high い確率で
 * 1マスずつ乱数で欠けさせるので、きれいな楕円にはならない。
 *
 * <p>地上({@link #tryGrow})では列(X/Z)ごとにその場の地表の高さを探し、
 * <b>段差が {@value #MAX_TERRAIN_FOLLOW} ブロック以内なら地面に沿わせて</b>茂みを載せる。
 * それより深い穴・崖など段差が大きい列は地面を追わず、汚染ブロックの高さで<b>浮かせて</b>生成する
 * (地面に張り付いた板ではなく、宙に浮いた塊になる)。
 *
 * <p>真上が塞がっていて真下が空気の場合(=洞窟の天井を汚染した)は、代わりに {@link #generateCeiling}
 * が天井に沿って下向きに垂れる塊を生成し、その一番下の茂みブロックの下面に垂れ植物を生やす。
 *
 * <p>壁面(側面の露出面)に汚染植物が生えるときは {@link #tryGrowOnWall} が地上より低い確率で
 * 呼ばれ、壁から突き出す(常に浮いた)茂みを生成する。
 *
 * <p>茂みを置いた列は、その一番上の茂みブロックの上に確率({@code bushUndergrowthChance})で、
 * 汚染時の露出上面と同じ抽選({@link CorruptionGrowth#randomTop})の下草が生える
 * ({@link com.yayuy.overgrownvegetation.block.OvergrownBushBlock} が支持面を持つので直接生やせる)。
 */
public final class LeafClumpGrowth {

    /** 1つの塊で置ける茂みブロックの上限(サーバー負荷の頭打ち)。 */
    private static final int MAX_CLUMP_BLOCKS = 50;
    /** 水平半径(X/Z)の最小・最大(ブロック)。軸ごとに個別に抽選するので楕円になる。 */
    private static final int MIN_HORIZONTAL_RADIUS = 2;
    private static final int MAX_HORIZONTAL_RADIUS = 4;
    /** 楕円の縁をどれくらい強く欠けさせるか(大きいほどスカスカ)。 */
    private static final float EDGE_NOISE = 0.6F;
    /** 中心からの正規化距離がこれ未満の列は 2 ブロック分の厚みにする(中央がやや盛り上がる)。 */
    private static final float DOME_THRESHOLD = 0.45F;
    /** 地面に沿わせる最大の段差(上下、ブロック)。これを超える段差/地面無しの列は浮かせて生成する。 */
    private static final int MAX_TERRAIN_FOLLOW = 2;

    /**
     * 汚染ブロックの露出した上面(または洞窟の天井では下面)から茂みの塊を生成しようとする。
     * 設定の確率抽選({@code leafClumpChance})に当たったうえで:
     * <ul>
     *     <li>真上が空気なら、地面に沿った(あるいは浮いた)いつも通りの塊を上に載せる。</li>
     *     <li>真上が塞がっていて真下が空気なら(=洞窟の天井を汚染した)、天井に沿って
     *         下向きに垂れる塊を生成し、その一番下の茂みブロックの下面に垂れ植物を生やす
     *         (要望: 茂みが洞窟を汚染する際は天井にも生成する)。</li>
     * </ul>
     */
    public static void tryGrow(ServerLevel level, BlockPos corruptedPos, RandomSource random) {
        if (OvergrownVegetationConfig.COMMON.swampNoBushes.get() && OvergrowthRegionMap.isSwampAtBlended(level, corruptedPos.getX(), corruptedPos.getZ(), 5)) {
            return; // 湿地・菌湿地帯には茂みを生やさない。
        }
        if (!OvergrownVegetationConfig.COMMON.spawnLeafClumps.get()) {
            return;
        }
        double chance = OvergrownVegetationConfig.COMMON.leafClumpChance.get();
        if (chance <= 0.0D || random.nextDouble() >= chance) {
            return;
        }

        int centerX = corruptedPos.getX() + random.nextInt(3) - 1;
        int centerZ = corruptedPos.getZ() + random.nextInt(3) - 1;

        BlockPos above = corruptedPos.above();
        if (com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, above) && level.getBlockState(above).isAir()) {
            // 地面に沿わせる基準は汚染ブロックの Y、浮かせる場合の高さはその1つ上(=空気)。
            generate(level, centerX, centerZ, corruptedPos.getY(), corruptedPos.getY() + 1, true, random);
            return;
        }

        BlockPos below = corruptedPos.below();
        if (com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, below) && level.getBlockState(below).isAir()) {
            // 洞窟の天井を汚染した。天井に沿って下向きに垂れる茂みの塊を生成する。
            generateCeiling(level, centerX, centerZ, corruptedPos.getY(), random);
        }
    }

    /**
     * 壁面(側面の露出面)に汚染植物が生えるときに呼ぶ。地上の {@link #tryGrow} とは別の
     * (低めの)確率 {@code wallLeafClumpChance} で、壁から突き出す茂みの塊を生成する。
     * 壁面では地面を追わず、常に {@code wallAirPos} の高さで浮かせて生成する。
     *
     * @param wallAirPos 壁から水平に突き出した1マス(既に空気であることが前提)
     */
    public static void tryGrowOnWall(ServerLevel level, BlockPos wallAirPos, RandomSource random) {
        if (OvergrownVegetationConfig.COMMON.swampNoBushes.get() && OvergrowthRegionMap.isSwampAtBlended(level, wallAirPos.getX(), wallAirPos.getZ(), 5)) {
            return; // 湿地・菌湿地帯には茂みを生やさない。
        }
        if (!OvergrownVegetationConfig.COMMON.spawnLeafClumps.get()) {
            return;
        }
        double chance = OvergrownVegetationConfig.COMMON.wallLeafClumpChance.get();
        if (chance <= 0.0D || random.nextDouble() >= chance) {
            return;
        }

        int centerX = wallAirPos.getX() + random.nextInt(3) - 1;
        int centerZ = wallAirPos.getZ() + random.nextInt(3) - 1;
        generate(level, centerX, centerZ, wallAirPos.getY(), wallAirPos.getY(), false, random);
    }

    /**
     * @param groundSearchCenterY {@code followGround} が true のときの地表探索の中心 Y。
     * @param floatBaseY          地面を追わない(段差が大きい/壁面)列を置く高さ。
     * @param followGround         true なら段差 {@value #MAX_TERRAIN_FOLLOW} 以内で地面に沿わせる。
     */
    private static void generate(ServerLevel level, int centerX, int centerZ, int groundSearchCenterY,
                                  int floatBaseY, boolean followGround, RandomSource random) {
        int rx = MIN_HORIZONTAL_RADIUS + random.nextInt(MAX_HORIZONTAL_RADIUS - MIN_HORIZONTAL_RADIUS + 1);
        int rz = MIN_HORIZONTAL_RADIUS + random.nextInt(MAX_HORIZONTAL_RADIUS - MIN_HORIZONTAL_RADIUS + 1);

        Block bushBlock = ModBlocks.OVERGROWN_BUSH.get();
        BlockState bush = bushBlock.defaultBlockState();
        double undergrowthChance = OvergrownVegetationConfig.COMMON.bushUndergrowthChance.get();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int placed = 0;

        for (int dx = -rx; dx <= rx && placed < MAX_CLUMP_BLOCKS; dx++) {
            float nx = (float) dx / rx;
            for (int dz = -rz; dz <= rz && placed < MAX_CLUMP_BLOCKS; dz++) {
                float nz = (float) dz / rz;
                float d = nx * nx + nz * nz;
                if (d > 1.0F) {
                    continue;
                }
                // 縁(d が 1 に近い)ほど欠けやすい。中心付近はほぼ確実に埋まる。
                if (random.nextFloat() < d * EDGE_NOISE) {
                    continue;
                }

                int cx = centerX + dx;
                int cz = centerZ + dz;

                int columnBase;
                if (followGround) {
                    int groundY = findGroundY(level, cx, cz, groundSearchCenterY, minY, maxY, bushBlock, cursor);
                    // 段差 MAX_TERRAIN_FOLLOW 以内で地面が見つかれば沿わせ、なければ浮かせる。
                    columnBase = groundY == Integer.MIN_VALUE ? floatBaseY : groundY + 1;
                } else {
                    columnBase = floatBaseY;
                }

                int layers = d < DOME_THRESHOLD ? 2 : 1;
                int topBushY = Integer.MIN_VALUE;
                for (int layer = 0; layer < layers && placed < MAX_CLUMP_BLOCKS; layer++) {
                    int y = columnBase + layer;
                    if (y < minY || y >= maxY) {
                        break;
                    }
                    cursor.set(cx, y, cz);
                    if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, cursor) || !level.getBlockState(cursor).isAir()) {
                        break;
                    }
                    level.setBlock(cursor, bush, Block.UPDATE_ALL);
                    topBushY = y;
                    placed++;
                }

                // この列の一番上の茂みブロックの上に、確率で下草を1つ生やす。
                if (topBushY != Integer.MIN_VALUE && undergrowthChance > 0.0D
                        && random.nextDouble() < undergrowthChance) {
                    placeUndergrowth(level, cx, topBushY + 1, cz, random, minY, maxY);
                }
            }
        }
    }

    /**
     * 洞窟の天井から下向きに垂れる茂みの塊を生成する。{@link #generate} を上下反転しただけの関係だが、
     * 天井は「列ごとに一番低い天井ブロックを {@code ceilingRefY ±}{@link #MAX_TERRAIN_FOLLOW} で探し、
     * その1つ下から下向きに積む」点と、一番下の茂みブロックの<b>下面</b>に垂れ植物
     * ({@link CorruptionGrowth#randomHanging})を生やす点が異なる。
     *
     * @param ceilingRefY 天井探索の中心 Y(通常は今しがた汚染された天井ブロックの Y)。
     */
    private static void generateCeiling(ServerLevel level, int centerX, int centerZ, int ceilingRefY,
                                         RandomSource random) {
        int rx = MIN_HORIZONTAL_RADIUS + random.nextInt(MAX_HORIZONTAL_RADIUS - MIN_HORIZONTAL_RADIUS + 1);
        int rz = MIN_HORIZONTAL_RADIUS + random.nextInt(MAX_HORIZONTAL_RADIUS - MIN_HORIZONTAL_RADIUS + 1);

        Block bushBlock = ModBlocks.OVERGROWN_BUSH.get();
        BlockState bush = bushBlock.defaultBlockState();
        double undergrowthChance = OvergrownVegetationConfig.COMMON.bushUndergrowthChance.get();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int placed = 0;

        for (int dx = -rx; dx <= rx && placed < MAX_CLUMP_BLOCKS; dx++) {
            float nx = (float) dx / rx;
            for (int dz = -rz; dz <= rz && placed < MAX_CLUMP_BLOCKS; dz++) {
                float nz = (float) dz / rz;
                float d = nx * nx + nz * nz;
                if (d > 1.0F) {
                    continue;
                }
                if (random.nextFloat() < d * EDGE_NOISE) {
                    continue;
                }

                int cx = centerX + dx;
                int cz = centerZ + dz;

                int ceilingY = findCeilingY(level, cx, cz, ceilingRefY, minY, maxY, bushBlock, cursor);
                // 天井が段差 MAX_TERRAIN_FOLLOW 以内で見つかればその1つ下から、なければ基準 Y の1つ下から垂らす。
                int columnBase = (ceilingY == Integer.MIN_VALUE ? ceilingRefY : ceilingY) - 1;

                int layers = d < DOME_THRESHOLD ? 2 : 1;
                int bottomBushY = Integer.MIN_VALUE;
                for (int layer = 0; layer < layers && placed < MAX_CLUMP_BLOCKS; layer++) {
                    int y = columnBase - layer;
                    if (y < minY || y >= maxY) {
                        break;
                    }
                    cursor.set(cx, y, cz);
                    if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, cursor) || !level.getBlockState(cursor).isAir()) {
                        break;
                    }
                    level.setBlock(cursor, bush, Block.UPDATE_ALL);
                    bottomBushY = y;
                    placed++;
                }

                // この列の一番下の茂みブロックの下面に、確率で垂れ植物を1つ生やす。
                if (bottomBushY != Integer.MIN_VALUE && undergrowthChance > 0.0D
                        && random.nextDouble() < undergrowthChance) {
                    placeHangingUndergrowth(level, cx, bottomBushY - 1, cz, minY, maxY, random);
                }
            }
        }
    }

    /**
     * {@code (cx, cz)} 列の天井(下面が空気に接した、空気でも上書き可能でも茂みでもない一番低いブロック)を
     * {@code centerY ±}{@link #MAX_TERRAIN_FOLLOW} の範囲で探す。見つからなければ {@link Integer#MIN_VALUE}。
     */
    private static int findCeilingY(ServerLevel level, int cx, int cz, int centerY, int minY, int maxY,
                                     Block bushBlock, BlockPos.MutableBlockPos cursor) {
        for (int y = centerY - MAX_TERRAIN_FOLLOW; y <= centerY + MAX_TERRAIN_FOLLOW; y++) {
            if (y < minY || y >= maxY) {
                continue;
            }
            cursor.set(cx, y, cz);
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, cursor)) {
                continue;
            }
            BlockState s = level.getBlockState(cursor);
            if (s.isAir() || s.canBeReplaced() || s.is(bushBlock)) {
                continue;
            }
            // 下面が空気(=洞窟側)を向いている天井ブロックだけを採用する。
            cursor.set(cx, y - 1, cz);
            if (y - 1 >= minY && com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, cursor)) {
                BlockState belowState = level.getBlockState(cursor);
                if (belowState.isAir() || belowState.canBeReplaced() || belowState.is(bushBlock)) {
                    return y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 茂みの下面(茂みブロックの下)に、下面の垂れ植物と同じ抽選の植物を1つ生やす。 */
    private static void placeHangingUndergrowth(ServerLevel level, int x, int y, int z, int minY, int maxY,
                                                 RandomSource random) {
        if (y < minY || y >= maxY) {
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, pos) || !level.getBlockState(pos).isAir()) {
            return;
        }
        BlockState growth = CorruptionGrowth.randomHanging(random);
        if (!growth.canSurvive(level, pos)) {
            return;
        }
        level.setBlock(pos, growth, Block.UPDATE_ALL);
    }

    /**
     * {@code (cx, cz)} 列の地表を {@code centerY ±}{@link #MAX_TERRAIN_FOLLOW} の範囲で探す
     * (空気でも上書き可能でも茂みでもない、一番上のブロック)。見つからなければ {@link Integer#MIN_VALUE}。
     */
    private static int findGroundY(ServerLevel level, int cx, int cz, int centerY, int minY, int maxY,
                                    Block bushBlock, BlockPos.MutableBlockPos cursor) {
        for (int y = centerY + MAX_TERRAIN_FOLLOW; y >= centerY - MAX_TERRAIN_FOLLOW; y--) {
            if (y < minY || y >= maxY) {
                continue;
            }
            cursor.set(cx, y, cz);
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, cursor)) {
                continue;
            }
            BlockState s = level.getBlockState(cursor);
            if (s.isAir() || s.canBeReplaced() || s.is(bushBlock)) {
                continue;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    /** 茂みの上面(茂みブロックの上)に、露出上面と同じ抽選の下草を1つ生やす。 */
    private static void placeUndergrowth(ServerLevel level, int x, int y, int z, RandomSource random,
                                          int minY, int maxY) {
        if (y < minY || y >= maxY) {
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, pos) || !level.getBlockState(pos).isAir()) {
            return;
        }
        BlockState growth = CorruptionGrowth.randomTop(random);
        if (!growth.canSurvive(level, pos)) {
            return;
        }
        level.setBlock(pos, growth, Block.UPDATE_ALL);
    }

    private LeafClumpGrowth() {
    }
}
