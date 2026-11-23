package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.BranchJointBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.worldgen.BranchJoints;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 枝の継ぎ目(2026-11-01): 原木の隣に置くとその向きの腕が出て、原木を除くと引っ込む / 階段状の枝の列から隙間埋めと先細りの計画が期待どおりに出る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BranchJointGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterBranchJointGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(BranchJointGameTests.class);
        }

        private Registration() {
        }
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void jointArmsFollowNeighbourLogs(GameTestHelper helper) {
        BlockPos joint = new BlockPos(3, 3, 3);
        BlockPos east = joint.east();
        BlockPos below = joint.below();
        // 原木を東と下に置いてから継ぎ目を置く(置いた時点で隣を見る)
        helper.setBlock(east, ModBlocks.OVERGROWN_LOG.get());
        helper.setBlock(below, ModBlocks.OVERGROWN_LOG.get());
        helper.setBlock(joint.north(), ModBlocks.OVERGROWN_LEAVES.get()); // 葉には繋がらない
        BlockState placed = BranchJointBlock.connectedState(helper.getLevel(), helper.absolutePos(joint),
                ModBlocks.OVERGROWN_BRANCH.get().defaultBlockState());
        helper.setBlock(joint, placed);
        helper.runAfterDelay(2, () -> {
            BlockState s = helper.getBlockState(joint);
            check(helper, s.getValue(BranchJointBlock.EAST) && s.getValue(BranchJointBlock.DOWN), "東・下の腕が出ていない: " + s);
            check(helper, !s.getValue(BranchJointBlock.NORTH) && !s.getValue(BranchJointBlock.UP)
                    && !s.getValue(BranchJointBlock.WEST) && !s.getValue(BranchJointBlock.SOUTH), "原木の無い向きに腕がある: " + s);
            // 東の原木を除く → 東の腕が引っ込む。西に継ぎ目を足す → 西の腕が出る(継ぎ目どうしも繋がる)
            helper.setBlock(east, Blocks.AIR);
            helper.setBlock(joint.west(), ModBlocks.OVERGROWN_BRANCH.get());
            helper.runAfterDelay(2, () -> {
                BlockState t = helper.getBlockState(joint);
                check(helper, !t.getValue(BranchJointBlock.EAST), "原木を除いても東の腕が残っている: " + t);
                check(helper, t.getValue(BranchJointBlock.WEST), "隣の継ぎ目へ腕が出ていない: " + t);
                check(helper, t.getValue(BranchJointBlock.DOWN), "下の腕が消えた: " + t);
                helper.succeed();
            });
        });
    }

    /** 水の中に生成で置いた継ぎ目は水没になる(置く前に水を読む。Codex の指摘 2026-11-01)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void placedJointKeepsWater(GameTestHelper helper) {
        BlockPos at = new BlockPos(3, 2, 3);
        for (BlockPos side : List.of(at.north(), at.south(), at.east(), at.west())) {
            helper.setBlock(side, Blocks.STONE); // 水を 1 マスに囲う
        }
        helper.setBlock(at, Blocks.WATER);
        BlockPos abs = helper.absolutePos(at);
        BranchJoints.Plan plan = new BranchJoints.Plan(List.of(), List.of(new BranchJoints.Joint(abs, true)));
        List<BlockPos> placed = BranchJoints.place(helper.getLevel(), plan, ModBlocks.OVERGROWN_LOG.get().defaultBlockState(),
                ModBlocks.OVERGROWN_BRANCH.get().defaultBlockState(), (p, s) -> helper.getLevel().setBlock(p, s, 3));
        BranchJoints.reconnect(helper.getLevel(), placed);
        BlockState s = helper.getBlockState(at);
        check(helper, s.is(ModBlocks.OVERGROWN_BRANCH.get()) && s.getValue(BranchJointBlock.WATERLOGGED), "水の中の継ぎ目が水没していない: " + s);
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void planFillsStairGapsAndTapersTip(GameTestHelper helper) {
        // 幹 (0,0,0) から東へ 1 歩ごとに横と上へ 4 マス進む枝(辺で接する階段)
        List<BlockPos> path = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 1, 0), new BlockPos(2, 2, 0),
                new BlockPos(3, 3, 0), new BlockPos(4, 4, 0));
        BranchJoints.Plan plan = BranchJoints.plan(path, true, true, p -> true, p -> false);
        check(helper, plan.logs().equals(List.of(new BlockPos(1, 1, 0), new BlockPos(2, 2, 0))), "原木のマスが違う: " + plan.logs());
        Set<String> joints = plan.joints().stream().map(j -> j.pos().toShortString() + (j.thick() ? " 太" : " 細")).collect(Collectors.toSet());
        // 先細り: 最後から 2 番目が太い・最後が細い。隙間: 各段の低い方 = 次のマスの真下(太い)
        Set<String> expect = Set.of("3, 3, 0 太", "4, 4, 0 細",
                "1, 0, 0 太", "2, 1, 0 太", "3, 2, 0 太", "4, 3, 0 太");
        check(helper, joints.equals(expect), "継ぎ目が違う: " + joints + " 期待 " + expect);

        // 幹の上の段がもう幹(木)なら、最初の隙間は埋めない。長さ 2 の枝は最後だけ細い
        List<BlockPos> shortPath = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 1, 0), new BlockPos(2, 2, 0));
        BranchJoints.Plan p2 = BranchJoints.plan(shortPath, true, true, p -> true, p -> p.equals(new BlockPos(0, 1, 0)));
        Set<String> j2 = p2.joints().stream().map(j -> j.pos().toShortString() + (j.thick() ? " 太" : " 細")).collect(Collectors.toSet());
        check(helper, p2.logs().equals(List.of(new BlockPos(1, 1, 0))), "短い枝の原木が違う: " + p2.logs());
        check(helper, j2.equals(Set.of("2, 2, 0 細", "2, 1, 0 太")), "短い枝の継ぎ目が違う: " + j2);

        // 角で接する斜め(北東へ)は L 字に 2 マス、低い段で。低い方が塞がっていれば上の道
        List<BlockPos> corner = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
        BranchJoints.Plan p3 = BranchJoints.plan(corner, false, true, p -> true, p -> false);
        check(helper, p3.joints().size() == 2 && p3.joints().stream().allMatch(j -> j.pos().getY() == 0 && j.thick())
                && p3.joints().stream().anyMatch(j -> j.pos().equals(new BlockPos(1, 0, 1))), "角の隙間が違う: " + p3.joints());
        BranchJoints.Plan p4 = BranchJoints.plan(corner, false, true, p -> p.getY() != 0, p -> false);
        check(helper, p4.joints().size() == 2 && p4.joints().stream().noneMatch(j -> j.pos().getY() == 0)
                && p4.joints().stream().anyMatch(j -> j.pos().equals(new BlockPos(0, 1, 0)) || j.pos().equals(new BlockPos(1, 1, 0))
                || j.pos().equals(new BlockPos(0, 1, 1))), "低い道が塞がったときの角の隙間が違う: " + p4.joints());

        // 切ると何も置かない(原木のまま)
        BranchJoints.Plan off = BranchJoints.plan(path, false, false, p -> true, p -> false);
        check(helper, off.joints().isEmpty() && off.logs().size() == 4, "切っても継ぎ目がある: " + off);
        helper.succeed();
    }

    private static void check(GameTestHelper helper, boolean ok, String message) {
        if (!ok) {
            helper.fail(message);
        }
    }

    private BranchJointGameTests() {
    }
}
