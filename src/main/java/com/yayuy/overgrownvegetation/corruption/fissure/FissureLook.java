package com.yayuy.overgrownvegetation.corruption.fissure;

import com.yayuy.overgrownvegetation.corruption.pushback.RingWards;
import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import com.yayuy.overgrownvegetation.organ.OrganTissueBlock;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 亀裂・巣・根を送り込む器官・口からあふれる根・洞窟の飾りの<b>見た目の口</b>。形は {@link #NEST}・{@link #BORER}({@link NestShape})と
 * {@link #state} の差し替えだけで変わるように、仕組み({@link FissureWorld}・{@link FissureLedger})から切り離してある。
 *
 * <p>2026-11-20 洞窟の作り(ユーザーが Fable の案から選んだ形。正本は side-session-notes の cave-structures-look-report.md と模型 Cave.java):
 * <ul>
 *     <li>巣1 縫い目: 割れ目は開いたまま。肋が 2 マスおきに割れ目をまたいで縫う。芯の上は膜 1 枚(破れば届く)。床に光る筋が放射状に這う。</li>
 *     <li>送1 根株: 高さ 7 マスの立った球根に殻の笠。支柱根が弧を描いて 6 方向の床へ刺さる。芯は球根の横腹。岩へ斜めに潜る根。</li>
 *     <li>入口 B 吐き出す根: 口の床に根が積もり、6 本の腕が扇に広がって地面に埋まる。腕のわきに汚染した草の点。</li>
 * </ul>
 * 模型の 3 つの置き方をそのまま持つ({@link Mode}): SET = 必ず置く・PUT = 空いた所だけ・EMBED = 固いマスだけ置き換える。
 * 置くのは待ち行列({@link #enqueue})で、1 tick の時間に上限がある(芯だけは呼ぶ側がすぐ置く)。
 */
public final class FissureLook {
    private FissureLook() {
    }

    /** 置くものの役。素材は {@link #state} が決める。 */
    public enum Part {
        CARVE, CORE, COVER, SHELL, ROOT, SPILL, RIB, MEMBRANE, VEIN, FLESH, HYPHA, BRAID, BLISTER
    }

    /** 置き方(模型の set / put / embed)。 */
    public enum Mode {
        /** 必ず置く(壊せない物・BlockEntity・液体の源泉は除く)。 */
        SET,
        /** 空いた所(空気・入れ替わる物)だけ。 */
        PUT,
        /** 固いマスだけ置き換える(床・壁・天井に埋める)。 */
        EMBED
    }

    public record Cell(BlockPos pos, Part part, Mode mode) {
        public Cell(BlockPos pos, Part part) {
            this(pos, part, defaultMode(part));
        }
    }

    static Mode defaultMode(Part p) {
        return switch (p) {
            case ROOT, SPILL, HYPHA -> Mode.EMBED;
            case VEIN -> Mode.PUT;
            default -> Mode.SET;
        };
    }

    /** 巣・送り込む器官の形: 洞窟の床の空気のマス {@code floor}(真下が床)から、置くマスの列を返す。CORE が芯。 */
    @FunctionalInterface
    public interface NestShape {
        List<Cell> build(BlockPos floor, Direction.Axis axis, RandomSource random, FissureTable.Nest t);
    }

    /** 巣の形(差し替える所)。 */
    public static NestShape NEST = FissureLook::seamNest;
    /** 根を送り込む器官の形(差し替える所)。洞窟が低くて入らないときは {@link #BORER_SMALL}。 */
    public static NestShape BORER = FissureLook::rootStock;
    public static NestShape BORER_SMALL = FissureLook::smallBorer;

    /** 役の素材(差し替える所)。 */
    public static BlockState state(Part part) {
        return switch (part) {
            case CARVE -> Blocks.AIR.defaultBlockState();
            case CORE -> OrganBlocks.ORGAN_CORE.get().defaultBlockState();
            case COVER, FLESH -> OrganBlocks.ORGAN_FLESH.get().defaultBlockState();
            case SHELL -> OrganBlocks.ORGAN_SHELL.get().defaultBlockState();
            case ROOT, SPILL -> OrganBlocks.TANGLED_ROOT.get().defaultBlockState();
            case RIB -> OrganBlocks.ORGAN_RIB.get().defaultBlockState();
            case MEMBRANE -> OrganBlocks.ORGAN_MEMBRANE.get().defaultBlockState();
            case HYPHA -> OrganBlocks.ORGAN_HYPHA_FLOOR.get().defaultBlockState();
            case BRAID -> OrganBlocks.WOVEN_VINE.get().defaultBlockState();
            case BLISTER -> OrganBlocks.CAVE_BLISTER.get().defaultBlockState();
            case VEIN -> OrganBlocks.ORGAN_VEIN.get().defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
        };
    }

    /** 枯れの姿(器官のブロックの WITHERED)。替えられなければ同じものを返す。 */
    public static BlockState withered(BlockState s) {
        if (s.hasProperty(OrganTissueBlock.WITHERED) && !s.getValue(OrganTissueBlock.WITHERED)) return s.setValue(OrganTissueBlock.WITHERED, true);
        return s;
    }

    // ---------------------------------------------------------------- 置く

    /**
     * 1 マス置く(置けたら true)。読み込まれていない・押し返す道具の効く所・壊せない物・BlockEntity・液体の中は置かない。
     * 置いたマスは {@code placed} に控える(枯らすとき。掘ったマスは控えない)。
     */
    public static boolean place(ServerLevel level, Cell c, @Nullable LongArrayList placed) {
        BlockPos p = c.pos();
        if (p.getY() < level.getMinBuildHeight() || p.getY() >= level.getMaxBuildHeight()) return false;
        if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) return false;
        if (RingWards.guarded(level, p)) return false;
        BlockState cur = level.getBlockState(p);
        if (cur.getDestroySpeed(level, p) < 0.0F || cur.hasBlockEntity() || !cur.getFluidState().isEmpty()) return false;
        if (cur.is(OrganBlocks.ORGAN_CORE.get())) return false; // ほかの芯を潰さない
        switch (c.mode()) {
            case SET -> {
                if (c.part() == Part.CARVE && cur.isAir()) return false;
            }
            case PUT -> {
                if (!cur.isAir() && !cur.canBeReplaced()) return false;
            }
            case EMBED -> {
                if (!hostable(level, p)) return false;
            }
        }
        if (c.part() == Part.VEIN && !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) return false;
        if (c.part() == Part.SPILL && c.mode() == Mode.PUT && !level.getBlockState(p.below()).blocksMotion()) return false; // 積もる根は下が固い所だけ
        level.setBlock(p, state(c.part()), Block.UPDATE_ALL);
        if (placed != null && c.part() != Part.CARVE) placed.add(p.asLong());
        return true;
    }

    private record Job(ServerLevel level, List<Cell> cells, @Nullable LongArrayList placed, int[] next) {
    }

    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();

    /** 置く列に入れる(1 tick の時間の上限の中で置く)。保存しない(止めたら残りは置かれない)。 */
    public static void enqueue(ServerLevel level, List<Cell> cells, @Nullable LongArrayList placed) {
        if (!cells.isEmpty()) QUEUE.add(new Job(level, cells, placed, new int[1]));
    }

    /** 列を締め切りまで置く。置いた数を返す。 */
    public static int drain(long deadline) {
        int n = 0;
        while (!QUEUE.isEmpty()) {
            Job j = QUEUE.peek();
            while (j.next()[0] < j.cells().size()) {
                if (n > 0 && (n & 7) == 0 && System.nanoTime() >= deadline) return n;
                place(j.level(), j.cells().get(j.next()[0]++), j.placed());
                n++;
            }
            QUEUE.poll();
        }
        return n;
    }

    /** 列を全部置く(コマンド・テスト)。 */
    public static int drainAll() {
        return drain(Long.MAX_VALUE);
    }

    public static int queued() {
        int n = 0;
        for (Job j : QUEUE) n += j.cells().size() - j.next()[0];
        return n;
    }

    /** 芯と、芯のまわり(殻・膜・掘る所)はすぐ置き、ほかは列へ。芯の場所を返す(置けなければ null)。 */
    @Nullable
    public static BlockPos placeWithCore(ServerLevel level, List<Cell> cells, LongArrayList placed) {
        BlockPos core = null;
        List<Cell> rest = new ArrayList<>();
        for (Cell c : cells) {
            boolean now = c.part() == Part.CARVE || c.part() == Part.SHELL || c.part() == Part.MEMBRANE || c.part() == Part.COVER;
            if (now) place(level, c, placed);
            else if (c.part() != Part.CORE) rest.add(c);
        }
        for (Cell c : cells) {
            if (c.part() != Part.CORE) continue;
            // 芯は SET で必ず(壊せない物・液体の中なら置けない)
            if (place(level, c, placed)) core = c.pos().immutable();
        }
        enqueue(level, rest, placed);
        return core;
    }

    // ---------------------------------------------------------------- 巣1 縫い目

    /** 決まった乱数(座標と種)。 */
    public static int hs(int a, int b, int seed) {
        int h = a * 73856093 ^ b * 19349663 ^ seed * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return h & 0x7fffffff;
    }

    /**
     * 巣1 縫い目(模型 Cave.nest の style 0)。割れ目は長さ {@code nest.length}・深さ {@code nest.depth - 1} の V 字。芯は床から {@code nest.depth} 下、
     * 芯の上は膜 1 枚、横と下は殻。肋が 2 マスおきに割れ目をまたぐ(両脇の床の上と真上)。割れ目の両端から床に根、両脇へ光る筋が 4 本。
     */
    static List<Cell> seamNest(BlockPos floor, Direction.Axis axis, RandomSource random, FissureTable.Nest t) {
        List<Cell> out = new ArrayList<>();
        int depth = Math.max(4, t.depth);
        int half = Math.max(2, t.length / 2);
        int seed = random.nextInt();
        Direction along = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        Direction side = along.getClockWise();
        BlockPos core = floor.below(depth);
        for (int s = -half; s <= half; s++) {
            int d = Math.max(1, (int) Math.round((depth - 1) * (1.0D - Math.abs(s) / (half + 1.0D))));
            BlockPos top = floor.relative(along, s);
            for (int k = 1; k <= d; k++) {
                out.add(new Cell(top.below(k), Part.CARVE));
                if (k >= 2 && hs(s, k, 3) % 2 == 0) out.add(new Cell(top.below(k).relative(side.getOpposite()), Part.ROOT));
            }
        }
        for (Direction dir : Direction.values()) {
            if (dir == Direction.UP) continue;
            out.add(new Cell(core.relative(dir), Part.SHELL));
        }
        out.add(new Cell(core.above(), Part.MEMBRANE));
        out.add(new Cell(core, Part.CORE));
        // 肋: 割れ目をまたいで縫う(両脇の床の上と、割れ目の真上 1 段)
        for (int s = -(half - 1); s <= half - 1; s += 2) {
            BlockPos p = floor.relative(along, s);
            out.add(new Cell(p.relative(side.getOpposite()), Part.RIB, Mode.PUT));
            out.add(new Cell(p.above(), Part.RIB, Mode.PUT));
            out.add(new Cell(p.relative(side), Part.RIB, Mode.PUT));
        }
        // 床の根(割れ目の両端から外へ)と光る筋(両脇へ放射状)
        run(out, floor.relative(along, -(half + 1)).below(), along.getOpposite(), side, 5, Part.ROOT, seed + 5);
        run(out, floor.relative(along, half + 1).below(), along, side, 5, Part.ROOT, seed + 6);
        run(out, floor.relative(along, -2).relative(side.getOpposite(), 2), side.getOpposite(), along, 5, Part.VEIN, seed + 7);
        run(out, floor.relative(along, 2).relative(side.getOpposite(), 2), side.getOpposite(), along, 4, Part.VEIN, seed + 8);
        run(out, floor.relative(along, -3).relative(side, 2), side, along, 5, Part.VEIN, seed + 9);
        run(out, floor.relative(along, 3).relative(side, 2), side, along, 4, Part.VEIN, seed + 10);
        return out;
    }

    /** 模型の rootRun: dir へ len マス、3 回に 1 回 wob の向きへ 1 マスずれる。 */
    private static void run(List<Cell> out, BlockPos start, Direction dir, Direction wob, int len, Part part, int seed) {
        BlockPos p = start;
        for (int i = 0; i < len; i++) {
            out.add(new Cell(p, part));
            if (hs(p.getX(), p.getZ(), seed) % 3 == 0) {
                p = p.relative((hs(p.getX(), p.getZ(), seed + 1) & 1) == 0 ? wob : wob.getOpposite());
                out.add(new Cell(p, part));
            }
            p = p.relative(dir);
        }
    }

    // ---------------------------------------------------------------- 送1 根株

    /** 送1 が入る高さと広さか(真ん中の柱 0〜7 段と、笠の 3×3 が空気)。 */
    public static boolean rootStockFits(BlockGetter level, BlockPos floor) {
        for (int y = 0; y <= 7; y++) if (!level.getBlockState(floor.above(y)).isAir()) return false;
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) if (!level.getBlockState(floor.offset(a, 6, b)).isAir()) return false;
        return true;
    }

    /**
     * 送1 根株(模型 Cave.borer の style 0)。球根(肉)は床の上 0〜4 段、芯は球根の横腹(+1, 2, +1)、肋の首(5 段)に殻の笠(6・7 段)。
     * 支柱根が 4 方向と斜め 2 方向へ弧を描いて床に刺さり、4 方向の根は岩の中へ斜めに潜る。
     */
    static List<Cell> rootStock(BlockPos floor, Direction.Axis axis, RandomSource random, FissureTable.Nest t) {
        List<Cell> out = new ArrayList<>();
        double rx = 1.6, ry = 2.3, rz = 1.6;
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 4; y++) for (int z = -2; z <= 2; z++) {
            double d = x * x / (rx * rx) + (y - 2) * (y - 2) / (ry * ry) + z * z / (rz * rz);
            if (d <= 1.0) out.add(new Cell(floor.offset(x, y, z), Part.FLESH, Mode.PUT));
        }
        out.add(new Cell(floor.above(5), Part.RIB));
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) out.add(new Cell(floor.offset(a, 6, b), Part.SHELL, Mode.PUT));
        out.add(new Cell(floor.above(7), Part.SHELL, Mode.PUT));
        int[][] arc = {{2, 4}, {3, 4}, {4, 3}, {4, 2}, {5, 1}, {5, 0}};
        int[][] dive = {{5, -1}, {6, -2}, {6, -3}, {7, -4}, {7, -5}};
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int[] p : arc) out.add(new Cell(floor.relative(d, p[0]).above(p[1]), Part.ROOT, Mode.PUT));
            for (int[] p : dive) out.add(new Cell(floor.relative(d, p[0]).above(p[1]), Part.ROOT, Mode.EMBED));
        }
        int[][] a2 = {{1, 4}, {2, 4}, {3, 3}, {3, 2}, {4, 1}, {4, 0}};
        int[][] diag = {{-1, 1}, {1, -1}};
        for (int[] d : diag) {
            for (int[] p : a2) out.add(new Cell(floor.offset(d[0] * p[0], p[1], d[1] * p[0]), Part.ROOT, Mode.PUT));
            out.add(new Cell(floor.offset(d[0] * 4, -1, d[1] * 4), Part.ROOT, Mode.EMBED));
        }
        out.add(new Cell(floor.offset(1, 2, 1), Part.CORE));
        return out;
    }

    /** 洞窟が低くて送1 が入らないときの小さい形(11-20 の仮の形): 床の肉の球根 2 段・芯は根元・殻・斜めに潜る根 4 本。 */
    static List<Cell> smallBorer(BlockPos floor, Direction.Axis axis, RandomSource random, FissureTable.Nest t) {
        List<Cell> out = new ArrayList<>();
        BlockPos core = floor.below();
        for (Direction d : Direction.Plane.HORIZONTAL) out.add(new Cell(core.relative(d), Part.SHELL));
        out.add(new Cell(core.below(), Part.SHELL));
        out.add(new Cell(floor, Part.COVER, Mode.PUT));
        out.add(new Cell(floor.above(), Part.COVER, Mode.PUT));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = core.below();
            for (int k = 1; k <= 5; k++) {
                p = p.below();
                if (k % 2 == 0) p = p.relative(d);
                out.add(new Cell(p, Part.ROOT));
            }
        }
        out.add(new Cell(core, Part.CORE));
        return out;
    }

    // ---------------------------------------------------------------- 入口 B 吐き出す根

    /** 腕の向き(模型の 6 本の扇。度・口の外向きからの角)。外向きが分からない(縦穴)ときは全周に 60 度おき。 */
    static final double[] ARM_DEG = {-75, -48, -20, 8, 34, 62};

    /**
     * 口の床に積もる根(模型 Cave.mouth の v == 1)。外向き {@code yaw}(ラジアン。NaN = 全周)の手前 −1〜外 3・横 ±3 の床の上に根、
     * 真ん中は 2 段目もまばらに。下が固い所にだけ置く。
     */
    public static List<Cell> mouthMound(BlockPos mouth, double yaw, int seed) {
        List<Cell> out = new ArrayList<>();
        double fx = Double.isNaN(yaw) ? 0.0D : Math.cos(yaw), fz = Double.isNaN(yaw) ? 0.0D : Math.sin(yaw);
        int r = Double.isNaN(yaw) ? 2 : 3;
        for (int a = -r; a <= r; a++) for (int b = -1; b <= 3; b++) {
            int x = Double.isNaN(yaw) ? a : (int) Math.round(-fz * a + fx * b), z = Double.isNaN(yaw) ? b - 1 : (int) Math.round(fx * a + fz * b);
            if (Double.isNaN(yaw) && Math.abs(z) > r) continue;
            out.add(new Cell(mouth.offset(x, 0, z), Part.SPILL, Mode.PUT));
            if (Math.abs(a) <= 1 && b <= 2 && hs(a, b, seed + 3) % 2 == 0) out.add(new Cell(mouth.offset(x, 1, z), Part.SPILL, Mode.PUT));
        }
        return out;
    }

    /**
     * 腕 i の道(口からの水平のずれ [dx, dz] の列。長さ 7〜11)。模型どおり、扇の角から出て 1 マスごとに少し曲がる。
     */
    public static int[][] armPath(int arm, int arms, double yaw, int seed) {
        double base = Double.isNaN(yaw) ? Math.PI * 2.0D * arm / Math.max(1, arms) : yaw + Math.toRadians(ARM_DEG[arm % ARM_DEG.length]);
        int len = 7 + hs(arm, 1, seed + 7) % 5;
        int[][] out = new int[len][];
        double a = base, px = Math.cos(base) * 2.0D, pz = Math.sin(base) * 2.0D;
        // 模型は外向き(+z)へ 3 進んでから曲がる: 外向きの成分を足す
        double ox = Double.isNaN(yaw) ? Math.cos(base) : Math.cos(yaw), oz = Double.isNaN(yaw) ? Math.sin(base) : Math.sin(yaw);
        px += ox * 1.0D;
        pz += oz * 1.0D;
        for (int k = 0; k < len; k++) {
            a += ((hs(arm, k, seed + 11) % 1000) / 1000.0D - 0.5D) * 0.5D;
            px += Math.cos(a);
            pz += Math.sin(a);
            out[k] = new int[]{(int) Math.round(px), (int) Math.round(pz)};
        }
        return out;
    }

    // ---------------------------------------------------------------- 共通

    /**
     * 根の道の空気のマス {@code air} に根を埋める床・壁のマス(床 → 横 → 天井の順で、根を埋めてよい物)。無ければ null。
     */
    static BlockPos rootHost(BlockGetter level, BlockPos air) {
        BlockPos below = air.below();
        if (hostable(level, below)) return below;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = air.relative(d);
            if (hostable(level, p)) return p;
        }
        BlockPos above = air.above();
        return hostable(level, above) ? above : null;
    }

    /** 根に替えてよい固いブロックか(液体・BlockEntity・壊せない物・器官のブロックは替えない)。 */
    static boolean hostable(BlockGetter level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir() || !s.getFluidState().isEmpty() || s.hasBlockEntity()) return false;
        if (s.getDestroySpeed(level, p) < 0.0F) return false;
        if (s.getBlock() instanceof OrganTissueBlock || s.is(OrganBlocks.ORGAN_CORE.get())) return false;
        if (s.is(OrganBlocks.WOVEN_VINE.get()) || s.is(OrganBlocks.CAVE_BLISTER.get())) return false;
        return s.isCollisionShapeFullBlock(level, p);
    }
}
