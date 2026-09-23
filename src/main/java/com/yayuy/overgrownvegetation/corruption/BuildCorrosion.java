package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 建築浸食 —— 繁茂系バイオームの中に<b>後から置かれた</b>ブロック(プレイヤーの建築、ピストンで動いた
 * ブロック、他 MOD の構造物など)を、時間経過で少しずつ汚染していく処理。
 *
 * <p>既存の 2 つの汚染仕上げ({@link OvergrownBiomeCreep})には穴がある。
 * <ul>
 *     <li>地表のフロンティア浸食は、各列の最上部から {@code overgrownBiomeCreepSurfaceDepth} ブロックしか
 *         見ない。建物の屋根と壁の最上段は拾えても、屋根より下の壁・床・内装には届かない。</li>
 *     <li>深部浸食は地表から岩盤まで一度だけ降りていくカーソル方式で、掘り切ったチャンクは
 *         再起動まで二度と見直さない。そのあとに建てたものは永遠に残る。</li>
 * </ul>
 * このクラスは両方の穴を埋める。チャンク内の非空セクションを丸ごと走査し、
 * 「未汚染で汚染可能、かつ<b>既存の汚染に面している</b>ブロック」を 3 次元のフロンティアとして集め、
 * 汚染源の隣接数に応じた確率で 1 回あたり数個ずつ汚染する。汚染は地面と接した土台から壁を這い上がり、
 * 屋根・内装へと有機的に広がる(チャンク単位で一括塗り潰しにはならない)。
 *
 * <p>対象になるブロックは次のいずれか。
 * <ul>
 *     <li>空気に面している(壁・床・屋根・家具の露出面)。既存の汚染に 1 面でも接していれば候補。</li>
 *     <li>完全に埋まっているが、6 面のうち {@code buildCorrosionBuriedNeighbors}(既定 3)面以上が既に汚染済み
 *         (二重壁の内側など。周囲が汚染されきってから最後に落ちる)。深部浸食がまだ届いていない
 *         地下の石は上の 1 面しか汚染に接していないので、ここでは触らず深部浸食に任せる。</li>
 * </ul>
 * 汚染にまったく接していない浮遊建築は、{@code buildCorrosionSeedChance} の確率で露出ブロックを 1 つ
 * 「種」として汚染し、そこから広げる。
 *
 * <p>走査するチャンクは、(1) {@link BlockEvent.EntityPlaceEvent} で繁茂系バイオーム内に汚染可能ブロックが
 * 置かれたチャンク(即時)、(2) プレイヤー周辺の対象チャンクの定期再走査({@code buildCorrosionIdleRescanTicks})
 * の 2 経路で拾う。候補が残っているチャンクは短い間隔({@code buildCorrosionIntervalTicks} ごと)で
 * 続きを処理し、候補が尽きたチャンクは長い間隔に戻る。設置イベントの記録はメモリ上だけなので、
 * 再起動後は定期再走査が拾い直す。
 *
 * <p>既定では「過繁茂」段階({@link ModBiomes#isFullyOvergrown})のチャンクだけが対象。
 * {@code buildCorrosionInOvergrowthStage} を true にすると「繁茂」段階でも走る。
 * 汚染判定・変換先・装飾植物は通常の侵食({@link CorruptionRegistry} / {@link CorruptionSpreader})と同じ。
 * チェストなどのブロックエンティティは {@code corruptBlockEntities} が false なら汚染されない。
 * この分の汚染は {@code CorruptionCounter} の累計には数えない(深部浸食と同じ扱い)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BuildCorrosion {

    /** ディメンションごとの「チャンク → 次に走査してよくなるゲーム内時刻」。既定 {@code Long.MIN_VALUE} = 即対象。 */
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> NEXT_VISIT = new HashMap<>();
    /** ディメンションごとの「設置イベントで汚された(次のパスで必ず見る)チャンク」。 */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> DIRTY = new HashMap<>();
    /** クールダウン表が肥大化しすぎたら丸ごと捨てる閾値。 */
    private static final int MAP_MAX_ENTRIES = 20_000;
    /** 1 回の走査で集める候補数の上限(超えた分は次回に回す)。 */
    private static final int MAX_CANDIDATES = 512;

    // ------------------------------------------------------------------------------------------
    // 設置イベント: 繁茂系バイオーム内に汚染可能ブロックが置かれたらそのチャンクを即対象にする
    // ------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!OvergrownVegetationConfig.COMMON.buildCorrosion.get()) {
            return;
        }
        BlockState placed = event.getPlacedBlock();
        if (placed.isAir() || placed.is(ModTags.CORRUPTED)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (!ModBiomes.isOvergrownLike(level.getBiome(pos))) {
            return;
        }
        if (!CorruptionRegistry.canCorrupt(level, pos, placed)) {
            return;
        }
        markDirty(level, new ChunkPos(pos));
    }

    /** このチャンクを次のパスで必ず走査させる(クールダウンを無視)。 */
    public static void markDirty(ServerLevel level, ChunkPos chunkPos) {
        DIRTY.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet()).add(chunkPos.toLong());
    }

    // ------------------------------------------------------------------------------------------
    // tick: 対象チャンクを集めて予算ぶんだけ浸食する
    // ------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.buildCorrosion.get() || !cfg.convertToOvergrownBiome.get()) {
            return;
        }
        int interval = Math.max(1, cfg.buildCorrosionIntervalTicks.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        int chunkBudget = cfg.buildCorrosionChunksPerPass.get();
        if (chunkBudget <= 0) {
            return;
        }

        boolean includeOvergrowthStage = cfg.buildCorrosionInOvergrowthStage.get();
        int radiusChunks = Math.max(1, Mth.ceil(cfg.overgrownBiomeCreepRadius.get() / 16.0F));
        long now = level.getGameTime();
        long activeTicks = interval;
        long idleTicks = cfg.buildCorrosionIdleRescanTicks.get();

        Long2LongOpenHashMap nextVisit = NEXT_VISIT.computeIfAbsent(level.dimension(), key -> newVisitMap());
        LongOpenHashSet dirty = DIRTY.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet());

        // 1. 設置イベントで汚されたチャンクを最優先で集める。
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList eligible = new LongArrayList();
        if (!dirty.isEmpty()) {
            LongArrayList dirtyList = new LongArrayList(dirty);
            shuffle(dirtyList, level.random);
            for (int i = 0; i < dirtyList.size(); i++) {
                long key = dirtyList.getLong(i);
                ChunkPos cp = new ChunkPos(key);
                if (!level.hasChunk(cp.x, cp.z)) {
                    dirty.remove(key);
                    continue;
                }
                if (!isTargetChunk(level, cp.x, cp.z, includeOvergrowthStage)) {
                    dirty.remove(key); // 繁茂段階など、いまは対象外。定期再走査に任せる。
                    continue;
                }
                if (seen.add(key)) {
                    eligible.add(key);
                }
            }
        }

        // 2. プレイヤー周辺の対象チャンクのうちクールダウンの切れたもの。
        LongArrayList periodic = new LongArrayList();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            ChunkPos center = player.chunkPosition();
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                    int cx = center.x + dx;
                    int cz = center.z + dz;
                    long key = ChunkPos.asLong(cx, cz);
                    if (!seen.add(key)) {
                        continue;
                    }
                    if (now < nextVisit.get(key)) {
                        continue;
                    }
                    if (!level.hasChunk(cx, cz)) {
                        continue;
                    }
                    if (!isTargetChunk(level, cx, cz, includeOvergrowthStage)) {
                        continue;
                    }
                    periodic.add(key);
                }
            }
        }
        shuffle(periodic, level.random);
        eligible.addAll(periodic);
        if (eligible.isEmpty()) {
            return;
        }

        int blocksPerChunk = cfg.buildCorrosionBlocksPerChunk.get();
        double baseChance = cfg.buildCorrosionBaseChance.get();
        double perNeighbor = cfg.buildCorrosionChancePerNeighbor.get();
        double seedChance = cfg.buildCorrosionSeedChance.get();
        int buriedNeighbors = cfg.buildCorrosionBuriedNeighbors.get();

        int limit = Math.min(chunkBudget, eligible.size());
        for (int i = 0; i < limit; i++) {
            long key = eligible.getLong(i);
            dirty.remove(key);
            boolean remaining = corrodeChunk(level, new ChunkPos(key), blocksPerChunk,
                    baseChance, perNeighbor, seedChance, buriedNeighbors);
            nextVisit.put(key, now + (remaining ? activeTicks : idleTicks));
        }

        if (nextVisit.size() > MAP_MAX_ENTRIES) {
            nextVisit.clear();
        }
    }

    /**
     * 1 チャンクの建築浸食を 1 回ぶん進める。
     *
     * @return まだ候補(未汚染で汚染可能なブロック)が残っているか。true なら短い間隔で続きを処理する。
     */
    private static boolean corrodeChunk(ServerLevel level, ChunkPos chunkPos, int blocksBudget,
                                        double baseChance, double perNeighbor, double seedChance,
                                        int buriedNeighbors) {
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        RandomSource random = level.random;
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // 隣接 6 面の読みは大半が同じチャンク内なので、チャンクキャッシュ付きの読み
        // (結果は Level#getBlockState と同じ)で毎回のチャンク検索を省く。
        ChunkCachedReader reader = new ChunkCachedReader(level);

        // 1. 非空セクションを走査し、フロンティア候補 {x, y, z, 汚染源数} と、種候補(露出しているが
        //    汚染に接していないブロック)を集める。上限に達したら打ち切り(残りは次回)。
        List<int[]> frontier = new ArrayList<>();
        List<BlockPos> exposedUntouched = new ArrayList<>();
        int untouchedTotal = 0;
        boolean truncated = false;

        LevelChunkSection[] sections = chunk.getSections();
        scan:
        for (int si = sections.length - 1; si >= 0; si--) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int sectionY = chunk.getSectionYFromSectionIndex(si) << 4;
            for (int ly = 15; ly >= 0; ly--) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        if (state.isAir() || state.is(ModTags.CORRUPTED)) {
                            continue;
                        }
                        cursor.set(baseX + lx, sectionY + ly, baseZ + lz);
                        if (!CorruptionRegistry.canCorrupt(level, cursor, state)) {
                            continue;
                        }
                        int corruptedFaces = 0;
                        boolean exposed = false;
                        for (Direction dir : Direction.values()) {
                            BlockState n = reader.getBlockState(cursor.getX() + dir.getStepX(),
                                    cursor.getY() + dir.getStepY(), cursor.getZ() + dir.getStepZ());
                            if (n.is(ModTags.CORRUPTED)) {
                                corruptedFaces++;
                            } else if (n.isAir()) {
                                exposed = true;
                            }
                        }
                        if (!exposed && corruptedFaces < buriedNeighbors) {
                            continue; // 埋まった石など。深部浸食の担当。
                        }
                        untouchedTotal++;
                        if (corruptedFaces > 0) {
                            frontier.add(new int[] {cursor.getX(), cursor.getY(), cursor.getZ(), corruptedFaces});
                        } else if (exposedUntouched.size() < 64) {
                            exposedUntouched.add(cursor.immutable());
                        }
                        if (frontier.size() >= MAX_CANDIDATES) {
                            truncated = true;
                            break scan;
                        }
                    }
                }
            }
        }

        if (frontier.isEmpty() && exposedUntouched.isEmpty()) {
            return false; // このチャンクにはいま汚染すべき建築ブロックが無い。
        }

        // 2. フロンティアをシャッフルし、汚染源の面数に応じた確率で変換。
        shuffle(frontier, random);
        int converted = 0;
        for (int[] cell : frontier) {
            if (converted >= blocksBudget) {
                break;
            }
            double chance = baseChance + (cell[3] - 1) * perNeighbor;
            if (chance < 1.0D && random.nextDouble() >= chance) {
                continue;
            }
            cursor.set(cell[0], cell[1], cell[2]);
            if (corrodeAt(level, cursor, random)) {
                converted++;
            }
        }

        // 3. 汚染に接していない浮遊建築などには、低確率で種を 1 つ植える。
        if (frontier.isEmpty() && !exposedUntouched.isEmpty() && random.nextDouble() < seedChance) {
            BlockPos seed = exposedUntouched.get(random.nextInt(exposedUntouched.size()));
            corrodeAt(level, seed, random);
        }

        return truncated || untouchedTotal > 0;
    }

    /** 1 ブロックを汚染する。変換先が同じブロックなら何もしない。 */
    private static boolean corrodeAt(ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(ModTags.CORRUPTED) || !CorruptionRegistry.canCorrupt(level, pos, state)) {
            return false;
        }
        BlockState corrupted = CorruptionRegistry.corrupt(level, pos, state, random);
        if (CorruptionRegistry.noChange(state, corrupted)) {
            return false;
        }
        BlockPos hit = pos.immutable();
        CorruptionRegistry.placeCorrupted(level, hit, state, corrupted, Block.UPDATE_ALL);
        CorruptionSpreader.growSurfacePlants(level, hit, corrupted, CorruptionSpreader.surfaceGrowthChance(level, hit));
        return true;
    }

    /** チャンク中心の 1 点を見て、そのチャンクが建築浸食の対象バイオームか。 */
    private static boolean isTargetChunk(ServerLevel level, int cx, int cz, boolean includeOvergrowthStage) {
        BlockPos sample = new BlockPos((cx << 4) + 8, level.getSeaLevel(), (cz << 4) + 8);
        var biome = level.getBiome(sample);
        return includeOvergrowthStage ? ModBiomes.isOvergrownLike(biome) : ModBiomes.isFullyOvergrown(biome);
    }

    private static Long2LongOpenHashMap newVisitMap() {
        Long2LongOpenHashMap map = new Long2LongOpenHashMap();
        map.defaultReturnValue(Long.MIN_VALUE);
        return map;
    }

    private static void shuffle(LongArrayList list, RandomSource random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            long tmp = list.getLong(i);
            list.set(i, list.getLong(j));
            list.set(j, tmp);
        }
    }

    private static <T> void shuffle(List<T> list, RandomSource random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    private BuildCorrosion() {
    }
}
