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
        if (!HANGS.isEmpty() && level.getGameTime() % 100L == 0L) data.hangWhenGrown(level);
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
            // 段 2b(11-19 ユーザー決定 A+B): 一番大きい建物から生え(A)、ほかの建物を太い根で抱える(B)。段 2 の中庭の巨大樹を置き換えた
            StructureTree.Embed e = StructureTree.embedSite(level, s, seed);
            markDone(k);
            if (e == null) {
                OvergrownVegetation.LOGGER.info("StructureTrees: {} に巨大樹を生やせない(建物らしい部品が無い)", s.id());
                return Start.GAVE_UP;
            }
            HUG_SKIPS.put(e.origin().asLong(), StructureTree.hugSkip(level, s, e.origin(), t.giantRadius * 1.6D + 6.0D));
            courtyards.add(e.origin().asLong());
            setDirty();
            boolean ok = plantCourtyardGiant(level, e.origin(), seed);
            if (ok) {
                StructureTree.Plan roots = StructureTree.hugRoots(level, s, e, seed);
                if (roots.pos().length > 0) jobs.add(new Job(k, roots.pos(), roots.mat(), 0));
                HANGS.add(new Hang(k, e, seed, level.getGameTime()));
            }
            OvergrownVegetation.LOGGER.info("StructureTrees: {} の建物 {} から巨大樹(芯 {}): {}", s.id(), e.host(), e.origin(), ok ? "植えた" : "育たなかった");
            return ok ? Start.STARTED : Start.GAVE_UP;
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

    /** 構造物から生える巨大樹の、幹の裾が置かないマス(部屋の中・入口)。芯の位置ごと。巨大樹の設置と同じく保存しない。 */
    private static final java.util.Map<Long, LongOpenHashSet> HUG_SKIPS = new java.util.HashMap<>();

    /** 巨大樹が育ち終わったら垂らす気根の予定(保存しない)。 */
    private record Hang(long site, StructureTree.Embed e, long seed, long since) {
    }

    private static final List<Hang> HANGS = new ArrayList<>();

    /** GiantTreeGrowthQueue が仕事を作るときに 1 回: 幹の最初のマスから 40 以内の芯の「置かないマス」。 */
    @Nullable
    public static LongOpenHashSet hugSkipAt(ServerLevel level, BlockPos trunk) {
        for (java.util.Map.Entry<Long, LongOpenHashSet> en : HUG_SKIPS.entrySet()) {
            BlockPos p = BlockPos.of(en.getKey());
            double dx = p.getX() - trunk.getX(), dz = p.getZ() - trunk.getZ();
            if (dx * dx + dz * dz <= 40.0D * 40.0D) return en.getValue();
        }
        return null;
    }

    /** 巨大樹の設置が全部終わってから、気根を作って積む。 */
    private void hangWhenGrown(ServerLevel level) {
        if (HANGS.isEmpty() || com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue.pendingJobs(level) > 0) return;
        long now = level.getGameTime();
        for (java.util.Iterator<Hang> it = HANGS.iterator(); it.hasNext(); ) {
            Hang h = it.next();
            if (now - h.since() < 200L) continue;
            BoundingBox hb = h.e().host();
            StructureFusion.Site[] ss = StructureFusion.sitesAt(level, ((hb.minX() + hb.maxX()) >> 1) >> 4, ((hb.minZ() + hb.maxZ()) >> 1) >> 4);
            if (ss == null) continue; // 読み込まれていない。また見る
            it.remove();
            for (StructureFusion.Site s : ss) {
                if (key(s) != h.site()) continue;
                StructureTree.Plan p = StructureTree.hangRoots(level, s, h.e(), h.seed());
                if (p.pos().length > 0) jobs.add(new Job(h.site(), p.pos(), p.mat(), 0));
                OvergrownVegetation.LOGGER.info("StructureTrees: {} の巨大樹の気根 {} 個", s.id(), p.pos().length);
            }
            setDirty();
        }
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
