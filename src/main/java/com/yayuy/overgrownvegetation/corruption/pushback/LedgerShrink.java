package com.yayuy.overgrownvegetation.corruption.pushback;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LedgerSpreadData;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>森の面積が減る</b>(押し返し 2026-11-18 夜 ユーザー決定「チャンクの過繁茂の割合が 3 割【仮】を切ったら台帳から外し、面積の点を減らす。
 * 胞子でまた増えたら戻す」)。
 *
 * <ul>
 *   <li><b>見直すのは汚染を取り除いたチャンクだけ</b>({@link #touch})。台帳に載る道は胞子の発芽で、そのとき地表は塗らない
 *   (芽の点が根のように広げる)ので、全チャンクを見回すと発芽したての森が次々に外れる。焼く・薬・汚染を掘る・爆発で壊すときに印を付ける。</li>
 *   <li><b>猶予</b>: 台帳に載ってから {@code graceDays}【仮 1 日】は外さない(印は猶予が明けるまで持ち越す)。</li>
 *   <li><b>地面の割合</b>({@link #groundShare}): 葉・幹・草花(動きを止めない物)をくぐった地面のマスが汚染(または苔の覆いの記録)の列の割合。
 *   水の下の列は分母から外す。バイオームの書き換え(高さ地図の最上部 = 葉も数える)とは物差しを変えた —— 林冠の下を焼いても割合が動くように。</li>
 *   <li><b>外す</b>({@link #forget}): 台帳から外し(面積の点の率が下がる。貯まった点は戻さない)・胞子の場の汚染を取り消し(降り積もりも 0)・
 *   萌芽体の数の記録を 0 に戻し(また発芽したら萌芽体が出る)・バイオームを世界生成のときへ戻す。</li>
 *   <li><b>戻る</b>: 胞子が溜まり直して発芽すれば、今までどおり台帳に載る({@code LedgerSpread#acceptFieldSprouts})。
 *   地面の汚染がバイオームの書き換えのしきい値(5 割)まで戻っても載る。</li>
 * </ul>
 * オーバーワールドだけ(台帳のキーは次元を持たない)。重さ: 印の付いたチャンクだけを 1 tick {@code chunksPerTick} 個まで、TickBudget の枠で。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LedgerShrink {

    /** 何で外したか(焼けた荒地にするかの分かれ目)。 */
    public enum Cause { REMOVED, FIRE }

    /** 外したときに知らせる先(焼けた荒地など)。サーバースレッド。 */
    public interface Listener {
        /** @return true ならバイオームは呼ばれた側が決めた(世界生成のときへ戻さない) */
        boolean onForgotten(ServerLevel level, LevelChunk chunk, Cause cause);
    }

    private static final List<Listener> LISTENERS = new ArrayList<>();
    /** 印の付いたチャンク → 次に数える時刻(オーバーワールドの gameTime)。 */
    private static final Long2LongOpenHashMap DUE = new Long2LongOpenHashMap();
    /** 印 → 外れたときの理由(火が混ざったら火)。 */
    private static final Long2LongOpenHashMap CAUSE = new Long2LongOpenHashMap();
    /** 最初の印から数えるまでの間(tick。続けて焼いている間は数えない)。 */
    private static final int SETTLE_TICKS = 20;
    /** 1 tick に印の表を見る数の上限(表が大きくなっても 1 tick を食わない)。 */
    private static final int SCAN_CAP = 512;

    /** 確かめ用: 外した数。 */
    public static int forgottenCount;

    private LedgerShrink() {
    }

    public static PushBackTable.Shrink table() {
        return DesignData.pushBack().shrink;
    }

    public static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    /** そのマスの汚染を取り除いた(焼いた・枯らした・掘った)。台帳に載っているチャンクなら数え直しの印を付ける。 */
    public static void touch(Level level, BlockPos pos, Cause cause) {
        if (!(level instanceof ServerLevel server) || server.dimension() != Level.OVERWORLD) return;
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (!PhaseData.get(server).isOvergrownKey(key)) return;
        if (!DUE.containsKey(key)) DUE.put(key, server.getGameTime() + SETTLE_TICKS);
        if (cause == Cause.FIRE) CAUSE.put(key, 1L);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        if (OvergrownLayerData.isCorruptedCell(level, event.getPos(), event.getState())) touch(level, event.getPos(), Cause.REMOVED);
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        long last = Long.MIN_VALUE;
        for (BlockPos p : event.getAffectedBlocks()) {
            long key = ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
            if (key == last) continue;
            if (OvergrownLayerData.isCorruptedCell(level, p, level.getBlockState(p))) {
                touch(level, p, Cause.REMOVED);
                last = key;
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DUE.clear();
        CAUSE.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (DUE.isEmpty() || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!TickBudget.mayStart(PerfJob.LEDGER_SHRINK)) return;
        long start = TickBudget.begin();
        int done = 0;
        try {
            PushBackTable.Shrink t = table();
            long now = level.getGameTime();
            PhaseData phase = PhaseData.get(level);
            int scanned = 0;
            List<long[]> later = new ArrayList<>();
            for (ObjectIterator<Long2LongMap.Entry> it = DUE.long2LongEntrySet().fastIterator(); it.hasNext() && scanned < SCAN_CAP; scanned++) {
                Long2LongMap.Entry e = it.next();
                if (e.getLongValue() > now) continue;
                if (done >= t.chunksPerTick || done > 0 && !TickBudget.timeLeft()) break;
                long key = e.getLongKey();
                it.remove();
                Cause cause = CAUSE.remove(key) != 0L ? Cause.FIRE : Cause.REMOVED;
                long retry = check(level, phase, key, cause, t);
                if (retry > 0) later.add(new long[]{key, now + retry, cause == Cause.FIRE ? 1 : 0});
                done++;
            }
            for (long[] l : later) {
                DUE.put(l[0], l[1]);
                if (l[2] != 0) CAUSE.put(l[0], 1L);
            }
        } finally {
            TickBudget.end(PerfJob.LEDGER_SHRINK, start, done);
        }
    }

    /** 1 チャンクを数え直す。猶予の中なら、あと何 tick で数え直すかを返す(それ以外は 0)。 */
    private static long check(ServerLevel level, PhaseData phase, long key, Cause cause, PushBackTable.Shrink t) {
        if (!phase.isOvergrownKey(key)) return 0;
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
        if (chunk == null) return 0; // 読み込まれていない(読み込んでまで数えない。また触れられたら数える)
        long since = phase.overgrownSinceTick(chunk.getPos());
        long age = phase.clock(level.getServer()) - since;
        long grace = (long) (t.graceDays * 24000L);
        if (since >= 0 && age < grace) return Math.max(t.recheckSeconds * 20L, grace - age);
        double share = groundShare(chunk, t.minColumns);
        if (share >= 0 && share < t.threshold) forget(level, chunk, cause);
        return 0;
    }

    /**
     * 地面の汚染の割合(0〜1)。数えられる列が {@code minColumns} より少なければ -1。
     * 列ごとに、葉を除いた高さ地図の上から、葉・幹・草花(動きを止めない物)をくぐって最初の地面のマスを見る(下へ 48 マスまで)。
     */
    public static double groundShare(LevelChunk chunk, int minColumns) {
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        int minY = chunk.getMinBuildHeight();
        int counted = 0, corrupted = 0;
        int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                boolean water = false;
                BlockState ground = null;
                for (int i = 0; i < 48 && y > minY; i++, y--) {
                    c.set(bx + x, y, bz + z);
                    BlockState s = chunk.getBlockState(c);
                    if (s.getFluidState().is(FluidTags.WATER)) { water = true; break; }
                    if (s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || !s.blocksMotion()) continue;
                    ground = s;
                    break;
                }
                if (water || ground == null) continue;
                counted++;
                if (OvergrownLayerData.isCorruptedCell(chunk, c, ground)) corrupted++;
            }
        }
        return counted < minColumns ? -1 : corrupted / (double) counted;
    }

    /** 台帳から外す(テスト・コマンドからも)。外したら true。 */
    public static boolean forget(ServerLevel level, LevelChunk chunk, Cause cause) {
        PhaseData phase = PhaseData.get(level);
        ChunkPos pos = chunk.getPos();
        if (!phase.forgetOvergrownChunk(pos)) return false; // 胞子の場の取り消しもここで
        LedgerSpreadData.get(level.getServer()).removePending(pos.toLong());
        chunk.setData(ModAttachments.SPROUTS_PLACED, 0);
        chunk.setData(ModAttachments.CAVE_SPROUTS_PLACED, 0);
        boolean handled = false;
        for (Listener l : LISTENERS) handled |= l.onForgotten(level, chunk, cause);
        if (!handled) OvergrownBiomeConverter.restoreOriginal(level, chunk);
        forgottenCount++;
        OvergrownVegetation.LOGGER.debug("押し返し: チャンク {} を台帳から外した({})", pos, cause);
        return true;
    }

    /** テスト用: 印を消す。 */
    public static void clearForTests() {
        DUE.clear();
        CAUSE.clear();
    }

    /** テスト用: 今すぐ数え直す(猶予も見る)。外したら true。 */
    public static boolean checkNowForTests(ServerLevel level, ChunkPos pos, Cause cause) {
        PhaseData phase = PhaseData.get(level);
        boolean before = phase.isOvergrownKey(pos.toLong());
        check(level, phase, pos.toLong(), cause, table());
        return before && !phase.isOvergrownKey(pos.toLong());
    }
}
