package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.SigillariaFringeBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 板の葉の支え(2026-11-03、ユーザー報告「葉から生える房が、元の葉を壊しても浮く」): 芯の葉を壊すと、そこに付いた板(房・木生シダ・封印木。
 * 角の葉は斜めの芯)が崩れる。芯が残っている板は残る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FringeSupportGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FringeSupportGameTests.class);
        }

        private Registration() {
        }
    }

    private static BlockState fringe(Block block, Direction facing, boolean corner) {
        return block.defaultBlockState().setValue(SigillariaFringeBlock.FACING, facing).setValue(SigillariaFringeBlock.CORNER, corner);
    }

    /** 中層の房: 芯の葉 1 つ + 板 5 枚(上・下・横 2・先)。芯を壊すと 5 枚とも消え、別の芯の板は残る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void midTuftFallsWithItsCore(GameTestHelper helper) {
        Block tuft = ModBlocks.OVERGROWN_LEAVES_TUFT.get();
        BlockPos core = new BlockPos(3, 4, 3);
        helper.setBlock(core, ModBlocks.OVERGROWN_LEAVES.get());
        helper.setBlock(core.west(), ModBlocks.OVERGROWN_BRANCH.get()); // 腕(幹の側。板は無い)
        helper.setBlock(core.above(), fringe(tuft, Direction.DOWN, false));
        helper.setBlock(core.below(), fringe(tuft, Direction.UP, false));
        List<BlockPos> sides = List.of(core.north(), core.south(), core.east());
        for (BlockPos p : sides) {
            Direction toCore = Direction.getNearest(core.getX() - p.getX(), 0, core.getZ() - p.getZ());
            helper.setBlock(p, fringe(tuft, toCore, false));
        }
        BlockPos other = new BlockPos(1, 4, 1);
        helper.setBlock(other, ModBlocks.OVERGROWN_LEAVES.get());
        helper.setBlock(other.below(), fringe(tuft, Direction.UP, false));
        helper.setBlock(core, Blocks.AIR);
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(tuft, core.above());
            helper.assertBlockNotPresent(tuft, core.below());
            for (BlockPos p : sides) {
                helper.assertBlockNotPresent(tuft, p);
            }
            helper.assertBlockPresent(tuft, other.below());
        });
    }

    /** 木生シダ: 横の板と角の板(芯は斜め)が芯の葉と一緒に崩れる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void treeFernFrondsFallWithTheirCore(GameTestHelper helper) {
        checkSideAndCorner(helper, ModBlocks.TREE_FERN_LEAVES.get(), ModBlocks.TREE_FERN_FROND.get());
    }

    /** 封印木: 垂れる板と角の板が芯の葉と一緒に崩れる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void sigillariaFringeFallsWithItsCore(GameTestHelper helper) {
        checkSideAndCorner(helper, ModBlocks.SIGILLARIA_LEAVES.get(), ModBlocks.SIGILLARIA_LEAVES_FRINGE.get());
    }

    private static void checkSideAndCorner(GameTestHelper helper, Block leaves, Block frond) {
        BlockPos core = new BlockPos(3, 4, 3);
        helper.setBlock(core, leaves);
        BlockPos side = core.east(), hang = core.below();
        // 角: 芯は FACING(北)とその左隣(西)の間の斜め = 板から見て北西。板は芯の南東
        BlockPos corner = core.south().east();
        helper.setBlock(side, fringe(frond, Direction.WEST, false));
        helper.setBlock(hang, fringe(frond, Direction.UP, false));
        helper.setBlock(corner, fringe(frond, Direction.NORTH, true));
        if (!SigillariaFringeBlock.supportPos(helper.getBlockState(corner), helper.absolutePos(corner)).equals(helper.absolutePos(core))) {
            helper.fail("角の板の芯の位置が違う");
            return;
        }
        // 芯がある間は崩れない(隣の更新が来ても)
        helper.setBlock(new BlockPos(1, 4, 1), Blocks.STONE);
        helper.runAfterDelay(3, () -> {
            helper.assertBlockPresent(frond, side);
            helper.assertBlockPresent(frond, corner);
            helper.setBlock(core, Blocks.AIR);
            helper.succeedWhen(() -> {
                helper.assertBlockNotPresent(frond, side);
                helper.assertBlockNotPresent(frond, hang);
                helper.assertBlockNotPresent(frond, corner);
            });
        });
    }
}
