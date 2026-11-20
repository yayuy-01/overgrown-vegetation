package com.yayuy.overgrownvegetation.corruption.cave;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.corruption.fissure.FissureData;
import com.yayuy.overgrownvegetation.corruption.fissure.FissureLook;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.spore.field.CaveMouths;
import com.yayuy.overgrownvegetation.spore.field.MyceliumSeep;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 汚染された洞窟の飾り(2026-11-20 洞窟の作り。ユーザーが Fable の案から選んだ形): 柱1 ねじれた太い柱(編んだ蔦・登れない・水ぶくれが付く)・
 * 嚢2 大きな水ぶくれ(床か天井。まわりに菌糸の床)・縦穴の壁を下りる太い縄(2 マス幅・二股)・コの字の縁から床まで宙に垂れる縄(床でとぐろ)。
 * どれも芯なし(ユーザーの芯の決まり)。
 *
 * <p>巡回(崖の汚染と同じ作り): プレイヤーのまわりのチャンクを少しずつ見て、地下が汚染されたチャンク(菌糸の染み下りが底まで済んだ・
 * 亀裂の台帳の追いつきが済んだ)に 1 回だけ飾る(済みの印は {@link Data})。出すかは根元のブロックが汚染済みか
 * ({@link OvergrownLayerData#isCorruptedCell})で決めるので、どの汚染の道でも同じに出る。地表から {@code surfaceKeep} より下だけ。
 * 押し返す道具の効く所・亀裂の芯のそばには出さない。置くのは {@link FissureLook} の置く列(1 tick の時間に上限)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CaveDecor {
    private CaveDecor() {
    }

    /** 直近の結果(調べのコマンド用)。 */
    static volatile String last = "";

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        CaveTable t = DesignData.cave();
        if (!t.enabled || CorruptionSwitch.isOff()) return;
        if (level.players().isEmpty()) return;
        if (!TickBudget.mayStart(PerfJob.CAVE_DECOR)) return;
        long start = TickBudget.begin();
        int done = 0;
        try {
            if (level.getGameTime() % Math.max(1, t.intervalTicks) == 0) done += sweep(level, t, level.getRandom());
            done += FissureLook.drain(System.nanoTime() + (long) (t.placeMillis * 1_000_000.0D));
        } finally {
            TickBudget.end(PerfJob.CAVE_DECOR, start, done);
        }
    }

    /** 1 回の巡回。飾ったチャンクの数。 */
    static int sweep(ServerLevel level, CaveTable t, RandomSource random) {
        Data data = Data.get(level);
        int processed = 0;
        int r = Math.max(0, t.radiusChunks);
        for (int i = 0; i < Math.max(1, t.chunksPerPass) * 6 && processed < t.chunksPerPass; i++) {
            ServerPlayer p = level.players().get(random.nextInt(level.players().size()));
            if (p.isSpectator()) continue;
            ChunkPos pc = p.chunkPosition();
            int cx = pc.x + random.nextInt(2 * r + 1) - r, cz = pc.z + random.nextInt(2 * r + 1) - r;
            long key = ChunkPos.asLong(cx, cz);
            if (data.done(key) || !ready(level, cx, cz)) continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (!undergroundCorrupted(level, chunk)) continue;
            last = decorateChunk(level, chunk, random, Options.of(t)).describe(chunk.getPos());
            data.mark(key);
            processed++;
        }
        return processed;
    }

    static boolean ready(ServerLevel level, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) return false;
        return true;
    }

    /** 地下の汚染が済んだチャンクか(巡回を絞るだけ。出すかは根元のブロックで決める)。 */
    static boolean undergroundCorrupted(ServerLevel level, LevelChunk chunk) {
        if (MyceliumSeep.progressPpm(chunk) >= MyceliumSeep.SCALE) return true;
        FissureData.Cell c = FissureData.get(level).ledger().get(chunk.getPos().toLong());
        return c != null && c.applied >= 256;
    }

    /** 1 チャンクの飾りの割合(コマンド・テストは全部 1 にする)。 */
    public record Options(double pillar, double blister, int ropes) {
        static Options of(CaveTable t) {
            return new Options(t.pillarChance, t.blisterChance, t.ropesPerChunk);
        }
    }

    public static final class Result {
        public int pillars, blisters, ropes, cells;
        /** 飾りを決めるのにかかった時間(置くのは置く列で別)。 */
        public long nanos;
        /** 撮影・調べ用: 柱・水ぶくれ・縦穴の縄・宙に垂れる縄の根元(無ければ null)。 */
        public BlockPos pillarAt, blisterAt, shaftAt, hangAt;

        String describe(ChunkPos c) {
            return String.format(java.util.Locale.ROOT, "チャンク %d, %d: 柱 %d・水ぶくれ %d・縄 %d(%d マス・%.1f ms)・柱 %s・水ぶくれ %s・縦穴の縄 %s・宙の縄 %s", c.x, c.z, pillars, blisters,
                    ropes, cells, nanos / 1e6, s(pillarAt), s(blisterAt), s(shaftAt), s(hangAt));
        }

        private static String s(BlockPos p) {
            return p == null ? "-" : p.toShortString();
        }
    }

    /** 洞窟の空気の縦の 1 区切り(床の空気の高さ y0・高さ h。h 段目が天井の固いマス)。 */
    record Run(int x, int z, int y0, int h, boolean floorCorrupted, boolean ceilCorrupted) {
    }

    /** チャンクの洞窟を飾る(置く列に入れる)。 */
    public static Result decorateChunk(ServerLevel level, LevelChunk chunk, RandomSource random, Options o) {
        CaveTable t = DesignData.cave();
        Result res = new Result();
        long t0 = System.nanoTime();
        List<Run> runs = runs(level, chunk, t);
        if (runs.isEmpty()) {
            res.nanos = System.nanoTime() - t0;
            return res;
        }
        List<BlockPos> cores = cores(level);
        List<FissureLook.Cell> cells = new ArrayList<>();
        // 1 柱
        if (random.nextDouble() < o.pillar()) {
            List<Run> c = new ArrayList<>();
            for (Run r : runs) if (r.floorCorrupted() && r.h() >= t.pillarMinHeight && r.h() <= t.pillarMaxHeight && roomy(level, r) && clear(cores, r, t)) c.add(r);
            if (!c.isEmpty()) {
                Run pr = c.get(random.nextInt(c.size()));
                pillar(cells, pr);
                res.pillars++;
                res.pillarAt = new BlockPos(pr.x(), pr.y0(), pr.z());
            }
        }
        // 2 水ぶくれの塊(床か天井)
        if (random.nextDouble() < o.blister()) {
            List<Run> c = new ArrayList<>();
            for (Run r : runs) if ((r.floorCorrupted() || r.ceilCorrupted()) && r.h() >= 4 && clear(cores, r, t)) c.add(r);
            if (!c.isEmpty()) {
                Run r = c.get(random.nextInt(c.size()));
                boolean ceiling = r.ceilCorrupted() && r.h() >= t.blisterCeilingMinHeight && (!r.floorCorrupted() || random.nextBoolean());
                blister(cells, r, ceiling, random.nextInt());
                res.blisters++;
                res.blisterAt = new BlockPos(r.x(), ceiling ? r.y0() + r.h() - 1 : r.y0(), r.z());
            }
        }
        // 3 縄(縦穴の壁・コの字の縁から宙に)
        int ropes = 0;
        List<Run> shuffled = new ArrayList<>(runs);
        java.util.Collections.shuffle(shuffled, new java.util.Random(random.nextLong()));
        for (Run r : shuffled) {
            if (ropes >= o.ropes()) break;
            if (!r.ceilCorrupted() && !r.floorCorrupted() || !clear(cores, r, t)) continue;
            Direction wall = r.h() >= t.shaftMinHeight ? wallSide(level, r) : null;
            if (wall != null) {
                shaftRope(cells, r, wall, random.nextInt());
                ropes++;
                if (res.shaftAt == null) res.shaftAt = new BlockPos(r.x(), r.y0(), r.z());
                continue;
            }
            Direction open = r.h() >= t.hangMinHeight ? overhangEdge(level, r) : null;
            if (open != null) {
                hangingRope(cells, r, open, random.nextDouble() < t.partialRopeChance, random.nextInt());
                ropes++;
                if (res.hangAt == null) res.hangAt = new BlockPos(r.x(), r.y0(), r.z());
            }
        }
        res.ropes = ropes;
        // 地表から surfaceKeep より下だけ
        List<FissureLook.Cell> keep = new ArrayList<>();
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap tops = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        for (FissureLook.Cell c : cells) {
            int x = c.pos().getX(), z = c.pos().getZ();
            LevelChunk lc = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (lc == null) continue;
            long col = ChunkPos.asLong(x, z);
            int top = tops.computeIfAbsent(col, k -> CaveMouths.groundTop(lc, x, lc.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15), z,
                    level.getMinBuildHeight()));
            if (c.pos().getY() <= top - t.surfaceKeep) keep.add(c);
        }
        res.cells = keep.size();
        res.nanos = System.nanoTime() - t0;
        FissureLook.enqueue(level, keep, null);
        return res;
    }

    /** チャンクの洞窟の空気の区切り(2 列おき)。床か天井の固いマスが汚染済みかも見る。 */
    static List<Run> runs(ServerLevel level, LevelChunk chunk, CaveTable t) {
        List<Run> out = new ArrayList<>();
        int minY = level.getMinBuildHeight() + 2;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
        for (int lx = 1; lx < 16; lx += 2) {
            for (int lz = 1; lz < 16; lz += 2) {
                int x = bx + lx, z = bz + lz;
                int ground = CaveMouths.groundTop(chunk, x, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz), z, level.getMinBuildHeight());
                int top = ground - t.surfaceKeep;
                int start = Integer.MIN_VALUE;
                for (int y = minY; y <= top; y++) {
                    BlockState s = chunk.getBlockState(m.set(x, y, z));
                    boolean air = s.isAir();
                    if (air && start == Integer.MIN_VALUE) {
                        BlockState below = chunk.getBlockState(m.set(x, y - 1, z));
                        if (below.isAir() || !below.getFluidState().isEmpty() || !below.blocksMotion()) continue;
                        start = y;
                    } else if (!air && start != Integer.MIN_VALUE) {
                        if (!s.getFluidState().isEmpty()) {
                            start = Integer.MIN_VALUE;
                            continue;
                        }
                        BlockPos fl = new BlockPos(x, start - 1, z), ce = new BlockPos(x, y, z);
                        boolean fc = OvergrownLayerData.isCorruptedCell(level, fl, level.getBlockState(fl));
                        boolean cc = OvergrownLayerData.isCorruptedCell(level, ce, s);
                        if (y - start >= 4 && (fc || cc)) out.add(new Run(x, z, start, y - start, fc, cc));
                        start = Integer.MIN_VALUE;
                    }
                }
            }
        }
        return out;
    }

    /** 亀裂の巣・送り込む器官の芯。 */
    static List<BlockPos> cores(ServerLevel level) {
        List<BlockPos> out = new ArrayList<>();
        for (FissureData.Fissure f : FissureData.get(level).fissures()) {
            if (f.core != null) out.add(f.core);
            for (FissureData.Borer b : f.borers) if (b.core != null) out.add(b.core);
        }
        return out;
    }

    static boolean clear(List<BlockPos> cores, Run r, CaveTable t) {
        double c2 = (double) t.coreClear * t.coreClear;
        for (BlockPos p : cores) {
            double dx = p.getX() - r.x(), dy = p.getY() - (r.y0() + r.h() / 2.0D), dz = p.getZ() - r.z();
            if (dx * dx + dz * dz + dy * dy < c2 + r.h() * r.h() / 4.0D) return false;
        }
        return true;
    }

    /** 柱が入る広さか(真ん中の高さで 5×5、床と天井の下で 3×3 が空気。11-20: 床と天井も 5×5 にすると自然の洞窟ではほぼ出なかった)。 */
    static boolean roomy(ServerLevel level, Run r) {
        int mid = r.y0() + r.h() / 2;
        int[] ys = {r.y0(), mid, r.y0() + r.h() - 1};
        for (int y : ys) for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) {
            if (y != mid && (Math.abs(a) == 2 || Math.abs(b) == 2)) continue;
            BlockPos p = new BlockPos(r.x() + a, y, r.z() + b);
            if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null || !level.getBlockState(p).isAir()) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- 形(模型 Cave.java に合わせた)

    /** 柱1 ねじれた太い柱: 3 本の蔦がより合わさって床から天井へ(床と天井で半径 2、間は 1.15)。3 段おきに芯の蔦。水ぶくれが 6 つ横に付く。 */
    static void pillar(List<FissureLook.Cell> out, Run r) {
        int h = r.h();
        for (int i = 0; i < 3; i++) {
            for (int y = 0; y < h; y++) {
                double th = i * Math.PI * 2 / 3 + y * 0.72, rad = y == 0 || y == h - 1 ? 2.0 : 1.15;
                int px = r.x() + (int) Math.round(Math.cos(th) * rad), pz = r.z() + (int) Math.round(Math.sin(th) * rad);
                out.add(new FissureLook.Cell(new BlockPos(px, r.y0() + y, pz), FissureLook.Part.BRAID, FissureLook.Mode.PUT));
                if (y > 0) { // 斜めに切れないよう、下の段とつなぐ
                    double t0 = th - 0.72, r0 = y - 1 == 0 ? 2.0 : 1.15;
                    int qx = r.x() + (int) Math.round(Math.cos(t0) * r0), qz = r.z() + (int) Math.round(Math.sin(t0) * r0);
                    if (qx != px && qz != pz) out.add(new FissureLook.Cell(new BlockPos(px, r.y0() + y - 1, qz), FissureLook.Part.BRAID, FissureLook.Mode.PUT));
                }
            }
        }
        for (int y = 1; y < h - 1; y += 3) out.add(new FissureLook.Cell(new BlockPos(r.x(), r.y0() + y, r.z()), FissureLook.Part.BRAID, FissureLook.Mode.PUT));
        int[][] s = {{2, 2, 0}, {0, 4, 2}, {0, 3, 2}, {2, 6, 1}, {1, 7, 2}, {-2, 5, 1}};
        for (int[] p : s) {
            int y = (int) Math.round(p[1] * (h - 1) / 8.0D); // 模型の高さ 9 を洞窟の高さに伸ばす
            out.add(new FissureLook.Cell(new BlockPos(r.x() + p[0], r.y0() + Math.max(1, Math.min(h - 2, y)), r.z() + p[2]), FissureLook.Part.BLISTER, FissureLook.Mode.PUT));
        }
    }

    /** 嚢2 大きな水ぶくれ: 床なら半径 2.4 の丸い塊 + 横に 2 つ・まわり半径 4 の菌糸の床。天井なら下へ 5 段すぼまる塊・まわり半径 3.4 の菌糸。 */
    static void blister(List<FissureLook.Cell> out, Run r, boolean ceiling, int seed) {
        int x0 = r.x(), z0 = r.z();
        if (ceiling) {
            int cy = r.y0() + r.h(); // 天井の固いマス
            disk(out, x0, cy, z0, 3.4, seed);
            double[] rr = {1.7, 1.7, 1.3, 0.8, 0.5};
            for (int k = 0; k < rr.length; k++) for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++)
                if (a * a + b * b <= rr[k] * rr[k] + 0.3) out.add(new FissureLook.Cell(new BlockPos(x0 + a, cy - 1 - k, z0 + b), FissureLook.Part.BLISTER, FissureLook.Mode.PUT));
        } else {
            int fy = r.y0();
            disk(out, x0, fy - 1, z0, 4.2, seed);
            for (int x = -3; x <= 3; x++) for (int y = 0; y <= 3; y++) for (int z = -3; z <= 3; z++) {
                double d = x * x / (2.4 * 2.4) + y * y / (2.3 * 2.3) + z * z / (2.4 * 2.4);
                if (d <= 1.0) out.add(new FissureLook.Cell(new BlockPos(x0 + x, fy + y, z0 + z), FissureLook.Part.BLISTER, FissureLook.Mode.PUT));
            }
            out.add(new FissureLook.Cell(new BlockPos(x0 + 3, fy, z0 + 2), FissureLook.Part.BLISTER, FissureLook.Mode.PUT));
            out.add(new FissureLook.Cell(new BlockPos(x0 - 3, fy, z0 + 1), FissureLook.Part.BLISTER, FissureLook.Mode.PUT));
        }
    }

    /** 菌糸の床の円(縁は少し欠ける)。固いマスだけ置き換える。 */
    static void disk(List<FissureLook.Cell> out, int cx, int y, int cz, double r, int seed) {
        for (int x = (int) (cx - r) - 1; x <= cx + r + 1; x++) for (int z = (int) (cz - r) - 1; z <= cz + r + 1; z++) {
            double d = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)) + ((FissureLook.hs(x, z, seed) % 1000) / 1000.0D - 0.5D) * 1.4D;
            if (d <= r) out.add(new FissureLook.Cell(new BlockPos(x, y, z), FissureLook.Part.HYPHA, FissureLook.Mode.EMBED));
        }
    }

    /** 区切りの横に、高さの 7 割以上で固い壁がある向き(縦穴の壁)。無ければ null。 */
    static Direction wallSide(ServerLevel level, Run r) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int solid = 0;
            for (int y = 0; y < r.h(); y++) {
                BlockPos p = new BlockPos(r.x() + d.getStepX(), r.y0() + y, r.z() + d.getStepZ());
                if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) return null;
                BlockState s = level.getBlockState(p);
                if (!s.isAir() && s.blocksMotion()) solid++;
            }
            if (solid >= r.h() * 0.7D) return d;
        }
        return null;
    }

    /** 区切りの天井の下が張り出しの縁か(横のどこかで天井より上まで空気が続く)。続く向きを返す。無ければ null。 */
    static Direction overhangEdge(ServerLevel level, Run r) {
        int ceil = r.y0() + r.h();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = new BlockPos(r.x() + d.getStepX(), ceil, r.z() + d.getStepZ());
            if (level.getChunkSource().getChunkNow(side.getX() >> 4, side.getZ() >> 4) == null) continue;
            if (level.getBlockState(side).isAir() && level.getBlockState(side.above()).isAir() && level.getBlockState(side.below()).isAir()) return d;
        }
        return null;
    }

    /** 縦穴の壁の太い縄(崖と同じ作り: 2 マス幅・左右にゆらぐ・5 割過ぎで二股・短い方は 8 割5分で終わる)。壁に沿った空気に置く。 */
    static void shaftRope(List<FissureLook.Cell> out, Run r, Direction wall, int seed) {
        Direction along = wall.getClockWise();
        int len = r.h();
        for (int k = 0; k < len; k++) {
            int y = r.y0() + r.h() - 1 - k;
            int sway = (int) Math.round(1.3D * Math.sin(k * 0.33D + (seed & 7)));
            int drift = k > len * 0.55D ? (k - (int) (len * 0.55D) + 2) / 4 : 0;
            BlockPos a = new BlockPos(r.x(), y, r.z()).relative(along, sway - drift);
            out.add(new FissureLook.Cell(a, FissureLook.Part.ROOT, FissureLook.Mode.PUT));
            if (k < len * 0.85D) out.add(new FissureLook.Cell(new BlockPos(r.x(), y, r.z()).relative(along, sway + 1 + drift), FissureLook.Part.ROOT, FissureLook.Mode.PUT));
        }
    }

    /** コの字の縁から床まで宙に垂れる縄(床でとぐろ)。途中で終わる縄は 5 割5分の高さで止まる。 */
    static void hangingRope(List<FissureLook.Cell> out, Run r, Direction open, boolean partial, int seed) {
        int end = partial ? r.y0() + (int) (r.h() * 0.45D) : r.y0();
        for (int y = r.y0() + r.h() - 1; y >= end; y--) out.add(new FissureLook.Cell(new BlockPos(r.x(), y, r.z()), FissureLook.Part.ROOT, FissureLook.Mode.PUT));
        if (!partial) {
            Direction s = open.getClockWise();
            BlockPos f = new BlockPos(r.x(), r.y0(), r.z());
            out.add(new FissureLook.Cell(f.relative(open), FissureLook.Part.ROOT, FissureLook.Mode.PUT));
            out.add(new FissureLook.Cell(f.relative(open).relative(s), FissureLook.Part.ROOT, FissureLook.Mode.PUT));
            out.add(new FissureLook.Cell(f.relative(s.getOpposite()).relative(open), FissureLook.Part.ROOT, FissureLook.Mode.PUT));
        }
    }

    // ---------------------------------------------------------------- 済みの印

    public static final class Data extends SavedData {
        static final String ID = "overgrown_vegetation_cave_decor";
        static final SavedData.Factory<Data> FACTORY = new SavedData.Factory<>(Data::new, (tag, reg) -> load(tag), null);
        private final LongOpenHashSet done = new LongOpenHashSet();

        public static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(FACTORY, ID);
        }

        boolean done(long chunk) {
            return done.contains(chunk);
        }

        void mark(long chunk) {
            if (done.add(chunk)) setDirty();
        }

        public void clear(long chunk) {
            if (done.remove(chunk)) setDirty();
        }

        public int size() {
            return done.size();
        }

        static Data load(CompoundTag tag) {
            Data d = new Data();
            for (long k : tag.getLongArray("Done")) d.done.add(k);
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLongArray("Done", done.toLongArray());
            return tag;
        }
    }
}
