package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.CoverTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
 * <b>覆った砂・砂利・砂岩は、時間がたつと過繁茂の草ブロックになる</b>(2026-11-01 ユーザー指定。砂岩は 11-02 ユーザー指定で追加)。
 *
 * <ul>
 *     <li>砂({@code #minecraft:sand}: 砂・赤い砂)・砂利・砂岩(自然に出る {@code sandstone}・{@code red_sandstone}。加工品の cut・chiseled・smooth は入れない = 仮決め)が覆われたとき({@link OvergrownLayerData#cover})、{@code cover.json} の
 *     {@code settleMinSeconds}〜{@code settleMaxSeconds}(仮決め 300〜900 秒 = 5〜15 分)の乱数だけ先の時刻をチャンクの記録
 *     ({@link ModAttachments#COVER_SETTLE})に書く。剥がしたら消す</li>
 *     <li>1 秒おきに、記録のある読み込まれたチャンクを見て、時刻が来たマスを {@code overgrown_grass_block} に置き換え、覆いの記録を消す。
 *     上が空いている(空気・水・動きを止めないもの = 草・海草など。仮決め)ときだけ。ふさがっていれば空くまで待つ。水中でも同じ草ブロック</li>
 *     <li>読み込まれていない間は進まないが、時刻はゲーム時刻なので、戻ってきたら過ぎた分はすぐ置き換わる</li>
 *     <li>この仕組みより前に覆った砂・砂利(時刻が無い)は、チャンクが読み込まれたときに同じ乱数の時刻を書き足す({@link #backfill})</li>
 * </ul>
 * 覆った砂・砂利は落ちない({@code mixin/FallingBlockCoveredMixin})ので、待っている間に記録と砂が離れることはない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class CoveredSandToGrass {

    private static final int SWEEP_TICKS = 20;
    /** 記録を持つ読み込まれたチャンク(次元ごと)。サーバーのスレッドだけが触る。 */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> CHUNKS = new HashMap<>();

    private CoveredSandToGrass() {
    }

    /** 時間がたつと草ブロックになるブロックか(砂・赤い砂・砂利・砂岩・赤い砂岩)。 */
    public static boolean settles(BlockState state) {
        return state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE);
    }

    /** そのマスが覆われた({@link OvergrownLayerData#cover} から)。砂・砂利なら置き換わる時刻を書く。 */
    static void onCovered(ServerLevel level, BlockPos pos) {
        if (!settles(level.getBlockState(pos))) {
            return;
        }
        CoverTable design = DesignData.cover();
        double seconds = design.settleMinSeconds() + level.random.nextDouble() * (design.settleMaxSeconds() - design.settleMinSeconds());
        schedule(level, pos, level.getGameTime() + Math.round(seconds * 20.0D));
    }

    /** 置き換わる時刻を書く(テストからも呼ぶ)。 */
    public static void schedule(ServerLevel level, BlockPos pos, long dueTick) {
        LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        OvAttach.getData(chunk, ModAttachments.COVER_SETTLE.get()).put(pos.asLong(), dueTick);
        chunk.setUnsaved(true);
        CHUNKS.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet()).add(chunk.getPos().toLong());
    }

    /** そのマスの記録(無ければ {@link Long#MIN_VALUE})。 */
    public static long dueTick(Level level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        Long2LongOpenHashMap map = chunk == null ? null : OvAttach.getExistingData(chunk, ModAttachments.COVER_SETTLE.get()).orElse(null);
        return map == null ? Long.MIN_VALUE : map.getOrDefault(pos.asLong(), Long.MIN_VALUE);
    }

    /** そのマスの覆いが剥がされた({@link OvergrownLayerData#uncover} から)。 */
    static void onUncovered(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        Long2LongOpenHashMap map = chunk == null ? null : OvAttach.getExistingData(chunk, ModAttachments.COVER_SETTLE.get()).orElse(null);
        if (map != null && map.remove(pos.asLong()) != map.defaultReturnValue()) {
            chunk.setUnsaved(true);
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (OvAttach.getExistingData(chunk, ModAttachments.COVER_SETTLE.get()).map(m -> !m.isEmpty()).orElse(false)) {
            CHUNKS.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet()).add(chunk.getPos().toLong());
        }
        if (OvergrownLayerData.size(chunk) > 0) {
            // この仕組みより前に覆った砂・砂利には時刻が無い。読み込みの中では触らず、tick で書く(backfill)。
            BACKFILL.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet()).add(chunk.getPos().toLong());
        }
    }

    /** 時刻を書き足すチャンク(次元ごと。この仕組み(11-01)より前に覆った砂・砂利の分)。 */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> BACKFILL = new HashMap<>();
    private static final int BACKFILL_PER_TICK = 4;

    /** 覆いの記録はあるが時刻の無い砂・砂利に時刻を書く(1 tick に {@value #BACKFILL_PER_TICK} チャンクまで)。 */
    private static void backfill(ServerLevel level) {
        LongOpenHashSet queue = BACKFILL.get(level.dimension());
        if (queue == null || queue.isEmpty()) {
            return;
        }
        CoverTable design = DesignData.cover();
        long now = level.getGameTime();
        for (int n = 0; n < BACKFILL_PER_TICK && !queue.isEmpty(); n++) {
            long cp = queue.iterator().nextLong();
            queue.remove(cp);
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(cp), ChunkPos.getZ(cp));
            LongOpenHashSet covered = chunk == null ? null : OvAttach.getExistingData(chunk, ModAttachments.OVERGROWN_LAYER.get()).orElse(null);
            if (covered == null || covered.isEmpty()) {
                continue;
            }
            Long2LongOpenHashMap settle = null;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (long packed : covered.toLongArray()) {
                if (!settles(chunk.getBlockState(pos.set(packed)))) {
                    continue;
                }
                if (settle == null) {
                    settle = OvAttach.getData(chunk, ModAttachments.COVER_SETTLE.get());
                }
                if (!settle.containsKey(packed)) {
                    double seconds = design.settleMinSeconds() + level.random.nextDouble() * (design.settleMaxSeconds() - design.settleMinSeconds());
                    settle.put(packed, now + Math.round(seconds * 20.0D));
                    chunk.setUnsaved(true);
                }
            }
            if (settle != null && !settle.isEmpty()) {
                CHUNKS.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet()).add(cp);
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LongOpenHashSet set = CHUNKS.get(level.dimension());
            if (set != null) {
                set.remove(event.getChunk().getPos().toLong());
            }
            LongOpenHashSet queue = BACKFILL.get(level.dimension());
            if (queue != null) {
                queue.remove(event.getChunk().getPos().toLong());
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || CorruptionSwitch.isOff()) {
            return;
        }
        backfill(level);
        if (level.getGameTime() % SWEEP_TICKS == 0) {
            sweep(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CHUNKS.clear();
        BACKFILL.clear();
    }

    /**
     * 時刻が来たマスを草ブロックへ置き換える(1 秒おき。テストからも呼ぶ)。
     *
     * @return 置き換えた数
     */
    public static int sweep(ServerLevel level) {
        LongOpenHashSet set = CHUNKS.get(level.dimension());
        if (set == null || set.isEmpty() || CorruptionSwitch.isOff()) {
            return 0;
        }
        long now = level.getGameTime();
        int converted = 0;
        for (long cp : set.toLongArray()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(cp), ChunkPos.getZ(cp));
            Long2LongOpenHashMap map = chunk == null ? null : OvAttach.getExistingData(chunk, ModAttachments.COVER_SETTLE.get()).orElse(null);
            if (map == null || map.isEmpty()) {
                set.remove(cp);
                continue;
            }
            LongArrayList due = new LongArrayList();
            for (var e : map.long2LongEntrySet()) {
                if (e.getLongValue() <= now) {
                    due.add(e.getLongKey());
                }
            }
            for (long packed : due) {
                BlockPos pos = BlockPos.of(packed);
                BlockState state = level.getBlockState(pos);
                if (!settles(state) || !OvergrownLayerData.isCovered(level, pos)) {
                    map.remove(packed); // 壊された・置き換わった・剥がされた
                    chunk.setUnsaved(true);
                    continue;
                }
                if (!openAbove(level, pos.above())) {
                    continue; // ふさがっている間は待つ
                }
                map.remove(packed);
                chunk.setUnsaved(true);
                OvergrownLayerData.uncover(level, pos); // 覆いの記録を消してクライアントへも知らせる
                level.setBlock(pos, ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
                converted++;
            }
        }
        return converted;
    }

    /** 上が空いているか(空気・水・動きを止めないもの)。仮決め。 */
    private static boolean openAbove(ServerLevel level, BlockPos above) {
        BlockState s = level.getBlockState(above);
        return s.isAir() || !s.blocksMotion() || s.getFluidState().is(FluidTags.WATER) && !s.isCollisionShapeFullBlock(level, above);
    }
}
