package com.yayuy.overgrownvegetation.base.road;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.build.BaseEconomy;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.plan.BasePlanner;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.perf.BackgroundTask;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongPredicate;

/**
 * 同盟軍の基地どうしの道とつながり(順 2 の着手順 7、2026-10-08)。
 *
 * <p><b>ユーザー指定</b>: 道でつながった基地 1 つで補給値 +15%(足し算)、補給基地とのつながりは +15% の代わりに +60%(10-01)/
 * 道は汚染で切れる(10-01)/ プレイヤーが敷いた道もつながりに数える(10-07)/ 数えるのは直接つながった基地だけ(10-08)。
 *
 * <p>Claude 仮決め:
 * <ul>
 *     <li>つながりは道の台帳({@link RoadLedger})のマスをたどって求める({@link RoadGraph})。ブロックは読まない(読み込まれていないチャンクに当たる)。
 *     数え直しは、マスが変わったとき(少し待ってまとめて)と 1 分おき(汚染の台帳の広がり・除去装置・陥落を拾う)。</li>
 *     <li>基地の口 = 正門から外へ伸びる幅 7 の道({@link BasePlanner#gateRoadEnd})。道がそこへ届いていれば、その基地に届いた。壁の途中は数えない。</li>
 *     <li>切れる: マスのあるチャンクが過繁茂(汚染の台帳)で、稼働中の胞子除去装置に守られていない / マスのアスファルトが苔に覆われた。
 *     苔の覆いと汚染の置き換えは {@link OvergrownLayerData} と {@code CorruptionRegistry#placeCorrupted} から知らされ、
 *     置いた・壊した・爆発・読み込みは tick の終わりにブロックを確かめる。</li>
 *     <li>補給基地とつながった側はその 1 本で +60%、補給基地の側は +15%。陥落した基地は数えない(口はあるので、その先へは通さない)。
 *     オーバーワールドだけ(汚染の台帳がオーバーワールドのもの)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BaseRoads {

    /** つながりを数え直す間隔(tick)。汚染の台帳の広がり・除去装置・基地の陥落はこれで拾う。 */
    private static final int RECOMPUTE_INTERVAL = 1200;
    /** マスが変わってから数え直すまでの間(tick。置く・壊すが続く間はまとめる)。 */
    private static final int RECOMPUTE_DEBOUNCE = 40;
    /** 1 tick に確かめるマスの上限(読み込んだチャンクにマスが多くても、残りは次の tick に)。 */
    private static final int RECHECKS_PER_TICK = 4096;

    /** 基地の番号 → 直接つながった基地の番号(最後に数えた答え)。 */
    private static Int2ObjectMap<IntSet> neighbors = Int2ObjectMaps.emptyMap();
    /** 最後に数えたときの台帳の版(−1 = まだ数えていない)。 */
    private static int computedVersion = -1;
    private static int lastComputeTick = Integer.MIN_VALUE / 2;
    /** 確かめるマス → 道のブロックなら台帳へ足すか(置いたときは true。壊した・爆発・汚染・読み込みは false = あるものを確かめるだけ)。 */
    private static final Long2BooleanOpenHashMap RECHECK = new Long2BooleanOpenHashMap();

    private static final BackgroundTask<Int2ObjectMap<IntSet>> TASK = new BackgroundTask<>("道のつながり");

    // ---------- 基地の口 ----------

    /** 基地の口の列(正門から外へ伸びる幅 7 の道。{@link ChunkPos#asLong} と同じ詰め方の世界の x, z)。 */
    public static long[] portColumns(BaseSite site) {
        int end = BasePlanner.gateRoadEnd(site.plan(site.level()).lobes);
        int half = BasePlanner.GATE_ROAD_HALF;
        LongArrayList out = new LongArrayList();
        for (int lz = BasePlanner.GATE_ROAD_START; lz <= end; lz++) {
            for (int lx = -half; lx <= half; lx++) {
                BlockPos w = site.toWorld(lx, 0, lz);
                out.add(ChunkPos.asLong(w.getX(), w.getZ()));
            }
        }
        return out.toLongArray();
    }

    /** 基地の口の先の端の真ん中(世界の座標、y は {@code y})。道を引き始める所・プレイヤーへの案内に使う。 */
    public static BlockPos portEnd(BaseSite site, int y) {
        return site.toWorld(0, y, BasePlanner.gateRoadEnd(site.plan(site.level()).lobes));
    }

    // ---------- 数える ----------

    /** 写しから数える材料: 数えるマス・基地の口・そのときの台帳の版。 */
    record Input(LongArrayList cells, List<RoadGraph.Port> ports, int version) {
    }

    /** サーバースレッドで写しを作る。覆われたマスと、切れたチャンク(過繁茂で守られていない)のマスは除く。 */
    static Input snapshot(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        RoadLedger ledger = RoadLedger.get(server);
        LongPredicate overgrown = BaseEconomy.ledgerForTests != null ? BaseEconomy.ledgerForTests : PhaseData.get(overworld)::isOvergrownKey;
        LongPredicate guarded;
        if (BaseEconomy.guardedForTests != null) {
            guarded = BaseEconomy.guardedForTests;
        } else {
            long[] guards = SporeDeviceData.ledgerGuards(overworld);
            guarded = k -> SporeDeviceData.guards(guards, ChunkPos.getX(k), ChunkPos.getZ(k));
        }
        Long2BooleanOpenHashMap cutChunks = new Long2BooleanOpenHashMap();
        LongArrayList cells = new LongArrayList(ledger.size());
        for (long p : ledger.cells()) {
            if (ledger.isCovered(p)) {
                continue;
            }
            long chunk = RoadLedger.chunkOf(p);
            boolean cut;
            if (cutChunks.containsKey(chunk)) {
                cut = cutChunks.get(chunk);
            } else {
                cut = overgrown.test(chunk) && !guarded.test(chunk);
                cutChunks.put(chunk, cut);
            }
            if (!cut) {
                cells.add(p);
            }
        }
        // 同盟軍の道: 工事の済んだアスファルトのマス(台帳の上のマス。ブロックは読まない)
        com.yayuy.overgrownvegetation.data.BaseRoadConfig cfg = DesignData.defense().baseRoad();
        for (AlliedRoad road : ledger.roads()) {
            AlliedRoad.Cells rc = road.cells(cfg.width, cfg.lampSpacing);
            for (int c = 0; c < rc.size(); c++) {
                if (rc.kind(c) != AlliedRoad.ASPHALT || !road.isBuilt(rc.index(c))) {
                    continue;
                }
                long base = rc.pos()[c];
                long p = rc.slab(c) ? BlockPos.asLong(BlockPos.getX(base), BlockPos.getY(base) + 1, BlockPos.getZ(base)) : base;
                long chunk = RoadLedger.chunkOf(p);
                boolean cut;
                if (cutChunks.containsKey(chunk)) {
                    cut = cutChunks.get(chunk);
                } else {
                    cut = overgrown.test(chunk) && !guarded.test(chunk);
                    cutChunks.put(chunk, cut);
                }
                if (!cut) {
                    cells.add(p);
                }
            }
        }
        List<RoadGraph.Port> ports = new ArrayList<>();
        for (BaseSite site : BaseSites.get(server).all()) {
            if (site.dimension.equals(Level.OVERWORLD)) {
                ports.add(new RoadGraph.Port(site.id, portColumns(site)));
            }
        }
        return new Input(cells, ports, ledger.version());
    }

    /** その場で数え直す(コマンド・GameTest)。 */
    public static void recomputeNow(MinecraftServer server) {
        Input in = snapshot(server);
        neighbors = RoadGraph.neighbors(in.cells(), in.ports());
        computedVersion = in.version();
        lastComputeTick = server.getTickCount();
    }

    /** 裏のスレッドで数え直す(答えは次の tick の頭に入る)。 */
    private static void recomputeLater(MinecraftServer server) {
        Input in = snapshot(server);
        lastComputeTick = server.getTickCount();
        TASK.start(server, () -> RoadGraph.neighbors(in.cells(), in.ports()), result -> {
            neighbors = result;
            computedVersion = in.version();
        });
    }

    /** 直接つながった基地(陥落したもの・台帳から消えたものを除く)。 */
    public static List<BaseSite> linked(MinecraftServer server, BaseSite site) {
        List<BaseSite> out = new ArrayList<>();
        if (site.fallen() || !site.dimension.equals(Level.OVERWORLD)) {
            return out;
        }
        IntSet ids = neighbors.get(site.id);
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        BaseSites data = BaseSites.get(server);
        for (int id : ids) {
            BaseSite other = data.byId(id);
            if (other != null && !other.fallen()) {
                out.add(other);
            }
        }
        out.sort((a, b) -> Integer.compare(a.id, b.id));
        return out;
    }

    /** つながりの割り増し(足し算): つながった基地 1 つで {@code connectionBonus}、相手が補給基地なら代わりに {@code supplyBaseBonus}。 */
    public static double bonus(MinecraftServer server, BaseSite site) {
        DefenseTable t = DesignData.defense();
        double sum = 0.0D;
        for (BaseSite other : linked(server, site)) {
            sum += other.type == BaseType.SUPPLY ? t.baseSupplyBaseBonus() : t.baseConnectionBonus();
        }
        return sum;
    }

    /** 管理端末・コマンドの 1 行(つながり + 工事中の道)。 */
    public static Component describe(MinecraftServer server, BaseSite site) {
        List<BaseSite> linked = linked(server, site);
        Component line;
        if (linked.isEmpty()) {
            line = Component.translatable("message.overgrown_vegetation.base.roads_none");
        } else {
            StringBuilder ids = new StringBuilder();
            for (BaseSite other : linked) {
                if (!ids.isEmpty()) {
                    ids.append(' ');
                }
                ids.append('#').append(other.id);
            }
            line = Component.translatable("message.overgrown_vegetation.base.roads", linked.size(),
                    Math.round(bonus(server, site) * 100.0D), ids.toString());
        }
        Component works = RoadWorks.describeWorks(server, site);
        return works == null ? line : Component.empty().append(line).append(" ").append(works);
    }

    // ---------- プレイヤーの道(置いた・壊した・爆発・読み込み) ----------

    private static boolean tracked(LevelAccessor level) {
        return level instanceof ServerLevel sl && sl.dimension() == Level.OVERWORLD;
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (tracked(event.getLevel()) && event.getPlacedBlock().is(ModTags.ROAD_BLOCKS)) {
            RECHECK.put(event.getPos().asLong(), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (tracked(event.getLevel())) {
            recheckIfTracked((ServerLevel) event.getLevel(), event.getPos());
        }
    }

    @SubscribeEvent
    public static void onExplode(ExplosionEvent.Detonate event) {
        if (!tracked(event.getLevel())) {
            return;
        }
        RoadLedger ledger = RoadLedger.get(((ServerLevel) event.getLevel()).getServer());
        if (ledger.size() == 0) {
            return;
        }
        for (BlockPos pos : event.getAffectedBlocks()) {
            long p = pos.asLong();
            if (ledger.has(p)) {
                RECHECK.putIfAbsent(p, false);
            }
        }
    }

    /** 読み込んだチャンクのマスを確かめ直す(コマンドで置き換えた・読み込まれていない間に変わったものを拾う)。 */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!tracked(event.getLevel()) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        RoadLedger ledger = RoadLedger.get(((ServerLevel) event.getLevel()).getServer());
        if (ledger.size() == 0) {
            return;
        }
        for (long p : ledger.cellsIn(chunk.getPos().toLong())) {
            RECHECK.putIfAbsent(p, false);
        }
    }

    /** 苔に覆われた・剥がされた({@link OvergrownLayerData#cover} / {@link OvergrownLayerData#uncover} から)。 */
    public static void onCover(ServerLevel level, BlockPos pos, boolean isCovered) {
        if (level.dimension() == Level.OVERWORLD) {
            RoadLedger.get(level.getServer()).setCovered(pos.asLong(), isCovered);
        }
    }

    /** 汚染でブロックが置き換わる({@code CorruptionRegistry#placeCorrupted} から。前のブロックが道のブロックのときだけ呼ばれる)。 */
    public static void onReplaced(LevelAccessor level, BlockPos pos) {
        if (tracked(level)) {
            recheckIfTracked((ServerLevel) level, pos);
        }
    }

    private static void recheckIfTracked(ServerLevel level, BlockPos pos) {
        long p = pos.asLong();
        if (RoadLedger.get(level.getServer()).has(p)) {
            RECHECK.putIfAbsent(p, false);
        }
    }

    /** 確かめる: いまそこに道のブロックがあるか(読み込まれていないチャンクは、読み込んだときに確かめる)。 */
    private static void processRechecks(MinecraftServer server) {
        ServerLevel level = server.overworld();
        RoadLedger ledger = RoadLedger.get(server);
        int budget = RECHECKS_PER_TICK;
        for (ObjectIterator<Long2BooleanMap.Entry> it = RECHECK.long2BooleanEntrySet().fastIterator(); it.hasNext() && budget-- > 0; ) {
            Long2BooleanMap.Entry e = it.next();
            long p = e.getLongKey();
            boolean addIfRoad = e.getBooleanValue();
            it.remove();
            LevelChunk chunk = level.getChunkSource().getChunkNow(BlockPos.getX(p) >> 4, BlockPos.getZ(p) >> 4);
            if (chunk == null) {
                continue;
            }
            BlockPos pos = BlockPos.of(p);
            BlockState state = chunk.getBlockState(pos);
            if (state.is(ModTags.ROAD_BLOCKS)) {
                if (addIfRoad || ledger.has(p)) {
                    ledger.add(p, OvergrownLayerData.isCovered(chunk, pos, state));
                }
            } else {
                ledger.remove(p);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!RECHECK.isEmpty()) {
            processRechecks(server);
        }
        if (server instanceof GameTestServer) {
            return; // GameTest は自分で数え直す(ほかの試験の差し替えが効いている間に数えない)
        }
        RoadLedger ledger = RoadLedger.get(server);
        if (ledger.size() == 0 && ledger.roads().isEmpty() && neighbors.isEmpty() && ledger.version() == computedVersion) {
            return;
        }
        int tick = server.getTickCount();
        boolean changed = ledger.version() != computedVersion;
        if ((changed && tick - lastComputeTick >= RECOMPUTE_DEBOUNCE) || tick - lastComputeTick >= RECOMPUTE_INTERVAL) {
            if (!TASK.running()) {
                recomputeLater(server);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        neighbors = Int2ObjectMaps.emptyMap();
        computedVersion = -1;
        lastComputeTick = Integer.MIN_VALUE / 2;
        RECHECK.clear();
    }

    private BaseRoads() {
    }
}
