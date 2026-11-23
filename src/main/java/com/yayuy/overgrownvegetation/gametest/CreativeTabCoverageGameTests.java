package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * クリエイティブタブの載せ漏れ(2026-11-19 夜、ユーザー「器官のブロックとか、クリエイティブタブにないものはしっかり追加しておいて」)。
 * この MOD が登録したアイテムが、どれかのタブに入っているか。技術上・持たせると壊れる物は {@link #EXCLUDED} で外す(理由は報告 creative-tab-report.md)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CreativeTabCoverageGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterCreativeTabCoverageGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(CreativeTabCoverageGameTests.class);
        }

        private Registration() {
        }
    }

    private CreativeTabCoverageGameTests() {
    }

    /** タブに入れないアイテム(パス)。 */
    static final Set<String> EXCLUDED = Set.of();

    @GameTest(template = "gametest_empty7", batch = "creative_tabs", timeoutTicks = 20)
    public static void everyItemIsInSomeCreativeTab(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), true, level.registryAccess());
        Set<Item> shown = new HashSet<>();
        for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            for (ItemStack s : tab.getDisplayItems()) shown.add(s.getItem());
            for (ItemStack s : tab.getSearchTabDisplayItems()) shown.add(s.getItem());
        }
        TreeSet<String> missing = new TreeSet<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (!OvergrownVegetation.MODID.equals(id.getNamespace()) || EXCLUDED.contains(id.getPath())) continue;
            if (!shown.contains(item)) missing.add(id.getPath());
        }
        OvergrownVegetation.LOGGER.info("CREATIVE_TAB_MISSING {} {}", missing.size(), missing);
        helper.assertTrue(missing.isEmpty(), "どのクリエイティブタブにも無いアイテム " + missing.size() + " 個: " + missing);
        helper.succeed();
    }
}
