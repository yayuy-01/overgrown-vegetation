package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EconomyTable;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestEconomy;
import com.yayuy.overgrownvegetation.director.ForestUpkeep;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 物差しを 100 倍・稼ぎと維持費を毎秒に(2026-11-21 前線と部隊 区切り 1)。
 * 維持費の式・8 割の関所・「ふつうに出しているだけでは足りなくならない」を確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ForestUpkeepGameTests {

    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void onRegisterForestUpkeepGameTests(net.minecraftforge.event.RegisterGameTestsEvent event) { // 1.20.1: 受け手の名前をクラスごとに
            event.register(ForestUpkeepGameTests.class);
        }

        private Registration() {
        }
    }

    /** 関所の数を決め打ちにするので、ほかの予算のテストと並ばないよう別の組にする。 */
    private static final String BATCH = "forest_upkeep";

    private ForestUpkeepGameTests() {
    }

    /** 物差し: 段階 0 の感染 Mob の生産 = 100。危険度 1 の値段の元も 100 倍(300)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void scaleIsHundredForStageZero(GameTestHelper helper) {
        helper.assertTrue(Math.abs(DesignData.director().largeRaid.costStageZero - 100.0D) < 1.0E-9D,
                "段階 0 の生産が 100 でない: " + DesignData.director().largeRaid.costStageZero);
        helper.assertTrue(DesignData.mobClass().price.base >= 100.0D, "値段の元が 100 倍になっていない: " + DesignData.mobClass().price.base);
        EconomyTable e = ForestEconomy.table();
        helper.assertTrue(e.infectGain(20.0D) >= 100.0D, "感染 1 回の稼ぎが 100 倍の桁でない: " + e.infectGain(20.0D));
        helper.succeed();
    }

    /** 維持費 = 値段 × upkeepPerSecond(森の外は × upkeepOutsideFactor)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void upkeepFormula(GameTestHelper helper) {
        EconomyTable e = ForestEconomy.table();
        double in = e.upkeepPerSecond(100.0D, 0.0D);
        double out = e.upkeepPerSecond(0.0D, 100.0D);
        helper.assertTrue(Math.abs(in - 100.0D * e.upkeepPerSecond) < 1.0E-12D, "森の中の維持費が値段 × 率でない: " + in);
        helper.assertTrue(Math.abs(out - in * e.upkeepOutsideFactor) < 1.0E-12D, "森の外の維持費が倍率どおりでない: " + out);
        helper.assertTrue(ForestUpkeep.unitPrice(EntityType.COW, false) == 0.0D, "バニラの牛に維持費が付いた");
        helper.succeed();
    }

    /**
     * ふつうに出しているだけでは足りなくならない: 稼ぎを決め打ちにして 1 体ずつ出すと、維持費が稼ぎの 8 割に届いた所で関所が止め、
     * 稼ぎ − 維持費は正のまま(予算は貯まり続ける)。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void fieldingStopsAtEightyPercentAndBudgetStillGrows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        Double before = ForestEconomy.incomeOverride();
        double budgetBefore = d.available(level);
        try {
            ForestEconomy.setIncomeForTests(12000.0D); // 1 日 12000 = ふつうは毎秒 10(倍率は森の健康・MOD の設定)
            ForestUpkeep.setSampleForTests(0.0D, 0.0D);
            d.setBudget(level, 1.0E9D);
            double income = ForestDirector.incomePerSecond(level);
            double limit = ForestDirector.upkeepLimitPerSecond(level);
            helper.assertTrue(Math.abs(limit - income * ForestEconomy.table().upkeepIncomeShare) < 1.0E-9D, "上限が稼ぎの 8 割でない");
            int n = 0;
            while (n < 1_000_000 && d.tryPayUnit(level, 100.0D, 100.0D, true)) n++;
            double upkeep = ForestUpkeep.perSecond(level);
            int want = (int) Math.floor(limit / ForestUpkeep.unitPerSecond(100.0D, true) + 1.0E-6D);
            helper.assertTrue(Math.abs(n - want) <= 1, "関所で止まった体数が違う: " + n + "(欲しい " + want + ")");
            helper.assertTrue(upkeep <= limit + 1.0E-9D, "維持費が 8 割を超えた: " + upkeep + " > " + limit);
            double net = income - upkeep;
            helper.assertTrue(net >= income * (1.0D - ForestEconomy.table().upkeepIncomeShare) - 1.0E-6D, "稼ぎ − 維持費が 2 割より少ない: " + net);
            // 1 日たっても予算は減らない(稼ぎ − 維持費で積む)
            double after = ForestDirector.accrued(0.0D, 24000L, net * EconomyTable.SECONDS_PER_DAY, 1.0E12D);
            helper.assertTrue(after > 0.0D, "上限いっぱいに出しても 1 日で予算が貯まらない: " + after);
            // 森の外は 1.5 倍なので、同じ稼ぎで出せる体数は少ない
            ForestUpkeep.setSampleForTests(0.0D, 0.0D);
            int m = 0;
            while (m < 1_000_000 && d.tryPayUnit(level, 100.0D, 100.0D, false)) m++;
            helper.assertTrue(m < n, "森の外でも同じだけ出せた: " + m + " / " + n);
            helper.succeed();
        } finally {
            ForestUpkeep.setSampleForTests(null, null);
            ForestEconomy.setIncomeForTests(before);
            d.setBudget(level, budgetBefore);
        }
    }

    /** 仮想の部隊の段階 0 の G1 は、生産の値段(100)で維持費を払う(定価の 768 ではない)。森の外なので 1.5 倍。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void virtualStageZeroPaysProductionPrice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        com.yayuy.overgrownvegetation.horde.VirtualHordes h = com.yayuy.overgrownvegetation.horde.VirtualHordes.get(level.getServer());
        com.yayuy.overgrownvegetation.horde.VirtualHordes.Swarm s = null;
        try {
            ForestUpkeep.setForTests(null);
            ForestUpkeep.invalidate();
            double before = ForestUpkeep.perSecond(level);
            s = h.newUnit(40_000_000, 64, 40_000_000, 9999, com.yayuy.overgrownvegetation.horde.UnitPurpose.DEFEND, level.getGameTime());
            s.addHeads(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(com.yayuy.overgrownvegetation.corpse.G1Species.ZOMBIE.entityType()), 10, 100.0D);
            ForestUpkeep.invalidate();
            double after = ForestUpkeep.perSecond(level);
            double want = ForestEconomy.table().upkeepPerSecond(0.0D, 10 * DesignData.director().largeRaid.costStageZero);
            helper.assertTrue(Math.abs((after - before) - want) < 1.0E-9D, "仮想の段階 0 の維持費が違う: +" + (after - before) + "(欲しい " + want + ")");
            helper.succeed();
        } finally {
            if (s != null) h.removeUnit(s);
            ForestUpkeep.invalidate();
            ForestUpkeep.setForTests(0.0D);
        }
    }

    /** 妨害されたときだけ足りなくなる: 稼ぎが削られて維持費を下回ると、予算は減る(0 で止まる)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void budgetFallsOnlyWhenIncomeIsCut(GameTestHelper helper) {
        double income = 10.0D, upkeep = 8.0D;
        double ok = ForestDirector.accrued(1000.0D, 24000L, (income - upkeep) * EconomyTable.SECONDS_PER_DAY, 1.0E12D);
        helper.assertTrue(ok > 1000.0D, "8 割のときに予算が増えない: " + ok);
        double cut = ForestDirector.accrued(1000.0D, 24000L, (income * 0.5D - upkeep) * EconomyTable.SECONDS_PER_DAY, 1.0E12D);
        helper.assertTrue(cut == 0.0D, "稼ぎを半分にされても予算が減らない(0 で止まらない): " + cut);
        helper.succeed();
    }
}
