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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
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

    /**
     * 天気の無い日は天気の分岐に入らない: 凪でも強風でもない日の風は凪・強風の度合いが 0 で、地上の風 = 上空の風。度合い 0 の風で回した場は、
     * 度合いの欄に触れない風で回した場と同じ答え。<b>前のコードとの比べではない</b>(前と同じなのは、分岐で素通りする作りと、前からの胞子のテスト
     * RainKindsGameTests・SporeFieldGameTests が通ることで見ている)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void zeroWeatherTakesNoBranch(GameTestHelper helper) {
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

    // ---------------------------------------------------------------- 本物の日の風で測る(風を一定にしない。11-16 見直し)

    /** 場を回すときに風を書き換える(凪・強風を切って比べる)。 */
    private interface WindEdit {
        void edit(PrevailingWind.Sample s);
    }

    /** 日 startDay の 0 時(dayTime = 日 × 24000、6 時)から steps 回、本物の卓越風で回した場。 */
    private static SporeField runReal(long seed, long startDay, int steps, double galeGroundWind, WindEdit edit) {
        SporeField f = forest();
        if (!Double.isNaN(galeGroundWind)) f.p.galeGroundWind = galeGroundWind;
        PrevailingWind.Sample s = new PrevailingWind.Sample();
        long base = startDay * PrevailingWind.DAY;
        for (int k = 1; k <= steps; k++) {
            long t = base + k * 100L;
            PrevailingWind.sample(seed, t, PrevailingWind.Weather.CLEAR, s);
            if (edit != null) edit.edit(s);
            f.step(new SporeField.Conditions(t, t, false, false, false), s);
        }
        return f;
    }

    /** 森(原点)から汚染された升までのいちばん遠い距離(チャンク。向きは問わない。本物の風は向きが回るので)。 */
    private static double reach(SporeField f) {
        double best = 0;
        for (int x = -90; x <= 90; x++) for (int z = -90; z <= 90; z++) if (f.isCorrupted(x, z)) best = Math.max(best, Math.hypot(x, z));
        return best;
    }

    /** 森のそば(原点から 6 チャンク以内)と遠く(12〜40)の浮遊の合計。 */
    private static double[] nearFarRing(SporeField f) {
        double near = 0, far = 0;
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                double d = Math.hypot(x, z);
                if (d <= 6) near += f.air(x, z);
                else if (d >= 12 && d <= 40) far += f.air(x, z);
            }
        }
        return new double[]{near, far};
    }

    /**
     * 本物の凪の日(5 時から 20 時まで凪)の正午 ~ 夕方: 凪をそのまま回した場は、同じ日の凪だけを切った場より森のそばが濃く、遠くが澄む。
     * 強風の日: 同じ日を、林床を運ばない(前からの強風の扱い)場・ふつうの日と比べて前線の届きを測る(数を決めるため。ログ WEATHER_MEASURE)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 600)
    public static void realDaysCalmAndGale(GameTestHelper helper) {
        long seed = 31337L;
        long calmDay = -1, galeDay = -1, plainDay = -1;
        for (long d = 1; d < 400 && (calmDay < 0 || galeDay < 0 || plainDay < 0); d++) {
            boolean strong = PrevailingWind.isStrongDay(seed, d, PrevailingWind.Weather.CLEAR);
            boolean calm = PrevailingWind.isCalmDay(seed, d, PrevailingWind.Weather.CLEAR);
            boolean next = PrevailingWind.isStrongDay(seed, d + 1, PrevailingWind.Weather.CLEAR) || PrevailingWind.isCalmDay(seed, d + 1, PrevailingWind.Weather.CLEAR);
            if (calm && calmDay < 0) calmDay = d;
            if (strong && galeDay < 0 && !next) galeDay = d;
            if (!strong && !calm && !next && plainDay < 0) plainDay = d;
        }
        if (calmDay < 0 || galeDay < 0 || plainDay < 0) {
            helper.fail("凪・強風・ふつうの日が見つからない");
            return;
        }
        // 凪: その日の 6 時から 16 時(100 step)まで。凪を切った場と比べる
        SporeField calmF = runReal(seed, calmDay, 100, Double.NaN, null);
        SporeField noCalm = runReal(seed, calmDay, 100, Double.NaN, s -> s.calm = 0);
        double[] c = nearFarRing(calmF), n = nearFarRing(noCalm);
        LOG.info("WEATHER_MEASURE 本物の凪の日(6〜16 時) 森のそば {} → {}(×{})・遠く {} → {}(×{})", n[0], c[0], c[0] / n[0], n[1], c[1],
                c[1] / Math.max(1e-12, n[1]));
        // 強風: その日の 6 時から 2 日
        double gale = reach(runReal(seed, galeDay, 480, Double.NaN, null));
        double galeOld = reach(runReal(seed, galeDay, 480, 0, null));
        double plain = reach(runReal(seed, plainDay, 480, Double.NaN, null));
        double[] sweep = new double[4];
        double[] gs = {10, 40, 80, 160};
        for (int i = 0; i < gs.length; i++) sweep[i] = reach(runReal(seed, galeDay, 480, gs[i], null));
        LOG.info("WEATHER_MEASURE 本物の強風の日(2 日) 届き ふつうの日 {}・前からの強風 {}・林床も運ぶ強風 {}(galeGroundWind 10 → {}・40 → {}・80 → {}・160 → {})",
                plain, galeOld, gale, sweep[0], sweep[1], sweep[2], sweep[3]);
        if (!(c[0] > n[0] && c[1] < n[1])) {
            helper.fail("本物の凪の日に森のそばが濃く・遠くが澄む、にならない: そば " + n[0] + " → " + c[0] + " 遠く " + n[1] + " → " + c[1]);
            return;
        }
        if (!(gale > galeOld)) {
            helper.fail("本物の強風の日に林床を運んでも前線が伸びない: 前からの強風 " + galeOld + " 林床も運ぶ " + gale);
            return;
        }
        helper.succeed();
    }

    /**
     * 積乱雲の下の落雷の場所(11-16 ユーザー決定「積乱雲の雷に合わせて汚染の森へも落とす」): 雲の下の半分が汚染の森なら、森を選ぶ方が多い。
     * 雲の下に巨大樹があれば、巨大樹に落ちることがある。どれも雲の胴の内側。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void cloudStrikesPreferForestAndTrees(GameTestHelper helper) {
        net.minecraft.util.RandomSource rnd = net.minecraft.util.RandomSource.create(99L);
        double cx = 1000, cz = 2000, r = 70;
        // 東半分(x ≥ 1000 のチャンク)だけ汚染の森
        java.util.function.LongPredicate east = k -> net.minecraft.world.level.ChunkPos.getX(k) * 16 >= 1000;
        int forest = 0, n = 2000;
        for (int i = 0; i < n; i++) {
            com.yayuy.overgrownvegetation.sky.StormLightning.Spot s = com.yayuy.overgrownvegetation.sky.StormLightning.choose(cx, cz, r, rnd, new long[0], east);
            if (Math.hypot(s.x() - cx, s.z() - cz) > r + 1e-6) {
                helper.fail("雲の胴の外に落ちた: " + s);
                return;
            }
            if (s.forest()) forest++;
        }
        long tree = ((long) 1020 << 32) | (2010 & 0xFFFFFFFFL);
        int onTree = 0;
        for (int i = 0; i < n; i++) {
            if (com.yayuy.overgrownvegetation.sky.StormLightning.choose(cx, cz, r, rnd, new long[]{tree}, k -> false).tree()) onTree++;
        }
        LOG.info("WEATHER_MEASURE 雲の落雷 東半分が森のとき森 {}/{}・巨大樹が下にあるとき巨大樹 {}/{}", forest, n, onTree, n);
        if (!(forest > n * 0.65)) {
            helper.fail("雲の下の半分が森なのに森を選ぶ方が多くない: " + forest + "/" + n);
            return;
        }
        if (!(onTree > n * 0.35 && onTree < n * 0.65)) {
            helper.fail("巨大樹に落ちる割合がおかしい: " + onTree + "/" + n);
            return;
        }
        helper.succeed();
    }
}
