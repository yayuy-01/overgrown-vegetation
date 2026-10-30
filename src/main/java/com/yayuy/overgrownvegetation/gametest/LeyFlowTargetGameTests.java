package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LeyFlowSync;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>光の流れをたどる</b>({@link LeyFlowSync#towardFlip}。2026-10-16 ユーザー指定 ⑦)のテスト: 地脈の光の流れの向きを、いちばん近い行き先
 * (ハイヴノード・巨大樹・器官)の方へそろえる。行き先が無ければ前の向きのまま。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class LeyFlowTargetGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(LeyFlowTargetGameTests.class);
        }

        private Registration() {
        }
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void flowTurnsTowardNearestTarget(GameTestHelper helper) {
        // 行き先は (100, 0) と (−300, 0)。(0, 0) から近いのは (100, 0)。
        double[] targets = {100.0, 0.0, -300.0, 0.0};
        if (LeyFlowSync.towardFlip(targets, 0.0, 0.0, 1.0, 0.0)) {
            helper.fail("行き先へ向いている流れを裏返した");
            return;
        }
        if (!LeyFlowSync.towardFlip(targets, 0.0, 0.0, -1.0, 0.0)) {
            helper.fail("行き先と逆の流れを裏返さない");
            return;
        }
        // 線の上で行き先を過ぎた所(x = 150)では逆向きが正しい(両側から行き先の所へ流れ込む)。
        if (!LeyFlowSync.towardFlip(targets, 150.0, 0.0, 1.0, 0.0)) {
            helper.fail("行き先を過ぎた所で向きを返さない");
            return;
        }
        if (LeyFlowSync.towardFlip(new double[0], 0.0, 0.0, -1.0, 0.0)) {
            helper.fail("行き先が無いのに裏返した");
            return;
        }
        helper.succeed();
    }
}
