package com.yayuy.overgrownvegetation.perf;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PerfTable;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * <b>流れの地図</b>(フローフィールド。2026-11-18 大群の軽量化 A。toml {@code flowFieldEnabled}・値は design の {@code perf.json} の flowField【仮】)。
 *
 * <p>11-18 の測りで、拠点へ 240 体を歩かせる場面では経路探索がサーバースレッドの 31%(1 tick 20 ms)だった。1 体ずつ A* で道を探す代わりに、
 * <b>行き先ごとに 1 枚</b>「行き先までの道のり」の地図を作り、Mob はいる升から道のりが減る向きへ歩くだけにする(何体いても地図は 1 枚)。
 *
 * <h2>地図の作り方</h2>
 * <ol>
 *     <li>サーバースレッドで、行き先のまわり {@code radiusBlocks} の<b>読み込み終わった</b>チャンク({@code getChunkNow})の区画を写し
 *     (必要な高さの区画だけ。{@link Snap})、列ごとの高さ地図(葉を除く)の天辺を読む。</li>
 *     <li>別スレッド({@link BackgroundTask})で、列ごとに立てる床を上から {@link #MAX_FLOORS} 段まで探す(天辺の上・幹の枝や根のアーチの下)。
 *     床 = 足元が固い(または水面)で、足と頭の高さが通れる。地中深く(固いブロックが {@link #SOLID_STOP} 段続く)へは潜らない。</li>
 *     <li>床から隣の列の床へは、上り 1 段・下り 3 段まで歩ける(斜めは両脇の列にも床があるときだけ)。行き先の升から道のりを広げる(ダイクストラ)。</li>
 * </ol>
 * 作り直しは {@code rebuildTicks} ごと(使っている間だけ)。{@code dropUnusedTicks} 使われなければ捨てる。
 *
 * <h2>使い方</h2>
 * {@link Steer} を Goal ごとに 1 つ持ち、毎回 {@link Steer#steer} を呼ぶ。地図が無い・地図の外・道が無い・詰まった({@code stuckTicks} の間
 * ほとんど進まない)ときは false を返すので、呼んだ側は今までの経路探索をする。詰まった Mob は {@code fallbackTicks} の間は地図を使わない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FlowFields {

    /** 1 列に持つ床の数。 */
    static final int MAX_FLOORS = 3;
    /** 床を探して下りるとき、固いブロックがこれだけ続いたら地面とみなして止める。 */
    static final int SOLID_STOP = 6;
    /** 床を探して天辺から下りる深さの上限。 */
    static final int MAX_SCAN = 64;
    static final short NO_FLOOR = Short.MIN_VALUE;
    static final int INF = Integer.MAX_VALUE;
    /** 道のりの単位: まっすぐ 10・斜め 14。水の中は 3 倍。 */
    static final int STRAIGHT = 10, DIAGONAL = 14, WATER_FACTOR = 3;
    /** 行き先とみなす升の半径(列)。 */
    static final int GOAL_RADIUS = 1;
    /** この幅より大きい Mob は 2×2 の空きを通る道のり({@link Field#distWide})を下る。 */
    static final float WIDE_MOB = 0.9F;

    private static final BackgroundTask<Field> TASK = new BackgroundTask<>("flow-field");
    private static final Long2ObjectOpenHashMap<Entry> ENTRIES = new Long2ObjectOpenHashMap<>();
    private static long lastSweep;

    /** 確かめ用: 作った地図・写しの合計 ms・計算の合計 ms・舵を取った回数・詰まって戻した回数・地図が使えなかった回数。 */
    public static long builds, captureNanos, computeNanos, steers, stuckFallbacks, misses;

    private FlowFields() {
    }

    public static boolean enabled() {
        return OvergrownVegetationConfig.COMMON.flowFieldEnabled.get();
    }

    /** 1 枚の地図。作った後は書き換えない(作り直すときは別の物に替える)ので、どのスレッドから読んでもよい。 */
    public static final class Field {
        final int minX, minZ, size;
        /** 列 i の k 段目の床の高さ(足の高さ)は floorY[i * MAX_FLOORS + k]。無ければ {@link #NO_FLOOR}。上の段から。 */
        final short[] floorY;
        /** その床が水の中(泳ぐ)か。 */
        final boolean[] water;
        /** 行き先までの道のり。 */
        final int[] dist;
        /** 体の大きい Mob(幅 {@link #WIDE_MOB} より)用の道のり: 2×2 升に床がそろう所だけを通る(11-18: 幹の間の 1 升の隙間で大きい種が詰まった)。 */
        final int[] distWide;
        /** その床から +x・+z・斜めの 3 升にも ±1 段の床がある(2×2 が空いている)。 */
        final boolean[] wide;
        final long builtAt;
        final int targetX, targetZ;

        Field(int minX, int minZ, int size, long builtAt, int targetX, int targetZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.size = size;
            int n = size * size * MAX_FLOORS;
            this.floorY = new short[n];
            this.water = new boolean[n];
            this.dist = new int[n];
            this.distWide = new int[n];
            this.wide = new boolean[n];
            Arrays.fill(this.floorY, NO_FLOOR);
            Arrays.fill(this.dist, INF);
            Arrays.fill(this.distWide, INF);
            this.builtAt = builtAt;
            this.targetX = targetX;
            this.targetZ = targetZ;
        }

        int column(int x, int z) {
            int ix = x - minX, iz = z - minZ;
            if (ix < 0 || iz < 0 || ix >= size || iz >= size) return -1;
            return iz * size + ix;
        }

        /** 足の高さ y の Mob が立っている床(無ければ -1)。高さの差 1.25 まで。 */
        int nodeAt(double x, double y, double z) {
            int c = column(net.minecraft.util.Mth.floor(x), net.minecraft.util.Mth.floor(z));
            if (c < 0) return -1;
            int best = -1;
            double bestD = 1.25D;
            for (int k = 0; k < MAX_FLOORS; k++) {
                short fy = floorY[c * MAX_FLOORS + k];
                if (fy == NO_FLOOR) break;
                double d = Math.abs(fy - y);
                if (d <= bestD) {
                    bestD = d;
                    best = c * MAX_FLOORS + k;
                }
            }
            return best;
        }

        public int distAt(double x, double y, double z, int[] d) {
            int n = nodeAt(x, y, z);
            return n < 0 ? INF : d[n];
        }

        /** その Mob の体に合う道のり。 */
        int[] distFor(net.minecraft.world.entity.Entity mob) {
            return mob.getBbWidth() > WIDE_MOB ? distWide : dist;
        }

        /** 床 a から隣の列 c へ歩いたとき、c のどの床に乗るか(歩けなければ -1)。上り 1・下り 3 まで。 */
        int stepTo(int a, int c) {
            int ya = floorY[a];
            int best = -1, bestD = Integer.MAX_VALUE;
            for (int k = 0; k < MAX_FLOORS; k++) {
                short fy = floorY[c * MAX_FLOORS + k];
                if (fy == NO_FLOOR) break;
                int dy = fy - ya;
                if (dy > 1 || dy < -3) continue;
                if (Math.abs(dy) < bestD) {
                    bestD = Math.abs(dy);
                    best = c * MAX_FLOORS + k;
                }
            }
            return best;
        }

        /** 列 c に高さ y ±1 の床があるか(斜めに角を切るときの両脇)。 */
        boolean hasFloorNear(int c, int y) {
            for (int k = 0; k < MAX_FLOORS; k++) {
                short fy = floorY[c * MAX_FLOORS + k];
                if (fy == NO_FLOOR) return false;
                if (Math.abs(fy - y) <= 1) return true;
            }
            return false;
        }

        /** 床 a から (dx, dz) の隣へ歩けるなら、その床(斜めは両脇の列にも ±1 段の床があるときだけ)。 */
        int neighbor(int a, int dx, int dz) {
            int c = a / MAX_FLOORS;
            int ix = c % size + dx, iz = c / size + dz;
            if (ix < 0 || iz < 0 || ix >= size || iz >= size) return -1;
            int to = stepTo(a, iz * size + ix);
            if (to < 0 || dx == 0 || dz == 0) return to;
            int s1 = stepTo(a, (c / size) * size + ix), s2 = stepTo(a, iz * size + c % size);
            if (s1 < 0 || s2 < 0 || Math.abs(floorY[s1] - floorY[a]) > 1 || Math.abs(floorY[s2] - floorY[a]) > 1) return -1;
            return to;
        }

        /**
         * 床 a から道のりが減る向きへ、平らなうちは {@code lookahead} 升まで先読みした目標(升の真ん中・足の高さ)。
         * 段差があればその 1 升目(上りはそこで跳ぶ)。下る道が無ければ null。
         */
        @Nullable
        Vec3 next(int a, int lookahead, int[] dist) {
            int cur = a, y0 = floorY[a], got = -1;
            for (int step = 0; step < lookahead; step++) {
                int best = -1, bestD = dist[cur];
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        int nb = neighbor(cur, dx, dz);
                        if (nb >= 0 && dist[nb] < bestD) {
                            bestD = dist[nb];
                            best = nb;
                        }
                    }
                }
                if (best < 0) break;
                if (floorY[best] != y0) {
                    if (got < 0) got = best; // 段差の 1 升目
                    break;
                }
                got = best;
                cur = best;
            }
            if (got < 0) return null;
            int c = got / MAX_FLOORS;
            double o = dist == distWide ? 1.0D : 0.5D; // 大きい Mob は 2×2 の真ん中
            return new Vec3(minX + c % size + o, floorY[got], minZ + c / size + o);
        }
    }

    private static final class Entry {
        /** 行き先。行進の地図は変わらない。追跡の地図(プレイヤーごと)は作り直すたびにプレイヤーの今の位置へ。 */
        int tx, tz;
        @Nullable
        Field field;
        boolean building;
        long buildStartedAt;
        long lastUsed;

        Entry(int tx, int tz) {
            this.tx = tx;
            this.tz = tz;
        }
    }

    /** 行き先 (x, z) の地図(4 ブロックに丸めて分け合う)。まだ無ければ作り始めて null。古ければ作り直しを始め、古い方を返す。サーバースレッドから。 */
    @Nullable
    public static Field request(ServerLevel level, double x, double z) {
        int tx = net.minecraft.util.Mth.floor(x) & ~3, tz = net.minecraft.util.Mth.floor(z) & ~3;
        long key = ((long) tx << 32) | (tz & 0xFFFFFFFFL);
        long now = level.getGameTime();
        PerfTable t = DesignData.perf();
        sweep(now, t);
        Entry e = ENTRIES.get(key);
        if (e == null) {
            e = new Entry(tx, tz);
            ENTRIES.put(key, e);
        }
        e.lastUsed = now;
        if (e.building && now - e.buildStartedAt > 400L) e.building = false; // 計算が落ちた(答えが来ない)
        if (!e.building && !TASK.running() && (e.field == null || now - e.field.builtAt >= t.flowRebuildTicks)) {
            build(level, e, now, t);
        }
        return e.field;
    }

    /** 追跡の地図(標的のプレイヤー 1 人につき 1 枚。11-18 ユーザー「遠くから追いかけるときだけ地図を使う」)。 */
    private static final java.util.Map<java.util.UUID, Entry> PLAYERS = new java.util.HashMap<>();

    /**
     * 標的のプレイヤーへの地図。プレイヤーが前の行き先から {@code chaseMoveBlocks} より動き、前に作ってから {@code chaseRebuildTicks} 経っていれば、
     * 今の位置で作り直す(できるまでは古い地図を返す。近い所では種ごとの狩りに戻るので、地図の遅れはそこで吸収する)。
     */
    @Nullable
    public static Field requestFor(ServerLevel level, net.minecraft.world.entity.player.Player p) {
        long now = level.getGameTime();
        PerfTable t = DesignData.perf();
        sweep(now, t);
        Entry e = PLAYERS.computeIfAbsent(p.getUUID(), k -> new Entry(net.minecraft.util.Mth.floor(p.getX()), net.minecraft.util.Mth.floor(p.getZ())));
        e.lastUsed = now;
        if (e.building && now - e.buildStartedAt > 400L) e.building = false;
        if (!e.building && !TASK.running()) {
            double mx = p.getX() - e.tx, mz = p.getZ() - e.tz;
            boolean moved = mx * mx + mz * mz > t.flowChaseMoveBlocks * t.flowChaseMoveBlocks;
            long age = e.field == null ? Long.MAX_VALUE : now - e.field.builtAt;
            if (e.field == null || moved && age >= t.flowChaseRebuildTicks || age >= t.flowRebuildTicks) {
                e.tx = net.minecraft.util.Mth.floor(p.getX());
                e.tz = net.minecraft.util.Mth.floor(p.getZ());
                build(level, e, now, t);
            }
        }
        return e.field;
    }

    private static void sweep(long now, PerfTable t) {
        if (now < lastSweep) lastSweep = now - 100L; // 時刻の若いワールドへ移った(11-18 見直し ③2)
        if (now - lastSweep < 100L) return;
        lastSweep = now;
        ENTRIES.values().removeIf(e -> now - e.lastUsed > t.flowDropUnusedTicks && !e.building);
        PLAYERS.values().removeIf(e -> now - e.lastUsed > t.flowDropUnusedTicks && !e.building);
    }

    private static final BlockState AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

    /**
     * 地図を作るための写し: チャンクごとに、列の天辺のいちばん低い所 − {@link #MAX_SCAN} からいちばん高い所 + 2 までの区画だけを写す
     * (巨大樹の写しは全部の区画を写すので、11-18 の初回は 1 回 10 ms かかった)。
     */
    @SuppressWarnings("unchecked")
    private static final class Snap {
        final int cx0, cz0, cw, ch, minSection;
        final net.minecraft.world.level.chunk.PalettedContainer<BlockState>[][] sections;

        Snap(int cx0, int cz0, int cw, int ch, int minSection) {
            this.cx0 = cx0;
            this.cz0 = cz0;
            this.cw = cw;
            this.ch = ch;
            this.minSection = minSection;
            this.sections = new net.minecraft.world.level.chunk.PalettedContainer[cw * ch][];
        }

        /** そのチャンクの区画の並び(写していなければ null)。 */
        @Nullable
        net.minecraft.world.level.chunk.PalettedContainer<BlockState>[] chunk(int x, int z) {
            int i = (x >> 4) - cx0, j = (z >> 4) - cz0;
            return i < 0 || j < 0 || i >= cw || j >= ch ? null : sections[j * cw + i];
        }

        BlockState get(net.minecraft.world.level.chunk.PalettedContainer<BlockState>[] secs, int x, int y, int z) {
            int s = (y >> 4) - minSection;
            if (s < 0 || s >= secs.length || secs[s] == null) return AIR;
            return secs[s].get(x & 15, y & 15, z & 15);
        }
    }

    private static void build(ServerLevel level, Entry e, long now, PerfTable t) {
        long t0 = System.nanoTime();
        int r = t.flowRadiusBlocks;
        int minX = e.tx - r, minZ = e.tz - r, size = 2 * r + 1;
        int cx0 = minX >> 4, cz0 = minZ >> 4, cx1 = (minX + size - 1) >> 4, cz1 = (minZ + size - 1) >> 4;
        Snap snap = new Snap(cx0, cz0, cx1 - cx0 + 1, cz1 - cz0 + 1, level.getMinSection());
        // 列の天辺(葉を除く高さ地図の、一番上の固いブロックの 1 つ上 = 立つ高さ)。読み込み終わっていない列は NO_FLOOR
        int[] tops = new int[size * size];
        Arrays.fill(tops, Integer.MIN_VALUE);
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                LevelChunk ch = level.getChunkSource().getChunkNow(cx, cz);
                if (ch == null) continue;
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int top = ch.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz) + 1;
                        lo = Math.min(lo, top);
                        hi = Math.max(hi, top);
                        int iz = (cz << 4) + lz - minZ, ix = (cx << 4) + lx - minX;
                        if (iz >= 0 && iz < size && ix >= 0 && ix < size) tops[iz * size + ix] = top;
                    }
                }
                net.minecraft.world.level.chunk.LevelChunkSection[] secs = ch.getSections();
                @SuppressWarnings("unchecked")
                net.minecraft.world.level.chunk.PalettedContainer<BlockState>[] copy = new net.minecraft.world.level.chunk.PalettedContainer[secs.length];
                int s0 = Math.max(0, ((lo - MAX_SCAN - 1) >> 4) - snap.minSection), s1 = Math.min(secs.length - 1, ((hi + 2) >> 4) - snap.minSection);
                for (int s = s0; s <= s1; s++) {
                    if (secs[s] != null && !secs[s].hasOnlyAir()) copy[s] = secs[s].getStates().copy();
                }
                snap.sections[(cz - cz0) * snap.cw + (cx - cx0)] = copy;
            }
        }
        captureNanos += System.nanoTime() - t0;
        e.building = true;
        e.buildStartedAt = now;
        int minY = level.getMinBuildHeight();
        TASK.start(level.getServer(), () -> compute(snap, tops, minX, minZ, size, minY, now, e.tx, e.tz), f -> {
            e.field = f;
            e.building = false;
            builds++;
        });
    }

    /** 別スレッド: 写しから床を探し、行き先から道のりを広げる。 */
    static Field compute(Snap snap, int[] tops, int minX, int minZ, int size, int minY, long now, int tx, int tz) {
        long t0 = System.nanoTime();
        Field f = new Field(minX, minZ, size, now, tx, tz);
        for (int iz = 0; iz < size; iz++) {
            for (int ix = 0; ix < size; ix++) {
                int c = iz * size + ix;
                int top = tops[c];
                if (top == Integer.MIN_VALUE) continue;
                int x = minX + ix, z = minZ + iz;
                net.minecraft.world.level.chunk.PalettedContainer<BlockState>[] secs = snap.chunk(x, z);
                if (secs == null) continue;
                int k = 0, solidRun = 0;
                // y を足の高さとして、天辺から下へ
                BlockState feet = snap.get(secs, x, top, z);
                BlockState head = snap.get(secs, x, top + 1, z);
                for (int y = top; y > top - MAX_SCAN && y > minY && k < MAX_FLOORS; y--) {
                    BlockState below = snap.get(secs, x, y - 1, z);
                    boolean feetWater = feet.getFluidState().is(FluidTags.WATER);
                    if (passable(feet) && passable(head)) {
                        boolean belowWater = below.getFluidState().is(FluidTags.WATER);
                        if (below.blocksMotion() && !below.getFluidState().is(FluidTags.LAVA) || belowWater && !feetWater) {
                            f.floorY[c * MAX_FLOORS + k] = (short) y;
                            f.water[c * MAX_FLOORS + k] = belowWater && !below.blocksMotion();
                            k++;
                        }
                    }
                    solidRun = below.blocksMotion() ? solidRun + 1 : 0;
                    if (solidRun >= SOLID_STOP) break;
                    head = feet;
                    feet = below;
                }
            }
        }
        // 2×2 が空いている床(大きい Mob 用)
        for (int iz = 0; iz + 1 < size; iz++) {
            for (int ix = 0; ix + 1 < size; ix++) {
                int c = iz * size + ix;
                for (int k = 0; k < MAX_FLOORS; k++) {
                    int n = c * MAX_FLOORS + k;
                    if (f.floorY[n] == NO_FLOOR) break;
                    int y = f.floorY[n];
                    f.wide[n] = f.hasFloorNear(c + 1, y) && f.hasFloorNear(c + size, y) && f.hasFloorNear(c + size + 1, y);
                }
            }
        }
        dijkstra(f, tx, tz, f.dist, false);
        dijkstra(f, tx, tz, f.distWide, true);
        computeNanos += System.nanoTime() - t0;
        return f;
    }

    static boolean passable(BlockState s) {
        return !s.blocksMotion() && !s.getFluidState().is(FluidTags.LAVA);
    }

    private static void dijkstra(Field f, int tx, int tz, int[] dist, boolean wideOnly) {
        int size = f.size;
        LongHeap heap = new LongHeap(4096);
        for (int dz = -GOAL_RADIUS; dz <= GOAL_RADIUS; dz++) {
            for (int dx = -GOAL_RADIUS; dx <= GOAL_RADIUS; dx++) {
                int c = f.column(tx + dx, tz + dz);
                if (c < 0) continue;
                for (int k = 0; k < MAX_FLOORS; k++) {
                    int n = c * MAX_FLOORS + k;
                    if (f.floorY[n] == NO_FLOOR) break;
                    if (wideOnly && !f.wide[n]) continue;
                    dist[n] = 0;
                    heap.push(n);
                }
            }
        }
        while (!heap.isEmpty()) {
            long top = heap.pop();
            int b = (int) top, db = (int) (top >>> 32);
            if (db != dist[b]) continue;
            int cb = b / MAX_FLOORS;
            int bx = cb % size, bz = cb / size;
            int enter = f.water[b] ? WATER_FACTOR : 1;
            // b へ歩いて来られる床 a を、隣の 8 列から探す(a から b への一歩が歩けること)
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int ax = bx + dx, az = bz + dz;
                    if (ax < 0 || az < 0 || ax >= size || az >= size) continue;
                    int ca = az * size + ax;
                    boolean diag = dx != 0 && dz != 0;
                    for (int k = 0; k < MAX_FLOORS; k++) {
                        int a = ca * MAX_FLOORS + k;
                        if (f.floorY[a] == NO_FLOOR) break;
                        int dy = f.floorY[b] - f.floorY[a]; // a から b への一歩: 上り 1・下り 3 まで
                        if (dy > 1 || dy < -3) continue;
                        if (wideOnly && !f.wide[a]) continue;
                        if (diag && (!f.hasFloorNear(az * size + bx, f.floorY[a]) || !f.hasFloorNear(bz * size + ax, f.floorY[a]))) continue;
                        int nd = db + (dx != 0 && dz != 0 ? DIAGONAL : STRAIGHT) * enter;
                        if (nd < dist[a]) {
                            dist[a] = nd;
                            heap.push(((long) nd << 32) | a);
                        }
                    }
                }
            }
        }
    }

    /** 道のり(上位 32 ビット)で並ぶ long の最小ヒープ。 */
    private static final class LongHeap {
        private long[] a;
        private int n;

        LongHeap(int cap) {
            a = new long[cap];
        }

        boolean isEmpty() {
            return n == 0;
        }

        void push(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            int i = n++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (a[p] <= v) break;
                a[i] = a[p];
                i = p;
            }
            a[i] = v;
        }

        long pop() {
            long r = a[0];
            long v = a[--n];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                if (l >= n) break;
                int m = l + 1 < n && a[l + 1] < a[l] ? l + 1 : l;
                if (a[m] >= v) break;
                a[i] = a[m];
                i = m;
            }
            a[i] = v;
            return r;
        }
    }

    /** Goal ごとの舵取り。詰まりの見張りと、普通の経路探索に戻している間を持つ。 */
    public static final class Steer {
        private boolean active, steering;
        private double lastX, lastZ;
        private long lastCheck, fallbackUntil;

        /**
         * 行き先 (tx, tz) の地図を下って歩かせる(移動の舵に目標を渡す)。歩かせたら true。地図が無い・地図の外・道が無い・行き先に着いた・
         * 詰まったなら false(呼んだ側が今までの経路探索をする)。{@code maxDist} を渡すと、その道のりより行き先に近い Mob は歩かせない(後続が先導役より前に出ない)。
         */
        public boolean steer(net.minecraft.world.entity.PathfinderMob mob, double tx, double tz, double speed, int maxDistBehind, @Nullable net.minecraft.world.entity.Mob ahead) {
            if (!enabled() || !(mob.level() instanceof ServerLevel level) || mob instanceof com.yayuy.overgrownvegetation.entity.ArboraEntity) return false;
            long now = level.getGameTime();
            if (now < fallbackUntil) return false;
            return steerOn(request(level, tx, tz), mob, level, now, speed, maxDistBehind, ahead);
        }

        /** 標的のプレイヤーへの地図を下る(追跡の寄り。11-18)。 */
        public boolean steerTo(net.minecraft.world.entity.PathfinderMob mob, net.minecraft.world.entity.player.Player target, double speed) {
            if (!enabled() || !(mob.level() instanceof ServerLevel level) || mob instanceof com.yayuy.overgrownvegetation.entity.ArboraEntity) return false;
            long now = level.getGameTime();
            if (now < fallbackUntil) return false;
            return steerOn(requestFor(level, target), mob, level, now, speed, 0, null);
        }

        private boolean steerOn(@Nullable Field f, net.minecraft.world.entity.PathfinderMob mob, ServerLevel level, long now, double speed, int maxDistBehind,
                                @Nullable net.minecraft.world.entity.Mob ahead) {
            if (f == null) {
                misses++;
                active = steering = false;
                return false;
            }
            int[] d = f.distFor(mob);
            int node = f.nodeAt(mob.getX(), mob.getY(), mob.getZ());
            if (node < 0 || d[node] == INF || d[node] == 0) {
                misses++;
                active = steering = false;
                return false;
            }
            if (ahead != null) {
                int da = f.distAt(ahead.getX(), ahead.getY(), ahead.getZ(), d);
                if (da == INF || d[node] <= da + maxDistBehind) {
                    active = steering = false;
                    return false; // 先導役より前・横にいる(先導役が地図の上にいない)
                }
            }
            Vec3 g = f.next(node, DesignData.perf().flowLookaheadCells, d);
            if (g == null) {
                misses++;
                active = steering = false;
                return false;
            }
            PerfTable t = DesignData.perf();
            if (!active) {
                active = true;
                lastX = mob.getX();
                lastZ = mob.getZ();
                lastCheck = now;
            } else if (now - lastCheck >= t.flowStuckTicks) {
                double dx = mob.getX() - lastX, dz = mob.getZ() - lastZ;
                if (dx * dx + dz * dz < 1.0D) {
                    fallbackUntil = now + t.flowFallbackTicks;
                    active = steering = false;
                    stuckFallbacks++;
                    return false;
                }
                lastX = mob.getX();
                lastZ = mob.getZ();
                lastCheck = now;
            }
            if (!mob.getNavigation().isDone()) mob.getNavigation().stop();
            double gx = g.x, gz = g.z;
            double spread = t.flowLaneSpreadBlocks;
            if (spread > 0.0D) {
                // 列の幅(11-18 ユーザー「列が細過ぎたら横にずらす」): Mob ごとに決まった量だけ、進む向きの横へずらす
                double ddx = gx - mob.getX(), ddz = gz - mob.getZ(), dl = Math.sqrt(ddx * ddx + ddz * ddz);
                if (dl > 1.0E-3D) {
                    double off = ((((mob.getId() * 0x9E3779B1) >>> 8) % 1000) / 1000.0D - 0.5D) * 2.0D * spread;
                    gx += -ddz / dl * off;
                    gz += ddx / dl * off;
                }
            }
            goalX = gx;
            goalY = g.y;
            goalZ = gz;
            goalSpeed = speed;
            mob.getMoveControl().setWantedPosition(goalX, goalY, goalZ, speed);
            steering = true;
            steers++;
            return true;
        }

        private double goalX, goalY, goalZ, goalSpeed;

        /** 地図を下っている最中か(最後の {@link #steer} が true)。 */
        public boolean active() {
            return steering;
        }

        /**
         * 前の {@link #steer} の目標を、移動の舵にもう一度渡す。バニラの舵(MoveControl)は目標を渡された tick しか歩かせない
         * (次の tick には待ちに戻る)ので、地図を下っている間は毎 tick 呼ぶ。目標の計算はしない。
         */
        public void reapply(net.minecraft.world.entity.Mob mob) {
            if (steering) mob.getMoveControl().setWantedPosition(goalX, goalY, goalZ, goalSpeed);
        }

        /** 地図を使っていない間に呼ぶ(詰まりの見張りを始めからにする)。 */
        public void reset() {
            active = steering = false;
        }

        /** 次の {@link #steer} まで舵の渡し直しを止める(詰まりの見張りは続ける)。 */
        public void pause() {
            steering = false;
        }
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        return String.format(java.util.Locale.ROOT, "流れの地図: %s・地図 %d 枚・作った %d 回(写し 平均 %.2f ms・計算 平均 %.1f ms)・舵 %d・詰まって戻した %d・使えなかった %d",
                enabled() ? "入" : "切", ENTRIES.size(), builds, builds == 0 ? 0.0D : captureNanos / 1e6 / builds,
                builds == 0 ? 0.0D : computeNanos / 1e6 / builds, steers, stuckFallbacks, misses);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ENTRIES.clear();
        PLAYERS.clear();
        lastSweep = 0L; // 次のワールドへ持ち越さない(11-18 見直し ③2)
    }

    /** {@code /ov perf flow [on|off]}: 入り切り(toml の値を書き換える。前後を同じ起動で比べる用)と、今の数。 */
    @SubscribeEvent
    public static void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("ov")
                .then(net.minecraft.commands.Commands.literal("perf").requires(s -> s.hasPermission(2))
                        .then(net.minecraft.commands.Commands.literal("flow")
                                .executes(ctx -> show(ctx.getSource()))
                                .then(net.minecraft.commands.Commands.literal("probe").executes(ctx -> probe(ctx.getSource())))
                                .then(net.minecraft.commands.Commands.literal("clear").executes(ctx -> {
                                    // 比べの片付け: まわり 256 の Mob(プレイヤー以外)を死なせずに消す(kill だと植物側は死の破裂で汚染を撒き、窓ごとに地形が変わる)
                                    int n = 0;
                                    for (net.minecraft.world.entity.Mob m : ctx.getSource().getLevel().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                                            new net.minecraft.world.phys.AABB(ctx.getSource().getPosition(), ctx.getSource().getPosition()).inflate(256.0D, 384.0D, 256.0D))) {
                                        m.discard();
                                        n++;
                                    }
                                    int fn = n;
                                    ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("流れの地図の比べ: " + fn + " 体を消した"), false);
                                    return n;
                                }))
                                .then(net.minecraft.commands.Commands.literal("trail")
                                        .then(net.minecraft.commands.Commands.argument("label", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .executes(ctx -> trail(ctx.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "label")))))
                                .then(net.minecraft.commands.Commands.literal("on").executes(ctx -> set(ctx.getSource(), true)))
                                .then(net.minecraft.commands.Commands.literal("off").executes(ctx -> set(ctx.getSource(), false))))));
    }

    /** 確かめ用: 歩かされている Mob を 5 体まで、地図の上のどこにいて、どこを目指しているか。 */
    private static int probe(net.minecraft.commands.CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        int n = 0;
        for (net.minecraft.world.entity.Mob m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(src.getPosition(), src.getPosition()).inflate(160.0D),
                com.yayuy.overgrownvegetation.spawn.PlantMarch::active)) {
            if (n++ >= 5) break;
            Vec3 t = com.yayuy.overgrownvegetation.spawn.PlantMarch.target(m);
            Field f = null;
            for (Entry e : ENTRIES.values()) if (e.field != null && Math.abs(e.tx - t.x) < 4 && Math.abs(e.tz - t.z) < 4) f = e.field;
            StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "%s (%.1f %.1f %.1f) 舵 %s 目標 (%.1f %.1f %.1f) ナビ %s 速さ %.3f",
                    m.getType().toShortString(), m.getX(), m.getY(), m.getZ(), m.getMoveControl().hasWanted(), m.getMoveControl().getWantedX(),
                    m.getMoveControl().getWantedY(), m.getMoveControl().getWantedZ(), m.getNavigation().isDone() ? "なし" : "あり", m.getDeltaMovement().horizontalDistance()));
            if (f != null) {
                int c = f.column(net.minecraft.util.Mth.floor(m.getX()), net.minecraft.util.Mth.floor(m.getZ()));
                b.append(" 床");
                if (c >= 0) for (int k = 0; k < MAX_FLOORS && f.floorY[c * MAX_FLOORS + k] != NO_FLOOR; k++) b.append(' ').append(f.floorY[c * MAX_FLOORS + k]).append('/').append(f.distFor(m)[c * MAX_FLOORS + k]);
                int node = f.nodeAt(m.getX(), m.getY(), m.getZ());
                Vec3 g = node < 0 ? null : f.next(node, DesignData.perf().flowLookaheadCells, f.distFor(m));
                b.append(" 次 ").append(g == null ? "-" : String.format(java.util.Locale.ROOT, "(%.1f %.1f %.1f)", g.x, g.y, g.z));
            }
            b.append(" 行動");
            m.goalSelector.getAvailableGoals().stream().filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
                    .forEach(w -> b.append(' ').append(w.getGoal().getClass().getSimpleName()));
            String s = b.toString();
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(s), false);
        }
        return n;
    }

    /** 足跡の記録(確かめ用): 印 flowtest の Mob の位置を 10 tick ごとに書く。label が "stop" なら止める。 */
    @Nullable
    private static java.io.PrintWriter trailOut;
    private static int trailTicks;

    private static int trail(net.minecraft.commands.CommandSourceStack src, String label) {
        if (trailOut != null) {
            trailOut.close();
            trailOut = null;
        }
        if (label.equals("stop")) {
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal("足跡: 止めた"), false);
            return 1;
        }
        try {
            java.nio.file.Path dir = src.getServer().getServerDirectory().resolve(OvergrownVegetation.MODID).resolve("perf");
            java.nio.file.Files.createDirectories(dir);
            trailOut = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(dir.resolve("flow-trail-" + label + ".tsv")));
            trailOut.println("tick\tid\tx\ty\tz\tflow");
            // 地図があれば、いちばん上の床の高さと道のりも書く(絵の下地)
            for (Entry e : ENTRIES.values()) {
                if (e.field == null) continue;
                Field f = e.field;
                java.io.PrintWriter fo = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(dir.resolve("flow-field-" + label + ".tsv")));
                fo.println("minX\t" + f.minX + "\tminZ\t" + f.minZ + "\tsize\t" + f.size + "\ttx\t" + f.targetX + "\ttz\t" + f.targetZ);
                for (int c = 0; c < f.size * f.size; c++) {
                    int n = c * MAX_FLOORS;
                    fo.print(f.floorY[n] == NO_FLOOR ? "x" : f.floorY[n] + "," + (f.dist[n] == INF ? -1 : f.dist[n]));
                    fo.print(c % f.size == f.size - 1 ? '\n' : ' ');
                }
                fo.close();
                break;
            }
        } catch (java.io.IOException ex) {
            src.sendFailure(net.minecraft.network.chat.Component.literal("足跡: 書けない " + ex));
            return 0;
        }
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal("足跡: 書き始めた " + label), false);
        return 1;
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (trailOut == null || ++trailTicks % 10 != 0) return;
        ServerLevel level = event.getServer().overworld();
        long now = level.getGameTime();
        for (net.minecraft.world.entity.Entity en : level.getAllEntities()) {
            if (!(en instanceof net.minecraft.world.entity.Mob m) || !m.getTags().contains("flowtest")) continue;
            trailOut.printf(java.util.Locale.ROOT, "%d\t%d\t%.1f\t%.1f\t%.1f\t%d%n", now, m.getId(), m.getX(), m.getY(), m.getZ(), m.getNavigation().isDone() ? 1 : 0);
        }
    }

    private static int set(net.minecraft.commands.CommandSourceStack src, boolean on) {
        OvergrownVegetationConfig.COMMON.flowFieldEnabled.set(on);
        return show(src);
    }

    private static int show(net.minecraft.commands.CommandSourceStack src) {
        String s = describe() + "・後続が地図を下った " + com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack.flowSteers + "・追跡の寄り 始めた " + FlowChaseGoal.starts + "・退いた " + FlowChaseGoal.gaveUp;
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(s), false);
        return 1;
    }
}
