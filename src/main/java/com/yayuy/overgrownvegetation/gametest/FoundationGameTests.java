package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;

/**
 * 土台(第 0 段)の確かめ: MOD が読み込まれ、設計データのフォルダができ、{@code /ov} が登録されている。
 *
 * <p>テスト場は {@code gametest_empty7}(7×7、床は y=1・外はバリア)。テストは並列に走るので、
 * ワールドに 1 つの値を変えるテストはバッチを分ける(メモリ gametest-arena-layout)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FoundationGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FoundationGameTests.class);
        }

        private Registration() {
        }
    }

    private FoundationGameTests() {
    }

    @GameTest(template = "gametest_empty7")
    public static void modAndDesignDataLoaded(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded(OvergrownVegetation.MODID), "MOD が読み込まれていない");
        helper.assertTrue(DesignData.loaded(), "設計データが読み込まれていない");
        helper.assertTrue(Files.isDirectory(DesignData.dir()), "設計データのフォルダが無い: " + DesignData.dir());
        helper.assertTrue(helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("ov") != null,
                "/ov が登録されていない");
        helper.succeed();
    }
}
