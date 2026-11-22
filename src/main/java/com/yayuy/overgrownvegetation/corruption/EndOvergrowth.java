package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>過繫茂したエンド</b>(2026-10-02 ユーザー指定)。森がエンドに着いたら({@link PortalCorruption}、ワールドで一度だけ)、
 * エンドの<b>本島をまるごと</b>過繁茂させる —— プレイヤーより先に森が着いたなら、プレイヤーが後から入ったときには
 * もう植物に覆われた島になっている。
 *
 * <ol>
 *     <li>着いた時点で本島のチャンク({@link #isIslandChunk})を待ち行列へ積む。まだ無いチャンクは作る
 *     (誰もエンドへ入っていなければ、ここで初めて生成される)。1 tick に {@link #TICK_BUDGET_NANOS} の範囲で少しずつ。
 *     ワールドの設定「読み込まれていない範囲への侵食」がオフなら、読み込まれているチャンクだけ塗る(残りは読み込まれたとき)。</li>
 *     <li>1 チャンクごとに: バイオームを {@code overgrown_end} にし、エンドストーンを過繁茂したエンドストーンへ
 *     (列の上から {@link #TOP_DEPTH} 段と、空気に面したマス = 島の下面・崖の面)、上面に植物を生やす。</li>
 *     <li>終えたチャンクは {@link PortalCorruptionData} に覚える。途中でサーバーが止まっても、残りは次に
 *     読み込まれたとき({@link #onChunkLoad})か、起動後の見回り({@link #onServerTick} の初回)で塗る。</li>
 * </ol>
 *
 * <h2>触らないもの</h2>
 * 黒曜石の柱・鉄格子・エンドクリスタル・岩盤(帰りのポータル)・エンドゲートウェイ・足場の黒曜石はそのまま
 * (エンドストーンしか書き換えない)。エンドラの戦いは高さマップで祭壇の高さを決めるので
 * ({@code EndDragonFight#spawnExitPortal})、原点から {@link #PODIUM_CLEAR} ブロックには植物を生やさない。
 * 過繁茂したエンドストーンは {@code #minecraft:dragon_immune} に入れてあり、エンドラに削られない。
 *
 * <p>本島の外(外側の島々)は塗らない(ユーザー指定は「最初の島」)。そこへ広がるかは、ふだんの汚染の広がり次第。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class EndOvergrowth {

    /**
     * 本島として塗る範囲(原点からのブロック)。本島の地形は原点から約 90 ブロックで尽きる
     * (バニラの {@code DensityFunctions.EndIslandDensityFunction}: 高さの値 = 100 − 距離、外側の島は 1024 ブロックより外)。
     * 縁の欠けを残さないよう少し広めに取る。仮決め。
     */
    static final int ISLAND_RADIUS = 128;
    /** 祭壇(帰りのポータル)のまわりの、植物を生やさない半径。 */
    static final int PODIUM_CLEAR = 10;
    /** 列の一番上のエンドストーンから何段を塗るか(上面の見た目 + 少し掘っても続く)。仮決め。 */
    static final int TOP_DEPTH = 3;
    /** 1 tick に使ってよい時間。チャンクを作る(生成する)ぶんも含む。最低 1 チャンクは進める。 */
    private static final long TICK_BUDGET_NANOS = 15_000_000L;

    /** 塗る順番待ちの本島のチャンク({@link ChunkPos#toLong})。 */
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    /** 起動後、塗り残しを一度見回ったか。 */
    private static boolean resumed;

    /** そのチャンクが本島の範囲か(チャンクの原点に一番近い点で測る)。 */
    public static boolean isIslandChunk(int cx, int cz) {
        int nx = nearestToZero(cx << 4, (cx << 4) + 15);
        int nz = nearestToZero(cz << 4, (cz << 4) + 15);
        return (long) nx * nx + (long) nz * nz <= (long) ISLAND_RADIUS * ISLAND_RADIUS;
    }

    private static int nearestToZero(int min, int max) {
        return min > 0 ? min : (max < 0 ? max : 0);
    }

    /** 本島のチャンクの一覧(原点に近い順)。 */
    public static List<ChunkPos> islandChunks() {
        int r = (ISLAND_RADIUS >> 4) + 1;
        List<ChunkPos> out = new ArrayList<>();
        for (int cx = -r; cx <= r; cx++) {
            for (int cz = -r; cz <= r; cz++) {
                if (isIslandChunk(cx, cz)) {
                    out.add(new ChunkPos(cx, cz));
                }
            }
        }
        out.sort((a, b) -> Integer.compare(a.x * a.x + a.z * a.z, b.x * b.x + b.z * b.z));
        return out;
    }

    /** 森がエンドに着いたとき({@link PortalCorruption})。まだ塗っていない本島のチャンクを全部積む。積んだ数を返す。 */
    public static int begin(MinecraftServer server) {
        PortalCorruptionData data = PortalCorruptionData.get(server);
        int queued = 0;
        for (ChunkPos pos : islandChunks()) {
            if (!data.isEndIslandDone(pos.toLong()) && QUEUE.add(pos.toLong())) {
                queued++;
            }
        }
        resumed = true;
        return queued;
    }

    /** 待ち行列の長さ({@code /ov portal status}・GameTest 用)。 */
    public static int queuedCount() {
        return QUEUE.size();
    }

    /** {@code /ov portal reset} 用。 */
    public static void reset() {
        QUEUE.clear();
        resumed = false;
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.END) {
            return;
        }
        ChunkPos pos = event.getChunk().getPos();
        if (isIslandChunk(pos.x, pos.z)) {
            // 着いたか・塗り終えたかは処理するときに見る(読み込みの途中でセーブデータを触らない)。
            QUEUE.add(pos.toLong());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        reset(); // 別のワールドを開いたときに前のワールドの待ち行列を持ち越さない
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 止める(順番待ちは残り、オンで続きから)
        }
        MinecraftServer server = event.getServer();
        if (!resumed) {
            resumed = true;
            if (PortalCorruptionData.get(server).endReached()) {
                begin(server); // 前回の起動で塗り終えなかったぶん
            }
        }
        if (QUEUE.isEmpty()) {
            return;
        }
        // サーバーが重いときは予算の番人が次の tick へ回す(2026-10-06。1 チャンクでも生成を伴って重いので、止めるときは丸ごと)。
        if (!TickBudget.mayStart(PerfJob.END_OVERGROWTH)) {
            return;
        }
        long start = TickBudget.begin();
        int done = 0;
        try {
            done = process(server, TICK_BUDGET_NANOS);
        } finally {
            TickBudget.end(PerfJob.END_OVERGROWTH, start, done);
        }
    }

    /** 待ち行列を時間の許す限り進める。塗ったチャンク数を返す。 */
    private static int process(MinecraftServer server, long budgetNanos) {
        PortalCorruptionData data = PortalCorruptionData.get(server);
        ServerLevel end = server.getLevel(Level.END);
        if (end == null || !data.endReached()) {
            QUEUE.clear(); // まだ着いていない(読み込みで積まれたもの)
            return 0;
        }
        long start = System.nanoTime();
        int done = 0;
        // ワールドの設定「読み込まれていない範囲への侵食」がオフなら、読み込まれていないチャンクは作らずに飛ばす
        // (読み込まれたときに onChunkLoad がまた積む。2026-10-10)。
        boolean unloadedAllowed = com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.unloadedSpread(server);
        while (!QUEUE.isEmpty() && (done == 0 || (System.nanoTime() - start < budgetNanos && TickBudget.timeLeft()))) {
            long key = QUEUE.removeFirstLong();
            if (data.isEndIslandDone(key)) {
                continue;
            }
            if (!unloadedAllowed && end.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) == null) {
                continue;
            }
            convertChunk(end, new ChunkPos(key));
            done++;
        }
        if (QUEUE.isEmpty() && done > 0) {
            OvergrownVegetation.LOGGER.info("過繫茂したエンド: 本島の {} チャンクを塗り終えた", data.endIslandDoneCount());
        }
        return done;
    }

    /** 積んであるものを今すぐ全部塗る({@code /ov portal step}・GameTest 用)。塗ったチャンク数を返す。 */
    public static int processAllNow(MinecraftServer server) {
        return process(server, Long.MAX_VALUE);
    }

    /**
     * 1 チャンクを過繫茂したエンドにする(無ければ作ってから)。書き換えたブロック数を返す。
     * 塗り終えた印も付ける。
     */
    public static int convertChunk(ServerLevel end, ChunkPos pos) {
        LevelChunk chunk = end.getChunk(pos.x, pos.z);
        OvergrownBiomeConverter.forceOvergrown(end, pos);

        BlockState overgrown = ModBlocks.OVERGROWN_END_STONE.get().defaultBlockState();
        int minY = end.getMinBuildHeight();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        // 植物は全部塗り終えてから生やす(先に生やすと、隣のマスの「空気に面しているか」が植物で狂う)。
        List<BlockPos> tops = new ArrayList<>();
        int placed = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                int fromTop = -1; // 列の一番上の固体から何段目か(まだ固体に当たっていなければ −1)
                for (int y = top; y >= minY; y--) {
                    at.set(x, y, z);
                    BlockState state = chunk.getBlockState(at);
                    if (state.isAir()) {
                        continue;
                    }
                    if (fromTop < 0) {
                        fromTop = 0;
                    } else {
                        fromTop++;
                    }
                    if (!state.is(Blocks.END_STONE)) {
                        continue;
                    }
                    if (fromTop < TOP_DEPTH || exposed(end, chunk, at)) {
                        end.setBlock(at, overgrown, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        placed++;
                        if (fromTop == 0) {
                            tops.add(at.immutable());
                        }
                    }
                }
            }
        }

        if (OvergrownVegetationConfig.COMMON.spawnGrowthPlants.get()) {
            double growth = OvergrownVegetationConfig.COMMON.growthChance.get();
            for (BlockPos top : tops) {
                long dx = top.getX();
                long dz = top.getZ();
                if (dx * dx + dz * dz <= (long) PODIUM_CLEAR * PODIUM_CLEAR || !end.getBlockState(top.above()).isAir()) {
                    continue;
                }
                CorruptionSpreader.growSurfacePlants(end, top, overgrown, growth);
            }
        }
        if (placed > 0) {
            CorruptionCounter.record(end, placed);
        }
        PortalCorruptionData.get(end.getServer()).markEndIslandDone(pos.toLong());
        return placed;
    }

    /**
     * 6 方向のどれかが空気か。隣のチャンクが本島なら(どうせ塗るので)作ってでも読む —— 読まないと、塗る順番がまだ来ていない
     * 隣との境目の崖・下面が 16 ブロックおきの縞になって塗り残る。本島の外で読み込まれていなければ塞がっているとみなす
     * (そこまで作り始めると、虚空のチャンクを次々に生成してしまう)。
     */
    private static boolean exposed(ServerLevel end, LevelChunk chunk, BlockPos pos) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            n.setWithOffset(pos, dir);
            if (n.getY() < end.getMinBuildHeight() || n.getY() >= end.getMaxBuildHeight()) {
                return true;
            }
            BlockState state;
            if ((n.getX() >> 4) == chunk.getPos().x && (n.getZ() >> 4) == chunk.getPos().z) {
                state = chunk.getBlockState(n);
            } else {
                int ncx = n.getX() >> 4;
                int ncz = n.getZ() >> 4;
                LevelChunk other = end.getChunkSource().getChunkNow(ncx, ncz);
                if (other == null) {
                    if (!isIslandChunk(ncx, ncz)) {
                        continue;
                    }
                    other = end.getChunk(ncx, ncz);
                }
                state = other.getBlockState(n);
            }
            if (state.isAir()) {
                return true;
            }
        }
        return false;
    }

    private EndOvergrowth() {
    }
}
