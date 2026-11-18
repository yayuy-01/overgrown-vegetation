package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.block.BracketFungusBlock;
import com.yayuy.overgrownvegetation.block.FallenLogBlock;
import com.yayuy.overgrownvegetation.block.LycopsidPlantBlock;
import com.yayuy.overgrownvegetation.block.MedullosaBlock;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 過繁茂した密林の林床(2026-11-01): 小葉類の敷物と株・泥炭の斑と水たまり・倒木と棚状の菌・メドゥロサ。どれも密林だけ。
 *
 * <ul>
 *     <li>{@link #beforePlants}: 地面が汚染された直後({@code CorruptionSpreader.growPlants} の頭)。泥炭の斑と倒木。地面を置き換えることがある。</li>
 *     <li>{@link #tryGrowUp}: 上面の下草の抽選({@code CorruptionSpreader.growUp})で、苗木・木生シダの次。メドゥロサ → 敷物(上に株) → 小葉類の株。</li>
 * </ul>
 * 斑・倒木の起点・水たまりは座標とワールドの種の混ぜ合わせで決める(汚染が来る順番に左右されない)。
 */
public final class FloorGrowth {

    private static final long PEAT_SALT = 0x2545F4914F6CDD1DL;
    private static final long POOL_SALT = 0x6A09E667F3BCC909L;
    private static final long LOG_SALT = 0x510E527FADE682D1L;
    private static final long K1 = 0x9E3779B97F4A7C15L, K2 = 0xC2B2AE3D27D4EB4FL, K3 = 0x165667B19E3779F9L;

    /** 低地: 海面からこの高さまで。 */
    private static final int LOWLAND_ABOVE_SEA = 4;
    /** 水辺: 水平にこのブロック数以内に水。 */
    private static final int WATERSIDE_RADIUS = 5;
    /** 泥炭の上に芽・屑を置く割合(残りはほかの下草)。 */
    private static final double PEAT_SPROUTS_SHARE = 0.3, PEAT_LITTER_SHARE = 0.3;

    // ======================= 下草の抽選(上面) =======================

    /** 苗木・木生シダの抽選の後に呼ぶ。林床の何かを置いたら true(呼び手はほかの下草を置かない)。{@code pos} は上面の上の空気。 */
    public static boolean tryGrowUp(ServerLevel level, BlockPos pos, RandomSource random, BlockState hostState) {
        if (!level.getBlockState(pos).isAir() || !isJungle(level, pos)) {
            return false;
        }
        var cfg = OvergrownVegetationConfig.COMMON;
        if (hostState.is(ModBlocks.PEAT.get())) {
            double roll = random.nextDouble();
            if (roll < PEAT_SPROUTS_SHARE) {
                return place(level, pos, ModBlocks.PEAT_SPROUTS.get().defaultBlockState());
            }
            if (roll < PEAT_SPROUTS_SHARE + PEAT_LITTER_SHARE) {
                return place(level, pos, ModBlocks.PEAT_LITTER.get().defaultBlockState());
            }
            return false;
        }
        if (random.nextDouble() < cfg.floorMedullosaChance.get() && growMedullosa(level, pos, random)) {
            return true;
        }
        if (random.nextDouble() < cfg.floorCarpetChance.get()) {
            BlockState carpet = random.nextDouble() < cfg.floorCarpetPlantChance.get()
                    ? lycopsidPlant(random).setValue(LycopsidPlantBlock.CARPET, true)
                    : ModBlocks.LYCOPSID_CARPET.get().defaultBlockState();
            return place(level, pos, carpet);
        }
        if (random.nextDouble() < cfg.floorLycopsidChance.get()) {
            return growLycopsid(level, pos, random);
        }
        return false;
    }

    private static boolean place(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.canSurvive(level, pos)) {
            return false;
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        return true;
    }

    private static BlockState lycopsidPlant(RandomSource random) {
        return (random.nextBoolean() ? ModBlocks.SELAGINELLA : ModBlocks.CLUBMOSS).get().defaultBlockState();
    }

    /** 小葉類の株(敷物なし)。{@code floorUprightShare} の割合で 2 段の立ち上がる小葉類。 */
    public static boolean growLycopsid(ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextDouble() < OvergrownVegetationConfig.COMMON.floorUprightShare.get()) {
            BlockPos upper = pos.above();
            BlockState upright = ModBlocks.LYCOPSID_UPRIGHT.get().defaultBlockState();
            if (com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, upper) && level.getBlockState(upper).isAir() && upright.canSurvive(level, pos)) {
                DoublePlantBlock.placeAt(level, upright, pos, Block.UPDATE_ALL);
                return true;
            }
        }
        return place(level, pos, lycopsidPlant(random));
    }

    /**
     * メドゥロサを {@code pos}(上面の上の空気)から立てる。高さ 2 か 3(半々)。幹と冠のマスと冠の上(立ち上がる葉)が空気で、
     * 冠の 4 方(板の葉が張り出す)が塞がっていない所だけ。根元が幹の絵 b、高さ 3 の冠は横 a + 垂れる葉、高さ 2 は横 b。
     */
    public static boolean growMedullosa(ServerLevel level, BlockPos pos, RandomSource random) {
        int height = random.nextBoolean() ? 3 : 2;
        BlockState base = ModBlocks.MEDULLOSA_TRUNK.get().defaultBlockState().setValue(MedullosaBlock.FLAG, true);
        if (!base.canSurvive(level, pos)) {
            return false;
        }
        for (int dy = 0; dy <= height; dy++) {
            BlockPos p = pos.above(dy);
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, p) || !level.getBlockState(p).isAir()) {
                return false;
            }
        }
        BlockPos crownPos = pos.above(height - 1);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos p = crownPos.relative(side);
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, p) || level.getBlockState(p).isSolidRender(level, p)) {
                return false;
            }
        }
        level.setBlock(pos, base, Block.UPDATE_ALL);
        if (height == 3) {
            level.setBlock(pos.above(), ModBlocks.MEDULLOSA_TRUNK.get().defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(crownPos, ModBlocks.MEDULLOSA_CROWN.get().defaultBlockState().setValue(MedullosaBlock.FLAG, height == 3), Block.UPDATE_ALL);
        return true;
    }

    // ======================= 地面(泥炭・倒木) =======================

    /**
     * 地面が汚染された直後に呼ぶ。泥炭の斑のマスなら泥炭へ置き換え、まわりの水たまりを掘る。倒木の起点なら倒木を置く。
     * 戻り値はその後の {@code hostState}(水たまりになったら水 = 呼び手は植物を生やさない)。
     */
    public static BlockState beforePlants(ServerLevel level, BlockPos pos, BlockState hostState) {
        if (!hostState.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get()) && !hostState.is(ModBlocks.OVERGROWN_DIRT.get())) {
            return hostState;
        }
        BlockPos above = pos.above();
        if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, above)) {
            return hostState;
        }
        BlockState up = level.getBlockState(above);
        if (!up.isAir() && !(up.canBeReplaced() && up.getFluidState().isEmpty())) {
            return hostState;
        }
        if (!isJungle(level, pos) || CaveGrowth.isUnderground(level, pos, hostState)) {
            return hostState;
        }
        CorruptionDropSuppressor.enter();
        try {
            long seed = level.getSeed();
            int patches = OvergrownVegetationConfig.COMMON.floorPeatPatches.get();
            if (patches > 0 && inPeatPatch(seed, pos.getX(), pos.getZ(), patches) && isLowland(level, pos)) {
                level.setBlock(pos, ModBlocks.PEAT.get().defaultBlockState(), Block.UPDATE_ALL);
                tryDigPool(level, pos);
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    tryDigPool(level, pos.relative(side));
                }
            }
            if (isFallenLogStart(seed, pos.getX(), pos.getZ())) {
                placeFallenLog(level, pos, level.random);
            }
        } finally {
            CorruptionDropSuppressor.exit();
        }
        return level.getBlockState(pos);
    }

    /** 1 チャンクに 1〜{@code maxPatches} 個の斑(半径² 1.2〜2.8 + マスごとの揺らぎ = 3〜9 マス)。隣のチャンクの斑もはみ出してくる。 */
    public static boolean inPeatPatch(long seed, int x, int z, int maxPatches) {
        int cx = x >> 4, cz = z >> 4;
        for (int dcx = -1; dcx <= 1; dcx++) {
            for (int dcz = -1; dcz <= 1; dcz++) {
                long h = mix(seed ^ PEAT_SALT ^ ((cx + dcx) * K1) ^ ((cz + dcz) * K2));
                int count = 1 + (int) Math.floorMod(h, (long) maxPatches);
                for (int i = 0; i < count; i++) {
                    h = mix(h);
                    int px = ((cx + dcx) << 4) + (int) (h & 15);
                    int pz = ((cz + dcz) << 4) + (int) ((h >>> 4) & 15);
                    double dx = x - px, dz = z - pz;
                    double d2 = dx * dx + dz * dz;
                    if (d2 > 9) {
                        continue;
                    }
                    double r2 = 1.2 + unit(mix(h ^ K3)) * 1.6;
                    double jitter = unit(mix(h ^ (x * K1) ^ (z * K2))) - 0.5;
                    if (d2 + jitter <= r2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 低地(海面 +{@value #LOWLAND_ABOVE_SEA} 以下)か水辺(水平 {@value #WATERSIDE_RADIUS} 以内、同じ高さか 1 段上に水)。 */
    private static boolean isLowland(ServerLevel level, BlockPos pos) {
        if (pos.getY() <= level.getSeaLevel() + LOWLAND_ABOVE_SEA) {
            return true;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -WATERSIDE_RADIUS; dx <= WATERSIDE_RADIUS; dx++) {
            for (int dz = -WATERSIDE_RADIUS; dz <= WATERSIDE_RADIUS; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    p.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, p) && level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 泥炭の {@code c} が 4 方を泥炭(か泥炭の水たまり)に囲まれ、上が空いていれば、確率で浅い水たまりにする: {@code c} を水、1 段下を水たまりの底。
     * 半分は深さ 2(下のマスも 4 方が塞がっているときだけ)。4 方が必ず塞がっているので水は流れ出さない。
     */
    private static void tryDigPool(ServerLevel level, BlockPos c) {
        if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, c) || !level.getBlockState(c).is(ModBlocks.PEAT.get())) {
            return;
        }
        long h = mix(level.getSeed() ^ POOL_SALT ^ (c.getX() * K1) ^ (c.getZ() * K2) ^ (c.getY() * K3));
        if (unit(h) >= OvergrownVegetationConfig.COMMON.floorPeatPoolChance.get()) {
            return;
        }
        BlockState up = level.getBlockState(c.above());
        if (!up.isAir() && !(up.canBeReplaced() && up.getFluidState().isEmpty())) {
            return;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos n = c.relative(side);
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, n)) {
                return;
            }
            BlockState s = level.getBlockState(n);
            boolean pool = s.is(Blocks.WATER) && level.getBlockState(n.below()).is(ModBlocks.PEAT_POOL_BOTTOM.get());
            boolean deepPool = s.is(Blocks.WATER) && level.getBlockState(n.below()).is(Blocks.WATER);
            if (!s.is(ModBlocks.PEAT.get()) && !pool && !deepPool) {
                return;
            }
        }
        BlockPos below = c.below();
        if (!level.getBlockState(below).isSolidRender(level, below)) {
            return;
        }
        boolean deep = (h & 0x100) != 0 && level.getBlockState(below.below()).isSolidRender(level, below.below());
        if (deep) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos n = below.relative(side);
                BlockState s = level.getBlockState(n);
                if (!s.isSolidRender(level, n) && !s.is(Blocks.WATER)) {
                    deep = false;
                    break;
                }
            }
        }
        if (!up.isAir()) {
            level.setBlock(c.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockState water = Blocks.WATER.defaultBlockState();
        if (deep) {
            level.setBlock(below.below(), ModBlocks.PEAT_POOL_BOTTOM.get().defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(below, water, Block.UPDATE_ALL);
        } else {
            level.setBlock(below, ModBlocks.PEAT_POOL_BOTTOM.get().defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(c, water, Block.UPDATE_ALL);
        if (!deep) {
            tryHorsetail(level, below); // 深さ 1 の水たまりにトクサの群れ(2026-11-02)
        }
    }

    // ======================= トクサの淵(2026-11-02、下絵 16 の案 A) =======================

    private static final long HORSETAIL_SALT = 0x1F83D9ABFB41BD6BL;
    /** 群れの中心はチャンクに 1 つ(この割合)。水辺から遠い中心は株が立たない = 水辺のチャンクだけ 0〜1 群。仮決め。 */
    static final double HORSETAIL_STAND_CHANCE = 1.0;
    /** 群れの広がり(中心からの距離の 2 乗。13 マス)と、その中で株が立つ割合。5〜13 本(平均 10)。仮決め。 */
    static final int HORSETAIL_RADIUS_SQ = 4;
    static final double HORSETAIL_DENSITY = 0.75;
    /** 陸の株は水平にこの距離以内に水があるときだけ(淵の縁)。仮決め。 */
    static final int HORSETAIL_WATER_RADIUS = 2;

    /** チャンク {@code (cx, cz)} の群れの中心 {x, z}。無ければ null。 */
    public static int[] horsetailAnchor(long seed, int cx, int cz) {
        long h = mix(seed ^ HORSETAIL_SALT ^ (cx * K1) ^ (cz * K2));
        if (unit(h) >= HORSETAIL_STAND_CHANCE) {
            return null;
        }
        h = mix(h);
        // チャンクの内側 4〜11 に置く: 群れ(半径 2)がチャンクをまたがず、隣のチャンクの群れと 9 以上離れる(重なると 1 か所に 20 本を超えた)
        return new int[]{(cx << 4) + 4 + (int) (h & 7), (cz << 4) + 4 + (int) ((h >>> 3) & 7)};
    }

    /** 列 {@code (x, z)} に立つトクサの高さ(陸で 2〜4。水の中は呼び手が +1)。群れの外・抜けた所は 0。座標と種だけで決まる。 */
    public static int horsetailHeightAt(long seed, int x, int z) {
        int[] a = horsetailAnchor(seed, x >> 4, z >> 4); // 群れはチャンクをまたがない
        if (a == null) {
            return 0;
        }
        int dx = x - a[0], dz = z - a[1];
        if (dx * dx + dz * dz > HORSETAIL_RADIUS_SQ) {
            return 0;
        }
        long h = mix(seed ^ HORSETAIL_SALT ^ (x * K3) ^ (z * K1));
        if (unit(h) >= HORSETAIL_DENSITY) {
            return 0;
        }
        return 2 + (int) Math.floorMod(mix(h), 3L);
    }

    /**
     * {@code ground}(汚染した地面・泥炭・水たまりの底・川底)の上にトクサを 1 本立てる(立てたら true)。群れの中の列だけ。
     * 陸 = 上が空気で、水平 {@value #HORSETAIL_WATER_RADIUS} 以内に水がある所。水 = 上が深さ 1 の水源(その上が空気)で、根元の段が水没し 1 段高い。
     * 地面が汚染されたとき({@code CorruptionSpreader.growPlants})と、泥炭の水たまりを掘ったとき({@link #tryDigPool})に呼ぶ。密林だけ。
     */
    public static boolean tryHorsetail(ServerLevel level, BlockPos ground) {
        int height = horsetailHeightAt(level.getSeed(), ground.getX(), ground.getZ());
        if (height <= 0 || !com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, ground.above(height + 1)) || !isJungle(level, ground)) {
            return false;
        }
        BlockState groundState = level.getBlockState(ground);
        if (!groundState.isFaceSturdy(level, ground, Direction.UP) || CaveGrowth.isUnderground(level, ground, groundState)) {
            return false; // 洞窟の床には立てない(群れの列は x, z だけで決まるので、真下の洞窟にも当たる)
        }
        BlockPos bottom = ground.above();
        BlockState at = level.getBlockState(bottom);
        int start;
        if (at.isAir()) {
            if (!nearWater(level, ground, HORSETAIL_WATER_RADIUS)) {
                return false;
            }
            start = 1;
        } else if (at.is(Blocks.WATER) && at.getFluidState().isSource() && level.getBlockState(bottom.above()).isAir()) {
            height++;
            start = 2;
        } else {
            return false;
        }
        for (int i = start; i < height; i++) {
            if (!level.getBlockState(ground.above(i + 1)).isAir()) {
                return false;
            }
        }
        com.yayuy.overgrownvegetation.block.HorsetailBlock.placeStem(level, bottom, height, ModBlocks.HORSETAIL.get().defaultBlockState());
        return true;
    }

    /** 水辺では林床の胞子の葦(トクサ)をトクサの群れに置き換える(葦を立てない。ユーザー指定)。密林で水平 {@value #WATERSIDE_RADIUS} 以内に水がある所。 */
    public static boolean replacesReedAt(ServerLevel level, BlockPos plantPos) {
        return isJungle(level, plantPos) && nearWater(level, plantPos.below(), WATERSIDE_RADIUS);
    }

    /** {@code ground} から水平 {@code radius} 以内(高さは地面の 1 段下〜1 段上)に水があるか。 */
    static boolean nearWater(ServerLevel level, BlockPos ground, int radius) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    p.set(ground.getX() + dx, ground.getY() + dy, ground.getZ() + dz);
                    if (com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, p) && level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 倒木の枠は 1 チャンクに 2 つ。それぞれ {@code floorFallenLogChance} で、起点の列は座標から決まる。 */
    static boolean isFallenLogStart(long seed, int x, int z) {
        double chance = OvergrownVegetationConfig.COMMON.floorFallenLogChance.get();
        int cx = x >> 4, cz = z >> 4;
        for (int slot = 0; slot < 2; slot++) {
            long h = mix(seed ^ LOG_SALT ^ (cx * K1) ^ (cz * K2) ^ (slot * K3));
            if (unit(h) >= chance) {
                continue;
            }
            h = mix(h);
            if ((x & 15) == (int) (h & 15) && (z & 15) == (int) ((h >>> 4) & 15)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 倒木を {@code ground}(地面のいちばん上のブロック)から置く(下絵 4 の置き方)。長さ 4〜9、向きは水平 4 方。
     * <ul>
     *     <li>35% は半分埋まる(地面と同じ高さに寝る = 地面を置き換える)。ほかは地面の上に乗る。</li>
     *     <li>途中で地面が 1 段下がれば下に空洞を作って渡り(水があれば水の上)、2 段以上の段差・壁・木で止まる。</li>
     *     <li>長さ 7 以上の 3 割は途中で折れる: 1 マス空けて、残りはその先の地面に合わせて置き直す(段の上に乗る)。</li>
     *     <li>35% は根元が跳ね上がる: 根元の外に縦 1×3 の根の皿、その外の地面を浅い水たまり(根の抜けた穴)。</li>
     *     <li>側面の 2〜4 か所に棚状の菌。</li>
     * </ul>
     * 置いたマスが 3 未満なら何も置かない。置いたら true。
     */
    public static boolean placeFallenLog(ServerLevel level, BlockPos ground, RandomSource random) {
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        int length = 4 + random.nextInt(6);
        boolean buried = random.nextFloat() < 0.35F;
        boolean rootPlate = random.nextFloat() < 0.35F;
        int breakAt = length >= 7 && random.nextFloat() < 0.3F ? 3 + random.nextInt(length - 6) : -1;

        List<BlockPos> cells = new ArrayList<>();
        int startY = ground.getY() + (buried ? 0 : 1);
        int y = startY;
        boolean reLevel = false;
        for (int i = 0; i < length; i++) {
            if (i == breakAt) {
                reLevel = true;
                continue;
            }
            int x = ground.getX() + dir.getStepX() * i, z = ground.getZ() + dir.getStepZ() * i;
            int g = groundTop(level, x, z, y + 2, y - 3);
            if (g == Integer.MIN_VALUE || !isCorruptedGround(level, x, g, z)) {
                break; // 汚染の縁の外(まだ汚染していない列)へははみ出さない
            }
            if (reLevel) {
                y = buried ? g : g + 1;
                reLevel = false;
                if (Math.abs(y - startY) > 1) {
                    break;
                }
            }
            int rel = y - g;
            if (rel < 0 || rel > 2) {
                break;
            }
            BlockPos cell = new BlockPos(x, y, z);
            if (!canTake(level, cell, rel == 0)) {
                break;
            }
            cells.add(cell);
        }
        if (cells.size() < 3) {
            return false;
        }

        BlockState log = ModBlocks.FALLEN_LOG.get().defaultBlockState().setValue(FallenLogBlock.AXIS, dir.getAxis());
        for (BlockPos cell : cells) {
            level.setBlock(cell, log, Block.UPDATE_ALL);
        }
        // 倒木の両端を、座標の乱数で G2・G3 の棲み処(倒木の洞)にする(2026-11-18 生態系。push-back 了承)
        com.yayuy.overgrownvegetation.ecosystem.life.EcoDens.onFallenLogPlaced(level, cells);
        if (rootPlate) {
            BlockPos base = cells.get(0).relative(dir.getOpposite());
            boolean ok = true;
            for (int dy = -1; dy <= 1 && ok; dy++) {
                ok = canTake(level, base.above(dy), dy == -1);
            }
            if (ok) {
                for (int dy = -1; dy <= 1; dy++) {
                    level.setBlock(base.above(dy), log.setValue(FallenLogBlock.ROOT, true), Block.UPDATE_ALL);
                }
                digRootHole(level, base.relative(dir.getOpposite()));
            }
        }
        int fungi = 2 + random.nextInt(3);
        Block fungus = ModBlocks.BRACKET_FUNGUS.get();
        for (int attempt = 0, placed = 0; attempt < fungi * 4 && placed < fungi; attempt++) {
            BlockPos cell = cells.get(random.nextInt(cells.size()));
            Direction side = random.nextBoolean() ? dir.getClockWise() : dir.getCounterClockWise();
            BlockPos f = cell.relative(side);
            BlockState at = level.getBlockState(f);
            if (!at.isAir() && !(at.canBeReplaced() && at.getFluidState().isEmpty())) {
                continue;
            }
            int fg = groundTop(level, f.getX(), f.getZ(), f.getY(), f.getY() - 3);
            if (fg == Integer.MIN_VALUE || !isCorruptedGround(level, f.getX(), fg, f.getZ())) {
                continue;
            }
            BlockState state = BracketFungusBlock.forWall(fungus, side, f);
            if (state.canSurvive(level, f)) {
                level.setBlock(f, state, Block.UPDATE_ALL);
                placed++;
            }
        }
        return true;
    }

    /** 列 {@code (x, z)} の {@code top} から {@code bottom} までで、上面が塞がった最初のブロックの高さ(植物・水は飛ばす)。無ければ MIN_VALUE。 */
    private static int groundTop(ServerLevel level, int x, int z, int top, int bottom) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = top; y >= bottom; y--) {
            p.set(x, y, z);
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, p)) {
                return Integer.MIN_VALUE;
            }
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty()) {
                continue;
            }
            return s.isFaceSturdy(level, p, Direction.UP) ? y : Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isCorruptedGround(ServerLevel level, int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y, z)).is(com.yayuy.overgrownvegetation.ModTags.CORRUPTED);
    }

    /** 倒木が入れるマス: 空気か置き換えられる植物(水は不可)。埋まるマスは汚染した土・草・泥炭だけ。 */
    private static boolean canTake(ServerLevel level, BlockPos p, boolean buried) {
        if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, p)) {
            return false;
        }
        BlockState s = level.getBlockState(p);
        if (buried) {
            return s.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get()) || s.is(ModBlocks.OVERGROWN_DIRT.get()) || s.is(ModBlocks.PEAT.get());
        }
        return s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty());
    }

    /** 根の抜けた穴: 列のいちばん上の土・草・泥炭を水、その下を水たまりの底に(4 方が塞がっているときだけ)。 */
    private static void digRootHole(ServerLevel level, BlockPos column) {
        int g = groundTop(level, column.getX(), column.getZ(), column.getY() + 1, column.getY() - 2);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        BlockPos hole = new BlockPos(column.getX(), g, column.getZ());
        if (!canTake(level, hole, true) || !level.getBlockState(hole.below()).isSolidRender(level, hole.below())) {
            return;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos n = hole.relative(side);
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, n) || !level.getBlockState(n).isSolidRender(level, n)) {
                return;
            }
        }
        BlockState up = level.getBlockState(hole.above());
        if (!up.isAir()) {
            if (!(up.canBeReplaced() && up.getFluidState().isEmpty())) {
                return;
            }
            level.setBlock(hole.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(hole.below(), ModBlocks.PEAT_POOL_BOTTOM.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hole, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ======================= 道具 =======================

    /**
     * GameTest の場は ±1500 万のどこかで、密林とは限らない。テストの同期の呼び出しの間だけ密林として扱う
     * ({@code jungle} = false なら密林でない扱い = 林床を足す前の下草の数を測る)。
     */
    @javax.annotation.Nullable
    private static Boolean jungleForTests;

    public static void runAsJungle(boolean jungle, Runnable action) {
        jungleForTests = jungle;
        try {
            action.run();
        } finally {
            jungleForTests = null;
        }
    }

    public static void runAsJungle(Runnable action) {
        runAsJungle(true, action);
    }

    /** 密林か、環境の分からない所(木生シダと同じ決まり)。 */
    static boolean isJungle(ServerLevel level, BlockPos pos) {
        if (jungleForTests != null) {
            return jungleForTests;
        }
        OvergrowthVariant variant = OvergrowthRegionMap.blendedVariantAt(level, pos.getX(), pos.getZ(), 1);
        return variant == null || variant == OvergrowthVariant.JUNGLE;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private FloorGrowth() {
    }
}
