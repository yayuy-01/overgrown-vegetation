package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeField;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 胞子の場の地下の層と高所(2026-11-04 暴露量ノート 追記 6): 地下が地上より濃くなる・口の無いチャンクには溜まらない・
 * 逆転層 + 20 で 5% 未満・巨大樹の近くは例外・逆転層より上は発芽しない・区画ファイルの版 3。
 * 場の式は素の Java なのでワールドを使わずに回す。数値は設計データ({@code spore_field.json})の値から。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SporeUndergroundGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterSporeUndergroundGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(SporeUndergroundGameTests.class);
        }

        private Registration() {
        }
    }

    private SporeUndergroundGameTests() {
    }

    private static final long DAY = PrevailingWind.DAY;

    /** 設計データの場の数値の写し(テストで書き換えても設計データに響かない)。 */
    private static SporeField.Params params() {
        SporeField.Params src = DesignData.sporeField().field();
        SporeField.Params p = new SporeField.Params();
        try {
            for (var fd : SporeField.Params.class.getFields()) fd.set(p, fd.get(src));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    /** どこも同じ高さ・ふつうの土地。 */
    private static SporeField field(SporeField.Params p, float height) {
        SporeField f = new SporeField(777L, p);
        f.ground = new SporeField.Ground() {
            @Override
            public float germFactor(int cx, int cz) {
                return 1;
            }

            @Override
            public boolean wet(int cx, int cz) {
                return false;
            }

            @Override
            public float height(int cx, int cz) {
                return height;
            }
        };
        return f;
    }

    /** 東へ強さ 1 の風(ふつうの日)。 */
    private static PrevailingWind.Sample breeze() {
        PrevailingWind.Sample s = new PrevailingWind.Sample();
        s.dirX = 1;
        s.strength = 1;
        return s;
    }

    /** days 日ぶん回す(昼夜あり、晴れ。rain = 雨の印)。 */
    private static void run(SporeField f, double days, boolean rain) {
        int n = (int) Math.round(days * DAY / f.p.stepTicks);
        PrevailingWind.Sample w = breeze();
        for (int k = 1; k <= n; k++) {
            long t = (long) k * f.p.stepTicks;
            f.step(new SporeField.Conditions(t, t, rain, false, false), w);
        }
    }

    /**
     * 汚染された 7×7 チャンクの真ん中で、口の多さ 1 のチャンクは数日で地下(浮遊)が地上(浮遊 + 林床)より濃くなり、洞窟の床から発芽する。
     * 隣の口の無いチャンク(口 0)は地上に浮遊があっても地下は 0 のまま(隣の地下へは伝えない)。
     */
    @GameTest(template = "gametest_empty7")
    public static void undergroundGetsDenserAndSealedStaysEmpty(GameTestHelper helper) {
        SporeField.Params p = params();
        SporeField f = field(p, 64);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) f.setCorrupted(x, z, -3 * DAY);
        f.setMouth(0, 0, 1f);
        f.setMouth(1, 0, 0f);
        run(f, 4, false);
        double surface = f.air(0, 0) + f.low(0, 0), under = f.under(0, 0);
        helper.assertTrue(under > surface, "4 日で地下が地上より濃くない: 地下 " + under + "・地上 " + surface);
        helper.assertTrue(f.caveCount(0, 0) >= 1, "洞窟の床から発芽していない(地下の降り積もり " + f.underDeposited(0, 0) + ")");
        helper.assertTrue(f.air(1, 0) > 0, "口の無いチャンクの地上に浮遊が無い(テストの前提)");
        helper.assertTrue(f.under(1, 0) == 0 && f.underDeposited(1, 0) == 0 && f.caveCount(1, 0) == 0,
                "口の無いチャンクの地下に溜まった: " + f.under(1, 0) + " / " + f.underDeposited(1, 0));
        // 測ったことの無いチャンクは既定値の口で少しだけ染み込む(地上より薄い)
        double dflt = f.under(-1, 0);
        helper.assertTrue(dflt > 0 && dflt < f.under(0, 0), "既定値の口の地下がおかしい: " + dflt);
        helper.succeed();
    }

    /** 逆転層より上(地表 y 250)は降り積もっても地上も洞窟も発芽しない。生きている巨大樹の根元から 4 チャンク以内は例外。 */
    @GameTest(template = "gametest_empty7")
    public static void noSproutAboveInversionExceptNearTree(GameTestHelper helper) {
        SporeField.Params p = params();
        p.inversionJitter = 0;
        helper.assertTrue(250 > p.inversionY + 20, "テストの前提: 逆転層 " + p.inversionY);
        SporeField high = field(p, 250);
        high.depositPoint(0, 0, 1.0);
        high.depositUnderPoint(0, 0, 1.0);
        run(high, 2, true);
        helper.assertTrue(!high.isCorrupted(0, 0) && high.caveCount(0, 0) == 0,
                "逆転層より上で発芽した(地上 " + high.isCorrupted(0, 0) + "・洞窟 " + high.caveCount(0, 0) + ")");
        SporeField tree = field(p, 250);
        tree.treeChunks = new long[]{ChunkPos.asLong(3, 0)};
        tree.depositPoint(0, 0, 1.0);
        tree.depositUnderPoint(0, 0, 1.0);
        tree.depositPoint(8, 0, 1.0); // 巨大樹から 5 チャンク(例外の外)
        run(tree, 2, true);
        helper.assertTrue(tree.isCorrupted(0, 0) && tree.caveCount(0, 0) >= 1,
                "巨大樹の近くなのに発芽しない(地上 " + tree.isCorrupted(0, 0) + "・洞窟 " + tree.caveCount(0, 0) + ")");
        helper.assertTrue(!tree.isCorrupted(8, 0), "巨大樹から 5 チャンクで発芽した");
        helper.succeed();
    }

    /** 高所の読み: 逆転層ではそのまま、+20 で 5% 未満(暴露を積まない)、間はなめらかに下がる。巨大樹の距離の判定。 */
    @GameTest(template = "gametest_empty7")
    public static void highCutBelowExposureAt20(GameTestHelper helper) {
        SporeFieldTable t = DesignData.sporeField();
        double inv = 200, fade = t.highFadeBlocks(), cap = t.highCapPercent();
        helper.assertTrue(fade <= 20.0001, "寄せきる高さが 20 ブロックより上: " + fade);
        helper.assertTrue(cap < t.exposureMinPercent(), "上限 " + cap + "% が暴露の下限 " + t.exposureMinPercent() + "% 以上");
        for (double v : new double[]{10, 100, 250}) {
            double at = SporeConcentration.highCut(v, inv, inv, fade, cap);
            double mid = SporeConcentration.highCut(v, inv + 10, inv, fade, cap);
            double top = SporeConcentration.highCut(v, inv + 20, inv, fade, cap);
            double above = SporeConcentration.highCut(v, inv + 60, inv, fade, cap);
            helper.assertTrue(at == v, "逆転層の高さで変わった: " + v + " → " + at);
            helper.assertTrue(top < 5 && above < 5, "+20 で 5% 未満にならない: " + v + " → " + top + " / " + above);
            helper.assertTrue(mid < at && mid > top, "間がなめらかでない: " + at + " → " + mid + " → " + top);
        }
        helper.assertTrue(SporeConcentration.highCut(3, inv + 30, inv, fade, cap) == 3, "上限より薄い値まで変わった");
        long origin = ((long) 100 << 32) | (200 & 0xFFFFFFFFL);
        double r = t.field().treeExemptChunks * 16;
        helper.assertTrue(SporeConcentration.nearTree(new long[]{origin}, 100 + (int) r - 1, 200, r), "巨大樹の近くと判定しない");
        helper.assertTrue(!SporeConcentration.nearTree(new long[]{origin}, 100 + (int) r + 1, 200, r), "巨大樹の外を近くと判定した");
        helper.succeed();
    }

    /** 区画ファイルの版 3 は地下の層を持ち越し、前の版は地下 0・口は既定値(測り直し)で読む。 */
    @GameTest(template = "gametest_empty7")
    public static void tileFileVersion3KeepsUnderground(GameTestHelper helper) {
        SporeField.Params p = params();
        SporeField a = field(p, 64);
        a.setCorrupted(0, 0, 0);
        a.setMouth(0, 0, 0.7f);
        a.depositUnderPoint(0, 0, 0.3);
        run(a, 0.5, false);
        double under = a.under(0, 0), underDep = a.underDeposited(0, 0);
        helper.assertTrue(under > 0, "テストの前提: 地下の浮遊が 0");
        CompoundTag tag = SporeFieldServer.tileTagForTests(a, 0, 0);
        helper.assertTrue(tag.getInt("ver") >= 3, "版が 3 より前: " + tag.getInt("ver"));
        SporeField b = field(p, 64);
        SporeFieldServer.loadTileForTests(b, 0, 0, tag);
        helper.assertTrue(b.under(0, 0) == under && b.underDeposited(0, 0) == underDep && Math.abs(b.mouth(0, 0) - 0.7) < 1e-6,
                "版 3 の読み込みで地下の層が変わった: " + b.under(0, 0) + " / " + b.mouth(0, 0));
        CompoundTag old = tag.copy();
        old.putInt("ver", 2);
        SporeField c = field(p, 64);
        SporeFieldServer.loadTileForTests(c, 0, 0, old);
        helper.assertTrue(c.under(0, 0) == 0 && c.underDeposited(0, 0) == 0 && c.mouth(0, 0) == p.mouthDefault,
                "前の版で地下の層を読んだ: " + c.under(0, 0) + " / " + c.mouth(0, 0));
        helper.succeed();
    }
    /** 前の形の保存(区画ごとのファイル)を読むと、まとめた形に書き直され、読み直しても場が変わらない(2026-11-15 軽量化 F)。 */
    @GameTest(template = "gametest_empty7")
    public static void oldTileFilesConvertToBundlesUnchanged(GameTestHelper helper) {
        SporeField.Params p = params();
        SporeField a = field(p, 64);
        // 区画をまたぐ離れた所(別のファイルになる所・負の座標も)に汚染と地下を置いて少し回す
        int[][] at = {{0, 0}, {40, 3}, {-5, -70}, {300, 10}, {-300, 600}};
        for (int[] c : at) {
            a.setCorrupted(c[0], c[1], 0);
            a.setMouth(c[0], c[1], 0.7f);
            a.depositUnderPoint(c[0], c[1], 0.3);
        }
        run(a, 0.5, false);
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ov_spore_migrate");
            String diff = SporeFieldServer.migrationRoundTripForTests(a, () -> field(p, 64), dir);
            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
            }
            helper.assertTrue(diff == null, "読み替えで場が変わった: " + diff);
        } catch (java.io.IOException e) {
            helper.fail("読み替えのテストでファイルを扱えなかった: " + e);
        }
        helper.succeed();
    }
}
