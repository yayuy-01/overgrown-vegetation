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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
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

    private static void place(GameTestHelper helper, BlockPos rel, BlockState state) {
        helper.getLevel().setBlock(helper.absolutePos(rel), state, Block.UPDATE_CLIENTS);
    }
}
