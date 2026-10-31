package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * ブロック汚染の「芽」と萌芽体の出し入れ(2026-10-23 胞子の場と風 第 2 段 E・F)。仕様は Vault「胞子濃度と暴露量(作り直し)」の
 * 【決定】ブロック汚染 3 層(粉の見た目 → 芽 → 萌芽体)。
 *
 * <ul>
 *     <li><b>芽</b>: 発芽したチャンクが読み込まれたら({@code LedgerSpreadCatchUp} がバイオームを過繁茂にした後)、地表と洞窟の床の
 *     ランダムな点(湿った土地は多め)から {@link CorruptionSpreader} を半径 {@code sprouts.radius} で走らせる。
 *     チャンクを四角く塗らず、点から根のように広がる。TickBudget で絞る(嵐で多数のチャンクが同時に発芽すると少し遅れて追いつく)</li>
 *     <li><b>萌芽体</b>: 場の「萌芽体の数」(1 チャンク 1〜2)だけ出す。読み込まれている間に発芽した所は歩く萌芽体
 *     (湿った所で根付く)、読み込まれていない間に発芽した所は読み込んだときに根付いた姿({@code blight_fern_rooted})で置く。
 *     出した数はチャンクの {@link ModAttachments#SPROUTS_PLACED} に覚える</li>
 * </ul>
 * 芽の進み(走っている点)は保存しない(閉じると止まる。バイオームはもう過繁茂なので見た目が途中で止まるだけ)。
 *
 * <p>2026-10-23 F からネザーでも同じ(相談セッションの答え: 芽の点と萌芽体を出す・前の床の這い出しは無し)。ネザーの点は
 * 空洞の床({@code NetherSurface})から選び、溶岩の上には置かない。走っている点・見直し待ちは次元ごとに持つ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeSprouts {

    private static final class Point {
        final BlockPos origin;
        final CorruptionSpreader spreader = new CorruptionSpreader();
        long next;
        /** 根付いた萌芽体の点(旧 汚染シダと同じ広がり方で、半径 corruptionRadius まで広げ続ける。10-23 A1)。 */
        boolean rooted;

        Point(BlockPos origin, long next) {
            this.origin = origin;
            this.next = next;
        }
    }

    /** 次元ごとの状態(オーバーワールドとネザー)。 */
    private static final class Dim {
        final List<Point> active = new ArrayList<>();
        /** 読み込まれている間に発芽したチャンク(書き換えのときに歩く萌芽体を出す)。 */
        final LongOpenHashSet fresh = new LongOpenHashSet();
        /** 読み込まれた、萌芽体の数を見直すチャンク。 */
        final LongLinkedOpenHashSet check = new LongLinkedOpenHashSet();
        /** 読み込んだチャンクにあった、広げ終えていない株(次の tick で点にする。読み込みの最中にブロックを書かない)。 */
        final LongOpenHashSet pendingRooted = new LongOpenHashSet();
    }

    private static final java.util.Map<net.minecraft.resources.ResourceKey<Level>, Dim> DIMS = new java.util.HashMap<>();
    private static final int CHECKS_PER_TICK = 8;

    /** 胞子の場の芽・萌芽体が動く次元か(オーバーワールドとネザー)。 */
    public static boolean fieldLevel(Level level) {
        return level.dimension() == Level.OVERWORLD
                || level.dimension() == Level.NETHER && NetherSporeFieldServer.running();
    }

    private static Dim dim(Level level) {
        return DIMS.computeIfAbsent(level.dimension(), k -> new Dim());
    }

    private SporeSprouts() {
    }

    /** 読み込まれている間に発芽した(オーバーワールド。LedgerSpread#acceptFieldSprouts から)。 */
    public static void markFresh(long chunkKey) {
        DIMS.computeIfAbsent(Level.OVERWORLD, k -> new Dim()).fresh.add(chunkKey);
    }

    /** 読み込まれている間に発芽した(次元を選ぶ版。ネザーの記録から)。 */
    public static void markFresh(ServerLevel level, long chunkKey) {
        dim(level).fresh.add(chunkKey);
    }

    /** 過繁茂にしたばかりのチャンクに芽を置き、萌芽体を出す(サーバースレッド、チャンクと周りが読み込まれていること)。 */
    public static void start(ServerLevel level, ChunkPos pos) {
        if (CorruptionSwitch.isOff()) return;
        SporeFieldTable t = DesignData.sporeField();
        RandomSource random = level.random;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) return;
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome = level.getBiome(pos.getMiddleBlockPosition(level.getSeaLevel()));
        boolean wet = level.dimension() == Level.OVERWORLD && (biome.is(net.minecraft.tags.BiomeTags.IS_RIVER)
                || biome.is(net.minecraft.tags.BiomeTags.IS_OCEAN) || biome.is(net.minecraft.tags.BiomeTags.IS_DEEP_OCEAN)) // 海も(2026-10-24 追記 18)
                || biome.is(net.neoforged.neoforge.common.Tags.Biomes.IS_SWAMP);
        int n = (int) Math.round(t.sproutPoints() * (wet ? t.sproutWetMultiplier() : 1.0D));
        long now = level.getGameTime();
        List<BlockPos> surface = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos p = level.dimension() == Level.OVERWORLD && random.nextDouble() < t.sproutCaveShare() ? caveFloor(level, chunk, random) : null;
            if (p == null) {
                p = surfacePoint(level, chunk, random);
                if (p != null) surface.add(p);
            }
            if (p != null) addPoint(level, p, now + random.nextInt(t.sproutTickInterval() * 4));
        }
        // 萌芽体
        boolean walkers = dim(level).fresh.remove(pos.toLong());
        int want = sproutCount(level, pos);
        int placed = chunk.getData(ModAttachments.SPROUTS_PLACED);
        for (int k = placed; k < want && !surface.isEmpty(); k++) {
            BlockPos at = surface.remove(random.nextInt(surface.size()));
            if (walkers) BlightFernPlacement.spawn(level, at, MobSpawnType.EVENT);
            else placeRooted(level, at);
        }
        if (want > placed) chunk.setData(ModAttachments.SPROUTS_PLACED, want);
    }

    /** 2 体目の萌芽体が発芽した(場の答えから)。読み込まれていれば歩く萌芽体を出す。そうでなければ読み込んだときに根付いた姿で。 */
    static void onSecondSprout(ServerLevel level, long chunkKey) {
        int cx = ChunkPos.getX(chunkKey), cz = ChunkPos.getZ(chunkKey);
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return;
        BlockPos at = surfacePoint(level, chunk, level.random);
        if (at == null) return;
        if (BlightFernPlacement.spawn(level, at, MobSpawnType.EVENT) != null) {
            chunk.setData(ModAttachments.SPROUTS_PLACED, chunk.getData(ModAttachments.SPROUTS_PLACED) + 1);
        }
    }

    /** 汚染の根を伸ばす点を足す(根付いた萌芽体のまわりなど)。 */
    public static void addPoint(ServerLevel level, BlockPos origin) {
        addPoint(level, origin, level.getGameTime());
    }

    /**
     * 根付いた萌芽体の点を足す(2026-10-23 A1: 前は芽と同じ半径 4 で 1 回だけ広げて止まっていた)。旧 汚染シダと同じ設定
     * (config の radius・触手)で広げ続け、広げ終えたら株に印を付ける(チャンクを読み直しても広げ直さない)。
     * 走っている点の上限には数えない(株は 1 チャンク 1〜2)。
     */
    public static void addRootedPoint(ServerLevel level, BlockPos origin) {
        if (!fieldLevel(level)) return;
        List<Point> active = dim(level).active;
        for (Point q : active) if (q.rooted && q.origin.equals(origin)) return;
        Point p = new Point(origin.immutable(), level.getGameTime());
        p.rooted = true;
        p.spreader.seedGround(level, p.origin);
        active.add(p);
    }

    private static void addPoint(ServerLevel level, BlockPos origin, long next) {
        if (!fieldLevel(level)) return;
        List<Point> active = dim(level).active;
        if (active.size() >= DesignData.sporeField().sproutMaxActive()) return;
        Point p = new Point(origin.immutable(), next);
        p.spreader.seedGround(level, p.origin);
        active.add(p);
    }

    /** 萌芽体が根付く: 足元に根付いた姿を置く(置けたら true)。 */
    public static boolean placeRootedAt(ServerLevel level, BlockPos at) {
        return placeRooted(level, at);
    }

    /**
     * 萌芽体が root アニメを流し終えて、決めておいた場所 {@code spot}({@link #rootedSpot})に根付いた姿を置く。
     * 歩く姿と同じ向き {@code yaw}(体の向き)で描かれ、{@code fresh} なら巻きが開くアニメから始まる(2026-10-23 萌芽体のモデル)。
     */
    public static boolean placeRootedAt(ServerLevel level, BlockPos spot, float yaw, boolean fresh) {
        BlockState state = ((com.yayuy.overgrownvegetation.block.BlightFernRootedBlock) ModBlocks.BLIGHT_FERN_ROOTED.get()).stateFor(level, spot); // 水源の中なら水の中の姿(2026-10-24)
        if (!level.getBlockState(spot).canBeReplaced() || !state.canSurvive(level, spot)) return false;
        level.setBlock(spot, state, Block.UPDATE_ALL);
        if (level.getBlockEntity(spot) instanceof com.yayuy.overgrownvegetation.block.BlightFernRootedBlockEntity be) {
            be.plant(yaw, fresh);
        }
        return true;
    }

    /** 根付いた姿を置ける所(足元が塞がっていれば上へ 2 まで)。無ければ null。 */
    public static BlockPos rootedSpot(ServerLevel level, BlockPos at) {
        BlockState state = ModBlocks.BLIGHT_FERN_ROOTED.get().defaultBlockState();
        for (int dy = 0; dy < 3; dy++) {
            BlockPos p = at.above(dy);
            if (level.getBlockState(p).canBeReplaced() && state.canSurvive(level, p)) {
                return p;
            }
        }
        return null;
    }

    /** 根付いた姿を置く(足元が塞がっていれば上へ)。 */
    static boolean placeRooted(ServerLevel level, BlockPos at) {
        BlockPos p = rootedSpot(level, at);
        if (p == null) return false;
        level.setBlock(p, ((com.yayuy.overgrownvegetation.block.BlightFernRootedBlock) ModBlocks.BLIGHT_FERN_ROOTED.get()).stateFor(level, p), Block.UPDATE_ALL);
        return true;
    }

    private static int sproutCount(ServerLevel level, ChunkPos pos) {
        SporeFieldServer.Cell cell = SporeFieldServer.cell(level, pos.x, pos.z);
        return cell != null ? Math.max(1, Math.min(2, cell.count())) : 1;
    }

    /**
     * 地表のランダムな 1 点(地面の 1 つ上)。水の下なら水底の床の 1 つ上(水の中。2026-10-24 追記 18: 胞子は水にも沈んで芽吹く)。
     * 溶岩・空は除く。
     */
    private static BlockPos surfacePoint(ServerLevel level, LevelChunk chunk, RandomSource random) {
        if (level.dimension() == Level.NETHER) return netherFloorPoint(level, chunk, random);
        for (int tries = 0; tries < 6; tries++) {
            int lx = random.nextInt(16), lz = random.nextInt(16);
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz) + 1; // チャンクの getHeight はいちばん上のブロック(-1 済み)。その 1 つ上
            BlockPos p = belowCanopy(level, new BlockPos(chunk.getPos().getMinBlockX() + lx, y, chunk.getPos().getMinBlockZ() + lz));
            if (p == null) continue;
            BlockState below = level.getBlockState(p.below());
            if (below.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                // 水面だった: 水底の床へ(OCEAN_FLOOR = 水と海草をくぐった固い面。チャンクの getHeight は -1 済み)。
                // 林冠の下の水(沼)では OCEAN_FLOOR も葉の上を答えるので、そのときは水を下へたどる
                int fy = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, lx, lz) + 1;
                if (fy > p.getY()) fy = waterFloor(level, p);
                if (fy == Integer.MIN_VALUE) continue;
                p = new BlockPos(p.getX(), fy, p.getZ());
                below = level.getBlockState(p.below());
                if (!level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) continue;
            }
            if (below.getFluidState().isEmpty() && !below.isAir()) return p;
        }
        return null;
    }

    /** 林冠の下へ降りる上限(段)。 */
    static final int CANOPY_DESCENT = 32;

    /** 葉か(バニラの {@code #minecraft:leaves} と mod の葉 {@link com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock}。mod の葉は InertBlock なので高さ地図の NO_LEAVES に固い面と数えられる)。 */
    static boolean isLeaves(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.LEAVES) || state.getBlock() instanceof com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock;
    }

    /**
     * 地面の 1 つ上の候補 {@code p} が葉の上なら、葉・空気・通れる草(当たり判定が無く水の無いもの)の間を下へ降ろす(上限 {@link #CANOPY_DESCENT} 段。2026-10-31 不具合: 萌芽体が林冠の上に湧いた)。
     * 足元が葉でなければ {@code p} のまま。降りた先の足元が幹・枝(葉でも通れる草でもない固い面のうち {@code #logs})か、上限まで降りきれなければ null。
     * 降りた先の足元が水なら呼び手の水底の分岐へ。
     */
    @org.jetbrains.annotations.Nullable
    public static BlockPos belowCanopy(net.minecraft.world.level.BlockGetter level, BlockPos p) {
        if (!isLeaves(level.getBlockState(p.below()))) return p;
        BlockPos.MutableBlockPos m = p.mutable();
        for (int d = 0; d < CANOPY_DESCENT; d++) {
            BlockPos under = m.below();
            BlockState below = level.getBlockState(under);
            boolean through = isLeaves(below) || below.getFluidState().isEmpty() && below.getCollisionShape(level, under).isEmpty();
            if (!through) {
                return below.is(net.minecraft.tags.BlockTags.LOGS) ? null : m.immutable();
            }
            m.move(Direction.DOWN);
            if (m.getY() <= level.getMinBuildHeight()) return null;
        }
        return null;
    }

    /** 水面 {@code p} から水を下へたどった床の 1 つ上(水の中)。上限 64 段。見つからなければ {@link Integer#MIN_VALUE}。 */
    private static int waterFloor(ServerLevel level, BlockPos p) {
        BlockPos.MutableBlockPos m = p.mutable().move(Direction.DOWN);
        for (int d = 0; d < 64 && m.getY() > level.getMinBuildHeight(); d++) {
            if (!level.getFluidState(m.below()).is(net.minecraft.tags.FluidTags.WATER)) return m.getY();
            m.move(Direction.DOWN);
        }
        return Integer.MIN_VALUE;
    }

    /** ネザーの空洞の床のランダムな 1 点(床の 1 つ上、上 2 マスが空いている。溶岩の上・溶岩の面は選ばない)。 */
    private static BlockPos netherFloorPoint(ServerLevel level, LevelChunk chunk, RandomSource random) {
        int[] floors = new int[com.yayuy.overgrownvegetation.corruption.NetherSurface.MAX_FLOORS];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int tries = 0; tries < 8; tries++) {
            int x = chunk.getPos().getMinBlockX() + random.nextInt(16), z = chunk.getPos().getMinBlockZ() + random.nextInt(16);
            int n = com.yayuy.overgrownvegetation.corruption.NetherSurface.floors(level, chunk, x, z, floors);
            if (n == 0) continue;
            int y = floors[random.nextInt(n)];
            if (!level.getBlockState(m.set(x, y, z)).getFluidState().isEmpty()) continue;
            if (!level.getBlockState(m.set(x, y + 1, z)).getFluidState().isEmpty()) continue;
            if (!com.yayuy.overgrownvegetation.corruption.NetherSurface.isOpen(level.getBlockState(m.set(x, y + 2, z)))) continue;
            return new BlockPos(x, y + 1, z);
        }
        return null;
    }

    /** 洞窟の床のランダムな 1 点(空の光 0・足元が塞がり上が空気)。見つからなければ null。 */
    private static BlockPos caveFloor(ServerLevel level, LevelChunk chunk, RandomSource random) {
        int minY = level.getMinBuildHeight() + 8;
        for (int tries = 0; tries < 4; tries++) {
            int lx = random.nextInt(16), lz = random.nextInt(16);
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz) - 8;
            if (top <= minY) continue;
            int y = minY + random.nextInt(top - minY);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(chunk.getPos().getMinBlockX() + lx, y, chunk.getPos().getMinBlockZ() + lz);
            // 下へたどって、空気の下の床を探す
            for (int d = 0; d < 24 && m.getY() > minY; d++, m.move(Direction.DOWN)) {
                BlockState here = level.getBlockState(m);
                BlockState below = level.getBlockState(m.below());
                if (here.isAir() && !below.isAir() && below.getFluidState().isEmpty() && !isLeaves(below) // 濃い林冠の中の空気の泡(空の光 0)も洞窟に見える
                        && level.getBrightness(LightLayer.SKY, m) == 0) {
                    return m.immutable();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 回す

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !fieldLevel(level)) return;
        Dim d = dim(level);
        tickChecks(level, d);
        if (!d.pendingRooted.isEmpty()) {
            for (long k : d.pendingRooted.toLongArray()) {
                BlockPos bp = BlockPos.of(k);
                if (level.isLoaded(bp) && level.getBlockState(bp).is(ModBlocks.BLIGHT_FERN_ROOTED.get())) addRootedPoint(level, bp);
            }
            d.pendingRooted.clear();
        }
        if (d.active.isEmpty() || CorruptionSwitch.isOff()) return;
        if (!TickBudget.mayStart(PerfJob.SPORE_SPROUTS)) return;
        SporeFieldTable t = DesignData.sporeField();
        long now = level.getGameTime();
        long start = TickBudget.begin();
        int done = 0;
        try {
            for (Iterator<Point> it = d.active.iterator(); it.hasNext(); ) {
                Point p = it.next();
                if (p.next > now) continue;
                if (done > 0 && !TickBudget.timeLeft()) break;
                if (!level.isLoaded(p.origin)) {
                    it.remove();
                    continue;
                }
                int before = p.spreader.getCorruptedCount();
                if (p.rooted) {
                    if (!level.getBlockState(p.origin).is(ModBlocks.BLIGHT_FERN_ROOTED.get())) {
                        it.remove(); // 株が壊された
                        continue;
                    }
                    p.spreader.tick(level, p.origin);
                } else {
                    p.spreader.tick(level, p.origin, t.sproutRadius(), 1, 0, 2, 4);
                }
                com.yayuy.overgrownvegetation.phase.CorruptionCounter.record(level, p.spreader.getCorruptedCount() - before);
                p.next = now + t.sproutTickInterval();
                done++;
                if (p.spreader.isFinished()) {
                    if (p.rooted && level.getBlockEntity(p.origin) instanceof com.yayuy.overgrownvegetation.block.BlightFernRootedBlockEntity be) {
                        be.markSpreadDone();
                    }
                    it.remove();
                }
            }
        } finally {
            TickBudget.end(PerfJob.SPORE_SPROUTS, start, done);
        }
    }

    /** 読み込まれたチャンクのうち、萌芽体の数が足りない所に根付いた姿を置く(読み込まれていない間の発芽の分)。 */
    private static void tickChecks(ServerLevel level, Dim d) {
        if (d.check.isEmpty() || CorruptionSwitch.isOff()) return;
        PhaseData phase = PhaseData.get(level);
        boolean nether = level.dimension() == Level.NETHER;
        for (int i = 0; i < CHECKS_PER_TICK && !d.check.isEmpty(); i++) {
            long key = d.check.removeFirstLong();
            ChunkPos pos = new ChunkPos(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            boolean overgrown = nether ? com.yayuy.overgrownvegetation.corruption.NetherLedgerData.get(level).isOvergrown(key) : phase.isOvergrownChunk(pos);
            if (chunk == null || !overgrown) continue;
            SporeFieldServer.Cell cell = SporeFieldServer.cell(level, pos.x, pos.z);
            if (cell == null) continue;
            int placed = chunk.getData(ModAttachments.SPROUTS_PLACED);
            int want = Math.min(2, cell.count());
            for (int k = placed; k < want; k++) {
                BlockPos at = surfacePoint(level, chunk, level.random);
                if (at != null) placeRooted(level, at);
            }
            if (want > placed) chunk.setData(ModAttachments.SPROUTS_PLACED, want);
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && fieldLevel(level) && !event.isNewChunk()
                && DesignData.sporeField().enabled()) {
            dim(level).check.add(event.getChunk().getPos().toLong());
        }
        // 広げ終えていない根付いた萌芽体を走らせ直す(芽の進みは保存しないので、読み込んだときに最初から)
        if (event.getLevel() instanceof ServerLevel level && fieldLevel(level)
                && event.getChunk() instanceof LevelChunk chunk) {
            for (net.minecraft.world.level.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
                if (be instanceof com.yayuy.overgrownvegetation.block.BlightFernRootedBlockEntity r && !r.spreadDone()) {
                    dim(level).pendingRooted.add(be.getBlockPos().asLong());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DIMS.clear();
    }
}
