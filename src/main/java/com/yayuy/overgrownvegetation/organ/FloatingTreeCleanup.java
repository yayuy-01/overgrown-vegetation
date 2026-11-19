package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 浮いた木の片付け(2026-11-19 構造物と森の融合 段 2b。{@code /ov perf organ fusion cleanup})。段 1 から段 2b の直しまでの間に、
 * 構造物の箱の中で幹が欠けて上だけ残った汚染の木を消す。
 *
 * <p>まわり {@code radius}(水平)の読み込まれたチャンクで、この MOD の原木・木・葉({@code #logs}・{@code #leaves} のこの MOD の物と、
 * 巨大樹・根のアーチの木部)を集め、6 方向でつながった塊に分ける。塊のどのブロックも、塊の外の「固い物」(動きを止めるブロック。地面・建物・
 * ほかの木でない物)に触れていなければ、支えの無い浮いた木として消す。範囲の端にかかる塊(外で支えられているかもしれない)と、
 * 構造物の守る範囲のそば(水平 16)に 1 ブロックも無い塊は残す。蔦やヒカリゴケなど固くない物は支えに数えない。
 */
public final class FloatingTreeCleanup {
    private FloatingTreeCleanup() {
    }

    public record Result(int clusters, int blocks, int scanned, int kept, int edge) {
    }

    /** 塊の大きさの上限(これを超える塊は巨大樹など。調べるのをやめて残す)。 */
    private static final int MAX_CLUSTER = 200_000;

    static boolean isTreePart(BlockState s) {
        if (s.isAir()) return false;
        Block b = s.getBlock();
        if (b == ModBlocks.OVERGROWN_WOOD.get() || b == ModBlocks.OVERGROWN_LEAVES.get() || b == ModBlocks.GIANT_OVERGROWN_LOG.get()
                || b == ModBlocks.GIANT_OVERGROWN_CAMBIUM.get() || b == ModBlocks.GIANT_CANOPY_LEAVES.get() || b == ModBlocks.ROOT_ARCH_LOG.get()
                || b == ModBlocks.OVERGROWN_LOG.get()) {
            return true;
        }
        if (!(s.is(BlockTags.LOGS) || isLeaf(s))) return false;
        return OvergrownVegetation.MODID.equals(BuiltInRegistries.BLOCK.getKey(b).getNamespace());
    }

    /** 支えになる物: 動きを止める・木でない、かつ丸ごとの立方体か、この MOD の物でない(段・階段など建物の部品)。木に付いた実・棚のキノコなど、この MOD の小さな飾りは支えに数えない(11-19 クライアントで、飾りのせいで浮いた木が残った)。 */
    private static final net.minecraft.tags.TagKey<Block> MOD_LEAVES = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, OvergrownVegetation.id("overgrown_leaves"));

    /** 葉(木部どうしのつながりに数えない)。 */
    static boolean isLeaf(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.is(MOD_LEAVES) || s.is(ModBlocks.OVERGROWN_LEAVES.get()) || s.is(ModBlocks.GIANT_CANOPY_LEAVES.get());
    }

    static boolean supports(ServerLevel level, BlockPos p, BlockState s) {
        if (s.isAir() || !s.blocksMotion() || isTreePart(s)) return false;
        if (s.isCollisionShapeFullBlock(level, p)) return true;
        return !OvergrownVegetation.MODID.equals(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getNamespace());
    }

    public static Result run(ServerLevel level, BlockPos center, int radius, boolean dryRun) {
        int cx0 = (center.getX() - radius) >> 4, cx1 = (center.getX() + radius) >> 4;
        int cz0 = (center.getZ() - radius) >> 4, cz1 = (center.getZ() + radius) >> 4;
        LongOpenHashSet tree = new LongOpenHashSet();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                LevelChunk ch = level.getChunkSource().getChunkNow(cx, cz);
                if (ch == null) continue;
                LevelChunkSection[] sections = ch.getSections();
                for (int si = 0; si < sections.length; si++) {
                    LevelChunkSection sec = sections[si];
                    if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(FloatingTreeCleanup::isTreePart)) continue;
                    int by = level.getSectionYFromSectionIndex(si) << 4;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                int wx = (cx << 4) + x, wz = (cz << 4) + z;
                                double dx = wx - center.getX(), dz = wz - center.getZ();
                                if (dx * dx + dz * dz > (double) radius * radius) continue;
                                if (isTreePart(sec.getBlockState(x, y, z))) tree.add(BlockPos.asLong(wx, by + y, wz));
                            }
                        }
                    }
                }
            }
        }
        // 木部(原木・木)と葉に分ける。葉は木部どうしのつながりに数えない(隣の木の樹冠と触れて「支えあり」になるのを防ぐ。11-19 クライアント)
        LongOpenHashSet logs = new LongOpenHashSet(), leaves = new LongOpenHashSet();
        for (long p : tree) {
            if (isLeaf(level.getBlockState(m.set(p)))) leaves.add(p);
            else logs.add(p);
        }
        int scanned = tree.size(), clusters = 0, kept = 0, edge = 0;
        LongOpenHashSet remove = new LongOpenHashSet();
        LongOpenHashSet keptLogs = new LongOpenHashSet(), floatLogs = new LongOpenHashSet();
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList comp = new LongArrayList();
        // 1. 木部の塊(26 方向)。どれかが固い物(支え)に触れていれば支えあり
        for (long start : logs.toLongArray()) {
            if (seen.contains(start)) continue;
            Group g = group(level, logs, start, seen, comp, center, radius, true, m);
            if (g.tooBig || g.supported) {
                keptLogs.addAll(comp);
                kept++;
            } else if (g.atEdge) {
                keptLogs.addAll(comp);
                edge++;
            } else if (!nearStructure(level, comp)) {
                keptLogs.addAll(comp);
                kept++;
            } else {
                floatLogs.addAll(comp);
                remove.addAll(comp);
                clusters++;
            }
        }
        // 2. 葉: 支えのある木部から葉をたどって 6 以内は残す。浮いた木部から 6 以内で、残す葉でない物は消す
        LongOpenHashSet keptLeaves = leafReach(leaves, keptLogs, 6);
        for (long p : leafReach(leaves, floatLogs, 6)) if (!keptLeaves.contains(p)) remove.add(p);
        // 3. 木部から離れた葉だけの塊: 支えが無く、範囲の端でなく、構造物のそばなら消す
        LongOpenHashSet near = leafReach(leaves, logs, 6);
        LongOpenHashSet lonely = new LongOpenHashSet();
        for (long p : leaves) if (!near.contains(p)) lonely.add(p);
        seen.clear();
        for (long start : lonely.toLongArray()) {
            if (seen.contains(start)) continue;
            Group g = group(level, lonely, start, seen, comp, center, radius, false, m);
            if (g.tooBig || g.supported || g.atEdge || !nearStructure(level, comp)) continue;
            remove.addAll(comp);
            clusters++;
        }
        if (!dryRun) {
            for (long p : remove) level.setBlock(m.set(p), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        int blocks = remove.size();
        OvergrownVegetation.LOGGER.info("FloatingTreeCleanup at {} r={}{}: 浮いた木 {} 塊・{} ブロック(木 {}・残した {}・端 {})", center, radius,
                dryRun ? "(数えただけ)" : "", clusters, blocks, scanned, kept, edge);
        return new Result(clusters, blocks, scanned, kept, edge);
    }

    private static final class Group {
        boolean supported, atEdge, tooBig;
    }

    /** start からつながる set の塊を comp に集める(26 方向か 6 方向)。支え・範囲の端・大きすぎを見る。 */
    private static Group group(ServerLevel level, LongOpenHashSet set, long start, LongOpenHashSet seen, LongArrayList comp, BlockPos center, int radius,
                               boolean diagonal, BlockPos.MutableBlockPos m) {
        Group g = new Group();
        comp.clear();
        comp.add(start);
        seen.add(start);
        for (int qi = 0; qi < comp.size(); qi++) {
            if (comp.size() > MAX_CLUSTER) {
                g.tooBig = true;
                break;
            }
            long p = comp.getLong(qi);
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            double dx = x - center.getX(), dz = z - center.getZ();
            if (dx * dx + dz * dz > (double) (radius - 1) * (radius - 1)) g.atEdge = true;
            for (int ax = -1; ax <= 1; ax++) {
                for (int ay = -1; ay <= 1; ay++) {
                    for (int az = -1; az <= 1; az++) {
                        int n = Math.abs(ax) + Math.abs(ay) + Math.abs(az);
                        if (n == 0 || (!diagonal && n > 1)) continue;
                        long nk = BlockPos.asLong(x + ax, y + ay, z + az);
                        if (set.contains(nk)) {
                            if (seen.add(nk)) comp.add(nk);
                            continue;
                        }
                        if (n > 1 || g.supported) continue; // 支えは 6 方向だけ
                        if (level.getChunkSource().getChunkNow((x + ax) >> 4, (z + az) >> 4) == null) {
                            g.atEdge = true;
                            continue;
                        }
                        m.set(x + ax, y + ay, z + az);
                        BlockState s = level.getBlockState(m);
                        if (!set.contains(nk) && !isTreePart(s) && supports(level, m, s)) g.supported = true;
                    }
                }
            }
        }
        return g;
    }

    private static boolean nearStructure(ServerLevel level, LongArrayList comp) {
        for (int i = 0; i < comp.size(); i += Math.max(1, comp.size() / 8)) {
            long p = comp.getLong(i);
            if (StructureFusion.anyNear(level, BlockPos.getX(p), BlockPos.getZ(p), 16.0D)) return true;
        }
        return false;
    }

    /** from の木部から葉を 6 方向にたどって steps 以内の葉。 */
    private static LongOpenHashSet leafReach(LongOpenHashSet leaves, LongOpenHashSet from, int steps) {
        LongOpenHashSet out = new LongOpenHashSet();
        LongArrayList frontier = new LongArrayList();
        for (long p : from) {
            for (Direction d : Direction.values()) {
                long n = BlockPos.offset(p, d);
                if (leaves.contains(n) && out.add(n)) frontier.add(n);
            }
        }
        for (int s = 1; s < steps && !frontier.isEmpty(); s++) {
            LongArrayList next = new LongArrayList();
            for (int i = 0; i < frontier.size(); i++) {
                long p = frontier.getLong(i);
                for (Direction d : Direction.values()) {
                    long n = BlockPos.offset(p, d);
                    if (leaves.contains(n) && out.add(n)) next.add(n);
                }
            }
            frontier = next;
        }
        return out;
    }
}
