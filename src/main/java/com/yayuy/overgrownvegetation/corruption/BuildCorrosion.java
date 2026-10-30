package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.SiegeStructurePartBlock;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import com.yayuy.overgrownvegetation.util.TickPhases;
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
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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
    /** 隣接 6 面の向き({@code Direction.values()} は呼ぶたびに配列を複製するので 1 つを使い回す。順番は同じ)。 */
    private static final Direction[] DIRECTIONS = Direction.values();

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

    /**
     * ワールドを閉じたら走査時刻の表と設置イベントの印を空にする(キーはディメンションとチャンク座標だけなので、
     * 次に別のワールドを開くと前のワールドの時刻と印が残り、そのぶん待たされたり、関係ないチャンクを見に行ったりする。
     * 起動し直したときと同じ状態に戻す)。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        NEXT_VISIT.clear();
        DIRTY.clear();
    }

    // ------------------------------------------------------------------------------------------
    // tick: 対象チャンクを集めて予算ぶんだけ浸食する
    // ------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 止める(順番待ちは残り、オンで続きから)
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.buildCorrosion.get() || !cfg.convertToOvergrownBiome.get()) {
            return;
        }
        int interval = Math.max(1, cfg.buildCorrosionIntervalTicks.get());
        // 位相をずらして、ほかの地形の周期処理と同じ tick に重ならないようにする(2026-09-24 軽量化、報告 589 / 1516。
        // 間隔は同じで、走る tick が周期の中でずれるだけ)。
        if (!TickPhases.isDue(level.getGameTime(), TickPhases.BUILD_CORROSION, interval)) {
            return;
        }
        // サーバーが重いときは予算の番人が次の周回へ回す(2026-10-06。結果は同じで遅れるだけ)。
        if (!TickBudget.mayStart(PerfJob.BUILD_CORROSION)) {
            return;
        }
        long start = TickBudget.begin();
        try {
            corrodePass(level, cfg, interval);
        } finally {
            TickBudget.end(PerfJob.BUILD_CORROSION, start, 1);
        }
    }

    private static void corrodePass(ServerLevel level, OvergrownVegetationConfig.Common cfg, int interval) {
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
                if (!loadingSettled(level, cp.x, cp.z)) {
                    continue; // 読み込みの途中。印は残して、読み込みが済んだ次のパスで見る。
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
                    if (!loadingSettled(level, cx, cz)) {
                        continue; // 読み込みの途中。走査時刻は書かないので、読み込みが済んだ次のパスで拾う。
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
        // 予算の番人に止められたら残りは次の周回(訪問の予定を付けないので、また候補に入る)。
        for (int i = 0; i < limit && (i == 0 || TickBudget.timeLeft()); i++) {
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
        // 2026-09-24 軽量化(報告 202 / 1472): 稼働中の胞子除去装置がこのチャンクに届かなければ、
        // 1 マスごとの canCorrupt で装置の判定(SavedData の表引き)を省く。届かないチャンクでは装置の判定は
        // 乱数を引かずに false を返すだけなので、結果も乱数の引き方も同じ(SporeDeviceData#mayInhibitBlocksIn)。
        boolean devicesNear = SporeDeviceData.mayInhibitBlocksIn(level, chunkPos, SporeDeviceData.Inhibit.CORRUPTION);
        // 空気にも汚染にも触れようのないセクション({@link #sealedSection})を 4096 マス丸ごと飛ばしてよいか。
        // 飛ばすマスは下の「埋まった石」の判定で必ず捨てられる(buriedNeighbors は 1 以上)。ただし canCorrupt が
        // 装置の判定で乱数を引くかもしれないチャンクでは飛ばさない(引く回数が変わるため)。
        boolean skipSealed = !devicesNear && buriedNeighbors > 0 && !level.isDebug();
        LevelChunk[] sideChunks = skipSealed ? loadedSideChunks(level, chunkPos) : null;

        // 1. 非空セクションを走査し、フロンティア候補 {x, y, z, 汚染源数} と、種候補(露出しているが
        //    汚染に接していないブロック)を集める。上限に達したら打ち切り(残りは次回)。
        List<int[]> frontier = new ArrayList<>();
        List<BlockPos> exposedUntouched = new ArrayList<>();
        int untouchedTotal = 0;
        boolean truncated = false;
        byte[] kinds = new byte[4096];

        LevelChunkSection[] sections = chunk.getSections();
        scan:
        for (int si = sections.length - 1; si >= 0; si--) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            // パレットに「空気でも汚染済みでもない」状態が 1 つも無いセクションは、下のループで全マスが最初の
            // 判定で飛ばされるだけなので丸ごと飛ばす(汚染しきった地下など。結果は同じ)。パレットには置き換わって
            // 使われなくなった状態が残ることがあるが、それは「走査する」側に倒れるだけ。
            if (!section.maybeHas(s -> !s.isAir() && !s.is(ModTags.CORRUPTED))) {
                continue;
            }
            // 地下深くの岩盤のように、このセクションにも上下・東西南北の隣のセクションにも空気と汚染が無ければ、
            // どのマスも「露出していない・汚染に接していない」ので下の判定で必ず捨てられる。丸ごと飛ばす(結果は同じ)。
            if (skipSealed && sealedSection(sections, si, sideChunks)) {
                continue;
            }
            int sectionY = chunk.getSectionYFromSectionIndex(si) << 4;
            // 区画の 4096 マスの「空気 / 汚染済み / それ以外」を先に 1 回だけ判定しておく(2026-10-06、③)。
            // 以前は 1 マスごとに自分と隣 6 面でタグを引き直していて、タグの判定だけで建物の侵食の時間の 6 割(1 回 35 ms)だった。
            // 区画の中の隣はこの表を引き、区画をはみ出す面だけ今までどおり読む。読むブロックも判定も同じなので結果は変わらない。
            fillKinds(section, kinds);
            for (int ly = 15; ly >= 0; ly--) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        if (kinds[(ly << 8) | (lz << 4) | lx] != KIND_OTHER) {
                            continue;
                        }
                        BlockState state = section.getBlockState(lx, ly, lz);
                        cursor.set(baseX + lx, sectionY + ly, baseZ + lz);
                        // 2026-09-24 軽量化(報告 202 の (1)): 装置の判定を省けるチャンク(devicesNear が false)では、
                        // canCorrupt を下の隣接 6 面の判定の<b>後ろ</b>へ回す。装置の判定を省いた canCorrupt は読むだけで
                        // 乱数も引かず、隣接面の判定も読むだけなので、2 つの AND の順番を入れ替えても残るマスは同じ。
                        // 大半を占める「埋まった石」が、タグ判定の束を払わずに隣接面だけで落ちる。
                        // 装置が届くチャンク(canCorrupt が乱数を引きうる)と、本体の位置で装置まで見る攻城戦の部品ブロックは
                        // 今までどおり先に判定する(判定する回数が変わると乱数の引き方が変わるため)。
                        boolean canCorruptFirst = devicesNear || state.getBlock() instanceof SiegeStructurePartBlock;
                        if (canCorruptFirst && !CorruptionRegistry.canCorrupt(level, cursor, state, devicesNear)) {
                            continue;
                        }
                        int corruptedFaces = 0;
                        boolean exposed = false;
                        for (Direction dir : DIRECTIONS) {
                            int nlx = lx + dir.getStepX();
                            int nly = ly + dir.getStepY();
                            int nlz = lz + dir.getStepZ();
                            // 同じセクションの中なら先に作った表を引く(このセクションは空気だけではないので
                            // Level#getBlockState もここを読む。結果は同じ)。はみ出す面だけチャンクを引いて読む。
                            if (((nlx | nly | nlz) & ~15) == 0) {
                                byte kind = kinds[(nly << 8) | (nlz << 4) | nlx];
                                if (kind == KIND_CORRUPTED) {
                                    corruptedFaces++;
                                } else if (kind == KIND_AIR) {
                                    exposed = true;
                                }
                                continue;
                            }
                            BlockState n = reader.getBlockState(baseX + nlx, sectionY + nly, baseZ + nlz);
                            if (n.is(ModTags.CORRUPTED)) {
                                corruptedFaces++;
                            } else if (n.isAir()) {
                                exposed = true;
                            }
                        }
                        if (!exposed && corruptedFaces < buriedNeighbors) {
                            continue; // 埋まった石など。深部浸食の担当。
                        }
                        if (!canCorruptFirst && !CorruptionRegistry.canCorrupt(level, cursor, state, false)) {
                            continue; // 上で後回しにした判定(devicesNear が false のときだけここへ来る)。
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
            CorruptionSpreader.growPlantsIfNewlyCovered(level, pos, state); // 覆っただけのマスにも植物(2026-10-10)
            return false;
        }
        BlockPos hit = pos.immutable();
        CorruptionRegistry.placeCorrupted(level, hit, state, corrupted, Block.UPDATE_ALL);
        CorruptionSpreader.growSurfacePlants(level, hit, corrupted, CorruptionSpreader.surfaceGrowthChance(level, hit));
        return true;
    }

    /** 東西南北の隣のチャンク。読み込みの済んでいないものは {@code null}(読み込みはしない)。 */
    private static LevelChunk[] loadedSideChunks(ServerLevel level, ChunkPos chunkPos) {
        return new LevelChunk[] {
                level.getChunkSource().getChunkNow(chunkPos.x - 1, chunkPos.z),
                level.getChunkSource().getChunkNow(chunkPos.x + 1, chunkPos.z),
                level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z - 1),
                level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z + 1)
        };
    }

    /** パレットの状態が「空気か汚染済み」か。{@link #sealedSection} で、隣接面の判定に効くものを探す。 */
    private static final Predicate<BlockState> AIR_OR_CORRUPTED = s -> s.isAir() || s.is(ModTags.CORRUPTED);

    /** {@link #fillKinds} の値: 空気でも汚染済みでもない / 空気 / 汚染済み。 */
    private static final byte KIND_OTHER = 0;
    private static final byte KIND_AIR = 1;
    private static final byte KIND_CORRUPTED = 2;

    /**
     * 区画の 4096 マスを「空気 / 汚染済み / それ以外」に分けて {@code kinds}(添え字 {@code (y << 8) | (z << 4) | x})へ書く(2026-10-06、③)。
     * 汚染済みの判定はタグなので重い —— 区画の種類表(パレット)に汚染済みが 1 つも無ければ、4096 マスとも空気かどうかだけ見る。
     */
    private static void fillKinds(LevelChunkSection section, byte[] kinds) {
        boolean anyCorrupted = section.maybeHas(s -> s.is(ModTags.CORRUPTED));
        BlockState last = null;
        byte lastKind = KIND_OTHER;
        for (int i = 0; i < 4096; i++) {
            BlockState s = section.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
            if (s != last) { // 並んだマスは同じブロックが多いので、直前と同じなら判定し直さない
                last = s;
                lastKind = anyCorrupted && s.is(ModTags.CORRUPTED) ? KIND_CORRUPTED : s.isAir() ? KIND_AIR : KIND_OTHER;
            }
            kinds[i] = lastKind;
        }
    }

    /**
     * セクション {@code si} のどのマスも、6 近傍に空気も汚染ブロックも無いと<b>パレットだけで言い切れる</b>か
     * (2026-09-24 軽量化、報告 202)。true なら {@link #corrodeChunk} の走査でこのセクションのマスは全部
     * 「埋まっていて汚染に接していない」で捨てられるので、1 マスも読まずに飛ばしてよい。
     *
     * <p>見るのは自分・上下のセクションと、東西南北の隣のチャンクの同じ高さのセクション(隣接 6 面が届く範囲)。
     * パレットは置かれている状態の上位集合なので、載っていなければ本当に無い。次のときは「言い切れない」に倒す:
     * 空気だけのセクション・高さ範囲の外(読むと AIR / VOID_AIR = 露出)、隣のチャンクが読み込まれていない
     * (1 マスずつの走査はそこを同期で読むので、今までどおりそちらに任せる)、自分のセクションに攻城戦の部品ブロックが
     * ありうる(canCorrupt が本体の位置で装置の判定をして乱数を引くことがあるので、飛ばすと引く回数が変わる)。
     */
    private static boolean sealedSection(LevelChunkSection[] sections, int si, LevelChunk[] sideChunks) {
        if (sections[si].maybeHas(s -> s.isAir() || s.is(ModTags.CORRUPTED)
                || s.getBlock() instanceof SiegeStructurePartBlock)) {
            return false;
        }
        if (si + 1 >= sections.length || mayTouchAirOrCorrupted(sections[si + 1])) {
            return false;
        }
        if (si - 1 < 0 || mayTouchAirOrCorrupted(sections[si - 1])) {
            return false;
        }
        for (LevelChunk side : sideChunks) {
            if (side == null) {
                return false;
            }
            LevelChunkSection[] sideSections = side.getSections();
            if (si >= sideSections.length || mayTouchAirOrCorrupted(sideSections[si])) {
                return false;
            }
        }
        return true;
    }

    /** 隣のセクションを読むと空気か汚染ブロックが出てくる<b>かもしれない</b>か。 */
    private static boolean mayTouchAirOrCorrupted(@Nullable LevelChunkSection section) {
        return section == null || section.hasOnlyAir() || section.maybeHas(AIR_OR_CORRUPTED);
    }

    /** チャンク中心の 1 点を見て、そのチャンクが建築浸食の対象バイオームか。 */
    private static boolean isTargetChunk(ServerLevel level, int cx, int cz, boolean includeOvergrowthStage) {
        BlockPos sample = new BlockPos((cx << 4) + 8, level.getSeaLevel(), (cz << 4) + 8);
        var biome = level.getBiome(sample);
        return includeOvergrowthStage ? ModBiomes.isOvergrownLike(biome) : ModBiomes.isFullyOvergrown(biome);
    }

    /**
     * このチャンクと周り 8 チャンクに「読み込みの途中」のものが無いか。
     *
     * <p>{@code hasChunk} は読み込み予定(生成・読み込みの途中)でも true になり、そのまま読むと終わるまで
     * サーバースレッドが止まる(バイオームの確認、走査、はみ出した隣接面の読み、変換で周りへ出す更新)。
     * 途中のものがあれば今回は見送る —— 走査時刻も書かず設置イベントの印も残すので、読み込みが済んだ
     * 次のパスでこれまでと同じ処理になる。
     * 読み込み予定にも入っていない隣({@code hasChunk} が false。読み込み範囲の外周のさらに外)はこれまでどおり
     * 見送らない(見送ると外周のチャンクが、プレイヤーが近づくまでいつまでも処理されなくなるため)。
     */
    private static boolean loadingSettled(ServerLevel level, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null
                        && level.hasChunk(cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
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
