package com.yayuy.overgrownvegetation.corruption.fissure;

import com.yayuy.overgrownvegetation.corruption.pushback.RingWards;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * 地下の台帳と前線(2026-11-20 追記 2・ユーザー決定)。地上の過繁茂の台帳と同じく 1 チャンク 1 マスで、亀裂の前線が届いたマスにだけ
 * 「届いた時刻」を持つ。前線は読み込まれていない所でも時刻で進み、チャンクが読み込まれたら本物の洞窟を後から汚染する。
 *
 * <ul>
 *     <li><b>速さ</b>: 洞窟があると分かっているチャンク(読み込んで測った所)へは {@code caveChunkTicks}、洞窟が無い・まだ分からない
 *     チャンク(根が岩を掘る)へは {@code rockChunkTicks}。岩へ進めるのは、根を送り込む器官が掘っている間だけ。</li>
 *     <li><b>閉じた洞窟</b>: 洞窟づたいに広がり切って(速く進める隣が無い)口にも届いていなければ、最後に届いてから {@code borerDelayTicks} 後に
 *     根を送り込む器官ができる。器官のマスから岩へ遅く進み、洞窟のあるチャンクに当たったら役目を終え、そこからまた洞窟づたいに速く進む。
 *     器官は読み込まれた所で形になり、芯を壊すと止まる。</li>
 *     <li><b>出口</b>: 口のあるチャンク(測ったときに洞窟から地上まで空気がつながっていた所)に届いたら、その口から地上へあふれる。</li>
 *     <li><b>軽く</b>: 地表が過繁茂のチャンクには進まず、載っていたマスは消す(菌糸の染み下りに任せる)。台帳は前線が届いたマスだけ。
 *     前線は {@code intervalTicks} ごとに計算する。</li>
 * </ul>
 * 巣の芯が壊れたら、壊れた時刻までに届く分で止まる(読み込んだときの追いつきも壊れた時刻まで)。
 */
public final class FissureLedger {
    private FissureLedger() {
    }

    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** 前線を計算する時刻(亀裂が死んでいれば死んだ時刻まで)。 */
    static long effectiveNow(FissureData.Fissure f, long now) {
        return f.state == FissureData.State.DEAD && f.diedAt >= 0 ? Math.min(now, f.diedAt) : now;
    }

    /** 巣のチャンク。 */
    static long nestChunk(FissureData.Fissure f) {
        BlockPos p = f.floor != null ? f.floor : new BlockPos(f.x, 0, f.z);
        return ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
    }

    // ---------------------------------------------------------------- 前線

    /** 前線を時刻 now まで進める。足したマスの数を返す。読み込まれていないチャンクは見ない(見立ての控えで決める)。 */
    public static int update(ServerLevel level, FissureData data, FissureData.Fissure f, long now) {
        if (f.state == FissureData.State.PENDING || f.floor == null) return 0;
        FissureTable t = DesignData.fissure();
        FissureTable.Ledger lt = t.ledger;
        long eff = effectiveNow(f, now);
        PhaseData pd = PhaseData.get(level);
        long nest = nestChunk(f);
        // 地表が過繁茂になったマスは消す(菌糸の染み下りに任せる。亀裂そのもの・芯・あふれは止めない)
        for (long k : f.cells.toLongArray()) if (pd.isOvergrownKey(k)) data.removeCell(k);
        int added = 0;
        if (f.cells.isEmpty() && !data.ledger().containsKey(nest) && !pd.isOvergrownKey(nest)) {
            long at = f.openedAt + (long) (t.nest.rootDelayDays * 24000.0D);
            if (at > eff) return 0;
            addCell(level, data, f, nest, at, false, -1);
            added++;
        }
        int nx = ChunkPos.getX(nest), nz = ChunkPos.getZ(nest);
        // 何も変わっていなければ、次に何か起きる時刻まで計算しない(11-20 測り: 毎回マスを全部見直して 1 回 200 ms を超えた)
        long[] memo = NEXT.get(f.id);
        if (memo != null && eff < memo[0] && memo[1] == data.knownVersion() && memo[2] == f.borers.size() && memo[3] == f.cells.size()) return added;
        // 到着の早い順(同じ時刻なら巣から遠い方 → ハッシュ)の待ち行列で進める
        java.util.PriorityQueue<long[]> pq = new java.util.PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(b[1], a[1]));
        for (int i = 0; i < f.cells.size(); i++) push(level, data, pd, f, pq, f.cells.getLong(i), nx, nz, lt);
        while (added < lt.addsPerUpdate && f.cells.size() < lt.maxCells && !pq.isEmpty()) {
            long[] e = pq.peek();
            if (e[0] > eff) break;
            pq.poll();
            long key = e[2], parent = e[3];
            boolean fast = e[4] == 1;
            int borer = (int) e[5];
            if (data.ledger().containsKey(key) || pd.isOvergrownKey(key)) continue;
            if (!fast) {
                // 岩の候補は、その器官がまだ掘っていて、根の先が出した物だけ(先が動いたら古い候補は捨てる)
                if (borer < 0 || borer >= f.borers.size() || !f.borers.get(borer).digging() || f.borers.get(borer).tip != parent) continue;
                addCell(level, data, f, key, e[0], true, borer);
                f.borers.get(borer).tip = key;
            } else {
                // 岩を掘ってきた根が洞窟に当たった: その器官は役目を終える(この先は洞窟づたいに速く)
                if (borer >= 0 && borer < f.borers.size()) f.borers.get(borer).done = true;
                addCell(level, data, f, key, e[0], false, -1);
            }
            added++;
            push(level, data, pd, f, pq, key, nx, nz, lt);
        }
        // 残った候補: 速く進める隣があるか・次に届く時刻
        boolean fastPending = false;
        long nextAt = Long.MAX_VALUE;
        for (long[] e : pq) {
            if (data.ledger().containsKey(e[2])) continue;
            if (e[4] == 1) fastPending = true;
            else {
                int b = (int) e[5];
                if (b < 0 || b >= f.borers.size() || !f.borers.get(b).digging() || f.borers.get(b).tip != e[3]) continue;
            }
            nextAt = Math.min(nextAt, e[0]);
        }
        // 閉じた洞窟: 洞窟づたいに広がり切って、口にも届いていなければ、根を送り込む器官
        if (f.state == FissureData.State.OPEN && f.outletMouth == null && !fastPending && f.borers.size() < lt.maxBorers && !anyDigging(f)) {
            long last = Long.MIN_VALUE, pick = Long.MIN_VALUE;
            for (int i = 0; i < f.cells.size(); i++) {
                long k = f.cells.getLong(i);
                FissureData.Cell c = data.ledger().get(k);
                if (c == null) continue;
                last = Math.max(last, c.reachedAt);
                if (hasFreeNeighbour(data, pd, k, nx, nz, lt) && (pick == Long.MIN_VALUE || c.reachedAt >= data.ledger().get(pick).reachedAt)) pick = k;
            }
            long at = last == Long.MIN_VALUE ? Long.MIN_VALUE : last + lt.borerDelayTicks;
            if (pick != Long.MIN_VALUE && at > eff) nextAt = Math.min(nextAt, at);
            if (pick != Long.MIN_VALUE && at <= eff) {
                f.borers.add(new FissureData.Borer(pick, at));
                com.yayuy.overgrownvegetation.organ.OrganNotices.fissure(level, f.id, ChunkPos.getX(pick) * 16 + 8, ChunkPos.getZ(pick) * 16 + 8, true); // 開発用の知らせ(11-20)
                data.ledger().get(pick).borer = f.borers.size() - 1;
                data.changed();
                ForestDirector.get(level).note(String.format(Locale.ROOT, "亀裂 #%d: 閉じた洞窟から根を送り込む器官ができた(チャンク %d, %d)", f.id,
                        ChunkPos.getX(pick), ChunkPos.getZ(pick)));
                return added + update(level, data, f, now); // 岩へ掘る分を同じ回で(控えはその中で作る)
            }
        }
        NEXT.put(f.id, new long[]{nextAt, data.knownVersion(), f.borers.size(), f.cells.size()});
        return added;
    }

    /** 次の計算を飛ばしてよい時刻の控え(亀裂の番号 → [時刻, 見立ての版, 器官の数, マスの数])。保存しない。 */
    private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<long[]> NEXT = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

    /** マス k の隣を候補に積む([到着, 点, 鍵, 親, 速い 1/岩 0, 器官])。 */
    private static void push(ServerLevel level, FissureData data, PhaseData pd, FissureData.Fissure f, java.util.PriorityQueue<long[]> pq, long k, int nx, int nz,
                             FissureTable.Ledger lt) {
        FissureData.Cell c = data.ledger().get(k);
        if (c == null) return;
        int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
        for (int[] d : NEIGHBOURS) {
            int ox = cx + d[0], oz = cz + d[1];
            if (Math.max(Math.abs(ox - nx), Math.abs(oz - nz)) > lt.maxRadiusChunks) continue;
            long n = ChunkPos.asLong(ox, oz);
            if (data.ledger().containsKey(n) || pd.isOvergrownKey(n)) continue;
            byte kind = kindOf(level, data, n);
            boolean fast = kind == FissureData.K_CAVE || kind == FissureData.K_MOUTH;
            long at;
            int borer;
            if (fast) {
                at = c.reachedAt + lt.caveChunkTicks;
                borer = c.borer;
            } else {
                // 岩へは、掘っている器官の根の先からだけ(根が 1 本ずつ地中へ伸びる)
                if (c.borer < 0 || c.borer >= f.borers.size() || !f.borers.get(c.borer).digging() || f.borers.get(c.borer).tip != k) continue;
                at = Math.max(c.reachedAt, f.borers.get(c.borer).createdAt) + lt.rockChunkTicks;
                borer = c.borer;
            }
            // 同じ時刻なら巣から遠い方へ(根は巣から離れる向きに伸びる)。それも同じならチャンクのハッシュ
            long dx = ox - nx, dz = oz - nz;
            long score = (dx * dx + dz * dz) * 1024L + (it.unimi.dsi.fastutil.HashCommon.mix(n ^ f.seed) & 1023L);
            pq.add(new long[]{at, score, n, k, fast ? 1L : 0L, borer});
        }
    }

    /** 掘っている器官があるか。芯を壊された器官があれば、その亀裂には新しい器官を作らない(閉じた洞窟は封じられたまま。【仮】)ので true。 */
    private static boolean anyDigging(FissureData.Fissure f) {
        for (FissureData.Borer b : f.borers) if (b.digging() || !b.alive) return true;
        return false;
    }

    private static boolean hasFreeNeighbour(FissureData data, PhaseData pd, long k, int nx, int nz, FissureTable.Ledger lt) {
        int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
        for (int[] d : NEIGHBOURS) {
            int ox = cx + d[0], oz = cz + d[1];
            if (Math.max(Math.abs(ox - nx), Math.abs(oz - nz)) > lt.maxRadiusChunks) continue;
            long n = ChunkPos.asLong(ox, oz);
            if (!data.ledger().containsKey(n) && !pd.isOvergrownKey(n)) return true;
        }
        return false;
    }

    private static void addCell(ServerLevel level, FissureData data, FissureData.Fissure f, long key, long at, boolean rock, int borer) {
        data.addCell(key, new FissureData.Cell(f.id, at, rock, borer));
        if (f.outletMouth == null && data.known(key) == FissureData.K_MOUTH) {
            BlockPos m = data.mouthOf(key);
            if (m != null) setOutlet(level, data, f, m, at);
        }
    }

    /** 出口を決める(地上へあふれ始める時刻 = 前線が口のチャンクに届いた時刻)。 */
    static void setOutlet(ServerLevel level, FissureData data, FissureData.Fissure f, BlockPos mouth, long at) {
        f.outletMouth = mouth.immutable();
        f.mouthAt = at;
        f.armDone = new int[Math.max(0, DesignData.fissure().mouth.arms)];
        data.changed();
        ForestDirector.get(level).note(String.format(Locale.ROOT, "亀裂 #%d の汚染が洞窟の口に届いた(%s)", f.id, mouth.toShortString()));
    }

    // ---------------------------------------------------------------- 見立て(洞窟があるか・口があるか)

    /** チャンクの見立て。読み込まれていてまだ測っていなければ測る列に入れる(測るまでは「分からない」= 岩と同じ)。 */
    static byte kindOf(ServerLevel level, FissureData data, long chunk) {
        byte k = data.known(chunk);
        if (k == 0 && level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null) MEASURE_QUEUE.add(chunk);
        return k;
    }

    /** 測る列(保存しない)。 */
    static final LongLinkedOpenHashSet MEASURE_QUEUE = new LongLinkedOpenHashSet();
    @Nullable
    private static Measure measuring;

    private static final class Measure {
        final long chunk;
        /** 床の候補(地面から下の、真下が固い空気のマス。2 列おき)。 */
        final long[] cands;
        int next;
        @Nullable
        FissureWorld.RouteSearch search;

        Measure(long chunk, long[] cands) {
            this.chunk = chunk;
            this.cands = cands;
        }
    }

    /** 床の候補のうち空気の多さを見る数の上限。 */
    private static final int MEASURE_TRIES = 16;

    /**
     * 測る仕事を 1 歩(毎 tick)。段に分ける(11-20 測り: まとめると 1 tick 57〜130 ms): 候補を集める tick → 候補を 2 つずつ空気の多さで見る tick →
     * 地表への道を {@code searchPerTick} ずつ探す tick。こなした数を返す。
     */
    static int measureTick(ServerLevel level, FissureData data) {
        FissureTable t = DesignData.fissure();
        Measure m = measuring;
        if (m == null) {
            while (!MEASURE_QUEUE.isEmpty()) {
                long k = MEASURE_QUEUE.removeFirstLong();
                if (data.known(k) != 0) continue;
                int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
                if (!FissureWorld.neighbourhoodReady(level, cx, cz)) continue; // 読み込み直したらまた入る
                long[] cands = FissureWorld.caveCandidates(level, cx, cz, (int) k, false, 2);
                if (cands.length == 0) {
                    data.setKnown(k, FissureData.K_NONE, null);
                    return 1;
                }
                measuring = new Measure(k, cands);
                return 1;
            }
            return 0;
        }
        int cx = ChunkPos.getX(m.chunk), cz = ChunkPos.getZ(m.chunk);
        if (!FissureWorld.neighbourhoodReady(level, cx, cz)) { // 測っている間に外れた: 捨てる(読み込み直したらまた入る)
            measuring = null;
            return 0;
        }
        if (m.search == null) {
            for (int i = 0; i < 2 && m.search == null; i++) {
                if (m.next >= Math.min(m.cands.length, MEASURE_TRIES)) {
                    measuring = null;
                    data.setKnown(m.chunk, FissureData.K_NONE, null); // 狭いすき間だけ: 洞窟は無いと見る
                    return 1;
                }
                BlockPos p = BlockPos.of(m.cands[m.next++]);
                if (FissureWorld.goodFloor(level, p)) m.search = new FissureWorld.RouteSearch(level, p);
            }
            return 1;
        }
        if (!m.search.step(level, Math.max(1, t.roots.searchPerTick))) return 1;
        measuring = null;
        finishMeasure(data, m.chunk, m.search);
        return 1;
    }

    /** その場で測る(コマンド・テスト)。 */
    public static byte measureNow(ServerLevel level, FissureData data, long chunk) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        if (level.getChunkSource().getChunkNow(cx, cz) == null) return 0;
        LongArrayList floors = FissureWorld.caveFloors(level, cx, cz, (int) chunk, false, 1, 16);
        if (floors.isEmpty()) {
            data.setKnown(chunk, FissureData.K_NONE, null);
            return FissureData.K_NONE;
        }
        FissureWorld.RouteSearch s = new FissureWorld.RouteSearch(level, BlockPos.of(floors.getLong(0)));
        while (!s.step(level, Integer.MAX_VALUE)) {
        }
        finishMeasure(data, chunk, s);
        return data.known(chunk);
    }

    /**
     * 測り終えた: 洞窟の床から地表への道が口に届いたら、道が通るチャンクを「洞窟」、口のチャンクを「口」(口のマス付き)にする。
     * 届かなければこのチャンクは「洞窟」。口は洞窟の空気から実際につながっている所だけ(崖の横穴・掘った階段だけの所は数えない)。
     */
    static void finishMeasure(FissureData data, long chunk, FissureWorld.RouteSearch s) {
        FissureData.Fissure tmp = new FissureData.Fissure(0, 0, 0, 0, 0L);
        s.applyTo(tmp);
        markRoute(data, tmp.route, tmp.routeHitsMouth);
        if (data.known(chunk) == 0) data.setKnown(chunk, FissureData.K_CAVE, null);
    }

    /** 根の道(洞窟の空気)が通るチャンクを「洞窟」に、口に届いていれば最後のチャンクを「口」にする。 */
    static void markRoute(FissureData data, @Nullable long[] route, boolean hitsMouth) {
        if (route == null || route.length == 0) return;
        for (long p : route) {
            long k = ChunkPos.asLong(BlockPos.getX(p) >> 4, BlockPos.getZ(p) >> 4);
            if (data.known(k) != FissureData.K_MOUTH) data.setKnown(k, FissureData.K_CAVE, null);
        }
        if (hitsMouth) {
            BlockPos m = BlockPos.of(route[route.length - 1]);
            data.setKnown(ChunkPos.asLong(m.getX() >> 4, m.getZ() >> 4), FissureData.K_MOUTH, m);
        }
    }

    // ---------------------------------------------------------------- 読み込んだときの追いつき

    /** 回し始める位置(台帳を毎 tick 頭から舐めない)。 */
    private static int applyCursor;

    /**
     * 読み込まれたチャンクの本物の洞窟を、届いてからの時間の割合だけ汚染する(列を順に。1 tick に {@code columnsPerTick} 列まで)。
     * 列は地表から {@code surfaceKeep} より下の、空気に面したブロックだけ(地上は割らない・汚さない)。こなした列の数を返す。
     */
    static int applyTick(ServerLevel level, FissureData data, long now) {
        FissureTable t = DesignData.fissure();
        int budget = Math.max(1, t.ledger.columnsPerTick);
        int done = 0;
        long[] keys = data.ledger().keySet().toLongArray();
        if (keys.length == 0) return 0;
        RandomSource random = level.getRandom();
        long deadline = System.nanoTime() + (long) (t.ledger.applyMillis * 1_000_000.0D);
        BlockPos.MutableBlockPos pm = new BlockPos.MutableBlockPos();
        while (!PLANTS.isEmpty() && System.nanoTime() < deadline) {
            pm.set(PLANTS.dequeueLong());
            if (level.getChunkSource().getChunkNow(pm.getX() >> 4, pm.getZ() >> 4) == null) continue;
            BlockState s = level.getBlockState(pm);
            if (s.isAir()) continue;
            FissureWorld.growPlants(level, pm.immutable(), s);
        }
        for (int i = 0; i < keys.length && done < budget && System.nanoTime() < deadline; i++) {
            long k = keys[(applyCursor + i) % keys.length];
            FissureData.Cell c = data.ledger().get(k);
            if (c == null || c.applied >= 256) continue;
            FissureData.Fissure f = data.byId(c.fissure);
            if (f == null) continue;
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            if (!FissureWorld.neighbourhoodReady(level, cx, cz)) continue;
            long eff = effectiveNow(f, now);
            int target = (int) Math.min(256L, Math.max(0L, (eff - c.reachedAt) * 256L / Math.max(1, t.ledger.fillTicks)));
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            while (c.applied < target && done < budget && (done == 0 || System.nanoTime() < deadline)) {
                int col = (c.applied * 167) & 255; // 167 は奇数なので 256 列を 1 回ずつ
                int rest = applyColumn(level, chunk, (cx << 4) + (col & 15), (cz << 4) + (col >> 4), t, random, c.resumeY, deadline);
                if (rest != Integer.MIN_VALUE) { // 時間切れ: 次の tick にこの列の続きから
                    c.resumeY = rest;
                    done = budget;
                    break;
                }
                c.resumeY = Integer.MAX_VALUE;
                c.applied++;
                done++;
            }
            data.changed();
            if (done >= budget) applyCursor = (applyCursor + i) % keys.length;
        }
        return done;
    }

    /** 追いつきで汚染したマスに生やす植物の待ち行列(保存しない)。 */
    private static final it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue PLANTS = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();

    /** 1 列を上から汚染する。{@code fromY} より上は済み。時間切れなら続きの高さ、終われば MIN_VALUE。 */
    static int applyColumn(ServerLevel level, LevelChunk chunk, int x, int z, FissureTable t, RandomSource random, int fromY, long deadline) {
        int top = com.yayuy.overgrownvegetation.spore.field.CaveMouths.groundTop(chunk, x, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15), z,
                level.getMinBuildHeight());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int n = 0;
        for (int y = Math.min(fromY, top - t.ledger.surfaceKeep); y > level.getMinBuildHeight(); y--) {
            if ((++n & 15) == 0 && System.nanoTime() >= deadline) return y;
            m.set(x, y, z);
            BlockState s = chunk.getBlockState(m);
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;
            if (!facesAirInChunk(level, m)) continue;
            BlockPos at = m.immutable();
            // 植物は待ち行列へ(1 列に大きな植物が重なると 1 tick が重くなる。11-20 測り 15 ms)
            if (FissureWorld.corruptOne(level, at, s, random, 0.0D) && random.nextDouble() < t.roots.plantShare && PLANTS.size() < 512) PLANTS.enqueue(at.asLong());
        }
        return Integer.MIN_VALUE;
    }

    private static boolean facesAirInChunk(ServerLevel level, BlockPos p) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            m.setWithOffset(p, d);
            if (level.getChunkSource().getChunkNow(m.getX() >> 4, m.getZ() >> 4) == null) continue;
            if (level.getBlockState(m).isAir()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 根を送り込む器官の形

    /** 形になっていない送り込む器官を、読み込まれていれば置く。置いた数を返す。 */
    static int formBorers(ServerLevel level, FissureData data, FissureData.Fissure f) {
        int n = 0;
        for (int i = 0; i < f.borers.size(); i++) {
            FissureData.Borer b = f.borers.get(i);
            if (b.core != null || !b.alive) continue;
            int cx = ChunkPos.getX(b.chunk), cz = ChunkPos.getZ(b.chunk);
            if (!FissureWorld.neighbourhoodReady(level, cx, cz)) continue;
            LongArrayList floors = FissureWorld.caveFloors(level, cx, cz, f.seed + i, false, 4, 32);
            if (floors.isEmpty()) continue; // 洞窟の床が無い(岩の中): 形は無いまま働く
            // 送1 根株(高さ 8)が入る床を先に。入らなければ小さい形【仮】
            BlockPos floor = null;
            for (long k : floors) {
                if (FissureLook.rootStockFits(level, BlockPos.of(k))) {
                    floor = BlockPos.of(k);
                    break;
                }
            }
            boolean small = floor == null;
            if (small) floor = BlockPos.of(floors.getLong(0));
            RandomSource r = RandomSource.create(f.seed * 31L + i);
            List<FissureLook.Cell> cells = (small ? FissureLook.BORER_SMALL : FissureLook.BORER).build(floor, r.nextBoolean() ? Direction.Axis.X : Direction.Axis.Z, r,
                    DesignData.fissure().nest);
            b.core = FissureLook.placeWithCore(level, cells, b.placed);
            if (b.core == null) b.core = floor; // 芯を置けなかった: 床の場所を控えて置き直さない
            data.changed();
            n++;
        }
        return n;
    }

    /** 送り込む器官の芯が壊れた。その器官を止める(岩へ掘るのが止まる)。 */
    static boolean onBorerCoreRemoved(ServerLevel level, FissureData data, BlockPos pos) {
        for (FissureData.Fissure f : data.fissures()) {
            for (FissureData.Borer b : f.borers) {
                if (b.alive && pos.equals(b.core)) {
                    b.alive = false;
                    data.changed();
                    ForestDirector.get(level).note(String.format(Locale.ROOT, "亀裂 #%d の根を送り込む器官が枯れた(芯が壊れた)", f.id));
                    return true;
                }
            }
        }
        return false;
    }

    /** 止まった送り込む器官のブロックを枯れの姿へ。 */
    static int witherBorers(ServerLevel level, FissureData data, FissureData.Fissure f, int budget) {
        int n = 0;
        for (FissureData.Borer b : f.borers) {
            if (b.alive && f.state != FissureData.State.DEAD) continue;
            while (b.withered < b.placed.size() && n < budget) {
                BlockPos p = BlockPos.of(b.placed.getLong(b.withered));
                if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) break;
                BlockState s = level.getBlockState(p);
                BlockState w = FissureLook.withered(s);
                if (w != s) level.setBlock(p, w, Block.UPDATE_ALL);
                b.withered++;
                n++;
            }
        }
        if (n > 0) data.changed();
        return n;
    }
}
