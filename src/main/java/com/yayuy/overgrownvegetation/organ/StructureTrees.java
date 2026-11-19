package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature;
import com.yayuy.overgrownvegetation.worldgen.fusion.FusionTable;
import com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 構造物の木と中庭の巨大樹を生やす係(2026-11-19 構造物と森の融合 段 2)。
 *
 * <p><b>きっかけ</b>: プレイヤーのまわり {@code tree.scanChunks} チャンクを {@code tree.scanTicks} ごとに見て、構造物(地表の部品を持つ物)の
 * 地表の全体の箱の真ん中が<b>過繁茂のバイオーム</b>になっていたら、1 つの構造物に 1 回だけ生やす。過繁茂のバイオームは汚染でしかできない
 * (世界生成では出ない)ので、汚染で過繁茂になった構造物も、過繁茂の中で新しく読み込んだ構造物も、同じこの入口を通る。
 * <ul>
 *   <li>全体が小・中: {@link StructureTree}(建物から生える構造物の木)。計画は保存し、1 tick に {@code tree.blocksPerTick} 個ずつ置く。</li>
 *   <li>全体が大: 中庭・広場({@link StructureTree#courtyard})に実寸の巨大樹(ふつうの巨大樹の機能。構造物の避けだけ外す)。</li>
 * </ul>
 * 置く直前にもマスを見直す(空気・置き換えられる草花・水なしのときだけ置く)。建物のブロックは置き換えない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class StructureTrees extends SavedData {
    private static final String ID = OvergrownVegetation.MODID + "_structure_trees";
    private static final Factory<StructureTrees> FACTORY = new Factory<>(StructureTrees::new, (tag, registries) -> load(tag), null);

    /** 生やし終えた(または生やせなかった)構造物。 */
    private final LongOpenHashSet done = new LongOpenHashSet();
    /** 中庭の巨大樹の根元(巨大樹の置き方が、構造物の箱の中の空気のマスにも置いてよい木を見分ける)。 */
    private final LongOpenHashSet courtyards = new LongOpenHashSet();
    /** 置いている途中の木。 */
    private final List<Job> jobs = new ArrayList<>();

    /** 置いている途中の木 1 本。 */
    static final class Job {
        final long site;
        final long[] pos;
        final byte[] mat;
        int cursor;

        Job(long site, long[] pos, byte[] mat, int cursor) {
            this.site = site;
            this.pos = pos;
            this.mat = mat;
            this.cursor = cursor;
        }
    }

    public static StructureTrees get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** 構造物の鍵(種類と全体の箱の隅)。 */
    public static long key(StructureFusion.Site s) {
        BoundingBox w = s.whole();
        return BlockPos.asLong(w.minX(), w.minY(), w.minZ()) * 31L + s.id().hashCode();
    }

    public boolean isDone(StructureFusion.Site s) {
        return done.contains(key(s));
    }

    public int pendingJobs() {
        return jobs.size();
    }

    // ---------------------------------------------------------------- きっかけ

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        FusionTable t = StructureFusion.table();
        if (!t.enabled || !t.treeEnabled) return;
        StructureTrees data = get(level);
        if (!data.jobs.isEmpty()) data.placeSome(level, t);
        if (level.getGameTime() % t.treeScanTicks != 0 || level.players().isEmpty()) return;
        data.scan(level, t);
    }

    private void scan(ServerLevel level, FusionTable t) {
        LongOpenHashSet seenChunks = new LongOpenHashSet();
        LongOpenHashSet seenSites = new LongOpenHashSet();
        int r = t.treeScanChunks;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            ChunkPos c = player.chunkPosition();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (!seenChunks.add(ChunkPos.asLong(c.x + dx, c.z + dz))) continue;
                    StructureFusion.Site[] sites = StructureFusion.sitesAt(level, c.x + dx, c.z + dz);
                    if (sites == null) continue;
                    for (StructureFusion.Site s : sites) {
                        long k = key(s);
                        if (!seenSites.add(k) || done.contains(k) || hasJob(k)) continue;
                        if (!overgrown(level, s)) continue;
                        if (start(level, s, false) != Start.WAIT) return; // 1 回の見回りで 1 本まで(重さ)
                    }
                }
            }
        }
    }

    private boolean hasJob(long k) {
        for (Job j : jobs) if (j.site == k) return true;
        return false;
    }

    /** 構造物の地表の全体の箱の真ん中が過繁茂のバイオームか。 */
    static boolean overgrown(ServerLevel level, StructureFusion.Site s) {
        BoundingBox b = s.surfaceWhole();
        int x = (b.minX() + b.maxX()) >> 1, z = (b.minZ() + b.maxZ()) >> 1;
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return false;
        return ModBiomes.isFullyOvergrown(level.getBiome(new BlockPos(x, Math.max(level.getSeaLevel(), b.minY()), z)));
    }

    public enum Start {
        /** 計画を積んだ(小・中)・巨大樹を始めた(大)。 */
        STARTED,
        /** 生やせなかった(もう試さない)。 */
        GAVE_UP,
        /** 読み込まれていない所がある(また見る)。 */
        WAIT
    }

    /** 1 つの構造物に生やし始める。{@code force} = 過繁茂でなくても・済みでも(コマンド)。 */
    public Start start(ServerLevel level, StructureFusion.Site s, boolean force) {
        long k = key(s);
        if (!force && (done.contains(k) || hasJob(k))) return Start.GAVE_UP;
        FusionTable t = StructureFusion.table();
        BoundingBox area = s.surfaceWhole().inflatedBy(t.treeCrownMax + 24);
        for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++) {
            for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return Start.WAIT;
            }
        }
        long seed = k ^ level.getSeed();
        if (s.wholeClass() == StructureFusion.SizeClass.LARGE) {
            return startLarge(level, s, k, seed, t);
        }
        StructureTree.Result r = StructureTree.plan(level, s, seed);
        if (r.plan() == null) {
            if ("wait".equals(r.why())) return Start.WAIT;
            markDone(k);
            OvergrownVegetation.LOGGER.info("StructureTrees: {} に木を生やせない({})", s.id(), r.why());
            return Start.GAVE_UP;
        }
        markDone(k);
        jobs.add(new Job(k, r.plan().pos(), r.plan().mat(), 0));
        setDirty();
        OvergrownVegetation.LOGGER.info("StructureTrees: {} に構造物の木({}・根元 {}・{} 個)", s.id(), r.plan().mode(), r.plan().anchor(), r.plan().pos().length);
        return Start.STARTED;
    }

    private void markDone(long k) {
        done.add(k);
        setDirty();
    }

    /** 中庭の巨大樹: ふつうの巨大樹の機能で(構造物の避けだけ外す。間隔・洞窟・設定「巨大樹が生えるか」は効く)。 */
    static boolean plantCourtyardGiant(ServerLevel level, BlockPos at, long seed) {
        ConfiguredFeature<?, ?> f = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(OvergrownVegetation.id("overgrown_tree_giant"));
        if (f == null) return false;
        GiantTreeFeature.beginCourtyard();
        try {
            return f.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), at);
        } finally {
            GiantTreeFeature.endCourtyard();
        }
    }

    /** 巨大樹の幹の最初のマスが、記録した中庭の根元から 24 以内か(GiantTreeGrowthQueue が仕事を作るときに 1 回)。 */
    public static boolean courtyardAt(ServerLevel level, BlockPos trunk) {
        for (long c : get(level).courtyards) {
            BlockPos p = BlockPos.of(c);
            double dx = p.getX() - trunk.getX(), dz = p.getZ() - trunk.getZ();
            if (dx * dx + dz * dz <= 40.0D * 40.0D) return true;
        }
        return false;
    }

    /** 段 2c で構造物に植えた器官(司令塔の予算を払っていない。建てないときに値段を返さない)。保存しない(建てる・やめるの判定はすぐ済む)。 */
    private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet PLANTED = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    public static boolean planted(int organId) {
        return PLANTED.contains(organId);
    }

    private static double[] centerOf(BoundingBox b) {
        return new double[]{(b.minX() + b.maxX() + 1) / 2.0D, (b.minZ() + b.maxZ() + 1) / 2.0D};
    }

    /**
     * 段 2c(11-19 ユーザー「構造物は巨大樹ではなく、中くらいの木によって根に覆われる感じに。器官と融合するのがメイン」。段 2b の A+B の巨大樹をやめた):
     * 一番大きい建物(細長い部品を除く体積最大)に大きい器官、ほかの建物・城壁など細長い部品に 1 つずつ確率 organChance で小さい器官(段 2d)。
     * 全部で organsMax 個まで(0 = 無し)。中くらいの木を家 mediumTreesPerHouses 軒に 1 本(mediumTreesMax 本まで)、大きい器官の付いていない大きめの建物から。
     */
    private Start startLarge(ServerLevel level, StructureFusion.Site s, long k, long seed, FusionTable t) {
        List<StructureFusion.Hit> hits = StructureFusion.hits(s);
        OrganFusion.Ctx c = new OrganFusion.Ctx(level, hits, t);
        List<BoundingBox> houses = new ArrayList<>(), walls = new ArrayList<>();
        for (StructureFusion.Hit h : hits) {
            BoundingBox b = h.piece();
            if ((long) b.getXSpan() * b.getYSpan() * b.getZSpan() > 20_000_000L || !c.building(b)) continue;
            if (StructureTree.elongated(b)) walls.add(b);
            else houses.add(b);
        }
        markDone(k);
        if (houses.isEmpty() && walls.isEmpty()) {
            OvergrownVegetation.LOGGER.info("StructureTrees: {} に器官を付けられない(建物らしい部品が無い)", s.id());
            return Start.GAVE_UP;
        }
        houses.sort((p, q) -> Long.compare((long) q.getXSpan() * q.getYSpan() * q.getZSpan(), (long) p.getXSpan() * p.getYSpan() * p.getZSpan()));
        java.util.Random rnd = new java.util.Random(seed);
        ForestOrgans data = ForestOrgans.get(level);
        int organs = 0;
        BoundingBox big = houses.isEmpty() ? null : houses.get(0);
        double[] bc = centerOf(big != null ? big : walls.get(0));
        // 段 2d: 形はふつうの器官の形からランダム(井戸 = 地脈を吸う根 A は除く)。一番大きい建物は大きい版、ほかの建物・城壁は小さい版
        if (big != null) {
            int[] f = LARGE_SHAPES[rnd.nextInt(LARGE_SHAPES.length)];
            plantOn(level, data, OrganKind.values()[f[0]], false, f[1], big, bc, rnd);
            organs++;
        }
        List<BoundingBox> rest = new ArrayList<>(houses.subList(Math.min(1, houses.size()), houses.size()));
        rest.sort((p, q) -> {
            double[] a = centerOf(p), b = centerOf(q);
            return Double.compare(Math.hypot(a[0] - bc[0], a[1] - bc[1]), Math.hypot(b[0] - bc[0], b[1] - bc[1]));
        });
        // 段 2d(11-19 ユーザー「器官の数はランダムでいこう」): ほかの建物・城壁などの細長い部品は、1 つずつ確率 organChance で小さい器官。
        // 乱数は構造物ごとの種(読み込み直しで変わらない)。総数の上限は organsMax(0 = 無し)
        for (BoundingBox b : rest) {
            if (t.organsMax > 0 && organs >= t.organsMax) break;
            if (rnd.nextDouble() >= t.organChance) continue;
            int[] f = SMALL_SHAPES[rnd.nextInt(SMALL_SHAPES.length)];
            plantOn(level, data, OrganKind.values()[f[0]], true, f[1], b, centerOf(b), rnd);
            organs++;
        }
        for (BoundingBox w : walls) {
            if (t.organsMax > 0 && organs >= t.organsMax) break;
            if (rnd.nextDouble() >= t.organChance) continue;
            int[] f = SMALL_SHAPES[rnd.nextInt(SMALL_SHAPES.length)];
            plantOn(level, data, OrganKind.values()[f[0]], true, f[1], w, centerOf(w), rnd);
            organs++;
        }
        // 中くらいの木: 大きい器官の付いていない大きめの建物から(建物が 1 つしか無ければ生やさない。仮)
        int nTree = Math.max(1, Math.min(t.mediumTreesMax, (int) Math.round(houses.size() / (double) t.mediumTreesPerHouses)));
        int trees = 0, blocks = 0;
        for (int i = 1; i < houses.size() && trees < nTree; i++) {
            StructureTree.Result r = StructureTree.planOnPiece(level, s, houses.get(i), seed ^ (i * 0x9E37L));
            if (r.plan() == null) continue;
            jobs.add(new Job(k, r.plan().pos(), r.plan().mat(), 0));
            trees++;
            blocks += r.plan().pos().length;
        }
        setDirty();
        OvergrownVegetation.LOGGER.info("StructureTrees: {}(大)に器官 {} 個(大きい器官 {}・城壁 {} 本)・中くらいの木 {} 本({} 個)", s.id(), organs,
                big != null ? big : "無し", walls.size(), trees, blocks);
        return Start.STARTED;
    }

    /** 段 2d の形の候補 {種類の番号, 形}。大きい版 11 形: 胞子の塔 A〜D・フェーズ A・B・蓄えの嚢 A・B・苗床 A・B・地脈を吸う根 B(A の沈む根の井戸は除く)。 */
    static final int[][] LARGE_SHAPES = {
            {OrganKind.TOWER.ordinal(), 0}, {OrganKind.TOWER.ordinal(), 1}, {OrganKind.TOWER.ordinal(), 2}, {OrganKind.TOWER.ordinal(), 3},
            {OrganKind.PHASE.ordinal(), 0}, {OrganKind.PHASE.ordinal(), 1}, {OrganKind.SAC.ordinal(), 0}, {OrganKind.SAC.ordinal(), 1},
            {OrganKind.NURSERY.ordinal(), 0}, {OrganKind.NURSERY.ordinal(), 1}, {OrganKind.LEY.ordinal(), 1}};
    /** 小さい版 10 形: 5 種類 × a・b(地脈を吸う根の小さい版 a・b はどちらも井戸ではない)。 */
    static final int[][] SMALL_SHAPES;

    static {
        List<int[]> s = new ArrayList<>();
        for (OrganKind k : OrganKind.values()) for (int v = 0; v < OrganShapes.SMALL_VARIANTS; v++) s.add(new int[]{k.ordinal(), v});
        SMALL_SHAPES = s.toArray(new int[0][]);
    }

    /** 建物の部品に重ねる器官を植える(段 2d)。形は器官の側で建物に重ねて作る({@link OrganFusion#planOverlay})。 */
    private static void plantOn(ServerLevel level, ForestOrgans data, OrganKind kind, boolean small, int variant, BoundingBox piece, double[] at,
                                java.util.Random rnd) {
        ForestOrgans.Organ o = data.plant(level, kind, small, variant, rnd.nextInt(), rnd.nextInt(4), (int) Math.floor(at[0]), (int) Math.floor(at[1]));
        o.onPiece = new int[]{piece.minX(), piece.minY(), piece.minZ(), piece.maxX(), piece.maxY(), piece.maxZ()};
        PLANTED.add(o.id);
        data.changed();
    }

    // ---------------------------------------------------------------- 置く

    private void placeSome(ServerLevel level, FusionTable t) {
        if (!TickBudget.mayStart(PerfJob.GRAMMAR_GROWTH)) return;
        long start = TickBudget.begin();
        int placed = 0, budget = t.treeBlocksPerTick;
        Job j = jobs.get(0);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (j.cursor < j.pos.length && budget > 0) {
            long p = j.pos[j.cursor];
            m.set(p);
            if (level.getChunkSource().getChunkNow(m.getX() >> 4, m.getZ() >> 4) == null) break; // 読み込まれるまで待つ
            j.cursor++;
            budget--;
            if (placeOne(level, m, j.mat[j.cursor - 1])) placed++;
        }
        if (j.cursor >= j.pos.length) jobs.remove(0);
        setDirty();
        TickBudget.end(PerfJob.GRAMMAR_GROWTH, start, placed);
    }

    /** 1 マス置く(空気・置き換えられる草花・水なしのときだけ)。 */
    static boolean placeOne(ServerLevel level, BlockPos p, byte mat) {
        BlockState cur = level.getBlockState(p);
        if (!(cur.isAir() || cur.canBeReplaced()) || !cur.getFluidState().isEmpty()) return false;
        BlockState s = stateOf(mat);
        if (s == null) return false;
        return level.setBlock(p, s, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /** 幹・根・枝は樹皮だけの過繁茂した木(木口の年輪が見えない)、葉は過繁茂の葉(散らない)。新しいブロックは作らない(段 2)。 */
    @Nullable
    static BlockState stateOf(byte mat) {
        return switch (mat) {
            case StructureTree.TRUNK, StructureTree.ROOT, StructureTree.BRANCH -> ModBlocks.OVERGROWN_WOOD.get().defaultBlockState();
            case StructureTree.LEAF -> ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState();
            case StructureTree.GIANT_ROOT -> ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
            default -> null;
        };
    }

    /** 計画を今すぐ全部置く(テスト・コマンド用)。置いた数を返す。 */
    public static int placeAllNow(ServerLevel level, StructureTree.Plan plan) {
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < plan.pos().length; i++) {
            if (placeOne(level, m.set(plan.pos()[i]), plan.mat()[i])) n++;
        }
        return n;
    }

    /** テスト用: 済みの印と途中の木を消す。 */
    public void clearForTests() {
        done.clear();
        courtyards.clear();
        jobs.clear();
        setDirty();
    }

    // ---------------------------------------------------------------- 保存

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("done", new LongArrayTag(done.toLongArray()));
        tag.put("courtyards", new LongArrayTag(courtyards.toLongArray()));
        ListTag list = new ListTag();
        for (Job j : jobs) {
            CompoundTag c = new CompoundTag();
            c.putLong("site", j.site);
            c.put("pos", new LongArrayTag(j.pos));
            c.putByteArray("mat", j.mat);
            c.putInt("cursor", j.cursor);
            list.add(c);
        }
        tag.put("jobs", list);
        return tag;
    }

    private static StructureTrees load(CompoundTag tag) {
        StructureTrees d = new StructureTrees();
        for (long k : tag.getLongArray("done")) d.done.add(k);
        for (long k : tag.getLongArray("courtyards")) d.courtyards.add(k);
        ListTag list = tag.getList("jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            long[] pos = c.getLongArray("pos");
            byte[] mat = c.getByteArray("mat");
            if (pos.length != mat.length) continue;
            d.jobs.add(new Job(c.getLong("site"), pos, mat, c.getInt("cursor")));
        }
        return d;
    }
}
