package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SpreadTable;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>台帳の広がり</b>(2026-09-26、2026-09-28 に規則を変更)。過繁茂バイオームを、プレイヤーがいるかどうかに関係なく広げる。
 *
 * <p>汚染シダは読み込まれた場所でしか動かないので、それだけだと過繁茂の増え方がプレイヤーの居場所しだいになる。
 * そこで<b>判定の間隔ごとに</b>(汚染の暦 {@link PhaseData#clock} が {@link SpreadTable#intervalTicks} の倍数の境目を越えるたびに 1 回。
 * ユーザー指定 5 分 = 6000 tick、1 日 4 回)、台帳の縁 —— 台帳に載っていないチャンクのうち過繁茂と辺で接しているもの —— を、
 * 接している面の数に応じた確率({@link SpreadTable#chanceByContacts}、ユーザー指定 1 面 25%・2 面 50%・3 面 75%・4 面 100%)で過繁茂にする。
 * 縁のチャンクは全部、その回の初めの台帳から一斉に判定する(その回に塗ったチャンクが同じ回のうちに隣を塗ることはない)。
 *
 * <ul>
 *     <li><b>除くもの</b> —— ワールドの境界の外と、胞子除去装置の効果範囲({@link SporeDeviceData#guards})。
 *     {@link SpreadTable#includeLoadedChunks} が false なら読み込まれているチャンクも(既定は含める = ユーザー指定)。
 *     ワールドの設定「読み込まれていない範囲への侵食」がオフなら、読み込まれていないチャンク(2026-10-10)。</li>
 *     <li><b>印を付けたら</b> —— {@link PhaseData#markOvergrownChunks}(面積とフェーズポイントに数える)。読み込まれていれば
 *     すぐ、読み込まれていなければ読み込まれたときに {@link LedgerSpreadCatchUp} がバイオームと地表を書き換える。</li>
 *     <li><b>睡眠</b> —— 飛ばした時間を汚染の暦に足す({@link #onSleepFinished})。飛ばした時間に入っていた回(夜なら 2 回ほど)は、
 *     起きた直後から 1 tick に 1 回ずつ判定する。暦がまとめて進んだとき({@code /ov climax age} など)も同じ。</li>
 *     <li>フェーズ0 と凍結中は止まる。再開したとき・間隔の設定を変えたときは、その回から数え直す(さかのぼって処理しない)。</li>
 * </ul>
 *
 * <p>台帳のキーは次元を持たないので、オーバーワールドだけで動く(極相・生態系の台帳と同じ制約。
 * ネザーで過繁茂になったチャンクも同じ座標の縁として数えられてしまうのは既存の性質)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LedgerSpread {

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** 読み込まれたばかりのチャンク(ChunkEvent.Load の中では世界にもデータにも触らず、ここに積むだけ)。 */
    private static final LongLinkedOpenHashSet LOADED = new LongLinkedOpenHashSet();
    /** 1 tick に確かめる「読み込まれたばかり」の数。 */
    private static final int LOADED_CHECKS_PER_TICK = 64;

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            LOADED.add(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LOADED.clear();
        LedgerSpreadCatchUp.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        SpreadTable table = DesignData.spread();
        drainLoaded(level);
        if (CorruptionSwitch.isOff()) {
            // /ov corruption false(2026-10-10 ユーザー指定): 台帳に印を付けず、印の付いたチャンクも塗らない(順番待ちは残る)。
            // 区切りはフェーズを止めたときと同じく止める = オンに戻してもオフの間の回は取り戻さない。
            // 読み込まれたばかりのチャンクの一覧(drainLoaded)はオフの間も捌く(待つ一覧に移るのは印の付いたチャンクだけなので増え続けない)。
            LedgerSpreadData.get(level.getServer()).stop();
            return;
        }
        LedgerSpreadCatchUp.tick(level, table);
        // 「5 分おきに縁を面の数 × 25%」の規則は止めた(2026-10-23 胞子の場と風 第 2 段)。広がりは胞子の場の発芽が担い、
        // 発芽したチャンクは acceptFieldSprouts で台帳と書き換え待ちに載る。書き換え待ちの捌き(CatchUp)と睡眠の暦はそのまま。
        // tickPeriods は残してある(テストと見積もりの道具が呼ぶ)。
    }

    /**
     * 睡眠で夜を飛ばした(2026-09-26、ユーザー指定「寝た時はしっかり一日分汚染を広げる」)。飛ばした時間を汚染の暦に足す
     * —— 飛ばした時間に入っていた回の判定が、次の tick から順に走る。フェーズの時間経過ポイントも入れる
     * ({@link SpreadTable#sleepGivesPhasePoints})。他の MOD が起きる時刻を変えても最後の値を使うよう、いちばん後に聞く。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSleepFinished(SleepFinishedTimeEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        creditSleep(level, event.getNewTime() - level.getDayTime(), DesignData.spread());
    }

    /** 睡眠で {@code skippedTicks} 飛ばした分を足す(テストからも呼ぶ)。足した暦の長さを返す。 */
    static long creditSleep(ServerLevel overworld, long skippedTicks, SpreadTable table) {
        if (skippedTicks <= 0L || !table.sleepAdvancesClock()) {
            return 0L;
        }
        PhaseData.get(overworld).addSleepSkip(overworld, skippedTicks, table.sleepGivesPhasePoints());
        return skippedTicks;
    }

    /**
     * 読み込まれたばかりのチャンクと、その周り 8 チャンクのうち、書き換え待ちのものを列へ。書き換えは周りまで
     * 読み込まれるのを待つので({@link LedgerSpreadCatchUp#neighbourhoodLoaded})、隣が読み込まれたときにも見直す。
     */
    private static void drainLoaded(ServerLevel level) {
        if (LOADED.isEmpty()) {
            return;
        }
        LedgerSpreadData data = LedgerSpreadData.get(level.getServer());
        for (int i = 0; i < LOADED_CHECKS_PER_TICK && !LOADED.isEmpty(); i++) {
            long key = LOADED.removeFirstLong();
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long n = ChunkPos.asLong(x + dx, z + dz);
                    if (data.isPending(n)) {
                        LedgerSpreadCatchUp.enqueue(n);
                    }
                }
            }
        }
    }

    /**
     * 判定の区切りを越えていたら、まだ判定していない回を 1 つだけ判定する(1 tick に 1 回まで)。付けた印の数を返す。
     */
    static int tickPeriods(ServerLevel level, SpreadTable table) {
        MinecraftServer server = level.getServer();
        PhaseData phase = PhaseData.get(level);
        LedgerSpreadData data = LedgerSpreadData.get(server);
        if (phase.getPhase() < 1 || phase.isFrozen()) {
            data.stop();
            return 0;
        }
        int interval = table.intervalTicks();
        long current = LedgerSpreadMath.periodOf(phase.clock(server), interval);
        if (!data.started() || data.periodTicks() != interval) {
            data.start(current, interval);
            return 0;
        }
        if (current <= data.pendingPeriod() || FRONTIER_TASK.running()) {
            return 0;
        }
        // サーバーが重ければ次の tick に回す(2026-10-06)。
        if (!TickBudget.mayStart(PerfJob.LEDGER_PERIODS)) {
            return 0;
        }
        // 台帳の縁(台帳の全チャンク × 4 辺の参照。過繁茂のチャンク数に比例して重くなる)は、台帳の写しから別スレッドで数える
        // (2026-10-06、④)。答えは次の tick の頭に当てはめる。写しは中の配列ごと写すので反復の順番も同じ ——
        // その 1 tick の間に台帳が変わらなければ、印の付き方はサーバースレッドで数えたときと同じ。
        long period = data.pendingPeriod();
        long start = TickBudget.begin();
        int[] marked = {0};
        try {
            it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap snapshot = phase.snapshotLedger();
            FRONTIER_TASK.start(server, () -> frontier(snapshot), contacts -> marked[0] = applyPeriod(level, table, period, contacts));
        } finally {
            TickBudget.end(PerfJob.LEDGER_PERIODS, start, 1);
        }
        return marked[0]; // 別スレッドで数えているあいだは 0(GameTest のサーバーではその場で当てはめる)
    }

    /** 台帳の縁を数える仕事(別スレッド、2026-10-06)。 */
    private static final com.yayuy.overgrownvegetation.perf.BackgroundTask<Long2IntOpenHashMap> FRONTIER_TASK =
            new com.yayuy.overgrownvegetation.perf.BackgroundTask<>("台帳の縁");

    /** 別スレッドで数えた縁で、{@code period} 回目の判定をする(サーバースレッド)。付けた印の数を返す。 */
    private static int applyPeriod(ServerLevel level, SpreadTable table, long period, Long2IntOpenHashMap contacts) {
        LedgerSpreadData data = LedgerSpreadData.get(level.getServer());
        if (!data.started() || data.pendingPeriod() != period) {
            return 0; // 数えているあいだに止まった・やり直した(フェーズが下がった・間隔を変えた)
        }
        PhaseData phase = PhaseData.get(level);
        // 写してから台帳に入ったチャンクはもう縁ではない(読み込まれているチャンクの浸食など)。変わったときだけ外す ——
        // 外すと表の並びが変わるので、変わっていなければ触らない。
        boolean stale = false;
        for (long key : contacts.keySet()) {
            if (phase.isOvergrownKey(key)) {
                stale = true;
                break;
            }
        }
        if (stale) {
            contacts.keySet().removeIf(phase::isOvergrownKey);
        }
        int marked = spreadWith(level, table, LedgerSpreadMath.periodSeed(level.getSeed(), period), contacts);
        data.finishPeriod(marked);
        return marked;
    }

    /**
     * 判定を 1 回する。台帳の縁のチャンクを接している面の数に応じた確率で過繁茂にし、付けた数を返す
     * ({@code /ov spread step} からも呼ぶ)。{@code seed} はこの回の乱数の種。
     */
    public static int spreadOnce(ServerLevel level, SpreadTable table, long seed) {
        return spreadWith(level, table, seed, frontier(PhaseData.get(level)));
    }

    /** {@link #spreadOnce} の後半: 数えた縁 {@code contacts} に印を付ける。 */
    private static int spreadWith(ServerLevel level, SpreadTable table, long seed, Long2IntOpenHashMap contacts) {
        PhaseData phase = PhaseData.get(level);
        if (contacts.isEmpty()) {
            return 0;
        }
        int size = contacts.size();
        long[] keys = new long[size];
        int[] counts = new int[size];
        boolean[] excluded = new boolean[size];
        long[] guards = SporeDeviceData.ledgerGuards(level);
        WorldBorder border = level.getWorldBorder();
        boolean includeLoaded = table.includeLoadedChunks();
        boolean unloadedAllowed = WorldDifficultyData.unloadedSpread(level.getServer());
        int i = 0;
        for (Long2IntMap.Entry e : Long2IntMaps.fastIterable(contacts)) {
            long key = e.getLongKey();
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            keys[i] = key;
            counts[i] = e.getIntValue();
            excluded[i] = !border.isWithinBounds(new ChunkPos(x, z))
                    || SporeDeviceData.guards(guards, x, z)
                    || (!includeLoaded && level.getChunkSource().getChunkNow(x, z) != null)
                    || unloadedBlocked(level, unloadedAllowed, x, z);
            i++;
        }
        int[] chosen = LedgerSpreadMath.roll(counts, excluded, table.chanceByContacts(), RandomSource.create(seed));
        if (chosen.length == 0) {
            return 0;
        }
        List<ChunkPos> picked = new ArrayList<>(chosen.length);
        for (int index : chosen) {
            picked.add(new ChunkPos(keys[index]));
        }
        phase.markOvergrownChunks(level, picked);
        LedgerSpreadData data = LedgerSpreadData.get(level.getServer());
        for (ChunkPos pos : picked) {
            long key = pos.toLong();
            data.addPending(key);
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
                LedgerSpreadCatchUp.enqueue(key);
            }
        }
        return picked.size();
    }

    /**
     * 胞子の場で初めて発芽したチャンクを受け取る(2026-10-23 胞子の場と風 第 2 段 A。サーバースレッド)。
     * 台帳に載せ(フェーズに数える = 1 チャンク 5 点と面積)、書き換え待ちに積む —— 読み込まれていれば今すぐ、
     * そうでなければ読み込まれたときに {@link LedgerSpreadCatchUp} が今までどおり過繁茂へ書き換える。
     * 受け取らないもの(世界の縁の外・焼けた荒地・ワールド設定で読み込まれていない範囲への侵食がオフ)を返す(場で取り消す)。
     * 除去装置の範囲の守りは見ない(新しい設計では除去装置は場から抜く吸い込み口で、範囲を 0 にはしない。2026-10-22 決定)。
     */
    public static long[] acceptFieldSprouts(ServerLevel level, long[] keys) {
        return acceptFieldSprouts(level, keys, null);
    }

    /**
     * {@code waiting} を渡すと、読み込まれていないので断ったもの(設定「読み込まれていない範囲への侵食」がオフ)は返り値でなくそこへ入れる
     * (場は取り消さずに読み込まれるまで待たせる。2026-10-24)。
     */
    public static long[] acceptFieldSprouts(ServerLevel level, long[] keys, it.unimi.dsi.fastutil.longs.LongArrayList waiting) {
        if (CorruptionSwitch.isOff()) {
            return keys;
        }
        WorldBorder border = level.getWorldBorder();
        boolean unloadedAllowed = WorldDifficultyData.unloadedSpread(level.getServer());
        List<ChunkPos> picked = new ArrayList<>(keys.length);
        it.unimi.dsi.fastutil.longs.LongArrayList rejected = new it.unimi.dsi.fastutil.longs.LongArrayList();
        PhaseData phase = PhaseData.get(level);
        for (long key : keys) {
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            ChunkPos pos = new ChunkPos(x, z);
            if (phase.isOvergrownKey(key)) {
                continue; // ほかの経路で既に載っている
            }
            if (waiting != null && border.isWithinBounds(pos) && unloadedBlocked(level, unloadedAllowed, x, z)) {
                waiting.add(key);
                continue;
            }
            if (!border.isWithinBounds(pos) || unloadedBlocked(level, unloadedAllowed, x, z)) {
                rejected.add(key);
                continue;
            }
            picked.add(pos);
        }
        if (!picked.isEmpty()) {
            phase.markOvergrownChunks(level, picked);
            LedgerSpreadData data = LedgerSpreadData.get(level.getServer());
            for (ChunkPos pos : picked) {
                long key = pos.toLong();
                data.addPending(key);
                if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
                    com.yayuy.overgrownvegetation.spore.field.SporeSprouts.markFresh(key); // 読み込まれている間の発芽 = 歩く萌芽体
                    LedgerSpreadCatchUp.enqueue(key);
                }
            }
        }
        return rejected.toLongArray();
    }

    /**
     * ワールドの設定「読み込まれていない範囲への侵食」がオフ({@code unloadedAllowed} が false)で、そのチャンクが
     * 読み込まれていないなら true(印を付けない。2026-10-10)。ネザーの台帳も同じ判定を使う。
     */
    static boolean unloadedBlocked(ServerLevel level, boolean unloadedAllowed, int x, int z) {
        return !unloadedAllowed && level.getChunkSource().getChunkNow(x, z) == null;
    }

    /**
     * {@link #frontier(PhaseData)} を台帳の写しで数える版(別スレッドから呼ぶ、2026-10-06)。反復の順番が同じなので、
     * 同じ台帳なら返す表(中の並びも)も同じ。
     */
    public static Long2IntOpenHashMap frontier(it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap ledger) {
        Long2IntOpenHashMap contacts = new Long2IntOpenHashMap();
        it.unimi.dsi.fastutil.longs.LongIterator keys = ledger.keySet().iterator();
        while (keys.hasNext()) {
            long key = keys.nextLong();
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            for (int[] d : SIDES) {
                long n = ChunkPos.asLong(x + d[0], z + d[1]);
                if (!ledger.containsKey(n)) {
                    contacts.addTo(n, 1);
                }
            }
        }
        return contacts;
    }

    /** 台帳の縁: 台帳に載っていないチャンク → 辺で接している台帳のチャンクの数(1〜4)。 */
    static Long2IntOpenHashMap frontier(PhaseData phase) {
        Long2IntOpenHashMap contacts = new Long2IntOpenHashMap();
        phase.forEachOvergrownChunk(key -> {
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            for (int[] d : SIDES) {
                long n = ChunkPos.asLong(x + d[0], z + d[1]);
                if (!phase.isOvergrownKey(n)) {
                    contacts.addTo(n, 1);
                }
            }
        });
        return contacts;
    }

    /** {@code /ov spread}・{@code /ov perf} の表示用: 書き換え待ちのうち、読み込まれていて順番を待っている数。 */
    public static int queuedCatchUps() {
        return LedgerSpreadCatchUp.queued();
    }

    private LedgerSpread() {
    }
}
