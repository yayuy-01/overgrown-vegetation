package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 対応していない(苔で覆っただけの)ブロックの上にも植物・苗木が生える(2026-10-10 ユーザー指定)。
 * 汚染の経路が「今覆った」を聞く口({@link OvergrownLayerData#takeNewlyCovered})と、苗木の足場({@code CorruptedSaplingBlock#isCoveredGround})。
 * 最後に必ず覆いを剥がす(GameTest の世界に覆いの記録を残さない)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CoveredGrowthGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterCoveredGrowthGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(CoveredGrowthGameTests.class);
        }

        private Registration() {
        }
    }

    /** 覆ったコンクリートの上には苗木が置ける。覆っていないコンクリートには置けない。剥がすと置けなくなる。 */
    @GameTest(template = "gametest_empty7")
    public static void saplingSurvivesOnCoveredConcreteOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, Blocks.WHITE_CONCRETE);
        helper.setBlock(rel.above(), Blocks.AIR);
        BlockPos ground = helper.absolutePos(rel);
        OvergrownLayerData.isCovered(level, ground); // 前の実行の古い記録をここで捨てさせる
        BlockState sapling = ModBlocks.OVERGROWN_SAPLING.get().defaultBlockState();
        String error = null;
        if (sapling.canSurvive(level, ground.above())) {
            error = "覆っていないコンクリートに苗木が置ける";
        }
        OvergrownLayerData.cover(level, ground);
        if (error == null && !sapling.canSurvive(level, ground.above())) {
            error = "覆ったコンクリートに苗木が置けない";
        }
        OvergrownLayerData.uncover(level, ground);
        if (error == null && sapling.canSurvive(level, ground.above())) {
            error = "覆いを剥がしたのに苗木が置ける";
        }
        if (error != null) {
            helper.fail(error);
            return;
        }
        helper.succeed();
    }

    /** 「今覆った」は新しく覆ったときだけ真(読んだら忘れる)。覆い直し・別のマスでは偽。 */
    @GameTest(template = "gametest_empty7")
    public static void newlyCoveredIsReportedOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos b = helper.absolutePos(new BlockPos(4, 2, 4));
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.WHITE_CONCRETE);
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.WHITE_CONCRETE);
        OvergrownLayerData.isCovered(level, a);
        OvergrownLayerData.isCovered(level, b);
        OvergrownLayerData.uncover(level, a);
        String error = null;
        OvergrownLayerData.cover(level, a);
        if (OvergrownLayerData.takeNewlyCovered(b)) {
            error = "別のマスを今覆ったことになった";
        }
        OvergrownLayerData.cover(level, a);
        if (error == null && OvergrownLayerData.takeNewlyCovered(a)) {
            error = "読んだ後も今覆ったことになっている / 覆い直しで今覆ったことになった";
        }
        OvergrownLayerData.uncover(level, a);
        OvergrownLayerData.cover(level, a);
        if (error == null && !OvergrownLayerData.takeNewlyCovered(a)) {
            error = "新しく覆ったのに今覆ったことにならない";
        }
        OvergrownLayerData.uncover(level, a);
        if (error != null) {
            helper.fail(error);
            return;
        }
        helper.succeed();
    }
}
