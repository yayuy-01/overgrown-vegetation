package com.yayuy.overgrownvegetation.worldgen;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedSaplingBlock;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * 読み込まれていない場所の汚染された苗木も育つようにする「追いつき成長」(2026-09-11)。
 *
 * <h2>仕組み</h2>
 * <ol>
 *     <li>苗木が置かれたとき({@link CorruptedSaplingBlock#onPlace}、汚染の拡散で置かれた場合も含む)に、
 *         そのチャンクの添付データ {@link SaplingRecords} へ「座標 → 設置時のゲーム内時刻」を記録する。
 *         苗木が壊れた/育ったとき({@code onRemove})に記録を消す。この機能より前から生えていた苗木は
 *         ランダムティックを受けたときに記録される({@link #ensureRecorded})。</li>
 *     <li>チャンクが読み込まれたとき({@link ChunkEvent.Load})、記録を舐めて「設置から
 *         {@code saplingOfflineGrowthTicks}(既定 4800 = 4 分)以上経った苗木」をキューに積む。
 *         読み込みイベントの最中にブロックは触らない(隣のまだ読み込まれていないチャンクへ木の葉を書くと
 *         そのチャンクを引っ張り込んで詰まる)。</li>
 *     <li>次のサーバー tick から、1 tick あたり {@code saplingOfflineGrowthPerTick}(既定 2)本ずつ、
 *         まだ苗木であることを確認してから育てる({@link CorruptedSaplingBlock#growNow}: STAGE を飛ばして最終成長)。
 *         長時間放置した拠点に戻っても数十本が同じ tick に育つことはない。</li>
 * </ol>
 * 読み込み中の苗木は従来どおりランダムティックで育つ(こちらの負荷は変わらない)。未読み込み中は何もしない。
 * ワールド時間はチャンクが読み込まれていなくても進むので、tick を数える必要が無く、読み込んだ瞬間の
 * 引き算だけで経過時間が分かる。
 *
 * <p>閾値は「設置からの経過時間」なので、読み込んだままランダムティックで育つ前に一度アンロードされ、
 * 再読み込み時に閾値を超えていればそこで育つ(=どちらか早い方)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class SaplingOfflineGrowth {

    /** 育てる予定の苗木(次元 + 座標)。読み込みイベントで積み、tick で消化する。 */
    private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();
    /** キューが際限なく膨らまないための上限(超えた分は次回の読み込みで拾い直す)。 */
    private static final int MAX_QUEUE = 4096;

    private record Pending(ServerLevel level, long pos) {
    }

    /** {@code /ov perf} の表示用: 育てる順番を待っている苗木の数。 */
    public static int queued() {
        return QUEUE.size();
    }

    private SaplingOfflineGrowth() {
    }

    private static boolean enabled() {
        return OvergrownVegetationConfig.COMMON.saplingOfflineGrowth.get();
    }

    // ------------------------------------------------------------------
    // 記録
    // ------------------------------------------------------------------

    /** 苗木が置かれた。設置時刻を記録する(既に記録があれば触らない)。 */
    public static void record(ServerLevel level, BlockPos pos) {
        if (!enabled()) {
            return;
        }
        LevelChunk chunk = level.getChunkAt(pos);
        SaplingRecords records = OvAttach.getData(chunk, ModAttachments.SAPLING_PLANTED);
        if (records.recordIfAbsent(pos, level.getGameTime())) {
            chunk.setUnsaved(true);
        }
    }

    /** この機能より前から生えていた苗木用: 記録が無ければ「今」を設置時刻として記録する。 */
    public static void ensureRecorded(ServerLevel level, BlockPos pos) {
        if (!enabled()) {
            return;
        }
        LevelChunk chunk = level.getChunkAt(pos);
        if (OvAttach.hasData(chunk, ModAttachments.SAPLING_PLANTED) && OvAttach.getData(chunk, ModAttachments.SAPLING_PLANTED).contains(pos)) {
            return;
        }
        record(level, pos);
    }

    /** 苗木が壊れた/育った。記録を消す。 */
    public static void forget(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkAt(pos);
        if (!OvAttach.hasData(chunk, ModAttachments.SAPLING_PLANTED)) {
            return;
        }
        SaplingRecords records = OvAttach.getData(chunk, ModAttachments.SAPLING_PLANTED);
        if (records.forget(pos)) {
            if (records.isEmpty()) {
                OvAttach.removeData(chunk, ModAttachments.SAPLING_PLANTED);
            }
            chunk.setUnsaved(true);
        }
    }

    // ------------------------------------------------------------------
    // チャンク読み込み → キュー
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.isNewChunk() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkAccess chunk = event.getChunk();
        if (!(chunk instanceof LevelChunk levelChunk) || !OvAttach.hasData(levelChunk, ModAttachments.SAPLING_PLANTED) || !enabled()) {
            return;
        }
        SaplingRecords records = OvAttach.getData(levelChunk, ModAttachments.SAPLING_PLANTED);
        if (records.isEmpty()) {
            return;
        }
        long threshold = OvergrownVegetationConfig.COMMON.saplingOfflineGrowthTicks.get();
        long now = level.getGameTime();
        List<Long> due = null;
        for (Long2LongMap.Entry e : records.entries()) {
            if (now - e.getLongValue() >= threshold) {
                if (due == null) {
                    due = new ArrayList<>();
                }
                due.add(e.getLongKey());
            }
        }
        if (due == null) {
            return;
        }
        for (long packed : due) {
            if (QUEUE.size() >= MAX_QUEUE) {
                break;
            }
            QUEUE.add(new Pending(level, packed));
        }
    }

    // ------------------------------------------------------------------
    // tick → 少しずつ育てる
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (QUEUE.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // サーバーが重いときは予算の番人が次の tick へ回す(2026-10-06。結果は同じで遅れるだけ)。
        if (!TickBudget.mayStart(PerfJob.SAPLING_OFFLINE)) {
            return;
        }
        long start = TickBudget.begin();
        int perTick = OvergrownVegetationConfig.COMMON.saplingOfflineGrowthPerTick.get();
        int left = perTick;
        try {
            left = grow(level, perTick);
        } finally {
            TickBudget.end(PerfJob.SAPLING_OFFLINE, start, perTick - left);
        }
    }

    /** 1 tick ぶん育てる。戻り値は使い残した本数。 */
    private static int grow(ServerLevel level, int perTick) {
        int budget = perTick;
        int scanned = 0;
        int size = QUEUE.size();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (budget > 0 && scanned < size && !QUEUE.isEmpty()) {
            Pending pending = QUEUE.poll();
            scanned++;
            if (pending.level() != level) {
                // 別の次元のぶんは後ろへ回す(その次元の tick で処理する)。
                QUEUE.add(pending);
                continue;
            }
            pos.set(pending.pos());
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            if (!LoadedChunks.loaded(level, cx, cz)) {
                // もうアンロードされている。記録は残っているので次の読み込みで拾い直す。
                continue;
            }
            if (!LoadedChunks.neighbourhoodLoaded(level, cx, cz)) {
                // 木は UPDATE_ALL で置くので、葉・枝が隣のチャンクへかかる。隣が読み込みの途中(読み込み範囲の縁)だと
                // サーバースレッドがその読み込み・生成を待って止まる(2026-10-06 の計測で見つかった)。後ろへ回して待つ。
                QUEUE.add(pending);
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof CorruptedSaplingBlock sapling)) {
                // 苗木ではなくなっていた(壊された/既に育った)。記録だけ掃除する。
                forget(level, pos.immutable());
                continue;
            }
            sapling.growNow(level, pos.immutable(), state, level.random);
            budget--;
            if (budget < perTick && !TickBudget.timeLeft()) {
                break;
            }
        }
        return budget;
    }

    /**
     * 次元が閉じられた(ワールドを抜けた/サーバー停止)。その次元のぶんは二度と tick されず消化できないので捨てる。
     * 残すと旧 {@link ServerLevel} を掴んだままになり、次のワールドの tick で毎回回され続けるうえ
     * {@link #MAX_QUEUE} の枠も埋める。記録({@link SaplingRecords})はチャンクに残るので、次の読み込みで拾い直す。
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            QUEUE.removeIf(p -> p.level() == serverLevel);
        }
    }
}
