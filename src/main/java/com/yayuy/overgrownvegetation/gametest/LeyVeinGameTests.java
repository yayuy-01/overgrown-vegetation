package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;

/**
 * <b>地脈の場</b>({@link LeyVeinField})のテスト。ユーザー指定は
 * 「かなり巨大な網目状に広がる」「1 チャンク以内で完結するものではない」。
 *
 * <p>地脈はチャンクごとに独立して敷く({@code LeyVeinPlacer})ので、場が
 * <b>座標だけの決定的な関数</b>でなくなった瞬間にチャンクの境目で線がずれる。
 * ここが壊れると「地脈が 16 ブロックごとに途切れる」という形で表に出る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class LeyVeinGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(LeyVeinGameTests.class);
        }

        private Registration() {
        }
    }

    private static final long SEED = 123456789L;

    /** 何度呼んでも同じ答え。ここが崩れるとチャンクの境目で線がずれる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void fieldIsDeterministic(GameTestHelper helper) {
        for (int i = 0; i < 64; i++) {
            int x = i * 37 - 500;
            int z = i * 53 + 200;
            double a = LeyVeinField.strengthAt(SEED, x, z);
            double b = LeyVeinField.strengthAt(SEED, x, z);
            if (a != b) {
                helper.fail("同じ座標で答えが変わった (" + x + ", " + z + "): " + a + " / " + b);
                return;
            }
        }
        // シードが変われば模様も変わる。
        boolean anyDifferent = false;
        for (int i = 0; i < 256 && !anyDifferent; i++) {
            int x = i * 11;
            if (LeyVeinField.strengthAt(SEED, x, 0) != LeyVeinField.strengthAt(SEED + 1L, x, 0)) {
                anyDifferent = true;
            }
        }
        if (!anyDifferent) {
            helper.fail("シードを変えても模様が同じ");
            return;
        }
        helper.succeed();
    }

    /** 地脈が地面を埋め尽くしても、どこにも無くてもいけない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void coverageIsSane(GameTestHelper helper) {
        int size = 256;
        int hits = 0;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                if (LeyVeinField.isVeinAt(SEED, x - 128, z - 128)) {
                    hits++;
                }
            }
        }
        double fraction = (double) hits / (size * size);
        if (fraction <= 0.001D) {
            helper.fail("地脈がほとんど無い: " + String.format("%.3f%%", fraction * 100.0D));
            return;
        }
        if (fraction >= 0.25D) {
            helper.fail("地脈が地面を埋めすぎ: " + String.format("%.1f%%", fraction * 100.0D));
            return;
        }
        helper.succeed();
    }

    /**
     * <b>1 チャンクで完結しない。</b> 地脈のひと続き(4 近傍で繋がった塊)の広がりが
     * 16 ブロックを大きく超えることを見る。ここが 16 以下だと「チャンクの中の模様」であって
     * 「巨大な網目」ではない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void veinsSpanManyChunks(GameTestHelper helper) {
        int size = 256;
        int half = size / 2;
        boolean[] vein = new boolean[size * size];
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                vein[x * size + z] = LeyVeinField.isVeinAt(SEED, x - half, z - half);
            }
        }

        int bestExtent = 0;
        boolean[] seen = new boolean[size * size];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < vein.length; start++) {
            if (!vein[start] || seen[start]) {
                continue;
            }
            int minX = size, maxX = -1, minZ = size, maxZ = -1;
            seen[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                int idx = queue.poll();
                int x = idx / size;
                int z = idx % size;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
                int[][] around = {{x - 1, z}, {x + 1, z}, {x, z - 1}, {x, z + 1}};
                for (int[] n : around) {
                    if (n[0] < 0 || n[0] >= size || n[1] < 0 || n[1] >= size) {
                        continue;
                    }
                    int ni = n[0] * size + n[1];
                    if (vein[ni] && !seen[ni]) {
                        seen[ni] = true;
                        queue.add(ni);
                    }
                }
            }
            bestExtent = Math.max(bestExtent, Math.max(maxX - minX, maxZ - minZ));
        }

        // 網目のセルは既定 192 ブロックなので、ひと続きは 256 の走査範囲いっぱいに届くはず。
        // 余裕を見て「4 チャンク以上」を下限にしておく。
        if (bestExtent < 64) {
            helper.fail("地脈のひと続きが短すぎる(" + bestExtent + " ブロック)。"
                    + "1 チャンクで完結しない巨大な網目になっていない");
            return;
        }
        helper.succeed();
    }

    /** 網目の粗さが設計値どおりか(セルを大きくすると地脈は減る)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void cellSizeIsRespected(GameTestHelper helper) {
        ClimaxTable.LeyVein spec = DesignData.climax().leyVein();
        if (spec.cellSize() < 64.0D) {
            helper.fail("網目のセルが 1 チャンク級に小さい: " + spec.cellSize());
            return;
        }
        if (spec.width() <= 0.0D || spec.width() > spec.cellSize() * 0.25D) {
            helper.fail("地脈の幅がセルに対して不自然: 幅 " + spec.width() + " / セル " + spec.cellSize());
            return;
        }
        helper.succeed();
    }

    private LeyVeinGameTests() {
    }
}
