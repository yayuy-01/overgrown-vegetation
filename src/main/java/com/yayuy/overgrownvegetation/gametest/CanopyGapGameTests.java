package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.TreeSpacing;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>林冠の穴</b>({@link TreeSpacing#inCanopyGap}。2026-10-16、ユーザー選択「濃い森の光 = 森に穴を残す + 葉を樹冠の数で数える」の前者)のテスト。
 *
 * <p>守りたいのは 3 つ。
 * <ul>
 *     <li><b>座標とシードだけで決まる</b> —— 汚染はチャンクごとにばらばらの時刻に木を植えるので、答えが揺れると輪の中に木が混ざる。</li>
 *     <li><b>輪は自分の格子のマスからはみ出さない</b> —— {@code inCanopyGap} は自分のマスしか見ないので、はみ出すと隣のマス側で輪が欠ける。</li>
 *     <li><b>輪の広さが森を食いすぎない・少なすぎない</b> —— 既定 0.5 で地面の約 1 割(半径 8〜11 の輪が 36 ブロック四方に 0.5 個)。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CanopyGapGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CanopyGapGameTests.class);
        }

        private Registration() {
        }
    }

    private static final long SEED = 987654321L;

    /** 何度呼んでも同じ答えで、輪は自分のマスの中に収まり、半径は決めた範囲。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void gapsAreDeterministicAndStayInCell(GameTestHelper helper) {
        int grid = TreeSpacing.GAP_GRID;
        int found = 0;
        for (int cx = -20; cx < 20; cx++) {
            for (int cz = -20; cz < 20; cz++) {
                double[] a = TreeSpacing.canopyGap(SEED, cx, cz, 0.5);
                double[] b = TreeSpacing.canopyGap(SEED, cx, cz, 0.5);
                if ((a == null) != (b == null) || a != null && (a[0] != b[0] || a[1] != b[1] || a[2] != b[2])) {
                    helper.fail("同じマスで答えが変わった (" + cx + ", " + cz + ")");
                    return;
                }
                if (a == null) {
                    continue;
                }
                found++;
                double r = a[2];
                if (r < TreeSpacing.GAP_RADIUS_MIN || r > TreeSpacing.GAP_RADIUS_MAX) {
                    helper.fail("半径が範囲の外: " + r);
                    return;
                }
                double minX = cx * (double) grid;
                double minZ = cz * (double) grid;
                if (a[0] - r < minX || a[0] + r > minX + grid || a[1] - r < minZ || a[1] + r > minZ + grid) {
                    helper.fail("輪がマスからはみ出した (" + cx + ", " + cz + "): 中心 " + a[0] + ", " + a[1] + " 半径 " + r);
                    return;
                }
                // 中心の列は輪の中、輪の外の列は輪の外。
                if (!TreeSpacing.inCanopyGap(SEED, (int) Math.floor(a[0]), (int) Math.floor(a[1]), 0.5)) {
                    helper.fail("輪の中心の列が輪の中にならない (" + cx + ", " + cz + ")");
                    return;
                }
                if (TreeSpacing.inCanopyGap(SEED, (int) Math.floor(a[0] + r + 1.0), (int) Math.floor(a[1]), 0.5)) {
                    helper.fail("輪の外の列が輪の中になった (" + cx + ", " + cz + ")");
                    return;
                }
            }
        }
        if (found == 0) {
            helper.fail("1600 マスに輪が 1 つも無い");
            return;
        }
        helper.succeed();
    }

    /** 既定 0.5 で地面の 7〜15%(期待 約 11%)。0 なら輪は無い。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void gapCoverageIsSane(GameTestHelper helper) {
        int size = 720;
        int inside = 0;
        int insideZero = 0;
        for (int x = -size / 2; x < size / 2; x++) {
            for (int z = -size / 2; z < size / 2; z++) {
                if (TreeSpacing.inCanopyGap(SEED, x, z, 0.5)) {
                    inside++;
                }
                if (TreeSpacing.inCanopyGap(SEED, x, z, 0.0)) {
                    insideZero++;
                }
            }
        }
        double fraction = (double) inside / ((double) size * size);
        if (insideZero != 0) {
            helper.fail("確率 0 なのに輪がある: " + insideZero);
            return;
        }
        if (fraction < 0.07 || fraction > 0.15) {
            helper.fail("輪の中の地面の割合が外れた: " + String.format("%.1f%%", fraction * 100.0));
            return;
        }
        helper.succeed();
    }

    private CanopyGapGameTests() {
    }
}
