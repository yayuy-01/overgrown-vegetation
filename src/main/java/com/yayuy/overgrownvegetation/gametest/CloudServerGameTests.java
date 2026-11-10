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
            CloudSky.clearNearCache();
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
                // 積乱雲・入道雲のかかる所は雲の床の上にも雲があるので、床だけの確かめから外す(11-09 段階 2a)。
                if (c == null || CloudSky.stormSpan(level, x, z, 0.0F) != null) {
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

    /** 積乱雲を必ず立てる形(1 升に立つ確率 1・巨大な雲は既定)。 */
    private static final CloudShape STORMY = new CloudShape(CloudField.STYLE_VALLEY, 0.6F, 50.0F, 0.75F, 1.0F, 1.0F, -1.0F, 1.0F);

    /**
     * 積乱雲(11-09 段階 2a): サーバーが種と時刻から同じ積乱雲を引けること、塊の中の判定・塊の縦の広がり・積乱雲の下の判定が食い違わないこと、
     * 積乱雲を立てない設計値では 1 つも立たないこと、足元の裾野は雲の床(y 350)より高くなってよいが裾野の外の床は y 350 を超えないこと。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void stormsAreQueryableOnTheServer(GameTestHelper helper) {
        long before = CloudField.seed();
        try {
            ServerLevel level = helper.getLevel();
            CloudShape.setOverride(STORMY);
            // ほかのテストが少し前の時刻で覚えた近くの積乱雲の一覧を使わない(11-10: 全体の実行でだけ、湧き始めの雲が一覧に無くて落ちた)。
            CloudSky.clearNearCache();
            CloudField.setSeed(SEED_A);
            float coverage = STORMY.coverage();
            java.util.List<CloudField.Storm> a1 = CloudSky.storms(level, coverage, -6000.0, -6000.0, 6000.0, 6000.0);
            java.util.List<CloudField.Storm> a2 = CloudSky.storms(level, coverage, -6000.0, -6000.0, 6000.0, 6000.0);
            if (a1.isEmpty()) {
                helper.fail("確率 1 なのに 12000 ブロック四方に積乱雲が 1 つも無い");
                return;
            }
            if (!a1.equals(a2)) {
                helper.fail("同じ種・同じ時刻で積乱雲の並びが違う");
                return;
            }
            // 塊の中・縦の広がり・下の判定(世界の座標へ直して引く)。
            long gt = level.getGameTime();
            int checked = 0;
            for (CloudField.Storm s : a1) {
                if (s.kind() != 0 || checked >= 6) {
                    continue;
                }
                double wx = s.x() + CloudField.offsetX(gt);
                double wz = s.z() + CloudField.offsetZ(gt);
                int[] span = CloudSky.stormSpan(level, wx, wz, 0.0F);
                if (span == null) {
                    continue;
                }
                checked++;
                double mid = (span[0] + span[1]) * 0.5;
                if (!CloudSky.insideStorm(level, wx, span[0] + 4.0, wz, 0.0F) && !CloudSky.insideStorm(level, wx, mid, wz, 0.0F)) {
                    helper.fail("積乱雲の真ん中の柱で塊の中の判定が外れた: " + span[0] + "〜" + span[1]);
                    return;
                }
                if (CloudSky.insideStorm(level, wx, span[1] + 20.0, wz, 0.0F)) {
                    helper.fail("積乱雲の天辺より上が雲の中になった: 天辺 " + span[1]);
                    return;
                }
                if (!CloudSky.underStorm(level, wx, span[0] - 30.0, wz, 0.0F) || CloudSky.underStorm(level, wx, span[1] + 20.0, wz, 0.0F)) {
                    helper.fail("積乱雲の下の判定が塊の広がりと合わない: " + span[0] + "〜" + span[1]);
                    return;
                }
                if (CloudSky.stormAt(level, wx, wz, 0.0F, true) == null) {
                    helper.fail("積乱雲の真ん中で stormAt が null");
                    return;
                }
            }
            if (checked == 0) {
                helper.fail("塊の柱が引ける積乱雲が 1 つも無い");
                return;
            }
            // 雲の床: 積乱雲から遠い所(裾野の外)は y 350 を超えない。
            float threshold = CloudField.threshold(coverage);
            int over = 0;
            int far = 0;
            for (int i = 0; i < 400; i++) {
                double fx = (i % 20) * 293.0 - 3000.0;
                double fz = (i / 20) * 307.0 - 3000.0;
                boolean nearStorm = false;
                for (CloudField.Storm s : a1) {
                    if (Math.hypot(fx - s.x(), fz - s.z()) < s.bodyRadius() * 3.6 + 60.0) {
                        nearStorm = true;
                        break;
                    }
                }
                if (nearStorm) {
                    continue;
                }
                far++;
                CloudField.Column c = CloudSky.columnRendered(fx, fz, threshold, coverage, STORMY.style(), a1);
                if (c.present() && c.top() > CloudField.LOW_BASE + 50) {
                    over++;
                }
            }
            if (far > 0 && over > 0) {
                helper.fail("積乱雲の裾野の外で雲の床が y 350 を超えた: " + over + " / " + far + " 点");
                return;
            }
            // 尾根・アーチ・浮いた雲(11-09 段階 2a-2): 尾根は真ん中が雲、アーチは稜線が雲で、その下(雲の床と稜線の間)は抜けている。浮いた雲は真ん中が雲、下は空。
            int ridges = 0;
            int arches = 0;
            int floats = 0;
            int floatBelow = 0;
            for (CloudField.Storm s : a1) {
                double wx = (s.x() + s.x2()) * 0.5 + CloudField.offsetX(gt);
                double wz = (s.z() + s.z2()) * 0.5 + CloudField.offsetZ(gt);
                if (s.kind() == CloudField.KIND_RIDGE) {
                    int[] span = CloudSky.stormSpan(level, wx, wz, 0.0F);
                    if (span == null) {
                        continue;
                    }
                    ridges++;
                    if (s.anvil()) {
                        // アーチ: 塊の下の端(span[0])が雲の床の天辺より 40 以上上で、その間は雲の塊でない。
                        int[] floor = CloudSky.floorAt(level, wx, wz, 0.0F);
                        int floorTop = floor == null ? CloudField.LOW_BASE : floor[1];
                        if (span[0] > floorTop + 40) {
                            arches++;
                            double gap = (floorTop + span[0]) * 0.5;
                            if (CloudSky.insideStorm(level, wx, gap, wz, 0.0F)) {
                                helper.fail("アーチの下(トンネル)が雲の中になった: 床 " + floorTop + "・アーチの底 " + span[0]);
                                return;
                            }
                        }
                    }
                } else if (s.kind() == CloudField.KIND_FLOAT && floats < 8) {
                    double fx = s.x() + CloudField.offsetX(gt);
                    double fz = s.z() + CloudField.offsetZ(gt);
                    if (!CloudSky.insideStorm(level, fx, s.top(), fz, 0.0F)) {
                        helper.fail("浮いた雲の真ん中が雲の中でない: y " + s.top());
                        return;
                    }
                    // 下は空(群れのほかの塊が下にかかることがあるので、半分より多く外れたら落とす)。
                    if (CloudSky.insideStorm(level, fx, s.top() - s.radius() * 1.3, fz, 0.0F)) {
                        floatBelow++;
                    }
                    // 浮いた雲の下は「積乱雲の下」でない(11-09 段階 2a-3 ユーザー決定)。積乱雲の上に浮いている所もあるので、下の y 250 で見る。
                    if (CloudSky.underStorm(level, fx, 250.0, fz, 0.0F) && CloudSky.stormAt(level, fx, fz, 0.0F, true) == null) {
                        helper.fail("浮いた雲だけの下で underStorm が true");
                        return;
                    }
                    floats++;
                }
            }
            if (floatBelow * 2 > floats) {
                helper.fail("浮いた雲の下が雲の中: " + floatBelow + " / " + floats);
                return;
            }
            if (ridges == 0 || arches == 0 || floats == 0) {
                helper.fail("尾根 " + ridges + "・抜けたアーチ " + arches + "・浮いた雲 " + floats + " のどれかが 0");
                return;
            }
            // 積乱雲を立てない設計値。
            CloudShape.setOverride(new CloudShape(CloudField.STYLE_VALLEY, 0.6F, 50.0F, 0.75F, 0.0F, 1.0F, -1.0F, 1.0F));
            if (!CloudSky.storms(level, coverage, -6000.0, -6000.0, 6000.0, 6000.0).isEmpty()) {
                helper.fail("storms.enabled = 0 なのに積乱雲が立った");
                return;
            }
            helper.succeed();
        } finally {
            CloudShape.setOverride(null);
            CloudField.setSeed(before);
        }
    }

    /**
     * 雨を雲の多い日に合わせる(11-09 段階 2a-5): 予定の雨は雲の多い日にだけ来る・種と日で決まる・{@code /weather clear} の間は降らせない・
     * {@code /weather rain} で手で起こした雨は止めない。天気は終わったら元に戻す。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void rainFollowsCloudyDays(GameTestHelper helper) {
        long before = CloudField.seed();
        ServerLevel level = helper.getLevel();
        net.minecraft.world.level.storage.ServerLevelData data = (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
        int clear0 = data.getClearWeatherTime();
        int rain0 = data.getRainTime();
        int thunder0 = data.getThunderTime();
        boolean raining0 = data.isRaining();
        boolean thundering0 = data.isThundering();
        net.minecraft.world.level.GameRules.BooleanValue cycle = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE);
        boolean cycle0 = cycle.get();
        try {
            CloudShape.setOverride(null);
            CloudField.setSeed(SEED_A);
            int rainDays = 0;
            for (long d = 0; d < 400; d++) {
                com.yayuy.overgrownvegetation.sky.CloudWeather.Rain r = com.yayuy.overgrownvegetation.sky.CloudWeather.rainOfDay(d);
                if (r == null) {
                    continue;
                }
                rainDays++;
                if (!com.yayuy.overgrownvegetation.sky.CloudWeather.manyDay(d)) {
                    helper.fail("雲の多い日でない日 " + d + " に雨の予定");
                    return;
                }
                if (!r.equals(com.yayuy.overgrownvegetation.sky.CloudWeather.rainOfDay(d))) {
                    helper.fail("同じ種・同じ日で雨の予定が違う");
                    return;
                }
            }
            if (rainDays < 60 || rainDays > 180) {
                helper.fail("400 日のうち雨の日が " + rainDays + "(多い日 4 割 × 7.5 割 = 120 前後のはず)");
                return;
            }
            cycle.set(true, level.getServer());
            com.yayuy.overgrownvegetation.sky.CloudWeather.reset();
            // /weather clear の間は降らせない。
            level.setWeatherParameters(6000, 0, false, false);
            com.yayuy.overgrownvegetation.sky.CloudWeather.tick(level);
            if (data.isRaining()) {
                helper.fail("/weather clear の間に雨にした");
                return;
            }
            // 予定どおり。
            level.setWeatherParameters(0, 0, false, false);
            com.yayuy.overgrownvegetation.sky.CloudWeather.reset();
            com.yayuy.overgrownvegetation.sky.CloudWeather.tick(level);
            boolean want = com.yayuy.overgrownvegetation.sky.CloudWeather.rainAt(level.getDayTime()) != null;
            if (data.isRaining() != want) {
                helper.fail("予定と天気が違う: 予定 " + want + "・天気 " + data.isRaining());
                return;
            }
            // 手で起こした雨(/weather rain と同じ書き方)は、予定に関係なく止めない。
            level.setWeatherParameters(0, 6000, true, false);
            com.yayuy.overgrownvegetation.sky.CloudWeather.tick(level);
            com.yayuy.overgrownvegetation.sky.CloudWeather.tick(level);
            if (!data.isRaining()) {
                helper.fail("/weather rain で起こした雨を止めた");
                return;
            }
            helper.succeed();
        } finally {
            com.yayuy.overgrownvegetation.sky.CloudWeather.reset();
            data.setClearWeatherTime(clear0);
            data.setRainTime(rain0);
            data.setThunderTime(thunder0);
            data.setRaining(raining0);
            data.setThundering(thundering0);
            cycle.set(cycle0, level.getServer());
            CloudShape.setOverride(null);
            CloudField.setSeed(before);
        }
    }

    private CloudServerGameTests() {
    }
}
