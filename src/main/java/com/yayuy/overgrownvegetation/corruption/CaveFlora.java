package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.block.CaveMyceliumBlock;
import com.yayuy.overgrownvegetation.block.CaveSheetBlock;
import com.yayuy.overgrownvegetation.block.FoxfireBlock;
import com.yayuy.overgrownvegetation.block.LeyRootBlock;
import com.yayuy.overgrownvegetation.block.OvergrownGlowVines;
import com.yayuy.overgrownvegetation.block.PrototaxitesTopBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 地下の植生 1(2026-11-03、光を出す側)の置き方。汚染した洞窟のブロック 1 つにつき {@link CorruptionSpreader#growSurfacePlants} から
 * ({@link CaveGrowth#tryStructure} の直後に)1 回呼ばれる。
 * <ul>
 *     <li><b>天井の光源 A</b>(U2 の巨大な洞窟): 天井から床まで {@link #BIG_CAVE_HEIGHT} 以上の空洞の天井に、光る根の網を円く貼り、中心から房を垂らす。
 *     まわりに光る蔓を足し、房の下の床に淡い下生えを生やす。区画(チャンクの 3 段のセクション)に 1 か所まで。</li>
 *     <li><b>地脈の根</b>(U3): 壁に縦の線で這わせる。光り方は地脈の線({@link LeyLines})までの距離。地脈の近くほど多い。</li>
 *     <li><b>狐火茸</b>(U4): 床にまばら(チャンクのその高さで {@link #FOXFIRE_MAX} 本まで)。地脈の近く(根が near になる所)には出さない。</li>
 *     <li><b>菌糸</b>(11-03 直し): 小さな洞窟(上下の空きが {@link #BIG_CAVE_HEIGHT} 未満)の暗い面に、面に貼る薄いブロック {@code cave_mycelium} を貼る。</li>
 * </ul>
 * 地下の植生 2(11-03 後半。どれも光らない):
 * <ul>
 *     <li><b>根の簾</b>(U5): 地表の巨大樹・コルダイテスの幹の真下(天井から {@link #CURTAIN_TREE_REACH} 以内)と洞窟の口(空の光の帯)の天井から、
 *     細い根を 5〜12 本・長さ 2〜6 垂らす。セクションに 1 束。</li>
 *     <li><b>胞子の溜まり</b>(U6): 床の窪み({@link #isPit})の真ん中に見えない印 {@code cave_spore_pool} を置く。霧と粒はクライアント、濃度の上乗せは
 *     {@code SporeConcentration}。セクションに 1 つ。</li>
 *     <li><b>巨大菌の柱</b>(U7): 床から天井まで {@link #PILLAR_MIN_SPAN} 以上の空洞に、高さ 8〜15・太さ 1×1 か 2×2 の {@code prototaxites_stem}。
 *     チャンクの 3 セクションに 1 本。</li>
 * </ul>
 * 数はすべて仮決め(11-03)。U8(地下の水の光る藻)はクライアントの {@code GlowAlgae} だけ。
 */
public final class CaveFlora {

    // ---- 天井の光源 A(仮決め) ----
    /** 「巨大な洞窟」= 天井から床までの空気の段数がこれ以上。 */
    public static final int BIG_CAVE_HEIGHT = 12;
    /** 巨大な洞窟の天井のブロック 1 つが光源 A の中心になる確率。 */
    static final double CEILING_LIGHT_CHANCE = 0.04D;
    /** 網の円の半径(直径 5〜7)。 */
    static final double NET_RADIUS_MIN = 2.5D;
    static final double NET_RADIUS_MAX = 3.5D;
    /** 房の本数と長さ(先を含む段数)。 */
    static final int TASSELS_MIN = 1;
    static final int TASSELS_MAX = 3;
    static final int TASSEL_LEN_MIN = 2;
    static final int TASSEL_LEN_MAX = 5;
    /** 網のまわりに足す光る蔓(本数・中心からの水平距離)。 */
    static final int EXTRA_VINES_MIN = 2;
    static final int EXTRA_VINES_MAX = 4;
    static final double EXTRA_VINES_NEAR = 4.0D;
    static final double EXTRA_VINES_FAR = 8.0D;
    /** 房の下の床の下生え(半径・生やす割合)。 */
    static final int UNDER_RADIUS = 6;
    static final double UNDER_SHARE = 0.35D;
    /** 水辺のトクサは水平この距離以内に水があるときだけ。 */
    static final int HORSETAIL_WATER_REACH = 2;

    // ---- 地脈の根(仮決め) ----
    /** 地脈の線からこの距離(ブロック)以内 = near、{@link #LEY_FAR} 以内 = far、それより遠い = none。 */
    public static final double LEY_NEAR = 6.0D;
    public static final double LEY_FAR = 18.0D;
    /** 壁の面 1 つが線の起点になる確率(線の長さ 2〜4 を掛けると面のおよそ 35% / 10% / 3% を覆う)。 */
    static final double LEY_CHANCE_NEAR = 0.12D;
    static final double LEY_CHANCE_FAR = 0.035D;
    static final double LEY_CHANCE_NONE = 0.01D;
    static final int LEY_RUN_MIN = 2;
    static final int LEY_RUN_MAX = 4;

    // ---- 狐火茸(仮決め) ----
    /** 床のブロック 1 つに狐火茸が立つ確率と、チャンクのその高さ(±{@link #FOXFIRE_SPAN})の上限。 */
    static final double FOXFIRE_CHANCE = 0.02D;
    public static final int FOXFIRE_MAX = 3;
    static final int FOXFIRE_SPAN = 8;
    /** 朽ちた根の切れ端(下絵 b)の割合。 */
    static final double FOXFIRE_WOOD_SHARE = 0.4D;

    // ---- 小さな洞窟の菌糸(仮決め 11-03 直し。ユーザー指定: 面に貼る薄いブロック・小さな洞窟だけ) ----
    /** 汚染した洞窟のブロック 1 つが、隣の暗い空気のマスに菌糸を貼る確率(角のマスは 2〜3 個の岩から抽選されるので、面のおよそ 3〜4 割)。 */
    public static final double MYCELIUM_CHANCE = 0.2D;
    /** 菌糸を貼るマスで、ほかの支えの面にもまとめて貼る割合(1 ブロックに複数の面)。 */
    static final double MYCELIUM_BUNDLE = 0.8D;

    // ---- U5 根の簾(仮決め 11-03。地下の植生 2) ----
    /** 天井のブロック 1 つが簾の束の起点になる確率(木の真下か洞窟の口のときだけ。区画に 1 束)。 */
    static final double CURTAIN_CHANCE = 0.04D;
    /** 天井から上へ木の幹を探す段数(ユーザー指定の依頼「天井が木から 30 ブロック以内」)と、探す列の水平のずれ(真ん中と東西南北 ±2)。 */
    public static final int CURTAIN_TREE_REACH = 30;
    static final int CURTAIN_TREE_SIDE = 2;
    /** 1 束の本数と 1 本の長さ(先を含む段数)。 */
    static final int CURTAIN_STRANDS_MIN = 5;
    static final int CURTAIN_STRANDS_MAX = 12;
    static final int CURTAIN_LEN_MIN = 2;
    static final int CURTAIN_LEN_MAX = 6;
    /** 束を広げる半径(木の下 / 洞窟の口)。 */
    static final int CURTAIN_RADIUS_TREE = 2;
    static final int CURTAIN_RADIUS_MOUTH = 3;
    /** 洞窟の口 = 天井の下のマスの空の光がこの間(屋根の下では 1 ブロックごとに 1 減る = 縁から奥へ 1〜6 ブロック)。 */
    public static final int MOUTH_SKY_MIN = 9;
    public static final int MOUTH_SKY_MAX = 14;

    // ---- U6 胞子の溜まり(仮決め 11-03) ----
    /** 床のブロック 1 つが溜まりの印の候補になる確率(区画に 1 つ)。 */
    static final double POOL_CHANCE = 0.03D;
    /** 窪みの床を 8 方向にたどる最大の歩数と、縁(2 段以上高い床)に当たらなければならない方向の数。 */
    static final int POOL_REACH = 6;
    static final int POOL_RIM_DIRS = 7;
    /** 窪みの幅(東西・南北とも)の下限。 */
    public static final int POOL_MIN_WIDTH = 3;

    // ---- U7 巨大菌の柱(仮決め 11-03) ----
    /** 床のブロック 1 つが柱の根元になる確率(区画 = チャンクの 3 セクションに 1 本)。 */
    static final double PILLAR_CHANCE = 0.01D;
    /** 床から天井までの空気の段数の下限(依頼「10 以上」と巨大な洞窟の高さの大きい方)。 */
    public static final int PILLAR_MIN_SPAN = Math.max(10, BIG_CAVE_HEIGHT);
    static final int PILLAR_HEIGHT_MIN = 8;
    static final int PILLAR_HEIGHT_MAX = 15;
    /** 2×2 の柱の割合。 */
    static final double PILLAR_WIDE_SHARE = 0.7D;
    /** 天辺が平たい傘になる割合(残りは丸い瘤。11-04 ユーザー決定「瘤と傘を混ぜる」、割合は仮)。傘は天辺の上に 2 段の空きがあり、まわりにはみ出す所が空いているときだけ。 */
    static final double PILLAR_CAP_SHARE = 0.3D;

    private CaveFlora() {
    }

    /** 汚染した洞窟のブロック {@code host} のまわりに、地下の植生を確率で置く。 */
    public static void decorate(ServerLevel level, BlockPos host, BlockState hostState, RandomSource random) {
        BlockPos below = host.below();
        if (isAir(level, below)) {
            if (random.nextDouble() < CEILING_LIGHT_CHANCE && caveHeightBelow(level, host) >= BIG_CAVE_HEIGHT) {
                placeCeilingLight(level, host, random);
            }
            if (random.nextDouble() < CURTAIN_CHANCE) {
                placeRootCurtain(level, host, random);
            }
        }
        if (isAir(level, host.above())) {
            // 柱を先に(溜まりの印・狐火茸・菌糸は柱の空けたマスを使わない)
            if (random.nextDouble() < PILLAR_CHANCE) {
                placePrototaxites(level, host, random);
            }
            if (random.nextDouble() < POOL_CHANCE) {
                placeSporePool(level, host);
            }
        }
        LeyRootBlock.Glow glow = leyGlowAt(level.getSeed(), host.getX(), host.getZ());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (random.nextDouble() < leyChance(glow)) {
                placeLeyRoots(level, host, d, glow, random);
            }
        }
        if (glow != LeyRootBlock.Glow.NEAR && random.nextDouble() < FOXFIRE_CHANCE) {
            placeFoxfire(level, host, random);
        }
        // 菌糸は最後(ほかの植生は菌糸のマスにも置ける = isAir が菌糸を空気と見る)
        if (random.nextDouble() < MYCELIUM_CHANCE) {
            placeMycelium(level, host, hostState, random);
        }
    }

    // ------------------------------------------------------------------
    // 天井の光源 A
    // ------------------------------------------------------------------

    /** 天井のブロック {@code ceiling} の下に続く空気の段数(床まで。最大 64)。 */
    public static int caveHeightBelow(ServerLevel level, BlockPos ceiling) {
        BlockPos.MutableBlockPos p = ceiling.mutable();
        int n = 0;
        while (n < 64) {
            p.move(Direction.DOWN);
            if (!isAir(level, p)) {
                break;
            }
            n++;
        }
        return n;
    }

    /**
     * 天井のブロック {@code center} を中心に光源 A を 1 つ置く(確率と高さは呼ぶ側が見る)。同じ区画にもう網があれば置かない。
     *
     * @return 置いたら true
     */
    public static boolean placeCeilingLight(ServerLevel level, BlockPos center, RandomSource random) {
        BlockState net = ModBlocks.CEILING_ROOT_NET.get().defaultBlockState().setValue(CaveSheetBlock.FACING, Direction.UP);
        if (sectionsMayHave(level, center, 1, s -> s.is(ModBlocks.CEILING_ROOT_NET.get()))) {
            return false;
        }
        double radius = NET_RADIUS_MIN + random.nextDouble() * (NET_RADIUS_MAX - NET_RADIUS_MIN);
        int r = (int) Math.ceil(radius);
        List<BlockPos> nets = new ArrayList<>();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius || (d > radius - 1.0D && random.nextFloat() < 0.3F)) {
                    continue; // 縁は 3 割欠けさせる
                }
                BlockPos cell = ceilingCell(level, center.getX() + dx, center.getY(), center.getZ() + dz, 3);
                if (cell != null && net.canSurvive(level, cell)) {
                    level.setBlock(cell, net, Block.UPDATE_ALL);
                    nets.add(cell);
                }
            }
        }
        if (nets.isEmpty()) {
            return false;
        }
        // 房: 中心に近い網から
        List<BlockPos> inner = new ArrayList<>();
        for (BlockPos n : nets) {
            if (sq(n.getX() - center.getX()) + sq(n.getZ() - center.getZ()) <= 2) {
                inner.add(n);
            }
        }
        if (inner.isEmpty()) {
            inner.add(nets.get(0));
        }
        int tassels = TASSELS_MIN + random.nextInt(TASSELS_MAX - TASSELS_MIN + 1);
        for (int t = 0; t < tassels && !inner.isEmpty(); t++) {
            BlockPos from = inner.remove(random.nextInt(inner.size()));
            hangTassel(level, from, TASSEL_LEN_MIN + random.nextInt(TASSEL_LEN_MAX - TASSEL_LEN_MIN + 1));
        }
        // 光る蔓を網のまわりに(ユーザー「光る蔓も混ぜる」)
        int vines = EXTRA_VINES_MIN + random.nextInt(EXTRA_VINES_MAX - EXTRA_VINES_MIN + 1);
        for (int v = 0; v < vines; v++) {
            double a = random.nextDouble() * Math.PI * 2.0D;
            double dist = EXTRA_VINES_NEAR + random.nextDouble() * (EXTRA_VINES_FAR - EXTRA_VINES_NEAR);
            BlockPos cell = ceilingCell(level, center.getX() + (int) Math.round(Math.cos(a) * dist), center.getY(),
                    center.getZ() + (int) Math.round(Math.sin(a) * dist), 4);
            if (cell == null) {
                continue;
            }
            BlockState vine = ModBlocks.OVERGROWN_GLOW_VINES.get().defaultBlockState().setValue(OvergrownGlowVines.BERRIES, true);
            if (vine.canSurvive(level, cell)) {
                level.setBlock(cell, vine, Block.UPDATE_ALL);
            }
        }
        placeUndergrowth(level, center, random);
        return true;
    }

    /** 網の下 {@code from} から {@code length} 段(先を含む)房を垂らす。空気が足りなければ短くする。 */
    static void hangTassel(ServerLevel level, BlockPos from, int length) {
        int n = 0;
        while (n < length && isAir(level, from.below(n + 1)) && isAir(level, from.below(n + 2))) {
            n++; // 先の下にも 1 段の空気を残す
        }
        if (n == 0) {
            return;
        }
        for (int i = 1; i < n; i++) {
            level.setBlock(from.below(i), ModBlocks.GLOW_TASSEL.get().defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(from.below(n), ModBlocks.GLOW_TASSEL_TIP.get().defaultBlockState(), Block.UPDATE_ALL);
    }

    /** 光源 A の真下の床(半径 {@link #UNDER_RADIUS})に淡い下生えを生やす。 */
    static void placeUndergrowth(ServerLevel level, BlockPos center, RandomSource random) {
        BlockPos.MutableBlockPos p = center.mutable();
        int floorY = Integer.MIN_VALUE;
        for (int i = 0; i < 64; i++) {
            p.move(Direction.DOWN);
            if (!level.isLoaded(p)) {
                return;
            }
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && s.blocksMotion()) { // 網・房・蔓は読み飛ばす
                floorY = p.getY();
                break;
            }
        }
        if (floorY == Integer.MIN_VALUE) {
            return;
        }
        for (int dz = -UNDER_RADIUS; dz <= UNDER_RADIUS; dz++) {
            for (int dx = -UNDER_RADIUS; dx <= UNDER_RADIUS; dx++) {
                if (dx * dx + dz * dz > UNDER_RADIUS * UNDER_RADIUS || random.nextDouble() >= UNDER_SHARE) {
                    continue;
                }
                BlockPos at = floorCell(level, center.getX() + dx, floorY, center.getZ() + dz, 3);
                if (at == null) {
                    continue;
                }
                BlockState plant = undergrowth(level, at, random);
                if (plant.canSurvive(level, at)) {
                    level.setBlock(at, plant, Block.UPDATE_ALL);
                }
            }
        }
    }

    /** 下生えの種類。水辺(水平 2 以内に水)なら半分はトクサ。 */
    private static BlockState undergrowth(ServerLevel level, BlockPos at, RandomSource random) {
        if (nearWater(level, at) && random.nextBoolean()) {
            return ModBlocks.CAVE_HORSETAIL.get().defaultBlockState();
        }
        return switch (random.nextInt(3)) {
            case 0 -> ModBlocks.PALE_CAVE_FERN.get().defaultBlockState();
            case 1 -> ModBlocks.PALE_CAVE_CAPS.get().defaultBlockState();
            default -> ModBlocks.PALE_CAVE_MOSS.get().defaultBlockState();
        };
    }

    /** 床の上のマス {@code at} の水平 {@link #HORSETAIL_WATER_REACH} 以内(同じ高さと 1 つ下)に水があるか。 */
    static boolean nearWater(ServerLevel level, BlockPos at) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 0; dy++) {
            for (int dz = -HORSETAIL_WATER_REACH; dz <= HORSETAIL_WATER_REACH; dz++) {
                for (int dx = -HORSETAIL_WATER_REACH; dx <= HORSETAIL_WATER_REACH; dx++) {
                    p.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (level.isLoaded(p) && level.getFluidState(p).is(FluidTags.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 地脈の根
    // ------------------------------------------------------------------

    /** 地脈の線までの距離から、根の光り方。 */
    public static LeyRootBlock.Glow glowForDistance(double distance) {
        return distance <= LEY_NEAR ? LeyRootBlock.Glow.NEAR : distance <= LEY_FAR ? LeyRootBlock.Glow.FAR : LeyRootBlock.Glow.NONE;
    }

    public static LeyRootBlock.Glow leyGlowAt(long seed, int x, int z) {
        return glowForDistance(LeyLines.distanceToLine(seed, x, z));
    }

    private static double leyChance(LeyRootBlock.Glow glow) {
        return switch (glow) {
            case NEAR -> LEY_CHANCE_NEAR;
            case FAR -> LEY_CHANCE_FAR;
            case NONE -> LEY_CHANCE_NONE;
        };
    }

    /**
     * 壁のブロック {@code wall} の {@code side} の面から下へ、2〜4 段の地脈の根を這わせる(壁が途切れたらそこまで)。
     *
     * @return 置いた段数
     */
    public static int placeLeyRoots(ServerLevel level, BlockPos wall, Direction side, LeyRootBlock.Glow glow, RandomSource random) {
        BlockState root = ModBlocks.LEY_ROOT.get().defaultBlockState().setValue(CaveSheetBlock.FACING, side.getOpposite())
                .setValue(LeyRootBlock.GLOW, glow);
        int run = LEY_RUN_MIN + random.nextInt(LEY_RUN_MAX - LEY_RUN_MIN + 1);
        int placed = 0;
        for (int i = 0; i < run; i++) {
            BlockPos at = wall.relative(side).below(i);
            if (!isAir(level, at) || !root.canSurvive(level, at)) {
                break;
            }
            level.setBlock(at, root, Block.UPDATE_ALL);
            placed++;
        }
        return placed;
    }

    // ------------------------------------------------------------------
    // 狐火茸
    // ------------------------------------------------------------------

    /**
     * 床のブロック {@code floor} の上に狐火茸を 1 本立てる。チャンクのその高さ(±{@link #FOXFIRE_SPAN})にもう {@link #FOXFIRE_MAX} 本あれば立てない。
     *
     * @return 立てたら true
     */
    public static boolean placeFoxfire(ServerLevel level, BlockPos floor, RandomSource random) {
        BlockPos at = floor.above();
        if (!isAir(level, at)) {
            return false;
        }
        BlockState foxfire = ModBlocks.FOXFIRE.get().defaultBlockState().setValue(FoxfireBlock.WOOD, random.nextDouble() < FOXFIRE_WOOD_SHARE);
        if (!foxfire.canSurvive(level, at) || countFoxfire(level, at) >= FOXFIRE_MAX) {
            return false;
        }
        level.setBlock(at, foxfire, Block.UPDATE_ALL);
        return true;
    }

    /** {@code at} のチャンクの、高さ ±{@link #FOXFIRE_SPAN} の狐火茸の数。 */
    public static int countFoxfire(ServerLevel level, BlockPos at) {
        if (!sectionsMayHave(level, at, 1, s -> s.is(ModBlocks.FOXFIRE.get()))) {
            return 0;
        }
        LevelChunk chunk = level.getChunkAt(at);
        int bx = SectionPos.sectionToBlockCoord(chunk.getPos().x);
        int bz = SectionPos.sectionToBlockCoord(chunk.getPos().z);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int n = 0;
        for (int y = at.getY() - FOXFIRE_SPAN; y <= at.getY() + FOXFIRE_SPAN; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (chunk.getBlockState(p.set(bx + x, y, bz + z)).is(ModBlocks.FOXFIRE.get())) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 小さな洞窟の菌糸
    // ------------------------------------------------------------------

    /**
     * 汚染した洞窟のブロック {@code host} の地下の面({@link CaveCover#caveFaces})から 1 つを選び、その前の空気のマスに菌糸を貼る。
     * そのマスのほかの支えの面にも {@link #MYCELIUM_BUNDLE} の割合でまとめて貼る。上下の空きが {@link #BIG_CAVE_HEIGHT} 以上の空洞(巨大な洞窟)には貼らない。
     * 絵の組(下地)は {@code host} から選ぶ(前からある菌糸のマスに面を足すときは変えない)。
     *
     * @return 貼ったら true
     */
    public static boolean placeMycelium(ServerLevel level, BlockPos host, BlockState hostState, RandomSource random) {
        int faces = CaveCover.caveFaces(level, host);
        if (faces == 0) {
            return false;
        }
        List<Direction> sides = new ArrayList<>();
        for (Direction d : Direction.values()) {
            if ((faces & 1 << d.ordinal()) != 0) {
                sides.add(d);
            }
        }
        Direction side = sides.get(random.nextInt(sides.size()));
        BlockPos cell = host.relative(side);
        BlockState current = level.getBlockState(cell);
        CaveMyceliumBlock block = (CaveMyceliumBlock) ModBlocks.CAVE_MYCELIUM.get();
        if (!current.isAir() && !current.is(block)) {
            return false;
        }
        Direction toHost = side.getOpposite();
        if (!MultifaceBlock.canAttachTo(level, toHost, host, hostState) || openSpan(level, cell) >= BIG_CAVE_HEIGHT) {
            return false;
        }
        BlockState state = current.is(block) ? current
                : block.defaultBlockState().setValue(CaveMyceliumBlock.GROUND, CaveMyceliumBlock.groundOf(hostState));
        state = state.setValue(MultifaceBlock.getFaceProperty(toHost), true);
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            if (d == toHost || random.nextDouble() >= MYCELIUM_BUNDLE) {
                continue;
            }
            n.setWithOffset(cell, d);
            if (level.isLoaded(n) && MultifaceBlock.canAttachTo(level, d, n, level.getBlockState(n))) {
                state = state.setValue(MultifaceBlock.getFaceProperty(d), true);
            }
        }
        level.setBlock(cell, state, Block.UPDATE_ALL);
        return true;
    }

    /** {@code cell} を通る縦の空き(空気・当たり判定の無い植物、水は含めない)の段数。{@link #BIG_CAVE_HEIGHT} で打ち切る。 */
    public static int openSpan(ServerLevel level, BlockPos cell) {
        int n = 1;
        BlockPos.MutableBlockPos p = cell.mutable();
        while (n < BIG_CAVE_HEIGHT && open(level, p.move(Direction.UP))) {
            n++;
        }
        p.set(cell);
        while (n < BIG_CAVE_HEIGHT && open(level, p.move(Direction.DOWN))) {
            n++;
        }
        return n;
    }

    private static boolean open(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState s = level.getBlockState(pos);
        return s.isAir() || !s.blocksMotion() && s.getFluidState().isEmpty();
    }

    // ------------------------------------------------------------------
    // U5 根の簾
    // ------------------------------------------------------------------

    /** 根の簾の出る所。 */
    public enum CurtainSite { NONE, TREE, MOUTH }

    /** 天井のブロック {@code ceiling} が、地表の木の幹の真下({@link #CURTAIN_TREE_REACH} 以内)か洞窟の口(下のマスの空の光)か。木が先。 */
    public static CurtainSite curtainSite(ServerLevel level, BlockPos ceiling) {
        if (underTree(level, ceiling)) {
            return CurtainSite.TREE;
        }
        return mouthSky(level, ceiling.below()) ? CurtainSite.MOUTH : CurtainSite.NONE;
    }

    /** {@code ceiling} から上へ {@link #CURTAIN_TREE_REACH} 段の、真ん中と東西南北 ±{@link #CURTAIN_TREE_SIDE} の列に、巨大樹・コルダイテスの幹があるか。 */
    static boolean underTree(ServerLevel level, BlockPos ceiling) {
        int[][] cols = {{0, 0}, {CURTAIN_TREE_SIDE, 0}, {-CURTAIN_TREE_SIDE, 0}, {0, CURTAIN_TREE_SIDE}, {0, -CURTAIN_TREE_SIDE}};
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= CURTAIN_TREE_REACH; dy++) {
            for (int[] c : cols) {
                p.set(ceiling.getX() + c[0], ceiling.getY() + dy, ceiling.getZ() + c[1]);
                if (level.isLoaded(p) && isTrunk(level.getBlockState(p))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 巨大樹の幹(原木・形成層・硬化した幹)とコルダイテスの幹(過繁茂の原木)・板根。 */
    static boolean isTrunk(BlockState s) {
        return s.is(com.yayuy.overgrownvegetation.ModTags.GIANT_WOOD)
                || s.is(ModBlocks.OVERGROWN_LOG.get()) || s.is(ModBlocks.CORDAITES_BUTTRESS.get());
    }

    /** 空気のマス {@code cell} の空の光が洞窟の口の帯({@link #MOUTH_SKY_MIN}〜{@link #MOUTH_SKY_MAX})か。 */
    static boolean mouthSky(ServerLevel level, BlockPos cell) {
        if (!level.isLoaded(cell)) {
            return false;
        }
        int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, cell);
        return sky >= MOUTH_SKY_MIN && sky <= MOUTH_SKY_MAX;
    }

    /**
     * 天井のブロック {@code ceiling} のまわりに根の簾を 1 束(5〜12 本・長さ 2〜6)垂らす。木の真下か洞窟の口でなければ、また同じセクションにもう簾があれば垂らさない。
     * 洞窟の口では、口の帯の空の光のマスだけに垂らす。
     *
     * @return 垂らした本数
     */
    public static int placeRootCurtain(ServerLevel level, BlockPos ceiling, RandomSource random) {
        if (sectionsMayHave(level, ceiling.below(), 0, s -> s.is(ModBlocks.DANGLING_ROOTS.get()))) {
            return 0;
        }
        return placeRootCurtain(level, ceiling, curtainSite(level, ceiling), random);
    }

    /**
     * 洞窟の口の入口(11-04 ユーザー指定「口の帯は天井の厚さ(isCave)に関係なく簾の対象」)。洞窟と判定されなかった汚染ブロック
     * ({@link CorruptionSpreader#growSurfacePlants} の洞窟でない分岐)から呼ばれる。真下が空気で、その空の光が口の帯なら、口の簾を 1 束垂らす。
     * 木の一部({@link com.yayuy.overgrownvegetation.ModTags#NOT_CAVE_ROOF})は天井にしない。ほかの地下の植生は置かない。
     *
     * @return 垂らした本数
     */
    public static int decorateMouth(ServerLevel level, BlockPos host, BlockState hostState, RandomSource random) {
        if (random.nextDouble() >= CURTAIN_CHANCE || hostState.is(com.yayuy.overgrownvegetation.ModTags.NOT_CAVE_ROOF)
                || !isAir(level, host.below()) || !hostState.isFaceSturdy(level, host, Direction.DOWN) || !mouthSky(level, host.below())
                || sectionsMayHave(level, host.below(), 0, s -> s.is(ModBlocks.DANGLING_ROOTS.get()))) {
            return 0;
        }
        return placeRootCurtain(level, host, CurtainSite.MOUTH, random);
    }

    // ------------------------------------------------------------------
    // 境目の帯 2(2026-11-08): 浅い洞窟の天井から地上の木の根
    // ------------------------------------------------------------------

    /**
     * 森の下の浅い洞窟の天井から、地上の木の根を垂らす(2026-11-08 境目の帯 2。頭の上に森があることを洞窟の中から感じさせる)。
     * 汚染したブロック 1 つにつき {@link CorruptionSpreader#growSurfacePlants} から 1 回呼ばれる(洞窟と判定されない薄い天井でも。染み下りの植物もこの入口を通るので、
     * 染み下りの時間の上限の中で動く)。確率 {@code forestRootsChance} に当たったときだけ天井を調べる。条件は {@link #forestAbove}。
     * 近く({@code forestRootsSpacing})にもう根があれば垂らさない。値は {@code spore_field.json} の {@code underground.forest*}(すべて仮決め)。
     *
     * @return 垂らした本数
     */
    public static int decorateForestRoots(ServerLevel level, BlockPos host, BlockState hostState, RandomSource random) {
        com.yayuy.overgrownvegetation.data.SporeFieldTable t = com.yayuy.overgrownvegetation.data.DesignData.sporeField();
        if (!t.forestRootsEnabled() || random.nextDouble() >= t.forestRootsChance()) {
            return 0;
        }
        if (hostState.is(com.yayuy.overgrownvegetation.ModTags.NOT_CAVE_ROOF) || !isAir(level, host.below())
                || !hostState.isFaceSturdy(level, host, Direction.DOWN) || !forestAbove(level, host)) {
            return 0;
        }
        if (rootsNear(level, host.below(), t.forestRootsSpacing())) {
            return 0;
        }
        return placeForestRoots(level, host, random);
    }

    /**
     * 天井のブロック {@code ceiling} の上が「汚染された森の浅い地面」か: 天井から上へ続く岩(木でない動きを止めるブロック)が {@code forestRoofMax} 段以内で終わり、
     * その上が液体でなく、地面のブロックが汚染済みか地面の所が過繁茂の地域で、地面から上 {@code forestTreeReach} 段・水平 ±{@code forestTreeSide} の正方形の列のどれかに
     * 木の幹(原木・過繁茂の原木・巨大樹の幹・木生シダの幹)がある。
     */
    public static boolean forestAbove(ServerLevel level, BlockPos ceiling) {
        com.yayuy.overgrownvegetation.data.SporeFieldTable t = com.yayuy.overgrownvegetation.data.DesignData.sporeField();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x = ceiling.getX();
        int z = ceiling.getZ();
        int y = ceiling.getY();
        int thick = 0;
        while (true) {
            p.set(x, y, z);
            if (!level.isLoaded(p)) {
                return false;
            }
            BlockState s = level.getBlockState(p);
            if (!isRoofRock(s)) {
                if (!s.getFluidState().isEmpty()) {
                    return false; // 上が水(湖・川の底)
                }
                break;
            }
            thick++;
            y++;
            if (thick > t.forestRoofMax()) {
                return false;
            }
        }
        BlockPos ground = new BlockPos(x, y - 1, z);
        BlockState groundState = level.getBlockState(ground);
        if (!OvergrownLayerData.isCorruptedCell(level, ground, groundState)
                && !com.yayuy.overgrownvegetation.registry.ModBiomes.isOvergrownLike(level.getBiome(p.set(x, y, z)))) {
            return false;
        }
        // 幹は 1 ブロックの太さが多いので、十字(巨大樹の underTree)でなく ±side の正方形の列を全部見る(確率に当たった時だけなので重さは無視できる)
        int side = t.forestTreeSide();
        for (int dy = -1; dy < t.forestTreeReach(); dy++) {
            for (int dz = -side; dz <= side; dz++) {
                for (int dx = -side; dx <= side; dx++) {
                    p.set(x + dx, y + dy, z + dz);
                    if (level.isLoaded(p) && isForestTrunk(level.getBlockState(p))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 天井の岩: 空気・液体・木の一部・天井にならないもの({@code #not_cave_roof})でない、動きを止めるブロック。 */
    private static boolean isRoofRock(BlockState s) {
        return !s.isAir() && s.blocksMotion() && s.getFluidState().isEmpty() && !s.is(com.yayuy.overgrownvegetation.ModTags.NOT_CAVE_ROOF)
                && !com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(s);
    }

    /** 地上の木の幹(葉は数えない)。 */
    static boolean isForestTrunk(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.LOGS) || isTrunk(s) || s.getBlock() instanceof com.yayuy.overgrownvegetation.block.TreeFernTrunkBlock;
    }

    /** 天井の真下の段 {@code cell} の高さで、水平 {@code radius} 以内に垂れた根(この MOD の垂れ根・バニラの垂れ根)があるか。 */
    private static boolean rootsNear(ServerLevel level, BlockPos cell, int radius) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                p.set(cell.getX() + dx, cell.getY(), cell.getZ() + dz);
                if (!level.isLoaded(p)) {
                    continue;
                }
                BlockState s = level.getBlockState(p);
                if (s.is(ModBlocks.DANGLING_ROOTS.get()) || s.is(ModBlocks.DANGLING_ROOTS_PLANT.get()) || s.is(net.minecraft.world.level.block.Blocks.HANGING_ROOTS)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 天井 {@code ceiling} のまわり(半径 {@code forestRootsRadius})に地上の木の根を 1 束垂らす。{@code forestRootsVanillaShare} の割合はバニラの垂れ根 1 段、
     * 残りはこの MOD の垂れ根を {@code forestRootLenMin}〜{@code forestRootLenMax} 段。条件は見ない(呼び手が {@link #forestAbove} で確かめる。テストからも呼ぶ)。
     *
     * @return 垂らした本数
     */
    public static int placeForestRoots(ServerLevel level, BlockPos ceiling, RandomSource random) {
        com.yayuy.overgrownvegetation.data.SporeFieldTable t = com.yayuy.overgrownvegetation.data.DesignData.sporeField();
        int radius = t.forestRootsRadius();
        int strands = t.forestStrandsMin() + random.nextInt(t.forestStrandsMax() - t.forestStrandsMin() + 1);
        int placed = 0;
        for (int tries = 0; tries < strands * 4 && placed < strands; tries++) {
            int dx = radius == 0 ? 0 : random.nextInt(radius * 2 + 1) - radius;
            int dz = radius == 0 ? 0 : random.nextInt(radius * 2 + 1) - radius;
            BlockPos cell = ceilingCell(level, ceiling.getX() + dx, ceiling.getY() - 1, ceiling.getZ() + dz, 1);
            if (cell == null || !level.getBlockState(cell).isAir()) {
                continue;
            }
            if (random.nextDouble() < t.forestRootsVanillaShare()) {
                BlockState hanging = net.minecraft.world.level.block.Blocks.HANGING_ROOTS.defaultBlockState();
                if (hanging.canSurvive(level, cell)) {
                    level.setBlock(cell, hanging, Block.UPDATE_ALL);
                    placed++;
                }
            } else if (hangRoots(level, cell, t.forestRootLenMin() + random.nextInt(t.forestRootLenMax() - t.forestRootLenMin() + 1))) {
                placed++;
            }
        }
        return placed;
    }

    private static int placeRootCurtain(ServerLevel level, BlockPos ceiling, CurtainSite site, RandomSource random) {
        if (site == CurtainSite.NONE) {
            return 0;
        }
        int radius = site == CurtainSite.MOUTH ? CURTAIN_RADIUS_MOUTH : CURTAIN_RADIUS_TREE;
        int strands = CURTAIN_STRANDS_MIN + random.nextInt(CURTAIN_STRANDS_MAX - CURTAIN_STRANDS_MIN + 1);
        int placed = 0;
        for (int tries = 0; tries < strands * 4 && placed < strands; tries++) {
            int dx = random.nextInt(radius * 2 + 1) - radius;
            int dz = random.nextInt(radius * 2 + 1) - radius;
            BlockPos cell = ceilingCell(level, ceiling.getX() + dx, ceiling.getY() - 1, ceiling.getZ() + dz, 2);
            if (cell == null || site == CurtainSite.MOUTH && !mouthSky(level, cell)) {
                continue;
            }
            if (hangRoots(level, cell, CURTAIN_LEN_MIN + random.nextInt(CURTAIN_LEN_MAX - CURTAIN_LEN_MIN + 1))) {
                placed++;
            }
        }
        return placed;
    }

    /** 天井の真下の空気のマス {@code top} から {@code length} 段(先を含む)根を垂らす。先の下に 1 段の空気を残し、足りなければ短くする。 */
    public static boolean hangRoots(ServerLevel level, BlockPos top, int length) {
        int n = 0;
        while (n < length && isAir(level, top.below(n)) && isAir(level, top.below(n + 1))) {
            n++;
        }
        if (n == 0) {
            return false;
        }
        for (int i = 0; i < n - 1; i++) {
            level.setBlock(top.below(i), ModBlocks.DANGLING_ROOTS_PLANT.get().defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(top.below(n - 1), ModBlocks.DANGLING_ROOTS.get().defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }

    // ------------------------------------------------------------------
    // U6 胞子の溜まり
    // ------------------------------------------------------------------

    /**
     * 床のブロック {@code floor} の上が窪み({@link #isPit})の床なら、そこに溜まりの印を 1 つ置く。同じセクションにもう印があれば置かない。
     *
     * @return 置いたら true
     */
    public static boolean placeSporePool(ServerLevel level, BlockPos floor) {
        BlockPos cell = floor.above();
        if (!level.isLoaded(cell) || !level.getBlockState(cell).isAir()
                || sectionsMayHave(level, cell, 0, s -> s.is(ModBlocks.CAVE_SPORE_POOL.get()))) {
            return false;
        }
        BlockState marker = ModBlocks.CAVE_SPORE_POOL.get().defaultBlockState();
        if (!marker.canSurvive(level, cell) || !isPit(level, cell)) {
            return false;
        }
        level.setBlock(cell, marker, Block.UPDATE_ALL);
        return true;
    }

    /**
     * 床の上のマス {@code cell} が窪みの中か: 同じ高さの床を 8 方向へ {@link #POOL_REACH} 歩までたどり、{@link #POOL_RIM_DIRS} 方向以上で
     * 2 段以上高い床(縁)に当たり、東西・南北の幅がどちらも {@link #POOL_MIN_WIDTH} 以上。下へ下がる所・水・遠すぎる所は縁でない。
     */
    public static boolean isPit(ServerLevel level, BlockPos cell) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        int rims = 0;
        int[] walked = new int[4];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < dirs.length; i++) {
            int steps = 0;
            boolean rim = false;
            for (int k = 1; k <= POOL_REACH; k++) {
                p.set(cell.getX() + dirs[i][0] * k, cell.getY(), cell.getZ() + dirs[i][1] * k);
                if (!level.isLoaded(p)) {
                    return false;
                }
                if (pitFloor(level, p)) {
                    steps = k;
                    continue;
                }
                rim = isRim(level, p);
                break;
            }
            if (rim) {
                rims++;
            }
            if (i < 4) {
                walked[i] = steps;
            }
        }
        return rims >= POOL_RIM_DIRS && walked[0] + walked[1] + 1 >= POOL_MIN_WIDTH && walked[2] + walked[3] + 1 >= POOL_MIN_WIDTH;
    }

    /** {@code p} が窪みの床の上のマス(空き・水なし・下が上向きに丈夫)。 */
    private static boolean pitFloor(ServerLevel level, BlockPos p) {
        if (!open(level, p) || !level.getFluidState(p).isEmpty()) {
            return false;
        }
        BlockPos below = p.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /**
     * {@code pos}(頭か足のマス)が溜まりの中か: 水平 ±{@link #POOL_REACH}・{@code pos} の 0〜2 段下に印があり、{@code pos} の列の印の高さが窪みの床。
     * 胞子の濃度({@code SporeConcentration})がプレイヤーごとに読む。
     */
    public static boolean inSporePool(ServerLevel level, BlockPos pos) {
        Predicate<BlockState> isPool = s -> s.is(ModBlocks.CAVE_SPORE_POOL.get());
        boolean maybe = false;
        for (int dz = -1; dz <= 1 && !maybe; dz += 2) {
            for (int dx = -1; dx <= 1 && !maybe; dx += 2) {
                maybe = sectionsMayHave(level, pos.offset(dx * POOL_REACH, -1, dz * POOL_REACH), 1, isPool);
            }
        }
        if (!maybe) {
            return false;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 2; dy++) {
            for (int dz = -POOL_REACH; dz <= POOL_REACH; dz++) {
                for (int dx = -POOL_REACH; dx <= POOL_REACH; dx++) {
                    p.set(pos.getX() + dx, pos.getY() - dy, pos.getZ() + dz);
                    if (level.isLoaded(p) && level.getBlockState(p).is(ModBlocks.CAVE_SPORE_POOL.get())) {
                        return pitFloor(level, p.set(pos.getX(), pos.getY() - dy, pos.getZ()));
                    }
                }
            }
        }
        return false;
    }

    /** 窪みの縁: {@code p} とその 1 つ上が塞がり、2 つ上か 3 つ上が空いている(= 床が 2〜3 段高い)。 */
    private static boolean isRim(ServerLevel level, BlockPos p) {
        if (open(level, p) || open(level, p.above())) {
            return false;
        }
        return open(level, p.above(2)) || open(level, p.above(3));
    }

    // ------------------------------------------------------------------
    // U7 巨大菌の柱(プロトタキシーテス)
    // ------------------------------------------------------------------

    /** 床のブロック {@code floor} の上に続く空気の段数(天井まで。最大 64)。 */
    public static int caveHeightAbove(ServerLevel level, BlockPos floor) {
        BlockPos.MutableBlockPos p = floor.mutable();
        int n = 0;
        while (n < 64) {
            p.move(Direction.UP);
            if (!isAir(level, p)) {
                break;
            }
            n++;
        }
        return n;
    }

    /**
     * 床のブロック {@code floor} の上に巨大菌の柱を 1 本立てる。床から天井まで {@link #PILLAR_MIN_SPAN} 以上の空洞だけ。チャンクの 3 セクションにもう柱があれば立てない。
     * 高さ 8〜15(天井の 2 段下まで)、太さ 1×1 か 2×2(2×2 が 7 割)。天辺に丸い瘤か平たい傘(3 割。11-04 ユーザー決定)を載せる。
     * 2×2 は列ごとに高さを 0〜3 段ばらし、列ごとに 1×1 の天辺を載せる(11-04 ユーザー指定)。
     *
     * @return 立てたら true
     */
    public static boolean placePrototaxites(ServerLevel level, BlockPos floor, RandomSource random) {
        return placePrototaxites(level, floor, random, -1, -1);
    }

    /** 太さ・天辺を決めて立てる(撮影・テスト用): wide / cap = 1 そうする・0 しない・-1 抽選(傘は余りが無ければ瘤)。 */
    public static boolean placePrototaxites(ServerLevel level, BlockPos floor, RandomSource random, int wideMode, int capMode) {
        BlockPos base = floor.above();
        if (!isAir(level, base) || sectionsMayHave(level, base, 1, s -> s.is(ModBlocks.PROTOTAXITES_STEM.get()))) {
            return false;
        }
        int span = caveHeightAbove(level, floor);
        if (span < PILLAR_MIN_SPAN) {
            return false;
        }
        int height = Math.min(PILLAR_HEIGHT_MIN + random.nextInt(PILLAR_HEIGHT_MAX - PILLAR_HEIGHT_MIN + 1), span - 2);
        if (height < PILLAR_HEIGHT_MIN) {
            return false;
        }
        List<BlockPos> cols = new ArrayList<>();
        cols.add(base);
        if (wideMode < 0 ? random.nextDouble() < PILLAR_WIDE_SHARE : wideMode == 1) {
            cols.add(base.east());
            cols.add(base.south());
            cols.add(base.south().east());
        }
        for (BlockPos col : cols) {
            BlockPos under = col.below();
            if (!level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)) {
                return false;
            }
            for (int i = 0; i <= height; i++) { // 天辺の分まで
                if (!isAir(level, col.above(i))) {
                    return false;
                }
            }
        }
        boolean wide = cols.size() == 4;
        // 列ごとの高さ: 1×1 は height。2×2 は height から 0〜3 段下げてばらす(11-04 ユーザー指定。少なくとも 1 列は height、隣り合う列は 1〜3 段違う)
        int[] heights = wide ? unevenHeights(height, random) : new int[]{height};
        BlockState stem = ModBlocks.PROTOTAXITES_STEM.get().defaultBlockState();
        for (int c = 0; c < cols.size(); c++) {
            for (int i = 0; i < heights[c]; i++) {
                level.setBlock(cols.get(c).above(i), stem, Block.UPDATE_ALL);
            }
        }
        // 天辺は列ごとに 1×1 の瘤か傘。傘はその列の上に 2 段の空きがあり、はみ出す 3×3 が空いていて、同じ柱のほかの列が自分より低いときだけ
        for (int c = 0; c < cols.size(); c++) {
            boolean cap = (capMode < 0 ? random.nextDouble() < PILLAR_CAP_SHARE : capMode == 1) && span - heights[c] >= 3
                    && highestOrOnly(heights, c) && capRoom(level, cols.get(c).above(heights[c]));
            Block topBlock = cap ? ModBlocks.PROTOTAXITES_CAP.get() : ModBlocks.PROTOTAXITES_KNOB.get();
            level.setBlock(cols.get(c).above(heights[c]), topBlock.defaultBlockState(), Block.UPDATE_ALL);
        }
        return true;
    }

    /** 2×2 の 4 列(北西・北東・南西・南東)の高さ。少なくとも 1 列は {@code height}、ほかは 0〜3 段低く、辺で隣り合う列はかならず 1〜3 段違う。 */
    static int[] unevenHeights(int height, RandomSource random) {
        int[][] edges = {{0, 1}, {0, 2}, {1, 3}, {2, 3}};
        for (int tries = 0; tries < 64; tries++) {
            int[] off = new int[4];
            int min = Integer.MAX_VALUE;
            for (int c = 0; c < 4; c++) {
                off[c] = random.nextInt(4);
                min = Math.min(min, off[c]);
            }
            boolean ok = true;
            for (int[] e : edges) {
                ok &= off[e[0]] != off[e[1]];
            }
            if (ok) {
                int[] h = new int[4];
                for (int c = 0; c < 4; c++) {
                    h[c] = height - (off[c] - min); // いちばん高い列を height に
                }
                return h;
            }
        }
        return new int[]{height, height - 2, height - 1, height - 3};
    }

    /** 同じ柱のほかの列がどれも列 {@code c} より低いか(傘が隣の列の幹・天辺に当たらない)。 */
    private static boolean highestOrOnly(int[] heights, int c) {
        for (int i = 0; i < heights.length; i++) {
            if (i != c && heights[i] >= heights[c]) {
                return false;
            }
        }
        return true;
    }

    /** 傘のはみ出す所(天辺のまわり 3×3)が空いているか。{@code top} = 天辺のマス。 */
    static boolean capRoom(ServerLevel level, BlockPos top) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!isAir(level, top.offset(dx, 0, dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 共通
    // ------------------------------------------------------------------

    /** {@code pos} のチャンクの、{@code pos} のセクションと上下 {@code span} 段のパレットに {@code test} に合う状態があるか(安い下見。古い項目で真になることがある)。 */
    static boolean sectionsMayHave(ServerLevel level, BlockPos pos, int span, Predicate<BlockState> test) {
        if (!level.isLoaded(pos)) {
            return true; // 読めない所には置かない
        }
        LevelChunk chunk = level.getChunkAt(pos);
        LevelChunkSection[] sections = chunk.getSections();
        int sy = SectionPos.blockToSectionCoord(pos.getY());
        for (int y = sy - span; y <= sy + span; y++) {
            int i = level.getSectionIndexFromSectionY(y);
            if (i < 0 || i >= sections.length) {
                continue;
            }
            LevelChunkSection s = sections[i];
            if (s != null && !s.hasOnlyAir() && s.maybeHas(test)) {
                return true;
            }
        }
        return false;
    }

    /** 列 (x, z) で {@code y0} ± {@code reach} にある「岩の天井の真下の空気」のマス(y0 に近い順)。無ければ null。 */
    static BlockPos ceilingCell(ServerLevel level, int x, int y0, int z, int reach) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k <= reach * 2; k++) {
            int y = y0 + ((k & 1) == 0 ? k / 2 : -(k + 1) / 2);
            p.set(x, y, z);
            if (!level.isLoaded(p)) {
                return null;
            }
            if (isAir(level, p) && level.getBlockState(p.above()).isFaceSturdy(level, p.above(), Direction.DOWN)) {
                return p.immutable();
            }
        }
        return null;
    }

    /** 列 (x, z) で {@code floorY} ± {@code reach} にある「上が丈夫な床の真上の空気」のマス。無ければ null。 */
    static BlockPos floorCell(ServerLevel level, int x, int floorY, int z, int reach) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k <= reach * 2; k++) {
            int y = floorY + 1 + ((k & 1) == 0 ? k / 2 : -(k + 1) / 2);
            p.set(x, y, z);
            if (!level.isLoaded(p)) {
                return null;
            }
            if (isAir(level, p) && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) {
                return p.immutable();
            }
        }
        return null;
    }

    /** 空気か(菌糸は空気と見る = ほかの植生と天井の高さの数えでは菌糸を読み飛ばし、上書きしてよい)。 */
    private static boolean isAir(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState s = level.getBlockState(pos);
        return s.isAir() || s.is(ModBlocks.CAVE_MYCELIUM.get());
    }

    private static int sq(int v) {
        return v * v;
    }
}
