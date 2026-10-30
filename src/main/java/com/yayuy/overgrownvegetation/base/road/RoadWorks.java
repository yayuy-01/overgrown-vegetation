package com.yayuy.overgrownvegetation.base.road;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.base.build.BaseEconomy;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.plan.Lobe;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.BaseRoadConfig;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 同盟軍の道の工事(順 2 の着手順 7、2026-10-08)。道を引く相手を選んで道筋を探し({@link RoadPlanner})、両方の基地から貯蓄を使って進め、
 * 読み込まれたチャンクにブロックを置く。
 *
 * <p><b>ユーザー指定</b>: 工兵がほかの基地への道を作る・妨害灯を支点に入り口から中継・山・崖・急勾配を避けて緩い坂(10-01)/
 * 工事は離れていても進み、工事の先頭が近くにあれば工兵が見える・各基地から近い順に 2 本まで・幅 3 と妨害灯 24 ごと(10-08)。
 *
 * <p>Claude 仮決め:
 * <ul>
 *     <li>工事は汚染の暦で進む(眠った夜も進む)。1 つの基地が 1 日 {@code blocksPerDay} ブロック、1 ブロックごとに貯蓄 {@code costPerBlock}。
 *     貯蓄が上限の {@code minStockFraction} を下回る基地は進めない。陥落した基地の側は止まる。</li>
 *     <li>ブロックは、読み込まれていて周り 8 チャンクも読み込まれているチャンクにだけ置く(読み込みの縁で隣を待たせない)。
 *     チャンクを読み込むたびに置き直す(壊れた所・苔に覆われた所を直す)。過繁茂で守られていないチャンクは直さない(道は切れたまま)。</li>
 *     <li>払う・埋める・置き換えるのは自然のブロック(土・石・砂・木・葉・草・雪・汚染されたブロック)と空気・水だけ。中身のあるブロック・人の建てた物は触らない
 *     (台帳に載っていない基地・村を突き抜けない)。木は道の上にかかった幹を上まで切る(葉は自然に枯れる)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class RoadWorks {

    /** 工事を進める間隔(tick)。 */
    private static final int ADVANCE_INTERVAL = 20;
    /** 道を引く相手を探す間隔(tick)。 */
    private static final int PLAN_INTERVAL = 200;
    /** 1 tick にブロックを置くチャンクの上限。 */
    private static final int CHUNKS_PER_TICK = 2;
    /** 1 tick に見る待ち行列の上限(周りが読み込まれるのを待っているチャンクを飛ばす分)。 */
    private static final int JOB_SCAN_PER_TICK = 64;
    /** つながりの見え方が変わるほど進んだかの区切り(この点数ごとに数え直しの目印を進める)。 */
    private static final int RECOUNT_STRIDE = 16;
    /** ほかの基地の浄化圏の箱から、道を通さない幅(鉄条網の外まで)。 */
    private static final int BASE_MARGIN = 6;
    /** 橋脚を立てる深さの上限。 */
    private static final int PILLAR_DEPTH = 64;
    /** 切り通しで、頭上より上を空が開けるまで払う段数の上限。 */
    private static final int OPEN_CUT = 32;

    /** ブロックを置くチャンク({@link ChunkPos#asLong})。 */
    private static final LongLinkedOpenHashSet JOBS = new LongLinkedOpenHashSet();
    /** チャンク → そこにマスのある道の番号(道が増えた・消えたら作り直す)。 */
    private static Long2ObjectOpenHashMap<IntArrayList> chunkRoads = new Long2ObjectOpenHashMap<>();
    private static int indexedStamp = -1;
    private static int indexedWidth = -1;
    private static int indexedLamp = -1;

    // ---------- 毎 tick ----------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        Runnable done;
        while ((done = PLAN_DONE.poll()) != null) {
            done.run();
        }
        if (server instanceof GameTestServer) {
            return; // GameTest は自分で呼ぶ
        }
        if (!MEASURE.isEmpty()) {
            RoadLedger ledger = RoadLedger.get(server);
            BaseSites data = BaseSites.get(server);
            for (int id : MEASURE) {
                BaseSite site = data.byId(id);
                int top = site == null ? RoadPlanner.UNKNOWN : measurePort(server.overworld(), site);
                if (top != RoadPlanner.UNKNOWN) {
                    ledger.setPortTop(id, top);
                }
            }
            MEASURE.clear();
        }
        int tick = server.getTickCount();
        if (tick % ADVANCE_INTERVAL == 0) {
            advance(server, PhaseData.clockNow(server.overworld()));
            RoadCrews.tick(server);
        }
        if (tick % PLAN_INTERVAL == 0) {
            maybePlan(server);
        }
        processJobs(server.overworld());
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        ChunkPos cp = chunk.getPos();
        // 正門の道の先がこのチャンクにある基地は、tick の終わりに路面の高さを測る(道の両端をそろえる)
        IntArrayList ports = portChunks(level.getServer()).get(cp.toLong());
        if (ports != null) {
            for (int i = 0; i < ports.size(); i++) {
                MEASURE.add(ports.getInt(i));
            }
        }
        RoadLedger ledger = RoadLedger.get(level.getServer());
        if (ledger.roads().isEmpty()) {
            return;
        }
        Long2ObjectOpenHashMap<IntArrayList> index = index(ledger);
        // 自分と、自分を待っていた周りのチャンク
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = ChunkPos.asLong(cp.x + dx, cp.z + dz);
                if (index.containsKey(key)) {
                    JOBS.add(key);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        JOBS.clear();
        chunkRoads = new Long2ObjectOpenHashMap<>();
        indexedStamp = -1;
        generation++;
        planning = false;
        MEASURE.clear();
        portChunks = new Long2ObjectOpenHashMap<>();
        portChunksStamp = -1;
    }

    // ---------- 正門の道の先の高さ ----------

    /** 路面の高さを測る基地(チャンクが読み込まれた tick の終わりに)。 */
    private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet MEASURE = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
    /** 正門の道の先のチャンク → その基地(基地が増えた・消えたら作り直す)。 */
    private static Long2ObjectOpenHashMap<IntArrayList> portChunks = new Long2ObjectOpenHashMap<>();
    private static long portChunksStamp = -1;

    private static Long2ObjectOpenHashMap<IntArrayList> portChunks(MinecraftServer server) {
        List<BaseSite> all = BaseSites.get(server).all();
        long stamp = all.size();
        for (BaseSite s : all) {
            stamp = stamp * 31 + s.id * 8L + s.level(); // 増えた・消えた・広げた(正門の道が伸びる)で変わる
        }
        if (stamp != portChunksStamp) {
            Long2ObjectOpenHashMap<IntArrayList> out = new Long2ObjectOpenHashMap<>();
            for (BaseSite s : all) {
                if (s.dimension.equals(Level.OVERWORLD)) {
                    BlockPos end = BaseRoads.portEnd(s, 0);
                    out.computeIfAbsent(ChunkPos.asLong(end.getX() >> 4, end.getZ() >> 4), k -> new IntArrayList()).add(s.id);
                }
            }
            portChunks = out;
            portChunksStamp = stamp;
        }
        return portChunks;
    }

    /** 正門の道の先の路面の高さ(そこが読み込まれていて、工事が済んでいるとき。ほかは {@link RoadPlanner#UNKNOWN})。 */
    static int measurePort(ServerLevel level, BaseSite site) {
        if (site.building() || !site.dimension.equals(Level.OVERWORLD)) {
            return RoadPlanner.UNKNOWN;
        }
        BlockPos end = BaseRoads.portEnd(site, 0);
        if (!LoadedChunks.loaded(level, end.getX() >> 4, end.getZ() >> 4)) {
            return RoadPlanner.UNKNOWN;
        }
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, end.getX(), end.getZ());
    }

    /** 道の端の高さ: 今読み込まれていれば測り、ほかは前に測った値(無ければ分からない)。 */
    private static int portTop(ServerLevel level, BaseSite site) {
        int now = measurePort(level, site);
        RoadLedger ledger = RoadLedger.get(level.getServer());
        if (now != RoadPlanner.UNKNOWN) {
            ledger.setPortTop(site.id, now);
            return now;
        }
        return ledger.portTop(site.id);
    }

    // ---------- 工事を進める ----------

    /** 汚染の暦 {@code now} まで工事を進める(両方の基地から、貯蓄を払って)。 */
    public static void advance(MinecraftServer server, long now) {
        RoadLedger ledger = RoadLedger.get(server);
        if (ledger.roads().isEmpty()) {
            return;
        }
        BaseSites data = BaseSites.get(server);
        BaseRoadConfig cfg = DesignData.defense().baseRoad();
        for (AlliedRoad r : List.copyOf(ledger.roads())) {
            BaseSite a = data.byId(r.siteA);
            BaseSite b = data.byId(r.siteB);
            if (a == null || b == null) {
                ledger.removeRoad(r); // 基地が台帳から消えた
                continue;
            }
            long dt = now - r.lastClock;
            r.lastClock = now;
            if (r.done() || dt <= 0) {
                continue;
            }
            double want = cfg.blocksPerDay * Math.min(dt, 30L * BaseEconomy.DAY) / BaseEconomy.DAY;
            int beforeA = (int) r.builtA;
            int beforeB = (int) r.builtB;
            int beforeStage = (int) ((r.builtA + r.builtB) / RECOUNT_STRIDE);
            double remaining = r.length() - r.builtA - r.builtB;
            // 両方の基地が進められるなら、残りを半分ずつ(長く留守にした後のまとめての工事で、A の側だけが全部を取らない)
            double shareA = canPay(b, cfg) ? Math.min(want, remaining / 2.0D) : Math.min(want, remaining);
            double gotA = pay(data, a, shareA, cfg, r, true);
            r.builtA += gotA;
            remaining -= gotA;
            double gotB = pay(data, b, Math.min(want, Math.max(0.0D, remaining)), cfg, r, false);
            r.builtB += gotB;
            remaining -= gotB;
            if (gotA >= shareA - 1e-9 && remaining > 1e-9 && shareA < want) {
                // B が払いきれず残った分を、まだ進める A が引き受ける
                double more = pay(data, a, Math.min(want - shareA, remaining), cfg, r, true);
                r.builtA += more;
                gotA += more;
            }
            if (gotA + gotB <= 0.0D) {
                continue;
            }
            ledger.progressed();
            queueBuilt(server.overworld(), r, beforeA, (int) r.builtA, true, cfg);
            queueBuilt(server.overworld(), r, beforeB, (int) r.builtB, false, cfg);
            if (r.done() || (int) ((r.builtA + r.builtB) / RECOUNT_STRIDE) != beforeStage) {
                ledger.roadsChanged();
            }
        }
    }

    /** 工事を進められる基地か(陥落していない・貯蓄が下限より上)。 */
    static boolean canPay(BaseSite site, BaseRoadConfig cfg) {
        return !site.fallen() && site.stock() > stockFloor(site, cfg);
    }

    /** 工事に使わずに残す貯蓄(上限 × {@code minStockFraction})。 */
    static int stockFloor(BaseSite site, BaseRoadConfig cfg) {
        return (int) Math.ceil(DesignData.defense().baseStockCap(site.level()) * cfg.minStockFraction);
    }

    /** {@code want} 点ぶん進めたい。払えた分だけ進める(戻り値 = 進めた点の数)。 */
    private static double pay(BaseSites data, BaseSite site, double want, BaseRoadConfig cfg, AlliedRoad r, boolean fromA) {
        if (want <= 0.0D || !canPay(site, cfg)) {
            return 0.0D;
        }
        int floor = stockFloor(site, cfg);
        if (cfg.costPerBlock <= 0.0D) {
            return want;
        }
        double carry = fromA ? r.carryA : r.carryB;
        double total = carry + want * cfg.costPerBlock;
        int due = (int) Math.floor(total);
        int paid = BaseEconomy.spend(data, site, due, floor);
        double got;
        if (paid >= due) {
            got = want;
            carry = total - due;
        } else {
            got = (paid + carry) / cfg.costPerBlock;
            carry = 0.0D;
        }
        if (fromA) {
            r.carryA = carry;
        } else {
            r.carryB = carry;
        }
        return Math.max(0.0D, got);
    }

    /** 新しく済んだ点のまわりの、読み込まれているチャンクをブロックを置く列へ。 */
    private static void queueBuilt(ServerLevel level, AlliedRoad r, int from, int to, boolean fromA, BaseRoadConfig cfg) {
        if (to <= from) {
            return;
        }
        int n = r.length();
        int lo = fromA ? from : Math.max(0, n - to);
        int hi = fromA ? Math.min(n, to) : n - from;
        int reach = (cfg.width + 3) / 2 + 1;
        for (int i = lo; i < hi; i++) {
            int x = r.pointX(i);
            int z = r.pointZ(i);
            for (int dx = -reach; dx <= reach; dx += reach) {
                for (int dz = -reach; dz <= reach; dz += reach) {
                    int cx = (x + dx) >> 4;
                    int cz = (z + dz) >> 4;
                    if (LoadedChunks.loaded(level, cx, cz)) {
                        JOBS.add(ChunkPos.asLong(cx, cz));
                    }
                }
            }
        }
    }

    /** チャンク → そこにマスのある道。 */
    static Long2ObjectOpenHashMap<IntArrayList> index(RoadLedger ledger) {
        BaseRoadConfig cfg = DesignData.defense().baseRoad();
        if (indexedStamp != ledger.roadsStamp() || indexedWidth != cfg.width || indexedLamp != cfg.lampSpacing) {
            Long2ObjectOpenHashMap<IntArrayList> out = new Long2ObjectOpenHashMap<>();
            for (AlliedRoad r : ledger.roads()) {
                for (long key : r.cells(cfg.width, cfg.lampSpacing).byChunk().keySet()) {
                    out.computeIfAbsent(key, k -> new IntArrayList()).add(r.id);
                }
            }
            chunkRoads = out;
            indexedStamp = ledger.roadsStamp();
            indexedWidth = cfg.width;
            indexedLamp = cfg.lampSpacing;
        }
        return chunkRoads;
    }

    // ---------- ブロックを置く ----------

    private static void processJobs(ServerLevel level) {
        if (JOBS.isEmpty() || !TickBudget.mayStart(PerfJob.BASE_ROADS)) {
            return;
        }
        long t0 = TickBudget.begin();
        int done = 0;
        int scanned = 0;
        for (LongIterator it = JOBS.iterator(); it.hasNext() && scanned < JOB_SCAN_PER_TICK; scanned++) {
            long key = it.nextLong();
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (!LoadedChunks.loaded(level, cx, cz)) {
                it.remove(); // 読み込まれたときにまた積む
                continue;
            }
            if (!LoadedChunks.neighbourhoodLoaded(level, cx, cz)) {
                continue; // 周りを待つ
            }
            it.remove();
            materializeChunk(level, key);
            done++;
            if (done >= CHUNKS_PER_TICK || !TickBudget.timeLeft()) {
                break;
            }
        }
        TickBudget.end(PerfJob.BASE_ROADS, t0, done);
    }

    /** このチャンクの、工事の済んだマスにブロックを置く(置いてあれば何もしない)。過繁茂で守られていないチャンクは触らない。 */
    public static int materializeChunk(ServerLevel level, long chunkKey) {
        RoadLedger ledger = RoadLedger.get(level.getServer());
        IntArrayList ids = index(ledger).get(chunkKey);
        if (ids == null || cut(level, chunkKey)) {
            return 0;
        }
        BaseRoadConfig cfg = DesignData.defense().baseRoad();
        int placed = 0;
        for (int i = 0; i < ids.size(); i++) {
            AlliedRoad r = ledger.road(ids.getInt(i));
            if (r == null) {
                continue;
            }
            AlliedRoad.Cells cells = r.cells(cfg.width, cfg.lampSpacing);
            IntArrayList list = cells.byChunk().get(chunkKey);
            if (list == null) {
                continue;
            }
            for (int j = 0; j < list.size(); j++) {
                int c = list.getInt(j);
                if (r.isBuilt(cells.index(c))) {
                    placed += placeCell(level, cells, c, cfg);
                }
            }
        }
        return placed;
    }

    /** 過繁茂(汚染の台帳)で、稼働中の胞子除去装置に守られていないチャンク = 道が切れている所。 */
    static boolean cut(ServerLevel level, long chunkKey) {
        if (BaseEconomy.ledgerForTests != null) {
            return BaseEconomy.ledgerForTests.test(chunkKey)
                    && (BaseEconomy.guardedForTests == null || !BaseEconomy.guardedForTests.test(chunkKey));
        }
        return PhaseData.get(level).isOvergrownKey(chunkKey)
                && !SporeDeviceData.guardsChunkFromLedger(level, ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
    }

    /** マス 1 つを置く。置いたブロックの数を返す。 */
    static int placeCell(ServerLevel level, AlliedRoad.Cells cells, int c, BaseRoadConfig cfg) {
        long p = cells.pos()[c];
        int x = BlockPos.getX(p);
        int y0 = BlockPos.getY(p);
        int z = BlockPos.getZ(p);
        int kind = cells.kind(c);
        boolean slab = cells.slab(c);
        boolean bridge = cells.bridge(c);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int n = 0;
        int top = y0 + (slab ? 1 : 0);
        n += clearAbove(level, m, x, z, top + 1, y0 + cfg.headroom + (slab ? 1 : 0));
        if (kind == AlliedRoad.ASPHALT) {
            n += put(level, m.set(x, y0, z), BaseBlocks.ASPHALT.get().defaultBlockState());
            if (slab) {
                n += put(level, m.set(x, y0 + 1, z), BaseBlocks.ASPHALT_SLAB.get().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
            }
            for (int y = y0; y <= top; y++) {
                if (OvergrownLayerData.isCovered(level, m.set(x, y, z))) {
                    OvergrownLayerData.uncover(level, m.immutable());
                }
            }
            // ノイズの地形に無い深い窪み(峡谷・洞窟の口)は埋めきらず、橋と同じく橋脚で渡す
            boolean deep = !bridge && gapDepth(level, m, x, y0 - 1, z, cfg.maxFill + 3) > cfg.maxFill + 2;
            if (!bridge && !deep) {
                n += fillBelow(level, m, x, y0 - 1, z, cfg.maxFill + 2);
            } else if (cells.pillar(c) || (deep && cells.pillarSpot(c))) {
                n += pillar(level, m, x, y0 - 1, z);
            }
            return n;
        }
        if (bridge || gapDepth(level, m, x, y0 - 1, z, cfg.maxFill + 3) > cfg.maxFill + 2) {
            return n; // 橋・深い窪みの上に路肩と灯りは無い
        }
        if (empty(level.getBlockState(m.set(x, y0, z)))) {
            level.setBlock(m, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            n++;
        }
        n += fillBelow(level, m, x, y0 - 1, z, cfg.maxFill + 2);
        if (kind == AlliedRoad.LAMP) {
            BlockState wall = BaseBlocks.REINFORCED_CONCRETE_WALL.get().defaultBlockState();
            for (int y = y0 + 1; y <= y0 + 2; y++) {
                if (empty(level.getBlockState(m.set(x, y, z)))) {
                    level.setBlock(m, wall, Block.UPDATE_ALL);
                    n++;
                }
            }
            if (empty(level.getBlockState(m.set(x, y0 + 3, z)))) {
                level.setBlock(m, BaseBlocks.JAMMING_LAMP.get().defaultBlockState(), Block.UPDATE_ALL);
                n++;
            }
        }
        return n;
    }

    /**
     * 道の上を払う(自然のブロックだけ。幹は上まで切る。水はそのまま)。{@code y2}(頭上の高さ)までは必ず払い、その上は空が開けるまで
     * ({@link #OPEN_CUT} 段まで)払う —— 深い切り通しの上に地面が庇のように残らない。頭上より上で人の建てた物・水に当たったら止める。
     */
    private static int clearAbove(ServerLevel level, BlockPos.MutableBlockPos m, int x, int z, int y1, int y2) {
        int n = 0;
        for (int y = y1; y <= y2 + OPEN_CUT; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir() || !s.getFluidState().isEmpty() || !natural(s)) {
                if (y > y2) {
                    break; // 空が開けた・払えない物
                }
                continue;
            }
            if (s.is(BlockTags.LOGS)) {
                for (int yy = y; yy < y + 48; yy++) {
                    if (!level.getBlockState(m.set(x, yy, z)).is(BlockTags.LOGS)) {
                        break;
                    }
                    level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    n++;
                }
                continue;
            }
            level.setBlock(m.set(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            n++;
        }
        return n;
    }

    /** 置き換えてよいなら置く(同じなら何もしない)。 */
    private static int put(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState cur = level.getBlockState(pos);
        if (cur == state || cur.is(state.getBlock()) && !(state.getBlock() instanceof SlabBlock)) {
            return 0;
        }
        if (cur.isAir() || natural(cur)) {
            level.setBlock(pos, state, Block.UPDATE_ALL);
            return 1;
        }
        return 0;
    }

    /** 下の窪みを土で埋める(固いブロックに着くまで、深さ {@code max} まで)。 */
    private static int fillBelow(ServerLevel level, BlockPos.MutableBlockPos m, int x, int yTop, int z, int max) {
        int n = 0;
        for (int d = 0; d < max; d++) {
            int y = yTop - d;
            if (y < level.getMinBuildHeight()) {
                break;
            }
            if (!empty(level.getBlockState(m.set(x, y, z)))) {
                break;
            }
            level.setBlock(m, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
            n++;
        }
        return n;
    }

    /** {@code yTop} から下へ、空いている(置けば埋まる)マスが続く数({@code limit} で打ち切る)。 */
    private static int gapDepth(ServerLevel level, BlockPos.MutableBlockPos m, int x, int yTop, int z, int limit) {
        int d = 0;
        while (d < limit && yTop - d >= level.getMinBuildHeight() && empty(level.getBlockState(m.set(x, yTop - d, z)))) {
            d++;
        }
        return d;
    }

    /** 橋脚(鉄筋コンクリート)を底まで。 */
    private static int pillar(ServerLevel level, BlockPos.MutableBlockPos m, int x, int yTop, int z) {
        int n = 0;
        BlockState concrete = BaseBlocks.REINFORCED_CONCRETE.get().defaultBlockState();
        for (int d = 0; d < PILLAR_DEPTH; d++) {
            int y = yTop - d;
            if (y < level.getMinBuildHeight()) {
                break;
            }
            if (!empty(level.getBlockState(m.set(x, y, z)))) {
                break;
            }
            level.setBlock(m, concrete, Block.UPDATE_ALL);
            n++;
        }
        return n;
    }

    /** 空気・草・水など、置けば埋まるもの(中身のあるブロックは除く)。 */
    private static boolean empty(BlockState s) {
        return s.isAir() || (s.canBeReplaced() && !s.hasBlockEntity());
    }

    /** 道のために払ってよい・置き換えてよい自然のブロックか。人の建てた物・中身のあるブロック・壊せないブロックは false。 */
    static boolean natural(BlockState s) {
        if (s.hasBlockEntity() || s.getBlock().defaultDestroyTime() < 0.0F || s.is(ModTags.ROAD_BLOCKS)) {
            return false;
        }
        return s.canBeReplaced() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND)
                || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.TERRACOTTA) || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE)
                || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(Tags.Blocks.GRAVELS) || s.is(Tags.Blocks.ORES)
                || s.is(Tags.Blocks.STONES) || s.is(Blocks.CLAY) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.MUD)
                || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.VINE) || s.is(Blocks.CALCITE)
                || s.is(Blocks.POINTED_DRIPSTONE) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.CACTUS) || s.is(Blocks.SUGAR_CANE)
                || s.is(Blocks.BAMBOO) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK)
                || s.is(ModTags.CORRUPTED);
    }

    // ---------- 道を引く相手と道筋 ----------

    /** 各基地から近い順に {@code maxLinks} 本(ユーザー指定 10-08)。道の無い組を 1 つ見つけたら道筋を探し始める。 */
    static void maybePlan(MinecraftServer server) {
        if (planning) {
            return;
        }
        RoadLedger ledger = RoadLedger.get(server);
        BaseRoadConfig cfg = DesignData.defense().baseRoad();
        long today = BaseEconomy.today(server);
        List<BaseSite> sites = candidates(server);
        for (BaseSite s : sites) {
            if (s.building()) {
                continue;
            }
            for (BaseSite t : nearest(s, sites, cfg)) {
                if (ledger.roadBetween(s.id, t.id) != null || ledger.retryDay(s.id, t.id) > today) {
                    continue;
                }
                startPlan(server, s, t, null);
                return;
            }
        }
    }

    /** 道を引ける基地(オーバーワールド・陥落していない)。番号の順。 */
    static List<BaseSite> candidates(MinecraftServer server) {
        List<BaseSite> out = new ArrayList<>();
        for (BaseSite s : BaseSites.get(server).all()) {
            if (s.dimension.equals(Level.OVERWORLD) && !s.fallen()) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingInt(s -> s.id));
        return out;
    }

    /** {@code s} から近い順に {@code maxLinks} 個(直線 {@code maxDistance} まで)。 */
    static List<BaseSite> nearest(BaseSite s, List<BaseSite> sites, BaseRoadConfig cfg) {
        List<BaseSite> out = new ArrayList<>();
        for (BaseSite t : sites) {
            if (t != s && dist2(s, t) <= (double) cfg.maxDistance * cfg.maxDistance) {
                out.add(t);
            }
        }
        out.sort(Comparator.comparingDouble((BaseSite t) -> dist2(s, t)).thenComparingInt(t -> t.id));
        return out.subList(0, Math.min(cfg.maxLinks, out.size()));
    }

    private static double dist2(BaseSite a, BaseSite b) {
        double dx = a.center.getX() - b.center.getX();
        double dz = a.center.getZ() - b.center.getZ();
        return dx * dx + dz * dz;
    }

    /** 正門の外へ向かう向き {dx, dz}。 */
    static int[] facing(BaseSite site) {
        BlockPos p0 = site.toWorld(0, 0, 0);
        BlockPos p1 = site.toWorld(0, 0, 1);
        return new int[]{p1.getX() - p0.getX(), p1.getZ() - p0.getZ()};
    }

    /** 道を仕上げる(開発用・GameTest)。読み込まれている所から置いていく。 */
    public static void finish(MinecraftServer server, AlliedRoad r) {
        int beforeA = (int) r.builtA;
        r.builtA = r.length() - r.builtB;
        RoadLedger.get(server).roadsChanged();
        queueBuilt(server.overworld(), r, beforeA, (int) r.builtA, true, DesignData.defense().baseRoad());
    }

    /** GameTest: 箱 {@code box} の中のマスだけ置く(隣のテスト場のチャンクに触らない)。 */
    public static int materializeWithin(ServerLevel level, AlliedRoad r, net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        BaseRoadConfig cfg = DesignData.defense().baseRoad();
        AlliedRoad.Cells cells = r.cells(cfg.width, cfg.lampSpacing);
        int placed = 0;
        for (int c = 0; c < cells.size(); c++) {
            long p = cells.pos()[c];
            if (r.isBuilt(cells.index(c)) && box.isInside(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p))) {
                placed += placeCell(level, cells, c, cfg);
            }
        }
        return placed;
    }

    /** 道筋を探す材料を作る(サーバースレッド)。{@code terrain} が null なら世界生成のノイズ。 */
    public static RoadPlanner.Request request(ServerLevel level, BaseSite a, BaseSite b, BaseRoadConfig cfg, @Nullable RoadTerrain terrain) {
        int[] da = facing(a);
        int[] db = facing(b);
        BlockPos ea = BaseRoads.portEnd(a, 0);
        BlockPos eb = BaseRoads.portEnd(b, 0);
        int sx = ea.getX() + da[0];
        int sz = ea.getZ() + da[1];
        int gx = eb.getX() + db[0];
        int gz = eb.getZ() + db[1];
        List<int[]> obstacles = new ArrayList<>();
        for (BaseSite s : BaseSites.get(level.getServer()).all()) {
            if (!s.dimension.equals(Level.OVERWORLD)) {
                continue;
            }
            for (Lobe l : s.plan(s.level()).lobes) {
                BlockPos c0 = s.toWorld(l.x0(), 0, l.z0());
                BlockPos c1 = s.toWorld(l.x1(), 0, l.z1());
                obstacles.add(new int[]{Math.min(c0.getX(), c1.getX()) - BASE_MARGIN, Math.min(c0.getZ(), c1.getZ()) - BASE_MARGIN,
                        Math.max(c0.getX(), c1.getX()) + BASE_MARGIN, Math.max(c0.getZ(), c1.getZ()) + BASE_MARGIN});
            }
        }
        // 切れたチャンク(過繁茂で守られていない)を、探す範囲の分だけ写す
        double dist = Math.hypot(gx - sx, gz - sz);
        int margin = (int) Math.max(128, dist / 2) + 2 * RoadPlanner.STEP;
        int cx0 = (Math.min(sx, gx) - margin) >> 4;
        int cz0 = (Math.min(sz, gz) - margin) >> 4;
        int cx1 = (Math.max(sx, gx) + margin) >> 4;
        int cz1 = (Math.max(sz, gz) + margin) >> 4;
        LongOpenHashSet cutSet = new LongOpenHashSet();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                if (cut(level, key)) {
                    cutSet.add(key);
                }
            }
        }
        return new RoadPlanner.Request(sx, sz, da[0], da[1], gx, gz, db[0], db[1], obstacles, cutSet::contains,
                terrain != null ? terrain : RoadTerrain.noise(level), cfg,
                terrain != null ? RoadPlanner.UNKNOWN : portTop(level, a), terrain != null ? RoadPlanner.UNKNOWN : portTop(level, b));
    }

    /**
     * 裏のスレッドで道筋を探し、見つかれば台帳に載せる(見つからなければ {@code retryDays} 日あとに探し直す)。
     * 地形のノイズを何千点も読むので数秒〜数十秒かかる。台帳の広がりなどの共有の裏のスレッド({@code BackgroundTask})を塞がないよう、道だけのスレッドで。
     */
    static boolean startPlan(MinecraftServer server, BaseSite a, BaseSite b, @Nullable CommandSourceStack reply) {
        if (planning) {
            return false;
        }
        ServerLevel level = server.overworld();
        BaseRoadConfig cfg = DesignData.defense().baseRoad();
        RoadPlanner.Request req = request(level, a, b, cfg, null);
        long today = BaseEconomy.today(server);
        int aid = a.id;
        int bid = b.id;
        java.util.function.Consumer<RoadPlanner.Outcome> apply = outcome -> {
            RoadLedger ledger = RoadLedger.get(server);
            BaseSites data = BaseSites.get(server);
            if (data.byId(aid) == null || data.byId(bid) == null || ledger.roadBetween(aid, bid) != null) {
                return;
            }
            RoadPlanner.Plan plan = outcome.plan();
            if (plan == null) {
                ledger.setRetry(aid, bid, today + cfg.retryDays);
                OvergrownVegetation.LOGGER.info("同盟軍の道 #{} → #{}: {}", aid, bid, outcome.failure());
                if (reply != null) {
                    reply.sendSuccess(() -> Component.literal("#" + aid + " → #" + bid + " の道筋が見つからなかった: " + outcome.failure()
                            + "(" + cfg.retryDays + " 日あとに探し直す)"), false);
                }
                return;
            }
            AlliedRoad r = ledger.addRoad(aid, bid, plan, PhaseData.clockNow(server.overworld()));
            OvergrownVegetation.LOGGER.info("同盟軍の道 #{}: #{} → #{} 長さ {}・橋 {} 点", r.id, aid, bid, r.length(), r.bridgePoints());
            if (reply != null) {
                reply.sendSuccess(() -> Component.literal("道 #" + r.id + " を引いた: #" + aid + " → #" + bid + "(長さ " + r.length()
                        + "・橋 " + r.bridgePoints() + " 点)"), true);
            }
        };
        if (server instanceof GameTestServer) {
            apply.accept(RoadPlanner.planWithReason(req));
            return true;
        }
        planning = true;
        int gen = generation;
        PLANNER.execute(() -> {
            long t0 = System.nanoTime();
            RoadPlanner.Outcome outcome;
            try {
                outcome = RoadPlanner.planWithReason(req);
            } catch (Throwable e) {
                OvergrownVegetation.LOGGER.error("同盟軍の道筋を探すのに失敗: #{} → #{}", aid, bid, e);
                outcome = new RoadPlanner.Outcome(null, "探している途中でエラー(" + e + ")");
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            RoadPlanner.Outcome done = outcome;
            PLAN_DONE.add(() -> {
                planning = false;
                if (gen == generation) {
                    OvergrownVegetation.LOGGER.debug("同盟軍の道筋 #{} → #{}: {} ms", aid, bid, ms);
                    apply.accept(done);
                }
            });
        });
        return true;
    }

    /** 道筋を探す専用のスレッド(1 本)。 */
    private static final java.util.concurrent.ExecutorService PLANNER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Overgrown Vegetation roads");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    /** 探し終えて、サーバースレッドで当てはめるのを待っているもの。 */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> PLAN_DONE = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile boolean planning;
    /** ワールドごとの番号(閉じたら進め、前のワールドで探した答えを捨てる)。 */
    private static volatile int generation;

    // ---------- 表示・コマンド ----------

    /** 工事中の道と、汚染で切れた道の 1 行(管理端末)。どちらも無ければ null。 */
    @Nullable
    public static Component describeWorks(MinecraftServer server, BaseSite site) {
        RoadLedger ledger = RoadLedger.get(server);
        BaseRoadConfig cfg = DesignData.defense().baseRoad();
        StringBuilder building = new StringBuilder();
        StringBuilder cuts = new StringBuilder();
        int n = 0;
        for (AlliedRoad r : ledger.roads()) {
            if (!r.touches(site.id)) {
                continue;
            }
            int other = r.siteA == site.id ? r.siteB : r.siteA;
            if (!r.done()) {
                if (n > 0) {
                    building.append(' ');
                }
                building.append('#').append(other).append(' ').append(Math.round(r.progress() * 100)).append('%');
                n++;
                continue;
            }
            BlockPos at = cutAt(server.overworld(), r, cfg);
            if (at != null) {
                if (!cuts.isEmpty()) {
                    cuts.append(' ');
                }
                cuts.append('#').append(other).append(" (").append(at.getX()).append(", ").append(at.getZ()).append(')');
            }
        }
        Component line = null;
        if (n > 0) {
            line = Component.translatable("message.overgrown_vegetation.base.roads_building", n, building.toString());
        }
        if (!cuts.isEmpty()) {
            Component cut = Component.translatable("message.overgrown_vegetation.base.roads_cut", cuts.toString());
            line = line == null ? cut : Component.empty().append(line).append(" ").append(cut);
        }
        return line;
    }

    /**
     * 仕上がった道が汚染で切れている所(切れたチャンクの真ん中。いくつもあれば、見つけた最初の 1 つ)。切れていなければ null。
     * プレイヤーの道は 1 本ずつに分けていないので出さない(つながりの数え方には入っている)。
     */
    @Nullable
    public static BlockPos cutAt(ServerLevel level, AlliedRoad r, BaseRoadConfig cfg) {
        for (long key : r.cells(cfg.width, cfg.lampSpacing).byChunk().keySet()) {
            if (cut(level, key)) {
                return new BlockPos(ChunkPos.getX(key) * 16 + 8, 0, ChunkPos.getZ(key) * 16 + 8);
            }
        }
        return null;
    }

    /** コマンドの一覧に出す道の行。 */
    static List<Component> listRoads(MinecraftServer server) {
        List<Component> out = new ArrayList<>();
        RoadLedger ledger = RoadLedger.get(server);
        BaseRoadConfig cfg = DesignData.defense().baseRoad();
        for (AlliedRoad r : ledger.roads()) {
            int f = r.frontier(true);
            String front = f < 0 ? "済み" : "先頭 (" + r.pointX(f) + ", " + r.pointZ(f) + ")";
            BlockPos at = r.done() ? cutAt(server.overworld(), r, cfg) : null;
            String cut = at == null ? "" : "・汚染で切れている (" + at.getX() + ", " + at.getZ() + " 付近)";
            out.add(Component.literal(String.format(Locale.ROOT, "道 #%d: #%d → #%d 長さ %d・工事 %d%%(A 側 %d / B 側 %d)・橋 %d 点・%s%s",
                    r.id, r.siteA, r.siteB, r.length(), Math.round(r.progress() * 100), (int) r.builtA, (int) r.builtB, r.bridgePoints(), front, cut)));
        }
        return out;
    }

    private RoadWorks() {
    }
}
