package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionArch;
import com.yayuy.overgrownvegetation.corruption.CorruptionRootTip;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 斜めの根のアーチと、地面・天井から突き出す根の先端(2026-10-09、ユーザー指定)。
 * <ul>
 *     <li>アーチはどの水平の角度でも、1 マスの細さでも面でつながる。南北・東西のアーチは今までと同じマス。</li>
 *     <li>根の先端は面でつながり、根元から先へ細り、先は 1 マスの原木。地上は上へ、天井は下へ向かい、斜め(真上・真下ではない)。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class RootArchGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(RootArchGameTests.class);
        }

        private Registration() {
        }
    }

    /** 斜めのアーチの中心線は、細さ 0 でも隣どうしが水平に面で接する。南北・東西は今までの置き方と同じマス。 */
    @GameTest(template = "gametest_empty7")
    public static void archCentersConnectAtAnyAngleAndKeepAxisAlignedSame(GameTestHelper helper) {
        RandomSource random = RandomSource.create(20261009L);
        for (int n = 0; n < 200; n++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int span = 3 + random.nextInt(28);
            BlockPos from = new BlockPos(0, 64, 0);
            BlockPos to = new BlockPos((int) Math.round(Math.cos(angle) * span), 64 + random.nextInt(7) - 3,
                    (int) Math.round(Math.sin(angle) * span));
            List<int[]> centers = CorruptionArch.centers(from, to, 1 + random.nextInt(8), 0);
            for (int i = 1; i < centers.size(); i++) {
                int[] a = centers.get(i - 1);
                int[] b = centers.get(i);
                if (Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]) != 1) {
                    helper.fail("細いアーチが面で接していない: 角度 " + Math.round(Math.toDegrees(angle)) + " 度・" + i + " 歩目");
                    return;
                }
            }
        }
        // 南北・東西の太いアーチ(つなぎを足さない)の中心線は今まで(i 歩目 = 起点 + 向き × i、高さは線形補間 + 正弦の持ち上げ)と同じ。
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(0, 66, -12);
        List<int[]> centers = CorruptionArch.centers(from, to, 5, 1);
        if (centers.size() != 13) {
            helper.fail("南北のアーチの歩数が変わった: " + centers.size());
            return;
        }
        for (int i = 0; i <= 12; i++) {
            double s = i / 12.0D;
            int y = (int) Math.round(64 + (66 - 64) * s) + (int) Math.round(5 * Math.sin(Math.PI * s));
            int[] c = centers.get(i);
            if (c[0] != 0 || c[2] != -i || c[1] != y || c[3] != 0) {
                helper.fail("南北のアーチの " + i + " 歩目が今までと違う: " + c[0] + "," + c[1] + "," + c[2]);
                return;
            }
        }
        helper.succeed();
    }

    /** 根の先端の形: 面でつながる・先へ細る・先はフェンス・地上は上へ・天井は下へ・斜め。 */
    @GameTest(template = "gametest_empty7")
    public static void rootTipPlansAreConnectedTaperedAndTilted(GameTestHelper helper) {
        RandomSource random = RandomSource.create(7L);
        for (int n = 0; n < 400; n++) {
            boolean hanging = n % 2 == 1;
            CorruptionRootTip.Shape shape = hanging ? CorruptionRootTip.rollHanging(random) : CorruptionRootTip.rollGround(random);
            if (shape.length() < CorruptionRootTip.MIN_LENGTH || shape.length() > CorruptionRootTip.MAX_LENGTH
                    || shape.baseRadius() < 1 || shape.baseRadius() > 2) {
                helper.fail("根の先端の大きさが決めた範囲の外: " + shape);
                return;
            }
            List<CorruptionRootTip.Cell> cells = CorruptionRootTip.plan(0.5D, 64.5D, 0.5D, shape);
            String what = (hanging ? "天井" : "地上") + "の根 " + n + " 本目 " + shape;
            int lastRadius = Integer.MAX_VALUE;
            for (int i = 0; i < cells.size(); i++) {
                CorruptionRootTip.Cell c = cells.get(i);
                if (i > 0) {
                    CorruptionRootTip.Cell p = cells.get(i - 1);
                    if (Math.abs(p.x() - c.x()) + Math.abs(p.y() - c.y()) + Math.abs(p.z() - c.z()) != 1) {
                        helper.fail(what + ": " + i + " マス目が前と面で接していない");
                        return;
                    }
                }
                if (c.radius() > lastRadius) {
                    helper.fail(what + ": " + i + " マス目で太くなった");
                    return;
                }
                lastRadius = c.radius();
            }
            CorruptionRootTip.Cell first = cells.get(0);
            CorruptionRootTip.Cell last = cells.get(cells.size() - 1);
            int rise = last.y() - first.y();
            int run = Math.abs(last.x() - first.x()) + Math.abs(last.z() - first.z());
            if (last.radius() != 0) {
                helper.fail(what + ": 先が 1 マスの細さで終わっていない(半径 " + last.radius() + ")");
                return;
            }
            if (hanging ? rise >= 0 : rise <= 0) {
                helper.fail(what + ": 向きが逆(高さの差 " + rise + ")");
                return;
            }
            if (run < 1) {
                helper.fail(what + ": 真っ直ぐ上下で斜めになっていない");
                return;
            }
        }
        helper.succeed();
    }

    /** テスト場に斜めのアーチ(細さ 0)を実際に置く: 置いたブロックが面でつながっている。 */
    @GameTest(template = "gametest_empty7")
    public static void diagonalArchPlacesConnectedBlocks(GameTestHelper helper) {
        LongOpenHashSet touched = new LongOpenHashSet();
        BlockState log = ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();

        // 斜めのアーチ(床 y=1 の上、x=0,z=0 の角から x=5,z=4 へ)。
        List<BlockPos> arch = new ArrayList<>();
        BlockPos from = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos to = helper.absolutePos(new BlockPos(5, 2, 4));
        // 持ち上げ 2: 斜めのつなぎと高さのつなぎの両方を見る。
        if (!CorruptionArch.build(helper.getLevel(), from, to, 2, 0, log, touched, null, arch)) {
            helper.fail("斜めのアーチを置けなかった");
            return;
        }
        if (!connected(arch)) {
            helper.fail("斜めのアーチのブロックがつながっていない: " + arch.size() + " 個");
            return;
        }
        helper.succeed();
    }

    /** テスト場に地上の向きの短い根の先端を置く: 先は原木で、地面より上へ出る。 */
    @GameTest(template = "gametest_empty7")
    public static void groundRootTipPlacesLogTipAboveGround(GameTestHelper helper) {
        LongOpenHashSet touched = new LongOpenHashSet();
        BlockState log = ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
        // 地上の根(床から斜めに立ち上がる短い根)。
        CorruptionRootTip.Shape ground = new CorruptionRootTip.Shape(4, 1, Math.toRadians(45.0D), 40.0D, 55.0D);
        BlockPos g = helper.absolutePos(new BlockPos(1, 1, 1));
        List<CorruptionRootTip.Cell> gCells = CorruptionRootTip.plan(g.getX() + 0.5D, g.getY() - 0.5D, g.getZ() + 0.5D, ground);
        List<BlockPos> gShell = new ArrayList<>();
        CorruptionRootTip.place(helper.getLevel(), gCells, log, touched, gShell);
        CorruptionRootTip.Cell gTip = gCells.get(gCells.size() - 1);
        BlockPos gTipPos = new BlockPos(gTip.x(), gTip.y(), gTip.z());
        if (!helper.getLevel().getBlockState(gTipPos).is(ModBlocks.GIANT_OVERGROWN_LOG.get())) {
            helper.fail("地上の根の先が原木でない: " + helper.getLevel().getBlockState(gTipPos));
            return;
        }
        if (gTip.y() <= g.getY()) {
            helper.fail("地上の根が地面より上へ出ていない");
            return;
        }
        helper.succeed();
    }

    /** テスト場に天井の向きの短い根の先端を置く: 先は原木で、天井より下へ垂れる。 */
    @GameTest(template = "gametest_empty7")
    public static void hangingRootTipPlacesLogTipBelowCeiling(GameTestHelper helper) {
        LongOpenHashSet touched = new LongOpenHashSet();
        BlockState log = ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
        // 天井の根(高い所の石から斜めに垂れる短い根)。
        BlockPos ceiling = helper.absolutePos(new BlockPos(5, 6, 5));
        helper.getLevel().setBlock(ceiling, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        CorruptionRootTip.Shape hanging = new CorruptionRootTip.Shape(4, 1, Math.toRadians(225.0D), -35.0D, -45.0D); // 床(y=1)に届かない浅さ
        List<CorruptionRootTip.Cell> hCells = CorruptionRootTip.plan(ceiling.getX() + 0.5D, ceiling.getY() + 0.5D, ceiling.getZ() + 0.5D, hanging);
        List<BlockPos> hShell = new ArrayList<>();
        CorruptionRootTip.place(helper.getLevel(), hCells, log, touched, hShell);
        CorruptionRootTip.Cell hTip = hCells.get(hCells.size() - 1);
        BlockPos hTipPos = new BlockPos(hTip.x(), hTip.y(), hTip.z());
        if (!helper.getLevel().getBlockState(hTipPos).is(ModBlocks.GIANT_OVERGROWN_LOG.get())) {
            helper.fail("天井の根の先が原木でない: " + helper.getLevel().getBlockState(hTipPos));
            return;
        }
        if (hTip.y() >= ceiling.getY()) {
            helper.fail("天井の根が垂れ下がっていない");
            return;
        }
        helper.succeed();
    }

    /** 置いたブロックの集まりが面でつながった 1 つの塊か。 */
    private static boolean connected(List<BlockPos> blocks) {
        if (blocks.isEmpty()) {
            return false;
        }
        Set<BlockPos> all = new HashSet<>(blocks);
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> stack = new ArrayList<>();
        stack.add(blocks.get(0));
        seen.add(blocks.get(0));
        while (!stack.isEmpty()) {
            BlockPos p = stack.remove(stack.size() - 1);
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                BlockPos q = p.relative(d);
                if (all.contains(q) && seen.add(q)) {
                    stack.add(q);
                }
            }
        }
        return seen.size() == all.size();
    }

    private RootArchGameTests() {
    }
}
