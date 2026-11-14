package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.data.SpreadTable;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import com.yayuy.overgrownvegetation.spore.field.SporeSprouts;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * 台帳に載った(胞子の場で発芽した)チャンクを、読み込まれているときに<b>世界へ反映する</b>(2026-09-26)。
 *
 * <p>1 チャンクにつき({@link #convertChunk}): ①バイオームを書き換える(発芽して 2 日未満は繁茂の札、それ以外は過繁茂。
 * 台帳には載っているので面積もポイントも数え直さない) ②芽の点と萌芽体を置く({@link SporeSprouts#start})。
 * 地表はここでは塗らない —— 点から根のように広がる芽と、過繁茂バイオームの浸食 {@link OvergrownBiomeCreep} に任せる。
 * 2026-11-14: 胞子の場が止まっているとき({@code spore_field.json} の enabled=false)に走っていた「地表を四角くまだらに塗る」
 * 古い道(paintSurface)と、その分かれ道を消した(見直し 11-06 (B) 6、ユーザー決定 11-07)。場が止まっている間は発芽が無いので
 * 新しく台帳に載るチャンクも無く、前から待っていたチャンクも同じやり方(バイオーム + 芽)で書き換える。
 *
 * <p>書き換えるのは<b>周り 8 チャンクまで読み込まれているチャンクだけ</b>({@link #neighbourhoodLoaded}、2026-10-06)。
 * 読み込み範囲のいちばん外側のチャンクは、その外が読み込まれるまで待つ。
 * 除去装置の範囲に入ったチャンク(印を付けた後に置かれた)は書き換えずに外す。
 */
final class LedgerSpreadCatchUp {

    /** 1 チャンクの書き換えを、1 tick の予算({@link SpreadTable#columnsPerTick})の何列ぶんに数えるか。 */
    private static final int COST_PER_CHUNK = 16;

    /** 読み込まれていて、書き換えを待っているチャンク(実行時だけ)。 */
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();

    static void enqueue(long chunkKey) {
        QUEUE.add(chunkKey);
    }

    static int queued() {
        return QUEUE.size();
    }

    static void clear() {
        QUEUE.clear();
    }

    static void tick(ServerLevel level, SpreadTable table) {
        if (QUEUE.isEmpty()) {
            return;
        }
        // サーバーが重いときは予算の番人が次の tick へ回す(2026-10-06。結果は同じで遅れるだけ)。
        if (!TickBudget.mayStart(PerfJob.LEDGER_CATCH_UP)) {
            return;
        }
        long start = TickBudget.begin();
        int columns = 0;
        try {
            columns = work(level, table);
        } finally {
            TickBudget.end(PerfJob.LEDGER_CATCH_UP, start, columns);
        }
    }

    /** 1 tick ぶん書き換える。戻り値は使った予算(列の数)。 */
    private static int work(ServerLevel level, SpreadTable table) {
        int budget = table.columnsPerTick();
        LedgerSpreadData data = LedgerSpreadData.get(level.getServer());
        while (budget > 0 && !QUEUE.isEmpty() && (budget == table.columnsPerTick() || TickBudget.timeLeft())) {
            long key = QUEUE.removeFirstLong();
            ChunkPos pos = new ChunkPos(key);
            if (!data.isPending(key) || !neighbourhoodLoaded(level, pos.x, pos.z)) {
                // 読み込まれていない(周り 8 チャンクのどれかも含む): 待ちに残す。自分か隣が読み込まれたらまた並ぶ
                // (LedgerSpread#drainLoaded)。
                continue;
            }
            convertChunk(level, data, pos);
            budget -= COST_PER_CHUNK;
        }
        return table.columnsPerTick() - budget;
    }

    /**
     * 書き換え待ちの 1 チャンクを書き換え、待ちから外す(ゲームの中とテストの共通の道)。チャンクと周りが読み込まれていること。
     * 戻り値: 書き換えた = true、除去装置の範囲なので外しただけ = false。
     */
    static boolean convertChunk(ServerLevel level, LedgerSpreadData data, ChunkPos pos) {
        long key = pos.toLong();
        data.removePending(key);
        if (SporeDeviceData.guardsChunkFromLedger(level, pos.x, pos.z)) {
            return false;
        }
        SporeFieldServer.Cell fieldCell = SporeFieldServer.enabled() ? SporeFieldServer.cell(pos.x, pos.z) : null;
        if (fieldCell != null && fieldCell.stage() == 1) {
            // 発芽して 2 日未満は繁茂の札(2 日を過ぎたら DepositBiomeSweep が過繁茂へ。相談セッションの答え 2)
            OvergrownBiomeConverter.maybeConvertToOvergrowth(level, pos);
        } else {
            OvergrownBiomeConverter.forceOvergrown(level, pos);
        }
        // 胞子の場(2026-10-23 第 2 段 E): 地表を塗らず、点から根のように広がる芽と萌芽体に任せる
        SporeSprouts.start(level, pos);
        return true;
    }

    /**
     * 自分と周り 8 チャンクが読み込み済み(FULL)か(2026-10-06)。芽と萌芽体はチャンクの縁から隣へはみ出すので、
     * 隣が読み込みの途中だとサーバースレッドが隣の読み込み・生成を待って止まる(計測でサーバースレッドの半分、1 tick 最大 1.2 秒)。
     */
    static boolean neighbourhoodLoaded(ServerLevel level, int cx, int cz) {
        return LoadedChunks.neighbourhoodLoaded(level, cx, cz);
    }

    /** テスト用: 1 チャンクを書き換え待ちに載せ、ゲームと同じ道({@link #convertChunk})でその場で書き換える。 */
    static boolean convertNow(ServerLevel level, ChunkPos pos) {
        LedgerSpreadData data = LedgerSpreadData.get(level.getServer());
        data.addPending(pos.toLong());
        return convertChunk(level, data, pos);
    }

    private LedgerSpreadCatchUp() {
    }
}
