package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.sky.CloudDrift;
import com.yayuy.overgrownvegetation.sky.CloudField;
import com.yayuy.overgrownvegetation.sky.CloudShape;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;

/**
 * サーバー側で引く雲(2026-11-08 雲の段階 1 の仕上げ 6〜9)。空の世界(雲の床を水面のように使う・上昇気流・雲に隠れる)の土台として、
 * サーバーが {@link CloudSky} から雲の床の高さ・雲の中の判定・積雲の厚さを引けること、それが種ごとに決まった値になることを確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CloudServerGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CloudServerGameTests.class);
        }

        private Registration() {
        }
    }

    private static final long SEED_A = 0x1234_5678_9ABCL;
    private static final long SEED_B = 0x0FED_CBA9_8765L;
    /** 量 0.6・谷のある雲の海・厚さ 50(ユーザー指定 y 300〜350)・平らさ 0.75。 */
    private static final CloudShape SHAPE = new CloudShape(CloudField.STYLE_VALLEY, 0.6F, 50.0F, 0.75F);

    /** 場の座標の 64 点の雲の床 {底, 天辺}(無ければ -1, -1)と積雲の厚さ。 */
    private static int[] sample() {
        float coverage = SHAPE.coverage();
        float threshold = CloudField.threshold(coverage);
        int[] out = new int[64 * 3];
        for (int i = 0; i < 64; i++) {
            double fx = (i % 8) * 173.0 - 600.0;
            double fz = (i / 8) * 211.0 - 700.0;
            CloudField.Column c = CloudSky.columnRendered(fx, fz, threshold, coverage, SHAPE.style());
            out[i * 3] = c.present() ? c.bottom() : -1;
            out[i * 3 + 1] = c.present() ? c.top() : -1;
            out[i * 3 + 2] = Math.round(CloudField.cumulusThickness(fx, fz, coverage) * 10.0F);
        }
        return out;
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void cloudFloorIsFixedPerSeedOnTheServer(GameTestHelper helper) {
        long before = CloudField.seed();
        try {
            CloudShape.setOverride(SHAPE);
            CloudField.setSeed(SEED_A);
            int[] a1 = sample();
            CloudField.setSeed(SEED_B);
            int[] b = sample();
            CloudField.setSeed(SEED_A);
            int[] a2 = sample();
            if (!Arrays.equals(a1, a2)) {
                helper.fail("同じ種で雲の床が変わった(種を替えて戻すと違う値)");
                return;
            }
            if (Arrays.equals(a1, b)) {
                helper.fail("種を替えても雲の床が同じ");
                return;
            }
            int clouds = 0;
            for (int i = 0; i < 64; i++) {
                int bottom = a1[i * 3];
                int top = a1[i * 3 + 1];
                if (bottom < 0) {
                    continue;
                }
                clouds++;
                if (bottom != CloudField.LOW_BASE || top > CloudField.LOW_BASE + 50 || top <= bottom) {
                    helper.fail("雲の床が y 300〜350 の外: " + bottom + "〜" + top);
                    return;
                }
                if (a1[i * 3 + 2] > 500) {
                    helper.fail("積雲の厚さが上限 50 を超えた: " + a1[i * 3 + 2] / 10.0F);
                    return;
                }
            }
            if (clouds == 0 || clouds == 64) {
                helper.fail("量 0.6 なのに雲の床が " + clouds + " / 64 点");
                return;
            }

            // サーバーの雲の流れ(仕上げ 6)と、世界の座標で引く口(仕上げ 7)。
            ServerLevel level = helper.getLevel();
            double t = level.getGameTime();
            if (Math.abs(CloudField.offsetX(t) - CloudDrift.SERVER_DRIFT.x(t)) > 1.0E-6) {
                helper.fail("サーバーの雲の流れが卓越風の積分になっていない");
                return;
            }
            int found = 0;
            for (int i = 0; i < 400 && found < 5; i++) {
                double x = i * 37.0 - 7000.0;
                double z = i * 53.0 + 300.0;
                int[] c = CloudSky.floorAt(level, x, z, 0.0F);
                if (c == null) {
                    continue;
                }
                found++;
                double mid = (c[0] + c[1]) * 0.5;
                if (!CloudSky.insideCloud(level, x, mid, z, 0.0F) || CloudSky.insideCloud(level, x, c[1] + 5.0, z, 0.0F)
                        || CloudSky.insideCloud(level, x, c[0] - 5.0, z, 0.0F)) {
                    helper.fail("雲の中の判定が雲の床の柱と合わない: " + c[0] + "〜" + c[1]);
                    return;
                }
                int[] again = CloudSky.floorAt(level, x, z, 0.0F);
                if (!Arrays.equals(c, again)) {
                    helper.fail("同じ時刻に引き直した雲の床が違う");
                    return;
                }
            }
            if (found == 0) {
                helper.fail("世界の座標で雲の床が 1 つも引けない");
                return;
            }
            helper.succeed();
        } finally {
            CloudShape.setOverride(null);
            CloudField.setSeed(before);
        }
    }

    private CloudServerGameTests() {
    }
}
