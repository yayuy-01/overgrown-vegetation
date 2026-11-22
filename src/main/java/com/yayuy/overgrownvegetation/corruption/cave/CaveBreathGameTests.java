package com.yayuy.overgrownvegetation.corruption.cave;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeField;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 洞窟が胞子を吸い込む・吐き出す(2026-11-20)の確かめ。胞子の場は世界のものを使わず、テストごとに作った場で回す(ほかのテストとぶつからない)。
 * <ul>
 *   <li>吸う・吐く・凪の決まり(見た目の CaveBreath と同じ: 風と口の外向きの内積。縦穴は夜に吸い昼に吐く)。</li>
 *   <li>風が吹き込む口は地下の層が上がり、洞窟の床が芽吹く。吹き出す口・凪の口では吸わない。</li>
 *   <li>地脈の線に近い洞窟ほど早く芽吹く。</li>
 *   <li>中が汚染された洞窟の吹き出す口は発生源になり、風下の升の浮遊が上がる。入口 B の口はいちばん強い。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CaveBreathGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CaveBreathGameTests.class);
        }

        private Registration() {
        }
    }

    @GameTest(template = "gametest_empty7", batch = "breath_a")
    public static void inhaleExhaleCalmRule(GameTestHelper h) {
        CaveTable.Breath t = DesignData.cave().breath;
        double out = 0.0D; // 口は東(+x)へ開いている
        double in = CaveBreathing.flow(out, -1, 0, t.flowThreshold, true);  // 西風が口へ吹き込む
        double ex = CaveBreathing.flow(out, 1, 0, t.flowThreshold, true);   // 東へ吹き出す
        double calm = CaveBreathing.flow(out, 0, 1, t.flowThreshold, true); // 口と平行
        h.assertTrue(in > 0.9D && ex < -0.9D && calm == 0.0D, "出入りの決まりが違う: 吸う " + in + "・吐く " + ex + "・凪 " + calm);
        h.assertTrue(CaveBreathing.soakFactor(in, false, 1.0D, t) > 1.0f, "吹き込む口が吸わない");
        h.assertTrue(CaveBreathing.soakFactor(ex, false, 1.0D, t) == 0.0f && CaveBreathing.soakFactor(calm, false, 1.0D, t) == 0.0f, "吹き出す・凪の口が吸った");
        h.assertTrue(CaveBreathing.flow(Double.NaN, 1, 0, t.flowThreshold, false) > 0 && CaveBreathing.flow(Double.NaN, 1, 0, t.flowThreshold, true) < 0,
                "縦穴が夜に吸い昼に吐かない");
        double full = 1.0D;
        double dirty = CaveBreathing.exhaleAmount(ex, 1.0D, false, 1.0D, t, full), outlet = CaveBreathing.exhaleAmount(ex, 1.0D, true, 1.0D, t, full);
        h.assertTrue(dirty > 0 && outlet > dirty * 2.0D, "入口 B がいちばん強く吐かない: " + dirty + " / " + outlet);
        h.assertTrue(CaveBreathing.exhaleAmount(ex, 0.0D, false, 1.0D, t, full) == 0.0D && CaveBreathing.exhaleAmount(in, 1.0D, false, 1.0D, t, full) == 0.0D,
                "きれいな洞窟・吸う口が吐いた");
        h.succeed();
    }

    /** 場を 1 つ作り、(3, 3) に浮遊を置いて口の多さ 1 にし、その升の染み込みの倍率を breath にして days 日回す。洞窟の床が芽吹いた日(芽吹かなければ −1)を返す。 */
    static double[] run(float breath, long leySeed, double leyGermMax, int cx, int cz, double air, double days) {
        SporeField.Params p = SporeFieldTable.gameDefaults();
        SporeField f = new SporeField(9L, p);
        f.emitPoint(cx, cz, air);
        f.setMouth(cx, cz, 1.0f);
        Long2FloatOpenHashMap m = new Long2FloatOpenHashMap();
        m.put(ChunkPos.asLong(cx, cz), breath);
        f.breathSoak = m;
        f.leySeed = leySeed;
        f.leyGermMax = leyGermMax;
        PrevailingWind.Sample calm = new PrevailingWind.Sample();
        double first = -1;
        double maxUnder = 0;
        int steps = (int) (days * PrevailingWind.DAY / p.stepTicks);
        for (int k = 1; k <= steps; k++) {
            f.step(new SporeField.Conditions((long) k * p.stepTicks, false, false, false), calm);
            maxUnder = Math.max(maxUnder, f.under(cx, cz) + f.underDeposited(cx, cz));
            if (first < 0 && f.caveCount(cx, cz) > 0) first = (double) k * p.stepTicks / PrevailingWind.DAY;
        }
        return new double[]{first, maxUnder, f.isCorrupted(cx, cz) ? 1 : 0};
    }

    @GameTest(template = "gametest_empty7", batch = "breath_b", timeoutTicks = 200)
    public static void windwardMouthSoaksAndSprouts(GameTestHelper h) {
        double[] in = run((float) DesignData.cave().breath.inhaleGain, 0, 1.0, 3, 3, 0.3, 4);
        double[] out = run(0.0f, 0, 1.0, 3, 3, 0.3, 4);
        String info = String.format("吸う口: 地下 %.4f・芽吹いた日 %.2f・地上 %s / 吸わない口: 地下 %.4f・芽吹いた日 %.2f", in[1], in[0], in[2] > 0 ? "汚染" : "きれい", out[1], out[0]);
        OvergrownVegetation.LOGGER.info("[breath test] {}", info);
        h.assertTrue(in[1] > 0 && out[1] == 0.0D, "吹き出す・凪の口が吸った: " + info);
        h.assertTrue(in[0] > 0, "吹き込む口の洞窟が芽吹かない: " + info);
        h.succeed();
    }

    @GameTest(template = "gametest_empty7", batch = "breath_c", timeoutTicks = 200)
    public static void leyLineCaveSproutsSooner(GameTestHelper h) {
        long seed = 12345L;
        // 地脈の線の真上のチャンクと、遠いチャンクを探す
        int near = -1, far = -1;
        for (int cx = 0; cx < 4000 && (near < 0 || far < 0); cx++) {
            double d = LeyLines.distanceToLine(seed, cx * 16 + 8, 8);
            if (near < 0 && d < 4) near = cx;
            if (far < 0 && d > 90) far = cx;
        }
        double[] a = run((float) DesignData.cave().breath.inhaleGain, seed, 3.0, near, 0, 0.12, 6);
        double[] b = run((float) DesignData.cave().breath.inhaleGain, seed, 3.0, far, 0, 0.12, 6);
        String info = String.format("線の上: 芽吹いた日 %.2f / 遠い: %.2f", a[0], b[0]);
        OvergrownVegetation.LOGGER.info("[breath test] {}", info);
        h.assertTrue(a[0] > 0 && (b[0] < 0 || a[0] < b[0]), "地脈の線に近い洞窟が早く芽吹かない: " + info);
        h.succeed();
    }

    /** 吐く口(発生源)の風下の升の浮遊が、吐かない場合より上がる。 */
    @GameTest(template = "gametest_empty7", batch = "breath_d", timeoutTicks = 200)
    public static void exhaleIsASourceDownwind(GameTestHelper h) {
        CaveTable.Breath t = DesignData.cave().breath;
        SporeField.Params p = SporeFieldTable.gameDefaults();
        PrevailingWind.Sample east = new PrevailingWind.Sample();
        east.dirX = 1;
        east.strength = 1;
        double flow = CaveBreathing.flow(0.0D, 1, 0, t.flowThreshold, true);
        double amount = CaveBreathing.exhaleAmount(flow, 1.0D, true, 1.0D, t, 1.0D);
        SporeField with = new SporeField(5L, p), without = new SporeField(5L, p);
        int every = Math.max(1, t.intervalTicks / p.stepTicks);
        for (int k = 1; k <= PrevailingWind.DAY / p.stepTicks; k++) {
            if (k % every == 0) with.emitPoint(0, 0, amount);
            with.step(new SporeField.Conditions((long) k * p.stepTicks, false, false, false), east);
            without.step(new SporeField.Conditions((long) k * p.stepTicks, false, false, false), east);
        }
        double down = 0, up = 0;
        for (int x = 1; x <= 6; x++) down += with.air(x, 0);
        for (int x = -6; x <= -1; x++) up += with.air(x, 0);
        String info = String.format("風下 %.4f / 風上 %.4f / 吐かない場の風下 %.4f", down, up, without.air(3, 0));
        OvergrownVegetation.LOGGER.info("[breath test] {}", info);
        h.assertTrue(down > 0 && down > up && down > without.air(3, 0) * 6 + 1e-9, "吐く口が風下の発生源にならない: " + info);
        h.succeed();
    }
}
