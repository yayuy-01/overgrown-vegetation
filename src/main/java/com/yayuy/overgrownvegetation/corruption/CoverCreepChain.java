package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;

import java.util.HashMap;
import java.util.Map;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * <b>這い上がる苔が最終段を過ぎたら覆いにする</b>(2026-11-03 ユーザー指定)。記録({@link ModAttachments#COVER_CREEP}、{@link CoverCreepData})の
 * 時刻から {@code cover.json} の {@code creepSeconds}(120 秒 = 1 段 15 秒)が過ぎたマスを {@link OvergrownLayerData#cover} で覆う。
 * 覆うと {@link CoverCreepData#spreadFrom} が上と横の建築ブロックに新しい記録を付けるので、建物は接した所から波のように 1 マスずつ覆われていく。
 *
 * <ul>
 *     <li>覆いにできないブロック({@link OvergrownLayerData#canCover} が false。プレイヤーが覆いの横に置いた土など)は最終段で止めたまま(新しい絵は無い)</li>
 *     <li>汚染してよくない間({@link CorruptionRegistry#canCorrupt} が false: 胞子除去装置の範囲・水没)は待ち、{@value #RETRY_TICKS} tick ごとに見直す(仮決め)</li>
 *     <li>{@code /ov corruption false} の間は進めない(記録も増えない)。オンに戻すと過ぎた分から続く</li>
 *     <li>読み込まれていないチャンクは進まない。時刻はゲーム時刻なので、読み込まれたら過ぎた分はすぐ覆われる</li>
 * </ul>
 *
 * <p>重さ(仮決め): 1 秒おきに、<b>次に見る時刻が来たチャンクだけ</b>記録を舐める(チャンクごとに、記録のうちいちばん早く尽きる時刻を持つ)。
 * 村 1 つで記録が数千あっても、毎秒舐めるのは段階が尽きたものがあるチャンクだけ。1 回に覆うのは {@value #COVERS_PER_SWEEP} マスまで
 * (残りは次の秒へ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class CoverCreepChain {

    private static final int SWEEP_TICKS = 20;
    /** 1 回(1 秒)に覆うマスの上限(仮決め)。 */
    static final int COVERS_PER_SWEEP = 256;
    /** 汚染してよくないので待っているマスを見直す間隔(仮決め)。 */
    static final long RETRY_TICKS = 1200L;
    /** 次元 → チャンク → 次に記録を舐める時刻(ゲーム時刻)。サーバーのスレッドだけが触る。 */
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> NEXT = new HashMap<>();

    private CoverCreepChain() {
    }

    /** 段階 1 から覆いになるまでの tick({@code creepSeconds})。 */
    public static long durationTicks() {
        return Math.round(DesignData.cover().creepSeconds() * 20.0D);
    }

    /** そのチャンクを {@code dueTick} までに見る(もっと早い予定があればそちら)。 */
    static void track(ServerLevel level, long chunkKey, long dueTick) {
        Long2LongOpenHashMap next = NEXT.computeIfAbsent(level.dimension(), k -> new Long2LongOpenHashMap());
        if (!next.containsKey(chunkKey) || next.get(chunkKey) > dueTick) {
            next.put(chunkKey, dueTick);
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk
                && OvAttach.getExistingData(chunk, ModAttachments.COVER_CREEP.get()).map(m -> !m.isEmpty()).orElse(false)) {
            // 読み込みの中では記録を舐めない。次の回にまとめて見る(本当の次の時刻はそこで決まる)
            track(level, chunk.getPos().toLong(), Long.MIN_VALUE);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Long2LongOpenHashMap next = NEXT.get(level.dimension());
            if (next != null) {
                next.remove(event.getChunk().getPos().toLong());
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        NEXT.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || CorruptionSwitch.isOff() || level.getGameTime() % SWEEP_TICKS != 0) {
            return;
        }
        Long2LongOpenHashMap next = NEXT.get(level.dimension());
        if (next == null || next.isEmpty()) {
            return; // 仕事が無い秒は番人に聞かない(「止めた」と数えないため)
        }
        // サーバーが重いときは予算の番人が次の秒へ回す(2026-11-03。遅れるだけで結果は同じ)
        if (!com.yayuy.overgrownvegetation.perf.TickBudget.mayStart(com.yayuy.overgrownvegetation.perf.PerfJob.COVER_CREEP)) {
            return;
        }
        long start = com.yayuy.overgrownvegetation.perf.TickBudget.begin();
        int covered = 0;
        try {
            covered = sweep(level);
        } finally {
            com.yayuy.overgrownvegetation.perf.TickBudget.end(com.yayuy.overgrownvegetation.perf.PerfJob.COVER_CREEP, start, Math.max(1, covered));
        }
    }

    /**
     * 段階が尽きたマスを覆う(1 秒おき。テストからも呼ぶ)。
     *
     * @return 覆った数
     */
    public static int sweep(ServerLevel level) {
        Long2LongOpenHashMap next = NEXT.get(level.dimension());
        if (next == null || next.isEmpty() || CorruptionSwitch.isOff()) {
            return 0;
        }
        long now = level.getGameTime();
        long duration = durationTicks();
        LongArrayList dueChunks = new LongArrayList();
        for (var e : next.long2LongEntrySet()) {
            if (e.getLongValue() <= now) {
                dueChunks.add(e.getLongKey());
            }
        }
        int budget = COVERS_PER_SWEEP;
        int covered = 0;
        for (long cp : dueChunks) {
            if (budget <= 0) {
                break; // 残りのチャンクは次の秒(予定は今のまま)
            }
            next.remove(cp); // 覆うと同じチャンクに新しい記録が付き track が呼ばれる。その予定と下の予定の早い方が残る
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(cp), ChunkPos.getZ(cp));
            Long2LongOpenHashMap map = chunk == null ? null : OvAttach.getExistingData(chunk, ModAttachments.COVER_CREEP.get()).orElse(null);
            if (map == null || map.isEmpty()) {
                continue;
            }
            long earliest = Long.MAX_VALUE;
            LongArrayList ready = new LongArrayList();
            for (var e : map.long2LongEntrySet()) {
                long due = CoverCreepData.startTick(e.getLongValue()) + duration;
                if (due <= now) {
                    ready.add(e.getLongKey());
                } else {
                    earliest = Math.min(earliest, due);
                }
            }
            boolean waiting = false;
            for (int i = 0; i < ready.size(); i++) {
                if (budget <= 0) {
                    earliest = now; // 上限。このチャンクの残りは次の秒
                    break;
                }
                BlockPos pos = BlockPos.of(ready.getLong(i));
                BlockState state = chunk.getBlockState(pos);
                if (!CoverCreepData.eligible(state) || OvergrownLayerData.isCovered(chunk, pos, state)) {
                    CoverCreepData.remove(level, pos); // 壊された・置き換わった・もう覆われている
                    continue;
                }
                if (!OvergrownLayerData.canCover(state)) {
                    continue; // 覆いにできない: 最終段で止めたまま(もう見ない)
                }
                if (!CorruptionRegistry.canCorrupt(level, pos, state)) {
                    waiting = true; // 胞子除去装置の範囲・水没など。しばらくして見直す
                    continue;
                }
                if (OvergrownLayerData.cover(level, pos)) { // 記録は onCovered が消し、隣へ連鎖させる
                    CorruptionSpreader.growPlantsIfNewlyCovered(level, pos, state); // 覆っただけのマスにも植物(ほかの覆いの経路と同じ)
                    covered++;
                    budget--;
                }
            }
            if (waiting) {
                earliest = Math.min(earliest, now + RETRY_TICKS);
            }
            if (earliest != Long.MAX_VALUE) {
                track(level, cp, earliest);
            }
        }
        return covered;
    }
}
