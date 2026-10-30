package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SpreadTable;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
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
 * <b>台帳の受け取りと書き換え待ち</b>(2026-09-26)。広がりそのものは 2026-10-23 から胞子の場の発芽が担い、
 * 発芽したチャンクを {@link #acceptFieldSprouts} で台帳に載せる(面積とフェーズポイントに数える)。読み込まれていればすぐ、
 * 読み込まれていなければ読み込まれたときに {@link LedgerSpreadCatchUp} がバイオームを書き換える。
 * 止まっていた「5 分おきに台帳の縁を面の数 × 25% で塗る」規則は 2026-10-30 に消した。
 *
 * <ul>
 *     <li><b>受け取らないもの</b> —— ワールドの境界の外と、ワールドの設定「読み込まれていない範囲への侵食」がオフのときの
 *     読み込まれていないチャンク(2026-10-10。場は読み込まれるまで待つ)。</li>
 *     <li><b>睡眠</b> —— 飛ばした時間を汚染の暦に足す({@link #onSleepFinished})。場はその分を追いつく。</li>
 * </ul>
 *
 * <p>台帳のキーは次元を持たないので、オーバーワールドだけで動く(ネザーは {@link NetherLedger})。
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
            return;
        }
        LedgerSpreadCatchUp.tick(level, table);
        // 「5 分おきに縁を面の数 × 25%」の規則は止めた(2026-10-23 胞子の場と風 第 2 段)。広がりは胞子の場の発芽が担い、
        // 発芽したチャンクは acceptFieldSprouts で台帳と書き換え待ちに載る。書き換え待ちの捌き(CatchUp)と睡眠の暦はそのまま。
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


    private LedgerSpread() {
    }
}
