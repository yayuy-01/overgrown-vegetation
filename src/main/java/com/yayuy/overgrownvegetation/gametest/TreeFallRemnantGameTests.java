package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.SwampTreeFall;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 元の森の木を消したあとに、木に付いていた物が空中に残らない(2026-10-16、ユーザー報告
 * 「木を消す処理で消された際、垂れ蔦が空中に浮いてしまう。汚染された下草なども浮いていた」)。
 *
 * <ul>
 *     <li>蔦(リアナ)は端の原木が消えたら、副アンカーごと消える。</li>
 *     <li>葉の下にぶら下がっていた汚染された下草は消える。</li>
 *     <li>ほかの固いもの(ここでは石)にも付いている下草は残る。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class TreeFallRemnantGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterTreeFallRemnantGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(TreeFallRemnantGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BlockPos STUMP = new BlockPos(3, 2, 3);
    private static final BlockPos TOP_LOG = new BlockPos(3, 4, 3);
    private static final BlockPos LIANA = new BlockPos(2, 3, 3);
    private static final BlockPos LIANA_SECONDARY = new BlockPos(1, 3, 3);
    private static final BlockPos HANGING_GROWTH = new BlockPos(2, 4, 2);
    private static final BlockPos ANCHORED_GROWTH = new BlockPos(5, 5, 3);

    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void removedTreeLeavesNothingFloating(GameTestHelper helper) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.swampTreeFall.get() || !cfg.jungleTreeFall.get()) {
            helper.succeed(); // 設定で切ってある(木を消さない)
            return;
        }
        ServerLevel level = helper.getLevel();
        // 木: 原木 3 段 + 上の葉(減衰しないよう距離 1)。形状更新を出さずに置く。
        for (int y = 2; y <= 4; y++) {
            place(helper, new BlockPos(3, y, 3), Blocks.OAK_LOG.defaultBlockState());
        }
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        for (BlockPos p : List.of(new BlockPos(3, 5, 3), new BlockPos(2, 5, 3), new BlockPos(4, 5, 3),
                new BlockPos(3, 5, 2), new BlockPos(3, 5, 4), new BlockPos(2, 5, 2))) {
            place(helper, p, leaf);
        }
        BlockState growth = ModBlocks.OVERGROWN_GROWTH.get().defaultBlockState();
        place(helper, HANGING_GROWTH, growth);                  // 葉 (2,5,2) の下にだけ付いている
        place(helper, new BlockPos(5, 6, 3), Blocks.STONE.defaultBlockState());
        place(helper, ANCHORED_GROWTH, growth);                 // 葉 (4,5,3) の隣で、上の石にも付いている

        // 蔦: 主アンカーは幹の隣、始点はまん中の原木。副アンカー 1 つ。
        place(helper, LIANA, ModBlocks.OVERGROWN_LIANA.get().defaultBlockState());
        place(helper, LIANA_SECONDARY, ModBlocks.OVERGROWN_LIANA.get().defaultBlockState());
        if (!(level.getBlockEntity(helper.absolutePos(LIANA)) instanceof CorruptedLianaBlockEntity primary)
                || !(level.getBlockEntity(helper.absolutePos(LIANA_SECONDARY)) instanceof CorruptedLianaBlockEntity secondary)) {
            helper.fail("前提: 蔦のブロックエンティティが無い");
            return;
        }
        primary.setEndpoints(helper.absolutePos(new BlockPos(3, 3, 3)), helper.absolutePos(new BlockPos(6, 3, 6)), 0.5F);
        primary.setSecondaryPositions(List.of(helper.absolutePos(LIANA_SECONDARY)));
        secondary.setPrimaryPos(helper.absolutePos(LIANA));

        SwampTreeFall.enqueue(level, helper.absolutePos(STUMP));

        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_LOG, TOP_LOG); // 木が消えるまで待つ
            helper.assertBlockNotPresent(ModBlocks.OVERGROWN_LIANA.get(), LIANA);
            helper.assertBlockNotPresent(ModBlocks.OVERGROWN_LIANA.get(), LIANA_SECONDARY);
            helper.assertBlockNotPresent(ModBlocks.OVERGROWN_GROWTH.get(), HANGING_GROWTH);
            helper.assertBlockPresent(ModBlocks.OVERGROWN_GROWTH.get(), ANCHORED_GROWTH);
        });
    }

    /**
     * 元の木を消しても、枝が斜めに触れている隣の mod の木は消えない(2026-10-31、ユーザー報告「既存の木を消す仕組みが
     * 汚染地域に生えた mod の木も消している」)。元の木の汚染された葉({@code overgrown_leaves})は元の木と一緒に消え、
     * mod の木の葉(同じ {@code overgrown_leaves})は残る。倒木になってもよい(切り株は床の上の段、倒れた幹も同じ段)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void removedTreeSparesTouchingModTree(GameTestHelper helper) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.swampTreeFall.get() || !cfg.jungleTreeFall.get()) {
            helper.succeed(); // 設定で切ってある(木を消さない)
            return;
        }
        ServerLevel level = helper.getLevel();
        // 元の木: 原木 3 段 (1, 2..4, 3) + バニラの葉 5 枚 + 汚染された葉 2 枚。
        List<BlockPos> vanillaLogs = List.of(new BlockPos(1, 2, 3), new BlockPos(1, 3, 3), new BlockPos(1, 4, 3));
        for (BlockPos p : vanillaLogs) {
            place(helper, p, Blocks.OAK_LOG.defaultBlockState());
        }
        BlockState oakLeaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        List<BlockPos> vanillaLeaves = List.of(new BlockPos(1, 5, 3), new BlockPos(0, 5, 3), new BlockPos(1, 5, 2),
                new BlockPos(1, 5, 4), new BlockPos(0, 5, 2));
        for (BlockPos p : vanillaLeaves) {
            place(helper, p, oakLeaf);
        }
        BlockState modLeaf = ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState();
        List<BlockPos> corruptedLeaves = List.of(new BlockPos(1, 6, 3), new BlockPos(0, 6, 3));
        for (BlockPos p : corruptedLeaves) {
            place(helper, p, modLeaf);
        }
        // mod の木: 幹 (5, 2..5, 3)、枝 (4,5,3) → (3,5,3) → (2,5,4)。(2,5,4) は元の木のてっぺん (1,4,3) と斜めに触れる。
        BlockState modLog = ModBlocks.OVERGROWN_LOG.get().defaultBlockState();
        List<BlockPos> modLogs = List.of(new BlockPos(5, 2, 3), new BlockPos(5, 3, 3), new BlockPos(5, 4, 3),
                new BlockPos(5, 5, 3), new BlockPos(4, 5, 3), new BlockPos(3, 5, 3), new BlockPos(2, 5, 4));
        for (BlockPos p : modLogs) {
            place(helper, p, modLog);
        }
        List<BlockPos> modLeaves = new java.util.ArrayList<>();
        for (int x = 2; x <= 6; x++) {
            for (int z = 2; z <= 4; z++) {
                modLeaves.add(new BlockPos(x, 6, z));
            }
        }
        modLeaves.addAll(List.of(new BlockPos(5, 5, 2), new BlockPos(5, 5, 4), new BlockPos(6, 5, 3),
                new BlockPos(4, 5, 2), new BlockPos(4, 5, 4), new BlockPos(3, 5, 2), new BlockPos(3, 5, 4)));
        for (BlockPos p : modLeaves) {
            place(helper, p, modLeaf);
        }

        SwampTreeFall.enqueue(level, helper.absolutePos(vanillaLogs.get(0)));

        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_LOG, vanillaLogs.get(2)); // 元の木が消えるまで待つ
            helper.assertBlockNotPresent(Blocks.OAK_LOG, vanillaLogs.get(1));
            for (BlockPos p : vanillaLeaves) {
                helper.assertBlockNotPresent(Blocks.OAK_LEAVES, p);
            }
            for (BlockPos p : corruptedLeaves) {
                helper.assertBlockNotPresent(ModBlocks.OVERGROWN_LEAVES.get(), p);
            }
            for (BlockPos p : modLogs) {
                helper.assertBlockPresent(ModBlocks.OVERGROWN_LOG.get(), p);
            }
            int kept = 0;
            for (BlockPos p : modLeaves) {
                if (level.getBlockState(helper.absolutePos(p)).is(ModBlocks.OVERGROWN_LEAVES.get())) {
                    kept++;
                }
            }
            if (kept * 10 < modLeaves.size() * 8) {
                helper.fail("mod の木の葉が残っていない: " + kept + " / " + modLeaves.size());
            }
        });
    }

    private static void place(GameTestHelper helper, BlockPos rel, BlockState state) {
        helper.getLevel().setBlock(helper.absolutePos(rel), state, Block.UPDATE_CLIENTS);
    }
}
