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
import com.yayuy.overgrownvegetation.spore.field.SporeSprouts;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * <b>台帳の書き換え待ち</b>・<b>睡眠の暦</b>のテスト(2026-09-26。2026-10-30 に止まっていた縁ぬりと極相のテストを消した)。
 *
 * <p>守りたいのは次のもの。
 * <ul>
 *     <li><b>寝た夜も 1 日に数える</b> —— 睡眠で飛ばした時間が汚染の暦に入る。</li>
 *     <li><b>読み込まれたチャンクの書き換え</b> —— バイオームが過繁茂になり、待ちから外れ、芽の点が置かれる
 *     (2026-11-14 に地表の四角い塗りと、その模様の計算のテストを消した)。</li>
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
        public static void onRegisterLedgerSpreadGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
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
     * 書き換え待ちのチャンクを書き換えると(胞子の場のやり方、2026-11-14 に四角い塗りから書き換え)、バイオームが過繁茂になり、
     * 待ちから外れ、芽の点が置かれる。地表はここでは塗らない(芽と過繁茂バイオームの浸食が後から広げる)。
     * 胞子の場の升が無いチャンク(このテストの遠いチャンク)は過繁茂の札。発芽して 2 日未満の升なら繁茂の札になる。
     */
    @GameTest(template = "gametest_empty7", batch = WORLD_BATCH, timeoutTicks = 200)
    public static void catchUpConvertsBiomeAndStartsSprouts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos pos = new ChunkPos(40_000 + (RUN_SALT % 50_000) * 4, 40_000 + NEXT.getAndIncrement() * 4);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(pos.x + dx, pos.z + dz); // 芽は周りへはみ出す
        int[] before = SporeSprouts.countsForTests(level);
        try {
            boolean converted = LedgerSpreadTestHooks.convertNow(level, pos);
            String where = "(チャンク " + pos + "・除去装置の圏内 "
                    + SporeDeviceData.mayInhibitBlocksIn(level, pos, SporeDeviceData.Inhibit.CORRUPTION) + ")";
            expectTrue(helper, converted, "書き換えずに外した" + where);
            expectTrue(helper, !LedgerSpreadTestHooks.isPending(level, pos), "書き換え待ちに残っている" + where);
            BlockPos middle = pos.getMiddleBlockPosition(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    pos.getMiddleBlockX(), pos.getMiddleBlockZ()));
            expectTrue(helper, ModBiomes.isFullyOvergrown(level.getBiome(middle)), "バイオームが過繁茂にならない" + where);
            int[] after = SporeSprouts.countsForTests(level);
            expectTrue(helper, after[0] + after[1] > before[0] + before[1],
                    "芽の点が置かれない(前 " + before[0] + "+" + before[1] + "・後 " + after[0] + "+" + after[1] + ")" + where);
        } finally {
            SporeSprouts.clearForTests(level); // テストで置いた点を残さない(同じバッチはこのテストだけが点を置く)
        }
        helper.succeed();
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
