package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SpreadTable;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>ネザーで侵食が広がり続ける仕組み</b>(2026-10-02 ユーザー指定: ネザー用のものを新しく作る)。
 *
 * <p>オーバーワールドの広がりは地表を高さマップで探すので、天井が岩盤のネザーでは何もしない。ここでは床
 * ({@link NetherSurface})を使って、オーバーワールドと同じ 2 本立てで広げる。
 * <ol>
 *     <li><b>読み込まれているチャンク</b>({@link #creep}): プレイヤーのまわりのチャンクで、汚染された床に接している床を
 *     確率で汚染する(接している列の数が多いほど高い。オーバーワールドの這い出しと同じ確率)。
 *     汚染が増えたら {@link OvergrownBiomeConverter#maybeConvert} でバイオームの書き換えを打診する。</li>
 *     <li><b>台帳</b>({@link NetherLedgerData}、留守中も進む): オーバーワールドの台帳の広がり({@link LedgerSpread})と同じ
 *     間隔・確率({@code spread.json})で、過繁茂チャンクに辺で接しているチャンクへ印を付ける。読み込まれたら
 *     バイオームを過繁茂へ書き換え、床を {@code surfaceCoverage} だけまだらに汚染する。</li>
 * </ol>
 * フェーズの面積とポイントには数える(フェーズはワールド単位)が、オーバーワールドの台帳(極相の判定)には入れない。
 * 過繁茂になったチャンクは、オーバーワールドと同じ深部の一括置換({@code OvergrownBiomeCreep})で全部置き換わる(2026-10-02 ユーザー指定。溶岩の海も玄武岩に)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class NetherLedger {


    // ---- 読み込まれているチャンクの広がり(仮決め) ----
    /** 這い出しの間隔。 */
    private static final int CREEP_INTERVAL = 40;
    /** プレイヤーのまわり何チャンクを見るか。 */
    private static final int CREEP_RADIUS_CHUNKS = 4;
    /** 1 回・1 プレイヤーあたり見るチャンク数。 */
    private static final int CREEP_CHUNKS_PER_PLAYER = 4;
    /** 1 チャンクで見る列の数。 */
    private static final int CREEP_COLUMNS = 64;
    /** 1 チャンクで汚染する上限。 */
    private static final int CREEP_MAX_PER_CHUNK = 32;
    /** 汚染できたチャンク / 何も無かったチャンクを次に見るまで。 */
    private static final long CREEP_RESCAN = 200L;
    private static final long CREEP_CLEAN_RESCAN = 1200L;

    private static final Long2LongOpenHashMap NEXT_CREEP = new Long2LongOpenHashMap();
    private static final LongLinkedOpenHashSet LOADED = new LongLinkedOpenHashSet();
    private static final LongLinkedOpenHashSet CATCH_UP = new LongLinkedOpenHashSet();

    /** 台帳を持つ次元か(今はネザーだけ)。 */
    static boolean isLedgerLevel(Level level) {
        return level.dimension() == Level.NETHER;
    }

    /**
     * 過繁茂になったチャンクを記録する({@link OvergrownBiomeConverter} から、オーバーワールド以外のとき)。
     * ネザーなら台帳へ入れ、<b>新しく入った分だけ</b>フェーズの面積とポイントに数える(台帳の広がりの印と
     * 書き換えの両方から来るので、二重に数えない)。ほかの次元(エンド)は台帳が無いのでそのまま数える。
     */
    static void record(ServerLevel level, List<ChunkPos> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        int added = chunks.size();
        if (isLedgerLevel(level)) {
            NetherLedgerData data = NetherLedgerData.get(level);
            added = 0;
            for (ChunkPos pos : chunks) {
                if (data.mark(pos.toLong())) {
                    added++;
                }
            }
        }
        if (added > 0) {
            PhaseData.get(level).addOvergrownChunks(level, added);
        }
    }

    /**
     * ネザーの胞子の場で発芽したチャンク(2026-10-23 F)。記録へ入れてフェーズの面積に数え、読み込まれていれば書き換えの列へ。
     * 読み込まれていなければ、読み込んだときに書き換える(今までの台帳の印と同じ)。
     */
    public static void acceptFieldSprouts(ServerLevel nether, long[] keys) {
        NetherLedgerData data = NetherLedgerData.get(nether);
        List<ChunkPos> fresh = new ArrayList<>();
        for (long key : keys) {
            if (data.isOvergrown(key) || data.isPending(key)) continue;
            if (SporeDeviceData.guardsChunkFromLedger(nether, ChunkPos.getX(key), ChunkPos.getZ(key))) continue;
            fresh.add(new ChunkPos(key));
        }
        if (fresh.isEmpty()) return;
        record(nether, fresh);
        for (ChunkPos pos : fresh) {
            long key = pos.toLong();
            data.addPending(key);
            if (nether.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
                CATCH_UP.add(key);
                com.yayuy.overgrownvegetation.spore.field.SporeSprouts.markFresh(nether, key); // 読み込まれている間の発芽は歩く萌芽体
            }
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && isLedgerLevel(level)) {
            LOADED.add(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        NEXT_CREEP.clear();
        LOADED.clear();
        CATCH_UP.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isLedgerLevel(level)) {
            return;
        }
        SpreadTable table = DesignData.spread();
        NetherLedgerData data = NetherLedgerData.get(level);
        drainLoaded(data);
        if (CorruptionSwitch.isOff()) {
            // /ov corruption false(2026-10-10 ユーザー指定): オーバーワールドの台帳と同じく、印も塗りも縁の広がりも止める(順番待ちは残る)。
            return;
        }
        tickCatchUp(level, data, table);
        // 2026-10-23 胞子の場 F: 留守中の広がりは台帳の縁の確率ではなく、ネザーの胞子の場の発芽({@link #acceptFieldSprouts})
        // 2026-10-23 F: 床の這い出しは無し(オーバーワールドと同じく、芽の点と萌芽体に任せる)。ネザーの場が動いていないときだけ前のまま
        if (!com.yayuy.overgrownvegetation.spore.field.NetherSporeFieldServer.running() && level.getGameTime() % CREEP_INTERVAL == 17L) {
            creep(level);
        }
    }


    /**
     * 読み込まれたばかりのチャンクと、その周り 8 チャンクのうち、書き換え待ちのものを列へ。書き換えは周りまで読み込まれるのを
     * 待つので({@link #tickCatchUp})、隣が読み込まれたときにも見直す(オーバーワールドの {@code LedgerSpread#drainLoaded} と同じ、2026-10-06)。
     */
    private static void drainLoaded(NetherLedgerData data) {
        for (int i = 0; i < 64 && !LOADED.isEmpty(); i++) {
            long key = LOADED.removeFirstLong();
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long n = ChunkPos.asLong(x + dx, z + dz);
                    if (data.isPending(n)) {
                        CATCH_UP.add(n);
                    }
                }
            }
        }
    }


    /** 印の付いたチャンクが読み込まれたら、バイオームを書き換えて床をまだらに汚染する(1 tick にチャンク 1 つまで)。 */
    private static void tickCatchUp(ServerLevel level, NetherLedgerData data, SpreadTable table) {
        if (CATCH_UP.isEmpty()) {
            return;
        }
        // サーバーが重いときは予算の番人が次の tick へ回す(2026-10-06。結果は同じで遅れるだけ)。
        if (!TickBudget.mayStart(PerfJob.NETHER_LEDGER)) {
            return;
        }
        long key = CATCH_UP.removeFirstLong();
        ChunkPos pos = new ChunkPos(key);
        if (!data.isPending(key) || !LoadedChunks.neighbourhoodLoaded(level, pos.x, pos.z)) {
            // 読み込まれていない(周り 8 チャンクのどれかも含む): 待ちに残す。自分か隣が読み込まれたらまた並ぶ。
            // 床は UPDATE_ALL で置くので、縁の列の近隣更新が読み込みの途中の隣に届くと、サーバースレッドが隣の読み込みを
            // 待って止まる(オーバーワールドの書き換えで 2026-10-06 に計測した不具合と同じ)。
            return;
        }
        if (SporeDeviceData.guardsChunkFromLedger(level, pos.x, pos.z)) {
            data.removePending(key);
            return;
        }
        long start = TickBudget.begin();
        try {
            if (com.yayuy.overgrownvegetation.spore.field.NetherSporeFieldServer.running()) {
                // 2026-10-23 F: 床を四角くまだらに塗らず、バイオームだけ書き換えて芽の点と萌芽体に任せる(オーバーワールドと同じ)
                OvergrownBiomeConverter.forceOvergrown(level, pos);
                com.yayuy.overgrownvegetation.spore.field.SporeSprouts.start(level, pos);
            } else {
                paintNow(level, pos, table);
            }
        } finally {
            TickBudget.end(PerfJob.NETHER_LEDGER, start, 1);
        }
        data.removePending(key);
    }

    /** そのチャンクを過繁茂へ書き換え、床を {@code surfaceCoverage} だけまだらに汚染する。置き換えた数を返す。 */
    public static int paintNow(ServerLevel level, ChunkPos pos, SpreadTable table) {
        OvergrownBiomeConverter.forceOvergrown(level, pos);
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        long seed = level.getSeed();
        double threshold = LedgerSpreadMath.noiseThreshold(table.surfaceCoverage());
        double growth = OvergrownVegetationConfig.COMMON.growthChance.get();
        RandomSource random = level.getRandom();
        int[] floors = new int[NetherSurface.MAX_FLOORS];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int placed = 0;
        for (int c = 0; c < 256; c++) {
            int x = pos.getMinBlockX() + (c & 15);
            int z = pos.getMinBlockZ() + (c >> 4);
            if (LedgerSpreadMath.patch(seed, x, z, table.patchSize()) >= threshold) {
                continue; // 塗り残し
            }
            int n = NetherSurface.floors(level, chunk, x, z, floors);
            for (int i = 0; i < n; i++) {
                if (corruptAt(level, at.set(x, floors[i], z), random, growth)) {
                    placed++;
                }
            }
        }
        if (placed > 0) {
            CorruptionCounter.record(level, placed);
        }
        return placed;
    }

    // ------------------------------------------------------------------------------------------
    // 読み込まれているチャンクの広がり
    // ------------------------------------------------------------------------------------------

    /** プレイヤーのまわりのチャンクで、汚染された床に接している床を汚染する。汚染した数を返す。 */
    static int creep(ServerLevel level) {
        if (!OvergrownVegetationConfig.COMMON.overgrownBiomeCreep.get()) {
            return 0;
        }
        long now = level.getGameTime();
        RandomSource random = level.getRandom();
        if (NEXT_CREEP.size() > 8192) {
            NEXT_CREEP.clear();
        }
        int total = 0;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            ChunkPos center = player.chunkPosition();
            for (int k = 0; k < CREEP_CHUNKS_PER_PLAYER; k++) {
                int cx = center.x + random.nextInt(CREEP_RADIUS_CHUNKS * 2 + 1) - CREEP_RADIUS_CHUNKS;
                int cz = center.z + random.nextInt(CREEP_RADIUS_CHUNKS * 2 + 1) - CREEP_RADIUS_CHUNKS;
                long key = ChunkPos.asLong(cx, cz);
                if (now < NEXT_CREEP.getOrDefault(key, Long.MIN_VALUE)) {
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null || !LoadedChunks.neighbourhoodLoaded(level, cx, cz)) {
                    continue; // 縁のチャンクは外が読み込まれるまで待つ(上の tickCatchUp と同じ理由、2026-10-06)
                }
                int placed = creepChunk(level, chunk, random);
                NEXT_CREEP.put(key, now + (placed > 0 ? CREEP_RESCAN : CREEP_CLEAN_RESCAN));
                if (placed > 0) {
                    CorruptionCounter.record(level, placed);
                    OvergrownBiomeConverter.maybeConvert(level, chunk.getPos());
                    total += placed;
                }
            }
        }
        return total;
    }

    /** 1 チャンクぶん。乱数で選んだ列の床のうち、横の列の同じ高さ(±1)に汚染があるものを確率で汚染する。 */
    public static int creepChunk(ServerLevel level, LevelChunk chunk, RandomSource random) {
        ChunkPos pos = chunk.getPos();
        double growth = OvergrownVegetationConfig.COMMON.growthChance.get();
        int[] floors = new int[NetherSurface.MAX_FLOORS];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int placed = 0;
        for (int c = 0; c < CREEP_COLUMNS && placed < CREEP_MAX_PER_CHUNK; c++) {
            int x = pos.getMinBlockX() + random.nextInt(16);
            int z = pos.getMinBlockZ() + random.nextInt(16);
            int n = NetherSurface.floors(level, chunk, x, z, floors);
            for (int i = 0; i < n && placed < CREEP_MAX_PER_CHUNK; i++) {
                int y = floors[i];
                int sources = 0;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    for (int dy = -1; dy <= 1; dy++) {
                        probe.set(x + dir.getStepX(), y + dy, z + dir.getStepZ());
                        if (level.isLoaded(probe) && OvergrownLayerData.isCorruptedCell(level, probe, level.getBlockState(probe))) {
                            sources++;
                            break;
                        }
                    }
                }
                if (sources == 0) {
                    continue;
                }
                double chance = Math.min(1.0D, 0.35D + (sources - 1) * 0.22D);
                if (random.nextDouble() >= chance) {
                    continue;
                }
                if (corruptAt(level, at.set(x, y, z), random, growth)) {
                    placed++;
                }
            }
        }
        return placed;
    }

    /** 床 1 マスを汚染する(ふだんの汚染と同じ経路)。置き換えたら {@code true}。 */
    private static boolean corruptAt(ServerLevel level, BlockPos.MutableBlockPos at, RandomSource random, double growth) {
        BlockState state = level.getBlockState(at);
        if (state.is(ModTags.CORRUPTED) || !CorruptionRegistry.canCorrupt(level, at, state)) {
            return false;
        }
        BlockState corrupted = CorruptionRegistry.corrupt(level, at, state, random);
        if (CorruptionRegistry.noChange(state, corrupted)) {
            CorruptionSpreader.growPlantsIfNewlyCovered(level, at, state); // 覆っただけのマスにも植物(2026-10-10)
            return false;
        }
        BlockPos placedAt = at.immutable();
        CorruptionRegistry.placeCorrupted(level, placedAt, state, corrupted, Block.UPDATE_ALL);
        CorruptionSpreader.growSurfacePlants(level, placedAt, corrupted, growth);
        return true;
    }

    private NetherLedger() {
    }
}
