package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.Gson;
import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LedgerSpreadMath;
import com.yayuy.overgrownvegetation.corruption.LedgerSpreadTestHooks;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.data.SpreadTable;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * <b>台帳の書き換え待ち</b>・<b>睡眠の暦</b>のテスト(2026-09-26。2026-10-30 に止まっていた縁ぬりと極相のテストを消した)。
 *
 * <p>守りたいのは次のもの。
 * <ul>
 *     <li><b>寝た夜も 1 日に数える</b> —— 睡眠で飛ばした時間が汚染の暦に入る。</li>
 *     <li><b>読み込まれたチャンクの書き換え</b> —— バイオームが過繁茂になり、地表は模様が塗ると決めた列だけ汚染される
 *     (模様が全体でおおよそ決めた割合を塗ることは計算のテストで見る)。</li>
 *     <li><b>同梱の json と Java の既定値が食い違わない</b>(spread.json)。</li>
 * </ul>
 *
 * <p>計算のテストは世界に触らない。世界(台帳・睡眠の累計・遠くのチャンク)を触るテストは自分だけのバッチ
 * ({@code ledger_spread_world})に入れてある —— 同じバッチは並列に走り、睡眠の累計を動かしている間に
 * ほかの台帳のテストが「今」を取ると狂うので、バッチを分けて順番に走らせる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class LedgerSpreadGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(LedgerSpreadGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String WORLD_BATCH = "ledger_spread_world";
    private static final long DAY = LedgerSpreadMath.TICKS_PER_DAY;
    /** 実行をまたいで台帳の座標がかぶらないように(台帳は消せず、GameTest のワールドは残る)。 */
    private static final int RUN_SALT = new java.util.Random().nextInt(1 << 20);
    private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger();
    /** 地表の塗り残しの模様: 決めた割合(既定 75%)だけ塗る。模様は連続(隣の列でほとんど変わらない)。 */
    @GameTest(template = "gametest_empty7")
    public static void surfacePatchCoversTheChosenShare(GameTestHelper helper) {
        double coverage = SpreadTable.DEFAULT.surfaceCoverage();
        double threshold = LedgerSpreadMath.noiseThreshold(coverage);
        double cell = SpreadTable.DEFAULT.patchSize();
        int painted = 0;
        int total = 0;
        double maxStep = 0.0D;
        for (int x = -300; x < 300; x += 2) {
            for (int z = -300; z < 300; z += 2) {
                double v = LedgerSpreadMath.patch(2026L, x, z, cell);
                if (v < threshold) {
                    painted++;
                }
                total++;
                maxStep = Math.max(maxStep, Math.abs(v - LedgerSpreadMath.patch(2026L, x + 1, z, cell)));
            }
        }
        double share = painted / (double) total;
        expectTrue(helper, Math.abs(share - coverage) < 0.05D, "塗った割合 " + share + "(期待 " + coverage + ")");
        // 細かい方の模様(格子 2.4 ブロック)があるので隣でも 0.3 ほどは動く。ホワイトノイズなら 1 近くまで飛ぶ。
        expectTrue(helper, maxStep < 0.5D, "隣の列で模様が飛んでいる(最大の差 " + maxStep + ")");
        helper.succeed();
    }

    /** spread.json と Java の既定値が同じ。 */
    @GameTest(template = "gametest_empty7")
    public static void bundledJsonMatchesDefault(GameTestHelper helper) {
        try (InputStream in = LedgerSpreadGameTests.class.getResourceAsStream("/overgrown_vegetation/design/spread.json")) {
            if (in == null) {
                helper.fail("design/spread.json が同梱されていない");
                return;
            }
            SpreadTable bundled = SpreadTable.fromJson(new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), SpreadTable.Json.class));
            if (!bundled.describe().equals(SpreadTable.DEFAULT.describe())) {
                helper.fail("spread.json が Java の既定値と違う\njson: " + bundled.describe() + "\njava: " + SpreadTable.DEFAULT.describe());
                return;
            }
        } catch (Exception e) {
            helper.fail("design/spread.json が読めない: " + e);
            return;
        }
        helper.succeed();
    }


    // ------------------------------------------------------------------ 世界を触るテスト(自分だけのバッチ)

    /** 睡眠で飛ばした時間は汚染の暦に入る(台帳の時刻も極相の齢も、寝た夜を 1 日に数える)。 */
    @GameTest(template = "gametest_empty7", batch = WORLD_BATCH)
    public static void sleepAdvancesTheClock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.get(level);
        long before = data.sleepSkippedTicks();
        try {
            long clock = data.clock(level.getServer());
            long credited = LedgerSpreadTestHooks.creditSleep(level, 11000L);
            expect(helper, credited, 11000L, "足した長さ");
            expect(helper, data.clock(level.getServer()) - clock, 11000L, "暦の進み");
            expect(helper, LedgerSpreadTestHooks.creditSleep(level, 0L), 0L, "飛ばしていなければ足さない");
        } finally {
            data.restoreSleepSkipForTests(before);
        }
        helper.succeed();
    }

    /**
     * 読み込まれたチャンクを書き換えると、バイオームが過繁茂になり、地表は<b>模様が塗ると決めた列だけ</b>汚染される。
     *
     * <p>割合は決め打ちの幅で見ない。模様は格子 6 ブロックの値ノイズなので、1 チャンク(256 列)の塗る割合は場所しだいで
     * 平均 0.75・標準偏差 0.14 ほどばらつく(シード 0 でこのテストが選びうる 10 万チャンクのうち 5.7% が 0.5 以下)。
     * チャンクは実行ごとに変わるので、以前の「0.75 − 0.25 より上」は 2026-09-26 に 0.40・0.45 で 2 回落ちた。
     * そこで、そのチャンクの模様を同じ設計値(塗る割合・模様の大きさ・世界のシード)から計算して列ごとに突き合わせる。
     * 全体でおおよそ決めた割合になることは {@link #surfacePatchCoversTheChosenShare} が広い範囲で見ている。
     */
    @GameTest(template = "gametest_empty7", batch = WORLD_BATCH, timeoutTicks = 200)
    public static void catchUpConvertsBiomeAndSurface(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos pos = new ChunkPos(40_000 + (RUN_SALT % 50_000) * 4, 40_000 + NEXT.getAndIncrement() * 4);
        level.getChunk(pos.x, pos.z); // 読み込む(平らな GameTest のワールドなので軽い)
        boolean[] before = corruptedColumns(level, pos); // 前の実行と同じチャンクに当たったときの分
        LedgerSpreadTestHooks.convertNow(level, pos);
        BlockPos middle = pos.getMiddleBlockPosition(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getMiddleBlockX(), pos.getMiddleBlockZ()));
        expectTrue(helper, ModBiomes.isFullyOvergrown(level.getBiome(middle)), "バイオームが過繁茂にならない");
        boolean[] after = corruptedColumns(level, pos);
        SpreadTable table = SpreadTable.DEFAULT;
        double threshold = LedgerSpreadMath.noiseThreshold(table.surfaceCoverage());
        int planned = 0;
        int missed = 0;
        int extra = 0;
        for (int c = 0; c < 256; c++) {
            boolean paint = LedgerSpreadMath.patch(level.getSeed(), pos.getMinBlockX() + (c & 15),
                    pos.getMinBlockZ() + (c >> 4), table.patchSize()) < threshold;
            if (paint) {
                planned++;
                if (!after[c]) {
                    missed++;
                }
            } else if (after[c] && !before[c]) {
                extra++;
            }
        }
        String where = "(チャンク " + pos + "・模様が塗る列 " + planned + " / 256・除去装置の圏内 "
                + SporeDeviceData.mayInhibitBlocksIn(level, pos, SporeDeviceData.Inhibit.CORRUPTION) + ")";
        expectTrue(helper, missed == 0, "模様が塗る列のうち " + missed + " 列が汚染されていない" + where);
        expectTrue(helper, extra == 0, "塗り残すはずの列が " + extra + " 列汚染された" + where);
        helper.succeed();
    }

    /** チャンクの 256 列(x が速く回る)の地表が汚染済みか。植物が乗っていればその下の地面まで見る。 */
    private static boolean[] corruptedColumns(ServerLevel level, ChunkPos pos) {
        boolean[] out = new boolean[256];
        for (int c = 0; c < 256; c++) {
            int x = pos.getMinBlockX() + (c & 15);
            int z = pos.getMinBlockZ() + (c >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            for (int k = 0; k < 3 && !out[c]; k++) {
                BlockPos at = new BlockPos(x, y - k, z); // 記録で覆ったマスも汚染済み(本体と同じ数え方、2026-10-30 夜)
                out[c] = com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCorruptedCell(level, at, level.getBlockState(at));
            }
        }
        return out;
    }

    private static ChunkPos freshCenter() {
        int base = 3_000_000 + RUN_SALT * 1024 + NEXT.getAndIncrement() * 64;
        return new ChunkPos(base, base);
    }

    private static void expect(GameTestHelper helper, double actual, double expected, String what) {
        if (Math.abs(actual - expected) > 1.0E-6D * Math.max(1.0D, Math.abs(expected))) {
            helper.fail(what + ": " + expected + " のはずが " + actual);
        }
    }

    private static void expectInt(GameTestHelper helper, int actual, int expected, String what) {
        if (actual != expected) {
            helper.fail(what + ": " + expected + " のはずが " + actual);
        }
    }

    private static void expectTrue(GameTestHelper helper, boolean ok, String what) {
        if (!ok) {
            helper.fail(what);
        }
    }

    private LedgerSpreadGameTests() {
    }
}
