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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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
        public static void onRegisterStorageCapGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
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
            int hives = com.yayuy.overgrownvegetation.front.FrontTrees.livingTreeCount(level); // 生きている巨大樹 = ハイヴノード
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

    /** 11-22 ユーザー決定: 器官の数の上限 = フェーズの数 + 生きている巨大樹 × 大 1・小 2(MOD の設定の倍率は全体に掛ける)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void organLimitGrowsWithGiantTrees(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var t = DesignData.organs();
        for (int phase = 0; phase <= 10; phase++) {
            for (int trees : new int[]{0, 1, 7, 55}) {
                helper.assertTrue(t.limit(phase, trees, false) == t.maxOrgans(phase) + trees * t.maxOrgansPerTree, "大きい版の上限が違う: F" + phase + "・巨大樹 " + trees);
                helper.assertTrue(t.limit(phase, trees, true) == t.maxSmall(phase) + trees * t.maxSmallPerTree, "小さい版の上限が違う: F" + phase + "・巨大樹 " + trees);
            }
        }
        helper.assertTrue(t.limit(7, 10, false) > t.limit(7, 0, false) && t.limit(7, 10, true) > t.limit(7, 0, true), "巨大樹で上限が増えない");
        int trees = com.yayuy.overgrownvegetation.front.FrontTrees.livingTreeCount(level);
        int phase = com.yayuy.overgrownvegetation.director.DirectorGates.phase(level);
        double factor = com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.of(level.getServer(), com.yayuy.overgrownvegetation.difficulty.DifficultySetting.ORGAN_COUNT);
        helper.assertTrue(OrganPlanner.limit(level, true) == Math.round(t.limit(phase, trees, true) * factor), "計画の上限が表と合わない");
        helper.succeed();
    }

    /**
     * 11-22 ユーザー決定: 広げる部隊が巨大樹の管理の外の升に着いたら前線のフィロセンスを 1 体植える(間隔の中には植えない)。
     * 育ち切ったら まわり watcherBlocks が森の視界(WATCHER)。保存・読み込みで残る。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void frontWatcherPlantsGrowsAndSees(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestDirector d = ForestDirector.get(level);
        double budget = d.available(level);
        Integer phase = com.yayuy.overgrownvegetation.director.DirectorGates.phaseOverride();
        var data = com.yayuy.overgrownvegetation.front.FrontWatchers.get(level.getServer());
        var keep = new ArrayList<>(data.sites());
        var h = com.yayuy.overgrownvegetation.horde.VirtualHordes.load(new net.minecraft.nbt.CompoundTag());
        try {
            data.clearForTests();
            com.yayuy.overgrownvegetation.director.DirectorGates.setPhaseForTests(7);
            d.setBudget(level, 1.0E9D);
            var s = h.newUnit(0, 64, 0, -1, com.yayuy.overgrownvegetation.horde.UnitPurpose.EXPAND, level.getGameTime());
            int cellX = 34_000_000 >> 6, cellZ = 34_000_000 >> 6; // 巨大樹の無い遠い升
            helper.assertTrue(data.tryPlant(level, s, cellX, cellZ), "管理の外の升に植えない");
            helper.assertTrue(!data.tryPlant(level, s, cellX, cellZ), "間隔(" + DesignData.front().frontWatcherSpacingBlocks + ")の中にもう 1 体植えた");
            var p0 = data.sites().get(0);
            int cx0 = (cellX * 4 + 2) * 16 + 8, cz0 = (cellZ * 4 + 2) * 16 + 8;
            helper.assertTrue(Math.abs(Math.abs(p0.x() - cx0) + Math.abs(p0.z() - cz0) - 64) == 0, "着いた升のとなり(その先)に植えていない: " + (p0.x() - cx0) + ", " + (p0.z() - cz0));
            helper.assertTrue(data.sites().size() == 1, "数が違う: " + data.sites().size());
            var w = data.sites().get(0);
            net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(w.x() + 80, 64, w.z()); // 拠点の見張り(48)より遠く、前線の視界(96)の中
            helper.assertTrue(!com.yayuy.overgrownvegetation.front.FrontWatchers.nearGrown(level, at, DesignData.front().frontWatcherVisionBlocks), "育つ前から見えている");
            data.ageForTests((long) (DesignData.director().watcher.growDays * 24000.0D) + 1L);
            data.tick(level);
            helper.assertTrue(data.sites().get(0).grown(), "育ち切らない");
            helper.assertTrue(com.yayuy.overgrownvegetation.front.FrontWatchers.nearGrown(level, at, DesignData.front().frontWatcherVisionBlocks), "育ち切ったのに見張りの視界に入らない");
            helper.assertTrue(com.yayuy.overgrownvegetation.director.ForestVision.why(level, at) != com.yayuy.overgrownvegetation.director.ForestVision.Why.NONE, "育ち切ったのに森に見えない");
            var again = com.yayuy.overgrownvegetation.front.FrontWatchers.load(data.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess()));
            helper.assertTrue(again.sites().size() == 1 && again.sites().get(0).grown() && again.sites().get(0).x() == w.x(), "読み直すと消える");
            helper.succeed();
        } finally {
            data.clearForTests();
            data.sites().addAll(keep);
            com.yayuy.overgrownvegetation.director.DirectorGates.setPhaseForTests(phase);
            d.setBudget(level, budget);
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
