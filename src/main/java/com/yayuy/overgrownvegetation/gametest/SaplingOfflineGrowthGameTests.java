package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.worldgen.SaplingRecords;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 苗木の追いつき成長({@link com.yayuy.overgrownvegetation.worldgen.SaplingOfflineGrowth})の記録部分のテスト:
 * 苗木を置くとチャンクに設置時刻が記録され、壊すと消える(空になれば添付データごと消える)。
 * チャンクのアンロード/再読み込みは GameTest では再現しないので、読み込み時のキュー処理はゲーム内で確認する。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SaplingOfflineGrowthGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SaplingOfflineGrowthGameTests.class);
        }
    }

    @GameTest(template = "gametest_empty7")
    public static void saplingPlantTimeIsRecordedAndForgotten(GameTestHelper helper) {
        BlockPos ground = new BlockPos(3, 1, 3);
        BlockPos rel = ground.above();
        helper.setBlock(ground, ModBlocks.OVERGROWN_GRASS_BLOCK.get());
        helper.setBlock(rel, ModBlocks.OVERGROWN_SAPLING.get());

        BlockPos abs = helper.absolutePos(rel);
        LevelChunk chunk = helper.getLevel().getChunkAt(abs);
        helper.assertTrue(chunk.hasData(ModAttachments.SAPLING_PLANTED), "no sapling record attachment after placing");
        SaplingRecords records = chunk.getData(ModAttachments.SAPLING_PLANTED);
        helper.assertTrue(records.contains(abs), "sapling position not recorded");

        helper.setBlock(rel, Blocks.AIR);
        helper.assertTrue(!chunk.hasData(ModAttachments.SAPLING_PLANTED)
                        || !chunk.getData(ModAttachments.SAPLING_PLANTED).contains(abs),
                "sapling record not forgotten after breaking");
        helper.succeed();
    }

    private SaplingOfflineGrowthGameTests() {
    }
}
