package com.yayuy.overgrownvegetation.corruption.fissure;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.corruption.pushback.RingWards;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.ley.LeyPumpBlock;
import com.yayuy.overgrownvegetation.ley.LeyPumpBlockEntity;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * 地下の小さな亀裂の働き(2026-11-20)。洞窟待ちの亀裂を開き、巣から根を伸ばして地下を繁茂させ、口に着いたら地上へあふれさせ、
 * 芯を壊されたら根を枯らす。
 *
 * <ul>
 *     <li><b>開く</b>: 司令塔が決めた (x, z) のチャンクと周り 8 つが読み込まれたら、そのチャンクで地脈の線から {@code lineBlocks} 以内の洞窟の床
 *     (地面から {@code minDepth} 下・つながった空気が {@code minAir} 以上)を探す。無い・汲み上げ器が近ければ選び直す。
 *     見つかれば {@link FissureLook#NEST} の形を置き、前触れ(F1 の地割れの音を小さく + 胞子の噴き上げ)。</li>
 *     <li><b>根</b>: 開いて {@code rootDelayDays} 後、巣のまわり {@code spreadRadius} を汚染し、洞窟の空気をたどって空の見えるマス(洞窟の口)までの道を
 *     作る(届かなければ行ける所の奥まで)。道を時計で {@code ticksPerCell} に 1 マスずつ進み、床・壁に絡む根を埋め、まわり {@code corruptRadius}
 *     の空気に面したブロックを汚染する(汚染の入口は菌糸の染み下りと同じ)。菌糸の染み下りとは記録を共有しない(別の道。同じマスは
 *     「もう汚染されている」で飛ばすだけ)。</li>
 *     <li><b>口</b>: 道の最後(口)に着いたら、口のまわりの地面へ絡む根を {@code arms} 本這わせ、芽の点(SporeSprouts)を足して地上の汚染を始める。</li>
 *     <li><b>枯れ</b>: 芯(器官の芯のブロック)が壊れたら止め、置いた根・巣を枯れの姿にする。</li>
 * </ul>
 * 時計で決まるので、読み込まれていない間の分は読み込まれたときに追いつく。押し返す道具の効く所には根を伸ばさない(そこで止まる)。
 * 重さは {@link TickBudget} の {@link PerfJob#UNDERGROUND_FISSURE}。生成中のチャンクには触らない(getChunkNow で確かめる)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class FissureWorld {
    private FissureWorld() {
    }

    private static final Vector3f SPORE_GREEN = new Vector3f(0.34F, 0.72F, 0.27F);
    private static final int FLAGS = Block.UPDATE_ALL;

    /** 計測(/ov perf fissure list に出す。保存しない)。 */
    static long statTickMaxNs, statTickSumNs, statTicks, statResolveMaxNs, statResolveLastNs, statScanMaxNs, statOpenNs, statSearchMaxNs, statUpdateMaxNs, statMeasureMaxNs, statApplyMaxNs, statMeasureOver10, statMeasureOver1;

    // ---------------------------------------------------------------- 毎 tick

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        FissureData data = FissureData.get(level);
        if (data.fissures().isEmpty()) return;
        if (!TickBudget.mayStart(PerfJob.UNDERGROUND_FISSURE)) return;
        long start = TickBudget.begin();
        int units = 0;
        try {
            FissureTable t = DesignData.fissure();
            long now = PhaseData.clockNow(level);
            long game = level.getGameTime();
            int budget = Math.max(1, t.roots.cellsPerTick);
            for (FissureData.Fissure f : List.copyOf(data.fissures())) {
                switch (f.state) {
                    case PENDING -> {
                        if (t.enabled && ((game + f.id) % 20 == 0 || JOBS.containsKey(f.id))) {
                            resolve(level, data, f);
                            units++;
                        }
                    }
                    case OPEN -> {
                        if (!t.enabled) continue;
                        // 地下の台帳の前線(粗い間隔。読み込まれていない所も時刻で進む)
                        if ((game + f.id) % Math.max(1, t.ledger.intervalTicks) == 0) {
                            long u0 = System.nanoTime();
                            units += 1 + FissureLedger.update(level, data, f, now);
                            statUpdateMaxNs = Math.max(statUpdateMaxNs, System.nanoTime() - u0);
                        }
                        if ((game + f.id) % 20 == 0) units += FissureLedger.formBorers(level, data, f);
                        int used = grow(level, data, f, now, budget);
                        budget -= used;
                        units += used;
                        if (f.reachedMouth()) units += overflow(level, data, f, now, Math.max(1, budget));
                        if (t.omen.puffTicks > 0 && (game + f.id) % t.omen.puffTicks == 0) puff(level, f, false);
                    }
                    case DEAD -> {
                        // 死んだ時刻までに届く分は台帳に足す(1 回で済む)
                        if ((game + f.id) % Math.max(1, t.ledger.intervalTicks) == 0) units += FissureLedger.update(level, data, f, now);
                        units += witherSome(level, data, f, t.roots.witherPerTick);
                    }
                }
                units += FissureLedger.witherBorers(level, data, f, t.roots.witherPerTick);
                if (budget <= 0 || !TickBudget.timeLeft()) break;
            }
            // 地下の見立てを測る・読み込まれたチャンクの洞窟を追いつかせる
            long m0 = System.nanoTime();
            if (TickBudget.timeLeft()) units += FissureLedger.measureTick(level, data);
            long a0 = System.nanoTime();
            statMeasureMaxNs = Math.max(statMeasureMaxNs, a0 - m0);
            if (a0 - m0 > 10_000_000L) statMeasureOver10++;
            if (a0 - m0 > 1_000_000L) statMeasureOver1++;
            if (TickBudget.timeLeft()) units += FissureLedger.applyTick(level, data, now);
            // 巣・器官・口の形の残り(置く列。2 ms まで)
            if (TickBudget.timeLeft()) units += FissureLook.drain(System.nanoTime() + 2_000_000L);
            statApplyMaxNs = Math.max(statApplyMaxNs, System.nanoTime() - a0);
        } finally {
            if (units > 0) {
                long ns = System.nanoTime() - start;
                statTickMaxNs = Math.max(statTickMaxNs, ns);
                statTickSumNs += ns;
                statTicks++;
            }
            TickBudget.end(PerfJob.UNDERGROUND_FISSURE, start, units);
        }
    }

    // ---------------------------------------------------------------- 開く

    /**
     * 洞窟待ちの亀裂を開けるか見る。開いたら "opened"、待つなら "wait: …"、選び直したら "moved: …"、取り消したら "dropped: …"。
     */
    public static String resolve(ServerLevel level, FissureData data, FissureData.Fissure f) {
        if (f.state != FissureData.State.PENDING) return "not pending";
        int cx = f.x >> 4, cz = f.z >> 4;
        if (!neighbourhoodReady(level, cx, cz)) return "wait: 読み込まれていない";
        FissureTable t = DesignData.fissure();
        // 汲み上げ器: 読み込まれている範囲のチャンクは覚え直す。読み込まれていない所は覚えている物で見る(置かれたとき・読み込まれたときに覚える)
        int pr = (t.plan.pumpClearBlocks >> 4) + 1;
        for (int dx = -pr; dx <= pr; dx++) {
            for (int dz = -pr; dz <= pr; dz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                if (c != null) rememberPumps(level, data, c);
            }
        }
        if (FissurePlanner.nearKnownPump(data, f.x, f.z)) return relocate(level, data, f, "汲み上げ器が近い");
        // 洞窟の床の候補を先に集め(1 回)、口に届くかの試しは 1 回の呼び出しに 1 つずつ(重さを tick に分ける。11-20 測り: まとめると 50 ms)
        long r0 = System.nanoTime();
        try {
            ResolveJob job = JOBS.get(f.id);
            if (job == null || job.x != f.x || job.z != f.z) {
                long s0 = System.nanoTime();
                job = new ResolveJob(f.x, f.z, caveFloors(level, cx, cz, f.seed));
                statScanMaxNs = Math.max(statScanMaxNs, System.nanoTime() - s0);
                if (job.floors.isEmpty()) {
                    JOBS.remove(f.id);
                    return relocate(level, data, f, "洞窟が無い");
                }
                JOBS.put(f.id, job);
                if (t.cave.preferMouth) return "wait: 洞窟の床を集めた(" + job.floors.size() + ")";
            }
            BlockPos floor = null;
            FissureData.Fissure tmp = null;
            if (t.cave.preferMouth && job.next < job.floors.size()) {
                if (job.search == null) job.search = new RouteSearch(level, BlockPos.of(job.floors.getLong(job.next)));
                long q0 = System.nanoTime();
                boolean fin = job.search.step(level, Math.max(1, t.roots.searchPerTick));
                statSearchMaxNs = Math.max(statSearchMaxNs, System.nanoTime() - q0);
                if (!fin) return "wait: 口に届くか試している(" + (job.next + 1) + "/" + job.floors.size() + ")";
                long k = job.floors.getLong(job.next++);
                tmp = new FissureData.Fissure(0, BlockPos.getX(k), BlockPos.getZ(k), 0, 0L);
                tmp.floor = BlockPos.of(k);
                job.search.applyTo(tmp);
                job.search = null;
                // 口までの道が短い床は洞窟の入口そのもの(地上から亀裂が見える。追記 1)なので使わない
                boolean tooClose = tmp.routeHitsMouth && tmp.route.length < t.cave.minMouthRoute;
                if (tmp.routeHitsMouth && !tooClose) floor = tmp.floor;
                else {
                    if (!tooClose && job.fallback == Long.MIN_VALUE) job.fallback = k;
                    if (job.next < job.floors.size()) return "wait: 口に届くか試している(" + job.next + "/" + job.floors.size() + ")";
                    tmp = null;
                }
            }
            if (floor == null) {
                if (!t.cave.preferMouth) floor = BlockPos.of(job.floors.getLong(0));
                else if (job.fallback != Long.MIN_VALUE) floor = BlockPos.of(job.fallback);
                else {
                    JOBS.remove(f.id);
                    return relocate(level, data, f, "洞窟の入口に近すぎる");
                }
            }
            JOBS.remove(f.id);
            if (FissurePlanner.nearKnownPump(data, floor.getX(), floor.getZ())) return relocate(level, data, f, "汲み上げ器が近い");
            long o0 = System.nanoTime();
            open(level, data, f, floor);
            statOpenNs = System.nanoTime() - o0;
            if (tmp != null) { // 試した道をそのまま使う(作り直さない)。道が通るチャンクは洞窟、口のチャンクは口と分かった
                f.route = tmp.route;
                f.routeHitsMouth = tmp.routeHitsMouth;
                FissureLedger.markRoute(data, f.route, f.routeHitsMouth);
                data.changed();
            }
            return "opened";
        } finally {
            statResolveLastNs = System.nanoTime() - r0;
            statResolveMaxNs = Math.max(statResolveMaxNs, statResolveLastNs);
        }
    }

    /** 洞窟待ちの亀裂の、調べかけ(保存しない。サーバーを止めたら集め直す)。 */
    private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<ResolveJob> JOBS = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    /** 開いた亀裂の根の道の探しかけ(保存しない)。 */
    private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<RouteSearch> GROW_SEARCH = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

    private static final class ResolveJob {
        final int x, z;
        final LongArrayList floors;
        int next;
        /** 口に届かないが使える床(最初の 1 つ)。 */
        long fallback = Long.MIN_VALUE;
        @Nullable
        RouteSearch search;

        ResolveJob(int x, int z, LongArrayList floors) {
            this.x = x;
            this.z = z;
            this.floors = floors;
        }
    }

    private static String relocate(ServerLevel level, FissureData data, FissureData.Fissure f, String why) {
        FissureTable t = DesignData.fissure();
        f.relocations++;
        int[] site = f.relocations > t.plan.maxRelocations ? null : FissurePlanner.chooseSite(level, data, level.getRandom(), f.id);
        if (site == null) {
            data.remove(f.id);
            JOBS.remove(f.id);
            if (f.paid > 0) ForestDirector.get(level).refund(level, f.paid);
            ForestDirector.get(level).note(String.format(Locale.ROOT, "亀裂 #%d を取り消した(%s。%.0f 戻した)", f.id, why, f.paid));
            return "dropped: " + why;
        }
        f.x = site[0];
        f.z = site[1];
        f.plannedAt = PhaseData.clockNow(level); // 選び直した時刻から育つ
        data.changed();
        return "moved: " + why;
    }

    static boolean neighbourhoodReady(ServerLevel level, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) return false;
            }
        }
        return true;
    }

    /**
     * そのチャンクの洞窟の床(真下が固い空気のマス・空が見えない・地面から {@code minDepth} 下・地脈の線から {@code lineBlocks} 以内・
     * つながった空気が {@code minAir} 以上)。線に近い物から(同じ距離なら種のくじ)。無ければ null。
     */
    static LongArrayList caveFloors(ServerLevel level, int cx, int cz, int seed) {
        return caveFloors(level, cx, cz, seed, true);
    }

    /** {@code nearLine} = 地脈の線から lineBlocks 以内の列だけ(亀裂を開くとき)。台帳の見立てでは線を見ない。 */
    static LongArrayList caveFloors(ServerLevel level, int cx, int cz, int seed, boolean nearLine) {
        FissureTable t = DesignData.fissure();
        return caveFloors(level, cx, cz, seed, nearLine, Math.max(1, t.cave.preferMouth ? t.cave.mouthTries : 1), 64);
    }

    /** {@code maxFloors} 個まで・空気の数を見る候補は {@code maxTries} 個まで。 */
    static LongArrayList caveFloors(ServerLevel level, int cx, int cz, int seed, boolean nearLine, int maxFloors, int maxTries) {
        long[] order = caveCandidates(level, cx, cz, seed, nearLine, 1);
        LongArrayList floors = new LongArrayList();
        int tried = 0;
        for (long k : order) {
            if (tried++ >= maxTries || floors.size() >= maxFloors) break;
            BlockPos p = BlockPos.of(k);
            // すぐ隣の床は同じ答えになるので、選んだ床から 6 マス以内は飛ばす
            boolean near = false;
            for (long q : floors) if (p.distSqr(BlockPos.of(q)) < 36.0D) near = true;
            if (near) continue;
            if (goodFloor(level, p)) floors.add(k);
        }
        return floors;
    }

    /** 床の候補として使えるか(空が見えない・押し返す道具の効く所でない・つながった空気が minAir 以上)。 */
    static boolean goodFloor(ServerLevel level, BlockPos p) {
        FissureTable t = DesignData.fissure();
        return !skyOpen(level, p) && !RingWards.guarded(level, p) && countAir(level, p, t.cave.airScanCap) >= t.cave.minAir;
    }

    /**
     * そのチャンクの洞窟の床の候補(真下が固い空気のマス・地面から minDepth 下・nearLine なら地脈の線から lineBlocks 以内)を、
     * 線に近い順(同じなら種のくじ)に。{@code stride} 列おきに見る(見立てを測るときは 2 で 1/4 の重さ)。
     */
    static long[] caveCandidates(ServerLevel level, int cx, int cz, int seed, boolean nearLine, int stride) {
        FissureTable t = DesignData.fissure();
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return new long[0];
        long wseed = level.getSeed();
        int minY = level.getMinBuildHeight() + t.cave.floorMargin;
        LongArrayList cands = new LongArrayList();
        it.unimi.dsi.fastutil.doubles.DoubleArrayList score = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
        RandomSource r = RandomSource.create(seed ^ 0x5F15_5E7DL);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int st = Math.max(1, stride);
        for (int lx = 0; lx < 16; lx += st) {
            for (int lz = 0; lz < 16; lz += st) {
                int x = (cx << 4) + lx, z = (cz << 4) + lz;
                double d = nearLine ? LeyLines.distanceToLine(wseed, x, z) : 0.0D;
                if (d > t.plan.lineBlocks) continue;
                // 本当の地面(梢・幹・下草をくぐる。OCEAN_FLOOR は幹と葉を地面と数え、タイガで入口の床を深いと見ていた)
                int ground = com.yayuy.overgrownvegetation.spore.field.CaveMouths.groundTop(chunk, x, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz), z, level.getMinBuildHeight());
                boolean solidBelow = false;
                for (int y = minY; y <= ground - t.cave.minDepth; y++) {
                    m.set(x, y, z);
                    BlockState s = chunk.getBlockState(m);
                    boolean air = s.isAir();
                    if (air && solidBelow) {
                        cands.add(m.asLong());
                        score.add(d + r.nextDouble() * 2.0D);
                    }
                    solidBelow = !air && s.getFluidState().isEmpty() && s.blocksMotion();
                }
            }
        }
        int n = cands.size();
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        it.unimi.dsi.fastutil.ints.IntArrays.quickSort(idx, (a, b) -> Double.compare(score.getDouble(a), score.getDouble(b)));
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = cands.getLong(idx[i]);
        return out;
    }

    /**
     * そのチャンクの洞窟の床を 1 つ、その場で選ぶ(コマンド・テスト用。毎 tick の働きは {@link #resolve} が 1 tick に 1 つずつ試す)。
     * 口に届く床を先に。無ければ null。
     */
    @Nullable
    public static BlockPos findCaveFloor(ServerLevel level, int cx, int cz, int seed) {
        LongArrayList floors = caveFloors(level, cx, cz, seed);
        if (floors.isEmpty()) return null;
        FissureTable.Cave c = DesignData.fissure().cave;
        if (!c.preferMouth) return BlockPos.of(floors.getLong(0));
        BlockPos fallback = null;
        for (long k : floors) {
            FissureData.Fissure tmp = new FissureData.Fissure(0, BlockPos.getX(k), BlockPos.getZ(k), 0, 0L);
            tmp.floor = BlockPos.of(k);
            buildRoute(level, tmp);
            boolean tooClose = tmp.routeHitsMouth && tmp.route.length < c.minMouthRoute;
            if (tmp.routeHitsMouth && !tooClose) return tmp.floor;
            if (!tooClose && fallback == null) fallback = tmp.floor;
        }
        return fallback;
    }

    /** つながった空気の数(上限まで)。読み込まれていないチャンクは数えない。 */
    static int countAir(ServerLevel level, BlockPos from, int cap) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        q.enqueue(from.asLong());
        seen.add(from.asLong());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (!q.isEmpty() && seen.size() < cap) {
            long k = q.dequeueLong();
            for (Direction d : Direction.values()) {
                m.set(k).move(d);
                long nk = m.asLong();
                if (seen.contains(nk) || !passable(level, m)) continue;
                seen.add(nk);
                q.enqueue(nk);
            }
        }
        return seen.size();
    }

    /** 根の道・空気の数で通れるマス(空気か、液体の無い入れ替わる物。読み込まれていない所は通れない)。 */
    static boolean passable(ServerLevel level, BlockPos p) {
        if (p.getY() < level.getMinBuildHeight() || p.getY() >= level.getMaxBuildHeight()) return false;
        LevelChunk c = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        if (c == null) return false;
        BlockState s = c.getBlockState(p);
        return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    /** 巣を置いて開く。 */
    public static void open(ServerLevel level, FissureData data, FissureData.Fissure f, BlockPos floor) {
        FissureTable t = DesignData.fissure();
        RandomSource r = RandomSource.create(f.seed);
        Direction.Axis axis = r.nextBoolean() ? Direction.Axis.X : Direction.Axis.Z;
        List<FissureLook.Cell> cells = FissureLook.NEST.build(floor, axis, r, t.nest);
        // 芯と、芯のまわり(割れ目・殻・膜)はすぐ。肋・根・光る筋は置く列で何 tick かに分ける
        f.core = FissureLook.placeWithCore(level, cells, f.placed);
        f.floor = floor.immutable();
        f.state = FissureData.State.OPEN;
        // 時計の起点は司令塔が場所を決めた時刻(誰も来ないうちも育っていたことにする。読み込まれたときに巣・根・台帳が追いつく)
        long now = PhaseData.clockNow(level);
        f.openedAt = Math.min(now, f.plannedAt);
        long nest = ChunkPos.asLong(floor.getX() >> 4, floor.getZ() >> 4);
        if (data.known(nest) == 0) data.setKnown(nest, FissureData.K_CAVE, null);
        data.changed();
        ForestDirector.get(level).note(String.format(Locale.ROOT, "亀裂 #%d が開いた(%s)", f.id, floor.toShortString()));
        if (now - f.plannedAt > t.omen.windowTicks) return; // 追いつきで開いた(前から開いていたことになる): 前触れは無し
        // 前触れ(全体管理の案【仮】): F1 の地割れの音を小さく・高めに + 胞子の噴き上げ
        // 洞窟の中にいるプレイヤーにだけ(追記 1: 前触れは洞窟の中だけ。地上には鳴らさない)
        if (t.omen.rumbleVolume > 0.0F) {
            for (ServerPlayer p : level.players()) {
                if (!underground(level, p) || p.distanceToSqr(floor.getX(), floor.getY(), floor.getZ()) > (double) t.omen.puffRange * t.omen.puffRange) continue;
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(ModSounds.EARTHQUAKE_FISSURE, SoundSource.HOSTILE,
                        floor.getX() + 0.5D, floor.getY(), floor.getZ() + 0.5D, t.omen.rumbleVolume, 1.35F, level.getRandom().nextLong()));
            }
        }
        puff(level, f, true);
    }

    /** 亀裂から胞子が漏れる(場所の手がかり)。開いたときは多め。 */
    static void puff(ServerLevel level, FissureData.Fissure f, boolean big) {
        if (f.floor == null) return;
        int range = DesignData.fissure().omen.puffRange;
        double x = f.floor.getX() + 0.5D, y = f.floor.getY() + 0.2D, z = f.floor.getZ() + 0.5D;
        // 洞窟の中にいるプレイヤーにだけ見せる(追記 1。地上の人には胞子も出さない)
        for (ServerPlayer p : level.players()) {
            if (!underground(level, p) || p.distanceToSqr(x, y, z) > (double) range * range) continue;
            level.sendParticles(p, new DustParticleOptions(SPORE_GREEN, big ? 1.6F : 1.0F), false, x, y, z, big ? 60 : 4, 0.8D, big ? 1.5D : 0.4D, 0.8D, 0.02D);
            if (big) level.sendParticles(p, ParticleTypes.LARGE_SMOKE, false, x, y, z, 20, 0.6D, 0.3D, 0.6D, 0.02D);
        }
    }

    /** プレイヤーが空の見えない所(洞窟の中)にいるか。 */
    static boolean underground(ServerLevel level, ServerPlayer p) {
        return !skyOpen(level, BlockPos.containing(p.getEyePosition()));
    }

    // ---------------------------------------------------------------- 根

    /** 根を時計の分だけ伸ばす。こなした数を返す。 */
    public static int grow(ServerLevel level, FissureData data, FissureData.Fissure f, long now, int budget) {
        FissureTable t = DesignData.fissure();
        if (f.state != FissureData.State.OPEN || f.floor == null || f.core == null) return 0;
        long sinceOpen = now - f.openedAt - (long) (t.nest.rootDelayDays * 24000.0D);
        if (sinceOpen < 0L) return 0;
        if (!neighbourhoodReady(level, f.floor.getX() >> 4, f.floor.getZ() >> 4)) return 0;
        int done = 0;
        if (!f.nestSpread) {
            // 巣のまわりを 1 tick に 1 段ずつ(一度にやると 60 ms を超えた。11-20 測り)
            int r = t.nest.spreadRadius;
            corruptLayer(level, f.core, r, f.nestLayer - r, t.roots.plantShare);
            f.nestLayer++;
            if (f.nestLayer > 2 * r) f.nestSpread = true;
            data.changed();
            return done + 1;
        }
        if (f.route == null) {
            // 根の道を探す(1 tick に searchPerTick 個まで広げ、次の tick に続ける)
            RouteSearch s = GROW_SEARCH.computeIfAbsent(f.id, id -> new RouteSearch(level, f.floor));
            if (!s.step(level, Math.max(1, t.roots.searchPerTick))) return done + 1;
            s.applyTo(f);
            GROW_SEARCH.remove(f.id);
            FissureLedger.markRoute(data, f.route, f.routeHitsMouth);
            data.changed();
            done++;
        }
        long target = Math.min(f.route.length, sinceOpen / Math.max(1, t.roots.ticksPerCell));
        while (f.grown < target && done < budget) {
            BlockPos cell = BlockPos.of(f.route[f.grown]);
            if (level.getChunkSource().getChunkNow(cell.getX() >> 4, cell.getZ() >> 4) == null) break; // 読み込まれたら続き
            if (RingWards.guarded(level, cell)) break; // 押し返す道具の効く所で止まる
            BlockPos host = FissureLook.rootHost(level, cell);
            if (host != null && !RingWards.guarded(level, host)) {
                level.setBlock(host, FissureLook.state(FissureLook.Part.ROOT), FLAGS);
                f.placed.add(host.asLong());
            }
            corruptAround(level, cell, t.roots.corruptRadius, t.roots.plantShare);
            f.grown++;
            done++;
            data.changed();
        }
        // 口に着く時刻は地下の台帳だけが決める(追記 2。根の道は読み込まれた所の根の置き方だけ)
        return done;
    }

    /**
     * 根の道: 亀裂の床から洞窟の空気をたどり、空の見えるマス(洞窟の口)までのいちばん短い道。{@code searchCells} までに見つからない・
     * {@code maxLength} より長ければ、たどれた所のいちばん奥(同じなら高い方)まで。読み込まれていない所はたどらない。
     */
    static void buildRoute(ServerLevel level, FissureData.Fissure f) {
        RouteSearch s = new RouteSearch(level, f.floor);
        while (!s.step(level, Integer.MAX_VALUE)) {
        }
        s.applyTo(f);
    }

    /**
     * 根の道を探す A*(途中で止めて次の tick に続けられる。11-20: 1 回で探すと 20〜40 ms かかった)。地表へ向かう重み 1.5、
     * h = 列の地面の高さ − y(地面より上なら 0)。空気を全部たどる探し方は、大きな洞窟で上限を使い切って口に届かなかった。
     * 口に届かなければ、たどれた所のうち地表にいちばん近い所まで。
     */
    static final class RouteSearch {
        final long start;
        final Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
        final Long2IntOpenHashMap gScore = new Long2IntOpenHashMap();
        final java.util.PriorityQueue<long[]> open = new java.util.PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(b[1], a[1]));
        long mouth = Long.MIN_VALUE, best;
        int bestH, expanded;
        boolean done;

        RouteSearch(ServerLevel level, BlockPos floor) {
            start = floor.asLong();
            gScore.defaultReturnValue(Integer.MAX_VALUE);
            parent.put(start, start);
            gScore.put(start, 0);
            best = start;
            bestH = toSurface(level, start);
            open.add(new long[]{(long) (1.5D * bestH), 0L, start});
        }

        /** 最大 n 個広げる。終わったら true。 */
        boolean step(ServerLevel level, int n) {
            if (done) return true;
            FissureTable.Roots t = DesignData.fissure().roots;
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            int k0 = 0;
            while (k0++ < n) {
                if (open.isEmpty() || expanded >= t.searchCells) {
                    done = true;
                    return true;
                }
                long[] e = open.poll();
                long k = e[2];
                int gk = (int) e[1];
                if (gk != gScore.get(k)) continue; // 古い候補
                expanded++;
                if (k != start && isMouth(level, m.set(k))) {
                    mouth = k;
                    done = true;
                    return true;
                }
                int hk = toSurface(level, k);
                if (hk < bestH || hk == bestH && gk > gScore.get(best)) {
                    bestH = hk;
                    best = k;
                }
                if (gk >= t.maxLength) continue;
                for (Direction d : Direction.values()) {
                    m.set(k).move(d);
                    long nk = m.asLong();
                    if (gk + 1 >= gScore.get(nk) || !passable(level, m)) continue;
                    gScore.put(nk, gk + 1);
                    parent.put(nk, k);
                    open.add(new long[]{gk + 1 + (long) (1.5D * toSurface(level, nk)), gk + 1, nk});
                }
            }
            return false;
        }

        boolean hitsMouth() {
            return mouth != Long.MIN_VALUE;
        }

        void applyTo(FissureData.Fissure f) {
            long end = hitsMouth() ? mouth : best;
            LongArrayList path = new LongArrayList();
            for (long k = end; ; k = parent.get(k)) {
                path.add(k);
                if (k == start) break;
            }
            java.util.Collections.reverse(path);
            f.route = path.toLongArray();
            f.routeHitsMouth = hitsMouth();
        }
    }

    /** 列の地面(葉を除く)までの高さ(地面より上なら 0。読み込まれていなければ 0)。 */
    static int toSurface(ServerLevel level, long k) {
        int x = BlockPos.getX(k), z = BlockPos.getZ(k);
        LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (c == null) return 0;
        return Math.max(0, c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) - BlockPos.getY(k));
    }

    /**
     * まわり半径 r の、空気に面したブロックを汚染する(入口は菌糸の染み下り {@code MyceliumSeep.column} と同じ: 変換・覆い・植物)。
     * 押し返す道具の効く所・読み込まれていない所・液体は触らない。
     */
    /** 半径 r の球の、高さ center.y + dy の 1 段だけ汚染する。 */
    static int corruptLayer(ServerLevel level, BlockPos center, int r, int dy, double plantShare) {
        RandomSource random = level.getRandom();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int n = 0;
        int r2 = r * r - dy * dy;
        if (r2 < 0) return 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r2) continue;
                m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                if (m.getY() < level.getMinBuildHeight() || m.getY() >= level.getMaxBuildHeight()) continue;
                if (level.getChunkSource().getChunkNow(m.getX() >> 4, m.getZ() >> 4) == null) continue;
                BlockState s = level.getBlockState(m);
                if (s.isAir() || !s.getFluidState().isEmpty() || !facesAir(level, m)) continue;
                if (corruptOne(level, m.immutable(), s, random, plantShare)) n++;
            }
        }
        return n;
    }

    static int corruptAround(ServerLevel level, BlockPos center, int r, double plantShare) {
        RandomSource random = level.getRandom();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int n = 0;
        int r2 = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (m.getY() < level.getMinBuildHeight() || m.getY() >= level.getMaxBuildHeight()) continue;
                    if (level.getChunkSource().getChunkNow(m.getX() >> 4, m.getZ() >> 4) == null) continue;
                    BlockState s = level.getBlockState(m);
                    if (s.isAir() || !s.getFluidState().isEmpty() || !facesAir(level, m)) continue;
                    if (corruptOne(level, m.immutable(), s, random, plantShare)) n++;
                }
            }
        }
        return n;
    }

    private static boolean facesAir(ServerLevel level, BlockPos p) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            m.setWithOffset(p, d);
            if (level.getChunkSource().getChunkNow(m.getX() >> 4, m.getZ() >> 4) == null) continue;
            if (level.getBlockState(m).isAir()) return true;
        }
        return false;
    }

    /** 1 マス汚染する(できたら true)。 */
    public static boolean corruptOne(ServerLevel level, BlockPos at, BlockState state, RandomSource random, double plantShare) {
        if (RingWards.guarded(level, at)) return false;
        if (OvergrownLayerData.isCorruptedCell(level, at, state)) return false;
        if (!CorruptionRegistry.canCorrupt(level, at, state)) return false;
        BlockState corrupted = CorruptionRegistry.corrupt(level, at, state, random);
        boolean plants = plantShare > 0.0D && random.nextDouble() < plantShare;
        if (CorruptionRegistry.noChange(state, corrupted)) {
            boolean newly = OvergrownLayerData.takeNewlyCovered(at);
            if (plants && newly) {
                com.yayuy.overgrownvegetation.phase.CorruptionCounter.record(level, 1);
                growPlants(level, at, state);
            }
            return newly;
        }
        CorruptionRegistry.placeCorrupted(level, at, state, corrupted, FLAGS);
        if (plants) growPlants(level, at, corrupted);
        return true;
    }

    static void growPlants(ServerLevel level, BlockPos at, BlockState host) {
        if (!com.yayuy.overgrownvegetation.corruption.FloorGrowth.tryHorsetail(level, at)) {
            com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.growSurfacePlants(level, at, host,
                    com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.surfaceGrowthChance(level, at));
        }
    }

    // ---------------------------------------------------------------- 口からあふれる

    /** 口から地面へ根を這わせ、芽の点を足す。こなした数を返す。 */
    public static int overflow(ServerLevel level, FissureData data, FissureData.Fissure f, long now, int budget) {
        FissureTable.Mouth t = DesignData.fissure().mouth;
        BlockPos mouth = f.mouth();
        if (f.state != FissureData.State.OPEN || mouth == null || !f.reachedMouth()) return 0;
        if (!neighbourhoodReady(level, mouth.getX() >> 4, mouth.getZ() >> 4)) return 0;
        long elapsed = Math.max(0L, now - f.mouthAt);
        int done = 0;
        // 入口 B 吐き出す根(11-20 ユーザーが選んだ形): 口の外向きを決め、床に根を積もらせ、6 本の腕を扇に這わせる
        if (!f.moundDone) {
            f.outletYaw = outwardYaw(level, mouth);
            f.armDone = new int[Math.max(0, t.arms)];
            FissureLook.enqueue(level, FissureLook.mouthMound(moundBase(level, mouth), f.outletYaw, f.seed), f.placed);
            f.moundDone = true;
            data.changed();
            done++;
        }
        int step = (int) Math.min(64, elapsed / Math.max(1, t.ticksPerArmCell) + 1);
        for (int i = 0; i < f.armDone.length && done < budget; i++) {
            int[][] path = FissureLook.armPath(i, f.armDone.length, f.outletYaw, f.seed);
            int target = Math.min(path.length, step);
            while (f.armDone[i] < target && done < budget) {
                int k = f.armDone[i];
                int x = mouth.getX() + path[k][0], z = mouth.getZ() + path[k][1];
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) break; // 読み込まれたら続き
                BlockPos ground = groundAt(level, x, z);
                if (ground == null || Math.abs(ground.getY() - (moundBase(level, mouth).getY() - 1)) > t.maxStep + k / 2) {
                    f.armDone[i] = path.length; // 崖・水: この腕は終わり
                    break;
                }
                // 腕は地面に埋まる。口に近い 2 マスは上にも積もる。わきの地面はまばらに汚染(草の点)
                FissureLook.place(level, new FissureLook.Cell(ground, FissureLook.Part.SPILL), f.placed);
                if (k < 2) FissureLook.place(level, new FissureLook.Cell(ground.above(), FissureLook.Part.SPILL, FissureLook.Mode.PUT), f.placed);
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    if (FissureLook.hs(x + d.getStepX(), z + d.getStepZ(), f.seed + 13) % 10 < 4) {
                        BlockPos g = groundAt(level, x + d.getStepX(), z + d.getStepZ());
                        if (g != null) corruptOne(level, g, level.getBlockState(g), level.getRandom(), 0.5D);
                    }
                }
                f.armDone[i] = k + 1;
                done++;
                data.changed();
            }
        }
        // 地上の汚染の点(芽の点)。腕の先から
        int points = (int) Math.min(t.maxPoints, elapsed / Math.max(1, t.ticksPerPoint) + 1);
        while (f.pointsDone < points && done < budget) {
            int i = f.pointsDone % Math.max(1, f.armDone.length);
            BlockPos g = null;
            if (f.armDone.length > 0 && f.armDone[i] > 0) {
                int[][] path = FissureLook.armPath(i, f.armDone.length, f.outletYaw, f.seed);
                int[] p = path[Math.min(path.length, f.armDone[i]) - 1];
                g = groundAt(level, mouth.getX() + p[0], mouth.getZ() + p[1]);
            }
            if (g == null) g = groundAt(level, mouth.getX(), mouth.getZ());
            if (g != null && !RingWards.guarded(level, g)) com.yayuy.overgrownvegetation.spore.field.SporeSprouts.addPoint(level, g);
            f.pointsDone++;
            done++;
            data.changed();
        }
        return done;
    }

    /** 口のまわりの地面の上の高さ(穴の中ではなく、まわり 4 方向 2 マス先の地面の低い方から 2 番目 + 1)。積もる根と腕はこの高さから。 */
    static BlockPos moundBase(ServerLevel level, BlockPos mouth) {
        int[] hs = new int[4];
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos g = groundAt(level, mouth.getX() + d.getStepX() * 2, mouth.getZ() + d.getStepZ() * 2);
            if (g != null) hs[n++] = g.getY();
        }
        if (n == 0) return mouth;
        java.util.Arrays.sort(hs, 0, n);
        return new BlockPos(mouth.getX(), hs[Math.min(n - 1, 1)] + 1, mouth.getZ());
    }

    /** その列の地面のいちばん上(葉を除く)。読み込まれていない・水なら null。 */
    @Nullable
    static BlockPos groundAt(ServerLevel level, int x, int z) {
        LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (c == null) return null;
        int top = c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        BlockPos p = new BlockPos(x, top, z);
        if (!level.getBlockState(p).getFluidState().isEmpty()) return null;
        return p;
    }

    /**
     * 口の外向き(ラジアン): まわり 8 方向 4 マス先の地面がいちばん低い向き(丘の横穴なら坂の下)。どの向きもほぼ同じ高さ(平らな所の縦穴)なら NaN = 全周。
     */
    static double outwardYaw(ServerLevel level, BlockPos mouth) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        double best = Double.NaN;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2.0D * i / 8.0D;
            BlockPos g = groundAt(level, mouth.getX() + (int) Math.round(Math.cos(a) * 4), mouth.getZ() + (int) Math.round(Math.sin(a) * 4));
            if (g == null) continue;
            if (g.getY() < lo) {
                lo = g.getY();
                best = a;
            }
            hi = Math.max(hi, g.getY());
        }
        return hi - lo <= 1 ? Double.NaN : best;
    }

    /**
     * 洞窟の口か: 空が見えて、まわり(東西南北 3 マス先)の地面のいちばん低い所 − 1 より上。縦穴の底は空が見えても口にしない(地面の高さまで上る)。
     */
    static boolean isMouth(ServerLevel level, BlockPos p) {
        if (!skyOpen(level, p)) return false;
        int g = Integer.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int x = p.getX() + d.getStepX() * 3, z = p.getZ() + d.getStepZ() * 3;
            LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (c != null) g = Math.min(g, c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15));
        }
        return g == Integer.MAX_VALUE || p.getY() >= g;
    }

    /** 空が見えるマスか(高さ地図で。光の計算を待たない)。 */
    static boolean skyOpen(ServerLevel level, BlockPos p) {
        LevelChunk c = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        return c != null && p.getY() > c.getHeight(Heightmap.Types.WORLD_SURFACE, p.getX() & 15, p.getZ() & 15);
    }

    /** 口から角度 ang に k マス先の地面のいちばん上(葉を除く)。読み込まれていない・水なら null。 */
    @Nullable
    static BlockPos armGround(ServerLevel level, BlockPos mouth, double ang, int k) {
        int x = mouth.getX() + (int) Math.round(Math.cos(ang) * k), z = mouth.getZ() + (int) Math.round(Math.sin(ang) * k);
        LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (c == null) return null;
        int top = c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        BlockPos p = new BlockPos(x, top, z);
        BlockState s = level.getBlockState(p);
        if (!s.getFluidState().isEmpty()) return null;
        return p;
    }

    // ---------------------------------------------------------------- 枯れ

    /** 器官の芯のブロックが壊れた({@code OrganCoreBlock.onRemove} から)。その芯の亀裂を枯らす。 */
    public static void onCoreRemoved(ServerLevel level, BlockPos pos) {
        if (level.dimension() != Level.OVERWORLD) return;
        FissureData data = FissureData.get(level);
        for (FissureData.Fissure f : data.fissures()) {
            if (f.state == FissureData.State.OPEN && pos.equals(f.core)) {
                kill(level, data, f, "芯が壊れた");
                return;
            }
        }
        FissureLedger.onBorerCoreRemoved(level, data, pos); // 根を送り込む器官の芯
    }

    /** 枯らす(根が止まる。置いた根・巣は少しずつ枯れの姿へ)。 */
    public static void kill(ServerLevel level, FissureData data, FissureData.Fissure f, String why) {
        if (f.state == FissureData.State.DEAD) return;
        f.state = FissureData.State.DEAD;
        f.diedAt = PhaseData.clockNow(level);
        f.withered = 0;
        FissureTable.Wither w = DesignData.fissure().wither;
        long points = w.points > 0 ? PhaseData.get(level).removePoints(w.points) : 0L;
        double cut = takeBudget(level, w.budget + ForestDirector.cap(level) * w.budgetCapShare);
        data.changed();
        ForestDirector.get(level).note(String.format(Locale.ROOT, "亀裂 #%d が枯れた(%s)。ポイント −%d・予算 −%.0f", f.id, why, points, cut));
    }

    /** 森の予算から amount 引く(足りなければ 0 まで)。引いた量を返す。 */
    static double takeBudget(ServerLevel level, double amount) {
        if (amount <= 0.0D) return 0.0D;
        ForestDirector dir = ForestDirector.get(level);
        double cut = Math.min(dir.available(level), amount);
        if (cut > 0.0D) dir.tryPay(level, cut);
        return cut;
    }

    /** 枯れの姿へ少しずつ(読み込まれていない所で止まり、読み込まれたら続き)。 */
    public static int witherSome(ServerLevel level, FissureData data, FissureData.Fissure f, int budget) {
        int n = 0;
        while (f.withered < f.placed.size() && n < budget) {
            BlockPos p = BlockPos.of(f.placed.getLong(f.withered));
            if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) break;
            BlockState s = level.getBlockState(p);
            BlockState w = FissureLook.withered(s);
            if (w != s) level.setBlock(p, w, FLAGS);
            f.withered++;
            n++;
        }
        if (n > 0) data.changed();
        return n;
    }

    // ---------------------------------------------------------------- 汲み上げ器を覚える

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        boolean any = false;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof LeyPumpBlockEntity) {
                any = true;
                break;
            }
        }
        FissureData data = FissureData.get(level);
        if (!any && data.pumps().isEmpty()) return;
        rememberPumps(level, data, chunk);
    }

    /** そのチャンクの汲み上げ器を覚え直す(無くなった物は忘れる)。 */
    static void rememberPumps(ServerLevel level, FissureData data, LevelChunk chunk) {
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        LongArrayList gone = new LongArrayList();
        for (long p : data.pumps()) {
            if (BlockPos.getX(p) >> 4 == cx && BlockPos.getZ(p) >> 4 == cz && !(chunk.getBlockEntity(BlockPos.of(p)) instanceof LeyPumpBlockEntity)) gone.add(p);
        }
        for (long p : gone) data.removePump(BlockPos.of(p));
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof LeyPumpBlockEntity) data.addPump(be.getBlockPos());
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD && event.getPlacedBlock().getBlock() instanceof LeyPumpBlock) {
            FissureData.get(level).addPump(event.getPos());
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD && event.getState().getBlock() instanceof LeyPumpBlock) {
            FissureData.get(level).removePump(event.getPos());
        }
    }
}
