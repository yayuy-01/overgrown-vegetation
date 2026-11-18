package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.LavaFernMatBlock;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NetherSpreadTable;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.spore.field.NetherSporeFieldServer;
import com.yayuy.overgrownvegetation.spore.field.NetherThermal;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>ネザーの先駆けの植物</b>(2026-11-12 植物の段の最初。正本は Vault「ネザーの環境の作り直し 2」の「植物の仕様」)。
 * 骨組みの差し込み口 {@link NetherFloraHooks} に入る。どれも光らず、胞子も出さない。
 * <ul>
 *     <li><b>根の網</b> {@code nether_root}: 太い根は最初の根の道({@code rootPrev/rootNext})をたどり、チャンクごとに入る辺から出る辺へ。
 *     台帳の根がチャンクに届いてから {@code firstRootTicksPerChunk} の間に伸びる。沈む所と 1 ブロック出る所がまだらに入れ替わり、
 *     幅 6 までの谷はアーチで渡り、最後のチャンクで溶岩の岸に潜る。枝の根はチャンクごとに 2〜3 本(長さ 4〜10)、太い根か覆いの起点から、
 *     いちばん熱い隣のチャンク({@link NetherThermal})の向きへ。段階 1 の間に伸びる。</li>
 *     <li><b>搾り殺し</b>: 段階 1 の覆いの前線が菌の木(柄とウォートブロックが一緒にある塊)に届いたら、柄 → 搾り殺された幹・
 *     ウォートブロック → 枯れたウォートブロック・シュルームライト → 枯れた傘(光が消える)。蔦は残す。</li>
 *     <li><b>溶岩の浮き草</b>: 段階 1 の間に、前線の中の溶岩の海の岸から沖へ 4〜6 ブロック、まだらに。殻・水になってもその上に残る。</li>
 * </ul>
 * 数値はすべて仮決め(ユーザー決定は「おすすめ通り」の形だけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class NetherPioneerFlora implements NetherFloraHooks.Flora {

    public static final NetherPioneerFlora INSTANCE = new NetherPioneerFlora();

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        NetherFloraHooks.register(INSTANCE);
    }

    // ---- 仮決めの値は design の nether_spread.json の flora*(2026-11-12 に定数から移した) ----
    // floraArchMaxGap の 6 はユーザー決定「6 ブロックまでの谷」。ほかは仮。

    private static final int SALT_EDGE = 91, SALT_RAISED = 77, SALT_BRANCH = 83, SALT_MAT = 79, SALT_MATLEN = 81;

    // ---- 保存しない覚え(サーバースレッドだけ) ----
    @Nullable
    private ServerLevel cacheLevel;
    private final Long2ObjectOpenHashMap<Path> mainPaths = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Path> branchPaths = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap mainDrawn = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap branchDrawn = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap nextHeavy = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<int[]> cols = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap reach = new Long2IntOpenHashMap();

    /** 置く順に並んだ根のブロック(位置と向き)。枝はいくつかの列を順に並べる(先頭から伸びる)。 */
    record Path(long[] pos, byte[] axis, int[] order) {
        int size() {
            return pos.length;
        }
    }

    private NetherPioneerFlora() {
    }

    private void checkLevel(ServerLevel level) {
        if (cacheLevel != level || mainPaths.size() > 4096) {
            cacheLevel = level;
            mainPaths.clear();
            branchPaths.clear();
            mainDrawn.clear();
            branchDrawn.clear();
            nextHeavy.clear();
            cols.clear();
            reach.clear();
        }
    }

    // ==========================================================================================
    // 差し込み口
    // ==========================================================================================

    @Override
    public void onPioneerTick(ServerLevel level, LevelChunk chunk, long age, int[] budget) {
        checkLevel(level);
        NetherSpreadTable t = DesignData.netherSpread();
        NetherLedgerData data = NetherLedgerData.get(level);
        long key = chunk.getPos().toLong();
        double span = Math.max(1, t.stageStartTicks[1]);
        double progress = Math.min(1.0, age / span);
        double rootFraction = Math.min(1.0, age / Math.max(1.0, t.firstRootTicksPerChunk / data.speed()));
        int[] cap = {Math.min(t.floraPlaceCap, budget[0])};
        int before = cap[0];
        grow(level, data, t, key, rootFraction, progress, cap);
        long now = level.getGameTime();
        if (cap[0] > 0 && now >= nextHeavy.getOrDefault(key, Long.MIN_VALUE)) {
            nextHeavy.put(key, now + t.floraHeavyTicks);
            strangle(level, data, t, chunk, progress, cap);
            if (cap[0] > 0) mats(level, data, t, key, progress, cap);
        }
        budget[0] -= before - cap[0];
    }

    @Override
    public boolean finishPioneer(ServerLevel level, LevelChunk chunk, int[] budget) {
        // 段階 1 の終わり: 途中の呼び出しが無かった(読み込まれていなかった)チャンクも、ここで描き終える。
        // 2026-11-12 から帯の予算の中で(1 回 floraPlaceCap まで)。上限で止まれば次の tick にまた呼ばれる
        checkLevel(level);
        long key = chunk.getPos().toLong();
        NetherSpreadTable t = DesignData.netherSpread();
        NetherLedgerData data = NetherLedgerData.get(level);
        int[] cap = {Math.min(t.floraPlaceCap, budget[0])};
        int before = cap[0];
        grow(level, data, t, key, 1.0, 1.0, cap);
        if (cap[0] > 0) strangle(level, data, t, chunk, 1.0, cap);
        if (cap[0] > 0) mats(level, data, t, key, 1.0, cap);
        budget[0] -= before - cap[0];
        if (cap[0] <= 0) return false; // 上限まで置いた: まだ残っているかもしれない
        forget(key);
        return true;
    }

    @Override
    public void onStageApplied(ServerLevel level, LevelChunk chunk, int stage) {
        checkLevel(level);
        if (stage >= 4) floatMats(level, chunk);
    }

    private void forget(long key) {
        mainPaths.remove(key);
        branchPaths.remove(key);
        mainDrawn.remove(key);
        branchDrawn.remove(key);
        nextHeavy.remove(key);
        cols.remove(key);
        reach.remove(key);
    }

    // ==========================================================================================
    // 根の網
    // ==========================================================================================

    private void grow(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long key, double rootFraction, double progress, int[] cap) {
        byte done = data.flora.get(key);
        if ((done & 1) == 0) {
            Path p = mainPaths.get(key);
            if (p == null) {
                p = mainPath(level, data, key);
                if (p == null) p = new Path(new long[0], new byte[0], new int[0]);
                mainPaths.put(key, p);
            }
            int target = (int) Math.ceil(rootFraction * p.size());
            int from = mainDrawn.get(key);
            mainDrawn.put(key, draw(level, p, from, target, cap));
            if (mainDrawn.get(key) >= p.size()) {
                data.flora.put(key, (byte) (data.flora.get(key) | 1));
                data.setDirty();
            }
        }
        done = data.flora.get(key);
        if ((done & 2) == 0) {
            Path p = branchPaths.get(key);
            if (p == null) {
                p = branchPath(level, data, t, key, mainPaths.get(key));
                branchPaths.put(key, p);
            }
            // 枝は順に並べた「何歩目」で伸ばす(どの枝も同じ速さで伸びる)
            int target = 0;
            int steps = (int) Math.ceil(progress * t.floraBranchMax);
            while (target < p.size() && p.order()[target] < steps) target++;
            int from = branchDrawn.get(key);
            branchDrawn.put(key, draw(level, p, from, target, cap));
            if (branchDrawn.get(key) >= p.size()) {
                data.flora.put(key, (byte) (data.flora.get(key) | 2));
                data.setDirty();
            }
        }
    }

    /** {@code from} から {@code to} の手前までを置く。置き終えた位置を返す。 */
    private static int draw(ServerLevel level, Path p, int from, int to, int[] cap) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int i = from;
        for (; i < to && i < p.size(); i++) {
            if (cap[0] <= 0) break;
            at.set(p.pos()[i]);
            BlockState here = level.getBlockState(at);
            if (here.is(ModBlocks.NETHER_ROOT.get()) || !replaceable(here)) continue;
            // 溶岩には海面より下(岸に潜る所)でしか入らない。溶岩の滝・流れを根で消すと、溶岩があふれる(11-12 の写真で見つけた)
            if (!here.getFluidState().isEmpty() && at.getY() > NetherLava.surfaceY(level)) continue;
            Direction.Axis axis = Direction.Axis.values()[p.axis()[i]];
            NetherGround.put(level, at, ModBlocks.NETHER_ROOT.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis));
            NetherFloraHooks.blocks++;
            cap[0]--;
        }
        return i;
    }

    /** 根が入ってよいブロック(空気・草・溶岩・ネザーの地面)。ポータル・建物・菌の木・浮き草には入らない。 */
    static boolean replaceable(BlockState s) {
        if (s.is(Blocks.NETHER_PORTAL) || s.is(ModBlocks.LAVA_FERN_MAT.get())) return false;
        // 管の樹(管・羽根)にも入らない(2026-11-18 揺れるテストの調べ): 開いた羽根は通り抜けられる = isOpen なので、根の網が羽根を根に置き換え、
        // 管の先の羽根が無くなっていた(tubeTreesStayUnderTheLake が決まった場所で落ちていた)
        if (s.is(ModBlocks.TUBE_TREE_STEM.get()) || s.is(ModBlocks.TUBE_TREE_PLUME.get())) return false;
        if (s.hasBlockEntity()) return false;
        if (NetherSurface.isOpen(s)) return true;
        if (!s.getFluidState().isEmpty()) return s.is(Blocks.LAVA) && s.getFluidState().isSource();
        return s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.NYLIUM) || s.is(Blocks.SOUL_SAND) || s.is(Blocks.SOUL_SOIL)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.MAGMA_BLOCK) || s.is(ModTags.NETHER_COVERABLE)
                || s.is(ModBlocks.COOLED_CRUST.get()) || s.is(ModBlocks.HEAT_CRYSTAL_CRUST.get())
                || NetherGround.isCoveredGround(s); // 覆われたブロック(2026-11-12)にも入る(先に覆われた列で根が途切れない)
    }

    /** 根の床としてのブロック(根・浮き草・草は床でない。溶岩は床と同じ高さとして扱う)。 */
    private static boolean ground(BlockState s) {
        if (s.is(ModBlocks.NETHER_ROOT.get())) return false;
        if (s.getFluidState().is(Fluids.LAVA)) return true;
        return s.blocksMotion();
    }

    private static boolean open(BlockState s) {
        return s.is(ModBlocks.NETHER_ROOT.get()) || NetherSurface.isOpen(s);
    }

    /** 列 (x, z) の床の高さ({@code hint} の近く。上 6・下 20 を探す)。見つからなければ {@code Integer.MIN_VALUE}。 */
    static int findFloor(ServerLevel level, int x, int z, int hint) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int min = level.getMinBuildHeight() + 1, max = level.getMaxBuildHeight() - 2;
        boolean aboveOpen = open(level.getBlockState(p.set(x, Math.min(max, hint + 7), z)));
        for (int y = Math.min(max, hint + 6); y >= Math.max(min, hint - 20); y--) {
            BlockState s = level.getBlockState(p.set(x, y, z));
            if (aboveOpen && ground(s)) return y;
            aboveOpen = open(s);
        }
        return Integer.MIN_VALUE;
    }

    /** 列 (x, z) のいちばん広い空間の床(上の空きがいちばん高い床。胞子で来たチャンクの起点の高さ)。 */
    static int widestFloor(ServerLevel level, LevelChunk chunk, int x, int z) {
        int[] floors = new int[12];
        int n = NetherSurface.floors(level, chunk, x, z, floors);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int best = Integer.MIN_VALUE, bestRoom = -1;
        for (int i = 0; i < n; i++) {
            int room = 0;
            while (room < 24 && open(level.getBlockState(p.set(x, floors[i] + 1 + room, z)))) room++;
            if (room > bestRoom) {
                bestRoom = room;
                best = floors[i];
            }
        }
        return best;
    }

    /** 隣り合うチャンク a・b の境目で根が渡る列(a 側の列と b 側の列。辺の真ん中から ± 5 ほど。両側で同じになる)。 */
    static int[] crossing(long seed, long a, long b) {
        int ax = ChunkPos.getX(a), az = ChunkPos.getZ(a), bx = ChunkPos.getX(b), bz = ChunkPos.getZ(b);
        long lo = Math.min(a, b), hi = Math.max(a, b);
        int off = 3 + (int) (NetherGround.hash01(seed, (int) lo, (int) (lo >> 32), (int) hi ^ (int) (hi >> 32), SALT_EDGE) * 10); // 3〜12
        if (bx != ax) {
            int edge = Math.max(ax, bx) << 4; // 大きい方のチャンクの西の端
            int z = (az << 4) + off;
            return bx > ax ? new int[]{edge - 1, z, edge, z} : new int[]{edge, z, edge - 1, z};
        }
        int edge = Math.max(az, bz) << 4;
        int x = (ax << 4) + off;
        return bz > az ? new int[]{x, edge - 1, x, edge} : new int[]{x, edge, x, edge - 1};
    }

    /** チャンクの太い根(道に載っていなければ null)。 */
    @Nullable
    private Path mainPath(ServerLevel level, NetherLedgerData data, long key) {
        boolean isStart = data.rootStart.containsKey(key) && !data.rootPrev.containsKey(key);
        boolean hasPrev = data.rootPrev.containsKey(key);
        if (!isStart && !hasPrev) return null;
        long seed = level.getSeed();
        int sx, sz, hint;
        int prevR = Integer.MIN_VALUE;
        if (isStart) {
            BlockPos exit = BlockPos.of(data.rootStart.get(key));
            sx = exit.getX();
            sz = exit.getZ();
            hint = exit.getY();
        } else {
            int[] c = crossing(seed, data.rootPrev.get(key), key);
            sx = c[2];
            sz = c[3];
            if (data.rootStart.containsKey(key)) {
                prevR = BlockPos.of(data.rootStart.get(key)).getY(); // 前のチャンクの最後の根の高さ
                hint = prevR;
            } else {
                hint = startHint(level, data, key, sx, sz);
            }
        }
        int s = NetherLava.surfaceY(level);
        boolean end = !data.rootNext.containsKey(key);
        boolean seaFound = false;
        NetherSpreadTable t = DesignData.netherSpread();
        int ex, ez;
        if (!end) {
            int[] c = crossing(seed, key, data.rootNext.get(key));
            ex = c[0];
            ez = c[1];
        } else {
            long sea = nearestSeaSurface(level, key, sx, sz, s);
            if (sea == Long.MIN_VALUE) {
                ex = (ChunkPos.getX(key) << 4) + 8;
                ez = (ChunkPos.getZ(key) << 4) + 8;
            } else {
                ex = BlockPos.getX(sea);
                seaFound = true;
                ez = BlockPos.getZ(sea);
            }
        }
        // 列: 入る所から出る所へ、辺でつながる歩み
        List<int[]> columns = walk(sx, sz, ex, ez);
        int n = columns.size();
        int[] g = new int[n];
        boolean[] dive = new boolean[n];
        int last = hint;
        int stop = n;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            int x = columns.get(i)[0], z = columns.get(i)[1];
            int f = findFloor(level, x, z, last);
            if (f == Integer.MIN_VALUE) f = last; // 床が見つからない: 同じ高さで渡る
            g[i] = f;
            last = f;
            if (end && f == s && NetherLava.isSeaSurfaceLava(level, p, x, s, z)) {
                dive[i] = true; // 溶岩の岸に着いた: ここで潜る
                stop = i + 1;
                break;
            }
        }
        n = stop;
        boolean dove = n > 0 && dive[n - 1];
        // 溶岩の海が床より下にある(ネザーの海は岩の下の洞にあることが多い): 最後の地面で地中へ潜り、岩の中を斜めに抜けて、
        // 洞の天井か壁から溶岩へ垂れる(11-12 ユーザー決定。見せるのは潜る所と出る所の 2 か所)。潜る列は、海の列から
        // (深さ × floraDiveSlope)ほど手前の地面(歩みが短ければ入り口の列)
        int ceiling = Integer.MIN_VALUE;
        if (end && !dove && seaFound && n == columns.size() && n > 0 && g[n - 1] > s && g[n - 1] - s <= t.floraDiveMaxDepth) {
            ceiling = caveCeiling(level, ex, ez, s, g[n - 1]);
            int d = 0;
            for (int i = n - 1; i >= 0; i--) {
                int x = columns.get(i)[0], z = columns.get(i)[1];
                double want = (g[i] - ceiling) * t.floraDiveSlope;
                if (Math.sqrt((double) (x - ex) * (x - ex) + (double) (z - ez) * (z - ez)) >= want) {
                    d = i;
                    break;
                }
            }
            n = d + 1;
        }
        int[] r = heights(seed, columns, g, n, true);
        int body = n; // 先頭に前のチャンクの列を足す前の列の数
        if (prevR != Integer.MIN_VALUE && n > 0) {
            // 前のチャンクの最後の列(境目の向こう)を先頭に足して、高さが違えば縦の根でつなぐ
            int[] c = crossing(seed, data.rootPrev.get(key), key);
            columns.add(0, new int[]{c[0], c[1]});
            int[] r2 = new int[n + 1];
            r2[0] = prevR;
            System.arraycopy(r, 0, r2, 1, n);
            r = r2;
            n++;
        }
        // 次のチャンクの入り口の高さの手がかり(読み込みの順が前後しても段差が出ないように)
        if (!end) {
            long next = data.rootNext.get(key);
            int[] c = crossing(seed, key, next);
            if (!data.rootStart.containsKey(next)) {
                data.rootStart.put(next, BlockPos.asLong(c[2], r[n - 1], c[3])); // 次のチャンクはこの高さからつなぐ
                data.setDirty();
            }
        }
        Builder b = new Builder();
        place(b, columns, r, n, 0);
        if (dove) {
            int x = columns.get(n - 1)[0], z = columns.get(n - 1)[1];
            for (int y = s; y >= s - 2; y--) b.add(x, y, z, Direction.Axis.Y, n);
        } else if (ceiling != Integer.MIN_VALUE && body > 0) {
            int x = columns.get(n - 1)[0], z = columns.get(n - 1)[1];
            tunnel(level, b, x, r[n - 1] - 1, z, ex, ceiling, ez, s, n);
        }
        return b.build();
    }

    /** 海の列 (x, z) の洞の天井(海面の上で初めての開いていないブロック。{@code top} まで無ければ {@code top})。 */
    static int caveCeiling(ServerLevel level, int x, int z, int s, int top) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = s + 1; y < top; y++) {
            if (!open(level.getBlockState(p.set(x, y, z)))) return y;
        }
        return top;
    }

    /** (x, y, z) の下が開いていて、そのまま溶岩の海の表面に落ちるか(潜った根が洞に出た所)。 */
    private static boolean dropsToSea(ServerLevel level, int x, int y, int z, int s) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int yy = y;
        while (yy > s && open(level.getBlockState(p.set(x, yy, z)))) yy--;
        return yy == s && NetherLava.isSeaSurfaceLava(level, p, x, s, z);
    }

    /**
     * 潜る根: (ax, ay, az) から海の列の天井 (bx, by, bz) へ、面でつながる斜めの線で岩の中を抜け、洞(下が海に落ちる開いた所)に
     * 出たらそこから海面の 2 つ下まで垂れる。途中の小さな空き(海に落ちない洞)は根が渡る。
     */
    private static void tunnel(ServerLevel level, Builder b, int ax, int ay, int az, int bx, int by, int bz, int s, int order) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int[] out = null;
        for (int[] q : line3(ax, ay, az, bx, by, bz)) {
            BlockState here = level.getBlockState(p.set(q[0], q[1], q[2]));
            if (open(here) && q[1] > s && dropsToSea(level, q[0], q[1], q[2], s)) {
                out = q; // 洞に出た(壁か天井)
                break;
            }
            b.add(q[0], q[1], q[2], Direction.Axis.values()[q[3]], order);
        }
        if (out == null) out = new int[]{bx, by - 1, bz}; // 天井まで岩だった: 天井の下から垂れる
        for (int y = out[1]; y >= s - 2; y--) b.add(out[0], y, out[2], Direction.Axis.Y, order + 1);
    }

    /** (ax, ay, az) から (bx, by, bz) へ面でつながるまっすぐな線(両端を含む)。各点は {x, y, z, 入ってきた向きの軸}。 */
    static List<int[]> line3(int ax, int ay, int az, int bx, int by, int bz) {
        List<int[]> out = new ArrayList<>();
        int x = ax, y = ay, z = az;
        int dx = bx - ax, dy = by - ay, dz = bz - az;
        out.add(new int[]{x, y, z, Direction.Axis.Y.ordinal()});
        int n = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        for (int i = 0; i < n; i++) {
            double fx = dx == 0 ? -1 : Math.abs(bx - x) / (double) Math.abs(dx);
            double fy = dy == 0 ? -1 : Math.abs(by - y) / (double) Math.abs(dy);
            double fz = dz == 0 ? -1 : Math.abs(bz - z) / (double) Math.abs(dz);
            Direction.Axis a;
            if (fy >= fx && fy >= fz && y != by) {
                y += Integer.signum(dy);
                a = Direction.Axis.Y;
            } else if (fx >= fz && x != bx) {
                x += Integer.signum(dx);
                a = Direction.Axis.X;
            } else if (z != bz) {
                z += Integer.signum(dz);
                a = Direction.Axis.Z;
            } else if (x != bx) {
                x += Integer.signum(dx);
                a = Direction.Axis.X;
            } else {
                y += Integer.signum(dy);
                a = Direction.Axis.Y;
            }
            out.add(new int[]{x, y, z, a.ordinal()});
        }
        return out;
    }

    /** 入り口の高さの手がかりが無いとき: 道の起点(ポータルの出口)の高さ、無ければ入り口の列のいちばん広い床。 */
    private static int startHint(ServerLevel level, NetherLedgerData data, long key, int x, int z) {
        long k = key;
        for (int i = 0; i < 64 && data.rootPrev.containsKey(k); i++) k = data.rootPrev.get(k);
        if (data.rootStart.containsKey(k)) return BlockPos.of(data.rootStart.get(k)).getY();
        LevelChunk chunk = NetherGround.chunkAt(level, x, z);
        int f = chunk == null ? Integer.MIN_VALUE : widestFloor(level, chunk, x, z);
        return f == Integer.MIN_VALUE ? NetherLava.surfaceY(level) + 1 : f + 1;
    }

    /** チャンクの中で (x, z) にいちばん近い溶岩の海の表面の列。無ければ Long.MIN_VALUE。 */
    private static long nearestSeaSurface(ServerLevel level, long key, int x, int z, int s) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = ChunkPos.getX(key) << 4, z0 = ChunkPos.getZ(key) << 4;
        long best = Long.MIN_VALUE;
        int bestD = Integer.MAX_VALUE;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int d = Math.abs(x0 + dx - x) + Math.abs(z0 + dz - z);
                if (d >= bestD) continue;
                if (!NetherLava.isSeaSurfaceLava(level, p, x0 + dx, s, z0 + dz)) continue;
                bestD = d;
                best = BlockPos.asLong(x0 + dx, s, z0 + dz);
            }
        }
        return best;
    }

    /** (sx, sz) から (ex, ez) へ辺でつながる列(両端を含む。斜めに近いまっすぐ)。 */
    static List<int[]> walk(int sx, int sz, int ex, int ez) {
        List<int[]> out = new ArrayList<>();
        int x = sx, z = sz;
        out.add(new int[]{x, z});
        int dx = ex - sx, dz = ez - sz;
        int n = Math.abs(dx) + Math.abs(dz);
        for (int i = 0; i < n; i++) {
            double fx = Math.abs(ex - x) / (double) Math.max(1, Math.abs(dx));
            double fz = Math.abs(ez - z) / (double) Math.max(1, Math.abs(dz));
            if (x != ex && (z == ez || fx >= fz)) x += Integer.signum(dx);
            else z += Integer.signum(dz);
            out.add(new int[]{x, z});
        }
        return out;
    }

    /**
     * 列ごとの根の高さ: 床の高さ(沈む)か 1 つ上(出る)をまだらに入れ替え、{@code arches} なら幅 {@code floraArchMaxGap} までの谷をアーチで渡る。
     */
    static int[] heights(long seed, List<int[]> columns, int[] g, int n, boolean arches) {
        NetherSpreadTable t = DesignData.netherSpread();
        int[] r = new int[n];
        for (int i = 0; i < n; i++) {
            int x = columns.get(i)[0], z = columns.get(i)[1];
            r[i] = g[i] + (NetherJitter.patch(seed, x, 0, z, t.floraRaisedShare, t.floraRaisedScale, SALT_RAISED) ? 1 : 0);
        }
        if (!arches) return r;
        for (int i = 0; i + 1 < n; i++) {
            if (g[i + 1] >= g[i] - 1) continue;
            // 谷の始まり: 向こう岸(もとの高さ - 1 以上に戻る列)を探す
            int j = -1;
            for (int k = i + 2; k < n && k - i - 1 <= t.floraArchMaxGap; k++) {
                if (g[k] >= g[i] - 1) {
                    j = k;
                    break;
                }
            }
            if (j < 0) continue; // 広い谷・崖: 床に沿って下りる
            int h0 = Math.max(r[i], g[i] + 1), h1 = Math.max(r[j], g[j] + 1);
            r[i] = h0;
            r[j] = h1;
            for (int k = i + 1; k < j; k++) {
                double u = (k - i) / (double) (j - i);
                r[k] = (int) Math.round(h0 + (h1 - h0) * u + 1.2 * Math.sin(Math.PI * u));
            }
            i = j - 1;
        }
        return r;
    }

    /** 列と高さからブロックを並べる(横の根 + 段差の縦の根。縦は高い方の列に、低い方の高さから)。{@code order0} は枝の何歩目の起点。 */
    private static void place(Builder b, List<int[]> columns, int[] r, int n, int order0) {
        for (int i = 0; i < n; i++) {
            int x = columns.get(i)[0], z = columns.get(i)[1];
            int[] o = i + 1 < n ? columns.get(i + 1) : (i > 0 ? columns.get(i - 1) : null);
            Direction.Axis axis = o == null || o[0] != x ? Direction.Axis.X : Direction.Axis.Z;
            if (i > 0 && r[i] > r[i - 1]) {
                for (int y = r[i - 1]; y < r[i]; y++) b.add(x, y, z, Direction.Axis.Y, order0 + i);
            } else if (i > 0 && r[i] < r[i - 1]) {
                int px = columns.get(i - 1)[0], pz = columns.get(i - 1)[1];
                for (int y = r[i - 1] - 1; y >= r[i]; y--) b.add(px, y, pz, Direction.Axis.Y, order0 + i);
            }
            b.add(x, r[i], z, axis, order0 + i);
        }
    }

    /** 枝の根: 2〜3 本、太い根の上か覆いの起点から、いちばん熱い隣の向きへ 4〜10 ブロック。 */
    private Path branchPath(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long key, @Nullable Path main) {
        long seed = level.getSeed();
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        int count = 2 + (NetherGround.hash01(seed, cx, 1, cz, SALT_BRANCH) < 0.5 ? 1 : 0);
        int[] dir = hottestNeighbour(level, data, t, key);
        double base = Math.atan2(dir[1], dir[0]);
        // 起点の候補: 太い根の横のブロック(縦でない)
        LongArrayList starts = new LongArrayList();
        if (main != null) {
            for (int i = 0; i < main.size(); i++) {
                if (main.axis()[i] != Direction.Axis.Y.ordinal()) starts.add(main.pos()[i]);
            }
        }
        long origin = data.coverOriginOf(key);
        int ox = Math.max(cx << 4, Math.min((cx << 4) + 15, BlockPos.getX(origin)));
        int oz = Math.max(cz << 4, Math.min((cz << 4) + 15, BlockPos.getZ(origin)));
        Builder b = new Builder();
        for (int k = 0; k < count; k++) {
            int sx, sz, hint;
            if (!starts.isEmpty()) {
                BlockPos sp = BlockPos.of(starts.getLong(starts.size() * (k + 1) / (count + 1)));
                sx = sp.getX();
                sz = sp.getZ();
                hint = sp.getY();
            } else {
                sx = ox;
                sz = oz;
                LevelChunk chunk = NetherGround.chunkAt(level, sx, sz);
                int f = chunk == null ? Integer.MIN_VALUE : widestFloor(level, chunk, sx, sz);
                if (f == Integer.MIN_VALUE) continue;
                hint = f + 1;
            }
            double h = NetherGround.hash01(seed, cx * 7 + k, 2, cz, SALT_BRANCH);
            double ang = base + Math.toRadians((k - (count - 1) / 2.0) * 28 + (h - 0.5) * 16);
            int len = t.floraBranchMin + (int) (NetherGround.hash01(seed, cx, 3 + k, cz, SALT_BRANCH) * (t.floraBranchMax - t.floraBranchMin + 1));
            int walkLen = len + (starts.isEmpty() ? (int) Math.ceil(t.coverOriginRadius + 1) : 0); // 起点の輪の中は飛ばすので、その分先まで
            int ex = sx + (int) Math.round(Math.cos(ang) * walkLen), ez = sz + (int) Math.round(Math.sin(ang) * walkLen);
            List<int[]> columns = walk(sx, sz, ex, ez);
            columns.remove(0); // 起点の列は太い根(か起点そのもの)
            if (starts.isEmpty()) {
                // 覆いの起点から出る枝は、必ず覆う輪(coverOriginRadius)の外から地面に出る(萌芽体の足もとの苔を根で消さない)
                double rr = t.coverOriginRadius + 1;
                columns.removeIf(c -> (c[0] - sx) * (c[0] - sx) + (c[1] - sz) * (c[1] - sz) <= rr * rr);
            }
            int n = Math.min(columns.size(), len);
            int[] g = new int[n];
            int last = hint - 1;
            int m = 0;
            for (int i = 0; i < n; i++) {
                int x = columns.get(i)[0], z = columns.get(i)[1];
                if (NetherGround.chunkAt(level, x, z) == null) break;
                int f = findFloor(level, x, z, last);
                if (f == Integer.MIN_VALUE || Math.abs(f - last) > 3) break; // 崖・壁で止まる
                g[i] = f;
                last = f;
                m = i + 1;
            }
            int[] r = heights(seed, columns, g, m, false);
            // 太い根から出る枝は、太い根の高さから 1 段ずつつなぐ
            if (m > 0 && !starts.isEmpty() && r[0] < hint - 1) r[0] = Math.max(r[0], hint - 1);
            place(b, columns, r, m, 0);
        }
        return b.buildSortedByOrder();
    }

    /**
     * いちばん熱い隣のチャンク(8 方向)への向き {dx, dz}。温度は胞子の場の {@link NetherThermal}(場が動いていなければ、
     * 隣の海面の高さの溶岩の割合 × 段階の冷え)。同じ熱さなら座標で決まった向き。
     */
    public static int[] hottestNeighbour(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long key) {
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        NetherThermal th = NetherSporeFieldServer.currentThermal();
        double best = -1;
        int[] dir = null;
        int startAt = (int) (NetherGround.hash01(level.getSeed(), cx, 5, cz, SALT_BRANCH) * 8);
        int[][] dirs = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
        for (int i = 0; i < 8; i++) {
            int[] d = dirs[(startAt + i) % 8];
            // 読み込まれた隣は今のブロックで測る(NetherThermal と同じ式。場の写しは数分おきで古いことがある)。読み込まれていなければ場の温度
            double temp = level.getChunkSource().getChunkNow(cx + d[0], cz + d[1]) != null || th == null
                    ? localTemperature(level, data, t, cx + d[0], cz + d[1]) : th.temperature(cx + d[0], cz + d[1]);
            if (temp > best + 1e-6) {
                best = temp;
                dir = d;
            }
        }
        return dir;
    }

    /** 場が動いていないときの温度: 海面の高さの溶岩の割合(8 列おきに 64 列)× 段階の冷え。 */
    static double localTemperature(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return 0;
        int s = NetherLava.surfaceY(level);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int lava = 0;
        for (int x = 0; x < 16; x += 2) {
            for (int z = 0; z < 16; z += 2) {
                if (chunk.getFluidState(p.set((cx << 4) + x, s, (cz << 4) + z)).is(Fluids.LAVA)) lava++;
            }
        }
        double heat = t.thermalBase + (1 - t.thermalBase) * Math.min(1.0, lava / 64.0 * t.thermalLavaGain);
        long k = ChunkPos.asLong(cx, cz);
        int stage = data.isReached(k) ? t.stageAt(level.getGameTime() - data.arrivalOf(k)) : 0;
        return heat * t.thermalCool[Math.max(0, Math.min(t.thermalCool.length - 1, stage))];
    }

    /** ブロックの並びを作る。 */
    private static final class Builder {
        final LongArrayList pos = new LongArrayList();
        final it.unimi.dsi.fastutil.bytes.ByteArrayList axis = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
        final it.unimi.dsi.fastutil.ints.IntArrayList order = new it.unimi.dsi.fastutil.ints.IntArrayList();
        final LongOpenHashSet seen = new LongOpenHashSet();

        void add(int x, int y, int z, Direction.Axis a, int o) {
            long p = BlockPos.asLong(x, y, z);
            if (!seen.add(p)) return;
            pos.add(p);
            axis.add((byte) a.ordinal());
            order.add(o);
        }

        Path build() {
            return new Path(pos.toLongArray(), axis.toByteArray(), order.toIntArray());
        }

        Path buildSortedByOrder() {
            Integer[] idx = new Integer[pos.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> Integer.compare(order.getInt(a), order.getInt(b)));
            long[] p = new long[idx.length];
            byte[] ax = new byte[idx.length];
            int[] o = new int[idx.length];
            for (int i = 0; i < idx.length; i++) {
                p[i] = pos.getLong(idx[i]);
                ax[i] = axis.getByte(idx[i]);
                o[i] = order.getInt(idx[i]);
            }
            return new Path(p, ax, o);
        }
    }

    // ==========================================================================================
    // 前線(覆いと同じ: 起点から段階 1 の間に広がる)
    // ==========================================================================================

    private int[] colsOf(ServerLevel level, NetherSpreadTable t, long key) {
        int[] c = cols.get(key);
        if (c == null) {
            c = NetherJitter.ownedColumns(level.getSeed(), new ChunkPos(key), t.edgeJitterBlocks, t.edgeJitterScale);
            cols.put(key, c);
        }
        return c;
    }

    private int reachOf(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long key) {
        if (!reach.containsKey(key)) reach.put(key, NetherStageWorker.farthest(colsOf(level, t, key), data.coverOriginOf(key), t.coverFrontBlocks));
        return reach.get(key);
    }

    // ==========================================================================================
    // 搾り殺し
    // ==========================================================================================

    static boolean isStem(BlockState s) {
        return s.is(Blocks.CRIMSON_STEM) || s.is(Blocks.WARPED_STEM) || s.is(Blocks.STRIPPED_CRIMSON_STEM) || s.is(Blocks.STRIPPED_WARPED_STEM)
                || s.is(Blocks.CRIMSON_HYPHAE) || s.is(Blocks.WARPED_HYPHAE) || s.is(Blocks.STRIPPED_CRIMSON_HYPHAE) || s.is(Blocks.STRIPPED_WARPED_HYPHAE);
    }

    static boolean isWart(BlockState s) {
        return s.is(Blocks.NETHER_WART_BLOCK) || s.is(Blocks.WARPED_WART_BLOCK);
    }

    private static boolean isTreePart(BlockState s) {
        return isStem(s) || isWart(s) || s.is(Blocks.SHROOMLIGHT);
    }

    /** 傘から垂れる・伸びる蔦(2026-11-12 ユーザー決定: 傘と一緒に消す。傘が無いと宙に浮くため)。 */
    static boolean isVine(BlockState s) {
        return s.is(Blocks.WEEPING_VINES) || s.is(Blocks.WEEPING_VINES_PLANT) || s.is(Blocks.TWISTING_VINES) || s.is(Blocks.TWISTING_VINES_PLANT);
    }

    /** 前線の中の菌の木を搾り殺す。変えたブロック数を返す。 */
    int strangle(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, LevelChunk chunk, double progress, int[] cap) {
        long key = chunk.getPos().toLong();
        long seed = level.getSeed();
        long origin = data.coverOriginOf(key);
        int reachBlocks = reachOf(level, data, t, key);
        int changed = 0;
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int si = 0; si < sections.length && cap[0] > 0; si++) {
            LevelChunkSection section = sections[si];
            if (section.hasOnlyAir() || !section.maybeHas(NetherPioneerFlora::isStem)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(si) << 4;
            for (int ly = 0; ly < 16; ly++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        if (!isStem(section.getBlockState(lx, ly, lz))) continue;
                        int x = chunk.getPos().getMinBlockX() + lx, z = chunk.getPos().getMinBlockZ() + lz;
                        if (!NetherGround.inFront(seed, x, z, origin, progress, reachBlocks)) continue;
                        changed += strangleTree(level, at.set(x, baseY + ly, z).immutable(), cap);
                        if (cap[0] <= 0) return changed;
                    }
                }
            }
        }
        return changed;
    }

    /**
     * {@code seed} の柄からつながった木(柄・ウォートブロック・シュルームライト。26 方向・横 ± 7・縦 -12〜+40・800 ブロックまで)を集め、
     * 柄が 2 つ以上とウォートブロックが 4 つ以上あれば自然の菌の木として搾り殺す(仮決め)。変えた数を返す。
     *
     * <p>2026-11-12 ユーザー決定で作り直し: オーバーワールドの木と同じく<b>傘は消えて幹だけが残る</b>。柄は 1 種類の搾り殺された幹(黒く湿って
     * 苔に覆われた埋もれ木)、ウォートブロック・シュルームライトと、そこから縦につながった垂れ蔦・ねじれ蔦は空気。
     * 1 本の木は上限 {@code cap} を超えても途中で止めない(止めると、幹が先に変わって傘だけが取り残され、もう見つけられなくなる)。
     */
    static int strangleTree(ServerLevel level, BlockPos seed, int[] cap) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongArrayList parts = new LongArrayList();
        queue.enqueue(seed.asLong());
        seen.add(seed.asLong());
        int stems = 0, warts = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty() && parts.size() < 800) {
            long l = queue.dequeueLong();
            BlockState s = level.getBlockState(p.set(l));
            if (!isTreePart(s)) continue;
            parts.add(l);
            if (isStem(s)) stems++;
            else if (isWart(s)) warts++;
            int x = p.getX(), y = p.getY(), z = p.getZ();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = x + dx, ny = y + dy, nz = z + dz;
                        if (Math.abs(nx - seed.getX()) > 7 || Math.abs(nz - seed.getZ()) > 7 || ny - seed.getY() < -12 || ny - seed.getY() > 40) continue;
                        long n = BlockPos.asLong(nx, ny, nz);
                        if (!seen.add(n)) continue;
                        if (NetherGround.chunkAt(level, nx, nz) == null) continue;
                        queue.enqueue(n);
                    }
                }
            }
        }
        if (stems < 2 || warts < 4) return 0; // 柄だけの柱・ウォートだけの塊(人の飾りかもしれない)は触らない
        // 傘から縦につながった蔦(下へ垂れる垂れ蔦・上へ伸びるねじれ蔦)も集める
        LongArrayList vines = new LongArrayList();
        LongOpenHashSet vineSeen = new LongOpenHashSet();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int i = 0; i < parts.size(); i++) {
            p.set(parts.getLong(i));
            if (isStem(level.getBlockState(p))) continue;
            for (int dir = -1; dir <= 1; dir += 2) {
                for (int k = 1; k <= 48; k++) {
                    q.set(p.getX(), p.getY() + dir * k, p.getZ());
                    if (!isVine(level.getBlockState(q)) || !vineSeen.add(q.asLong())) break;
                    vines.add(q.asLong());
                }
            }
        }
        parts.addAll(vines);
        int changed = 0;
        for (int i = 0; i < parts.size(); i++) {
            p.set(parts.getLong(i));
            BlockState s = level.getBlockState(p);
            BlockState to;
            if (isStem(s)) {
                to = ModBlocks.STRANGLED_STEM.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, s.getValue(RotatedPillarBlock.AXIS));
            } else if (isWart(s) || s.is(Blocks.SHROOMLIGHT) || isVine(s)) {
                to = Blocks.AIR.defaultBlockState(); // 傘と蔦は消える(2026-11-12 ユーザー決定)
            } else {
                continue;
            }
            NetherGround.put(level, p, to);
            NetherFloraHooks.blocks++;
            cap[0]--;
            changed++;
        }
        return changed;
    }

    // ==========================================================================================
    // 溶岩の浮き草
    // ==========================================================================================

    /** 前線の中の、溶岩の海の岸から沖へ 4〜6 ブロックの所に、まだらに浮き草を置く(段階 1 の進みに合わせて沖へ)。置いた数を返す。 */
    int mats(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long key, double progress, int[] cap) {
        int s = NetherLava.surfaceY(level);
        long seed = level.getSeed();
        int[] owned = colsOf(level, t, key);
        // 溶岩の海の表面が無いチャンクはすぐ帰る
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int c = 0; c + 1 < owned.length && !any; c += 2) {
            if (level.getFluidState(p.set(owned[c], s, owned[c + 1])).is(Fluids.LAVA)) any = true;
        }
        if (!any) return 0;
        // 岸からの距離(4 方向・6 まで)を、持ち主の列のまわり ± (6 + 揺らぎ) で数える
        int cx = ChunkPos.getX(key) << 4, cz = ChunkPos.getZ(key) << 4;
        int margin = t.edgeJitterBlocks + t.floraMatMax + 1;
        int x0 = cx - margin, z0 = cz - margin, size = 16 + 2 * margin;
        byte[] dist = new byte[size * size];
        boolean[] surf = new boolean[size * size];
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int x = x0 + i, z = z0 + j;
                if (NetherGround.chunkAt(level, x, z) == null) continue;
                surf[i * size + j] = NetherLava.isSeaSurfaceLava(level, p, x, s, z);
            }
        }
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (!surf[i * size + j]) continue;
                boolean shore = false;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int ni = i + d.getStepX(), nj = j + d.getStepZ();
                    if (ni < 0 || nj < 0 || ni >= size || nj >= size) continue;
                    if (surf[ni * size + nj]) continue;
                    int x = x0 + ni, z = z0 + nj;
                    if (NetherGround.chunkAt(level, x, z) == null) continue;
                    if (!NetherLava.isLava(level.getBlockState(p.set(x, s, z)))) shore = true; // 隣が陸(殻も陸)
                }
                if (shore) {
                    dist[i * size + j] = 1;
                    queue.enqueue(((long) i << 32) | j);
                }
            }
        }
        while (!queue.isEmpty()) {
            long q = queue.dequeueLong();
            int i = (int) (q >> 32), j = (int) q;
            int d = dist[i * size + j];
            if (d >= t.floraMatMax) continue;
            for (Direction dd : Direction.Plane.HORIZONTAL) {
                int ni = i + dd.getStepX(), nj = j + dd.getStepZ();
                if (ni < 0 || nj < 0 || ni >= size || nj >= size) continue;
                int k = ni * size + nj;
                if (!surf[k] || dist[k] != 0) continue;
                dist[k] = (byte) (d + 1);
                queue.enqueue(((long) ni << 32) | nj);
            }
        }
        long origin = data.coverOriginOf(key);
        int reachBlocks = reachOf(level, data, t, key);
        BlockState mat = ModBlocks.LAVA_FERN_MAT.get().defaultBlockState();
        int placed = 0;
        for (int c = 0; c + 1 < owned.length && cap[0] > 0; c += 2) {
            int x = owned[c], z = owned[c + 1];
            int i = x - x0, j = z - z0;
            int d = dist[i * size + j];
            if (d == 0) continue;
            int limit = t.floraMatMin + (int) (NetherGround.hash01(seed, x, 0, z, SALT_MATLEN) * (t.floraMatMax - t.floraMatMin + 1));
            if (d > Math.ceil(progress * limit)) continue; // 段階 1 の間に岸から沖へ
            if (!NetherGround.inFront(seed, x, z, origin, progress, reachBlocks)) continue;
            if (!NetherJitter.patch(seed, x, 9, z, t.floraMatShare, 3, SALT_MAT)) continue;
            if (!level.getBlockState(p.set(x, s + 1, z)).isAir()) continue;
            NetherGround.put(level, p, mat);
            NetherFloraHooks.blocks++;
            cap[0]--;
            placed++;
        }
        return placed;
    }

    /** 段階 4 以降: 浮き草を同じ列のいちばん上の水面へ置き直す(水が来たら浮き上がる)。 */
    static void floatMats(ServerLevel level, LevelChunk chunk) {
        int s = NetherLava.surfaceY(level);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = x0 + dx, z = z0 + dz;
                for (int y = s - 3; y <= s + 2; y++) {
                    if (!chunk.getBlockState(p.set(x, y, z)).is(ModBlocks.LAVA_FERN_MAT.get())) continue;
                    // 同じ列の水面(水の源の真上が空気)を上から探す
                    int top = Integer.MIN_VALUE;
                    for (int wy = s + 1; wy >= s - 4; wy--) {
                        FluidState f = level.getFluidState(p.set(x, wy, z));
                        if (f.is(Fluids.WATER) && f.isSource() && level.getBlockState(p.set(x, wy + 1, z)).isAir()
                                || f.is(Fluids.WATER) && f.isSource() && wy + 1 == y) {
                            top = wy;
                            break;
                        }
                    }
                    if (top == Integer.MIN_VALUE || top + 1 == y) break;
                    NetherGround.put(level, p.set(x, y, z), Blocks.AIR.defaultBlockState());
                    NetherGround.put(level, p.set(x, top + 1, z), ModBlocks.LAVA_FERN_MAT.get().defaultBlockState());
                    break;
                }
            }
        }
    }

    // ==========================================================================================
    // テスト用
    // ==========================================================================================

    /** テスト用: そのチャンクの植物を段階 1 の {@code progress} まで描く(太い根は {@code rootFraction})。 */
    public static void growForTests(ServerLevel level, long key, double rootFraction, double progress) {
        INSTANCE.checkLevel(level);
        int[] cap = {Integer.MAX_VALUE};
        NetherLedgerData data = NetherLedgerData.get(level);
        NetherSpreadTable t = DesignData.netherSpread();
        INSTANCE.grow(level, data, t, key, rootFraction, progress, cap);
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
        if (chunk != null) INSTANCE.strangle(level, data, t, chunk, progress, cap);
        INSTANCE.mats(level, data, t, key, progress, cap);
    }

    /** テスト用: 覚えを捨てる(同じ座標を描き直す)。 */
    public static void forgetForTests(long key) {
        INSTANCE.forget(key);
    }

    /** テスト用: そのチャンクの太い根・枝の根のブロックの位置。 */
    public static long[] pathForTests(ServerLevel level, long key, boolean branches) {
        INSTANCE.checkLevel(level);
        Path p = branches ? INSTANCE.branchPaths.get(key) : INSTANCE.mainPaths.get(key);
        return p == null ? new long[0] : p.pos().clone();
    }

    /** テスト用: 浮き草を 1 枚置く(段階の当てはめと同じ置き方)。 */
    public static boolean canMatStand(ServerLevel level, BlockPos pos) {
        return LavaFernMatBlock.canStandOn(level, pos.below());
    }
}
