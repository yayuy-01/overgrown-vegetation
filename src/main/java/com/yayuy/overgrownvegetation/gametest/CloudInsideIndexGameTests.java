package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.sky.CloudField;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@link CloudField#inside} の格子(2026-10-19 軽量化)が、塊を全部当てたときと同じ答えを返す。
 * 全部当てた答え = 塊 1 つずつの一覧で当てた答えの OR(縁の雑音は点の位置だけで決まるので、1 つずつでも同じ)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CloudInsideIndexGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CloudInsideIndexGameTests.class);
        }

        private Registration() {
        }
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void indexedInsideMatchesEveryPuff(GameTestHelper helper) {
        CloudField.Storm[] storms = {
                new CloudField.Storm(120.0, -80.0, 260.0F, 1150, false, false, 1.0F, 9001L, 2),
                new CloudField.Storm(-400.0, 300.0, 320.0F, 1300, true, false, 0.8F, 9002L, 1),
                new CloudField.Storm(2000.0, 1500.0, 700.0F, 2800, true, true, 1.0F, 9003L, 2),
        };
        RandomSource random = RandomSource.create(42L);
        int checked = 0;
        int hits = 0;
        for (CloudField.Storm s : storms) {
            List<CloudField.Puff> puffs = CloudField.puffs(s);
            if (puffs.size() < 24) {
                helper.fail("前提: 塊が少なくて格子を使わない(" + puffs.size() + ")");
                return;
            }
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (CloudField.Puff p : puffs) {
                minX = Math.min(minX, p.x() - p.r() * 1.3);
                maxX = Math.max(maxX, p.x() + p.r() * 1.3);
                minZ = Math.min(minZ, p.z() - p.r() * 1.3);
                maxZ = Math.max(maxZ, p.z() + p.r() * 1.3);
            }
            for (int i = 0; i < 20000; i++) {
                double x = minX + random.nextDouble() * (maxX - minX);
                double z = minZ + random.nextDouble() * (maxZ - minZ);
                double y = CloudField.LOW_BASE - 10.0 + random.nextDouble() * (s.top() + 60.0 - CloudField.LOW_BASE);
                boolean indexed = CloudField.inside(puffs, x, y, z);
                boolean any = false;
                for (CloudField.Puff p : puffs) {
                    if (CloudField.inside(List.of(p), x, y, z)) {
                        any = true;
                        break;
                    }
                }
                if (indexed != any) {
                    helper.fail(String.format("答えが違う: 雲 %d の (%.1f, %.1f, %.1f) で格子 %s・全部 %s", s.id(), x, y, z, indexed, any));
                    return;
                }
                checked++;
                if (any) {
                    hits++;
                }
            }
        }
        if (hits < checked / 50) {
            helper.fail("前提: 雲の中の点が少なすぎて確かめになっていない(" + hits + "/" + checked + ")");
            return;
        }
        helper.succeed();
    }
}
