package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.spore.MorningMist;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>朝霧</b>({@link MorningMist}。2026-10-16 ユーザー指定「朝霧の谷に胞子がたまる」)の式のテスト。
 *
 * <p>サーバー(感染が進みやすい・胞子の露)とクライアント(霞)が同じ式で答えを出すので、式が時刻と朝の番号だけで決まること、
 * 昼・雨には出ないこと、層の中の深さの形がシェーダー(ov_mist.glsl の ovMistF)と同じことを守る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class MorningMistGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(MorningMistGameTests.class);
        }

        private Registration() {
        }
    }

    /** 3 時にでき始め、5〜7 時がいちばん濃く、9 時半に晴れる。昼・夕方・夜中は出ない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void mistOnlyAtDawn(GameTestHelper helper) {
        // バニラの dayTime 0 = 朝 6 時、6000 = 正午、12000 = 夕方 6 時、18000 = 真夜中。
        long[] none = {6000L, 9000L, 12000L, 15000L, 18000L, 20000L};
        for (long t : none) {
            if (MorningMist.timeCurve(t) != 0.0) {
                helper.fail("霧の出ない時刻に出た: dayTime " + t + " → " + MorningMist.timeCurve(t));
                return;
            }
        }
        if (MorningMist.timeCurve(0L) != 1.0 || MorningMist.timeCurve(-500L + 24000L) != 1.0) {
            helper.fail("日の出の前後(5〜7 時)がいちばん濃くない");
            return;
        }
        double forming = MorningMist.timeCurve(22000L);
        double clearing = MorningMist.timeCurve(2500L);
        if (!(forming > 0.0 && forming < 1.0) || !(clearing > 0.0 && clearing < 1.0)) {
            helper.fail("4 時・8 時半が途中の濃さでない: " + forming + " / " + clearing);
            return;
        }
        // 朝の番号は真夜中で替わる(夜明け前と夜明けの後は同じ朝)。
        if (MorningMist.morningKey(22000L) != MorningMist.morningKey(24000L + 2000L)
                || MorningMist.morningKey(17999L) == MorningMist.morningKey(18000L)) {
            helper.fail("朝の番号の替わり目が真夜中でない");
            return;
        }
        helper.succeed();
    }

    /** 朝ごとの当たりは番号だけで決まり、2 割ほどの朝は出ず、出る朝は 0.45〜1。雨では出ない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void morningsVaryAndRainClears(GameTestHelper helper) {
        int none = 0;
        int count = 2000;
        for (long key = -1000; key < count - 1000; key++) {
            double a = MorningMist.morningAmount(key);
            if (a != MorningMist.morningAmount(key)) {
                helper.fail("同じ朝で当たりが変わった: " + key);
                return;
            }
            if (a == 0.0) {
                none++;
            } else if (a < 0.45 || a > 1.0) {
                helper.fail("出る朝の濃さが範囲の外: " + a);
                return;
            }
        }
        double share = none / (double) count;
        if (share < 0.15 || share > 0.25) {
            helper.fail("霧の出ない朝の割合が 2 割から外れた: " + share);
            return;
        }
        // 霧の出る朝を探して、雨で消えることを確かめる。
        long dawn = -1L;
        for (long day = 0; day < 50 && dawn < 0; day++) {
            if (MorningMist.morningAmount(day) > 0.0) {
                dawn = day * 24000L;
            }
        }
        if (MorningMist.strength(dawn, 0.0) <= 0.0 || MorningMist.strength(dawn, 1.0) != 0.0) {
            helper.fail("晴れた朝に出ない、または雨の朝に出た");
            return;
        }
        helper.succeed();
    }

    /** 層の中の深さ: てっぺんより上は 0、てっぺんから SOFT ブロックで 1 まで直線、その下は 1(シェーダーの ovMistF の微分と同じ形)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void layerProfileMatchesShader(GameTestHelper helper) {
        double soft = MorningMist.SOFT;
        if (MorningMist.insideFactor(0.5) != 0.0 || MorningMist.insideFactor(0.0) != 0.0
                || MorningMist.insideFactor(-soft) != 1.0 || MorningMist.insideFactor(-20.0) != 1.0) {
            helper.fail("層の端の深さが違う");
            return;
        }
        double half = MorningMist.insideFactor(-soft / 2.0);
        if (Math.abs(half - 0.5) > 1.0E-9) {
            helper.fail("てっぺんのぼかしが直線でない: " + half);
            return;
        }
        // 空気中の胞子: 霧の朝 > 夜 > 真昼、雨で減る。
        double noon = MorningMist.airSpores(6000L, 0.0, 0.0);
        double night = MorningMist.airSpores(18000L, 0.0, 0.0);
        double misty = MorningMist.airSpores(0L, 0.0, 1.0);
        double rainy = MorningMist.airSpores(6000L, 1.0, 0.0);
        if (!(misty > night && night > noon && rainy < noon)) {
            helper.fail("空気中の胞子の並びが違う: 霧の朝 " + misty + " 夜 " + night + " 真昼 " + noon + " 雨 " + rainy);
            return;
        }
        helper.succeed();
    }
}
