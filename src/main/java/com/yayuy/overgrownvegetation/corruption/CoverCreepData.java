package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.CoverCreepPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>這い上がる苔の記録</b>(案 H の 28、2026-11-01 ユーザー指定)。帯が出るのは<b>覆われたマスに接して新しく置かれた</b>ブロックだけ
 * (もともと世界にあって覆いに接しているだけの地形・古い建物には出さない。「次に覆われる所が見える」のは置いた物に限る)。
 *
 * <ul>
 *     <li>置いたとき({@link BlockEvent.EntityPlaceEvent}: プレイヤー・Mob・ディスペンサー)、置かれたブロックが不透明で、下か横 4 つに覆われたマスか汚染ブロック({@link #source}、11-01 から)があれば、
 *     チャンクの記録({@link ModAttachments#COVER_CREEP})に「置いた時刻 + そのとき覆われていた向き」を書く。後から隣が覆われても足さない(仮決め)</li>
 *     <li>消すのは、そのマスが覆われたとき({@link OvergrownLayerData#cover} から)・壊された / 置き換わったとき(そのマスの更新の知らせ
 *     {@link BlockEvent.NeighborNotifyEvent} で、もう帯を描く資格が無ければ)</li>
 *     <li>送り方は覆いの記録と同じ(見始めたらまるごと・増減は tick の終わりにまとめて。{@link CoverCreepPayload})。クライアントは置いた時刻からの経過で段階を決める
 *     (入り直しても途中から続く)</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CoverCreepData {

    /** 送り残し(次元 → チャンク → マス → 値。値が {@link #REMOVED_MARK} なら消す)。サーバーのスレッドだけが触る。 */
    private static final Map<ResourceKey<Level>, Map<ChunkPos, Long2LongOpenHashMap>> PENDING = new HashMap<>();
    private static final long REMOVED_MARK = Long.MIN_VALUE;

    private CoverCreepData() {
    }

    /** 値から置いた時刻(ゲーム時刻)。 */
    public static long startTick(long value) {
        return value >> 6;
    }

    /** 値から、置いたときに覆われていた向きの印(ビット = {@link Direction#ordinal()})。 */
    public static int sides(long value) {
        return (int) (value & 63);
    }

    /** 帯を描く資格のあるブロックか(不透明な模型。覆われてもいない・汚染済みでもない)。 */
    public static boolean eligible(BlockState state) {
        return !state.isAir() && state.getRenderShape() == RenderShape.MODEL && state.canOcclude() && !state.is(ModTags.CORRUPTED);
    }

    /**
     * 苔が這い上がってくる元になる汚染ブロックか(2026-11-01 ユーザー指定「覆われたブロック = 植物に汚染されたブロック全般」)。
     * {@code #corrupted} のうち不透明なもの(置き換えブロック・overgrown_block・幹)。葉・草などの非固体は元にしない(仮決め)。
     * 記録で覆ったマスは {@code #corrupted} を持たないので、呼び手は {@link OvergrownLayerData#isCovered} と両方を見る。
     */
    public static boolean source(BlockState state) {
        return state.is(ModTags.CORRUPTED) && state.canOcclude();
    }

    @SubscribeEvent
    public static void onPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            placed(level, event.getPos(), event.getPlacedBlock());
        }
    }

    /**
     * ブロックが置かれた(テストからも呼ぶ)。覆われた隣があれば記録する。
     *
     * @return 記録したら true
     */
    public static boolean placed(ServerLevel level, BlockPos pos, BlockState state) {
        if (!eligible(state) || OvergrownLayerData.isCovered(level, pos)) {
            return false;
        }
        int sides = 0;
        for (Direction d : CoverCreep.FROM) {
            BlockPos n = pos.relative(d);
            if (OvergrownLayerData.isCovered(level, n) || source(level.getBlockState(n))) {
                sides |= 1 << d.ordinal();
            }
        }
        if (sides == 0) {
            return false;
        }
        LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        long value = level.getGameTime() << 6 | sides;
        chunk.getData(ModAttachments.COVER_CREEP.get()).put(pos.asLong(), value);
        chunk.setUnsaved(true);
        pending(level, chunk.getPos()).put(pos.asLong(), value);
        return true;
    }

    /** そのマスの記録(無ければ {@link Long#MIN_VALUE})。 */
    public static long get(Level level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return Long.MIN_VALUE;
        }
        Long2LongOpenHashMap map = chunk.getExistingData(ModAttachments.COVER_CREEP.get()).orElse(null);
        return map == null ? Long.MIN_VALUE : map.getOrDefault(pos.asLong(), Long.MIN_VALUE);
    }

    /** そのマスが覆われた({@link OvergrownLayerData#cover} から)。記録を消す。 */
    public static void onCovered(ServerLevel level, BlockPos pos) {
        remove(level, pos);
    }

    /** そのマスが変わった: もう帯を描く資格が無ければ記録を消す(壊された・汚染で置き換わった・別の物に替わった)。 */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (get(level, pos) != Long.MIN_VALUE && !eligible(event.getState())) {
            remove(level, pos);
        }
    }

    private static void remove(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return;
        }
        Long2LongOpenHashMap map = chunk.getExistingData(ModAttachments.COVER_CREEP.get()).orElse(null);
        if (map == null || !map.containsKey(pos.asLong())) {
            return;
        }
        map.remove(pos.asLong());
        chunk.setUnsaved(true);
        pending(level, chunk.getPos()).put(pos.asLong(), REMOVED_MARK);
    }

    private static Long2LongOpenHashMap pending(ServerLevel level, ChunkPos cp) {
        return PENDING.computeIfAbsent(level.dimension(), k -> new HashMap<>()).computeIfAbsent(cp, k -> new Long2LongOpenHashMap());
    }

    /** この tick の増減を、そのチャンクを見ているプレイヤーへ送る(消す分を先に)。 */
    public static void flush(ServerLevel level) {
        Map<ChunkPos, Long2LongOpenHashMap> pending = PENDING.remove(level.dimension());
        if (pending == null) {
            return;
        }
        for (Map.Entry<ChunkPos, Long2LongOpenHashMap> entry : pending.entrySet()) {
            ChunkPos cp = entry.getKey();
            if (level.getChunkSource().getChunkNow(cp.x, cp.z) == null) {
                continue;
            }
            LongOpenHashSet removed = new LongOpenHashSet();
            Long2LongOpenHashMap added = new Long2LongOpenHashMap();
            for (var e : entry.getValue().long2LongEntrySet()) {
                if (e.getLongValue() == REMOVED_MARK) {
                    removed.add(e.getLongKey());
                } else {
                    added.put(e.getLongKey(), e.getLongValue());
                }
            }
            if (!removed.isEmpty()) {
                PacketDistributor.sendToPlayersTrackingChunk(level, cp, new CoverCreepPayload(cp, removed.toLongArray(), new long[0], false, true));
            }
            if (!added.isEmpty()) {
                PacketDistributor.sendToPlayersTrackingChunk(level, cp,
                        new CoverCreepPayload(cp, added.keySet().toLongArray(), added.values().toLongArray(), false, false));
            }
        }
    }

    /** そのチャンクを見始めたプレイヤーへ記録をまるごと送る。 */
    public static void sendAll(ServerPlayer player, LevelChunk chunk) {
        Long2LongOpenHashMap map = chunk.getExistingData(ModAttachments.COVER_CREEP.get()).orElse(null);
        if (map == null || map.isEmpty()) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new CoverCreepPayload(chunk.getPos(), map.keySet().toLongArray(), map.values().toLongArray(), true, false));
    }

    /** 届いた記録をクライアントのチャンクへ取り込む。 */
    public static void accept(LevelChunk chunk, CoverCreepPayload p) {
        Long2LongOpenHashMap map = chunk.getData(ModAttachments.COVER_CREEP.get());
        if (p.remove()) {
            for (long k : p.positions()) {
                map.remove(k);
            }
            return;
        }
        if (p.replace()) {
            map.clear();
        }
        for (int i = 0; i < p.positions().length && i < p.values().length; i++) {
            map.put(p.positions()[i], p.values()[i]);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }
}
