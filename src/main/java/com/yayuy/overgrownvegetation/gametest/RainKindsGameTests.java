package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.sky.RainIntensity;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeField;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 雨の種類と胞子(2026-11-12、ユーザー決定「雨が胞子を洗い落とす・湿った地面で発芽が速い・風で飛びにくい」「雷雨は巻き上げる」)。
 * 場の式は素の Java なので、ワールドを使わずに同じ放出から小雨・普通・大雨・雷雨を回して比べる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class RainKindsGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterRainKindsGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(RainKindsGameTests.class);
        }

        private Registration() {
        }
    }

    private RainKindsGameTests() {
    }

    /** 放出してから steps 回、決まった天気で回した場。 */
    private static SporeField run(boolean raining, boolean thundering, float intensity, int steps) {
        SporeField f = new SporeField(5L, SporeFieldTable.gameDefaults());
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) f.emitPoint(x, z, 5.0);
        PrevailingWind.Sample wind = new PrevailingWind.Sample();
        wind.dirX = 1;
        wind.strength = 1.5;
        for (int k = 1; k <= steps; k++) {
            f.step(new SporeField.Conditions(k * 100L, k * 100L, raining, thundering, false, intensity, RainIntensity.NORMAL), wind);
        }
        return f;
    }

    private static double sumAir(SporeField f) {
        double s = 0;
        for (int x = -12; x <= 24; x++) for (int z = -12; z <= 12; z++) s += f.air(x, z);
        return s;
    }

    private static double sumDeposit(SporeField f) {
        double s = 0;
        for (int x = -12; x <= 24; x++) for (int z = -12; z <= 12; z++) s += f.deposited(x, z);
        return s;
    }

    /** 大雨は小雨より空中の胞子を多く地面へ落とし、空気が澄む。 */
    @GameTest(template = "gametest_empty7")
    public static void heavyRainWashesMoreThanLight(GameTestHelper helper) {
        SporeField light = run(true, false, RainIntensity.LIGHT, 3);
        SporeField heavy = run(true, false, RainIntensity.HEAVY, 3);
        double depL = sumDeposit(light), depH = sumDeposit(heavy), airL = sumAir(light), airH = sumAir(heavy);
        if (!(depH > depL)) {
            helper.fail("大雨の降り積もりが小雨より多くない: 大雨 " + depH + " 小雨 " + depL);
            return;
        }
        if (!(airH < airL)) {
            helper.fail("大雨の後の空気が小雨より澄んでいない: 大雨 " + airH + " 小雨 " + airL);
            return;
        }
        helper.succeed();
    }

    /** 普通の雨は今までの式(強さを渡さない作り方)と同じ答え。 */
    @GameTest(template = "gametest_empty7")
    public static void normalRainKeepsOldNumbers(GameTestHelper helper) {
        SporeField a = run(true, false, RainIntensity.NORMAL, 4);
        SporeField b = new SporeField(5L, SporeFieldTable.gameDefaults());
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) b.emitPoint(x, z, 5.0);
        PrevailingWind.Sample wind = new PrevailingWind.Sample();
        wind.dirX = 1;
        wind.strength = 1.5;
        for (int k = 1; k <= 4; k++) b.step(new SporeField.Conditions(k * 100L, k * 100L, true, false, false), wind);
        if (Math.abs(sumDeposit(a) - sumDeposit(b)) > 1e-9 || Math.abs(sumAir(a) - sumAir(b)) > 1e-9) {
            helper.fail("普通の雨の答えが強さ無しの作り方と違う");
            return;
        }
        helper.succeed();
    }

    /** 雷雨は雷の無い大雨より胞子を空に残す(巻き上げる・放出が増える)。 */
    @GameTest(template = "gametest_empty7")
    public static void thunderLiftsInsteadOfWashing(GameTestHelper helper) {
        SporeField heavy = run(true, false, RainIntensity.HEAVY, 3);
        SporeField storm = run(true, true, RainIntensity.HEAVY, 3);
        double depH = sumDeposit(heavy), depS = sumDeposit(storm);
        if (!(depS < depH)) {
            helper.fail("雷雨なのに大雨より多く降り積もった: 雷雨 " + depS + " 大雨 " + depH);
            return;
        }
        helper.succeed();
    }
}
