package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 洞窟が汚染されるとグロウベリーが丸ごと消える(2026-10-16 ユーザー指定「グロウベリーが削除されずに残る」)。
 * 以前は対応表に無いので苔で覆って残り、汚染された天井の下でも垂れたままだった。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GlowBerryCorruptionGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGlowBerryCorruptionGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(GlowBerryCorruptionGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BlockPos CEILING = new BlockPos(3, 6, 3);

    /** 天井が汚染されたら、そこから垂れるグロウベリーは下の段まで全部消える。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void corruptedCeilingDropsHangingBerries(GameTestHelper helper) {
        hangBerries(helper);
        corruptAt(helper, CEILING);
        if (helper.getBlockState(CEILING).is(Blocks.STONE)) {
            helper.fail("前提: 天井の石が汚染されていない");
            return;
        }
        expectNoVines(helper);
    }

    /** 途中の段に汚染が当たっても、上から下まで丸ごと消える(下草に変わらない・天井はそのまま)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void corruptionOnVineClearsWholeVine(GameTestHelper helper) {
        hangBerries(helper);
        corruptAt(helper, CEILING.below(2));
        if (!helper.getBlockState(CEILING).is(Blocks.STONE)) {
            helper.fail("天井の石まで変わった: " + helper.getBlockState(CEILING));
            return;
        }
        expectNoVines(helper);
    }

    /** 石の天井から 3 段(上 2 段がつる、いちばん下が実のなる先端)。 */
    private static void hangBerries(GameTestHelper helper) {
        helper.setBlock(CEILING, Blocks.STONE);
        for (int i = 1; i <= 3; i++) {
            helper.setBlock(CEILING.below(i), i < 3 ? Blocks.CAVE_VINES_PLANT : Blocks.CAVE_VINES);
        }
        for (int i = 1; i <= 3; i++) {
            BlockState state = helper.getBlockState(CEILING.below(i));
            if (!state.is(Blocks.CAVE_VINES) && !state.is(Blocks.CAVE_VINES_PLANT)) {
                helper.fail("前提: グロウベリーが置けていない(" + i + " 段目: " + state + ")");
            }
        }
    }

    private static void corruptAt(GameTestHelper helper, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(rel);
        BlockState state = level.getBlockState(abs);
        BlockState corrupted = CorruptionRegistry.corrupt(level, abs, state, level.random);
        if (!CorruptionRegistry.noChange(state, corrupted)) {
            CorruptionRegistry.placeCorrupted(level, abs, state, corrupted, Block.UPDATE_ALL);
        }
    }

    private static void expectNoVines(GameTestHelper helper) {
        for (int i = 1; i <= 3; i++) {
            BlockState state = helper.getBlockState(CEILING.below(i));
            if (!state.isAir()) {
                helper.fail("グロウベリーが残った(" + i + " 段目: " + state + ")");
                return;
            }
        }
        helper.succeed();
    }
}
