package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 過繁茂バイオーム({@link ModBiomes#OVERGROWN})の汚染を時間経過で仕上げる処理。汚染シダ
 * ({@code blight_fern})が近くにいなくても動く。2 つのパスからなる。
 *
 * <ol>
 *     <li><b>バイオーム内のフロンティア浸食</b>({@link #sweepInterior})——
 *         過繁茂バイオームになった後も地表にまばらに残る未汚染ブロックを潰す。ただし
 *         「<b>既に汚染されたブロックに隣接する</b>未汚染ブロック」だけを、1 マスずつ外側へ
 *         汚染する。汚染源となる隣接ブロックが多い(=汚染に囲まれた取りこぼし)ほど高い確率で、
 *         少ない(=平らな縁)ほど低い確率で汚染するので、汚染の縁は木の根や苔のように不規則な
 *         フロンティアとして有機的に広がり、チャンク単位で地表を一括で塗り潰すことがない
 *         (= 汚染が矩形に広がらない)。1 tick あたりのチャンク数
 *         ({@code overgrownBiomeCreepChunksPerTick})と 1 チャンクあたりの汚染数
 *         ({@code overgrownBiomeCreepMaxConversionsPerChunk})に上限があるので、広大な範囲が
 *         一度に過繁茂バイオーム化してもサーバーは固まらない。処理し終えたチャンクはしばらく
 *         再処理しない({@code overgrownBiomeCreepRescanTicks} /
 *         掃除済みなら {@code overgrownBiomeCreepCleanRescanTicks})。</li>
 *     <li><b>バイオームの縁のにじみ</b>({@link #tryBleedOnce})——
 *         過繁茂バイオームの縁から {@code overgrownBiomeCreepMargin} ブロックぶんは、
 *         バイオームの外(チャンク境界の向こう側を含む)へも汚染をはみ出させる。ここでも
 *         「既存の汚染に隣接している地点」しか汚染しないので、バイオームの外に孤立した
 *         汚染ブロックが点在することはない。はみ出した汚染がやがて隣チャンクの地表汚染割合を
 *         閾値({@code overgrownBiomeThreshold})まで押し上げれば、そのチャンクも
 *         {@link OvergrownBiomeConverter} でバイオーム書き換えされる。</li>
 *     <li><b>深部浸食</b>({@link #deepenInterior})——
 *         地表が汚染しきった<b>過繁茂</b>バイオーム({@link ModBiomes#isFullyOvergrown})のチャンクを、
 *         時間経過で地表から下へ 1 層ずつ汚染し、最終的にチャンク内の全固体ブロック(地表より上の
 *         建築物・浮遊ブロックから、地下深部の露出していない石・鉱石まで)を汚染しきる。
 *         カーソル(次に浸食する Y 層)をチャンクごとに持ち、1 回の処理で走査する層数
 *         ({@code overgrownBiomeCreepDeepLayersPerVisit})と汚染するブロック数
 *         ({@code overgrownBiomeCreepDeepBlocksPerChunk})に上限があるので、広大な過繁茂バイオームの
 *         地下全体を汚染してもサーバーは固まらない。地下の一括変換なので矩形問題は起きない
 *         (そもそも地下は見えない)。この分の汚染は {@link CorruptionCounter} の累計には数えない。</li>
 * </ol>
 *
 * <p>どのパスも汚染判定・変換先・装飾植物の生やし方は通常の侵食
 * ({@link CorruptionSpreader} / {@link CorruptionRegistry})と同じ処理を流用する。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OvergrownBiomeCreep {

    /**
     * ディメンションごとの「チャンク({@link ChunkPos#toLong()}) → 次にそのチャンクをフロンティア浸食
     * してよくなるゲーム内時刻」。処理済みのチャンクを毎回また舐め直さないためのクールダウン表。
     * デフォルト値は {@code Long.MIN_VALUE}(= まだ一度も処理していない → 即対象)。
     */
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> NEXT_SWEEP = new HashMap<>();

    /** クールダウン表が肥大化しすぎたら丸ごと捨てる閾値(遠く離れたチャンクのエントリが溜まり続けないように)。 */
    private static final int SWEEP_MAP_MAX_ENTRIES = 20_000;

    /**
     * パス3(深部浸食)のディメンションごとの「チャンク → 次に深部浸食してよくなるゲーム内時刻」。
     * {@link #NEXT_SWEEP} と同じ役割のクールダウン表。
     */
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> NEXT_DEEPEN = new HashMap<>();

    /**
     * パス3(深部浸食)のディメンションごとの「チャンク → 次に浸食を始める Y 層」。地表から下へ 1 層ずつ
     * 降りていく進捗カーソル。既定値 {@link Integer#MAX_VALUE} = 未開始(チャンク最上部の固体ブロックから)。
     * {@code minBuildHeight - 1} 以下 = そのチャンクは深部まで汚染しきった(以降は対象外)。
     * ワールドには保存しない —— 再起動後は最上部から舐め直すが、汚染済みの層は {@code layersPerVisit}
     * 上限つきで素早く読み飛ばして進捗を貼り直す。
     */
    private static final Map<ResourceKey<Level>, Long2IntOpenHashMap> DEEPEN_CURSOR = new HashMap<>();

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.overgrownBiomeCreep.get() || !cfg.convertToOvergrownBiome.get()) {
            return;
        }
        int interval = Math.max(1, cfg.overgrownBiomeCreepIntervalTicks.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }

        sweepInterior(level, cfg);
        bleedEdges(level, cfg);
        deepenInterior(level, cfg);
    }

    // ------------------------------------------------------------------------------------------
    // パス1: バイオーム内のフロンティア浸食
    // ------------------------------------------------------------------------------------------

    /**
     * プレイヤー周辺の過繁茂バイオームのチャンクを、クールダウンの切れたものから
     * {@code overgrownBiomeCreepChunksPerTick} 個まで取り上げ、それぞれ「既存の汚染に隣接する
     * 未汚染ブロック」を 1 マスずつ外側へ汚染する({@link #growChunkFrontier})。
     */
    private static void sweepInterior(ServerLevel level, OvergrownVegetationConfig.Common cfg) {
        int chunkBudget = cfg.overgrownBiomeCreepChunksPerTick.get();
        if (chunkBudget <= 0) {
            return;
        }
        int radiusChunks = Math.max(1, Mth.ceil(cfg.overgrownBiomeCreepRadius.get() / 16.0F));
        int maxPerChunk = cfg.overgrownBiomeCreepMaxConversionsPerChunk.get();
        int surfaceDepth = cfg.overgrownBiomeCreepSurfaceDepth.get();
        double growthChance = cfg.growthChance.get();
        double frontierBaseChance = cfg.overgrownBiomeCreepFrontierBaseChance.get();
        double frontierChancePerNeighbor = cfg.overgrownBiomeCreepFrontierChancePerNeighbor.get();
        long now = level.getGameTime();
        long rescanTicks = cfg.overgrownBiomeCreepRescanTicks.get();
        long cleanRescanTicks = cfg.overgrownBiomeCreepCleanRescanTicks.get();

        Long2LongOpenHashMap nextSweep =
                NEXT_SWEEP.computeIfAbsent(level.dimension(), key -> newSweepMap());

        // まずクールダウンの切れた対象チャンクを全プレイヤーぶん洗い出す(重複除去)。
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList eligible = new LongArrayList();
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
                    if (now < nextSweep.get(key)) {
                        continue;
                    }
                    if (!level.hasChunk(cx, cz)) {
                        continue;
                    }
                    if (!isOvergrownChunk(level, cx, cz)) {
                        continue;
                    }
                    eligible.add(key);
                }
            }
        }
        if (eligible.isEmpty()) {
            return;
        }

        // 偏りなく順に片付けていくため、対象をシャッフルしてから予算ぶんだけ処理する。
        shuffle(eligible, level.random);
        int limit = Math.min(chunkBudget, eligible.size());
        for (int i = 0; i < limit; i++) {
            ChunkPos chunkPos = new ChunkPos(eligible.getLong(i));
            int converted = growChunkFrontier(level, chunkPos, maxPerChunk, surfaceDepth, growthChance,
                    frontierBaseChance, frontierChancePerNeighbor);
            nextSweep.put(eligible.getLong(i), now + (converted > 0 ? rescanTicks : cleanRescanTicks));
        }

        if (nextSweep.size() > SWEEP_MAP_MAX_ENTRIES) {
            nextSweep.clear();
        }
    }

    /**
     * 1 チャンクの中で「既に汚染されたブロックに隣接する未汚染ブロック(フロンティア)」を洗い出し、
     * 汚染源の隣接数に応じた確率で最大 {@code maxConversions} 個まで汚染ブロックへ置き換える。
     *
     * <p>孤立した(周囲に汚染がまったく無い)未汚染ブロックには絶対に触れないので、チャンク単位で
     * 地表がまとめて塗り潰されることは無く、汚染は常に既存の汚染の縁から 1 マスずつ有機的に広がる。
     *
     * @return 実際に置き換えたブロック数
     */
    private static int growChunkFrontier(ServerLevel level, ChunkPos chunkPos, int maxConversions,
                                         int surfaceDepth, double growthChance,
                                         double frontierBaseChance, double frontierChancePerNeighbor) {
        RandomSource random = level.random;
        int minY = level.getMinBuildHeight();
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // 1. チャンク + 外周1リング(18x18)の各列に汚染ブロックがあるか事前計算する。
        //    index = ローカル座標 + 1(ローカル -1..16 → index 0..17)。
        boolean[][] corruptedColumn = new boolean[18][18];
        for (int ix = 0; ix < 18; ix++) {
            for (int iz = 0; iz < 18; iz++) {
                int x = baseX + ix - 1;
                int z = baseZ + iz - 1;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                int topY = surfaceTopY(level, x, z);
                if (topY < minY) {
                    continue;
                }
                corruptedColumn[ix][iz] = columnHasCorruption(level, cursor, x, topY, z, minY, surfaceDepth);
            }
        }

        // 2. チャンク内256列を走査。凍結面を剥がしつつ、フロンティア(未汚染だが自列/隣接列に
        //    汚染がある = 既存の汚染から1マスだけ外側)の列を候補に集める。
        //    候補は {targetX, targetY, targetZ, 汚染源の隣接数} を持つ。
        List<int[]> frontier = new ArrayList<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                int topY = surfaceTopY(level, x, z);
                if (topY < minY) {
                    continue;
                }

                // 過繁茂バイオームは常夏。汚染が届く前に元バイオームで積もっていた雪・氷、
                // また極端に高い建築上限で万一降ってしまった雪も、走査ついでに剥がす。
                if (stripFrozenSurface(level, cursor, x, topY, z)) {
                    // 凍結面を剥がすと地表が変わる。この列は次の走査で改めて拾う。
                    continue;
                }

                BlockPos target = findCorruptibleInColumn(level, cursor, x, topY, z, minY, surfaceDepth);
                if (target == null) {
                    continue; // 既に片付いている / 岩盤・水で打ち切り
                }

                int ix = lx + 1;
                int iz = lz + 1;
                int sources = 0;
                if (corruptedColumn[ix][iz]) sources++;       // 自列(汚染植物の下に埋もれた地面)
                if (corruptedColumn[ix - 1][iz]) sources++;
                if (corruptedColumn[ix + 1][iz]) sources++;
                if (corruptedColumn[ix][iz - 1]) sources++;
                if (corruptedColumn[ix][iz + 1]) sources++;
                if (sources == 0) {
                    continue; // フロンティアではない = 孤立した未汚染列。触らない(矩形の湧きを防ぐ)。
                }

                frontier.add(new int[] {target.getX(), target.getY(), target.getZ(), sources});
            }
        }
        if (frontier.isEmpty()) {
            return 0;
        }

        // 3. シャッフルして、汚染源が多い列ほど高い確率で変換。上限は maxConversions。
        shuffle(frontier, random);
        int converted = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int[] cell : frontier) {
            if (converted >= maxConversions) {
                break;
            }
            double chance = frontierBaseChance + (cell[3] - 1) * frontierChancePerNeighbor;
            if (chance < 1.0D && random.nextDouble() >= chance) {
                continue;
            }

            at.set(cell[0], cell[1], cell[2]);
            BlockState state = level.getBlockState(at);
            if (state.is(ModTags.CORRUPTED) || !CorruptionRegistry.canCorrupt(level, at, state)) {
                continue;
            }
            BlockState corrupted = CorruptionRegistry.corrupt(level, at, state, random);
            if (CorruptionRegistry.noChange(state, corrupted)) {
                continue;
            }

            BlockPos hit = at.immutable();
            CorruptionRegistry.placeCorrupted(level, hit, state, corrupted, Block.UPDATE_ALL);
            CorruptionSpreader.growSurfacePlants(level, hit, corrupted, growthChance);
            converted++;
        }

        if (converted > 0) {
            CorruptionCounter.record(level, converted);
            // 通常この呼び出しは「既に OVERGROWN なので即 return」で終わるが、閾値ちょうどの
            // 端のチャンク(縁のにじみで OVERGROWN になったばかり等)を取りこぼさないよう一応呼ぶ。
            OvergrownBiomeConverter.maybeConvert(level, chunkPos);
        }
        return converted;
    }

    /**
     * {@code (x, z)} 列の最上部から下へ {@code depth} ブロックぶんの範囲に、汚染ブロックが
     * 1 つでもあるか。未汚染の不透過フルブロックに先に当たった場合、そこから下は地表ではないので
     * {@code false} で打ち切る。
     */
    private static boolean columnHasCorruption(ServerLevel level, BlockPos.MutableBlockPos cursor,
                                               int x, int topY, int z, int minY, int depth) {
        int limitY = Math.max(minY, topY - depth + 1);
        for (int y = topY; y >= limitY; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) {
                continue;
            }
            if (state.is(ModTags.CORRUPTED)) {
                return true;
            }
            if (state.isCollisionShapeFullBlock(level, cursor)) {
                return false;
            }
        }
        return false;
    }

    /**
     * {@code (x, z)} 列の最上部から下へ {@code depth} ブロックぶん見て、最初に見つかった
     * 「汚染してよい未汚染ブロック」の位置を返す。
     *
     * <ul>
     *     <li>汚染済みの<b>フルブロック</b>に当たったら、その下に通常ブロックが露出することは
     *         無いので {@code null}(この列は片付いている)。</li>
     *     <li>汚染済みでも薄い装飾植物・茂みなどフルブロックでないものは、その下に通常の地面が
     *         隠れていることがあるのでさらに下を見る。</li>
     *     <li>岩盤・水面など {@link CorruptionRegistry#canCorrupt} が false のブロックに当たったら
     *         {@code null}(この列はここで打ち切り)。</li>
     * </ul>
     */
    @Nullable
    private static BlockPos findCorruptibleInColumn(ServerLevel level, BlockPos.MutableBlockPos cursor,
                                                    int x, int topY, int z, int minY, int depth) {
        int limitY = Math.max(minY, topY - depth + 1);
        for (int y = topY; y >= limitY; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) {
                continue;
            }
            if (state.is(ModTags.CORRUPTED)) {
                if (state.isCollisionShapeFullBlock(level, cursor)) {
                    return null;
                }
                continue;
            }
            if (!CorruptionRegistry.canCorrupt(level, cursor, state)) {
                return null;
            }
            return cursor.immutable();
        }
        return null;
    }

    /**
     * {@code (x, z)} 列の地表付近({@code topY + 2} 〜 {@code topY - 1})にある「積もった雪」「氷」を剥がす。
     * 雪の層は空気に、氷・薄氷は水源に戻す(氷は元々水面にできるため)。パックされた氷・青氷は
     * 構造物的なので触らない。1 ブロックでも消したら true。
     */
    private static boolean stripFrozenSurface(ServerLevel level, BlockPos.MutableBlockPos cursor,
                                              int x, int topY, int z) {
        boolean removedAny = false;
        for (int y = topY + 2; y >= topY - 1; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.getBlock() instanceof SnowLayerBlock) {
                level.setBlock(cursor.immutable(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                removedAny = true;
            } else if (state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE)) {
                level.setBlock(cursor.immutable(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                removedAny = true;
            }
        }
        return removedAny;
    }

    /**
     * チャンク中心の 1 点だけを見て、そのチャンクが繁茂系バイオーム(過繁茂 or 繁茂)か。
     * 繁茂バイオームも「仕様はすべて過繁茂バイオームと同じ」なので、地表浸食の対象に含める
     * ({@link ModBiomes#isOvergrownLike} 参照。フロンティア浸食は既存の汚染の縁しか触らないので、
     * 汚染がまだ届いていない繁茂チャンクを対象にしても実際には何も起きない)。
     */
    private static boolean isOvergrownChunk(ServerLevel level, int cx, int cz) {
        BlockPos sample = new BlockPos((cx << 4) + 8, level.getSeaLevel(), (cz << 4) + 8);
        return ModBiomes.isOvergrownLike(level.getBiome(sample));
    }

    private static Long2LongOpenHashMap newSweepMap() {
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

    // ------------------------------------------------------------------------------------------
    // パス2: バイオームの縁のにじみ(バイオームの外側 margin ブロックぶんへはみ出す)
    // ------------------------------------------------------------------------------------------

    private static void bleedEdges(ServerLevel level, OvergrownVegetationConfig.Common cfg) {
        int margin = cfg.overgrownBiomeCreepMargin.get();
        if (margin <= 0) {
            return;
        }
        int attempts = cfg.overgrownBiomeCreepAttempts.get();
        if (attempts <= 0) {
            return;
        }
        int radius = cfg.overgrownBiomeCreepRadius.get();
        double growthChance = cfg.growthChance.get();
        RandomSource random = level.random;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            for (int i = 0; i < attempts; i++) {
                tryBleedOnce(level, player, radius, margin, growthChance, random);
            }
        }
    }

    private static void tryBleedOnce(ServerLevel level, ServerPlayer player, int radius, int margin,
                                     double growthChance, RandomSource random) {
        int dx = random.nextInt(radius * 2 + 1) - radius;
        int dz = random.nextInt(radius * 2 + 1) - radius;
        if (dx * dx + dz * dz > radius * radius) {
            return;
        }
        int x = player.getBlockX() + dx;
        int z = player.getBlockZ() + dz;
        if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z))) {
            return;
        }

        BlockPos surface = findCorruptibleSurface(level, x, z);
        if (surface == null) {
            return;
        }
        // バイオームの内側はフロンティア浸食のパスが受け持つ。ここは「外側 margin ブロックぶん」だけ。
        if (!isOutsideButWithinMargin(level, surface, margin)) {
            return;
        }
        // 既存の汚染に隣接している地点だけをにじませる(バイオームの外に孤立した汚染点を作らない)。
        if (!hasCorruptedNeighbourColumn(level, surface)) {
            return;
        }

        BlockState state = level.getBlockState(surface);
        BlockState corrupted = CorruptionRegistry.corrupt(level, surface, state, random);
        if (CorruptionRegistry.noChange(state, corrupted)) {
            return;
        }

        CorruptionRegistry.placeCorrupted(level, surface, state, corrupted, Block.UPDATE_ALL);
        CorruptionCounter.record(level, 1);
        CorruptionSpreader.growSurfacePlants(level, surface, corrupted, growthChance);
        OvergrownBiomeConverter.maybeConvert(level, new ChunkPos(surface));
    }

    /**
     * 列の「地表」の Y。水中(最上部が水などの流体)の列は海底
     * ({@link Heightmap.Types#OCEAN_FLOOR})を返すので、海バイオームでも浸食・にじみが働く。
     * 葉に覆われた列などは従来どおり {@code MOTION_BLOCKING_NO_LEAVES}(葉の下の本体)を返す。
     */
    private static int surfaceTopY(ServerLevel level, int x, int z) {
        int motionBlocking = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int oceanFloor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        // 水中の列だけ oceanFloor(< motionBlocking)。陸(葉の天蓋を含む)は motionBlocking のまま。
        return Math.min(motionBlocking, oceanFloor);
    }

    /**
     * {@code (x, z)} 列の「葉を除いた地表」(原木や地面。上に草・花が乗っていてもその下の本体。
     * 水中なら海底)を返す。既に汚染済み、または汚染不可(岩盤など)なら {@code null}。
     */
    @Nullable
    private static BlockPos findCorruptibleSurface(ServerLevel level, int x, int z) {
        int y = surfaceTopY(level, x, z);
        if (y < level.getMinBuildHeight()) {
            return null;
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(ModTags.CORRUPTED)) {
            return null;
        }
        if (!CorruptionRegistry.canCorrupt(level, pos, state)) {
            return null;
        }
        return pos;
    }

    /**
     * {@code pos} の水平4方向の隣の列の地表付近({@code y + 1} 〜 {@code y - 2})に、汚染ブロックが
     * 1 つでもあるか。にじみを「既存の汚染の縁」に限定するために使う。
     */
    private static boolean hasCorruptedNeighbourColumn(ServerLevel level, BlockPos pos) {
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int x = pos.getX() + dir.getStepX();
            int z = pos.getZ() + dir.getStepZ();
            if (!level.hasChunkAt(new BlockPos(x, minY, z))) {
                continue;
            }
            int y = surfaceTopY(level, x, z);
            if (y < minY) {
                continue;
            }
            for (int dy = 1; dy >= -2; dy--) {
                cursor.set(x, y + dy, z);
                if (level.getBlockState(cursor).is(ModTags.CORRUPTED)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code pos} が繁茂系バイオーム(過繁茂 or 繁茂)の<b>外</b>で、かつその縁から {@code margin}
     * ブロック以内か。バイオームの内側は {@link #sweepInterior} が受け持つので、ここでは弾く。
     */
    private static boolean isOutsideButWithinMargin(ServerLevel level, BlockPos pos, int margin) {
        if (ModBiomes.isOvergrownLike(level.getBiome(pos))) {
            return false;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (ModBiomes.isOvergrownLike(level.getBiome(pos.relative(dir, margin)))) {
                return true;
            }
        }
        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
        for (int sx = -margin; sx <= margin; sx += 2 * margin) {
            for (int sz = -margin; sz <= margin; sz += 2 * margin) {
                scratch.set(pos.getX() + sx, pos.getY(), pos.getZ() + sz);
                if (ModBiomes.isOvergrownLike(level.getBiome(scratch))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // パス3: 深部浸食(地表から下へ 1 層ずつ、チャンク内の全固体ブロックを汚染しきる)
    // ------------------------------------------------------------------------------------------

    /**
     * プレイヤー周辺の<b>過繁茂</b>バイオーム({@link ModBiomes#isFullyOvergrown})のチャンクのうち、
     * 地表が汚染しきったものを、クールダウンの切れたものから {@code overgrownBiomeCreepDeepChunksPerTick}
     * 個まで取り上げ、それぞれ {@link #deepenChunk} で地表から下へ 1 層ずつ汚染を降ろす。
     *
     * <p>最終的にチャンク内の全固体ブロック(地表より上の建築物・浮遊ブロックから、地下深部の
     * 露出していない石・鉱石まで)が汚染される。地下の一括変換なので見た目は「汚染面が真下へ
     * 降りていく」だけで、地表のフロンティア浸食のような矩形問題は起きない(そもそも地下は見えない)。
     */
    private static void deepenInterior(ServerLevel level, OvergrownVegetationConfig.Common cfg) {
        if (!cfg.overgrownBiomeCreepDeepCorruption.get()) {
            return;
        }
        int chunkBudget = cfg.overgrownBiomeCreepDeepChunksPerTick.get();
        if (chunkBudget <= 0) {
            return;
        }
        int radiusChunks = Math.max(1, Mth.ceil(cfg.overgrownBiomeCreepRadius.get() / 16.0F));
        int blocksPerChunk = cfg.overgrownBiomeCreepDeepBlocksPerChunk.get();
        int layersPerVisit = Math.max(1, cfg.overgrownBiomeCreepDeepLayersPerVisit.get());
        double deepChance = cfg.overgrownBiomeCreepDeepChance.get();
        double growthChance = cfg.growthChance.get();
        long now = level.getGameTime();
        long rescanTicks = cfg.overgrownBiomeCreepDeepRescanTicks.get();
        int minY = level.getMinBuildHeight();

        Long2LongOpenHashMap nextDeepen =
                NEXT_DEEPEN.computeIfAbsent(level.dimension(), key -> newSweepMap());
        Long2IntOpenHashMap cursors =
                DEEPEN_CURSOR.computeIfAbsent(level.dimension(), key -> newCursorMap());

        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList eligible = new LongArrayList();
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
                    if (now < nextDeepen.get(key)) {
                        continue;
                    }
                    if (cursors.get(key) <= minY) {
                        continue; // このチャンクは深部まで汚染しきった
                    }
                    if (!level.hasChunk(cx, cz)) {
                        continue;
                    }
                    if (!isFullyOvergrownChunk(level, cx, cz)) {
                        continue; // 深部浸食は「過繁茂」段階のみ(「繁茂」段階では走らせない)
                    }
                    eligible.add(key);
                }
            }
        }
        if (eligible.isEmpty()) {
            return;
        }

        shuffle(eligible, level.random);
        int limit = Math.min(chunkBudget, eligible.size());
        for (int i = 0; i < limit; i++) {
            long key = eligible.getLong(i);
            deepenChunk(level, new ChunkPos(key), cursors, key,
                    blocksPerChunk, layersPerVisit, deepChance, growthChance);
            nextDeepen.put(key, now + rescanTicks);
        }

        if (nextDeepen.size() > SWEEP_MAP_MAX_ENTRIES) {
            nextDeepen.clear();
            cursors.clear();
        }
    }

    /**
     * 1 チャンクの深部浸食を 1 回ぶん進める。{@code cursors} に記録した「次に浸食を始める Y 層」から
     * 下へ向かって、各層のすべての未汚染・汚染可能な固体ブロックを汚染ブロックへ置き換える。
     *
     * <p>まずチャンクの地表(全 256 列)が汚染しきっているか確かめ、1 列でも未汚染の地表が残って
     * いれば深部浸食はまだ始めない(地表のフロンティア浸食 {@link #sweepInterior} に任せる)。
     * こうすることで汚染は必ず「地表 → 直下 → さらに下」の順に降り、地表より先に地下が汚染される
     * ことがない。
     *
     * <p>1 回の呼び出しで走査する層数は {@code layersPerVisit}、汚染するブロック数は
     * {@code blocksBudget} が上限。ある層に汚染すべきブロックが残ったまま上限に達した場合、
     * カーソルはその層に留まり、次回また同じ層から続ける({@code deepChance} が 1 未満で
     * 取りこぼした場合も同様に、その層がクリーンになるまで下の層へ進まない)。
     */
    private static void deepenChunk(ServerLevel level, ChunkPos chunkPos, Long2IntOpenHashMap cursors,
                                    long key, int blocksBudget, int layersPerVisit,
                                    double deepChance, double growthChance) {
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        int minY = level.getMinBuildHeight();
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();

        // 1. 地表(葉を除いた最上部。水中なら海底)が全 256 列で汚染しきっているか。
        //    そもそも汚染できない地表(岩盤など)の列は「済み」扱いにして待たない。
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                int topY = surfaceTopY(level, x, z);
                if (topY < minY) {
                    continue;
                }
                probe.set(x, topY, z);
                BlockState state = level.getBlockState(probe);
                if (state.is(ModTags.CORRUPTED) || !CorruptionRegistry.canCorrupt(level, probe, state)) {
                    continue;
                }
                // 未汚染の地表が残っている。深部浸食はまだ始めない。
                return;
            }
        }

        // 2. カーソル(次に浸食を始める Y)。未開始ならチャンク最上部の固体ブロックから。
        int startY = cursors.get(key);
        if (startY == Integer.MAX_VALUE || startY > level.getMaxBuildHeight()) {
            startY = computeTopSolidY(chunk, minY);
        }
        if (startY < minY) {
            cursors.put(key, minY - 1);
            return;
        }

        RandomSource random = level.random;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        int converted = 0;
        int layersScanned = 0;
        int y = startY;
        for (; y >= minY; y--) {
            if (layersScanned >= layersPerVisit || converted >= blocksBudget) {
                break;
            }
            layersScanned++;

            int corruptibleLeft = 0;
            boolean budgetHit = false;
            layer:
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    cursor.set(baseX + lx, y, baseZ + lz);
                    BlockState state = chunk.getBlockState(cursor);
                    if (state.isAir() || state.is(ModTags.CORRUPTED)) {
                        continue;
                    }
                    if (!CorruptionRegistry.canCorrupt(level, cursor, state)) {
                        continue;
                    }
                    corruptibleLeft++;
                    if (converted >= blocksBudget) {
                        budgetHit = true;
                        break layer;
                    }
                    if (deepChance < 1.0D && random.nextDouble() >= deepChance) {
                        continue; // 今回は見送り。この層はクリーンにならないので次回また来る。
                    }
                    BlockState corrupted = CorruptionRegistry.corrupt(level, cursor, state, random);
                    if (CorruptionRegistry.noChange(state, corrupted)) {
                        corruptibleLeft--; // 変換先が同じ = 実質何もしないブロック。層を止めない。
                        continue;
                    }
                    BlockPos hit = cursor.immutable();
                    CorruptionRegistry.placeCorrupted(level, hit, state, corrupted, Block.UPDATE_ALL);
                    // 完全に埋まったブロックには装飾植物を生やさない(抽選が無駄なので事前に弾く)。
                    // 洞窟や空洞に面したブロックだけ、露出面へ苔・蔦などを生やす。
                    if (hasAirNeighbour(level, hit, neighbour)) {
                        CorruptionSpreader.growSurfacePlants(level, hit, corrupted, growthChance);
                    }
                    converted++;
                    corruptibleLeft--;
                }
            }

            if (budgetHit || corruptibleLeft > 0) {
                break; // この層にまだ汚染すべきものが残っている。次回もこの層から。
            }
            // 層が完全にクリーン → for 文の y-- でさらに下の層へ。
        }

        if (y < minY) {
            cursors.put(key, minY - 1); // 深部まで汚染しきった
        } else {
            cursors.put(key, y);
        }
    }

    /** チャンク内で最も高い「空気でない」ブロックの Y(建築物・浮遊ブロックを含む)。無ければ {@code minY - 1}。 */
    private static int computeTopSolidY(LevelChunk chunk, int minY) {
        int max = minY - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                max = Math.max(max, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
            }
        }
        return max;
    }

    /** チャンク中心の 1 点を見て、そのチャンクが「過繁茂」段階(陸・海・川)のバイオームか。 */
    private static boolean isFullyOvergrownChunk(ServerLevel level, int cx, int cz) {
        BlockPos sample = new BlockPos((cx << 4) + 8, level.getSeaLevel(), (cz << 4) + 8);
        return ModBiomes.isFullyOvergrown(level.getBiome(sample));
    }

    private static boolean hasAirNeighbour(ServerLevel level, BlockPos pos, BlockPos.MutableBlockPos scratch) {
        for (Direction dir : Direction.values()) {
            scratch.setWithOffset(pos, dir);
            if (level.getBlockState(scratch).isAir()) {
                return true;
            }
        }
        return false;
    }

    private static Long2IntOpenHashMap newCursorMap() {
        Long2IntOpenHashMap map = new Long2IntOpenHashMap();
        map.defaultReturnValue(Integer.MAX_VALUE);
        return map;
    }

    private OvergrownBiomeCreep() {
    }
}
