package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import com.yayuy.overgrownvegetation.util.TickPhases;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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

    /**
     * パス3(深部浸食)で「選んだが、まだ回していない」チャンク(ディメンションごと)
     * (2026-09-24 軽量化、報告 1505 / 600)。
     *
     * <p>以前は 1 回のパスで選んだ最大 {@code overgrownBiomeCreepDeepChunksPerTick}(既定 8)チャンクを、
     * その tick のうちに全部回していた(1 チャンク最大 128 マスの置き換え × 8 が 1 tick に集まる)。
     * 選び方・回す順番・クールダウンは元のまま、回すのだけを選んだ tick から {@link #DEEPEN_SPREAD_TICKS} tick に
     * 分ける(既定の 8 チャンクなら 1 tick に 2 チャンク)。パスの間隔(20 tick 以上)より十分短いので、
     * 次のパスまでに必ず回し終わる。
     */
    private static final Map<ResourceKey<Level>, DeepenBatch> DEEPEN_PENDING = new HashMap<>();

    /** 1 回のパスで選んだ深部浸食を何 tick に分けて回すか。仮決め 2026-09-24。 */
    private static final int DEEPEN_SPREAD_TICKS = 4;

    /** 選んだ順のチャンクと、1 tick に回す数。回すときの設定値は選んだときのもの(元は選んだ tick に回していたので)。 */
    private static final class DeepenBatch {
        final LongArrayFIFOQueue keys = new LongArrayFIFOQueue();
        int perTick = 1;
        int blocksPerChunk;
        int layersPerVisit;
        double deepChance;
        double growthChance;
    }

    /**
     * ワールドを閉じたらクールダウン表と深部浸食のカーソルを空にする(キーはディメンションだけなので、
     * 次に別のワールドを開くと前のワールドの時刻・進捗が残り、同じ座標のチャンクが待たされる。
     * 起動し直したときと同じ状態に戻す)。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        NEXT_SWEEP.clear();
        NEXT_DEEPEN.clear();
        DEEPEN_CURSOR.clear();
        DEEPEN_PENDING.clear();
    }

    /** 次元が閉じたら、その次元で選んだまま回していない深部浸食を捨てる(2026-09-24、報告 1505 の待ち行列)。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            DEEPEN_PENDING.remove(level.dimension());
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 止める(順番待ちは残り、オンで続きから)
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (NetherLedger.active(level)) {
            // 2026-11-11 ネザーの骨組み: ネザーの地面は根の台帳の段階だけが変える(面と奥 1〜3 段だけ。地中の一括置き換え・縁のにじみはしない)
            return;
        }
        // サーバーが重いときは予算の番人が次の tick(パスは次の周回)へ回す(2026-10-06。結果は同じで遅れるだけ)。
        // 番人に聞くのは仕事がある tick だけ(何も無い tick を「止めた」と数えないため)。
        if (!hasWork(level) || !TickBudget.mayStart(PerfJob.BIOME_CREEP)) {
            return;
        }
        long start = TickBudget.begin();
        try {
            runPasses(level);
            // 選んである深部浸食を、この tick のぶんだけ回す(選んだ tick から数 tick に分ける。報告 1505)。
            // 選んだ時点で回すことは決まっているので、設定を切り替えても回し切る(元は選んだ tick のうちに全部回していた)。
            drainDeepen(level);
        } finally {
            TickBudget.end(PerfJob.BIOME_CREEP, start, 1);
        }
    }

    /** この tick に回すパスがあるか、選んである深部浸食が残っているか({@link #runPasses}・{@link #drainDeepen} と同じ条件)。 */
    private static boolean hasWork(ServerLevel level) {
        DeepenBatch batch = DEEPEN_PENDING.get(level.dimension());
        if (batch != null && !batch.keys.isEmpty()) {
            return true;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.overgrownBiomeCreep.get() || !cfg.convertToOvergrownBiome.get()) {
            return false;
        }
        int interval = Math.max(1, cfg.overgrownBiomeCreepIntervalTicks.get());
        long offset = TickPhases.offsetInCycle(level.getGameTime(), TickPhases.BIOME_CREEP, interval);
        long third = interval / 3;
        return offset == 0L || offset == third || offset == third * 2L;
    }

    private static void runPasses(ServerLevel level) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.overgrownBiomeCreep.get() || !cfg.convertToOvergrownBiome.get()) {
            return;
        }
        int interval = Math.max(1, cfg.overgrownBiomeCreepIntervalTicks.get());
        // 2026-09-24 軽量化(報告 589 / 1516 / 600): 3 つのパスを同じ tick に続けて回していたのを、周期の 1/3 ずつ
        // ずらした別々の tick に分ける(どのパスも間隔は同じで、走る tick が周期の中でずれるだけ)。周期全体の位相は
        // TickPhases で、建築浸食・湿地化などの周期処理とも重ならないようにしてある。
        // 間隔が 3 tick 未満なら 3 つとも同じ tick になり、今までどおりこの順に回る。
        long offset = TickPhases.offsetInCycle(level.getGameTime(), TickPhases.BIOME_CREEP, interval);
        long third = interval / 3;
        if (offset == 0L) {
            sweepInterior(level, cfg);
        }
        if (offset == third) {
            bleedEdges(level, cfg);
        }
        if (offset == third * 2L) {
            deepenInterior(level, cfg);
        }
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
                    if (!neighbourhoodReady(level, cx, cz)) {
                        continue; // 周りが読み込みの途中。読み込まれていないときと同じく次の周回で。
                    }
                    // 2026-11-04(縁を列ごとに): 繁茂系に接した隣のチャンクも対象(列ごとの門 EdgeGate が縁の形を決める)
                    if (!isOvergrownChunk(level, cx, cz) && !EdgeGate.nearOvergrown(level, cx, cz)) {
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
        // 予算の番人に止められたら残りは次の周回(クールダウンを付けないので、また候補に入る)。
        for (int i = 0; i < limit && (i == 0 || TickBudget.timeLeft()); i++) {
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
        EdgeGate gate = EdgeGate.of(level, chunkPos); // 縁を列ごとに(2026-11-04 A)
        boolean ownOvergrown = isOvergrownChunk(level, chunkPos.x, chunkPos.z);
        int holeSides = com.yayuy.overgrownvegetation.data.DesignData.sporeField().holeSides();
        int covered = 0; // 穴のうち覆った数(2026-11-04 B)

        // 1. チャンク + 外周1リング(18x18)の各列に汚染ブロックがあるか事前計算する。
        //    index = ローカル座標 + 1(ローカル -1..16 → index 0..17)。
        boolean[][] corruptedColumn = new boolean[18][18];
        for (int ix = 0; ix < 18; ix++) {
            for (int iz = 0; iz < 18; iz++) {
                int x = baseX + ix - 1;
                int z = baseZ + iz - 1;
                if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.ready(level, x >> 4, z >> 4)) {
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

                int ix = lx + 1;
                int iz = lz + 1;
                // 4 方のうち汚染された列の数(穴埋めの判定。2026-11-04 B)
                int sides = (corruptedColumn[ix - 1][iz] ? 1 : 0) + (corruptedColumn[ix + 1][iz] ? 1 : 0)
                        + (corruptedColumn[ix][iz - 1] ? 1 : 0) + (corruptedColumn[ix][iz + 1] ? 1 : 0);
                boolean hole = sides >= holeSides;

                BlockPos target = findTarget(level, cursor, x, topY, z, minY, surfaceDepth);
                if (target == null) {
                    // 穴なのに変換の的が無い(汚染できないブロック): 覆えるものは覆う(2026-11-04 B)
                    if (hole && !corruptedColumn[ix][iz] && coverHole(level, cursor, x, topY, z, minY)) {
                        covered++;
                    }
                    continue; // 既に片付いている / 岩盤・水で打ち切り
                }

                int sources = sides + (corruptedColumn[ix][iz] ? 1 : 0); // 自列(汚染植物の下に埋もれた地面)も数える
                if (sources == 0) {
                    continue; // フロンティアではない = 孤立した未汚染列。触らない(矩形の湧きを防ぐ)。
                }
                // 縁の門(2026-11-04 A): 繁茂系でないチャンク(汚染に接した隣)だけ、4 チャンクで混ぜた場の値 + 雑音で列ごとに決める。
                // 繁茂系のチャンクの中は今までどおり全部の列(内側に塗り残しを作らない)。穴は門を通さず必ず埋める
                if (!hole && !ownOvergrown && !gate.open(x, z)) {
                    continue;
                }

                frontier.add(new int[] {target.getX(), target.getY(), target.getZ(), hole ? 99 : sources});
            }
        }
        if (frontier.isEmpty()) {
            return covered;
        }

        // 3. シャッフルして、汚染源が多い列ほど高い確率で変換。上限は maxConversions。
        // 穴(印 99)は先頭へ寄せ、確率を掛けずに埋める(2026-11-04 B。上限で後回しにされない)
        shuffle(frontier, random);
        frontier.sort((a, b) -> Boolean.compare(b[3] == 99, a[3] == 99));
        int converted = covered;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int[] cell : frontier) {
            if (converted >= maxConversions) {
                break;
            }
            double chance = cell[3] == 99 ? 1.0D : frontierBaseChance + (cell[3] - 1) * frontierChancePerNeighbor;
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
                CorruptionSpreader.growPlantsIfNewlyCovered(level, at, state); // 覆っただけのマスにも植物(2026-10-10)
                continue;
            }

            BlockPos hit = at.immutable();
            CorruptionRegistry.placeCorrupted(level, hit, state, corrupted, Block.UPDATE_ALL);
            CorruptionSpreader.growSurfacePlants(level, hit, corrupted, growthChance);
            UnderwaterPlants.grow(level, hit, corrupted); // 水に接した面には水中の植物(触手の汚染と同じ。2026-11-01 夜)
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
     * 穴埋めの覆い(2026-11-04 B、仮決め): まわりが汚染された列の地面が汚染できないブロック({@link CorruptionRegistry#canCorrupt} が false)のとき、
     * 覆いの記録で覆える種類(自然の葉・水に浸かった欠けたブロック)なら覆う。免疫タグ・ブロックエンティティ・壊せないもの(岩盤)・液体はそのまま残す。
     * 地面 = 頂から木の一部・空気・動きを止めないものをくぐった最初のブロック。覆ったら true。
     */
    private static boolean coverHole(ServerLevel level, BlockPos.MutableBlockPos cursor, int x, int topY, int z, int minY) {
        if (CorruptionSwitch.isOff()) {
            return false;
        }
        for (int y = topY; y >= Math.max(minY, topY - 16); y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir() || (!state.blocksMotion() && state.getFluidState().isEmpty())) {
                continue;
            }
            if (OvergrownLayerData.isCorruptedCell(level, cursor, state)) {
                return false;
            }
            boolean naturalLeaves = state.is(net.minecraft.tags.BlockTags.LEAVES);
            boolean wetPartial = !state.getFluidState().isEmpty() && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock);
            if (!naturalLeaves && !wetPartial) {
                return false;
            }
            if (state.is(ModTags.CORRUPTION_IMMUNE) || state.hasBlockEntity() || state.getBlock().defaultDestroyTime() < 0.0F) {
                return false;
            }
            return OvergrownLayerData.cover(level, cursor.immutable());
        }
        return false;
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
            if (OvergrownLayerData.isCorruptedCell(level, cursor, state)) {
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
    static BlockPos findCorruptibleInColumn(ServerLevel level, BlockPos.MutableBlockPos cursor,
                                                    int x, int topY, int z, int minY, int depth) {
        int limitY = Math.max(minY, topY - depth + 1);
        for (int y = topY; y >= limitY; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) {
                continue;
            }
            // 覆ったマス(砂・砂利・砂岩・安山岩などは 10-30/31 から置き換えずに覆う)も汚染済み。前は #corrupted だけを見ていたので、
            // 覆った立方体を毎回また的にしていた(何も起きないが無駄。2026-11-01 夜)。
            if (OvergrownLayerData.isCorruptedCell(level, cursor, state)) {
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
     * 列の浸食の的: {@link #findCorruptibleInColumn}(頂から {@code depth} 段)で無ければ、頂にかぶさった梢・幹・橋の下の床
     * ({@link #findFloorUnderCover})。フロンティア浸食・縁のにじみ・台帳の塗りが同じものを使う。
     */
    @Nullable
    static BlockPos findTarget(ServerLevel level, BlockPos.MutableBlockPos cursor, int x, int topY, int z, int minY, int depth) {
        BlockPos target = findCorruptibleInColumn(level, cursor, x, topY, z, minY, depth);
        return target != null ? target : findFloorUnderCover(level, cursor, x, topY, z, minY);
    }

    /** {@link #findFloorUnderCover} が水の中を下る最大の段数。 */
    static final int WATER_FLOOR_SCAN = 48;
    /**
     * {@link #findFloorUnderCover} が床・水面より上(梢・幹・空気)をくぐる最大の段数。巨大樹は高さ 120(overgrown_tree_giant)で、
     * 樹冠の下の林床は頂から 100 段以上下になる(初めは 48 で、ゲーム内で巨大樹の下の床が残った。2026-11-01 夜)。空気だけの区画は 16 段ずつ飛ばす。
     */
    static final int CANOPY_SCAN = 160;

    /**
     * 列の頂が<b>床の上にかぶさったもの</b>(林冠の葉・幹・枝、根のアーチ・橋、胞子昆布の柱)のとき、その下の床(水底でも陸でも。汚染済みなら null)。
     * 2026-11-01 夜(ユーザー「川底で砂・土・粘土が素のまま残る」、続けて「草が生えている場所の下が汚染されない」)。
     *
     * <p>列の頂({@link #surfaceTopY})は動きを止めるブロックの一番上で、過繁茂の葉({@code OvergrownLeavesBlock})はバニラの葉
     * ({@code LeavesBlock})でないので {@code MOTION_BLOCKING_NO_LEAVES} から外れない。梢が張り出した列・根のアーチが渡った列では頂が汚染済みの
     * 葉や幹になり、{@link #findCorruptibleInColumn} は「片付いた列」と答えていた。胞子昆布の列は柱(水を含む・立方体でない)を
     * {@code surfaceDepth}(4)段くだったところで尽きる。どれも<b>その下の川底・海底・林床は浸食の的にならなかった</b>(深部浸食が届くまで)。
     * 林床の草・シダは下の地面と一緒に汚染されないまま残るので、ユーザーには「草の下が抜ける」と見えていた。
     *
     * <p>床より上でくぐるのは空気・動きを止めないもの(高さ地図と同じ判定。草・シダ・水面の膜も)・木の一部
     * ({@link TreeCanopyGroundFinder#isTreePart}。汚染の原木と葉も)・汚染済みの立方体でないものだけ。水(水を含むブロック =
     * シャジクモ・昆布・海草も)を下った最初の水でないマスが水底の床、水に入らず当たった動きを止めるマスが陸の床。
     * 陸の床は真上が空いているとき(空気・草など)だけ的にする —— 幹の根元・葉が直に載った地面は表に出ていないので触らない(深部浸食が拾う)。
     */
    @Nullable
    static BlockPos findFloorUnderCover(ServerLevel level, BlockPos.MutableBlockPos cursor, int x, int topY, int z, int minY) {
        int limitY = Math.max(minY, topY - CANOPY_SCAN - WATER_FLOOR_SCAN);
        int waterTop = Integer.MIN_VALUE;
        boolean aboveOpen = false; // 1 つ上のマスが空いていたか(空気・動きを止めないもの)
        LevelChunk chunk = level.getChunkAt(cursor.set(x, topY, z));
        for (int y = topY; y >= limitY; y--) {
            if (waterTop == Integer.MIN_VALUE) {
                int sectionIndex = chunk.getSectionIndex(y);
                if (sectionIndex >= 0 && sectionIndex < chunk.getSectionsCount() && chunk.getSection(sectionIndex).hasOnlyAir()) {
                    aboveOpen = true;
                    y = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(y)); // 区画の底へ(for の y-- で 1 つ下の区画)
                    continue;
                }
            }
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                if (waterTop == Integer.MIN_VALUE) {
                    waterTop = y;
                } else if (waterTop - y > WATER_FLOOR_SCAN) {
                    return null;
                }
                continue;
            }
            if (waterTop == Integer.MIN_VALUE) {
                if (topY - y > CANOPY_SCAN) {
                    return null;
                }
                if (state.isAir() || !state.blocksMotion()) {
                    aboveOpen = true;
                    continue;
                }
                if (com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(state)) {
                    aboveOpen = false;
                    continue;
                }
                boolean corrupted = OvergrownLayerData.isCorruptedCell(level, cursor, state);
                if (corrupted && !state.isCollisionShapeFullBlock(level, cursor)) {
                    aboveOpen = true;
                    continue; // 汚染済みの茂み・段など(findCorruptibleInColumn と同じくその下を見る)
                }
                // 陸の床
                if (y == topY || !aboveOpen || corrupted || !CorruptionRegistry.canCorrupt(level, cursor, state)) {
                    return null; // 頂そのもの(findCorruptibleInColumn の受け持ち)・表に出ていない・片付いた・汚染できない
                }
                return cursor.immutable();
            }
            // 水の下の最初の水でないマス = 水底の床
            if (OvergrownLayerData.isCorruptedCell(level, cursor, state) || !CorruptionRegistry.canCorrupt(level, cursor, state)) {
                return null;
            }
            return cursor.immutable();
        }
        return null;
    }

    /**
     * {@code (x, z)} 列の地表付近({@code topY + 2} 〜 {@code topY - 1})にある「積もった雪」「氷」を剥がす。
     * 過繁茂バイオームは常夏なので、元バイオームの凍結面は残さない。1 ブロックでも消したら true。
     *
     * <ul>
     *     <li>積もった雪の層({@link SnowLayerBlock})は空気。雪の塊・粉雪は対応表で {@code overgrown_dirt} に
     *     なるのでここでは触らない。</li>
     *     <li>氷({@code #minecraft:ice} = 氷・薄氷・<b>パックされた氷・青氷</b>)は
     *     {@link MeltedIce#stateAt}(水に接していれば水源、そうでなければ空気)。</li>
     * </ul>
     *
     * <p>以前はパックされた氷・青氷を「構造物的なので触らない」として残していたが、熱帯なのに氷山・
     * 氷柱・氷河が残るとの指摘で 2026-09-29 に溶かす対象へ変えた。剥がすのは 1 回の走査で上から
     * 数段までなので、氷山のような厚い塊は走査のたびに上から溶けていく(剥がした列はその回の
     * 浸食候補から外れ、次の走査で改めて拾われる)。
     */
    static boolean stripFrozenSurface(ServerLevel level, BlockPos.MutableBlockPos cursor,
                                              int x, int topY, int z) {
        boolean removedAny = false;
        for (int y = topY + 2; y >= topY - 1; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.getBlock() instanceof SnowLayerBlock) {
                level.setBlock(cursor.immutable(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                removedAny = true;
            } else if (MeltedIce.isIce(state)) {
                BlockPos at = cursor.immutable();
                level.setBlock(at, MeltedIce.stateAt(level, at), Block.UPDATE_ALL);
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

    /**
     * チャンクとその周り 3x3 に「読み込みの途中」(hasChunk は true だがまだ FULL になっていない)のものが無いか。
     * フロンティア浸食・深部浸食は周りのチャンクも読むので、途中のものをそのまま読むと生成・読み込みが
     * 終わるまでサーバースレッドが止まる。1 つでもあればチャンクごと今回は見送る(クールダウンは書かない
     * ので、次の周回で同じ処理になる。列だけ飛ばすと結果が変わるのでやらない)。
     * hasChunk が false の隣はこれまでどおりの扱い(フロンティア浸食ならその列を飛ばす)なので、ここでは待たない。
     */
    private static boolean neighbourhoodReady(ServerLevel level, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.hasChunk(cx + dx, cz + dz)
                        && level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) {
                    return false;
                }
            }
        }
        return true;
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
            CorruptionSpreader.growPlantsIfNewlyCovered(level, surface, state); // 覆っただけのマスにも植物(2026-10-10)
            return;
        }

        CorruptionRegistry.placeCorrupted(level, surface, state, corrupted, Block.UPDATE_ALL);
        CorruptionCounter.record(level, 1);
        CorruptionSpreader.growSurfacePlants(level, surface, corrupted, growthChance);
        UnderwaterPlants.grow(level, surface, corrupted); // 水に接した面には水中の植物(2026-11-01 夜)
        OvergrownBiomeConverter.maybeConvert(level, new ChunkPos(surface));
    }

    /**
     * 列の「地表」の Y。水中(最上部が水などの流体)の列は海底
     * ({@link Heightmap.Types#OCEAN_FLOOR})を返すので、海バイオームでも浸食・にじみが働く。
     * 葉に覆われた列などは従来どおり {@code MOTION_BLOCKING_NO_LEAVES}(葉の下の本体)を返す。
     */
    static int surfaceTopY(ServerLevel level, int x, int z) {
        if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.ready(level, x >> 4, z >> 4)) {
            return level.getMinBuildHeight() - 1; // 11-18 軽量化 ④: 読み込まれていない列は調べない(同期読み込みで 400 ms 止まっていた)。呼ぶ側は「地表なし」で飛ばす
        }
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
        if (state.isAir()) {
            return null;
        }
        if (OvergrownLayerData.isCorruptedCell(level, pos, state)) { // 覆ったマスも(2026-11-01 夜)
            // 頂が梢・幹なら、その下の林床・水底(2026-11-01 夜、草の下の汚染の抜け)
            return findFloorUnderCover(level, new BlockPos.MutableBlockPos(), x, y, z, level.getMinBuildHeight());
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
                if (OvergrownLayerData.isCorruptedCell(level, cursor, level.getBlockState(cursor))) {
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
                    if (!neighbourhoodReady(level, cx, cz)) {
                        continue; // 周りが読み込みの途中。読み込まれていないときと同じく次の周回で。
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
        // 選んだチャンクはこの場では回さず、選んだ順に積んで drainDeepen が数 tick に分けて回す
        // (2026-09-24 軽量化、報告 1505 / 600)。クールダウンは元と同じく選んだ tick の時刻で書く。
        // クールダウン表とカーソルの肥大化の掃除も、元どおり「全部回し終えたあと」に drainDeepen で行う。
        DeepenBatch batch = DEEPEN_PENDING.computeIfAbsent(level.dimension(), key -> new DeepenBatch());
        for (int i = 0; i < limit; i++) {
            long key = eligible.getLong(i);
            batch.keys.enqueue(key);
            nextDeepen.put(key, now + rescanTicks);
        }
        batch.perTick = Math.max(1, (batch.keys.size() + DEEPEN_SPREAD_TICKS - 1) / DEEPEN_SPREAD_TICKS);
        batch.blocksPerChunk = blocksPerChunk;
        batch.layersPerVisit = layersPerVisit;
        batch.deepChance = deepChance;
        batch.growthChance = growthChance;
    }

    /**
     * {@link #deepenInterior} が選んで積んだチャンクを、この tick のぶん({@link DeepenBatch#perTick} 個)だけ
     * 選んだ順に {@link #deepenChunk} で回す(2026-09-24 軽量化、報告 1505 / 600)。選んだ tick にも呼ぶので、
     * 最初のぶんは今までどおり選んだ tick のうちに回る。
     *
     * <p>選んでから数 tick のうちに読み込みが外れた・読み込みの途中になったチャンクは回さない
     * (そのまま読むと同期で読み込むため。選んだときの条件と同じ)。クールダウンは選んだときに書いてあるので、
     * 切れたら次のパスで選び直される。
     */
    private static void drainDeepen(ServerLevel level) {
        DeepenBatch batch = DEEPEN_PENDING.get(level.dimension());
        if (batch == null || batch.keys.isEmpty()) {
            return;
        }
        Long2IntOpenHashMap cursors =
                DEEPEN_CURSOR.computeIfAbsent(level.dimension(), key -> newCursorMap());
        for (int n = 0; n < batch.perTick && !batch.keys.isEmpty() && (n == 0 || TickBudget.timeLeft()); n++) {
            long key = batch.keys.dequeueLong();
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (!level.hasChunk(cx, cz) || !neighbourhoodReady(level, cx, cz)) {
                continue;
            }
            deepenChunk(level, new ChunkPos(key), cursors, key,
                    batch.blocksPerChunk, batch.layersPerVisit, batch.deepChance, batch.growthChance);
        }
        if (batch.keys.isEmpty()) {
            Long2LongOpenHashMap nextDeepen = NEXT_DEEPEN.get(level.dimension());
            if (nextDeepen != null && nextDeepen.size() > SWEEP_MAP_MAX_ENTRIES) {
                nextDeepen.clear();
                cursors.clear();
            }
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
        // 2026-09-24 軽量化(報告 1472): 稼働中の胞子除去装置がこのチャンクに届かなければ、1 マスごとの
        // canCorrupt で装置の判定を省く(届かないチャンクでは装置の判定は乱数を引かずに false を返すだけなので、
        // 汚染されるマスも乱数の引き方も同じ)。装置の稼働が切り替わるのは装置自身の tick で、装置のブロックは
        // 汚染で置き換わらないので、このチャンクの処理の間は答えを使い回してよい。
        boolean devicesNear = SporeDeviceData.mayInhibitBlocksIn(level, chunkPos, SporeDeviceData.Inhibit.CORRUPTION);

        // ネザー(天井のある次元)も深部まで汚染する(2026-10-02 ユーザー指定。溶岩の海も玄武岩になる)。
        // 高さマップは天井の岩盤を返すので、下の 1. は全列「汚染できない地表 = 済み」になり、チャンクが過繁茂になった時点で
        // 天井の岩盤の下から下へ向かって全部を置き換え始める(床の表面が汚染しきるのは待たない)。
        // 1. 地表(葉を除いた最上部。水中なら海底)が全 256 列で汚染しきっているか。
        //    そもそも汚染できない地表(岩盤など)の列は「済み」扱いにして待たない。
        //    梢の下の林床まで読むのは始める前だけ(始まった後は深部浸食が上から拾う。梢の列を毎回 床まで読まないため)。
        boolean started = cursors.get(key) != Integer.MAX_VALUE;
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
                // 覆ったマスも汚染済み(2026-11-01 夜)。前は #corrupted だけを見ていたので、砂・砂利・砂岩などを覆った地表(10-30/31 から)が
                // 1 列でもあると「未汚染の地表が残っている」になり、川底・海底のチャンクで深部浸食がいつまでも始まらなかった
                // (岸の水中の壁・段の横・張り出しや林冠の下の水底を最後に拾うのはこの深部浸食)。
                if (surfaceDone(level, chunk, probe, state, devicesNear, !started)) {
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
                    if (state.isAir() || state.is(ModTags.CORRUPTED) || OvergrownLayerData.isCovered(chunk, cursor, state)) {
                        continue;
                    }
                    if (!CorruptionRegistry.canCorrupt(level, cursor, state, devicesNear)) {
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
                        CorruptionSpreader.growPlantsIfNewlyCovered(level, cursor, state); // 覆っただけのマスにも植物(2026-10-10)
                        corruptibleLeft--; // 変換先が同じ = 実質何もしないブロック。層を止めない。
                        continue;
                    }
                    BlockPos hit = cursor.immutable();
                    // 2026-09-24 軽量化(報告 1505 / 600): 6 面とも不透明な立方体に囲まれ、置き換えで信号も変わらない
                    // マスは、隣への近隣通知を省く(UPDATE_CLIENTS。placeCorrupted が KNOWN_SHAPE を足すので形状更新は
                    // もともと無い)。そういう隣は通知を受けても何も起きないので、置かれるものも隣の状態も同じ
                    // ({@link #neighbourUpdateIsNoop})。地下の石の一括置換は大半がこれで、1 マスごとの 6 回の通知が消える。
                    boolean sealed = neighbourUpdateIsNoop(level, hit, state, corrupted, neighbour);
                    CorruptionRegistry.placeCorrupted(level, hit, state, corrupted,
                            sealed ? Block.UPDATE_CLIENTS : Block.UPDATE_ALL);
                    // 完全に埋まったブロックには装飾植物を生やさない(抽選が無駄なので事前に弾く)。
                    // 洞窟や空洞に面したブロックだけ、露出面へ苔・蔦などを生やす。
                    // sealed なら 6 面とも空気ではないので、hasAirNeighbour は必ず false(読まずに済ませる)。
                    if (!sealed && hasAirNeighbour(level, hit, neighbour)) {
                        CorruptionSpreader.growSurfacePlants(level, hit, corrupted, growthChance);
                    }
                    if (!sealed) {
                        UnderwaterPlants.grow(level, hit, corrupted); // 水に接した面には水中の植物(2026-11-01 夜)
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

    /** 深部浸食の入口: 地表のマスが「済み」か(汚染済み・覆った・汚染できない)。 */
    private static boolean surfaceDone(ServerLevel level, LevelChunk chunk, BlockPos pos, BlockState state, boolean devicesNear,
                                      boolean lookUnderCover) {
        if (OvergrownLayerData.isCorruptedCell(chunk, pos, state)) {
            // 頂が汚染済みの梢・幹でも、その下の林床・水底が残っていれば「まだ」(2026-11-01 夜、草の下の汚染の抜け)
            return !lookUnderCover || findFloorUnderCover(level, new BlockPos.MutableBlockPos(), pos.getX(), pos.getY(), pos.getZ(),
                    level.getMinBuildHeight()) == null;
        }
        return !CorruptionRegistry.canCorrupt(level, pos, state, devicesNear);
    }

    /** GameTest 用: 深部浸食の入口で、その列の地表が「済み」と数えられるか({@link #deepenChunk} の 1. と同じ判定)。 */
    public static boolean surfaceDoneForTest(ServerLevel level, int x, int z) {
        BlockPos pos = new BlockPos(x, surfaceTopY(level, x, z), z);
        return surfaceDone(level, level.getChunkAt(pos), pos, level.getBlockState(pos), true, true);
    }

    /**
     * GameTest 用: 1 列ぶんのフロンティア浸食({@link #growChunkFrontier} の的の選び方・置き換え・覆い・植物は本物と同じ。
     * 隣の汚染源の数と確率の抽選、上限、凍った面の剥がしだけ省く)。置き換えたか新しく覆ったら true。
     */
    /** テスト用: そのチャンクのフロンティア浸食を 1 回(設定の値で。縁の門・穴埋め込み)。置き換え・覆った数を返す(2026-11-04)。 */
    public static int frontierForTest(ServerLevel level, ChunkPos chunkPos) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        return growChunkFrontier(level, chunkPos, cfg.overgrownBiomeCreepMaxConversionsPerChunk.get(), cfg.overgrownBiomeCreepSurfaceDepth.get(),
                cfg.growthChance.get(), cfg.overgrownBiomeCreepFrontierBaseChance.get(), cfg.overgrownBiomeCreepFrontierChancePerNeighbor.get());
    }

    public static boolean creepColumnForTest(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int topY = surfaceTopY(level, x, z);
        BlockPos target = findTarget(level, cursor, x, topY, z, minY,
                OvergrownVegetationConfig.COMMON.overgrownBiomeCreepSurfaceDepth.get());
        if (target == null) {
            return false;
        }
        BlockState state = level.getBlockState(target);
        if (!CorruptionRegistry.canCorrupt(level, target, state)) {
            return false;
        }
        boolean coveredBefore = OvergrownLayerData.isCovered(level, target);
        BlockState corrupted = CorruptionRegistry.corrupt(level, target, state, level.random);
        if (CorruptionRegistry.noChange(state, corrupted)) {
            CorruptionSpreader.growPlantsIfNewlyCovered(level, target, state);
            return !coveredBefore && OvergrownLayerData.isCovered(level, target);
        }
        CorruptionRegistry.placeCorrupted(level, target, state, corrupted, Block.UPDATE_ALL);
        UnderwaterPlants.grow(level, target, corrupted);
        return true;
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

    /**
     * {@code pos} を {@code before} から {@code after} へ置き換えるとき、隣への近隣通知
     * ({@link Block#UPDATE_NEIGHBORS})が<b>何も起こさない</b>と言い切れるか(2026-09-24 軽量化、報告 1505 / 600)。
     * 置き換える<b>前</b>に呼ぶこと。
     *
     * <p>近隣通知でやることは 3 つ —— 6 近傍の {@code neighborChanged}、NeoForge の近隣通知イベント
     * (この MOD は聞いていない)、
     * 置いたブロックが比較器の入力になるときの比較器への通知。次のときは省いても何も変わらない:
     * <ul>
     *     <li>6 近傍がどれも読み込み済みで、不透明な立方体({@code isSolidRender})で、BlockEntity を持たない
     *     バニラかこの MOD のブロック。そういうバニラのブロックの {@code neighborChanged} は、どれも「受けている信号が
     *     変わったか」だけを見る(ランプ・音符ブロック・TNT など)。例外のピストン(押す先が押せるかも見直す)は
     *     隣にあれば省かない。この MOD の {@code neighborChanged} を持つブロック(砲台・遮蔽盾・攻城砲)は立方体ではない。</li>
     *     <li>置き換えの前後で BlockEntity を持たず、信号を出さず({@code isSignalSource})、信号を通すかどうか
     *     ({@code isRedstoneConductor})も同じ —— 隣が受ける信号は変わらない。</li>
     *     <li>前後どちらのブロックも比較器の入力にならない({@code hasAnalogOutputSignal})。</li>
     * </ul>
     * 迷うものはすべて false(= 今までどおり {@link Block#UPDATE_ALL})。読み込まれていない隣も読まずに false にする。
     */
    private static boolean neighbourUpdateIsNoop(ServerLevel level, BlockPos pos, BlockState before, BlockState after,
                                                 BlockPos.MutableBlockPos scratch) {
        if (before.hasBlockEntity() || after.hasBlockEntity()
                || before.isSignalSource() || after.isSignalSource()
                || before.hasAnalogOutputSignal() || after.hasAnalogOutputSignal()
                || before.isRedstoneConductor(level, pos) != after.isRedstoneConductor(level, pos)) {
            return false;
        }
        for (Direction dir : DIRECTIONS) {
            scratch.setWithOffset(pos, dir);
            if (!level.isLoaded(scratch)) {
                return false;
            }
            BlockState neighbour = level.getBlockState(scratch);
            // ピストンは信号のほかに「押す先が押せるか」も見直すので、隣にあれば省かない。
            // BlockEntity を持つもの(機械など)と他 MOD のブロックは、通知で何をするか確かめようがないので省かない。
            if (!neighbour.isSolidRender(level, scratch) || neighbour.hasBlockEntity()
                    || neighbour.getBlock() instanceof PistonBaseBlock
                    || !neighbourReactionKnown(neighbour.getBlock())) {
                return false;
            }
        }
        return true;
    }

    /** 近隣通知への反応を確かめてあるブロック(バニラとこの MOD のもの)か({@link #neighbourUpdateIsNoop})。 */
    private static boolean neighbourReactionKnown(Block block) {
        String namespace = BuiltInRegistries.BLOCK.getKey(block).getNamespace();
        return "minecraft".equals(namespace) || OvergrownVegetation.MODID.equals(namespace);
    }

    /** 隣接 6 面の向き({@code Direction.values()} は呼ぶたびに配列を複製するので 1 つを使い回す。順番は同じ)。 */
    private static final Direction[] DIRECTIONS = Direction.values();

    private static boolean hasAirNeighbour(ServerLevel level, BlockPos pos, BlockPos.MutableBlockPos scratch) {
        for (Direction dir : DIRECTIONS) {
            scratch.setWithOffset(pos, dir);
            if (level.getBlockState(scratch).isAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 開発用(DevBridge の {@code deepwhy}、2026-11-03 村の覆いの切り分け): そのチャンクの深部浸食の様子 —— カーソル・次に選べるまでの tick・
     * 過繁茂か・入口(地表 256 列が「済み」か、{@link #deepenChunk} の 1. と同じ判定)で済んでいない列の数と、はじめの 4 列。
     */
    public static String deepenStatusForDev(ServerLevel level, ChunkPos chunkPos) {
        long key = chunkPos.toLong();
        Long2IntOpenHashMap cursors = DEEPEN_CURSOR.get(level.dimension());
        Long2LongOpenHashMap next = NEXT_DEEPEN.get(level.dimension());
        int cursor = cursors == null ? Integer.MAX_VALUE : cursors.get(key);
        boolean started = cursor != Integer.MAX_VALUE;
        StringBuilder sb = new StringBuilder("カーソル=" + (started ? String.valueOf(cursor) : "未開始")
                + " 次まで=" + (next == null || !next.containsKey(key) ? "-" : String.valueOf(next.get(key) - level.getGameTime()))
                + " 過繁茂=" + isFullyOvergrownChunk(level, chunkPos.x, chunkPos.z));
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int notDone = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = chunkPos.getMinBlockX() + lx, z = chunkPos.getMinBlockZ() + lz;
                int topY = surfaceTopY(level, x, z);
                if (topY < level.getMinBuildHeight()) {
                    continue;
                }
                probe.set(x, topY, z);
                BlockState state = level.getBlockState(probe);
                if (!surfaceDone(level, chunk, probe, state, true, !started)) {
                    if (++notDone <= 4) {
                        BlockPos under = OvergrownLayerData.isCorruptedCell(chunk, probe, state)
                                ? findFloorUnderCover(level, new BlockPos.MutableBlockPos(), x, topY, z, level.getMinBuildHeight()) : null;
                        sb.append(" | ").append(x).append(' ').append(topY).append(' ').append(z).append(' ').append(state)
                                .append(under == null ? "" : " 下の床 " + under.toShortString() + " " + level.getBlockState(under));
                    }
                }
            }
        }
        return sb.append(" | 済んでいない列 ").append(notDone).toString();
    }

    private static Long2IntOpenHashMap newCursorMap() {
        Long2IntOpenHashMap map = new Long2IntOpenHashMap();
        map.defaultReturnValue(Integer.MAX_VALUE);
        return map;
    }

    private OvergrownBiomeCreep() {
    }
}
