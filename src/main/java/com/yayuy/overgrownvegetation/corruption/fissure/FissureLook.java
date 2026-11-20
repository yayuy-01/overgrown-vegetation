package com.yayuy.overgrownvegetation.corruption.fissure;

import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import com.yayuy.overgrownvegetation.organ.OrganTissueBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 亀裂・巣・根・口からあふれる根の<b>見た目の口</b>(2026-11-20。形は【仮】)。後で Fable(自然環境とシェーダーの調整)とユーザーが詰めるので、
 * 形は {@link #NEST}({@link NestShape})と {@link #state} の差し替えだけで変わるように、仕組み({@link FissureWorld})から切り離してある。
 *
 * <p>今の仮の形: 洞窟の床に長さ {@code nest.length}・深さ {@code nest.depth} の V 字の切れ目(幅 1)。いちばん下に器官の芯、その上に肉の栓、
 * 横と下に殻(= 芯を覆う器官。掘って芯を出す)。切れ目の壁を絡む根が這い上がる。根は洞窟の床・壁に埋まった絡む根、
 * 口からあふれる根は地面に埋まった絡む根。どれも器官のブロック(新しい絵は無い)。
 */
public final class FissureLook {
    private FissureLook() {
    }

    /** 置くものの役。素材は {@link #state} が決める。 */
    public enum Part {
        /** 掘る(空気)。 */
        CARVE,
        /** 芯(壊すと枯れる)。 */
        CORE,
        /** 芯の上の栓(肉)。 */
        COVER,
        /** 芯の横と下の殻。 */
        SHELL,
        /** 根(洞窟の床・壁・切れ目の壁)。 */
        ROOT,
        /** 口から地面へあふれる根。 */
        SPILL
    }

    public record Cell(BlockPos pos, Part part) {
    }

    /** 巣の形: 洞窟の床の空気のマス {@code floor}(真下が床)から、置く順のマスの列を返す。最後の CORE が芯。 */
    @FunctionalInterface
    public interface NestShape {
        List<Cell> build(BlockPos floor, Direction.Axis axis, RandomSource random, FissureTable.Nest t);
    }

    /** 今の巣の形(差し替える所)。 */
    public static NestShape NEST = FissureLook::slitNest;

    /** 根を送り込む器官の形(差し替える所。追記 2)。 */
    public static NestShape BORER = FissureLook::borer;

    /**
     * 仮の形: 洞窟の床に座る肉の球根(2 段)の根元に芯を半分埋め、横と下を殻で覆う。殻の下から絡む根が岩の中へ斜めに 4 本、5 段ずつ潜る。
     */
    static List<Cell> borer(BlockPos floor, Direction.Axis axis, RandomSource random, FissureTable.Nest t) {
        List<Cell> out = new ArrayList<>();
        BlockPos core = floor.below();
        for (Direction d : Direction.Plane.HORIZONTAL) out.add(new Cell(core.relative(d), Part.SHELL));
        out.add(new Cell(core.below(), Part.SHELL));
        out.add(new Cell(floor, Part.COVER));
        out.add(new Cell(floor.above(), Part.COVER));
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

    /** 役の素材(差し替える所)。 */
    public static BlockState state(Part part) {
        return switch (part) {
            case CARVE -> Blocks.AIR.defaultBlockState();
            case CORE -> OrganBlocks.ORGAN_CORE.get().defaultBlockState();
            case COVER -> OrganBlocks.ORGAN_FLESH.get().defaultBlockState();
            case SHELL -> OrganBlocks.ORGAN_SHELL.get().defaultBlockState();
            case ROOT, SPILL -> OrganBlocks.TANGLED_ROOT.get().defaultBlockState();
        };
    }

    /** 枯れの姿(器官のブロックの WITHERED)。替えられなければ同じものを返す。 */
    public static BlockState withered(BlockState s) {
        if (s.hasProperty(OrganTissueBlock.WITHERED) && !s.getValue(OrganTissueBlock.WITHERED)) return s.setValue(OrganTissueBlock.WITHERED, true);
        return s;
    }

    /** 仮の形: V 字の切れ目 + 底の芯と、それを覆う栓と殻 + 切れ目の壁を這う根。 */
    static List<Cell> slitNest(BlockPos floor, Direction.Axis axis, RandomSource random, FissureTable.Nest t) {
        List<Cell> out = new ArrayList<>();
        int depth = Math.max(3, t.depth);
        int half = Math.max(1, t.length / 2);
        Direction along = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        Direction side = along.getClockWise();
        BlockPos bottom = floor.below(depth);
        for (int s = -half; s <= half; s++) {
            int d = s == 0 ? depth - 2 : Math.max(1, (int) Math.round((depth - 1) * (1.0D - Math.abs(s) / (half + 1.0D))));
            BlockPos top = floor.relative(along, s);
            for (int k = 1; k <= d; k++) {
                BlockPos c = top.below(k);
                out.add(new Cell(c, Part.CARVE));
                // 切れ目の壁を這い上がる根(片側ずつ・半分くらい)
                if (k >= 2 && random.nextInt(2) == 0) out.add(new Cell(c.relative(random.nextBoolean() ? side : side.getOpposite()), Part.ROOT));
            }
        }
        // 芯を覆う器官: 横と下の殻、上の栓
        for (Direction dir : Direction.values()) {
            if (dir == Direction.UP) continue;
            out.add(new Cell(bottom.relative(dir), Part.SHELL));
        }
        out.add(new Cell(bottom.above(), Part.COVER));
        out.add(new Cell(bottom, Part.CORE));
        return out;
    }

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
        return s.isCollisionShapeFullBlock(level, p);
    }
}
