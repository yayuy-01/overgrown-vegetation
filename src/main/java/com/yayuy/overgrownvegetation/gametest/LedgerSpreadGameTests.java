package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.Gson;
import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LedgerSpreadMath;
import com.yayuy.overgrownvegetation.corruption.LedgerSpreadTestHooks;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
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
 * <b>台帳の広がり</b>・<b>フェーズの日数</b>・<b>極相の齢</b>のテスト(2026-09-26、2026-09-28 に台帳の広がりの規則変更へ追従)。
 *
 * <p>守りたいのは次のもの。
 * <ul>
 *     <li><b>ユーザー指定の数値</b> —— 5 分(6000 tick)おきに、縁のチャンクが接している面の数 × 25%(1 面 25%・2 面 50%・3 面 75%・
 *     4 面 100%)の確率で過繁茂になる、F1→F10 の日数から逆算したしきい値(この規則で実際に回した平均の増え方と合う)、
 *     極相は F5 以降で「齢 − F1→F5 の日数 ≥ 14 日」。</li>
 *     <li><b>寝た夜も 1 日に数える</b> —— 睡眠で飛ばした時間が汚染の暦に入る。</li>
 *     <li><b>胞子除去装置の範囲には印を付けない</b>。</li>
 *     <li><b>読み込まれたチャンクの書き換え</b> —— バイオームが過繁茂になり、地表は模様が塗ると決めた列だけ汚染される
 *     (模様が全体でおおよそ決めた割合を塗ることは計算のテストで見る)。</li>
 *     <li><b>同梱の json と Java の既定値が食い違わない</b>(spread.json・phases.json のしきい値・climax.json の minPhase)。</li>
 * </ul>
 *
 * <p>計算のテストは世界に触らない。世界(台帳・睡眠の累計・遠くのチャンク)を触るテストは自分だけのバッチ
 * ({@code ledger_spread_world})に入れてある —— 同じバッチは並列に走り、睡眠の累計を動かしている間に
 * 極相の台帳のテストが「今」を取ると狂うので、バッチを分けて順番に走らせる。
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

    /**
     * 判定は 5 分(6000 tick)おき = 1 日 4 回。接している面の数ごとの確率は 1 面 25%・2 面 50%・3 面 75%・4 面 100%、
     * 接していなければ 0。
     */
    @GameTest(template = "gametest_empty7")
    public static void chanceGrowsWithTouchingFaces(GameTestHelper helper) {
        int interval = SpreadTable.DEFAULT.intervalTicks();
        expectInt(helper, interval, 6000, "判定の間隔(5 分)");
        double[] c = SpreadTable.DEFAULT.chanceByContacts();
        expect(helper, LedgerSpreadMath.chanceFor(1, c), 0.25D, "1 面");
        expect(helper, LedgerSpreadMath.chanceFor(2, c), 0.50D, "2 面");
        expect(helper, LedgerSpreadMath.chanceFor(3, c), 0.75D, "3 面");
        expect(helper, LedgerSpreadMath.chanceFor(4, c), 1.00D, "4 面");
        expect(helper, LedgerSpreadMath.chanceFor(0, c), 0.00D, "接していない(斜めだけ)");
        expect(helper, LedgerSpreadMath.periodOf(interval - 1, interval), 0.0D, "最初の区切りの最後の tick");
        expect(helper, LedgerSpreadMath.periodOf(interval, interval), 1.0D, "6000 tick で次の回");
        expect(helper, LedgerSpreadMath.periodOf(DAY, interval), 4.0D, "1 日で 4 回");
        helper.succeed();
    }

    /**
     * 1 回の判定: 1 面・2 面・3 面のチャンクがそれぞれ 25%・50%・75% の割合で選ばれ、4 面は全部、
     * 除いたもの(除去装置の範囲など)は 1 つも選ばれない。同じ乱数の種なら同じ結果。
     */
    @GameTest(template = "gametest_empty7")
    public static void rollFollowsTheChances(GameTestHelper helper) {
        double[] c = SpreadTable.DEFAULT.chanceByContacts();
        int each = 20000;
        int[] contacts = new int[each * 3 + 2000 + 2000];
        boolean[] excluded = new boolean[contacts.length];
        for (int i = 0; i < contacts.length; i++) {
            contacts[i] = i < each ? 1 : i < each * 2 ? 2 : i < each * 3 ? 3 : 4;
            excluded[i] = i >= each * 3 + 2000; // 最後の 2000 は 4 面でも除く
        }
        int[] chosen = LedgerSpreadMath.roll(contacts, excluded, c, RandomSource.create(2026L));
        int[] picked = new int[5];
        int excludedPicked = 0;
        for (int i : chosen) {
            if (excluded[i]) {
                excludedPicked++;
            } else {
                picked[contacts[i]]++;
            }
        }
        for (int k = 1; k <= 3; k++) {
            double share = picked[k] / (double) each;
            expectTrue(helper, Math.abs(share - 0.25D * k) < 0.015D, k + " 面の選ばれた割合 " + share + "(期待 " + 0.25D * k + ")");
        }
        expectInt(helper, picked[4], 2000, "4 面は全部選ばれる");
        expectInt(helper, excludedPicked, 0, "除いたチャンクが選ばれた");
        int[] again = LedgerSpreadMath.roll(contacts, excluded, c, RandomSource.create(2026L));
        expectTrue(helper, java.util.Arrays.equals(chosen, again), "同じ乱数の種で結果が変わった");
        helper.succeed();
    }

    /**
     * 極相の齢: 「過繁茂になってからの日数 − F1→F5 の日数」が 14 日で届く。F1 開始のころに過繁茂になったチャンクは
     * F5 の 14 日後、10 日目に過繁茂になったチャンクはその 10 日後 —— 古い順に極相になる。
     */
    @GameTest(template = "gametest_empty7")
    public static void climaxWaitsFourteenDaysAfterSubtractingDaysToPhase5(GameTestHelper helper) {
        long minAge = ClimaxTable.DEFAULT.minAgeTicks();
        expect(helper, minAge, 14 * DAY, "齢のしきい値は 14 日");
        expectInt(helper, ClimaxTable.DEFAULT.minPhase(), 5, "極相化はフェーズ 5 から");
        long phase1 = 3 * DAY;
        long phase5 = 23 * DAY; // F1→F5 に 20 日
        long oldest = 3 * DAY;
        expectTrue(helper, !LedgerSpreadMath.climaxAgeReached(oldest, phase5, minAge, phase1, phase5),
                "F5 になった瞬間に古いチャンクが極相になった");
        expectTrue(helper, !LedgerSpreadMath.climaxAgeReached(oldest, phase5 + 14 * DAY - 1, minAge, phase1, phase5),
                "F5 の 14 日後より前に極相になった");
        expectTrue(helper, LedgerSpreadMath.climaxAgeReached(oldest, phase5 + 14 * DAY, minAge, phase1, phase5),
                "いちばん古いチャンクが F5 の 14 日後に極相にならない");
        long younger = phase1 + 10 * DAY;
        expectTrue(helper, !LedgerSpreadMath.climaxAgeReached(younger, phase5 + 14 * DAY, minAge, phase1, phase5),
                "10 日遅く過繁茂になったチャンクまで同時に極相になった");
        expectTrue(helper, LedgerSpreadMath.climaxAgeReached(younger, phase5 + 24 * DAY, minAge, phase1, phase5),
                "10 日遅く過繁茂になったチャンクが 10 日遅れで極相にならない");
        helper.succeed();
    }

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

    // 新しい場では閾値を後で引き直す(10-23)。
    /**
     * フェーズのしきい値はユーザー指定の日数(F1→F2〜F4→F5 各 5 日、F5→F6・F6→F7 各 20 日、F7→F8〜F9→F10 各 10 日)から
     * 逆算したもの。台帳の広がりの規則(5 分おきに面の数 × 25%)を、地割れに似た 16×3 チャンクの帯から実際に回した
     * 平均の増え方で計算し直して 4% 以内に収まること、F7 以降も自動で上がること。確率や間隔を変えたらしきい値も直す、の見張り。
     */
    // 2026-10-23 外した(胞子の場と風 第 2 段): 広がりは「5 分おきに縁を面の数 × 25%」の規則から胞子の場の発芽に替わり、
    // しきい値はもう旧規則の平均から逆算した値と結び付かない。しきい値と日数はユーザーが後で見直す(触らない指定)ので、
    // それまでこの見張りは外しておく(新しい運び方の見積もりは tools/spread-measure/field の表)。
    // @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void phaseThresholdsMatchTheDaySchedule(GameTestHelper helper) {
        PhaseTable t = PhaseTable.DEFAULT;
        int[] days = {0, 0, 5, 5, 5, 5, 20, 20, 10, 10, 10}; // 添字 n = F(n-1)→Fn の日数
        int interval = SpreadTable.DEFAULT.intervalTicks();
        int stepsPerDay = (int) (DAY / interval);
        double[] area = meanArea(6, 90 * stepsPerDay, SpreadTable.DEFAULT.chanceByContacts()); // 添字 = 判定の回数
        expectTrue(helper, Math.abs(area[20 * stepsPerDay] - 4956.0D) / 4956.0D < 0.08D,
                "20 日目の過繁茂 " + area[20 * stepsPerDay] + "(見積もり 4956)");
        expectTrue(helper, Math.abs(area[40 * stepsPerDay] - 18094.0D) / 18094.0D < 0.08D,
                "40 日目の過繁茂 " + area[40 * stepsPerDay] + "(見積もり 18094)");
        int start = 0;
        for (int n = 2; n <= PhaseTable.MAX_PHASE; n++) {
            int end = start + days[n];
            double modelled = 0.0D;
            for (int s = start * stepsPerDay; s < end * stepsPerDay; s++) {
                modelled += interval * t.pointsPerTickWithChunks((long) area[s]) + t.pointsPerOvergrownChunk() * (area[s + 1] - area[s]);
            }
            long need = t.pointsToAdvance(n);
            expectTrue(helper, need != Long.MAX_VALUE, "フェーズ " + n + " へ自動で上がらない");
            expectTrue(helper, Math.abs(need - modelled) / modelled < 0.04D,
                    "フェーズ " + n + " のしきい値 " + need + " が台帳の広がりからの計算 " + Math.round(modelled) + " と合わない");
            start = end;
        }
        PhaseTable bundled;
        try (InputStream in = LedgerSpreadGameTests.class.getResourceAsStream("/overgrown_vegetation/design/phases.json")) {
            if (in == null) {
                helper.fail("design/phases.json が同梱されていない");
                return;
            }
            bundled = PhaseTable.fromJson(new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), PhaseTable.Json.class));
        } catch (Exception e) {
            helper.fail("design/phases.json が読めない: " + e);
            return;
        }
        for (int n = 2; n <= PhaseTable.MAX_PHASE; n++) {
            expect(helper, bundled.pointsToAdvance(n), t.pointsToAdvance(n), "phases.json のフェーズ " + n + " のしきい値");
        }
        helper.succeed();
    }

    /** spread.json と Java の既定値が同じ。climax.json の minPhase も 5。 */
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
        try (InputStream in = LedgerSpreadGameTests.class.getResourceAsStream("/overgrown_vegetation/design/climax.json")) {
            ClimaxTable climax = ClimaxTable.fromJson(new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), ClimaxTable.Json.class));
            expectInt(helper, climax.minPhase(), ClimaxTable.DEFAULT.minPhase(), "climax.json の minPhase");
            // 順 1 の着手順 10(2026-10-03): 極相化の半径 12(= 縁からの深さ d の上限)と、深さの重みの下限 0.15
            expectInt(helper, climax.neighborRadiusChunks(), ClimaxTable.DEFAULT.neighborRadiusChunks(),
                    "climax.json の neighborRadiusChunks");
            if (Math.abs(climax.depthSpawnFloor() - ClimaxTable.DEFAULT.depthSpawnFloor()) > 1e-9) {
                helper.fail("climax.json の depthSpawnFloor が Java の既定値と違う: " + climax.depthSpawnFloor()
                        + " / " + ClimaxTable.DEFAULT.depthSpawnFloor());
                return;
            }
        } catch (Exception e) {
            helper.fail("design/climax.json が読めない: " + e);
            return;
        }
        helper.succeed();
    }

    /** 胞子除去装置の範囲(既定 48 ブロック)のチャンクは守られ、遠くのチャンクは守られない。 */
    @GameTest(template = "gametest_empty7")
    public static void purifierRangeIsGuarded(GameTestHelper helper) {
        long[] device = {BlockPos.asLong(8, 64, 8)}; // チャンク (0,0) の真ん中
        expectTrue(helper, SporeDeviceData.guards(device, 0, 0), "装置のあるチャンクが守られない");
        expectTrue(helper, SporeDeviceData.guards(device, 2, 0), "32 ブロック先のチャンクが守られない");
        expectTrue(helper, !SporeDeviceData.guards(device, 6, 0), "96 ブロック先のチャンクまで守られた");
        expectTrue(helper, !SporeDeviceData.guards(new long[0], 0, 0), "装置が無いのに守られた");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 世界を触るテスト(自分だけのバッチ)

    /** 台帳の縁は「台帳に無い・辺で接している」チャンクで、接している数を数える(L 字の切れ込みは 2)。 */
    @GameTest(template = "gametest_empty7", batch = WORLD_BATCH)
    public static void frontierCountsEdgeContacts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.get(level);
        ChunkPos c = freshCenter();
        // L 字: (0,0) (1,0) (0,1)。切れ込み (1,1) は 2 つと接する。
        data.adoptOvergrownChunk(level, new ChunkPos(c.x, c.z));
        data.adoptOvergrownChunk(level, new ChunkPos(c.x + 1, c.z));
        data.adoptOvergrownChunk(level, new ChunkPos(c.x, c.z + 1));
        Long2IntOpenHashMap contacts = LedgerSpreadTestHooks.frontier(data);
        expectInt(helper, contacts.get(ChunkPos.asLong(c.x + 1, c.z + 1)), 2, "L 字の切れ込み");
        expectInt(helper, contacts.get(ChunkPos.asLong(c.x + 2, c.z)), 1, "東の端の外");
        expectInt(helper, contacts.get(ChunkPos.asLong(c.x - 1, c.z)), 1, "西の端の外");
        expectTrue(helper, !contacts.containsKey(ChunkPos.asLong(c.x, c.z)), "台帳にあるチャンクが縁に入った");
        expectTrue(helper, !contacts.containsKey(ChunkPos.asLong(c.x + 2, c.z + 2)), "斜めにしか接していないチャンクが縁に入った");
        helper.succeed();
    }

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
                out[c] = level.getBlockState(new BlockPos(x, y - k, z)).is(ModTags.CORRUPTED);
            }
        }
        return out;
    }

    /**
     * 台帳の広がりの規則を、地割れに似た 16×3 チャンクの帯から {@code runs} 回・{@code steps} 回の判定ぶん回した平均の過繁茂チャンク数
     * (添字 = 判定の回数、0 は最初)。判定は本物と同じ {@link LedgerSpreadMath#roll}(除くものは無し)。
     * 縁は差分で持つ(塗ったチャンクを縁から外し、4 方向の隣の接している数を足す)ので、1 回の判定は縁の数に比例するだけ。
     */
    private static double[] meanArea(int runs, int steps, double[] chances) {
        double[] mean = new double[steps + 1];
        for (int run = 0; run < runs; run++) {
            RandomSource random = RandomSource.create(1000L + run);
            it.unimi.dsi.fastutil.longs.LongOpenHashSet over = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            Long2IntOpenHashMap frontier = new Long2IntOpenHashMap();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 3; z++) {
                    markForSimulation(over, frontier, ChunkPos.asLong(x, z));
                }
            }
            mean[0] += over.size();
            for (int s = 1; s <= steps; s++) {
                long[] keys = new long[frontier.size()];
                int[] counts = new int[frontier.size()];
                int i = 0;
                for (it.unimi.dsi.fastutil.longs.Long2IntMap.Entry e
                        : it.unimi.dsi.fastutil.longs.Long2IntMaps.fastIterable(frontier)) {
                    keys[i] = e.getLongKey();
                    counts[i] = e.getIntValue();
                    i++;
                }
                for (int index : LedgerSpreadMath.roll(counts, null, chances, random)) {
                    markForSimulation(over, frontier, keys[index]);
                }
                mean[s] += over.size();
            }
        }
        for (int s = 0; s <= steps; s++) {
            mean[s] /= runs;
        }
        return mean;
    }

    private static void markForSimulation(it.unimi.dsi.fastutil.longs.LongOpenHashSet over, Long2IntOpenHashMap frontier, long key) {
        if (!over.add(key)) {
            return;
        }
        frontier.remove(key);
        int x = ChunkPos.getX(key);
        int z = ChunkPos.getZ(key);
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : sides) {
            long n = ChunkPos.asLong(x + d[0], z + d[1]);
            if (!over.contains(n)) {
                frontier.addTo(n, 1);
            }
        }
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
