package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 器官が建つ所の木を払う(2026-11-19 ユーザー「んじゃ払うか」)。森で建てると器官が木に埋もれて地上から見えず、高さも樹冠の上で決まっていた。
 * <ul>
 *   <li>建つ高さは木(幹・葉・下草)を除いた地面で決める({@link #groundY})。</li>
 *   <li>器官の形の外周 + 余白(大きい版 {@code clearing.marginBlocks}・小さい版 {@code clearing.smallMarginBlocks})の円の中の木を払う。
 *       幹が円の中にある木は、円の外へはみ出した葉もまとめて払う(幹から葉 {@code clearing.leafReach} 段まで)。</li>
 *   <li>巨大樹(giant_wood・巨大樹の樹冠の葉)は払わない。地面の下草・過繁茂の地面は残す(森の中にぽっかり開けた場所になる)。</li>
 *   <li>払った木は落とし物を出さない(空気に置き換えるだけ。まわりの葉に形の更新を送らないので、残った葉も散らない)。</li>
 * </ul>
 * 芽を置いた後、器官を置き始める前に、置く予算の中で少しずつ進める。途中で止まった分は覚えず、再起動したら円を最初から見直す(払った所は空なので軽い)。
 */
public final class OrganClearing {
    private OrganClearing() {
    }

    /** 払う木(幹と葉)。巨大樹は {@link #isTree} で除く。 */
    static final TagKey<Block> TREE = BlockTags.create(OvergrownVegetation.id("organ_clear_tree"));
    /** 山刀で払える下草・蔦(地面と数えない。高い所にぶら下がっている物は払う)。 */
    static final TagKey<Block> BRUSH = BlockTags.create(OvergrownVegetation.id("machete_clearable"));

    /** 汚染のブロック全部(このうち当たりの無い物 = 幹や葉に付く草・垂れ根・苔)。 */
    static final TagKey<Block> CORRUPTED = BlockTags.create(OvergrownVegetation.id("corrupted"));

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * 木に付いている物か(2026-11-20 ユーザー「周囲の木にあった草とか垂れ根とかがめっちゃ浮いてる」): 山刀で払える下草・蔦、
     * または汚染のブロックのうち当たりの無い物(垂れる根・気根・苔・壁のシダなど)。木を払うとき一緒に払う。プレイヤーの置いた物は入らない。
     */
    static boolean isAttached(BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty() || isTree(s)) return false;
        return s.is(BRUSH) || (s.is(CORRUPTED) && !s.blocksMotion() && !(s.getBlock() instanceof OrganVeinBlock));
    }

    /** 地面に立っている草か(下が木でない固いブロック)。木のわきの林床の草は残す。 */
    private static boolean onGround(ServerLevel level, BlockPos p) {
        BlockState below = level.getBlockState(p.below());
        return below.blocksMotion() && !isTree(below) && !isLog(below);
    }

    /** 払いかけの器官ごとの、これから消す所と、もう入れた所(保存しない)。 */
    private static final Int2ObjectOpenHashMap<LongArrayFIFOQueue> QUEUE = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<LongOpenHashSet> QUEUED = new Int2ObjectOpenHashMap<>();
    /** 払いかけの器官ごとの、次に見る列(保存しない)。 */
    private static final Int2ObjectOpenHashMap<int[]> COLUMN = new Int2ObjectOpenHashMap<>();
    private static final Map<OrganBlueprint, Integer> EXTENT = new WeakHashMap<>();

    static void forget(int id) {
        QUEUE.remove(id);
        QUEUED.remove(id);
        COLUMN.remove(id);
    }

    static void clearAll() {
        QUEUE.clear();
        QUEUED.clear();
        COLUMN.clear();
    }

    // ---------------------------------------------------------------- 見分け

    /** 払う木か(巨大樹の幹・樹冠は払わない)。 */
    static boolean isTree(BlockState s) {
        return s.is(TREE) && !s.is(ModTags.GIANT_WOOD) && !s.is(ModTags.GIANT_SILHOUETTE_LEAVES);
    }

    static boolean isLog(BlockState s) {
        return s.is(BlockTags.LOGS) && !s.is(ModTags.GIANT_WOOD);
    }

    /** 地面と数えない物: 空気・木(巨大樹も)・下草・足を止めない物。水は地面と数える(水の上は今までどおり)。 */
    static boolean notGround(BlockState s) {
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty()) return false;
        if (s.is(TREE) || s.is(ModTags.GIANT_WOOD) || s.is(ModTags.GIANT_SILHOUETTE_LEAVES) || s.is(BRUSH)) return true;
        return !s.blocksMotion();
    }

    /** 木を除いた地面のいちばん上の高さ。 */
    static int groundY(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        int min = level.getMinBuildHeight();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = top; y > min; y--) {
            if (!notGround(level.getBlockState(p.set(x, y, z)))) return y;
        }
        return top;
    }

    /** 払う円の半径(形の外周 + 余白)。 */
    static int radius(OrganBlueprint bp, boolean small) {
        int extent = EXTENT.computeIfAbsent(bp, b -> {
            int r2 = 0;
            for (long e : b.entries) {
                int mm = OrganBlueprint.material(e);
                if (mm == OrganBlueprint.M_VINE || mm == OrganBlueprint.M_VEIN || mm == OrganBlueprint.M_COVER) continue; // 融合の蔦・脈・覆いは払う円に数えない(11-19)
                int dx = OrganBlueprint.dx(e), dz = OrganBlueprint.dz(e);
                r2 = Math.max(r2, dx * dx + dz * dz);
            }
            return (int) Math.ceil(Math.sqrt(r2));
        });
        OrganTable t = DesignData.organs();
        return extent + (small ? t.clearSmallMargin : t.clearMargin);
    }

    // ---------------------------------------------------------------- 枯れた後に生え直す

    /**
     * 芯を壊して枯れた器官の、払った円が段階的に森へ戻る(11-19 ユーザー「生え直す方向で」→ 同日「15〜20 日かけて段階的に、下草 → 苗木」。
     * 理由: 器官を潰した見返り(開けた場所)が長く残るほど、押し返す遊びが報われる)。全部で {@code clearing.regrowDays} 日。
     * <ol>
     *   <li>下草(初めの {@code regrowUndergrowthShare}): 円の広さ ÷ {@code regrowUndergrowthSpacing}² か所に汚染の下草。うち {@code regrowTallShare} は
     *       2 段の大きなシダ・葦(払った林床には背の低い下草が残っているので、背の高い草が増えて「茂ってきた」と見えるように)。</li>
     *   <li>苗木(残り): 円の広さ ÷ {@code regrowSpacing}² か所に過繁茂の苗木(下草の上でも植える。育ち方は苗木に任せる)。</li>
     *   <li>{@code regrowGiantSapling} なら最後に巨大樹の苗木を 1 本(既定 off【仮】)。</li>
     * </ol>
     * 地面が受け付けない所(器官の上・石)は飛ばす。枯れた器官の形は残る。役割の数え(100 tick ごと)から呼ぶ。
     */
    static boolean regrow(ServerLevel level, ForestOrgans.Organ o, OrganBlueprint bp, long now) {
        OrganTable t = DesignData.organs();
        if (o.regrown < 0 || !t.regrowEnabled || !o.cleared || o.placed <= 0 || o.state != ForestOrgans.State.WITHERED) return false;
        int r = radius(bp, o.small);
        double area = Math.PI * r * r;
        double share = t.regrowUndergrowthShare;
        int underTarget = share <= 0.0D ? 0 : (int) Math.round(area / ((double) t.regrowUndergrowthSpacing * t.regrowUndergrowthSpacing));
        int treeTarget = (int) Math.round(area / ((double) t.regrowSpacing * t.regrowSpacing));
        if (o.regrowUnder >= underTarget && o.regrown >= treeTarget) {
            if (t.regrowGiantSapling) giantSapling(level, o, r);
            o.regrown = -1; // 戻り終えた(もう設計図を引かない)
            return true;
        }
        double f = (now - o.witheredAt) / (t.regrowDays * 24000.0D);
        int underDue = share <= 0.0D ? underTarget : (int) Math.min(underTarget, Math.floor(underTarget * f / share));
        int treeDue = f <= share ? 0 : (int) Math.min(treeTarget, Math.floor(treeTarget * (f - share) / (1.0D - share)));
        boolean changed = false;
        for (int tries = 0; o.regrowUnder < underDue && tries < 8; tries++) {
            BlockPos p = spot(level, o, r, o.regrowUnder, 101L);
            if (p == null) break; // 読み込まれたら続きから
            java.util.Random rnd = new java.util.Random(o.seed * 0x2545F4914F6CDD1DL + o.regrowUnder * 131L);
            o.regrowUnder++;
            changed = true;
            if (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.blocks(level, p)) continue; // 構造物の部品の箱には生やさない(11-19 融合 段 1)
            BlockState here = level.getBlockState(p);
            if (!here.isAir() && !(here.canBeReplaced() && !(here.getBlock() instanceof DoublePlantBlock))) continue;
            net.minecraft.util.RandomSource pick = net.minecraft.util.RandomSource.create(rnd.nextLong());
            if (rnd.nextDouble() < t.regrowTallShare) {
                BlockState tall = com.yayuy.overgrownvegetation.corruption.CorruptionGrowth.randomTall(pick);
                BlockState above = level.getBlockState(p.above());
                if ((above.isAir() || above.canBeReplaced()) && tall.canSurvive(level, p) && tall.getBlock() instanceof DoublePlantBlock) {
                    DoublePlantBlock.placeAt(level, tall, p, Block.UPDATE_ALL);
                    continue;
                }
            }
            BlockState top = com.yayuy.overgrownvegetation.corruption.CorruptionGrowth.randomTop(pick);
            if (here.isAir() && top.canSurvive(level, p)) level.setBlock(p, top, Block.UPDATE_ALL);
        }
        BlockState sapling = com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_SAPLING.get().defaultBlockState();
        for (int tries = 0; o.regrown < treeDue && tries < 4; tries++) {
            BlockPos p = spot(level, o, r, o.regrown, 17L);
            if (p == null) break;
            o.regrown++;
            changed = true;
            if (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.blocks(level, p)) continue;
            // 下草(2 段の草も)は苗木で置き換える(下草が苗木の場所を取って森が戻らない、を防ぐ)
            BlockState here = level.getBlockState(p);
            if (!here.isAir() && !here.canBeReplaced()) continue;
            if (!sapling.canSurvive(level, p)) continue;
            level.setBlock(p, sapling, Block.UPDATE_ALL);
        }
        return changed;
    }

    /** 生え直す i 番目の所(器官の種から決まる。円の中に一様)の地面の 1 つ上。読み込まれていなければ null。 */
    private static BlockPos spot(ServerLevel level, ForestOrgans.Organ o, int r, int i, long salt) {
        java.util.Random rnd = new java.util.Random(o.seed * 0x5DEECE66DL + i * 31L + salt);
        double ang = rnd.nextDouble() * Math.PI * 2.0D, d = r * Math.sqrt(rnd.nextDouble());
        int x = o.x + (int) Math.round(Math.cos(ang) * d), z = o.z + (int) Math.round(Math.sin(ang) * d);
        if (!OrganWorld.ready(level, x >> 4, z >> 4)) return null;
        return new BlockPos(x, groundY(level, x, z) + 1, z);
    }

    /**
     * 枯れた器官のそば(払った円の 6〜9 割の所)に巨大樹の苗木を 1 本(11-19 ユーザー「巨大樹の苗も生えるように」)。
     * 器官の上・石など受け付けない所は向きを変えて 12 回まで試す。置けたら true。
     */
    static boolean giantSapling(ServerLevel level, ForestOrgans.Organ o, int r) {
        // 構造物のそばには巨大樹の苗を植えない(11-19 融合 段 1。全体管理の決め H「苗を置く段で弾く」。巨大樹の融合は段 2)
        if (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.anyNear(level, o.x, o.z, r + com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.table().giantClearRadius)) return false;
        java.util.Random rnd = new java.util.Random(o.seed * 0x9E3779B97F4A7C15L + 7L);
        BlockState giant = com.yayuy.overgrownvegetation.registry.ModBlocks.GIANT_OVERGROWN_SAPLING.get().defaultBlockState();
        for (int i = 0; i < 12; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2.0D, d = r * (0.6D + 0.3D * rnd.nextDouble());
            int x = o.x + (int) Math.round(Math.cos(ang) * d), z = o.z + (int) Math.round(Math.sin(ang) * d);
            if (!OrganWorld.ready(level, x >> 4, z >> 4)) continue;
            BlockPos p = new BlockPos(x, groundY(level, x, z) + 1, z);
            BlockState here = level.getBlockState(p);
            if ((here.isAir() || here.canBeReplaced()) && giant.canSurvive(level, p)) {
                level.setBlock(p, giant, Block.UPDATE_ALL);
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 進める

    /** 置く予算の中で払う(毎 tick)。払い終えたら {@code o.cleared}。残りの予算を返す。 */
    static int step(ServerLevel level, ForestOrgans.Organ o, OrganBlueprint bp, int budget) {
        return run(level, o, bp, budget, true);
    }

    /** その場で全部払う(コマンド・テスト。読み込まれている所だけ)。払ったブロックの数を返す。 */
    static int clearNow(ServerLevel level, ForestOrgans.Organ o, OrganBlueprint bp) {
        int before = Integer.MAX_VALUE;
        return before - run(level, o, bp, before, false);
    }

    private static int run(ServerLevel level, ForestOrgans.Organ o, OrganBlueprint bp, int budget, boolean timed) {
        OrganTable t = DesignData.organs();
        if (o.cleared) return budget;
        if (!t.clearEnabled || o.state == ForestOrgans.State.WITHERED) {
            o.cleared = true;
            forget(o.id);
            return budget;
        }
        int r = radius(bp, o.small);
        // 円の升が全部生成し終わるまで待つ(生成中に触ると主スレッドが待つ。11-18 の測り)
        for (int cx = (o.x - r) >> 4; cx <= (o.x + r) >> 4; cx++) {
            for (int cz = (o.z - r) >> 4; cz <= (o.z + r) >> 4; cz++) {
                if (!OrganWorld.ready(level, cx, cz)) return budget;
            }
        }
        int side = 2 * r + 1;
        LongArrayFIFOQueue q = QUEUE.computeIfAbsent(o.id, k -> new LongArrayFIFOQueue());
        LongOpenHashSet seen = QUEUED.computeIfAbsent(o.id, k -> new LongOpenHashSet());
        int[] col = COLUMN.computeIfAbsent(o.id, k -> new int[1]);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        while (budget > 0) {
            if (!q.isEmpty()) {
                p.set(q.dequeueLong());
                BlockState s = level.getBlockState(p);
                if ((isTree(s) || isAttached(s)) && !com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.blocks(level, p)) level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
                budget = timed ? TickBudget.spend(budget) : budget - 1;
                continue;
            }
            if (col[0] >= side * side) {
                o.cleared = true;
                forget(o.id);
                return budget;
            }
            int i = col[0]++;
            int dx = i % side - r, dz = i / side - r;
            budget = timed ? TickBudget.spend(budget) : budget - 1;
            if (dx * dx + dz * dz > r * r) continue;
            scanColumn(level, o, r, o.x + dx, o.z + dz, q, seen, t);
        }
        return budget;
    }

    /** 1 本の列: 地面より上の木・ぶら下がる下草を入れる。幹に当たったら、その木をまるごと入れる。 */
    private static void scanColumn(ServerLevel level, ForestOrgans.Organ o, int r, int x, int z, LongArrayFIFOQueue q, LongOpenHashSet seen,
                                   OrganTable t) {
        int ground = groundY(level, x, z);
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = ground + 1; y <= top; y++) {
            p.set(x, y, z);
            long key = p.asLong();
            if (seen.contains(key)) continue;
            if (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.blocks(level, p)) continue; // 構造物の部品の箱の木(家の柱・生け垣)は払わない(11-19 融合 段 1)
            BlockState s = level.getBlockState(p);
            if (isLog(s)) {
                tree(level, o, r, p.immutable(), q, seen, t);
            } else if (isTree(s) || (isAttached(s) && y >= ground + 3)) {
                seen.add(key);
                q.enqueue(key);
            }
        }
    }

    /**
     * 幹から木をたどって入れる。幹 → 幹(斜めも)・幹 → 葉、葉 → 葉は幹から {@code leafReach} 段まで、葉 → 幹へは進まない
     * (葉が触れ合っている隣の木まで払わない)。蔦は下へたどる。円の中心から半径 + 16 の外・生成し終わっていない升には出ない。
     */
    private static void tree(ServerLevel level, ForestOrgans.Organ o, int r, BlockPos start, LongArrayFIFOQueue q, LongOpenHashSet seen, OrganTable t) {
        LongArrayFIFOQueue open = new LongArrayFIFOQueue();
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap depth = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        long s0 = start.asLong();
        open.enqueue(s0);
        depth.put(s0, 0);
        seen.add(s0);
        q.enqueue(s0);
        int count = 1;
        int reach = (r + 16) * (r + 16);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        while (!open.isEmpty() && count < t.clearTreeMax) {
            long cur = open.dequeueLong();
            p.set(cur);
            int d = depth.get(cur);
            if (d < 0) {
                // 木に付いていた物: 上下にだけ続きをたどる(垂れ下がる房・2 段の草)
                for (int oy = -1; oy <= 1; oy += 2) {
                    n.set(p.getX(), p.getY() + oy, p.getZ());
                    long key = n.asLong();
                    if (seen.contains(key) || n.getY() <= level.getMinBuildHeight() || n.getY() >= level.getMaxBuildHeight()) continue;
                    if (!isAttached(level.getBlockState(n)) || (oy < 0 && onGround(level, n))) continue;
                    seen.add(key);
                    q.enqueue(key);
                    depth.put(key, -1);
                    open.enqueue(key);
                    if (++count >= t.clearTreeMax) return;
                }
                continue;
            }
            boolean fromLog = d == 0;
            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        if (ox == 0 && oy == 0 && oz == 0) continue;
                        boolean face = Math.abs(ox) + Math.abs(oy) + Math.abs(oz) == 1;
                        if (!fromLog && !face) continue; // 葉は面でだけつながる
                        n.set(p.getX() + ox, p.getY() + oy, p.getZ() + oz);
                        long key = n.asLong();
                        if (seen.contains(key)) continue;
                        int hx = n.getX() - o.x, hz = n.getZ() - o.z;
                        if (hx * hx + hz * hz > reach) continue;
                        if (n.getY() <= level.getMinBuildHeight() || n.getY() >= level.getMaxBuildHeight()) continue;
                        if (!OrganWorld.ready(level, n.getX() >> 4, n.getZ() >> 4)) continue;
                        if (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.blocks(level, n)) continue; // 構造物の部品の箱へはたどらない(11-19 融合 段 1)
                        BlockState s = level.getBlockState(n);
                        int nd;
                        if (isLog(s)) {
                            if (!fromLog) continue; // 葉から幹へは行かない
                            nd = 0;
                        } else if (isTree(s)) {
                            if (!face && !fromLog) continue;
                            nd = d + 1;
                            if (nd > t.clearLeafReach) continue;
                        } else if (s.is(Blocks.VINE) && face) {
                            nd = Math.max(1, d); // 蔦は葉と同じ段で下へたどる
                            if (oy > 0) continue;
                        } else if (face && isAttached(s) && !(oy <= 0 && onGround(level, n))) {
                            nd = -1; // 幹・葉に付いた草・垂れ根・苔(11-20)
                        } else {
                            continue;
                        }
                        seen.add(key);
                        q.enqueue(key);
                        depth.put(key, nd);
                        open.enqueue(key);
                        if (++count >= t.clearTreeMax) return;
                    }
                }
            }
        }
    }
}
