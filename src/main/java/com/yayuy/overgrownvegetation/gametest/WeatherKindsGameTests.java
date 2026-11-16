package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.network.WeatherPayload;
import com.yayuy.overgrownvegetation.sky.WorldWeather;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeField;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/**
 * 天気 4 つ(2026-11-16 weather-system: 濃霧・雷雨の強化・凪・強風の日)。場の式と天気の予定は素の Java なので、ワールドを使わずに回して比べる。
 * 強制用の static(PrevailingWind.forcedX・calmChance)は並べて走るほかのテストへ漏れるので、ここでは触らない。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class WeatherKindsGameTests {

    private static final Logger LOG = com.mojang.logging.LogUtils.getLogger();

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(WeatherKindsGameTests.class);
        }

        private Registration() {
        }
    }

    private WeatherKindsGameTests() {
    }

    /** 7×7 チャンクの過繁茂の森(真ん中が原点)を置いた場。 */
    private static SporeField forest() {
        SporeField f = new SporeField(7L, SporeFieldTable.gameDefaults());
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) f.setCorrupted(x, z, -3L * PrevailingWind.DAY);
        return f;
    }

    private static PrevailingWind.Sample eastWind(double strength, double calm, double gale) {
        PrevailingWind.Sample w = new PrevailingWind.Sample();
        w.dirX = 1;
        w.dirZ = 0;
        w.strength = strength;
        w.calm = calm;
        w.gale = gale;
        w.strongDay = gale > 0;
        return w;
    }

    /** 1 日回して、森のそば(森とその風下 3 チャンク)と遠く(風下 12〜40 チャンク)の浮遊の平均を [近く, 遠く] で返す。 */
    private static double[] nearFar(PrevailingWind.Sample wind, int steps) {
        SporeField f = forest();
        double near = 0, far = 0;
        for (int k = 1; k <= steps; k++) {
            long t = k * 100L;
            f.step(new SporeField.Conditions(t, t, false, false, false), wind);
            for (int z = -3; z <= 3; z++) {
                for (int x = -3; x <= 6; x++) near += f.air(x, z);
                for (int x = 12; x <= 40; x++) far += f.air(x, z);
            }
        }
        return new double[]{near / steps, far / steps};
    }

    /** 凪の日は森のそばが濃く、遠くは澄む(ユーザー「胞子は遠くへ運ばれない代わりに森のそばに滞って濃くなる。遠くは澄む」)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void calmKeepsSporesNearForest(GameTestHelper helper) {
        double[] normal = nearFar(eastWind(1.0, 0, 0), 240);
        double[] calm = nearFar(eastWind(1.0, 1, 0), 240);
        LOG.info("WEATHER_MEASURE 凪 近く {} → {}(×{})・遠く {} → {}(×{})", normal[0], calm[0], calm[0] / normal[0], normal[1], calm[1],
                calm[1] / Math.max(1e-12, normal[1]));
        if (!(calm[0] > normal[0])) {
            helper.fail("凪の日に森のそばが濃くならない: ふだん " + normal[0] + " 凪 " + calm[0]);
            return;
        }
        if (!(calm[1] < normal[1])) {
            helper.fail("凪の日に遠くが澄まない: ふだん " + normal[1] + " 凪 " + calm[1]);
            return;
        }
        helper.succeed();
    }

    /** 風下(z の帯 −3〜3)で汚染された升のいちばん遠い x。 */
    private static int frontX(SporeField f) {
        int best = 3;
        for (int x = 4; x <= 80; x++) for (int z = -3; z <= 3; z++) if (f.isCorrupted(x, z)) best = Math.max(best, x);
        return best;
    }

    private static int frontAfter(PrevailingWind.Sample wind, int steps) {
        return frontAfter(wind, steps, Double.NaN);
    }

    /** galeGroundWind を変えて測る(NaN = 既定のまま)。 */
    private static int frontAfter(PrevailingWind.Sample wind, int steps, double galeGroundWind) {
        SporeField f = forest();
        if (!Double.isNaN(galeGroundWind)) f.p.galeGroundWind = galeGroundWind;
        for (int k = 1; k <= steps; k++) {
            long t = k * 100L;
            f.step(new SporeField.Conditions(t, t, false, false, false), wind);
        }
        return frontX(f);
    }

    /** 強風の日は前線が風下へ伸びやすい(林床の層も風下へ運ぶ)。前からある強風の扱い(強さ 1.95・長く浮く)だけより伸びる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void galeStretchesFrontDownwind(GameTestHelper helper) {
        int normal = frontAfter(eastWind(1.0, 0, 0), 480);
        PrevailingWind.Sample oldGale = eastWind(1.95, 0, 0);
        oldGale.strongDay = true;
        int before = frontAfter(oldGale, 480);
        int gale = frontAfter(eastWind(1.95, 0, 1), 480);
        LOG.info("WEATHER_MEASURE 強風 前線(2 日) ふだん {}・前からの強風 {}・林床も運ぶ強風 {}", normal, before, gale);
        // 11-16 の測り(一定の強風で 2 日): galeGroundWind 10 → 24・20 → 37・40 → 55・80 以上 → 80(測りの端)。既定 20【仮】。
        if (!(gale > normal && gale > before)) {
            helper.fail("強風の日に前線が伸びない: ふだん " + normal + " 前からの強風 " + before + " 強風 " + gale);
            return;
        }
        helper.succeed();
    }

    /** 天気が無い日は前と同じ: 凪・強風の度合いが 0 の風は、新しい値を持たない風と 1 文字も変わらない答え。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void noWeatherKeepsOldNumbers(GameTestHelper helper) {
        long seed = 12345L;
        // 凪でも強風でもない日を探す
        long day = 0;
        while (PrevailingWind.isCalmDay(seed, day, PrevailingWind.Weather.CLEAR) || PrevailingWind.isStrongDay(seed, day, PrevailingWind.Weather.CLEAR)) day++;
        SporeField a = forest(), b = forest();
        PrevailingWind.Sample s = new PrevailingWind.Sample();
        for (int k = 1; k <= 240; k++) {
            long t = day * PrevailingWind.DAY + k * 100L;
            PrevailingWind.sample(seed, t, PrevailingWind.Weather.CLEAR, s);
            if (s.calm != 0 || s.gale != 0) {
                helper.fail("天気の無い日に凪・強風の度合いが付いた: " + s.calm + " / " + s.gale);
                return;
            }
            PrevailingWind.Sample old = new PrevailingWind.Sample();
            old.dirX = s.dirX;
            old.dirZ = s.dirZ;
            old.strength = s.strength;
            old.storm = s.storm;
            old.strongDay = s.strongDay;
            if (s.ground() != s.strength) {
                helper.fail("天気の無い日に地上の風が変わった");
                return;
            }
            a.step(new SporeField.Conditions(t, t, false, false, false), s);
            b.step(new SporeField.Conditions(t, t, false, false, false), old);
        }
        for (int x = -10; x <= 60; x++) {
            for (int z = -10; z <= 10; z++) {
                if (a.air(x, z) != b.air(x, z) || a.deposited(x, z) != b.deposited(x, z) || a.low(x, z) != b.low(x, z)
                        || a.isCorrupted(x, z) != b.isCorrupted(x, z)) {
                    helper.fail("天気の無い日の答えが変わった: (" + x + ", " + z + ")");
                    return;
                }
            }
        }
        helper.succeed();
    }

    /** 凪は地上の風だけ: 凪の日も雲・上空の風の強さ(strength)は凪でない計算と同じで、地上の風(ground)だけが落ちる。夜は凪の度合い 0。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void calmDaysComeAndGo(GameTestHelper helper) {
        long seed = 98765L;
        int calmDays = 0;
        long firstCalm = -1;
        for (long d = 0; d < 1000; d++) {
            if (PrevailingWind.isCalmDay(seed, d, PrevailingWind.Weather.CLEAR)) {
                calmDays++;
                if (firstCalm < 0) firstCalm = d;
            }
        }
        LOG.info("WEATHER_MEASURE 凪の日 1000 日中 {} 日", calmDays);
        if (calmDays < 40 || calmDays > 130) {
            helper.fail("凪の日の割合がおかしい: 1000 日中 " + calmDays);
            return;
        }
        PrevailingWind.Sample s = new PrevailingWind.Sample();
        long base = firstCalm * PrevailingWind.DAY;
        PrevailingWind.sample(seed, base + 6000, PrevailingWind.Weather.CLEAR, s); // 正午
        if (!(s.calm > 0.99 && s.ground() < s.strength * 0.2)) {
            helper.fail("凪の日の正午に地上の風が止まっていない: 凪 " + s.calm + " 地上 " + s.ground() + " 上空 " + s.strength);
            return;
        }
        PrevailingWind.sample(seed, base + 18000, PrevailingWind.Weather.CLEAR, s); // 真夜中
        if (s.calm != 0) {
            helper.fail("凪が夜まで続いた: " + s.calm);
            return;
        }
        // 雨の間は凪にならない
        PrevailingWind.Weather rain = new PrevailingWind.Weather(true, false, base, PrevailingWind.NONE, PrevailingWind.NONE, PrevailingWind.NONE);
        PrevailingWind.sample(seed, base + 6000, rain, s);
        if (s.calm != 0) {
            helper.fail("雨の間に凪になった");
            return;
        }
        helper.succeed();
    }

    /** 濃霧は雨上がり(夜〜朝に止んだ雨)と朝方に起き、数千 tick で終わる。雨の間は無い。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void fogComesAfterRainAndInMornings(GameTestHelper helper) {
        long seed = 4242L;
        int[] kind = new int[1];
        // 雨上がり: 20 時過ぎ(tod 15000)に止んだ雨を、霧の出る止み方が見つかるまで探す
        long end = -1;
        for (long d = 0; d < 200 && end < 0; d++) {
            long e = d * PrevailingWind.DAY + 15000;
            PrevailingWind.Weather w = new PrevailingWind.Weather(false, false, e - 6000, e, PrevailingWind.NONE, PrevailingWind.NONE);
            if (PrevailingWind.fogLevel(seed, e + 2000, w, kind) > 0.99 && kind[0] == 1) end = e;
        }
        if (end < 0) {
            helper.fail("雨上がりの濃霧が 200 回の雨で 1 度も出ない");
            return;
        }
        PrevailingWind.Weather w = new PrevailingWind.Weather(false, false, end - 6000, end, PrevailingWind.NONE, PrevailingWind.NONE);
        if (PrevailingWind.fogLevel(seed, end + 7500, w, kind) != 0) {
            helper.fail("雨上がりの濃霧が 7500 tick を過ぎても晴れない");
            return;
        }
        PrevailingWind.Weather raining = new PrevailingWind.Weather(true, false, end - 6000, PrevailingWind.NONE, PrevailingWind.NONE, PrevailingWind.NONE);
        if (PrevailingWind.fogLevel(seed, end + 2000, raining, kind) != 0) {
            helper.fail("雨の間に濃霧が出た");
            return;
        }
        // 朝方: 雨の無い日に、朝 6 時前後(tod 0)の霧が出る日を探す。昼過ぎ(tod 8000)には晴れている
        int mornings = 0;
        for (long d = 1; d < 1000; d++) {
            double m = PrevailingWind.fogLevel(seed, d * PrevailingWind.DAY, PrevailingWind.Weather.CLEAR, kind);
            if (m > 0.5) {
                mornings++;
                if (PrevailingWind.fogLevel(seed, d * PrevailingWind.DAY + 8000, PrevailingWind.Weather.CLEAR, kind) != 0) {
                    helper.fail("朝方の濃霧が昼過ぎまで残った(日 " + d + ")");
                    return;
                }
            }
        }
        LOG.info("WEATHER_MEASURE 朝方の濃霧 1000 日中 {} 日", mornings);
        if (mornings < 30 || mornings > 120) {
            helper.fail("朝方の濃霧の割合がおかしい: 1000 日中 " + mornings);
            return;
        }
        helper.succeed();
    }

    /** 汚染の森への落雷: その升に胞子を放出し、一部を上空の層へ持ち上げる(雲より上の風で遠くへ = 飛び地のもと)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void lightningLiftsForestSpores(GameTestHelper helper) {
        SporeField plain = forest(), struck = forest();
        PrevailingWind.Sample wind = eastWind(1.0, 0, 0);
        struck.lightningBurst(0, 0, WorldWeather.STRIKE_EMIT, WorldWeather.STRIKE_LIFT);
        if (!(struck.air(0, 0) > plain.air(0, 0))) {
            helper.fail("落雷で升の浮遊が増えない");
            return;
        }
        for (int k = 1; k <= 25; k++) {
            long t = k * 100L;
            plain.step(new SporeField.Conditions(t, t, false, false, false), wind);
            struck.step(new SporeField.Conditions(t, t, false, false, false), wind);
        }
        double pp = 0, ps = 0;
        for (SporeField.Parcel p : plain.parcels) pp += p.mass;
        for (SporeField.Parcel p : struck.parcels) ps += p.mass;
        LOG.info("WEATHER_MEASURE 落雷 上空の塊 {} → {}", pp, ps);
        if (!(ps > pp)) {
            helper.fail("落雷で上空の塊が増えない: 落雷なし " + pp + " 落雷 " + ps);
            return;
        }
        helper.succeed();
    }

    /** 汚染されていない所への落雷は何もしない。今の天気はテストの世界でも作れる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void plainLightningDoesNothing(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        if (WorldWeather.strike(helper.getLevel(), pos)) {
            helper.fail("汚染されていない所の落雷で胞子を吹き上げた");
            return;
        }
        WeatherPayload p = WorldWeather.compute(helper.getLevel().getServer().overworld());
        if (!(p.fog() >= 0 && p.fog() <= 1 && p.calm() >= 0 && p.calm() <= 1 && p.gale() >= 0 && p.gale() <= 1)) {
            helper.fail("今の天気の値が 0〜1 の外: " + p);
            return;
        }
        helper.succeed();
    }
}
