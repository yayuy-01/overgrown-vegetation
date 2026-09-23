package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.ErosionFood;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>浸食度</b>の効き方のテスト。
 *
 * <p>ユーザー指定の規則は「<b>100% のときの効果 × 現在の %</b>」——
 * 最大体力 -50% は 34% のとき -17%(体力 20 なら -3.4)。ここが狂うと
 * 「少し潜っただけで半減する」か「100% でも何も起きない」のどちらかになる。
 *
 * <p>{@code climax.json} の値そのものではなく<b>比例関係</b>を見ているので、
 * 数値を調整してもテストは壊れない(調整のたびにテストを直す羽目になると、
 * テストの方を先に消すことになる)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ErosionGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ErosionGameTests.class);
        }

        private Registration() {
        }
    }

    private static final double EPSILON = 1.0e-6D;

    /** 効き具合は 0〜1 の直線で、範囲外は頭打ち。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void scaleIsLinearAndClamped(GameTestHelper helper) {
        ClimaxTable table = DesignData.climax();
        double max = table.maxPercent();

        if (Math.abs(table.scale(0.0D)) > EPSILON) {
            helper.fail("0% なのに効いている: " + table.scale(0.0D));
            return;
        }
        if (Math.abs(table.scale(max) - 1.0D) > EPSILON) {
            helper.fail("最大 % で 1.0 にならない: " + table.scale(max));
            return;
        }
        if (Math.abs(table.scale(max * 2.0D) - 1.0D) > EPSILON) {
            helper.fail("最大を超えた % が頭打ちになっていない: " + table.scale(max * 2.0D));
            return;
        }
        if (Math.abs(table.scale(-5.0D)) > EPSILON) {
            helper.fail("負の % が 0 になっていない: " + table.scale(-5.0D));
            return;
        }
        double half = table.scale(max * 0.5D);
        if (Math.abs(half - 0.5D) > EPSILON) {
            helper.fail("半分の % で 0.5 にならない(直線でない): " + half);
            return;
        }
        helper.succeed();
    }

    /**
     * ユーザー指定の数値例をそのまま見る —— 最大体力 -50%・浸食度 34%・体力 20 で <b>-3.4</b>。
     * {@code climax.json} を調整したときは、その値での期待値に読み替えて比べる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void maxHealthDropsInProportion(GameTestHelper helper) {
        ClimaxTable table = DesignData.climax();
        double atFull = table.maxHealthReduction();
        double at34 = table.maxHealthReduction() * table.scale(34.0D);

        // 34% のときの効きは、100% のときのちょうど 0.34 倍。
        double expected = atFull * (34.0D / table.maxPercent());
        if (Math.abs(at34 - expected) > EPSILON) {
            helper.fail("34% の効きが比例していない: " + at34 + " / 期待 " + expected);
            return;
        }
        // 体力 20 に当てはめた減少量。
        double lost = 20.0D * at34;
        double expectedLost = 20.0D * expected;
        if (Math.abs(lost - expectedLost) > EPSILON) {
            helper.fail("体力の減少量が合わない: " + lost + " / 期待 " + expectedLost);
            return;
        }
        // 既定値(-50%)のときだけ、指定された -3.4 を直接確かめる。
        if (Math.abs(atFull - 0.5D) < EPSILON && Math.abs(table.maxPercent() - 100.0D) < EPSILON) {
            if (Math.abs(lost - 3.4D) > 1.0e-4D) {
                helper.fail("既定値での減少量が -3.4 でない: -" + lost);
                return;
            }
        }
        helper.succeed();
    }

    /** 装備の汚染速度の<b>上乗せ</b>ぶんが % に比例する(100% で +500% = 6 倍)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void gearContaminationScalesInProportion(GameTestHelper helper) {
        ClimaxTable table = DesignData.climax();
        double max = table.maxPercent();
        double bonusAtFull = table.gearContaminationBonus() * table.scale(max);
        double bonusAt34 = table.gearContaminationBonus() * table.scale(34.0D);

        if (Math.abs(bonusAt34 - bonusAtFull * (34.0D / max)) > EPSILON) {
            helper.fail("上乗せが比例していない: " + bonusAt34);
            return;
        }
        // 既定値(+500%)のときだけ、倍率そのものを確かめる。
        if (Math.abs(table.gearContaminationBonus() - 5.0D) < EPSILON
                && Math.abs(max - 100.0D) < EPSILON) {
            double factorAtFull = 1.0D + bonusAtFull;
            if (Math.abs(factorAtFull - 6.0D) > EPSILON) {
                helper.fail("100% での倍率が 6 倍でない: " + factorAtFull);
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 増える速さより<b>減る速さの方が速い</b>(極相に 100 分居て溜めたものが、外で 50 分で抜ける)。
     * ここが逆転していると、一度潜ったら二度と抜けられなくなる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void decayIsFasterThanGain(GameTestHelper helper) {
        ClimaxTable table = DesignData.climax();
        double gainPerTick = table.gainPerInterval() / table.gainIntervalTicks();
        double decayPerTick = table.decayPerInterval() / table.decayIntervalTicks();
        if (decayPerTick <= gainPerTick) {
            helper.fail("減る速さが増える速さ以下: 増 " + gainPerTick + " / 減 " + decayPerTick);
            return;
        }
        helper.succeed();
    }


    /** 食料ゲージの上限が % に比例して減り、0% ではバニラのまま。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void foodMaxDropsInProportion(GameTestHelper helper) {
        ClimaxTable table = DesignData.climax();
        float max = (float) table.maxPercent();

        if (ErosionFood.maxFood(0.0F) != ErosionFood.VANILLA_MAX_FOOD) {
            helper.fail("0% で上限がバニラと違う: " + ErosionFood.maxFood(0.0F));
            return;
        }
        int atFull = ErosionFood.maxFood(max);
        int atHalf = ErosionFood.maxFood(max * 0.5F);
        if (!(atFull <= atHalf && atHalf <= ErosionFood.VANILLA_MAX_FOOD)) {
            helper.fail("上限が % に対して単調に減っていない: 満 " + atFull + " / 半 " + atHalf);
            return;
        }
        if (atFull < 1) {
            helper.fail("上限が 1 未満になった: " + atFull);
            return;
        }
        helper.succeed();
    }

    /**
     * <b>ゲージを満たすのに要る食料の数が変わらない</b> —— ユーザー指定のいちばん大事な規則。
     * 上限が半分でも回復量が半分なら、満たすのに要る個数は同じになる。
     * ここが崩れると「小さくなったゲージを楽に満たせる」ことになり、上限を下げた意味が消える。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void refillCostIsUnchanged(GameTestHelper helper) {
        ClimaxTable table = DesignData.climax();
        for (float percent : new float[]{0.0F, 25.0F, 50.0F, 75.0F, (float) table.maxPercent()}) {
            double factor = ErosionFood.restoreFactor(percent);
            if (factor <= 0.0D) {
                helper.fail(percent + "% で回復量が 0 倍になった(食べても永久に満たせない)");
                return;
            }
            // 上限 ÷ 回復倍率 = 「バニラ換算で何ぶん食べる必要があるか」。上限の丸めぶん ±1 を許す。
            double effective = ErosionFood.maxFood(percent) / factor;
            if (Math.abs(effective - ErosionFood.VANILLA_MAX_FOOD) > 1.0D) {
                helper.fail(percent + "% で必要な食料の量が変わった: バニラ換算 " + effective
                        + " / 期待 " + ErosionFood.VANILLA_MAX_FOOD);
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 自然回復の判定が上限に追随する —— ユーザー指定「減った分の中で最大値になれば体力は回復する」。
     * ここが固定値のままだと、上限が 18 を下回った瞬間に<b>一切回復しなくなる</b>。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void regenThresholdFollowsMax(GameTestHelper helper) {
        ClimaxTable table = DesignData.climax();
        if (ErosionFood.regenThreshold(0.0F) != 18) {
            helper.fail("0% で回復の閾値がバニラ(18)と違う: " + ErosionFood.regenThreshold(0.0F));
            return;
        }
        float max = (float) table.maxPercent();
        int cap = ErosionFood.maxFood(max);
        int threshold = ErosionFood.regenThreshold(max);
        if (threshold > cap) {
            helper.fail("回復の閾値が上限を超えている(永久に回復しない): 閾値 " + threshold + " / 上限 " + cap);
            return;
        }
        if (threshold < 1) {
            helper.fail("回復の閾値が 1 未満: " + threshold);
            return;
        }
        helper.succeed();
    }
    private ErosionGameTests() {
    }
}
