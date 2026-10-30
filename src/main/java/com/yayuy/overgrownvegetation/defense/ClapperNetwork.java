package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 鳴子の紐の<b>つながり</b>と<b>鳴らし方</b>(2026-10-15、ユーザー指定「接続している鳴子は全部鳴る」「結んだ柱から信号」)。
 *
 * <ul>
 *     <li>台帳: 次元(Level)ごとに「柱 → その柱に結んだ紐(目印のブロックの位置)」と、全ての紐の一覧。紐の目印のブロックエンティティが
 *     読み込まれたとき・張られたときに載り、消えるとき・チャンクが閉じるときに外れる(クライアントにも同じ形の台帳があり、
 *     右クリックの当たり判定に使う)。</li>
 *     <li>鳴らす: どれか 1 本に触れたら、端の柱を共有している紐をたどって(最大 {@link #MAX_ROPES} 本)全部鳴らす。
 *     紐ごとに 2 秒の間を置く。鳴った紐の両端の柱は {@link #POWER_TICKS} の間レッドストーン信号 15 を出す
 *     ({@code mixin/FenceBlockClapperSignalMixin}・{@code WallBlockClapperSignalMixin})。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ClapperNetwork {

    /** 1 回に鳴らせるつながった紐の数(仮決め)。 */
    public static final int MAX_ROPES = 64;
    /** 同じ紐が次に鳴るまでの間(tick。仮決め 2 秒)。 */
    public static final int COOLDOWN_TICKS = 40;
    /** 柱が信号を出す長さ(tick。仮決め 1 秒)。 */
    public static final int POWER_TICKS = 20;

    private static final class Ledger {
        final Long2ObjectOpenHashMap<LongOpenHashSet> postToRopes = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet ropes = new LongOpenHashSet();
        final Long2LongOpenHashMap poweredUntil = new Long2LongOpenHashMap();
    }

    private static final Map<Level, Ledger> LEDGERS = new WeakHashMap<>();

    private static synchronized Ledger ledger(Level level) {
        return LEDGERS.computeIfAbsent(level, l -> new Ledger());
    }

    public static synchronized void register(Level level, BlockPos rope, BlockPos source, BlockPos target) {
        Ledger l = ledger(level);
        long key = rope.asLong();
        l.ropes.add(key);
        l.postToRopes.computeIfAbsent(source.asLong(), k -> new LongOpenHashSet()).add(key);
        l.postToRopes.computeIfAbsent(target.asLong(), k -> new LongOpenHashSet()).add(key);
    }

    public static synchronized void unregister(Level level, BlockPos rope, BlockPos source, BlockPos target) {
        Ledger l = LEDGERS.get(level);
        if (l == null) {
            return;
        }
        long key = rope.asLong();
        l.ropes.remove(key);
        for (long post : new long[]{source.asLong(), target.asLong()}) {
            LongOpenHashSet set = l.postToRopes.get(post);
            if (set != null) {
                set.remove(key);
                if (set.isEmpty()) {
                    l.postToRopes.remove(post);
                }
            }
        }
    }

    /** {@code center} から {@code radius} ブロック以内にある紐(目印のブロックの位置)。 */
    public static synchronized List<BlockPos> ropesNear(Level level, BlockPos center, double radius) {
        Ledger l = LEDGERS.get(level);
        List<BlockPos> out = new ArrayList<>();
        if (l == null) {
            return out;
        }
        double r2 = radius * radius;
        for (long key : l.ropes) {
            BlockPos pos = BlockPos.of(key);
            if (pos.distSqr(center) <= r2) {
                out.add(pos);
            }
        }
        return out;
    }

    /** その柱に結ばれている紐の数(GameTest・確かめ用)。 */
    public static synchronized int ropesAtPost(Level level, BlockPos post) {
        Ledger l = LEDGERS.get(level);
        LongOpenHashSet set = l == null ? null : l.postToRopes.get(post.asLong());
        return set == null ? 0 : set.size();
    }

    /**
     * {@code start} の紐に触れた。つながった紐を全部鳴らす(間を置いている紐は飛ばすが、たどるのは続ける)。
     *
     * @return 実際に鳴らした紐の数
     */
    public static int ring(ServerLevel level, BlockPos start) {
        List<BlockPos> chain = new ArrayList<>();
        synchronized (ClapperNetwork.class) {
            Ledger l = LEDGERS.get(level);
            if (l == null || !l.ropes.contains(start.asLong())) {
                return 0;
            }
            LongOpenHashSet seen = new LongOpenHashSet();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            queue.enqueue(start.asLong());
            seen.add(start.asLong());
            while (!queue.isEmpty() && chain.size() < MAX_ROPES) {
                long key = queue.dequeueLong();
                chain.add(BlockPos.of(key));
                if (!(level.getBlockEntity(BlockPos.of(key)) instanceof ClapperRopeBlockEntity rope) || !rope.hasRope()) {
                    continue;
                }
                for (BlockPos post : new BlockPos[]{rope.getSource(), rope.getTarget()}) {
                    LongOpenHashSet next = l.postToRopes.get(post.asLong());
                    if (next == null) {
                        continue;
                    }
                    for (long n : next) {
                        if (seen.add(n)) {
                            queue.enqueue(n);
                        }
                    }
                }
            }
        }
        int rung = 0;
        for (BlockPos pos : chain) {
            if (level.getBlockEntity(pos) instanceof ClapperRopeBlockEntity rope && rope.ringNow(level)) {
                rung++;
                power(level, rope.getSource());
                power(level, rope.getTarget());
            }
        }
        return rung;
    }

    private static void power(ServerLevel level, BlockPos post) {
        boolean wasPowered;
        synchronized (ClapperNetwork.class) {
            Ledger l = ledger(level);
            wasPowered = l.poweredUntil.containsKey(post.asLong());
            l.poweredUntil.put(post.asLong(), level.getGameTime() + POWER_TICKS);
        }
        if (!wasPowered) {
            level.updateNeighborsAt(post, level.getBlockState(post).getBlock());
        }
    }

    /**
     * その柱が今、鳴子の信号を出しているか(柵・塀の {@code getSignal} から。とても多く呼ばれるので、何も鳴っていなければすぐ返す)。
     */
    public static int signalAt(BlockGetter getter, BlockPos pos) {
        if (!(getter instanceof Level level)) {
            return 0;
        }
        synchronized (ClapperNetwork.class) {
            Ledger l = LEDGERS.get(level);
            if (l == null || l.poweredUntil.isEmpty()) {
                return 0;
            }
            return l.poweredUntil.containsKey(pos.asLong()) ? 15 : 0;
        }
    }

    /** 信号を出し終えた柱を消し、周りに知らせる。 */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<BlockPos> ended = null;
        synchronized (ClapperNetwork.class) {
            Ledger l = LEDGERS.get(level);
            if (l == null || l.poweredUntil.isEmpty()) {
                return;
            }
            long now = level.getGameTime();
            var it = l.poweredUntil.long2LongEntrySet().iterator();
            while (it.hasNext()) {
                Long2LongMap.Entry e = it.next();
                if (e.getLongValue() <= now) {
                    if (ended == null) {
                        ended = new ArrayList<>();
                    }
                    ended.add(BlockPos.of(e.getLongKey()));
                    it.remove();
                }
            }
        }
        if (ended != null) {
            for (BlockPos post : ended) {
                level.updateNeighborsAt(post, level.getBlockState(post).getBlock());
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (ClapperNetwork.class) {
            LEDGERS.clear();
        }
    }

    private ClapperNetwork() {
    }
}
