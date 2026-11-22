package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PerfTable;
import com.yayuy.overgrownvegetation.perf.MobThinkThrottle;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * AI の段階化の距離の段(2026-11-18 大群のための軽量化 B の ②a。値は design の perf.json)。GameTest のサーバーには本物のプレイヤーがいないので
 * 間引きそのものは働かない(反応のテストが揺れないように)。ここでは段の決め方だけを設計値から確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class AiTierGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(AiTierGameTests.class);
        }

        private Registration() {
        }
    }

    private AiTierGameTests() {
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 5)
    public static void aiTiersFollowDesignDistances(GameTestHelper helper) {
        PerfTable t = DesignData.perf();
        double n = t.nearBlocks, m = t.midBlocks;
        int atNear = MobThinkThrottle.intervalFor(n * n, t);
        int justMid = MobThinkThrottle.intervalFor((n + 0.5D) * (n + 0.5D), t);
        int atMid = MobThinkThrottle.intervalFor(m * m, t);
        int beyond = MobThinkThrottle.intervalFor((m + 0.5D) * (m + 0.5D), t);
        if (atNear != 2 || justMid != t.midIntervalTicks || atMid != t.midIntervalTicks || beyond != t.farIntervalTicks) {
            helper.fail("段が設計値と違う: " + atNear + " / " + justMid + " / " + atMid + " / " + beyond + "(設計 " + n + "・" + m + "・"
                    + t.midIntervalTicks + "・" + t.farIntervalTicks + ")");
            return;
        }
        if (t.midIntervalTicks < 2 || t.farIntervalTicks < t.midIntervalTicks) {
            helper.fail("遠いほど間隔が長くない: 中 " + t.midIntervalTicks + "・遠く " + t.farIntervalTicks);
            return;
        }
        helper.succeed();
    }
}
