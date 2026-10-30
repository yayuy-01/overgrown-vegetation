package com.yayuy.overgrownvegetation.purify;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor;
import com.yayuy.overgrownvegetation.covered.CoveredBlocks;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 木が燃える。2026-10-08 夜の「原木を宙に残さない」(ユーザー指定「燃やした後木の原木が宙に残っていたりする。木が燃えた場合木全体がしっかり燃えるように」)を、
 * 10-09 朝に作り直した形で守る。
 *
 * <p><b>ユーザー指定(10-09 朝)</b>: 火炎放射器はふつうの火を広くつける道具になり、この MOD の木は燃え尽きにくく、燃え移りにくい
 * (ふつうの火の表の値 —— {@link VanillaFire})。木は 1 マスずつゆっくり燃え、<b>幹が焼け切れて地面から離れた部分だけが、丸ごと燃え落ちる</b>
 * (根元の切り株は残る。Claude が出した 3 択から選ばれた)。
 *
 * <p>ふつうの火が木のマスを燃やし尽くすと({@link VanillaFire#consume})、{@link #onConsumed} が隣の木を調べる。原木は原木のつながり(斜めを含む 26 方向)で、
 * どれかが固いブロック(地面・岩・建物など、木でも火でもないもの)に触れていれば支えがある。触れていなければ、その原木と、葉づたいに {@value #LEAF_REACH} マス以内の葉、
 * 付いた燃える植物(つる・垂れた蔦)を丸ごと燃やす(Claude 仮決め: 全部のマスに 0〜{@value #IGNITE_STAGGER} tick ずらして火が付き、外側にふつうの火が付く。
 * 燃え尽きるまで原木 {@value #LOG_BURN}〜{@value #LOG_BURN} + {@value #LOG_BURN_RANGE} tick(25〜40 秒)・葉 {@value #LEAF_BURN}〜{@value #LEAF_BURN} + {@value #LEAF_BURN_RANGE} tick・
 * 植物 {@value #PLANT_BURN}〜{@value #PLANT_BURN} + {@value #PLANT_BURN_RANGE} tick)。原木が葉づたいに見つからない葉の塊は、どの葉も固いブロックに触れていなければ燃やす。
 * 汚染されていない木も同じ。
 *
 * <p><b>建物の原木は丸ごと燃やさない</b>(湿地の木の片付け {@code corruption/SwampTreeFall} と同じ見分け方): 樹皮を剥いだ原木を含む・木材や柵などに接している・
 * 生成ストラクチャーの中・原木が {@value #MAX_LOGS} を超える、のどれか。<b>巨大樹</b>は大きすぎて地面から離れたかを確かめられないので、1 マスずつ燃えるだけ。
 *
 * <p><b>地面に埋まった根</b>は燃え尽きると空気ではなく焼けた土になる({@link #inGround}。2026-10-08 夜にクライアントで足した仮決め —— 空気にすると根元のまわりに穴が並んだ)。
 *
 * <p>燃え落ちる途中の仕事はセーブしない。読み直した後は、残った部分に付いているふつうの火がまた 1 マス燃やし尽くしたときに、もう一度燃え落ち始める。
 * 読み込まれていない所・周り 8 チャンクが読み込まれていない所では燃えるのを待つ(縁の近隣更新で読み込みを待たない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TreeBurn {

    /** これより大きな原木のつながりは木ではなく建物と見なす(湿地の木の片付けと同じ)。 */
    private static final int MAX_LOGS = 320;
    /** 葉は原木から葉づたいにこの距離までを木の一部と見なす(バニラの葉の減衰距離 + 余裕)。 */
    private static final int LEAF_REACH = 7;
    private static final int MAX_LEAVES = 4096;
    /** 地面から離れた部分の全部のマスに火が付くまでのずれ(tick)。 */
    static final int IGNITE_STAGGER = 50;
    /** 火が付いてから燃え尽きるまで(tick): 原木。 */
    static final int LOG_BURN = 500;
    static final int LOG_BURN_RANGE = 300;
    /** 火が付いてから燃え尽きるまで(tick): 葉。 */
    static final int LEAF_BURN = 100;
    static final int LEAF_BURN_RANGE = 100;
    /** 火が付いてから燃え尽きるまで(tick): 付いた植物。 */
    static final int PLANT_BURN = 20;
    static final int PLANT_BURN_RANGE = 40;
    /** 燃えている木のマス 1 つの外側に付けるふつうの火の数。 */
    private static final int FLAMES = 2;
    /** 1 tick に進める燃え方の出来事の上限(燃え落ちる途中の木ぜんぶで)。 */
    private static final int PER_TICK = 512;
    /** 読み込まれていない所の出来事を見送る間隔(tick)。 */
    private static final int RETRY = 100;
    /** 建物だったマスを覚えておく長さ(tick)。同じ建物を燃えるたびに調べ直さない。 */
    private static final int REJECT_TICKS = 100;
    private static final long IGNITE = 0L;
    private static final long CONSUME = 1L;
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final List<Job> JOBS = new ArrayList<>();
    /** 回している途中に始まった木(回し終えてから {@link #JOBS} へ移す)。 */
    private static final List<Job> STARTED = new ArrayList<>();
    private static boolean running;
    /** 燃え落ちる途中の木のマス(次元ごと)。二重に始めない。 */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> CLAIMED = new HashMap<>();
    /** 建物だったマス → 覚えている期限(ゲーム時間)。 */
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> REJECTED = new HashMap<>();

    // ------------------------------------------------------------------ 木のブロック

    /** 原木・幹(バニラの {@code #logs}・覆われた原木・過繁茂の原木・湿地の木・菌の柱・巨大樹)。 */
    public static boolean isWood(BlockState state) {
        return state.is(BlockTags.LOGS) || Burning.isModWood(state);
    }

    /** 葉(バニラの {@code #leaves}・過繁茂の葉・湿地の葉・菌の傘)。 */
    public static boolean isLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES) || Burning.isModLeaf(state);
    }

    /** 木のブロック(汚染されていないものも含む)。 */
    public static boolean isTreeBlock(BlockState state) {
        return isWood(state) || isLeaf(state);
    }

    // ------------------------------------------------------------------ 幹が焼け切れたら

    /**
     * ふつうの火が木のマス {@code pos} を燃やし尽くした({@link VanillaFire#consume} から)。隣の木が地面から離れていたら(幹が焼け切れた)、
     * その部分を丸ごと燃やす。燃え落ちる途中の木・建物の原木・巨大樹には何もしない。
     */
    public static void onConsumed(ServerLevel level, BlockPos pos) {
        LongOpenHashSet claim = claimed(level);
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList logStarts = new LongArrayList();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos n = pos.offset(dx, dy, dz);
                    long nk = n.asLong();
                    if (seen.contains(nk) || claim.contains(nk) || isRejected(level, nk) || !loaded(level, n)) {
                        continue;
                    }
                    BlockState s = level.getBlockState(n);
                    if (isWood(s)) {
                        logStarts.add(nk);
                    } else if (isLeaf(s) && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) {
                        LongOpenHashSet cluster = leafCluster(level, n, seen, logStarts);
                        if (cluster != null) {
                            burnDetached(level, cluster);
                        }
                    }
                }
            }
        }
        for (int i = 0; i < logStarts.size(); i++) {
            long l = logStarts.getLong(i);
            if (seen.contains(l) || claim.contains(l)) {
                continue;
            }
            LongOpenHashSet members = detachedLogs(level, BlockPos.of(l), seen);
            if (members != null) {
                burnDetached(level, members);
            }
        }
    }

    /**
     * 原木から: 原木のつながり(26 方向)をたどる。どれかが固いブロックに触れていれば支えがあるので {@code null}。建物の原木・巨大樹・
     * 読み込まれていない所に続くものも {@code null}。離れていれば、その原木と、葉づたいに {@value #LEAF_REACH} マス以内の葉と、付いた燃える植物を返す。
     * {@code seen} に調べたマスを足す(同じ塊を二度調べない)。
     */
    @Nullable
    static LongOpenHashSet detachedLogs(ServerLevel level, BlockPos start, LongOpenHashSet seen) {
        LongOpenHashSet claim = claimed(level);
        LongOpenHashSet logs = new LongOpenHashSet();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        logs.add(start.asLong());
        frontier.add(start);
        while (!frontier.isEmpty()) {
            BlockPos p = frontier.poll();
            BlockState ps = level.getBlockState(p);
            if (Burning.isGiant(ps)) {
                seen.addAll(logs);
                return null; // 巨大樹: 1 マスずつ燃えるだけ
            }
            if (isStrippedLog(ps)) {
                reject(level, logs);
                seen.addAll(logs);
                return null; // 樹皮を剥いだ原木は自然には生えない = 建物
            }
            for (Direction dir : DIRECTIONS) {
                BlockPos n = p.relative(dir);
                if (!loaded(level, n)) {
                    seen.addAll(logs);
                    return null;
                }
                BlockState s = level.getBlockState(n);
                if (isBuildingBlock(s)) {
                    reject(level, logs);
                    seen.addAll(logs);
                    return null; // 木材・柵・階段・ドアなどに接している = 建物
                }
                if (isTerrain(s)) {
                    seen.addAll(logs);
                    return null; // 固いブロックに触れている = 支えがある(切り株も)
                }
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos n = p.offset(dx, dy, dz);
                        long nk = n.asLong();
                        if (logs.contains(nk) || claim.contains(nk)) {
                            continue;
                        }
                        if (!loaded(level, n)) {
                            seen.addAll(logs);
                            return null;
                        }
                        if (!isWood(level.getBlockState(n))) {
                            continue;
                        }
                        if (logs.size() >= MAX_LOGS || isInsideStructurePiece(level, n)) {
                            reject(level, logs);
                            seen.addAll(logs);
                            return null; // 建物などの大きな繋がり・ストラクチャーの建物
                        }
                        logs.add(nk);
                        frontier.add(n);
                    }
                }
            }
        }
        seen.addAll(logs);
        if (isInsideStructurePiece(level, start)) {
            return null;
        }
        LongOpenHashSet members = new LongOpenHashSet(logs);
        members.addAll(leavesNear(level, logs, members));
        addAttachedPlants(level, members);
        return members;
    }

    /**
     * 葉から: 葉づたいに {@value #LEAF_REACH} マスまで原木を探し、いちばん近い原木が見つかれば {@code logStarts} に足して {@code null}(原木の側で見る)。
     * 見つからなければ葉の塊(6 方向のつながり、{@value #MAX_LEAVES} まで)を集め、どの葉も固いブロックに触れていなければ、その葉と付いた植物を返す。
     * 森の火事では葉が次々に燃え尽きるので、原木が 1 つ見つかったら探すのをやめ、調べた葉は {@code seen} に入れる(同じ茂みを隣の葉ごとに調べ直さない)。
     */
    @Nullable
    static LongOpenHashSet leafCluster(ServerLevel level, BlockPos start, LongOpenHashSet seen, LongArrayList logStarts) {
        LongOpenHashSet claim = claimed(level);
        LongOpenHashSet near = new LongOpenHashSet();
        near.add(start.asLong());
        ArrayDeque<long[]> q = new ArrayDeque<>();
        q.add(new long[]{start.asLong(), 0});
        while (!q.isEmpty()) {
            long[] cur = q.poll();
            BlockPos p = BlockPos.of(cur[0]);
            for (Direction dir : DIRECTIONS) {
                BlockPos n = p.relative(dir);
                long nk = n.asLong();
                if (near.contains(nk) || !loaded(level, n)) {
                    continue;
                }
                BlockState s = level.getBlockState(n);
                if (isWood(s)) {
                    if (!claim.contains(nk)) {
                        logStarts.add(nk);
                    }
                    seen.addAll(near);
                    return null; // この葉は木の一部(木が地面から離れていれば、原木の側で葉ごと燃やす)
                }
                if (isLeaf(s) && cur[1] + 1 < LEAF_REACH) {
                    near.add(nk);
                    q.add(new long[]{nk, cur[1] + 1});
                }
            }
        }

        LongOpenHashSet leaves = new LongOpenHashSet();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        leaves.add(start.asLong());
        frontier.add(start);
        while (!frontier.isEmpty()) {
            BlockPos p = frontier.poll();
            for (Direction dir : DIRECTIONS) {
                BlockPos n = p.relative(dir);
                long nk = n.asLong();
                if (leaves.contains(nk)) {
                    continue;
                }
                if (!loaded(level, n) || claim.contains(nk)) {
                    seen.addAll(leaves);
                    return null; // 読み込まれていない所・燃え落ちる途中の木に続く
                }
                BlockState s = level.getBlockState(n);
                if (isLeaf(s)) {
                    if (leaves.size() >= MAX_LEAVES) {
                        seen.addAll(leaves);
                        return null;
                    }
                    leaves.add(nk);
                    frontier.add(n);
                } else if (isWood(s) || isTerrain(s)) {
                    seen.addAll(leaves);
                    return null; // 遠くの原木か固いブロックに触れている = 支えがある
                }
            }
        }
        seen.addAll(leaves);
        LongOpenHashSet members = new LongOpenHashSet(leaves);
        addAttachedPlants(level, members);
        return members;
    }

    /** 原木から葉づたいに {@value #LEAF_REACH} マス以内の葉(燃え落ちる途中の木のものは除く)。 */
    private static LongOpenHashSet leavesNear(ServerLevel level, LongOpenHashSet logs, LongOpenHashSet exclude) {
        LongOpenHashSet claim = claimed(level);
        LongOpenHashSet leaves = new LongOpenHashSet();
        LongOpenHashSet visited = new LongOpenHashSet(exclude);
        ArrayDeque<long[]> q = new ArrayDeque<>();
        for (long l : logs) {
            q.add(new long[]{l, 0});
        }
        while (!q.isEmpty() && leaves.size() < MAX_LEAVES) {
            long[] cur = q.poll();
            if (cur[1] >= LEAF_REACH) {
                continue;
            }
            BlockPos p = BlockPos.of(cur[0]);
            for (Direction dir : DIRECTIONS) {
                BlockPos n = p.relative(dir);
                long nk = n.asLong();
                if (!visited.add(nk) || claim.contains(nk) || !loaded(level, n)) {
                    continue;
                }
                if (isLeaf(level.getBlockState(n))) {
                    leaves.add(nk);
                    q.add(new long[]{nk, cur[1] + 1});
                }
            }
        }
        return leaves;
    }

    /** 木のマスに付いた燃える植物(つる・垂れた蔦・根元の草。汚染されていないものも)を足す。 */
    private static void addAttachedPlants(ServerLevel level, LongOpenHashSet members) {
        LongArrayList attached = new LongArrayList();
        for (long l : members) {
            BlockPos p = BlockPos.of(l);
            for (Direction dir : DIRECTIONS) {
                BlockPos n = p.relative(dir);
                long nl = n.asLong();
                if (!members.contains(nl) && loaded(level, n) && Burning.isLightPlant(level, n, level.getBlockState(n))) {
                    attached.add(nl);
                }
            }
        }
        members.addAll(attached);
    }

    /** 地面から離れた部分を丸ごと燃やし始める。 */
    private static void burnDetached(ServerLevel level, LongOpenHashSet members) {
        LongOpenHashSet claim = claimed(level);
        for (LongIterator it = members.iterator(); it.hasNext(); ) {
            if (claim.contains(it.nextLong())) {
                it.remove();
            }
        }
        if (members.isEmpty()) {
            return;
        }
        claim.addAll(members);
        Job job = new Job(level, members);
        start(job);
        job.igniteAll(level.getGameTime());
    }

    /** そのマスが燃え落ちる途中の木の一部か(テスト・コマンド用)。 */
    public static boolean isClaimed(ServerLevel level, BlockPos pos) {
        return claimed(level).contains(pos.asLong());
    }

    // ------------------------------------------------------------------ 炎を出さずに消す

    /**
     * 汚染された木を炎を出さずに丸ごと消す(焼けた荒地が元に戻るときの後始末 {@link Burning#cleanChunk} から)。巨大樹・建物・汚染されていない木は消さない。
     *
     * @return 消したマスの数
     */
    public static int removeQuietly(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isTreeBlock(state) || Burning.isGiant(state)) {
            return 0;
        }
        long key = pos.asLong();
        if (claimed(level).contains(key) || isRejected(level, key)) {
            return 0;
        }
        LongOpenHashSet touched = new LongOpenHashSet();
        Found found = find(level, pos, touched, true);
        if (found == null || found.giant != null) {
            reject(level, touched);
            return 0;
        }
        int removed = 0;
        for (long l : found.members) {
            BlockPos p = BlockPos.of(l);
            if (LoadedChunks.neighbourhoodLoaded(level, p.getX() >> 4, p.getZ() >> 4)) {
                BlockState s = level.getBlockState(p);
                // 地面に埋まった根は土に(空気にすると穴が並ぶ)。
                level.setBlock(p, isWood(s) && inGround(level, p) ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL);
                removed++;
            }
        }
        return removed;
    }

    /** 見つけた木: ふつうの木なら木のマス、巨大樹なら当たった巨大樹のマス。 */
    static final class Found {
        LongOpenHashSet members;
        BlockPos giant;
    }

    /**
     * 当たったマスから木 1 本を集める({@link #removeQuietly} 用)。木でなければ {@code null}({@code touched} に調べたマスが残る)。
     * 葉に当たったら葉づたいに原木を探す(見つからなければ葉の塊だけ)。巨大樹に行き当たったら巨大樹として返す。
     * {@code requireCorrupted} なら、汚染されたブロックを 1 つも含まない木は木と見なさない(後始末で汚染されていない木を消さない)。
     */
    @Nullable
    static Found find(ServerLevel level, BlockPos hit, LongOpenHashSet touched, boolean requireCorrupted) {
        BlockState hitState = level.getBlockState(hit);
        if (Burning.isGiant(hitState)) {
            Found f = new Found();
            f.giant = hit;
            return f;
        }
        LongOpenHashSet logs = new LongOpenHashSet();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        if (isWood(hitState)) {
            logs.add(hit.asLong());
            frontier.add(hit);
        } else {
            // 1. 葉に当たった: 葉づたいに原木を探す。
            LongOpenHashSet leafStart = new LongOpenHashSet();
            leafStart.add(hit.asLong());
            ArrayDeque<long[]> q = new ArrayDeque<>();
            q.add(new long[]{hit.asLong(), 0});
            while (!q.isEmpty()) {
                long[] cur = q.poll();
                if (cur[1] >= LEAF_REACH) {
                    continue;
                }
                BlockPos p = BlockPos.of(cur[0]);
                for (Direction dir : DIRECTIONS) {
                    BlockPos n = p.relative(dir);
                    if (leafStart.contains(n.asLong()) || logs.contains(n.asLong()) || !loaded(level, n)) {
                        continue;
                    }
                    BlockState s = level.getBlockState(n);
                    if (Burning.isGiant(s)) {
                        Found f = new Found();
                        f.giant = n;
                        return f;
                    }
                    if (isWood(s)) {
                        logs.add(n.asLong());
                        frontier.add(n);
                    } else if (isLeaf(s)) {
                        leafStart.add(n.asLong());
                        q.add(new long[]{n.asLong(), cur[1] + 1});
                    }
                }
            }
            touched.addAll(leafStart);
        }

        // 2. 原木のつながり(26 方向)。建物のしるしがあれば木ではない。
        while (!frontier.isEmpty()) {
            BlockPos p = frontier.poll();
            touched.add(p.asLong());
            if (isStrippedLog(level.getBlockState(p))) {
                return null; // 樹皮を剥いだ原木は自然には生えない = 建物
            }
            for (Direction dir : DIRECTIONS) {
                BlockPos n = p.relative(dir);
                if (loaded(level, n) && isBuildingBlock(level.getBlockState(n))) {
                    return null; // 木材・柵・階段・ドアなどに接している = 建物
                }
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos n = p.offset(dx, dy, dz);
                        if (logs.contains(n.asLong()) || !loaded(level, n)) {
                            continue;
                        }
                        BlockState s = level.getBlockState(n);
                        if (isWood(s) && !Burning.isGiant(s)) {
                            if (logs.size() >= MAX_LOGS || isInsideStructurePiece(level, n)) {
                                touched.addAll(logs);
                                return null; // 建物などの大きな繋がり・ストラクチャーの建物
                            }
                            logs.add(n.asLong());
                            frontier.add(n);
                        }
                    }
                }
            }
        }
        if (!logs.isEmpty() && isInsideStructurePiece(level, BlockPos.of(logs.iterator().nextLong()))) {
            return null;
        }

        // 3. 葉(原木から葉づたいに LEAF_REACH まで)。原木が無ければ当たった葉の塊。
        LongOpenHashSet leaves;
        if (logs.isEmpty()) {
            LongOpenHashSet one = new LongOpenHashSet();
            one.add(hit.asLong());
            leaves = new LongOpenHashSet(one);
            leaves.addAll(leavesNear(level, one, one));
        } else {
            leaves = leavesNear(level, logs, logs);
        }
        touched.addAll(leaves);

        // 4. 後始末では、汚染されていない木は消さない。
        if (requireCorrupted && !anyCorrupted(level, logs) && !anyCorrupted(level, leaves)) {
            return null;
        }

        // 5. 付いた燃える植物(つる・垂れた蔦・根元の草。汚染されていないものも)。
        LongOpenHashSet members = new LongOpenHashSet(logs);
        members.addAll(leaves);
        addAttachedPlants(level, members);
        Found f = new Found();
        f.members = members;
        return f;
    }

    private static boolean anyCorrupted(ServerLevel level, LongOpenHashSet blocks) {
        for (long l : blocks) {
            if (Burning.kind(level.getBlockState(BlockPos.of(l))) != Burning.Kind.NONE) {
                return true;
            }
        }
        return false;
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return LoadedChunks.loaded(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** 燃やしてよいマスか(自分と周り 8 チャンクが読み込み済み)。 */
    private static boolean mayBurn(ServerLevel level, BlockPos pos) {
        return LoadedChunks.neighbourhoodLoaded(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * 地面に埋まった根か(2026-10-08 夜にクライアントで足した。根が燃えて空気になると、根元のまわりに穴が並んだ): 上が地面か、横 4 マスのうち 2 つ以上が地面の原木。
     * 燃え尽きると空気ではなく焼けた土になる。地中の根は細く伸びるので横の 2 つは地面になる。地面に立っている幹の根元は横が空いているので当たらない
     * (「下と横の 1 つが地面」も入れていたが、隣に地脈が 1 つある巨大樹の根元まで焼けた土になったので外した)。
     */
    static boolean inGround(ServerLevel level, BlockPos pos) {
        if (isTerrain(level.getBlockState(pos.above()))) {
            return true;
        }
        int sides = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (isTerrain(level.getBlockState(pos.relative(dir))) && ++sides >= 2) {
                return true;
            }
        }
        return false;
    }

    /** 地面など(固く、木でも火でもないブロック。焼けた土・岩・建物も)か。 */
    private static boolean isTerrain(BlockState state) {
        return state.blocksMotion() && !isTreeBlock(state) && !state.is(BlockTags.FIRE);
    }

    /** 燃えるマスか(木のブロック・燃える薄い植物)。 */
    private static boolean burnable(ServerLevel level, BlockPos pos, BlockState state) {
        return isTreeBlock(state) || Burning.isLightPlant(level, pos, state);
    }

    // ------------------------------------------------------------------ 建物のしるし(SwampTreeFall と同じ)

    /** 生成ストラクチャーのピース(建物 1 棟ぶんの箱)の中か。 */
    private static boolean isInsideStructurePiece(ServerLevel level, BlockPos pos) {
        // 構造物の参照が 1 つも無いチャンクなら答えは「無い」(バニラは参照ごとに起点のチャンクを引きに行くので、燃える木のマスごとだと重い。
        // 2026-10-10、0.1.9 の前の軽量化。バニラも同じチャンクの参照の一覧から始めるので結果は同じ)
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

    /** 覆われたブロック(汚染された建物の一部)は元のブロックとして見る。 */
    private static BlockState coveredToVanilla(BlockState state) {
        return CoveredBlocks.vanillaOf(state.getBlock()) != null ? CoveredBlocks.vanillaState(state) : state;
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

    // ------------------------------------------------------------------ 燃え落ちる途中の木

    /**
     * 地面から離れて燃え落ちる木の塊 1 つ。燃え方は出来事の待ち行列({時刻, マス, 種類})で進める: 火が付く(外側にふつうの火を付け、燃え尽きる時刻を積む)
     * → 燃え尽きる(空気にして、半分の確率でそのマスに火を残す。地面に埋まった根は焼けた土)。
     */
    private static final class Job {
        final ServerLevel level;
        /** 燃え尽きていないマス。 */
        final LongOpenHashSet members;
        final PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        final LongOpenHashSet burning = new LongOpenHashSet();
        /** テスト用: 速さを無視して全部進めている(読み込まれていない所は待たずに飛ばす)。 */
        boolean rush;

        Job(ServerLevel level, LongOpenHashSet members) {
            this.level = level;
            this.members = members;
        }

        /** 全部のマスに、少しずつずらして火を付ける。 */
        void igniteAll(long t) {
            for (long key : members.toLongArray()) {
                queue.add(new long[]{t + level.random.nextInt(IGNITE_STAGGER + 1), key, IGNITE});
            }
        }

        /** 時刻 {@code now} までの出来事を進める。終わったら true。{@code budget[0]} を進めた数だけ減らす。 */
        boolean step(int[] budget, long now) {
            while (!queue.isEmpty() && budget[0] > 0 && queue.peek()[0] <= now) {
                long[] e = queue.poll();
                if (e[2] == IGNITE) {
                    ignite(e[0], e[1]);
                } else {
                    consume(e[0], e[1]);
                }
                budget[0] = TickBudget.spend(budget[0]);
            }
            return queue.isEmpty();
        }

        /** 待つ(読み込まれていない所)。急ぎのテストでは待たずに飛ばす。 */
        private void later(long t, long key, long type) {
            if (rush) {
                burning.remove(key);
                forget(key);
            } else {
                queue.add(new long[]{Math.max(t, level.getGameTime()) + RETRY, key, type}); // 遅れて取り出した出来事を同じ tick に何度も積み直さない(2026-10-10)
            }
        }

        private void forget(long key) {
            members.remove(key);
            claimed(level).remove(key);
        }

        private void ignite(long t, long key) {
            if (burning.contains(key) || !members.contains(key)) {
                return;
            }
            BlockPos pos = BlockPos.of(key);
            if (!mayBurn(level, pos)) {
                later(t, key, IGNITE);
                return;
            }
            BlockState state = level.getBlockState(pos);
            if (!burnable(level, pos, state)) {
                forget(key); // 切られた・ほかの物に変わった・ふつうの火が先に燃やした
                return;
            }
            burning.add(key);
            boolean tree = isTreeBlock(state);
            int burn = tree ? (isWood(state) ? LOG_BURN + level.random.nextInt(LOG_BURN_RANGE + 1)
                    : LEAF_BURN + level.random.nextInt(LEAF_BURN_RANGE + 1))
                    : PLANT_BURN + level.random.nextInt(PLANT_BURN_RANGE + 1);
            queue.add(new long[]{t + burn, key, CONSUME});
            if (tree) {
                attachFlames(pos);
            }
        }

        /**
         * 燃え尽きる: 空気にして、半分の確率でそのマスに火を残す(地面に埋まった根は焼けた土)。
         * ハイヴノードのまわりでは火を残さない({@link HiveFireGuard}。燃え落ちること自体は同じ)。
         */
        private void consume(long t, long key) {
            if (!burning.contains(key)) {
                return;
            }
            BlockPos pos = BlockPos.of(key);
            if (!mayBurn(level, pos)) {
                later(t, key, CONSUME);
                return;
            }
            burning.remove(key);
            forget(key);
            BlockState state = level.getBlockState(pos);
            if (!burnable(level, pos, state)) {
                return;
            }
            if (isWood(state) && inGround(level, pos)) {
                level.setBlock(pos, PurifyRegistry.BURNT_SOIL.get().defaultBlockState(), Block.UPDATE_ALL);
            } else {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                if (level.random.nextBoolean() && !HiveFireGuard.protects(level, pos)) {
                    Burning.ignite(level, pos);
                }
            }
            if (Burning.kind(state) != Burning.Kind.NONE) {
                BurntLand.noteBurnt(level, pos);
            }
        }

        /** 燃えているマスの外側(上 → 横 → 下の順に空いたマス)にふつうの火を付ける。 */
        private void attachFlames(BlockPos pos) {
            Direction[] order = {Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN};
            for (int i = 4; i > 1; i--) { // 横の 4 つだけ順番を混ぜる
                int j = 1 + level.random.nextInt(i);
                Direction tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
            int placed = 0;
            for (Direction dir : order) {
                BlockPos c = pos.relative(dir);
                if (level.getBlockState(c).isAir() && mayBurn(level, c) && Burning.ignite(level, c) && ++placed >= FLAMES) {
                    return;
                }
            }
        }

        /** 終わったときに、燃え落ちる途中の印を外す(燃え残った分 —— 読み込まれずに飛ばしたマスなど)。 */
        void release() {
            LongOpenHashSet claim = claimed(level);
            for (long l : members) {
                claim.remove(l);
            }
        }
    }

    private static void start(Job job) {
        if (running) {
            STARTED.add(job);
        } else {
            JOBS.add(job);
        }
    }

    // ------------------------------------------------------------------ 印

    private static LongOpenHashSet claimed(ServerLevel level) {
        return CLAIMED.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet());
    }

    private static boolean isRejected(ServerLevel level, long key) {
        Long2LongOpenHashMap map = REJECTED.get(level.dimension());
        if (map == null) {
            return false;
        }
        long until = map.get(key);
        if (until == 0L) {
            return false;
        }
        if (until < level.getGameTime()) {
            map.remove(key);
            return false;
        }
        return true;
    }

    private static void reject(ServerLevel level, LongOpenHashSet touched) {
        Long2LongOpenHashMap map = REJECTED.computeIfAbsent(level.dimension(), k -> new Long2LongOpenHashMap());
        if (map.size() > 200_000) {
            map.clear();
        }
        long until = level.getGameTime() + REJECT_TICKS;
        for (long l : touched) {
            map.put(l, until);
        }
    }

    // ------------------------------------------------------------------ 毎 tick

    /** 燃え落ちる途中の木の数(コマンド・テスト用)。 */
    public static int jobCount() {
        return JOBS.size() + STARTED.size();
    }

    /**
     * 燃え落ちる途中の木を 1 tick ぶん進める(テストからも)。進めた出来事の数を返す。{@code rush} なら速さを無視して全部燃え尽きさせる(テスト用)。
     */
    public static int runAll(int perTick, boolean rush) {
        int[] budget = {perTick};
        running = true;
        try {
            CorruptionDropSuppressor.run(() -> {
                Iterator<Job> it = JOBS.iterator();
                while (it.hasNext() && budget[0] > 0) {
                    Job job = it.next();
                    job.rush = rush;
                    if (job.step(budget, rush ? Long.MAX_VALUE : job.level.getGameTime())) {
                        job.release();
                        it.remove();
                    }
                }
            });
        } finally {
            running = false;
            JOBS.addAll(STARTED);
            STARTED.clear();
        }
        return perTick - budget[0];
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty() || event.getServer() instanceof GameTestServer) {
            return; // GameTest は自分で runAll を呼ぶ
        }
        if (!TickBudget.mayStart(PerfJob.TREE_BURN)) {
            return;
        }
        long t0 = TickBudget.begin();
        int done = runAll(PER_TICK, false);
        TickBudget.end(PerfJob.TREE_BURN, t0, done);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        JOBS.clear();
        STARTED.clear();
        CLAIMED.clear();
        REJECTED.clear();
    }

    private TreeBurn() {
    }
}
