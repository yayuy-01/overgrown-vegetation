package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.StorageOverflow;
import com.yayuy.overgrownvegetation.front.Fronts;
import com.yayuy.overgrownvegetation.organ.ForestOrgans;
import com.yayuy.overgrownvegetation.organ.OrganKind;
import com.yayuy.overgrownvegetation.organ.OrganPlanner;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 貯蓄の上限の作り直し(2026-11-22 ユーザー決定。依頼書 storage-and-stealth-brief.md の 1)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class StorageCapGameTests {

    private static final String BATCH = "storage_cap";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(StorageCapGameTests.class);
        }

        private Registration() {
        }
    }

    private StorageCapGameTests() {
    }

    /** 上限は 1 日の稼ぎに比例しない(基礎値 + ハイヴノード)。あふれた分は胞子に回り(上限 1 つ分 = 森全体の 1 日分 × 倍率)、直近の量として残る。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void overflowBecomesSpores(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fronts fronts = Fronts.get(level.getServer());
        Fronts.Front f = fronts.addSourceForTests(31_000_000.5D, 31_000_000.5D);
        try {
            StorageOverflow.resetForTests();
            double cap = ForestDirector.cap(level);
            int hives = PhaseData.get(level).livingGiantTreeCount(); // 生きている巨大樹 = ハイヴノード
            double plain = DesignData.director().cap(hives);
            helper.assertTrue(cap + 1.0E-6D >= plain, "上限が基礎値 + ハイヴノードより小さい: " + cap + " < " + plain);
            double over = cap * 0.5D; // 150% たまっていた
            StorageOverflow.addForTests(over);
            helper.assertTrue(Math.abs(StorageOverflow.recent() - over) < 1.0E-6D, "直近にあふれた量が残らない");
            double spores = StorageOverflow.flush(level);
            double want = over / Math.max(1.0D, cap) * PhaseData.get(level).overgrownLedgerSize() * DesignData.director().overflowSporeFactor;
            helper.assertTrue(Math.abs(spores - want) < 1.0E-6D, "胞子に回した量が違う: " + spores + "(欲しい " + want + ")");
            helper.assertTrue(StorageOverflow.flush(level) == 0.0D, "同じあふれを 2 回胞子にした");
            helper.assertTrue(Math.abs(StorageOverflow.total - over) < 1.0E-6D, "あふれた量の合計が違う");
            helper.succeed();
        } finally {
            StorageOverflow.resetForTests();
            fronts.fronts().remove(f);
        }
    }

    /** 蓄えの嚢の値段は、今ある同じ大きさの嚢 1 つごとに × sacPriceGrowth(上限の 95% で切る)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void sacPriceGrowsPerSac(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        List<Integer> planted = new ArrayList<>();
        try {
            int before = data.livingCount(OrganKind.SAC, false);
            double p0 = OrganPlanner.sacPrice(level, data, false);
            ForestOrgans.Organ o = data.plant(level, OrganKind.SAC, false, 0, 9, 0, 32_000_000, 32_000_000);
            planted.add(o.id);
            helper.assertTrue(data.livingCount(OrganKind.SAC, false) == before + 1, "嚢の数が増えない");
            double p1 = OrganPlanner.sacPrice(level, data, false);
            double capped = ForestDirector.cap(level) * 0.95D;
            double want = Math.min(capped, DesignData.organs().price(false) * Math.pow(DesignData.director().sacPriceGrowth, before + 1));
            helper.assertTrue(Math.abs(p1 - want) < 1.0E-6D, "嚢の値段が違う: " + p1 + "(欲しい " + want + ")");
            helper.assertTrue(p1 >= p0, "嚢が増えたのに値段が下がった");
            helper.succeed();
        } finally {
            for (int id : planted) data.remove(id);
        }
    }
}
