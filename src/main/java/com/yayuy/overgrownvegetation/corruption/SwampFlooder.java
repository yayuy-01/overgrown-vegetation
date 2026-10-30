package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 湿地系バイオーム({@link OvergrowthVariant#isSwamp()})のチャンクを、じめじめした水環境へ地形ごと作り替える。
 *
 * <h2>段階</h2>
 * <ul>
 *     <li><b>繁茂段階</b>(胞子濃度 100%、地表汚染は閾値未満): 地面 1 マスの水たまりだけ。</li>
 *     <li><b>過繁茂段階</b>: 平坦な場所には池(深さ 1〜3)と細い水路、斜面は棚田状の段々に削って
 *     各段に水を張り、畦(あぜ)の一部を切って段から段へ水が落ちる連続した滝にする。
 *     水際の地面は泥に変わる。</li>
 * </ul>
 *
 * <h2>場所の決め方</h2>
 * <p>チャンクごとの乱数ではなく、ワールドシード固有のシンプレックスノイズ(池: 周期 48、水路: 周期 26 の
 * 尾根状、水たまり: 周期 5)を座標で引くので、隣のチャンクと矛盾せず一続きの池・水路になる。
 * 棚田は「地表高さを半径 3 で平滑化した値を {@code swampTerraceStep} 段ごとに量子化」した高さに
 * 各列を削る / 盛るだけなので、等高線に沿った段々が自然にできる。
 *
 * <h2>水漏れ防止</h2>
 * <p>水は必ず水源として置き、「4 方向の隣の列の上面(または隣も水になる面)が水面以上」の列にしか
 * 置かない。棚田では下の段と接する列を畦(泥の土手。既定では水面と同じ高さ)にし、畦のうち {@code swampSpillwayChance} の割合だけを
 * 水にして意図的に溢れさせる(= 滝)。溢れる先は水になる列に限る。
 *
 * <h2>対象と進捗 (2026-09-10 改)</h2>
 * <p>対象は「地域マップ({@link OvergrowthRegionMap})上で湿地になる場所」のチャンク。バイオームがまだ
 * 書き換わっていなくても、地表が汚染された列({@code #corrupted})から順に処理する。まだ汚染されていない
 * 自然の地面({@code KIND_CLEAN})は壁として扱い触らない。段階は汚染列数({@code swampFloodStage2Columns})
 * か過繁茂バイオームで決まる。列ごとの処理済み段階は {@link SwampFloodProgress}(SavedData)に保存し、
 * 同じ段階では二度と触らない(削ったことで平滑化高さが変わり段が下がり続ける侵食を防ぐ)。
 *
 * <h2>除外</h2>
 * <p>地表が自然の土・石・砂・汚染地面でない列(建築物)、原木の列(木)、頭上 4 マス以内に人工物の
 * ある列(屋根の下)は、{@code swampFloodSkipArtificial} が true なら水たまりも含めて一切触らない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SwampFlooder {

    /** チャンクの外側に読む余白(列)。平滑化半径 3 + 傾斜判定 4 + 隣接 1。 */
    private static final int PAD = 8;
    private static final int SIZE = 16 + PAD * 2;
    private static final int SMOOTH_RADIUS = 3;
    private static final int SLOPE_REACH = 4;
    private static final int MAX_WALK_DOWN = 48;
    /** 地形を書き換えるときのフラグ。支えを失った草・睡蓮などのドロップを出さない(UPDATE_SUPPRESS_DROPS)。 */
    private static final int FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;

    private static final byte KIND_NATURAL = 0;
    private static final byte KIND_WATER = 1;
    private static final byte KIND_CLEAN = 2; // 自然の地面だがまだ汚染されていない
    private static final byte KIND_TREE = 3;
    private static final byte KIND_ARTIFICIAL = 4;
    private static final byte KIND_NONE = 5;

    private static final byte MODE_NONE = 0;
    private static final byte MODE_TERRACE = 1;
    private static final byte MODE_FLAT = 2;

    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> NEXT = new HashMap<>();
    private static final Map<Long, SimplexNoise> NOISE = new ConcurrentHashMap<>();
    /**
     * {@link #NEXT} がこの件数を超えたら、期限切れの項目を掃除する(2026-09-24 軽量化、報告 1527)。仮決め 2026-09-24。
     * 以前はサーバー停止まで消えず、歩き回ったぶんだけ増え続けていた。
     */
    private static final int NEXT_PRUNE_SIZE = 20000;
    /**
     * 処理済み段階の記録が無いチャンク用の「全列 0」(読むだけ。<b>書き込まない</b>)。書くときは
     * {@link SwampFloodProgress#columns} で本物の配列を作る(2026-09-24 軽量化 報告 1527)。
     */
    private static final byte[] NO_PROGRESS = new byte[256];

    /**
     * ワールドを閉じたら再走査時刻の表を空にする(キーはディメンションだけなので、次に別のワールドを開くと
     * 前のワールドの時刻が残り、そのぶん待たされる。起動し直したときと同じ状態に戻す)。
     * {@link #NOISE} はシードごとなので残してよい。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        NEXT.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 止める(順番待ちは残り、オンで続きから)
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.swampFlooding.get() || !cfg.convertToOvergrownBiome.get()) {
            return;
        }
        int interval = Math.max(1, cfg.swampFloodIntervalTicks.get());
        // 位相をずらして、ほかの地形の周期処理と同じ tick に重ならないようにする(2026-09-24 軽量化、報告 589 / 1516。
        // 間隔は同じで、走る tick が周期の中でずれるだけ)。
        if (!com.yayuy.overgrownvegetation.util.TickPhases.isDue(level.getGameTime(),
                com.yayuy.overgrownvegetation.util.TickPhases.SWAMP_FLOODER, interval)) {
            return;
        }
        // サーバーが重いときは予算の番人が次の周回へ回す(2026-10-06。結果は同じで遅れるだけ)。
        if (!TickBudget.mayStart(PerfJob.SWAMP_FLOOD)) {
            return;
        }
        long start = TickBudget.begin();
        int done = 0;
        try {
            done = floodPass(level, cfg);
        } finally {
            TickBudget.end(PerfJob.SWAMP_FLOOD, start, done);
        }
    }

    /** 1 回ぶん水没させる。戻り値は処理したチャンク数。 */
    private static int floodPass(ServerLevel level, OvergrownVegetationConfig.Common cfg) {
        int radiusChunks = Math.max(1, Mth.ceil(cfg.overgrownBiomeCreepRadius.get() / 16.0F));
        int perPass = cfg.swampFloodChunksPerPass.get();
        int budget = perPass;
        long now = level.getGameTime();
        long rescan = cfg.swampFloodRescanTicks.get();
        Long2LongOpenHashMap next = NEXT.computeIfAbsent(level.dimension(), k -> {
            Long2LongOpenHashMap map = new Long2LongOpenHashMap();
            map.defaultReturnValue(0L);
            return map;
        });
        // 期限切れ(値 <= now)の項目は、下の「now < next.get(key)」で項目が無いとき(既定値 0)と同じく偽になるので、
        // 消しても結果は変わらない。表が大きくなったときだけ掃除する(2026-09-24 軽量化 報告 1527)。
        if (next.size() > NEXT_PRUNE_SIZE) {
            next.long2LongEntrySet().removeIf(e -> e.getLongValue() <= now);
        }

        LongOpenHashSet seen = new LongOpenHashSet();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            ChunkPos center = player.chunkPosition();
            // 近い順(リング状)に見る。
            for (int ring = 0; ring <= radiusChunks && budget > 0; ring++) {
                for (int dx = -ring; dx <= ring && budget > 0; dx++) {
                    for (int dz = -ring; dz <= ring && budget > 0; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue;
                        }
                        int cx = center.x + dx;
                        int cz = center.z + dz;
                        long key = ChunkPos.asLong(cx, cz);
                        if (!seen.add(key) || now < next.get(key)) {
                            continue;
                        }
                        if (!neighbourhoodLoaded(level, cx, cz)) {
                            continue;
                        }
                        ChunkPos chunkPos = new ChunkPos(cx, cz);
                        // バイオームが書き換わっていなくても、地域マップ上で湿地になる場所なら対象。
                        if (!OvergrowthRegionMap.variantAt(level, chunkPos).isSwamp()) {
                            next.put(key, now + rescan);
                            continue;
                        }
                        int corruptedColumns = countCorruptedColumns(level, cx, cz);
                        if (corruptedColumns == 0) {
                            next.put(key, now + rescan);
                            continue;
                        }
                        Holder<Biome> biome = level.getBiome(new BlockPos((cx << 4) + 8, level.getSeaLevel(), (cz << 4) + 8));
                        int stage = ModBiomes.isFullyOvergrown(biome) || corruptedColumns >= cfg.swampFloodStage2Columns.get() ? 2 : 1;
                        next.put(key, now + rescan);
                        processChunk(level, chunkPos, stage, cfg);
                        // 水底の泥も汚染として数えるので、浸水後にバイオーム昇格の判定を掛け直す。
                        OvergrownBiomeConverter.maybeConvert(level, chunkPos);
                        budget--;
                    }
                }
            }
        }
        return perPass - budget;
    }

    /** チャンク地表(流体を除いた最上部)のうち汚染ブロックの列数。 */
    private static int countCorruptedColumns(ServerLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunk(cx, cz);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int y = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z); // ChunkAccess#getHeight は最上部ブロックの Y
                cursor.set((cx << 4) + x, y, (cz << 4) + z);
                if (chunk.getBlockState(cursor).is(ModTags.CORRUPTED)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * このチャンクに掛かる生成ストラクチャーの全ピース(建物 1 棟・道 1 本ごとの箱)を {@code margin} ぶん
     * 広げた境界箱の一覧。村の民家の中や軒先に池・棚田を作らないため。
     */
    private static List<BoundingBox> collectStructurePieces(ServerLevel level, ChunkPos cp, int margin) {
        List<BoundingBox> boxes = new ArrayList<>();
        for (StructureStart start : level.structureManager().startsForStructure(cp, structure -> true)) {
            for (StructurePiece piece : start.getPieces()) {
                boxes.add(piece.getBoundingBox().inflatedBy(margin));
            }
        }
        return boxes;
    }

    /** XZ だけで見て、いずれかの箱の中か(民家は地表にあるので高さは見ない)。 */
    private static boolean insideAnyXZ(List<BoundingBox> boxes, int x, int z) {
        for (BoundingBox box : boxes) {
            if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 周り 3x3 チャンクが読み込み済みか。hasChunk は「読み込み予定」(生成・読み込みの途中)でも true になり、
     * そのまま読むと終わるまでサーバースレッドが止まるので、FULL まで済んでいるか({@code getChunkNow})も見る。
     * 途中のものがあれば読み込まれていないときと同じく今回は見送る(再走査時刻は書かないので、次の周回で同じ処理になる)。
     */
    private static boolean neighbourhoodLoaded(ServerLevel level, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!level.hasChunk(cx + dx, cz + dz)
                        || level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // チャンク処理
    // ------------------------------------------------------------------------------------------

    private static void processChunk(ServerLevel level, ChunkPos cp, int stage, OvergrownVegetationConfig.Common cfg) {
        boolean skipArtificial = cfg.swampFloodSkipArtificial.get();
        int step = Math.max(2, cfg.swampTerraceStep.get());
        double slopeThreshold = cfg.swampTerraceSlope.get();
        double pondThr = cfg.swampPondThreshold.get();
        double creekThr = cfg.swampCreekThreshold.get();
        double puddleThr = cfg.swampPuddleThreshold.get();
        double spillChance = cfg.swampSpillwayChance.get();
        long seed = level.getSeed();
        SimplexNoise noise = NOISE.computeIfAbsent(seed, s -> new SimplexNoise(RandomSource.create(s ^ 0x5A7E8F00DL)));

        int originX = cp.getMinBlockX() - PAD;
        int originZ = cp.getMinBlockZ() - PAD;

        int[] ground = new int[SIZE * SIZE];
        byte[] kind = new byte[SIZE * SIZE];
        boolean[] shallow = new boolean[SIZE * SIZE]; // 深さ 1 の水(棚田の水面・水たまり)を頭に載せた列
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // 列ごとの処理済み段階。記録が無ければ全列 0 として読むだけにし、初めて書く列で作る(2026-09-24 軽量化
        // 報告 1527: 以前は読むだけでも空の記録を台帳に足していた)。
        SwampFloodProgress progress = SwampFloodProgress.get(level);
        byte[] done = progress.peek(cp);
        if (done == null) {
            done = NO_PROGRESS;
        }

        // 先に自チャンクの 16×16 列だけを読む(2026-09-24 軽量化 報告 1527)。この段階で書き換えうるのは
        // 「自然の汚染地面(KIND_NATURAL)で、まだこの段階を済ませていない列」だけで、下で種類が変わるのは
        // NATURAL → 人工物 / 触らない地面 の向きだけ。1 列も無ければ、水たまり段階(stage 1)は何も書かずに
        // 終わる(以前は 32×32 列を全部読んでから全部弾いていた)。過繁茂段階(stage 2)は、処理済みの列にも
        // 水際の泥を足しうるので今までどおり最後まで回す。
        boolean anyTodo = false;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int i = (lx + PAD) * SIZE + (lz + PAD);
                scanColumn(level, originX + lx + PAD, originZ + lz + PAD, cursor, ground, kind, shallow, i, skipArtificial);
                if (kind[i] == KIND_NATURAL && done[lx * 16 + lz] < stage) {
                    anyTodo = true;
                }
            }
        }
        if (!anyTodo && stage < 2) {
            return;
        }

        // 生成ストラクチャー(村の民家・道など)のピースの範囲(+margin)は建築物として扱い、一切触らない。
        List<BoundingBox> protectedBoxes = cfg.swampFloodProtectStructures.get()
                ? collectStructurePieces(level, cp, cfg.swampFloodStructureMargin.get())
                : List.of();
        for (int ix = 0; ix < SIZE; ix++) {
            for (int iz = 0; iz < SIZE; iz++) {
                int i = ix * SIZE + iz;
                boolean ownChunk = ix >= PAD && ix < PAD + 16 && iz >= PAD && iz < PAD + 16;
                if (!ownChunk) {
                    // 自チャンクの列は上で読み済み(読むだけで世界を変えないので、読む順番は結果に関係しない)。
                    scanColumn(level, originX + ix, originZ + iz, cursor, ground, kind, shallow, i, skipArtificial);
                }
                if (kind[i] == KIND_NATURAL && !protectedBoxes.isEmpty()
                        && insideAnyXZ(protectedBoxes, originX + ix, originZ + iz)) {
                    kind[i] = KIND_ARTIFICIAL;
                }
            }
        }

        // 大穴・崖(大きな洞窟へ落ち込む穴など)の縁と底は棚田にしない。隣の列との落差が swampTerraceVoidDrop 以上ある
        // 列(崖の上の縁と、崖下の底の両方)と、そこから swampTerraceVoidMargin 列以内の列を「触らない自然の地面」扱いに
        // する(2026-09-11、穴の縁に泥の壁と水が積み上がって柱状に浮いた見た目になっていたため)。平滑化からも外す。
        int voidDrop = Math.max(1, cfg.swampTerraceVoidDrop.get());
        int voidMargin = Math.max(0, cfg.swampTerraceVoidMargin.get());
        boolean[] voidCol = new boolean[SIZE * SIZE];
        for (int ix = 1; ix < SIZE - 1; ix++) {
            for (int iz = 1; iz < SIZE - 1; iz++) {
                int i = ix * SIZE + iz;
                if (kind[i] == KIND_NONE) {
                    continue;
                }
                for (int dx = -1; dx <= 1 && !voidCol[i]; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int j = (ix + dx) * SIZE + (iz + dz);
                        if (kind[j] != KIND_NONE && Math.abs(ground[i] - ground[j]) >= voidDrop) {
                            voidCol[i] = true;
                            break;
                        }
                    }
                }
            }
        }
        if (voidMargin > 0) {
            boolean[] expanded = voidCol.clone();
            for (int ix = 0; ix < SIZE; ix++) {
                for (int iz = 0; iz < SIZE; iz++) {
                    if (!voidCol[ix * SIZE + iz]) {
                        continue;
                    }
                    for (int dx = -voidMargin; dx <= voidMargin; dx++) {
                        for (int dz = -voidMargin; dz <= voidMargin; dz++) {
                            int jx = ix + dx;
                            int jz = iz + dz;
                            if (jx >= 0 && jx < SIZE && jz >= 0 && jz < SIZE) {
                                expanded[jx * SIZE + jz] = true;
                            }
                        }
                    }
                }
            }
            voidCol = expanded;
        }
        for (int i = 0; i < SIZE * SIZE; i++) {
            if (voidCol[i] && kind[i] == KIND_NATURAL) {
                kind[i] = KIND_CLEAN;
            }
        }

        // 平滑化した地表高さ(窓が収まる範囲だけ)。
        double[] smooth = new double[SIZE * SIZE];
        for (int ix = SMOOTH_RADIUS; ix < SIZE - SMOOTH_RADIUS; ix++) {
            for (int iz = SMOOTH_RADIUS; iz < SIZE - SMOOTH_RADIUS; iz++) {
                double sum = 0.0D;
                int n = 0;
                for (int dx = -SMOOTH_RADIUS; dx <= SMOOTH_RADIUS; dx++) {
                    for (int dz = -SMOOTH_RADIUS; dz <= SMOOTH_RADIUS; dz++) {
                        int j = (ix + dx) * SIZE + (iz + dz);
                        if (kind[j] != KIND_NONE && !voidCol[j]) {
                            sum += ground[j];
                            n++;
                        }
                    }
                }
                smooth[ix * SIZE + iz] = n == 0 ? ground[ix * SIZE + iz] : sum / n;
            }
        }

        // 各列のモード(棚田 / 平坦)、棚田の段の高さと段差。チャンク + 隣接 1 列ぶんだけ要る。
        // 段差は傾斜に応じて変える: なだらかなら swampTerraceStep の小さな段を何段も、急なら
        // swampTerraceMaxStep までの大きな段を数個(高い壁を持つ大きな棚)。
        byte[] mode = new byte[SIZE * SIZE];
        int[] terrace = new int[SIZE * SIZE];
        // 段差は step の倍数 3 段階(step / 2·step / maxStep)。倍数にしておくと違う段差の段が隣り合っても
        // 段の高さが必ず step の倍数で揃い、綺麗な壁になる(以前の列ごとの連続値は市松状の柱を生んだ)。
        int maxStep = Math.max(step, (int) Math.max(1, Math.round(cfg.swampTerraceMaxStep.get() / (double) step)) * step);
        int maxCut = Math.max(1, cfg.swampTerraceMaxCut.get());
        int maxFill = Math.max(1, cfg.swampTerraceMaxFill.get());
        double steepSlope = Math.max(slopeThreshold, cfg.swampTerraceSteepSlope.get());
        double midSlope = (slopeThreshold + steepSlope) * 0.5D;
        int lo = PAD - 1;
        int hi = PAD + 16; // inclusive
        // 傾斜の判定は quart(4x4 列)単位。同じ quart の列は必ず同じ段差クラスになる。
        int[] quartStep = new int[SIZE * SIZE];
        for (int ix = lo; ix <= hi; ix++) {
            for (int iz = lo; iz <= hi; iz++) {
                int qx = Math.floorDiv(originX + ix, 4) * 4 - originX + 2; // quart 中心(ローカル)
                int qz = Math.floorDiv(originZ + iz, 4) * 4 - originZ + 2;
                qx = Mth.clamp(qx, SLOPE_REACH + SMOOTH_RADIUS, SIZE - 1 - SLOPE_REACH - SMOOTH_RADIUS);
                qz = Mth.clamp(qz, SLOPE_REACH + SMOOTH_RADIUS, SIZE - 1 - SLOPE_REACH - SMOOTH_RADIUS);
                double slope = Math.abs(smooth[(qx + SLOPE_REACH) * SIZE + qz] - smooth[(qx - SLOPE_REACH) * SIZE + qz])
                        + Math.abs(smooth[qx * SIZE + qz + SLOPE_REACH] - smooth[qx * SIZE + qz - SLOPE_REACH]);
                int s;
                if (slope < slopeThreshold) {
                    s = 0; // 平坦
                } else if (slope < midSlope) {
                    s = step;
                } else if (slope < steepSlope) {
                    s = Math.min(maxStep, step * 2);
                } else {
                    s = maxStep;
                }
                quartStep[ix * SIZE + iz] = s;
            }
        }
        for (int ix = lo; ix <= hi; ix++) {
            for (int iz = lo; iz <= hi; iz++) {
                int i = ix * SIZE + iz;
                if (kind[i] != KIND_NATURAL) {
                    continue;
                }
                if (stage < 2 || quartStep[i] == 0) {
                    mode[i] = MODE_FLAT;
                    continue;
                }
                int s = quartStep[i];
                // 段の高さは平滑化高さに一番近い段(切り捨てではなく四捨五入)。切り捨てだと段差の大きい場所で
                // 最大 s-1 も掘り下げてしまい、深い落とし穴になる。
                int t = (int) Math.round(smooth[i] / s) * s;
                // 隣に既に出来ている段の水面があれば、その段に合わせる(処理済みの隣と同じ池に繋がる)。
                for (int d = 0; d < 4; d++) {
                    int j = neighbourIndex(i, d);
                    if (kind[j] == KIND_WATER || shallow[j]) {
                        int tn = kind[j] == KIND_WATER ? ground[j] - 1 : ground[j];
                        if (Math.abs(tn - t) <= s && ground[i] >= tn - maxFill && ground[i] <= tn + maxCut) {
                            t = tn;
                            break;
                        }
                    }
                }
                // 削るだけでなく盛って作る: 掘りすぎになる列は 1 段上の段に、盛りすぎになる列は 1 段下の段に寄せて、
                // 必ずどこかの段に収める(小さな山を削り残して柱にしない。盛る材料は過繁茂した泥)。
                while (ground[i] - t > maxCut) {
                    t += s;
                }
                while (t - ground[i] > maxFill) {
                    t -= s;
                }
                if (ground[i] - t > maxCut || t - ground[i] > maxFill) {
                    mode[i] = MODE_NONE; // 段差が上限より大きく、どの段にも収まらない(極端な崖)。
                    continue;
                }
                mode[i] = MODE_TERRACE;
                terrace[i] = t;
            }
        }

        // 平坦列の水(池 → 水路 → 水たまり)の深さをノイズで決める。
        int[] flatDepth = new int[SIZE * SIZE];
        boolean[] flatPond = new boolean[SIZE * SIZE];
        for (int ix = lo; ix <= hi; ix++) {
            for (int iz = lo; iz <= hi; iz++) {
                int i = ix * SIZE + iz;
                if (mode[i] != MODE_FLAT) {
                    continue;
                }
                int wx = originX + ix;
                int wz = originZ + iz;
                int depth = 0;
                if (stage >= 2) {
                    double p = noise.getValue(wx / 48.0D, wz / 48.0D);
                    if (p > pondThr) {
                        depth = Mth.clamp(1 + Mth.floor((p - pondThr) / Math.max(1.0e-6D, 1.0D - pondThr) * 3.0D), 1, 3);
                        flatPond[i] = true;
                    } else {
                        double c = 1.0D - Math.abs(noise.getValue(wx / 26.0D + 1000.0D, wz / 26.0D - 1000.0D));
                        if (c > creekThr) {
                            depth = c > 0.95D ? 2 : 1;
                        }
                    }
                }
                if (depth == 0) {
                    double q = noise.getValue(wx / 5.0D - 500.0D, wz / 5.0D + 500.0D);
                    if (q > puddleThr) {
                        depth = 1;
                    }
                }
                flatDepth[i] = depth;
            }
        }

        // 周囲がまだ汚染されていない列は待つ(4 隣のうち 3 つ以上が汚染済み or 水になってから)。
        // 汚染の前線でぽつぽつと 1 列ずつ削ると、市松状の穴と柱になるため。
        boolean[] ready = new boolean[SIZE * SIZE];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int i = (lx + PAD) * SIZE + (lz + PAD);
                int n = 0;
                for (int d = 0; d < 4; d++) {
                    byte k = kind[neighbourIndex(i, d)];
                    if (k == KIND_NATURAL || k == KIND_WATER) {
                        n++;
                    }
                }
                ready[i] = n >= 3;
            }
        }

        boolean dirty = false;

        // 「今回のこの処理で実際に書き換える列」。畦・漏れの判定で予測(段の水面)を使ってよいのはこの列だけ。
        // 隣のチャンクの列や、周囲の汚染待ち・処理済みの列は<b>今の地形のまま</b>として見る
        // (以前は隣チャンクの列も「いずれ水になる」と予測して畦を省いたため、まだ削られていない低い地面へ
        // 水が流れ出していた)。
        boolean[] placedNow = new boolean[SIZE * SIZE];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int i = (lx + PAD) * SIZE + (lz + PAD);
                placedNow[i] = kind[i] == KIND_NATURAL && mode[i] != MODE_NONE
                        && done[lx * 16 + lz] < stage && (stage < 2 || ready[i]);
            }
        }

        // 各列が最終的に水面になるかの予測(畦の判定・漏れ判定はこの予測だけに依存し、溢れ口には依存しない)。
        boolean[] becameWater = new boolean[SIZE * SIZE];
        boolean[] rim = new boolean[SIZE * SIZE];
        boolean[] willWater = new boolean[SIZE * SIZE];
        for (int ix = lo; ix <= hi; ix++) {
            for (int iz = lo; iz <= hi; iz++) {
                int i = ix * SIZE + iz;
                if (mode[i] == MODE_FLAT && placedNow[i]) {
                    willWater[i] = flatDepth[i] > 0
                            && enclosedAt(i, ground[i], ground, kind, mode, terrace, shallow, placedNow);
                }
            }
        }
        // 棚田の畦。隣が「水面(今回水になる列 / 既存の水)」でその水面が自段より低い → 下段との境の畦。
        // 隣が「固体の地面」でその上面が自段の水面以下 → 水が越えられる縁なので畦にする。
        // 畦の高さは swampTerraceRimAboveWater 次第(既定は水面と同じ高さの 1 段)。
        for (int ix = lo; ix <= hi; ix++) {
            for (int iz = lo; iz <= hi; iz++) {
                int i = ix * SIZE + iz;
                if (mode[i] != MODE_TERRACE || !placedNow[i]) {
                    continue;
                }
                int t = terrace[i];
                for (int d = 0; d < 4; d++) {
                    int j = neighbourIndex(i, d);
                    int sj = surfaceLevel(j, ground, kind, mode, terrace, shallow, placedNow);
                    boolean waterish = kind[j] == KIND_WATER || shallow[j]
                            || (placedNow[j] && mode[j] == MODE_TERRACE)
                            || (placedNow[j] && mode[j] == MODE_FLAT && willWater[j]);
                    if (waterish ? sj < t + 1 : sj <= t + 1) {
                        rim[i] = true;
                        break;
                    }
                }
                willWater[i] = !rim[i];
            }
        }

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int ix = lx + PAD;
                int iz = lz + PAD;
                int i = ix * SIZE + iz;
                if (kind[i] != KIND_NATURAL) {
                    continue;
                }
                int col = lx * 16 + lz;
                if (done[col] >= stage) {
                    continue; // この段階では処理済み。
                }
                if (stage >= 2 && !ready[i]) {
                    continue; // 周囲の汚染が届いてから(処理済みにはしない)。
                }
                if (done == NO_PROGRESS) {
                    done = progress.columns(cp); // このチャンクで初めて書く列。ここで記録を作る(共有の 0 配列には書かない)。
                }
                done[col] = (byte) stage;
                dirty = true;
                int wx = originX + ix;
                int wz = originZ + iz;
                int g = ground[i];

                if (mode[i] == MODE_TERRACE) {
                    placeTerrace(level, cursor, wx, wz, i, g, terrace[i], rim[i], false, becameWater);
                    continue;
                }
                if (mode[i] != MODE_FLAT) {
                    continue;
                }

                // 平坦: 予測どおり水になる列だけ。
                int depth = flatDepth[i];
                boolean pond = flatPond[i];
                if (depth == 0 || !willWater[i]) {
                    continue;
                }
                if (hasLavaBelow(level, cursor, wx, wz, g - depth, g)) {
                    continue;
                }
                // 地面より上の草などを消し、g-depth+1..g を水、g-depth を泥に。
                clearPlantsAbove(level, cursor, wx, wz, g, 2);
                for (int y = g - depth + 1; y <= g; y++) {
                    cursor.set(wx, y, wz);
                    level.setBlock(cursor, Blocks.WATER.defaultBlockState(), FLAGS);
                }
                cursor.set(wx, g - depth, wz);
                level.setBlock(cursor, ModBlocks.OVERGROWN_MUD.get().defaultBlockState(), FLAGS);
                becameWater[i] = true;
                if (pond && depth >= 2 && unit(hash(seed, wx, wz, 3)) < 0.10D) {
                    cursor.set(wx, g + 1, wz);
                    BlockState lily = ModBlocks.OVERGROWN_LILY.get().defaultBlockState();
                    if (level.getBlockState(cursor).isAir() && lily.canSurvive(level, cursor)) {
                        level.setBlock(cursor, lily, FLAGS);
                    }
                }
            }
        }

        if (dirty) {
            progress.setDirty();
        }

        // 溢れ口(滝): 今回置いた畦のうち、一番低い隣が「実際に水面になっている列」(既存の川・池、または
        // 今回水を置いた下段・池)であるものだけを swampSpillwayChance の割合で切る。予測ではなく実際の
        // 配置結果で判定するので、隣のチャンクのまだ削っていない地面や土手の上へ溢れることはない。
        if (stage >= 2) {
            BlockState water = Blocks.WATER.defaultBlockState();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int ix = lx + PAD;
                    int iz = lz + PAD;
                    int i = ix * SIZE + iz;
                    if (mode[i] != MODE_TERRACE || !rim[i] || done[lx * 16 + lz] < stage) {
                        continue;
                    }
                    int t = terrace[i];
                    int lowest = Integer.MAX_VALUE;
                    int lowestJ = -1;
                    // 落ちる先は「今回水を張った下段の棚田」だけ。自然の川・湖や平坦部の池は縁が水面と同じ高さで、
                    // 着水した流水が岸に広がって流れ出すので対象にしない。
                    for (int d = 0; d < 4; d++) {
                        int j = neighbourIndex(i, d);
                        if (!becameWater[j] || mode[j] != MODE_TERRACE) {
                            continue;
                        }
                        int s = terrace[j] + 1;
                        if (s < lowest) {
                            lowest = s;
                            lowestJ = j;
                        }
                    }
                    if (lowestJ < 0 || (t + 1) - lowest < 2) {
                        continue;
                    }
                    int wx = originX + ix;
                    int wz = originZ + iz;
                    if (unit(hash(seed, wx, wz, 11)) >= spillChance) {
                        continue;
                    }
                    // 角の畦は下の段のほかにも低い面(下の段の畦・低い地面)に接していることがある。そこへも溢れると
                    // 縁の無い水源が滝の脇へ流れ出す(2026-10-09 ユーザー報告)ので、空いてよいのは下の段へ落ちる向きだけ。
                    final int self = i;
                    if (!rimHoldsWater(level, new BlockPos(wx, t + 1, wz), d -> {
                        int j = neighbourIndex(self, d);
                        return becameWater[j] && mode[j] == MODE_TERRACE && terrace[j] < t;
                    })) {
                        continue;
                    }
                    cursor.set(wx, t + 1, wz);
                    if (level.getBlockState(cursor).is(ModBlocks.OVERGROWN_MUD.get())) {
                        level.setBlock(cursor, water, FLAGS);
                        cursor.set(wx, t + 2, wz);
                        if (level.getBlockState(cursor).is(ModBlocks.OVERGROWN_MUD.get())) {
                            level.setBlock(cursor, Blocks.AIR.defaultBlockState(), FLAGS); // 土手の上段があれば切る
                        }
                    }
                }
            }
        }

        // 縫い目の修復: 今回水を張った段と、以前(隣のチャンクの処理や汚染待ちで)畦にされた列を挟んで
        // 同じ高さの水面がある場合、その畦はもう不要なので水面に戻して池を繋げる。
        // 畦は「t と t+1 が泥」、その向こうの列の t+1 が水源(水没ハーフ含む)であることを確認する。
        // t+2 に古い 2 段の土手が残っていれば一緒に消す。
        if (stage >= 2) {
            BlockState mud = ModBlocks.OVERGROWN_MUD.get().defaultBlockState();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int ix = lx + PAD;
                    int iz = lz + PAD;
                    int i = ix * SIZE + iz;
                    if (!becameWater[i] || mode[i] != MODE_TERRACE) {
                        continue;
                    }
                    int t = terrace[i];
                    int wx = originX + ix;
                    int wz = originZ + iz;
                    for (int d = 0; d < 4; d++) {
                        int dx = d == 0 ? 1 : d == 1 ? -1 : 0;
                        int dz = d == 2 ? 1 : d == 3 ? -1 : 0;
                        int nx = wx + dx;
                        int nz = wz + dz;
                        cursor.set(nx, t + 1, nz);
                        if (!level.isLoaded(cursor) || !level.getBlockState(cursor).is(mud.getBlock())) {
                            continue;
                        }
                        cursor.set(nx, t, nz);
                        if (!level.getBlockState(cursor).is(mud.getBlock())) {
                            continue;
                        }
                        cursor.set(nx + dx, t + 1, nz + dz);
                        if (!level.isLoaded(cursor)) {
                            continue;
                        }
                        var beyond = level.getBlockState(cursor).getFluidState();
                        if (!beyond.is(net.minecraft.tags.FluidTags.WATER) || !beyond.isSource()) {
                            continue;
                        }
                        // この畦が横(並びと直角の向き)の低い段のための畦でもあれば残す(水にすると横へ流れ出す)。
                        if (!rimHoldsWater(level, new BlockPos(nx, t + 1, nz), side -> false)) {
                            continue;
                        }
                        cursor.set(nx, t + 1, nz);
                        level.setBlock(cursor, terraceSurfaceState(), FLAGS);
                        cursor.set(nx, t + 2, nz);
                        if (level.getBlockState(cursor).is(mud.getBlock())) {
                            level.setBlock(cursor, Blocks.AIR.defaultBlockState(), FLAGS);
                        }
                    }
                }
            }
        }

        // 水際の地面を泥に(過繁茂段階のみ)。
        if (stage >= 2) {
            BlockState mud = ModBlocks.OVERGROWN_MUD.get().defaultBlockState();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int ix = lx + PAD;
                    int iz = lz + PAD;
                    int i = ix * SIZE + iz;
                    if (kind[i] != KIND_NATURAL || becameWater[i]) {
                        continue;
                    }
                    boolean nearWater = false;
                    for (int d = 0; d < 4 && !nearWater; d++) {
                        int j = neighbourIndex(i, d);
                        nearWater = kind[j] == KIND_WATER || becameWater[j];
                    }
                    if (!nearWater) {
                        continue;
                    }
                    int wx = originX + ix;
                    int wz = originZ + iz;
                    if (unit(hash(seed, wx, wz, 5)) < 0.6D) {
                        cursor.set(wx, ground[i], wz);
                        level.setBlock(cursor, mud, FLAGS);
                    }
                }
            }
        }
    }

    /**
     * 棚田の 1 列。段の高さ {@code t} まで削る / 盛り、{@code t} を泥、{@code t+1} を水(または畦の泥、
     * 溢れ口の水)にする。
     *
     * <p>畦(仕切り)の高さは {@code swampTerraceRimAboveWater} で決まる。既定(false)では水面と
     * ちょうど同じ高さ({@code t+1} に泥 1 ブロックだけ)にし、true なら
     * {@code t+2} にもう 1 段積んで水面より高い土手にする(滝の着水で水面の上に広がる流水を枠の中に
     * 留められるが、仕切りが水面から飛び出して見える)。
     */
    private static void placeTerrace(ServerLevel level, BlockPos.MutableBlockPos cursor, int wx, int wz, int i,
                                     int g, int t, boolean rim, boolean spill, boolean[] becameWater) {
        if (hasLavaBelow(level, cursor, wx, wz, Math.min(g, t) - 1, Math.max(g, t) + 1)) {
            return;
        }

        BlockState mud = ModBlocks.OVERGROWN_MUD.get().defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        // 地面より上の草・低木を消す(削る場合は削る範囲より上、盛る場合は盛る範囲より上)。
        clearPlantsAbove(level, cursor, wx, wz, Math.max(g, t + 1), 3);

        // 削る: t+2 .. g を空気に。
        for (int y = g; y >= t + 2; y--) {
            cursor.set(wx, y, wz);
            level.setBlock(cursor, air, FLAGS);
        }
        // 盛る: g+1 .. t-1 を泥に。
        for (int y = g + 1; y <= t - 1; y++) {
            cursor.set(wx, y, wz);
            level.setBlock(cursor, mud, FLAGS);
        }
        cursor.set(wx, t, wz);
        level.setBlock(cursor, mud, FLAGS);
        cursor.set(wx, t + 1, wz);
        if (rim && !spill) {
            // 畦。既定では水面と同じ高さの 1 段だけ。
            level.setBlock(cursor, mud, FLAGS);
            if (OvergrownVegetationConfig.COMMON.swampTerraceRimAboveWater.get()) {
                cursor.set(wx, t + 2, wz);
                level.setBlock(cursor, mud, FLAGS);
            }
        } else {
            level.setBlock(cursor, terraceSurfaceState(), FLAGS);
            becameWater[i] = true;
        }
    }

    /**
     * 畦 {@code pos} を水源にしても横へ流れ出さないか。4 方向の隣の同じ高さを<b>実際のブロック</b>で見る
     * (予測の {@code ground}/{@code terrace} ではなく。溶岩で飛ばした列など、予測どおりに置かれていない列がある)。
     * 動きを止めるブロック(壁・畦の泥)か水源(水没ハーフを含む)なら漏れない。それ以外(空気・流れている水・読み込まれていない)は、
     * {@code fallOk} がその向き({@link #neighbourIndex} と同じ番号)を許すときだけ空いていてよい(溢れ口の、下の段へ落ちる向き)。
     *
     * <p>2026-10-09: 角の畦を溢れ口にしたとき・縫い目の修復で畦を水に戻したとき、1 方向しか見ていなかったので、
     * 縁の無い水源が段の角から流れ出していた(ユーザー報告)。
     */
    public static boolean rimHoldsWater(ServerLevel level, BlockPos pos, java.util.function.IntPredicate fallOk) {
        BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        for (int d = 0; d < 4; d++) {
            side.set(pos.getX() + (d == 0 ? 1 : d == 1 ? -1 : 0), pos.getY(), pos.getZ() + (d == 2 ? 1 : d == 3 ? -1 : 0));
            if (level.isLoaded(side)) {
                BlockState state = level.getBlockState(side);
                var fluid = state.getFluidState();
                if (state.blocksMotion() || fluid.is(net.minecraft.tags.FluidTags.WATER) && fluid.isSource()) {
                    continue;
                }
            }
            if (!fallOk.test(d)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 棚田の水面のブロック: 水深 0.5 なら水没した泥のハーフブロック(下付き)、そうでなければ水源。
     *
     * <p>どちらも<b>水面はブロックの上端</b>({@code t+2} の面)にあることに注意。水没ハーフの水は
     * ブロック 1 つぶんの水源なので、浅い段でも水面の高さは水源ブロックと同じで、違うのは
     * 「上に乗ったときの水深(0.5)」だけ。だから畦は泥 1 ブロック({@code t+1})で水面と同じ高さになる。
     */
    private static BlockState terraceSurfaceState() {
        return OvergrownVegetationConfig.COMMON.swampTerraceShallow.get()
                ? ModBlocks.OVERGROWN_MUD_SLAB.get().defaultBlockState()
                        .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                        .setValue(SlabBlock.WATERLOGGED, Boolean.TRUE)
                : Blocks.WATER.defaultBlockState();
    }

    /**
     * 列 {@code j} の「水面または上面」の高さ。水になる予定の列は水面、それ以外は最上部の固体。
     * 「隣の水面がこれ以上なら、この高さまでの水は漏れない」判定に使う。
     */
    private static int surfaceLevel(int j, int[] ground, byte[] kind, byte[] mode, int[] terrace, boolean[] shallow,
                                    boolean[] placedNow) {
        if (placedNow[j] && mode[j] == MODE_TERRACE) {
            return terrace[j] + 1; // 今回この段の水面になる列
        }
        return ground[j] + (shallow[j] ? 1 : 0); // 深さ 1 の水を載せた列はその水面
    }

    /** 平坦モードで、上面 {@code g} を水にしても 4 方向へ漏れないか。 */
    private static boolean enclosedAt(int i, int g, int[] ground, byte[] kind, byte[] mode, int[] terrace,
                                      boolean[] shallow, boolean[] placedNow) {
        for (int d = 0; d < 4; d++) {
            int j = neighbourIndex(i, d);
            if (kind[j] == KIND_NONE) {
                return false;
            }
            if (surfaceLevel(j, ground, kind, mode, terrace, shallow, placedNow) < g) {
                return false;
            }
        }
        return true;
    }

    private static int neighbourIndex(int i, int d) {
        return switch (d) {
            case 0 -> i + SIZE;
            case 1 -> i - SIZE;
            case 2 -> i + 1;
            default -> i - 1;
        };
    }

    // ------------------------------------------------------------------------------------------
    // 列の走査
    // ------------------------------------------------------------------------------------------

    /**
     * 列の「地面」(葉・植物・原木を除いた最上部の固体)の高さと種類を求める。
     * 種類: 自然の地面 / 水 / 泥(処理済み) / 木 / 人工物 / 不明。
     */
    private static void scanColumn(ServerLevel level, int x, int z, BlockPos.MutableBlockPos cursor,
                                   int[] ground, byte[] kind, boolean[] shallow, int i, boolean skipArtificial) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int minY = level.getMinBuildHeight();
        if (top < minY) {
            ground[i] = minY;
            kind[i] = KIND_NONE;
            return;
        }
        cursor.set(x, top, z);
        BlockState state = level.getBlockState(cursor);
        int y = top;
        if (!state.getFluidState().isEmpty()) {
            if (!state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                ground[i] = top;
                kind[i] = KIND_NONE;
                return;
            }
            // 深さ 1 の水(前段階で置いた水たまり、浅い岸)は「水面」ではなく、その下の地面として扱う。
            // そうしないと周りが削られたあと、水を頭に載せた柱が取り残される(浮いたブロックの原因)。
            cursor.set(x, top - 1, z);
            BlockState below = level.getBlockState(cursor);
            if (!below.getFluidState().isEmpty() || !below.blocksMotion()) {
                ground[i] = top;
                kind[i] = KIND_WATER;
                return;
            }
            y = top - 1;
            shallow[i] = true;
        }

        // 葉(このMODの葉は LeavesBlock ではないので heightmap に乗る)や植物を飛ばして地面まで降りる。
        int walked = 0;
        while (y > minY && walked < MAX_WALK_DOWN) {
            cursor.set(x, y, z);
            state = level.getBlockState(cursor);
            if (isLog(state)) {
                ground[i] = y;
                kind[i] = KIND_TREE;
                return;
            }
            if (isLeaves(state) || !state.blocksMotion() || state.canBeReplaced()) {
                y--;
                walked++;
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                ground[i] = y;
                kind[i] = state.getFluidState().is(net.minecraft.tags.FluidTags.WATER) ? KIND_WATER : KIND_NONE;
                return;
            }
            break;
        }
        ground[i] = y;
        if (!isNaturalGround(state)) {
            kind[i] = skipArtificial ? KIND_ARTIFICIAL : KIND_NATURAL;
            return;
        }
        // まだ汚染されていない自然の地面は触らない(汚染が届いた列から順に水・棚田になっていく)。
        if (!state.is(ModTags.CORRUPTED)) {
            kind[i] = KIND_CLEAN;
            return;
        }
        // 頭上 4 マスに人工物(屋根・床)があれば触らない。
        if (skipArtificial) {
            for (int dy = 1; dy <= 4; dy++) {
                cursor.set(x, y + dy, z);
                BlockState above = level.getBlockState(cursor);
                if (above.isAir() || !above.blocksMotion() || above.canBeReplaced() || isLeaves(above) || isLog(above)) {
                    continue;
                }
                if (!isNaturalGround(above)) {
                    kind[i] = KIND_ARTIFICIAL;
                    return;
                }
            }
        }
        kind[i] = KIND_NATURAL;
    }

    private static boolean isLog(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(ModBlocks.OVERGROWN_LOG.get()) || state.is(ModBlocks.OVERGROWN_WOOD.get())
                || state.is(ModBlocks.GIANT_OVERGROWN_LOG.get()) || state.is(ModBlocks.OVERGROWN_SWAMP_LOG.get())
                || state.is(ModBlocks.OVERGROWN_SWAMP_WOOD.get()) || state.is(ModBlocks.FUNGAL_PILLAR.get());
    }

    private static boolean isLeaves(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(ModBlocks.OVERGROWN_LEAVES.get())
                || state.is(ModBlocks.OVERGROWN_SWAMP_LEAVES.get());
    }

    /** 自然の地面(汚染済みの地面ブロックを含む)か。建築ブロックは含まない。 */
    private static boolean isNaturalGround(BlockState state) {
        if (state.is(ModTags.CORRUPTED)) {
            return state.blocksMotion() && !isLog(state) && !isLeaves(state);
        }
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.TERRACOTTA) || state.is(BlockTags.SNOW)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.MUD) || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.CALCITE)
                || state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
                || state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
                || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES)
                || state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES);
    }

    private static boolean hasLavaBelow(ServerLevel level, BlockPos.MutableBlockPos cursor, int x, int z, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            cursor.set(x, y, z);
            if (level.getBlockState(cursor).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    /** {@code y+1 .. y+count} の草・低木(動きを止めないブロック)を消す。 */
    private static void clearPlantsAbove(ServerLevel level, BlockPos.MutableBlockPos cursor, int x, int z, int y, int count) {
        for (int dy = 1; dy <= count; dy++) {
            cursor.set(x, y + dy, z);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) {
                continue;
            }
            if (!state.blocksMotion() || state.canBeReplaced()) {
                level.setBlock(cursor, Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }
    }

    private static long hash(long seed, int a, int b, int salt) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h ^= (long) a * 0xBF58476D1CE4E5B9L;
        h = Long.rotateLeft(h, 23) * 0x94D049BB133111EBL;
        h ^= (long) b * 0xD6E8FEB86659FD93L;
        h = Long.rotateLeft(h, 31) ^ (h >>> 29);
        h += salt * 0x632BE59BD9B4E019L;
        h ^= h >>> 32;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return h;
    }

    private static double unit(long h) {
        return (h & 0xFFFFFFL) / (double) 0x1000000L;
    }

    private SwampFlooder() {
    }
}
