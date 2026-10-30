package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>過繫茂チャンク台帳</b>({@link PhaseData} の {@code overgrownSince})のテスト。
 * 極相({@code engulfed})への昇格条件はこの台帳だけで決まるので、ここが狂うと
 * 「極相が永久に生まれない」か「汚染しはじめた翌日に極相になる」のどちらかになる。
 *
 * <p>守りたいのは 4 つ。
 * <ul>
 *     <li><b>同じチャンクを二重に数えない</b> —— 数えるとフェーズポイントが水増しされる。</li>
 *     <li><b>半径の内側が 1 チャンクでも欠けていたら昇格しない</b> —— 半径のループが
 *     off-by-one だと、汚染の縁が極相になったり、逆に永久にならなかったりする。</li>
 *     <li><b>齢が足りなければ昇格しない</b> —— 14 日待たせる仕掛けそのもの。</li>
 *     <li><b>極相になったチャンクも「過繫茂以上」として数える</b> —— 台帳から消すと、
 *     最初の 1 チャンクが極相になった時点で極相の広がりが止まる。</li>
 * </ul>
 *
 * <p><b>チャンク座標をゲーム内時刻から作っている理由</b>: 台帳には項目を消す口が無い
 * (極相になっても「過繫茂以上」の印として残す設計なので、消す口を作ると壊れる)。
 * 固定の座標を使うと、同じワールドで 2 回目を走らせたときに 1 回目の項目が残っていて
 * 「欠けている」が作れず、テストが通らなくなる。走るたびに別の領域を使えばその問題が消える。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ClimaxLedgerGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ClimaxLedgerGameTests.class);
        }

        private Registration() {
        }
    }

    /** この JVM の実行に固有の値。前回の実行が残した項目と重ならないようにするためだけのもの。 */
    private static final int RUN_SALT = new java.util.Random().nextInt(1 << 20);

    /** この実行の中で呼ぶたびに増える。テスト同士が同じ領域を掴まないようにする。 */
    private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger();

    /** 同じチャンクは 1 回しか記録されない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void ledgerIgnoresDuplicates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.get(level);
        ChunkPos pos = freshCenter(helper);

        int before = data.overgrownLedgerSize();
        if (!data.markOvergrownChunk(level, pos)) {
            helper.fail("初回の記録が false を返した(台帳に既に載っている?)");
            return;
        }
        if (data.markOvergrownChunk(level, pos)) {
            helper.fail("同じチャンクが二重に記録された");
            return;
        }
        if (data.overgrownLedgerSize() != before + 1) {
            helper.fail("台帳の件数が 1 だけ増えていない: " + before + " → " + data.overgrownLedgerSize());
            return;
        }
        if (data.overgrownSinceTick(pos) < 0L) {
            helper.fail("記録したのに過繫茂になった時刻が入っていない");
            return;
        }
        if (!data.isOvergrownChunk(pos)) {
            helper.fail("記録したのに過繫茂以上として扱われていない");
            return;
        }
        helper.succeed();
    }

    /** 台帳に無いチャンクは「過繫茂以上ではない」・時刻は -1。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void unknownChunkIsNotOvergrown(GameTestHelper helper) {
        PhaseData data = PhaseData.get(helper.getLevel());
        ChunkPos pos = freshCenter(helper);

        if (data.isOvergrownChunk(pos)) {
            helper.fail("記録していないチャンクが過繫茂以上になっている");
            return;
        }
        if (data.overgrownSinceTick(pos) != -1L) {
            helper.fail("記録していないチャンクの時刻が -1 ではない: " + data.overgrownSinceTick(pos));
            return;
        }
        helper.succeed();
    }

    /**
     * 半径の円の内側が 1 チャンクでも欠けていたら極相にならない。埋まった瞬間に通る。
     * 円で測る(順 1 の着手順 10、2026-10-03)ので、正方形の角(円の外)は欠けていてもよい。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void climaxNeedsEveryNeighbour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.get(level);
        ChunkPos center = freshCenter(helper);
        long now = PhaseData.clockNow(level); // 台帳の時刻は汚染の暦(gameTime + 睡眠で飛ばした夜)で付く(2026-09-26)

        int radius = 2;
        // 円の上の端(dx² + dz² = r²)を 1 つ欠かす。角 (r, r) は円の外なので最初から埋めない
        ChunkPos missing = new ChunkPos(center.x + radius, center.z);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                if (dx * dx + dz * dz <= radius * radius && !pos.equals(missing)) {
                    data.adoptOvergrownChunk(level, pos);
                }
            }
        }

        if (data.canBecomeClimax(center, radius, 0L, now)) {
            helper.fail("円の端が 1 チャンク欠けているのに極相の条件を満たしている");
            return;
        }
        data.adoptOvergrownChunk(level, missing);
        if (!data.canBecomeClimax(center, radius, 0L, now)) {
            helper.fail("円の内側が全て埋まったのに極相の条件を満たさない(角まで要求している?)");
            return;
        }
        if (data.countMissingNeighbours(center, radius) != 0) {
            helper.fail("円の内側が全て埋まったのに欠けを数えた: " + data.countMissingNeighbours(center, radius));
            return;
        }
        helper.succeed();
    }

    /** 齢が足りなければ、周囲が埋まっていても極相にならない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void climaxNeedsAge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.get(level);
        ChunkPos center = freshCenter(helper);
        long now = PhaseData.clockNow(level); // 台帳の時刻は汚染の暦(gameTime + 睡眠で飛ばした夜)で付く(2026-09-26)

        int radius = 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                data.adoptOvergrownChunk(level, new ChunkPos(center.x + dx, center.z + dz));
            }
        }

        // adopt は「今」の時刻で記録するので、齢は 0。14 日ぶん要求すれば必ず足りない。
        long fourteenDays = 14L * 24000L;
        if (data.canBecomeClimax(center, radius, fourteenDays, now)) {
            helper.fail("過繫茂になった直後なのに極相の条件を満たしている");
            return;
        }
        if (!data.canBecomeClimax(center, radius, fourteenDays, now + fourteenDays)) {
            helper.fail("14 日経っても極相の条件を満たさない");
            return;
        }
        helper.succeed();
    }


    /**
     * {@code /ov climax age}({@link PhaseData#skipCalendar})は<b>暦を先へ送る</b>だけで、
     * 台帳の時刻には触らない。
     *
     * <p><b>何度でも効くこと</b>を見るのがこのテストの肝(2026-09-26 の作り直し)。前は台帳の時刻を
     * 過去へずらしていて、時刻が 0 で止まると<b>それ以上は何度撃っても齢が伸びなかった</b> ——
     * 齢のしきい値がワールドの齢より長い(F1→F{@code minPhase} の暦を足すので普通に起こる)と
     * 必ずその状態に入り、「日数を上げても極相化しない」になる。
     *
     * <p>暦を動かすので、齢を見ている他のテストと同じ組で走らせない({@code batch} を分ける ——
     * 同じ組のテストは並列に走る)。GameTest のワールドは実行をまたいで残るので、<b>必ず元へ戻す</b>。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = "climax_backdate")
    public static void calendarSkipRaisesTheAgeEveryTime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.get(level);
        ChunkPos center = freshCenter(helper);
        long before = data.sleepSkippedTicks();
        long fourteenDays = 14L * 24000L;
        try {
            long now = PhaseData.clockNow(level);
            int radius = 1;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    data.adoptOvergrownChunk(level, new ChunkPos(center.x + dx, center.z + dz));
                }
            }
            long since = data.overgrownSinceTick(center);
            // 周囲は埋まっているが、記録したばかりなので齢が足りない。
            if (data.canBecomeClimax(center, radius, fourteenDays, now)) {
                helper.fail("記録した直後なのに齢の条件を満たしている");
                return;
            }

            data.skipCalendar(fourteenDays);
            if (data.clock(level.getServer()) != now + fourteenDays) {
                helper.fail("暦が 14 日ぶん進んでいない: " + now + " → " + data.clock(level.getServer()));
                return;
            }
            if (data.overgrownSinceTick(center) != since) {
                helper.fail("台帳の時刻まで動かした: " + since + " → " + data.overgrownSinceTick(center));
                return;
            }
            if (!data.canBecomeClimax(center, radius, fourteenDays, data.clock(level.getServer()))) {
                helper.fail("暦を 14 日進めたのに齢の条件を満たさない");
                return;
            }

            // 2 回目も同じだけ効く(頭打ちが無い)。ここが前の作りで死んでいた。
            data.skipCalendar(fourteenDays);
            long twentyEight = 28L * 24000L;
            if (!data.canBecomeClimax(center, radius, twentyEight, data.clock(level.getServer()))) {
                helper.fail("2 回目の進めが効いていない(齢 "
                        + (data.clock(level.getServer()) - since) + " tick)");
                return;
            }
            helper.succeed();
        } finally {
            data.restoreSleepSkipForTests(before);
        }
    }

    /**
     * 極相になったチャンクは、周囲の判定で<b>引き続き条件を満たす側として数えられる</b>。
     * ここが崩れると、最初の 1 チャンクが極相になった時点で極相の広がりが止まる。
     * 過繫茂を経ずに直接極相にした場合も同じく数えられることを見ている。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void climaxChunkStillCountsAsNeighbour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.get(level);
        ChunkPos center = freshCenter(helper);
        long now = PhaseData.clockNow(level); // 台帳の時刻は汚染の暦(gameTime + 睡眠で飛ばした夜)で付く(2026-09-26)

        int radius = 1;
        // 円で測るので、半径 1 の隣は東西南北の 4 つ(斜めは円の外。順 1 の着手順 10)
        ChunkPos neighbour = new ChunkPos(center.x + 1, center.z);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                // 隣のうち 1 つだけは過繫茂を経ずに直接極相にする。
                if (!pos.equals(neighbour)) {
                    data.adoptOvergrownChunk(level, pos);
                }
            }
        }
        if (data.canBecomeClimax(center, radius, 0L, now)) {
            helper.fail("隣が 1 チャンク欠けているのに極相の条件を満たしている");
            return;
        }

        if (!data.markClimaxChunk(level, neighbour)) {
            helper.fail("台帳に無いチャンクを極相にしたのに載らなかった");
            return;
        }
        if (!data.isOvergrownChunk(neighbour)) {
            helper.fail("極相にしたチャンクが過繫茂以上として扱われていない");
            return;
        }
        if (!data.canBecomeClimax(center, radius, 0L, now)) {
            helper.fail("隣が極相なのに条件を満たさない(極相が過繫茂以上として数えられていない)");
            return;
        }

        // 既に載っているチャンクを極相にしても、台帳は増えないし時刻も動かない。
        long before = data.overgrownSinceTick(neighbour);
        int sizeBefore = data.overgrownLedgerSize();
        if (data.markClimaxChunk(level, neighbour)) {
            helper.fail("既に台帳にあるチャンクが二重に載った");
            return;
        }
        if (data.overgrownLedgerSize() != sizeBefore || data.overgrownSinceTick(neighbour) != before) {
            helper.fail("極相化で台帳の件数か時刻が書き換わった");
            return;
        }
        helper.succeed();
    }

    /**
     * このテスト専用の、まだ誰も触っていないチャンク座標。呼ぶたびに別の領域を返す。
     *
     * <p><b>なぜこんな作りなのか</b>: 台帳には項目を消す口が無く(極相になっても「過繫茂以上」の印として
     * 残す設計なので、消す口を作ると壊れる)、しかも gametest のワールド({@code run/world})は
     * 実行をまたいで残る。固定の座標を使うと、前回の実行で書き込んだ項目のせいで
     * 「欠けている」状態が作れず、2 回目から落ちる。
     *
     * <p>{@link #RUN_SALT}(JVM ごとに 1 回引く乱数)で<b>実行をまたいだ</b>重複を、
     * {@link #NEXT}(呼ぶたびに増える)で<b>実行の中での</b>重複を防ぐ。
     * 1 テストが触るのは半径 2 = 5×5 までなので、64 チャンク刻みで十分離れる。
     */
    private static ChunkPos freshCenter(GameTestHelper helper) {
        int base = 1_000_000 + RUN_SALT * 1024 + NEXT.getAndIncrement() * 64;
        return new ChunkPos(base, base);
    }

    private ClimaxLedgerGameTests() {
    }
}
