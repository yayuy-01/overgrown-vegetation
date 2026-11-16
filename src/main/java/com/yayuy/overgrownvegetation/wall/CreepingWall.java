package com.yayuy.overgrownvegetation.wall;

import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 蠢く壁 1 つ(2026-11-16 試作。正本は Vault「蠢く壁」の決定 その 2〜7)。<b>Mob にもブロックにもしない</b>: サーバーが埋まった升の表を持ち、
 * 当たり・飲み込み・壊す・止まるをここで決める。画面には先頭の升だけを送って描く({@link CreepingWalls}・{@code client/wall})。
 *
 * <h2>広がり方</h2>
 * 始まりの升から、距離の小さい順に升を埋める(ダイクストラ法。決定 その 7 の (b))。隣の 6 面は距離 1、辺で接する斜めの 12 升は √2
 * (斜めは間の 2 升のどちらかが空いているときだけ。壁の角からは漏れない)。時刻 t に届く距離 = 速さ × t なので、
 * <b>どの枝もどの向きの通路も同じ速さ</b>で進み、分岐ではすべての枝へ分かれ、行き止まりも埋まる(決定 その 5)。
 * 距離は 1/32 ブロックの整数({@link #UNIT})。広がりそのものには 1 tick の上限をかけない(かけると枝ごとに速さが変わる)。
 *
 * <ul>
 *     <li>置いたブロック({@link WallCells.Kind#CHEW})は、隣の升が埋まった時から 硬さ × 2 秒 で壊し、その時の距離で続きへ進む。</li>
 *     <li>空が見える升({@link WallCells.Kind#SKY})には入らない。</li>
 *     <li>読み込まれていないチャンクの升は脇に置き、読み込まれたら届くはずだった距離で戻す(遅れた分は次の tick に一度に進む)。</li>
 *     <li>止まるのは時間だけ: {@link WallTuning#baseDurationTicks} + 飲んだ Mob × {@link WallTuning#ticksPerSwallow}。</li>
 * </ul>
 * 保存しない(再起動したら、その場で止まった扱い。決定 その 7)。
 */
public final class CreepingWall {

    /** 距離の単位(1 ブロック = 32)。 */
    public static final int UNIT = 32;
    private static final int COST_FACE = UNIT;
    private static final int COST_EDGE = 45; // 32 × √2 = 45.25
    /** 斜めの 12 方向(dx, dy, dz)。 */
    private static final int[][] EDGES;

    static {
        EDGES = new int[12][];
        int i = 0;
        for (int a = -1; a <= 1; a += 2) {
            for (int b = -1; b <= 1; b += 2) {
                EDGES[i++] = new int[]{a, b, 0};
                EDGES[i++] = new int[]{a, 0, b};
                EDGES[i++] = new int[]{0, a, b};
            }
        }
    }

    private static final Direction[] DIRS = Direction.values();
    /** 斜め i の「間の 2 升」= 面の隣 2 つの向き(Direction の ordinal)。 */
    private static final int[][] EDGE_FACES = new int[12][2];

    static {
        for (int i = 0; i < 12; i++) {
            int[] e = EDGES[i];
            int k = 0;
            if (e[0] != 0) EDGE_FACES[i][k++] = Direction.fromDelta(e[0], 0, 0).ordinal();
            if (e[1] != 0) EDGE_FACES[i][k++] = Direction.fromDelta(0, e[1], 0).ordinal();
            if (e[2] != 0) EDGE_FACES[i][k++] = Direction.fromDelta(0, 0, e[2]).ordinal();
        }
    }

    final int id;
    final ServerLevel level;
    final BlockPos origin;
    final long startTick;
    long stopTick;
    int swallowed;
    boolean stopped;
    long stoppedAt;

    /** 埋まった升。 */
    final LongOpenHashSet filled = new LongOpenHashSet();
    /** まだ埋まっていない升の、今わかっている最短の距離。 */
    private final Long2IntOpenHashMap tentative = new Long2IntOpenHashMap();
    /** 距離ごとの待ち行列(古い値は {@link #tentative} と比べて読み捨てる)。 */
    private final Int2ObjectRBTreeMap<LongArrayList> queue = new Int2ObjectRBTreeMap<>();
    /** 壊している置いたブロック → 壊れる tick。 */
    final Long2LongOpenHashMap chewing = new Long2LongOpenHashMap();
    /** 読み込まれていないチャンクで止まった升 → 届くはずだった距離。 */
    private final Long2IntOpenHashMap parked = new Long2IntOpenHashMap();
    /** 先頭の升(まだ埋まっていない隣がありうる升)。送り直しと止まった所の塊に使う。 */
    final LongOpenHashSet front = new LongOpenHashSet();
    /** 先頭の整理の順番(少しずつ見る)。 */
    private LongArrayList frontList = new LongArrayList();
    private int frontCursor;
    private int settledThisTick;
    /** 前に送ってから埋まった升。 */
    final LongArrayList newlySettled = new LongArrayList();
    /** 列 → 屋根の y(空の判定)。 */
    private final Long2IntOpenHashMap roofCache = new Long2IntOpenHashMap();
    /** 跡の待ち行列(升と、置いてよくなる tick)。 */
    private final LongArrayFIFOQueue trailPos = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue trailTime = new LongArrayFIFOQueue();
    private final LongOpenHashSet trailSeen = new LongOpenHashSet();
    /** 止まった所の塊を置く升。 */
    private final LongArrayFIFOQueue lumpQueue = new LongArrayFIFOQueue();

    int minX, minY, minZ, maxX, maxY, maxZ;
    /** 測った値(ns)。 */
    long lastFillNanos, maxFillNanos, totalFillNanos, fillTicks;
    long trailPlaced;
    int maxFilled;

    CreepingWall(int id, ServerLevel level, BlockPos origin, long now) {
        this.id = id;
        this.level = level;
        this.origin = origin.immutable();
        this.startTick = now;
        this.stopTick = now + WallTuning.baseDurationTicks;
        tentative.defaultReturnValue(Integer.MAX_VALUE);
        parked.defaultReturnValue(Integer.MAX_VALUE);
        roofCache.defaultReturnValue(Integer.MIN_VALUE);
        minX = maxX = origin.getX();
        minY = maxY = origin.getY();
        minZ = maxZ = origin.getZ();
        push(origin.asLong(), 0);
    }

    public int id() {
        return id;
    }

    public BlockPos origin() {
        return origin;
    }

    public boolean isStopped() {
        return stopped;
    }

    public int filledCount() {
        return filled.size();
    }

    public boolean isFilled(BlockPos pos) {
        return filled.contains(pos.asLong());
    }

    public int swallowed() {
        return swallowed;
    }

    public long stopTick() {
        return stopTick;
    }

    public long startTick() {
        return startTick;
    }

    /** テスト用: 止まる時刻を決める(飲んだ分はこの後も足される)。 */
    public void setStopTickForTests(long tick) {
        this.stopTick = tick;
    }

    public boolean isChewing(BlockPos pos) {
        return chewing.containsKey(pos.asLong());
    }

    /** 今の時刻に届いている距離(1/32 ブロック)。 */
    int reach(long now) {
        double t = Math.max(0L, Math.min(now, stopTick) - startTick);
        return (int) Math.floor(t * WallTuning.speedBlocksPerSecond / 20.0D * UNIT);
    }

    /** 真上に空が見える升か(列ごとに屋根の y を覚える)。 */
    boolean isSky(BlockPos pos) {
        long col = ((long) pos.getX() << 32) | (pos.getZ() & 0xFFFFFFFFL);
        int roof = roofCache.get(col);
        if (roof == Integer.MIN_VALUE) {
            roof = WallCells.roofY(level, pos.getX(), pos.getZ());
            roofCache.put(col, roof);
        }
        return pos.getY() > roof;
    }

    /** 列の覚えを捨てる(壁が置いたブロックを壊したとき)。 */
    private void forgetRoof(BlockPos pos) {
        roofCache.remove(((long) pos.getX() << 32) | (pos.getZ() & 0xFFFFFFFFL));
    }

    void swallowOne() {
        swallowed++;
        if (!stopped) {
            stopTick += WallTuning.ticksPerSwallow;
        }
    }

    private void push(long pos, int dist) {
        if (dist < tentative.get(pos)) {
            tentative.put(pos, dist);
            queue.computeIfAbsent(dist, k -> new LongArrayList()).add(pos);
        }
    }

    /** 1 tick 進める。止まったら true を返す最初の tick で {@link #stopped} になる。 */
    void tick(long now) {
        if (stopped) {
            return;
        }
        long t0 = System.nanoTime();
        int reach = reach(now);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        // 壊している置いたブロック
        if (!chewing.isEmpty()) {
            LongArrayList done = null;
            for (Long2LongOpenHashMap.Entry e : chewing.long2LongEntrySet()) {
                if (e.getLongValue() <= now) {
                    (done == null ? done = new LongArrayList() : done).add(e.getLongKey());
                }
            }
            if (done != null) {
                for (int i = 0; i < done.size(); i++) {
                    long p = done.getLong(i);
                    chewing.remove(p);
                    m.set(p);
                    if (!level.isLoaded(m)) {
                        parked.put(p, Math.min(parked.get(p), reach));
                        continue;
                    }
                    BlockState s = level.getBlockState(m);
                    WallCells.Kind k = WallCells.kindOf(level, m, s);
                    if (k == WallCells.Kind.CHEW) {
                        // 壊したブロックも、支えを失って外れた物(レール・松明・鎖)も落とさない(飲まれる。計測で落ちたアイテムが 1 tick の 2 割を食った)
                        com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor.run(() -> level.destroyBlock(m, false));
                        forgetRoof(m);
                        k = WallCells.kindOf(level, m, level.getBlockState(m));
                    }
                    if (k == WallCells.Kind.PASS && !isSky(m)) {
                        push(p, reach);
                    }
                }
            }
        }

        // 読み込まれたチャンクの升を戻す(1 秒ごと)
        if (!parked.isEmpty() && (now - startTick) % 20 == 0) {
            LongArrayList back = new LongArrayList();
            for (Long2IntOpenHashMap.Entry e : parked.long2IntEntrySet()) {
                if (level.isLoaded(m.set(e.getLongKey()))) {
                    back.add(e.getLongKey());
                }
            }
            for (int i = 0; i < back.size(); i++) {
                long p = back.getLong(i);
                int d = parked.remove(p);
                if (!filled.contains(p)) {
                    m.set(p);
                    WallCells.Kind k = WallCells.classify(level, m, this);
                    if (k == WallCells.Kind.PASS) {
                        push(p, d);
                    } else if (k == WallCells.Kind.CHEW && !chewing.containsKey(p)) {
                        chewing.put(p, now + chewTicks(m));
                    }
                }
            }
        }

        // 届いた距離までの升を埋める
        while (!queue.isEmpty()) {
            int key = queue.firstIntKey();
            if (key > reach) {
                break;
            }
            LongArrayList bucket = queue.remove(key);
            for (int i = 0; i < bucket.size(); i++) {
                long p = bucket.getLong(i);
                if (tentative.get(p) != key || filled.contains(p)) {
                    continue;
                }
                tentative.remove(p);
                settle(p, key, now, m);
            }
        }
        maxFilled = Math.max(maxFilled, filled.size());
        pruneFrontStep(2000);

        long dt = System.nanoTime() - t0;
        if (dt > 100_000_000L) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[creeping_wall] #{} 重い tick {} ms: 埋めた {} 升・壊し中 {}・脇の升 {}・先頭 {}・待ち行列の距離の数 {}",
                    id, dt / 1_000_000, settledThisTick, chewing.size(), parked.size(), front.size(), queue.size());
        }
        settledThisTick = 0;
        lastFillNanos = dt;
        maxFillNanos = Math.max(maxFillNanos, dt);
        totalFillNanos += dt;
        fillTicks++;

        if (now >= stopTick) {
            stop(now);
        }
    }

    private int chewTicks(BlockPos pos) {
        float h = level.getBlockState(pos).getDestroySpeed(level, pos);
        return Math.max(1, (int) Math.round(h * WallTuning.chewTicksPerHardness));
    }

    /** 升 {@code p} を埋める(距離 {@code d})。 */
    private void settle(long p, int d, long now, BlockPos.MutableBlockPos m) {
        m.set(p);
        BlockPos pos = m.immutable();
        // 着く直前に置かれたブロック・空になった所を見直す
        WallCells.Kind kind = WallCells.classify(level, pos, this);
        if (kind == WallCells.Kind.CHEW) {
            if (!chewing.containsKey(p)) {
                chewing.put(p, now + chewTicks(pos));
            }
            return;
        }
        if (kind != WallCells.Kind.PASS) {
            return;
        }
        filled.add(p);
        front.add(p);
        frontList.add(p);
        settledThisTick++;
        newlySettled.add(p);
        minX = Math.min(minX, pos.getX());
        minY = Math.min(minY, pos.getY());
        minZ = Math.min(minZ, pos.getZ());
        maxX = Math.max(maxX, pos.getX());
        maxY = Math.max(maxY, pos.getY());
        maxZ = Math.max(maxZ, pos.getZ());
        WallSporeTrail.mark(level, pos, now);

        BlockState state = level.getBlockState(pos);
        if (WallCells.isLight(state)) {
            // 灯りは飲まれて消える(落とさない。決定 その 3)
            // 周りへの更新は出さない(連鎖して重くなり、支えを失った物が落ちる)。見た目と光だけ変わる
            BlockState out = state.getFluidState().createLegacyBlock();
            com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor.run(
                    () -> level.setBlock(pos, out, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
            state = level.getBlockState(pos);
        }
        if (!state.isAir() && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) {
            queueTrail(p, now); // レール・チェストなど、この升の中の物を覆う
        }

        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        // 面の隣が空いているか(斜めの「間の升」の判定に使い回す。classify を何度も呼ばない)
        boolean[] faceOpen = new boolean[6];
        for (Direction dir : DIRS) {
            n.setWithOffset(pos, dir);
            long np = n.asLong();
            if (filled.contains(np)) {
                faceOpen[dir.ordinal()] = true;
                continue;
            }
            if (!level.isLoaded(n)) {
                parked.put(np, Math.min(parked.get(np), d + COST_FACE));
                continue;
            }
            WallCells.Kind k = WallCells.classify(level, n, this);
            faceOpen[dir.ordinal()] = k == WallCells.Kind.PASS;
            switch (k) {
                case PASS -> push(np, d + COST_FACE);
                case CHEW -> {
                    if (!chewing.containsKey(np)) {
                        chewing.put(np, now + chewTicks(n));
                    }
                }
                case WALL -> queueTrail(np, now);
                case SKY -> {
                }
            }
        }
        for (int i = 0; i < EDGES.length; i++) {
            int[] e = EDGES[i];
            // 間の 2 升(片方の軸だけ動かした升 = 面の隣)のどちらかが空いているときだけ
            if (!faceOpen[EDGE_FACES[i][0]] && !faceOpen[EDGE_FACES[i][1]]) {
                continue;
            }
            n.set(pos.getX() + e[0], pos.getY() + e[1], pos.getZ() + e[2]);
            long np = n.asLong();
            if (filled.contains(np) || !level.isLoaded(n)) {
                continue;
            }
            if (WallCells.classify(level, n, this) == WallCells.Kind.PASS) {
                push(np, d + COST_EDGE);
            }
        }
    }

    private void queueTrail(long p, long now) {
        if (trailSeen.add(p)) {
            trailPos.enqueue(p);
            trailTime.enqueue(now + WallTuning.trailDelayTicks);
        }
    }

    /** 止める(時間切れ・コマンド)。先頭の升を、止まった所の塊にする予定に入れる。 */
    void stop(long now) {
        if (stopped) {
            return;
        }
        stopped = true;
        stoppedAt = now;
        stopTick = Math.min(stopTick, now);
        pruneFront();
        for (LongIterator it = front.iterator(); it.hasNext(); ) {
            lumpQueue.enqueue(it.nextLong());
        }
        queue.clear();
        tentative.clear();
        parked.clear();
        chewing.clear();
    }

    /** 先頭の升のうち、隣が全部 埋まった・天然の壁 になったものを外す。 */
    /** 先頭の整理を少しずつ(1 tick に budget 升。一度に全部見ると数十万升の洞窟で 100 ms を超えた)。 */
    private void pruneFrontStep(int budget) {
        int end = Math.min(frontList.size(), frontCursor + budget);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (; frontCursor < end; frontCursor++) {
            long p = frontList.getLong(frontCursor);
            if (front.contains(p) && enclosed(m.set(p))) {
                front.remove(p);
            }
        }
        if (frontCursor >= frontList.size()) {
            frontList = new LongArrayList(front);
            frontCursor = 0;
        }
    }

    /** 隣が全部 埋まった・天然の壁(読み込まれていない隣は開いている扱い)。 */
    private boolean enclosed(BlockPos m) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction dir : DIRS) {
            n.setWithOffset(m, dir);
            if (filled.contains(n.asLong())) {
                continue;
            }
            if (!level.isLoaded(n) || WallCells.classify(level, n, this) != WallCells.Kind.WALL) {
                return false;
            }
        }
        return true;
    }

    void pruneFront() {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (LongIterator it = front.iterator(); it.hasNext(); ) {
            long p = it.nextLong();
            m.set(p);
            boolean open = false;
            for (Direction dir : DIRS) {
                n.setWithOffset(m, dir);
                long np = n.asLong();
                if (filled.contains(np)) {
                    continue;
                }
                if (!level.isLoaded(n) || WallCells.classify(level, n, this) != WallCells.Kind.WALL) {
                    open = true;
                    break;
                }
            }
            if (!open) {
                it.remove();
            }
        }
    }

    /** 跡を置く(全部の壁で 1 tick に {@code budget} まで)。置いた数を返す。 */
    int placeTrail(long now, int budget) {
        int used = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // 止まった所の塊が先(洞窟の中に根を張る)
        while (used < budget && !lumpQueue.isEmpty()) {
            long p = lumpQueue.dequeueLong();
            m.set(p);
            used++;
            if (!level.isLoaded(m)) {
                continue;
            }
            BlockState s = level.getBlockState(m);
            if ((s.isAir() || s.canBeReplaced()) && !s.hasBlockEntity()) {
                level.setBlock(m, com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_DIRT.get().defaultBlockState(), Block.UPDATE_ALL);
                trailPlaced++;
            }
        }
        while (used < budget && !trailPos.isEmpty()) {
            if (trailTime.firstLong() > now && !stopped) {
                break;
            }
            long p = trailPos.dequeueLong();
            trailTime.dequeueLong();
            m.set(p);
            used++;
            long c0 = System.nanoTime();
            boolean placed = level.isLoaded(m) && CorruptionSpreader.corruptSingle(level, m.immutable());
            long cd = System.nanoTime() - c0;
            if (cd > 50_000_000L) {
                com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[creeping_wall] 重い跡 {} ms: {} {}", cd / 1_000_000, m.toShortString(), level.getBlockState(m));
            }
            if (placed) {
                trailPlaced++;
            }
        }
        return used;
    }

    /** 止まっていて、跡も置き終わった。 */
    boolean isFinished() {
        return stopped && trailPos.isEmpty() && lumpQueue.isEmpty();
    }

    int trailPending() {
        return trailPos.size() + lumpQueue.size();
    }
}
