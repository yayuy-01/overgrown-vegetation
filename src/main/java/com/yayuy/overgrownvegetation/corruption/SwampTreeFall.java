package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedLianaBlock;
import com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 森が湿地・菌湿地帯に汚染されるとき、元からあった木を残さない。
 *
 * <p>湿地系の地域({@link OvergrowthRegionMap})でバニラの原木が汚染された瞬間に
 * ({@link CorruptionRegistry#corrupt(ServerLevel, BlockPos, BlockState, RandomSource)} から)その位置を
 * 積んでおき、毎 tick 少しずつ処理する。処理では原木の繋がり(木 1 本)とその葉を丸ごと集め、
 * <ul>
 *     <li>{@code swampFallenTreeChance} の確率で<b>倒木</b>にする: 一番下の原木 1 個だけを切り株として残し、
 *     幹の長さぶんの汚染された原木を、地面の起伏に沿ってランダムな水平方向へ横倒しに並べる
 *     (途中で崖や壁にぶつかればそこで止まる)。倒木の上面にはまれに薄い苔が付く。</li>
 *     <li>それ以外は<b>消滅</b>させる(原木も葉もすべて空気に)。</li>
 * </ul>
 * どちらも元の葉はすべて消す(このMODの葉は自然に枯れないため)。ドロップは出ない。
 *
 * <p>木の判定は {@code #minecraft:logs} の原木と、汚染された幹({@link ModBlocks#OVERGROWN_TRUNK_BLOCK}。
 * 覆いの記録が付いた元の原木はそのまま {@code #logs})を繋がりとして辿る。倒木は元の木の原木で並べる —— {@link #fallenLog}。
 * <b>このMODが生やす木の原木は辿らない</b>({@link #isModTreeLog}。2026-10-31、ユーザー報告「既存の木を消す仕組みが
 * 汚染地域に生えた mod の木も消している」)。{@link ModBlocks#OVERGROWN_LOG} / {@link ModBlocks#OVERGROWN_WOOD} は
 * Create 互換のため {@code #logs} に入っているので(10-14)、タグとは別に名指しで外す。昔は元の木の汚染された部分が
 * {@code OVERGROWN_LOG} に化けたのでそれも辿っていたが、mod の木の枝は幹から 6〜7 ブロック広がるので、元の木に斜めに
 * 1 か所触れただけで mod の木が丸ごと同じ木として消えていた(2026-11-01 からは元の原木が {@code OVERGROWN_LOG} に化けることも無い)。
 * 葉は元の木の葉も汚染されると {@link ModBlocks#OVERGROWN_LEAVES} になり mod の木の葉と見分けが付かないので、
 * 集めた葉のうち mod の原木から葉づたいに同じか近い葉は mod の木のものとして残す({@link #releaseModTreeLeaves})。
 * プレイヤーの建築(原木を使った家)も繋がりとして辿ってしまうので、繋がりが
 * {@code MAX_LOGS} を超えたら「木ではない」と見なして何もしない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SwampTreeFall {

    /** これより大きな原木の繋がりは木ではなく建築物と見なす。 */
    private static final int MAX_LOGS = 320;
    /** 葉は原木からこの距離までを木の一部と見なす(バニラの葉の減衰距離 + 余裕)。 */
    private static final int LEAF_REACH = 7;
    private static final int MAX_LEAVES = 4096;
    private static final int MIN_FALLEN_LENGTH = 3;
    private static final int MAX_FALLEN_LENGTH = 14;
    private static final int GROUND_SEARCH = 3;
    /** ドロップを出さずに置き換える(UPDATE_SUPPRESS_DROPS)。 */
    private static final int FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
    /**
     * 木を消すときのフラグ(2026-10-06)。形状更新を出さない —— 出すと木に付いたツタ・ココアなどが 1 マスずつ「壊れ」、
     * 破壊音と破片の粒子が一度に何百も出ていた(ドロップもした)。付いていた植物は {@link StrandedPlants#clearAround} が
     * 静かに消す。近隣更新は残す(水の中の木を消したあとへ水が流れ込むように)。
     */
    private static final int REMOVE_FLAGS = Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS
            | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    /** {@link Direction#values()} は呼ぶたびに配列を複製するので 1 つを使い回す(順番は同じ)。 */
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * 1 tick に空気へ置き換えるマスの上限(葉と原木の合計。2026-09-24 軽量化、報告 611 / 622)。仮決め 2026-09-24。
     *
     * <p>以前の予算は木の本数({@code swampTreeFallPerTick})だけで、大きな木(葉は最大 {@link #MAX_LEAVES})を
     * 同じ tick に丸ごと {@link Block#UPDATE_ALL} で消していた。1 マスごとに隣への通知・形状更新と、経路を持つ Mob
     * 全員の経路の確認が走るので、密林の大木では 1 tick に数千回になる。普通の木(葉と原木で数十〜二百マス)は
     * これまでどおり同じ tick に消え、これを超える大木だけが数 tick に分かれて消える。
     */
    private static final int BLOCKS_PER_TICK = 512;

    private static final Map<ResourceKey<Level>, LongLinkedOpenHashSet> QUEUE = new HashMap<>();
    /**
     * 撤去の途中の木(次元ごとに 1 本だけ)。これが終わるまで次の木は集めない —— 木を 1 本ずつ順に
     * 「集める → 全部消す → 倒木にする」流れは以前と同じにしておく(消えかけの木の葉を、次の木の葉の探索が
     * 辿ってしまわないように)。
     */
    private static final Map<ResourceKey<Level>, PendingFall> PENDING = new HashMap<>();

    /** 集め終わって、消している途中の木 1 本。 */
    private static final class PendingFall {
        /** 空気にするマス。葉 → 原木の順(原木を先に消すと、残った葉の減衰が始まるため)。 */
        final long[] blocks;
        /** 次に消す {@link #blocks} の添字。 */
        int next;
        final BlockPos lowest;
        /** 倒木に使う原木(集めたときの切り株の原木から決める。消し終えたあとは切り株も空気なので先に覚える)。 */
        final BlockState fallenLog;
        final int trunkLength;
        final double fallenChance;

        PendingFall(long[] blocks, BlockPos lowest, BlockState fallenLog, int trunkLength, double fallenChance) {
            this.blocks = blocks;
            this.lowest = lowest;
            this.fallenLog = fallenLog;
            this.trunkLength = trunkLength;
            this.fallenChance = fallenChance;
        }

        boolean done() {
            return next >= blocks.length;
        }
    }

    /**
     * ワールドを閉じたら積んである原木を捨てる(キーはディメンションと座標だけなので、残すと
     * 次に開いた別のワールドで、同じ座標にある木を倒してしまう)。起動し直したときと同じ状態に戻す。
     * 撤去の途中の木も同じ理由で捨てる。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QUEUE.clear();
        PENDING.clear();
    }

    /** 湿地系の地域でバニラの原木が汚染された。あとで木ごと処理する。 */
    public static void enqueue(ServerLevel level, BlockPos pos) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.swampTreeFall.get() && !cfg.jungleTreeFall.get()) {
            return;
        }
        QUEUE.computeIfAbsent(level.dimension(), k -> new LongLinkedOpenHashSet()).add(pos.asLong());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 止める(順番待ちは残り、オンで続きから)
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        LongLinkedOpenHashSet waiting = QUEUE.get(level.dimension());
        if (PENDING.get(level.dimension()) == null && (waiting == null || waiting.isEmpty())) {
            return;
        }
        // サーバーが重いときは予算の番人が次の tick へ回す(2026-10-06。結果は同じで遅れるだけ)。
        if (!TickBudget.mayStart(PerfJob.SWAMP_TREE_FALL)) {
            return;
        }
        long start = TickBudget.begin();
        try {
            tickFalls(level);
        } finally {
            TickBudget.end(PerfJob.SWAMP_TREE_FALL, start, 1);
        }
    }

    private static void tickFalls(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        LongLinkedOpenHashSet queue = QUEUE.get(dimension);
        PendingFall pending = PENDING.get(dimension);
        if (pending == null && (queue == null || queue.isEmpty())) {
            return;
        }
        int blockBudget = BLOCKS_PER_TICK;
        // 前の tick から続いている木を先に片付ける(この木は積んだ tick に本数の予算を使い済み)。
        if (pending != null) {
            blockBudget = removeSome(level, pending, blockBudget);
            if (!pending.done()) {
                return;
            }
            PENDING.remove(dimension);
            finishFall(level, pending);
        }
        if (queue == null) {
            return;
        }
        int budget = Math.max(1, OvergrownVegetationConfig.COMMON.swampTreeFallPerTick.get());
        while (budget-- > 0 && !queue.isEmpty() && blockBudget > 0) {
            long packed = queue.removeFirstLong();
            BlockPos pos = BlockPos.of(packed);
            if (!level.isLoaded(pos)) {
                continue;
            }
            PendingFall fall = collectTree(level, pos, queue, blockBudget);
            if (fall == null) {
                continue;
            }
            blockBudget = removeSome(level, fall, blockBudget);
            if (!fall.done()) {
                // 大木: 残りは次の tick 以降(終わるまで次の木は集めない)。
                PENDING.put(dimension, fall);
                return;
            }
            finishFall(level, fall);
        }
    }

    /**
     * {@code fall} の残りを最大 {@code budget} マス空気にし、残りの予算を返す。
     * 前の tick から続いている木はその間にチャンクが外れていることがあるので、読み込まれていないマスは飛ばす
     * (集めた tick のうちに消すぶんは必ず読み込み済み = 以前と同じ)。
     */
    private static int removeSome(ServerLevel level, PendingFall fall, int budget) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (budget > 0 && !fall.done()) {
            cursor.set(fall.blocks[fall.next++]);
            budget = TickBudget.spend(budget);
            // 集めた後に生えた封印木は消さない(2026-11-01。数 tick に分けて消す間に苗木が育つことがある)
            if (level.isLoaded(cursor) && !isSigillariaPart(level.getBlockState(cursor))) {
                level.setBlock(cursor, air, REMOVE_FLAGS);
                StrandedPlants.clearAround(level, cursor);
            }
        }
        return budget;
    }

    /** 4. 倒木にする / 消滅。木を全部消し終えた tick に呼ぶ(以前は集めた tick の最後)。 */
    private static void finishFall(ServerLevel level, PendingFall fall) {
        // 木に付いていた物の後始末(2026-10-16、ユーザー報告「消された木の跡に垂れ蔦・汚染された下草が空中に浮く」)。
        // 蔦(リアナ)はブロックエンティティで、支えが無くても残る作りなので StrandedPlants では消えない。下草・茂みは生き残りの判定を持たない。
        LongOpenHashSet removed = new LongOpenHashSet(fall.blocks);
        removeLianasEndingIn(level, removed);
        StrandedPlants.clearUnanchored(level, removed);
        if (!level.isLoaded(fall.lowest)) {
            return; // 撤去の途中で切り株のチャンクが外れた(集めた tick に終わる木では起きない)。
        }
        RandomSource random = level.getRandom();
        if (random.nextDouble() >= fall.fallenChance) {
            return;
        }
        layFallenTrunk(level, fall.lowest, fall.fallenLog, Math.max(1, fall.trunkLength), random);
    }

    /**
     * 始点か終点が消した木の原木だった蔦(リアナ)を静かに消す。蔦の主アンカーは始点の幹の隣にあり、終点はそこから
     * 蔦の長さ({@code vineMaxLength} / 手で張る {@code vinePlaceMaxLength})まで離れうるので、消した木の範囲をその分広げた
     * 読み込み済みのチャンクのブロックエンティティを見る(木 1 本につき 1 回)。
     */
    private static void removeLianasEndingIn(ServerLevel level, LongOpenHashSet removed) {
        if (removed.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long l : removed) {
            int x = BlockPos.getX(l);
            int z = BlockPos.getZ(l);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int reach = Math.max(cfg.vineMaxLength.get(), cfg.vinePlaceMaxLength.get()) + 2;
        List<BlockPos> primaries = new ArrayList<>();
        for (int cx = (minX - reach) >> 4; cx <= (maxX + reach) >> 4; cx++) {
            for (int cz = (minZ - reach) >> 4; cz <= (maxZ + reach) >> 4; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (net.minecraft.world.level.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof CorruptedLianaBlockEntity liana && liana.getPrimaryPos() == null
                            && (endsIn(liana.getSourcePos(), removed) || endsIn(liana.getTargetPos(), removed))) {
                        primaries.add(be.getBlockPos());
                    }
                }
            }
        }
        // 集め終えてから消す(消しながらだとチャンクのブロックエンティティの表を回している最中に書き換わる)。
        for (BlockPos primary : primaries) {
            CorruptedLianaBlock.removeQuietly(level, primary);
        }
    }

    private static boolean endsIn(@org.jetbrains.annotations.Nullable BlockPos end, LongOpenHashSet removed) {
        return end != null && removed.contains(end.asLong());
    }

    /**
     * 原木の繋がり(木 1 本)とその葉を集め、消す順に並べて返す。木ではない(建築物・ストラクチャー・葉の無い原木の塊)
     * ときや、この環境で無効なときは {@code null}。
     *
     * <p>以前の processTree の 1〜3 段目(集める・判定する・幹の長さを数える)そのもの。消す順番も以前と同じ
     * (葉 → 原木、それぞれ集合の並び順)。ただし {@code budgetThisTick} に収まらない大木だけは、葉を
     * 「原木から遠い順」に並べ替える: 数 tick に分けて消すあいだ、残っている葉から原木までの葉の道が
     * 途中で切れない(= バニラの葉の距離が伸びて減衰・ドロップし始めない)ようにするため。
     */
    @org.jetbrains.annotations.Nullable
    private static PendingFall collectTree(ServerLevel level, BlockPos start, LongLinkedOpenHashSet queue,
                                           int budgetThisTick) {
        if (!isTreeLog(level.getBlockState(start))) {
            return null;
        }
        // 環境ごとの有効/無効。湿地系は swampTreeFall、密林は jungleTreeFall(2026-09-11 に密林にも拡張)。
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        boolean swamp = OvergrowthRegionMap.blendedVariantAt(level, start.getX(), start.getZ(), 2).isSwamp();
        if (swamp ? !cfg.swampTreeFall.get() : !cfg.jungleTreeFall.get()) {
            return null;
        }
        double fallenChance = swamp ? cfg.swampFallenTreeChance.get() : cfg.jungleFallenTreeChance.get();
        // 0. 生成ストラクチャー(村・前哨基地など)のピース内にある原木は、その建物の一部なので触らない。
        //    ピース単位の判定なので、村の中に自然に生えた木(建物の箱の外)は普通に木として扱われる。
        if (isInsideStructurePiece(level, start)) {
            return null;
        }
        // 1. 原木の繋がりを集める。人工物の兆候(樹皮を剥いだ原木、木材・柵・階段などと隣接)があれば木ではない。
        LongOpenHashSet logs = new LongOpenHashSet();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(start);
        logs.add(start.asLong());
        BlockPos lowest = start;
        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
        while (!frontier.isEmpty()) {
            BlockPos p = frontier.poll();
            if (p.getY() < lowest.getY()) {
                lowest = p;
            }
            if (isStrippedLog(level.getBlockState(p))) {
                return null; // 樹皮を剥いだ原木は自然には生えない = 建築物。
            }
            for (Direction dir : DIRECTIONS) {
                scratch.setWithOffset(p, dir);
                if (level.isLoaded(scratch) && isBuildingBlock(level.getBlockState(scratch))) {
                    return null; // 木材・柵・階段・ドアなどに接している = 建築物。
                }
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos n = p.offset(dx, dy, dz);
                        if (logs.contains(n.asLong()) || !level.isLoaded(n)) {
                            continue;
                        }
                        if (isTreeLog(level.getBlockState(n))) {
                            if (isInsideStructurePiece(level, n)) {
                                return null; // 繋がりの先がストラクチャーの建物に入っている。
                            }
                            logs.add(n.asLong());
                            frontier.add(n);
                            if (logs.size() > MAX_LOGS) {
                                return null; // 建築物などの巨大な繋がり。触らない。
                            }
                        }
                    }
                }
            }
        }
        // 同じ木の他の原木が積まれていても二重処理しない。
        for (long l : logs) {
            queue.remove(l);
        }

        // 2. 葉を集める(原木から LEAF_REACH 以内を葉づたいに)。
        LongOpenHashSet leaves = new LongOpenHashSet();
        // 葉ごとの原木からの葉づたいの距離(1〜LEAF_REACH。幅優先なので最短)。大木を分けて消す順番にだけ使う。
        Long2IntOpenHashMap leafDist = new Long2IntOpenHashMap();
        ArrayDeque<long[]> leafFrontier = new ArrayDeque<>();
        for (long l : logs) {
            leafFrontier.add(new long[]{l, 0});
        }
        LongOpenHashSet seen = new LongOpenHashSet(logs);
        while (!leafFrontier.isEmpty() && leaves.size() < MAX_LEAVES) {
            long[] cur = leafFrontier.poll();
            BlockPos p = BlockPos.of(cur[0]);
            int dist = (int) cur[1];
            if (dist >= LEAF_REACH) {
                continue;
            }
            for (Direction dir : DIRECTIONS) {
                BlockPos n = p.relative(dir);
                long nl = n.asLong();
                if (seen.contains(nl) || !level.isLoaded(n)) {
                    continue;
                }
                seen.add(nl);
                if (isTreeLeaves(level.getBlockState(n))) {
                    leaves.add(nl);
                    leafDist.put(nl, dist + 1);
                    leafFrontier.add(new long[]{nl, dist + 1});
                }
            }
        }

        // 葉が 1 枚も付いていない原木の塊は木ではない(丸太置き場・柱など)。
        if (leaves.isEmpty()) {
            return null;
        }
        // 隣の mod の木の葉は残す(葉が全部 mod の木のものでも、葉の付いた原木なので木として消す)。
        releaseModTreeLeaves(level, leaves, leafDist);

        // 3. 幹の長さ(切り株の列に積まれた原木の本数)を数えてから、全部消す(消すのは呼び出し側の removeSome)。
        int trunkLength = 0;
        for (int y = lowest.getY(); y < lowest.getY() + 64; y++) {
            if (!logs.contains(BlockPos.asLong(lowest.getX(), y, lowest.getZ()))) {
                break;
            }
            trunkLength++;
        }
        long[] blocks = new long[leaves.size() + logs.size()];
        int k = 0;
        if (blocks.length <= budgetThisTick) {
            // この tick に消し切れる木: 以前と同じ順番(葉の集合の並び順)。
            for (long l : leaves) {
                blocks[k++] = l;
            }
        } else {
            // 数 tick に分かれる大木: 原木から遠い葉から消す(同じ距離の中は集合の並び順)。
            for (int d = LEAF_REACH; d >= 1; d--) {
                for (long l : leaves) {
                    if (leafDist.get(l) == d) {
                        blocks[k++] = l;
                    }
                }
            }
        }
        for (long l : logs) {
            blocks[k++] = l;
        }
        return new PendingFall(blocks, lowest, fallenLog(level.getBlockState(lowest)), trunkLength, fallenChance);
    }

    /**
     * 倒木の原木: 元の木の<b>覆われた原木</b>(2026-10-05 夜から、汚染された幹は同じ木の覆われた原木になる。オークの木はオークの倒木)。
     * 2026-10-30 から覆われた原木 = 元の原木 + 覆いの記録({@link #layFallenTrunk} が並べたあと記録を書く)。
     * 元が分からない(前のセーブで過繁茂した原木になっていた幹など)ときは過繁茂した原木。
     */
    private static BlockState fallenLog(BlockState stump) {
        if (CorruptionRegistry.coversInPlace(stump) && stump.hasProperty(RotatedPillarBlock.AXIS)) {
            return stump.getBlock().defaultBlockState();
        }
        return ModBlocks.OVERGROWN_LOG.get().defaultBlockState();
    }

    /** 並べた元の木の原木を記録で覆う(2026-10-30 から覆われた原木 = 原木 + 記録)。過繁茂した原木は何もしない。 */
    private static void coverIfInPlace(ServerLevel level, BlockPos pos, BlockState log) {
        if (CorruptionRegistry.coversInPlace(log)) {
            OvergrownLayerData.cover(level, pos);
        }
    }

    /** 切り株 1 個を残し、幹の長さぶんの原木を横倒しに並べる。 */
    private static void layFallenTrunk(ServerLevel level, BlockPos stump, BlockState upright, int trunkLength, RandomSource random) {
        level.setBlock(stump, upright, FLAGS);
        coverIfInPlace(level, stump, upright);

        int length = Math.min(MAX_FALLEN_LENGTH, Math.max(MIN_FALLEN_LENGTH, trunkLength - 1));
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        BlockState lying = upright.setValue(RotatedPillarBlock.AXIS, dir.getAxis());
        double mossChance = OvergrownVegetationConfig.COMMON.swampFallenTreeMossChance.get();

        int x = stump.getX();
        int y = stump.getY();
        int z = stump.getZ();
        LongArrayList placed = new LongArrayList();
        for (int i = 1; i <= length; i++) {
            x += dir.getStepX();
            z += dir.getStepZ();
            int groundY = findGroundY(level, x, y - 1, z);
            if (groundY == Integer.MIN_VALUE) {
                break;
            }
            int logY = groundY + 1;
            if (logY > y + 1) {
                break; // 登り坂にぶつかった(倒木は上へは伸びない)。
            }
            BlockPos at = new BlockPos(x, logY, z);
            BlockState existing = level.getBlockState(at);
            if (!existing.isAir() && !existing.canBeReplaced() && !existing.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                break;
            }
            level.setBlock(at, lying, FLAGS);
            coverIfInPlace(level, at, lying);
            placed.add(at.asLong());
            y = logY;
        }

        // 倒木の上面に薄い苔。
        if (mossChance > 0.0D) {
            for (long l : placed) {
                if (random.nextDouble() >= mossChance) {
                    continue;
                }
                BlockPos above = BlockPos.of(l).above();
                if (level.getBlockState(above).isAir()) {
                    BlockState moss = ModBlocks.OVERGROWN_MOSS_LAYER.get().defaultBlockState()
                            .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), Boolean.TRUE);
                    if (moss.canSurvive(level, above)) {
                        level.setBlock(above, moss, FLAGS);
                    }
                }
            }
        }
    }

    /**
     * {@code (x, z)} 列で {@code centerY ± GROUND_SEARCH} の範囲にある最上部の「動きを止めるブロック」(水面含む)の
     * Y。見つからなければ {@link Integer#MIN_VALUE}。
     */
    private static int findGroundY(ServerLevel level, int x, int centerY, int z) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = centerY + GROUND_SEARCH; y >= centerY - GROUND_SEARCH; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.blocksMotion() && !state.canBeReplaced()) {
                return y;
            }
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                return y - 1; // 水面: 倒木は水の中(水面の高さ)に横たわる。
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 生成ストラクチャーのピース(建物 1 棟ぶんの箱)の中か。 */
    private static boolean isInsideStructurePiece(ServerLevel level, BlockPos pos) {
        // 構造物の参照が 1 つも無いチャンクなら答えは「無い」(2026-10-10、0.1.9 の前の軽量化。TreeBurn と同じ。結果は同じ)
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null && chunk.getAllReferences().isEmpty()) {
            return false;
        }
        return level.structureManager().getStructureWithPieceAt(pos, holder -> true).isValid();
    }

    /** 樹皮を剥いだ原木・木(stripped_*)か。自然の木には現れない。 */
    private static boolean isStrippedLog(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(coveredToVanilla(state).getBlock()).getPath().startsWith("stripped_");
    }

    /**
     * 覆われたブロック(汚染された建物の一部)は元のブロックとして見る —— 覆われた樹皮を剥いだ原木・石レンガも建物のしるし。
     * 2026-10-30 から覆われたブロックは元のブロックのまま(記録で覆う)なので、そのまま返す。
     */
    private static BlockState coveredToVanilla(BlockState state) {
        return state;
    }

    /** 建築に使われるブロック(木材・柵・階段・ハーフブロック・ドア・トラップドア・壁・ガラス・松明など)か。 */
    private static boolean isBuildingBlock(BlockState state) {
        state = coveredToVanilla(state);
        return state.is(BlockTags.PLANKS) || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS) || state.is(BlockTags.DOORS)
                || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.WALLS) || state.is(BlockTags.WOOL)
                || state.is(BlockTags.BEDS) || state.is(BlockTags.CANDLES) || state.is(BlockTags.BANNERS)
                || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.LANTERN)
                || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE) || state.is(Blocks.CHEST) || state.is(Blocks.BARREL)
                || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE) || state.is(Blocks.BOOKSHELF)
                || state.is(Blocks.LADDER) || state.is(Blocks.HAY_BLOCK) || state.is(Blocks.BELL)
                || state.is(Blocks.SMOOTH_STONE) || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.BRICKS);
    }

    /**
     * 集めた葉のうち、このMODの木の葉を外す(2026-10-31)。汚染された元の木の葉も mod の木の葉も同じ
     * {@link ModBlocks#OVERGROWN_LEAVES} なので、近くの mod の原木から葉づたいの距離を測り、元の木の原木からの距離
     * ({@code leafDist})と同じか近い葉は mod の木のものとして集合から外す(境目で同点の葉は mod の木に付ける。仮決め)。
     * バニラの葉は汚染される前の元の木の葉なので、外さない。
     *
     * <p>mod の原木は、集めた mod の葉から mod の葉づたいに {@code LEAF_REACH - 1} 歩までの隣だけを探す
     * (それより遠い mod の原木からの距離は {@code LEAF_REACH} を超え、どの葉の {@code leafDist} にも勝たない)。
     * 集めた葉に mod の葉が無いか、近くに mod の原木が無ければ何もしない。
     */
    private static void releaseModTreeLeaves(ServerLevel level, LongOpenHashSet leaves, Long2IntOpenHashMap leafDist) {
        Block modLeaf = ModBlocks.OVERGROWN_LEAVES.get();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // 1. 近くの mod の原木を探す。
        ArrayDeque<long[]> frontier = new ArrayDeque<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        for (long l : leaves) {
            if (level.getBlockState(cursor.set(l)).is(modLeaf)) {
                frontier.add(new long[]{l, 0});
                seen.add(l);
            }
        }
        LongOpenHashSet modLogs = new LongOpenHashSet();
        int pushed = 0; // 集めた葉の外へ広げた葉の数(上限は元の木の葉集めと同じ MAX_LEAVES)
        while (!frontier.isEmpty() && pushed < MAX_LEAVES) {
            long[] cur = frontier.poll();
            int dist = (int) cur[1];
            for (Direction dir : DIRECTIONS) {
                cursor.set(cur[0]).move(dir);
                long nl = cursor.asLong();
                if (seen.contains(nl) || !level.isLoaded(cursor)) {
                    continue;
                }
                seen.add(nl);
                BlockState state = level.getBlockState(cursor);
                if (isModTreeLog(state)) {
                    modLogs.add(nl);
                } else if (state.is(modLeaf) && dist + 1 < LEAF_REACH) {
                    frontier.add(new long[]{nl, dist + 1});
                    pushed++;
                }
            }
        }
        if (modLogs.isEmpty()) {
            return;
        }
        // 2. mod の原木から葉づたいの距離を測り、同じか近い葉を外す。
        frontier.clear(); // 1 が上限で止まったときの残り(1 の距離)を混ぜない
        for (long l : modLogs) {
            frontier.add(new long[]{l, 0});
        }
        LongOpenHashSet reached = new LongOpenHashSet(modLogs);
        int walked = 0; // 辿った葉の数(上限は MAX_LEAVES)
        while (!frontier.isEmpty() && walked < MAX_LEAVES) {
            long[] cur = frontier.poll();
            int dist = (int) cur[1];
            if (dist >= LEAF_REACH) {
                continue;
            }
            for (Direction dir : DIRECTIONS) {
                cursor.set(cur[0]).move(dir);
                long nl = cursor.asLong();
                if (reached.contains(nl) || !level.isLoaded(cursor)) {
                    continue;
                }
                reached.add(nl);
                if (level.getBlockState(cursor).is(modLeaf)) {
                    if (leaves.contains(nl) && dist + 1 <= leafDist.get(nl)) {
                        leaves.remove(nl);
                        leafDist.remove(nl);
                    }
                    frontier.add(new long[]{nl, dist + 1});
                    walked++;
                }
            }
        }
    }

    /**
     * 元の木の原木か: {@code #logs} のうちこのMODが生やす木の原木を除いたもの、または汚染された幹(覆われた原木)。
     * mod の原木は {@code #logs} に入っているものがある(Create 互換)ので名指しで外す。
     */
    private static boolean isTreeLog(BlockState state) {
        return state.is(ModBlocks.OVERGROWN_TRUNK_BLOCK.get()) || (state.is(BlockTags.LOGS) && !isModTreeLog(state));
    }

    /** このMODが生やす木(苗木の木・巨大樹)の原木か(湿地の木・菌類の柱は 2026-10-31 に parked へ退避)。 */
    private static boolean isModTreeLog(BlockState state) {
        return state.is(ModBlocks.OVERGROWN_LOG.get()) || state.is(ModBlocks.OVERGROWN_WOOD.get())
                || state.is(ModBlocks.GIANT_OVERGROWN_LOG.get()) || state.is(ModBlocks.GIANT_OVERGROWN_CAMBIUM.get())
                || state.is(ModBlocks.HARDENED_GIANT_TRUNK.get())
                // 封印木(2026-11-01)。#logs に入っているので名指しで外す。葉(sigillaria_leaves・_fringe)は isTreeLeaves に入れない = 元の木の葉として集めない
                || state.is(ModBlocks.SIGILLARIA_LOG.get())
                // 枝の継ぎ目(2026-11-01)。#logs に入っているので名指しで外す(数えないと枝先から mod の木が元の木として片付く)
                || state.getBlock() instanceof com.yayuy.overgrownvegetation.block.BranchJointBlock;
    }

    /** 封印木の原木・枝・葉・球果(元の木の一部には決してならない)。 */
    private static boolean isSigillariaPart(BlockState state) {
        return state.is(ModBlocks.SIGILLARIA_LOG.get()) || state.is(ModBlocks.SIGILLARIA_BRANCH.get()) || state.is(ModBlocks.SIGILLARIA_LEAVES.get()) || state.is(ModBlocks.SIGILLARIA_LEAVES_FRINGE.get())
                || state.is(ModBlocks.SIGILLARIA_CONE.get())
                // 木生シダ(2026-11-01)も同じく消さない
                || state.is(ModBlocks.TREE_FERN_TRUNK.get()) || state.is(ModBlocks.TREE_FERN_LEAVES.get()) || state.is(ModBlocks.TREE_FERN_FROND.get());
    }

    private static boolean isTreeLeaves(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(ModBlocks.OVERGROWN_LEAVES.get());
    }

    private SwampTreeFall() {
    }
}
