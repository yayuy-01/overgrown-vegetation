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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import com.yayuy.overgrownvegetation.port.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * <b>這い上がる苔の記録</b>(案 H の 28、2026-11-01 ユーザー指定)。帯が出るのは<b>覆われたマスに接して新しく置かれた</b>ブロックだけ
 * (もともと世界にあって覆いに接しているだけの地形・古い建物には出さない。「次に覆われる所が見える」のは置いた物に限る)。
 *
 * <ul>
 *     <li>置いたとき({@link BlockEvent.EntityPlaceEvent}: プレイヤー・Mob・ディスペンサー)、置かれたブロックが不透明で、下か横 4 つに覆われたマスか汚染ブロック({@link #source}、11-01 から)があれば、
 *     チャンクの記録({@link ModAttachments#COVER_CREEP})に「置いた時刻 + そのとき覆われていた向き」を書く。後から隣が覆われても足さない(仮決め)</li>
 *     <li>消すのは、そのマスが覆われたとき({@link OvergrownLayerData#cover} から)・壊された / 置き換わったとき(そのマスの更新の知らせ
 *     {@link BlockEvent.NeighborNotifyEvent} で、もう帯を描く資格が無ければ)</li>
 *     <li>2026-11-03 ユーザー指定から、覆われた・汚染ブロックになったマスの上と横の<b>既にある</b>建築ブロックにも付ける({@link #spreadFrom})。
 *     最終段の時間が過ぎたら覆いになり({@link CoverCreepChain})、そこからまた隣へ連鎖する</li>
 *     <li>送り方は覆いの記録と同じ(見始めたらまるごと・増減は tick の終わりにまとめて。{@link CoverCreepPayload})。クライアントは置いた時刻からの経過で段階を決める
 *     (入り直しても途中から続く)</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
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
        if (!eligible(state) || OvergrownLayerData.isCovered(level, pos)
                || inPeelGrace(level, level.getChunk(pos.getX() >> 4, pos.getZ() >> 4), pos)) {
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
        OvAttach.getData(chunk, ModAttachments.COVER_CREEP.get()).put(pos.asLong(), value);
        chunk.setUnsaved(true);
        pending(level, chunk.getPos()).put(pos.asLong(), value);
        CoverCreepChain.track(level, chunk.getPos().toLong(), level.getGameTime() + CoverCreepChain.durationTicks());
        return true;
    }

    /**
     * 連鎖で記録を付けるブロックか(2026-11-03): 帯を描く資格があり({@link #eligible})、建築タブのブロック({@link CorruptionRegistry#coversInPlace}:
     * 板材・丸石・石レンガ・階段・ハーフ・原木など)。砂・砂利・土・石などの地形と他 MOD のブロックは連鎖しない(仮決め。砂漠や地下を
     * 覆いが際限なく渡っていかないように。地形は今までどおり汚染の置き換え・深部浸食が受け持つ)。
     */
    public static boolean chains(BlockState state) {
        return eligible(state) && CorruptionRegistry.coversInPlace(state);
    }

    /**
     * {@code pos} が覆われた・不透明な汚染ブロックになった(2026-11-03 ユーザー指定「覆われたブロックに隣接する既存のブロックにも付ける」)。
     * {@code pos} を下か横({@link CoverCreep#FROM})に持つマス(= {@code pos} の上と横 4 つ)のうち、連鎖するブロック({@link #chains})で、
     * まだ覆われておらず、汚染してよい({@link CorruptionRegistry#canCorrupt}: 胞子除去装置・水没・汚染のオフ)ものに記録を付ける。
     * 時刻は今(段階 1 から)。もう記録があれば時刻はそのままで向きだけ足す(帯がその辺にも出る)。
     * 段階が尽きたら {@link CoverCreepChain} が覆いにし、そこからまた隣へ —— 建物が接した所から 1 マスずつ覆われていく。
     *
     * <p>読み込まれていないチャンクのマスは見ない(チャンクの縁を越える連鎖は、隣が読み込まれている間だけ)。
     */
    public static void spreadFrom(ServerLevel level, BlockPos pos) {
        if (CorruptionSwitch.isOff()) {
            return;
        }
        long now = level.getGameTime();
        for (Direction d : CoverCreep.FROM) {
            BlockPos n = pos.relative(d.getOpposite());
            LevelChunk chunk = level.getChunkSource().getChunkNow(n.getX() >> 4, n.getZ() >> 4);
            if (chunk == null || level.isOutsideBuildHeight(n)) {
                continue;
            }
            BlockState state = chunk.getBlockState(n);
            if (!chains(state) || OvergrownLayerData.isCovered(chunk, n, state) || inPeelGrace(level, chunk, n)
                    || !CorruptionRegistry.canCorrupt(level, n, state)) {
                continue;
            }
            Long2LongOpenHashMap map = OvAttach.getData(chunk, ModAttachments.COVER_CREEP.get());
            long key = n.asLong();
            int bit = 1 << d.ordinal();
            long value;
            if (!map.containsKey(key)) {
                value = now << 6 | bit;
                CoverCreepChain.track(level, chunk.getPos().toLong(), now + CoverCreepChain.durationTicks());
            } else {
                long old = map.get(key);
                if ((old & bit) != 0) {
                    continue;
                }
                value = old | bit;
            }
            map.put(key, value);
            chunk.setUnsaved(true);
            pending(level, chunk.getPos()).put(key, value);
        }
    }

    /** そのマスの記録(無ければ {@link Long#MIN_VALUE})。 */
    public static long get(Level level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return Long.MIN_VALUE;
        }
        Long2LongOpenHashMap map = OvAttach.getExistingData(chunk, ModAttachments.COVER_CREEP.get()).orElse(null);
        return map == null ? Long.MIN_VALUE : map.getOrDefault(pos.asLong(), Long.MIN_VALUE);
    }

    /** 剥がしたマスに這い上がる苔を付けない猶予(2026-11-03 ユーザー指定。10 分は仮決め)。 */
    public static final long PEEL_GRACE_TICKS = 12000L;

    /**
     * そのマスの覆いが剥がされた({@link OvergrownLayerData#uncover} から、2026-11-03 ユーザー指定)。そのマスの記録を消し、
     * 剥がした時刻を書く —— {@link #PEEL_GRACE_TICKS} の間は連鎖({@link #spreadFrom})でも置いたとき({@link #placed})でも記録を付けない
     * (付けると隣が覆われた拍子に 2 分で覆われ直し、剥がしても戻ってしまう)。
     */
    public static void onUncovered(ServerLevel level, BlockPos pos) {
        remove(level, pos);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) {
            OvAttach.getData(chunk, ModAttachments.COVER_PEELED.get()).put(pos.asLong(), level.getGameTime());
            chunk.setUnsaved(true);
        }
    }

    /** 剥がしてから猶予の間か。過ぎた印はここで捨てる。 */
    public static boolean inPeelGrace(ServerLevel level, LevelChunk chunk, BlockPos pos) {
        Long2LongOpenHashMap peeled = OvAttach.getExistingData(chunk, ModAttachments.COVER_PEELED.get()).orElse(null);
        if (peeled == null || !peeled.containsKey(pos.asLong())) {
            return false;
        }
        if (level.getGameTime() - peeled.get(pos.asLong()) < PEEL_GRACE_TICKS) {
            return true;
        }
        peeled.remove(pos.asLong());
        chunk.setUnsaved(true);
        return false;
    }

    /** GameTest 用: そのマスの記録の時刻を {@code ticks} 前へずらし、次の {@link CoverCreepChain#sweep} で見させる(時間を進める代わり)。 */
    public static void ageForTest(ServerLevel level, BlockPos pos, long ticks) {
        LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        Long2LongOpenHashMap map = OvAttach.getData(chunk, ModAttachments.COVER_CREEP.get());
        if (map.containsKey(pos.asLong())) {
            long v = map.get(pos.asLong());
            map.put(pos.asLong(), (startTick(v) - ticks) << 6 | sides(v));
            CoverCreepChain.track(level, chunk.getPos().toLong(), Long.MIN_VALUE);
        }
    }

    /** そのマスが覆われた({@link OvergrownLayerData#cover} から)。記録を消し、隣へ連鎖させる({@link #spreadFrom}、2026-11-03)。 */
    public static void onCovered(ServerLevel level, BlockPos pos) {
        remove(level, pos);
        spreadFrom(level, pos);
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

    static void remove(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return;
        }
        Long2LongOpenHashMap map = OvAttach.getExistingData(chunk, ModAttachments.COVER_CREEP.get()).orElse(null);
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
        Long2LongOpenHashMap map = OvAttach.getExistingData(chunk, ModAttachments.COVER_CREEP.get()).orElse(null);
        if (map == null || map.isEmpty()) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new CoverCreepPayload(chunk.getPos(), map.keySet().toLongArray(), map.values().toLongArray(), true, false));
    }

    /** 届いた記録をクライアントのチャンクへ取り込む。 */
    public static void accept(LevelChunk chunk, CoverCreepPayload p) {
        Long2LongOpenHashMap map = OvAttach.getData(chunk, ModAttachments.COVER_CREEP.get());
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
