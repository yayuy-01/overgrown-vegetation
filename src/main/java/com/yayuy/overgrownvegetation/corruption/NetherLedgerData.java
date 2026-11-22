package com.yayuy.overgrownvegetation.corruption;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import com.yayuy.overgrownvegetation.port.saved.OvSavedData;

/**
 * <b>ネザーの根の台帳</b>(2026-11-11 骨組みで作り直し。前は 10-02 の「過繁茂チャンクの集合」)。{@link NetherLedger} が使う。
 *
 * <p>チャンクごとに<b>前線が届いた時刻</b>(ゲーム時刻)を持つ。段階は「今 − 届いた時刻」から毎回計算する
 * ({@link com.yayuy.overgrownvegetation.data.NetherSpreadTable#stageAt})ので、留守中も進み、読み込まれたときに地面が追いつく。
 * <ul>
 *     <li>{@code arrival}: 前線が届いたチャンク → 届いた時刻</li>
 *     <li>{@code frontier}: まだ届いていない隣のチャンク → いちばん早く届く時刻(根の速さで決まる)</li>
 *     <li>{@code terrain}: 海(1)・陸(0)の覚え(根の速さに使う)</li>
 *     <li>{@code applied}: 地面の作り変えが終わった段階(読み込まれたときにここから続ける)</li>
 *     <li>{@code counted}: 段階 3(過繁茂)に入ってフェーズの面積・ポイントに数えたチャンク。{@link #isOvergrown} はこれ</li>
 *     <li>{@code lakeCells}・{@code pillars}: 湖の水面のマス数と、天を支える樹の口を呼んだチャンク</li>
 *     <li>{@code origins}: 起点(ポータルの出口のチャンク)。広がりの上限はここからの距離</li>
 * </ul>
 * 保存先はネザーの {@code data/}。版 1(10-02〜11-11)の {@code overgrown} は、段階 3 に入った所として読み込む。
 */
public final class NetherLedgerData extends OvSavedData {

    private static final String ID = "overgrown_vegetation_nether_ledger";
    private static final int VERSION = 2;

    public static final OvSavedData.Factory<NetherLedgerData> FACTORY =
            new OvSavedData.Factory<>(NetherLedgerData::new, (tag, registries) -> load(tag), null);

    final Long2LongOpenHashMap arrival = new Long2LongOpenHashMap();
    final Long2LongOpenHashMap frontier = new Long2LongOpenHashMap();
    final Long2ByteOpenHashMap terrain = new Long2ByteOpenHashMap();
    final Long2ByteOpenHashMap applied = new Long2ByteOpenHashMap();
    final LongOpenHashSet counted = new LongOpenHashSet();
    final Long2IntOpenHashMap lakeCells = new Long2IntOpenHashMap();
    final LongOpenHashSet pillars = new LongOpenHashSet();
    final LongArrayList origins = new LongArrayList();
    /** 前線(根)の 1 マスがどの起点の根か。 */
    final Long2LongOpenHashMap frontierOrigin = new Long2LongOpenHashMap();
    /** 近くに溶岩の海が無く、隣へ這って海を探している根の起点。 */
    final LongOpenHashSet creepOrigins = new LongOpenHashSet();
    /** 根が溶岩の海に着いた起点(そこから先の広がりは胞子。根はもう伸びない)。 */
    final LongOpenHashSet rootDone = new LongOpenHashSet();
    /** 覆いが広がり始める所(チャンク → BlockPos の long。y は 0)。ふだんの汚染なら当たった所、根・胞子なら入ってきた側の辺(2026-11-12)。 */
    final Long2LongOpenHashMap coverOrigin = new Long2LongOpenHashMap();
    /**
     * 最初の根の道筋(2026-11-12 根の網): チャンク → 前 / 次のチャンク(道の上だけ)。起点のチャンクの {@code rootStart} はポータルの出口(BlockPos の long)。
     * 太い根はこれをたどって、チャンクごとに入る辺から出る辺へ描く。海に着いた最後のチャンクは次が無い(岸に潜る)。
     */
    final Long2LongOpenHashMap rootPrev = new Long2LongOpenHashMap();
    final Long2LongOpenHashMap rootNext = new Long2LongOpenHashMap();
    final Long2LongOpenHashMap rootStart = new Long2LongOpenHashMap();
    /** 植物を描き終えたしるし(チャンク → ビット。1 = 太い根・2 = 枝の根・4 = 管の樹)。 */
    final Long2ByteOpenHashMap flora = new Long2ByteOpenHashMap();
    /** 管の樹の数(チャンク → 立てた樹の数。2026-11-12)。胞子の場の管の樹の多さに使う(描き終えたチャンク = flora の 4 だけ)。 */
    final Long2IntOpenHashMap tubes = new Long2IntOpenHashMap();
    /** 速さの倍率({@code /ov nether speed})。根の 1 チャンクの時間をこれで割る。 */
    double speed = 1.0;
    long lastLeapCheck = Long.MIN_VALUE;
    /** 版 1 から読み込んだ過繁茂チャンク(届いた時刻は最初の tick で決める)。 */
    final LongOpenHashSet legacy = new LongOpenHashSet();

    private NetherLedgerData() {
        arrival.defaultReturnValue(Long.MIN_VALUE);
        frontier.defaultReturnValue(Long.MAX_VALUE);
        terrain.defaultReturnValue((byte) -1);
        applied.defaultReturnValue((byte) 0);
    }

    private static NetherLedgerData load(CompoundTag tag) {
        NetherLedgerData d = new NetherLedgerData();
        if (tag.getInt("version") < VERSION) {
            for (long key : tag.getLongArray("overgrown")) {
                d.legacy.add(key);
            }
            return d;
        }
        readMap(tag.getLongArray("arrival"), d.arrival);
        readMap(tag.getLongArray("frontier"), d.frontier);
        long[] t = tag.getLongArray("terrain");
        for (int i = 0; i + 1 < t.length; i += 2) d.terrain.put(t[i], (byte) t[i + 1]);
        long[] a = tag.getLongArray("applied");
        for (int i = 0; i + 1 < a.length; i += 2) d.applied.put(a[i], (byte) a[i + 1]);
        for (long k : tag.getLongArray("counted")) d.counted.add(k);
        long[] l = tag.getLongArray("lakeCells");
        for (int i = 0; i + 1 < l.length; i += 2) d.lakeCells.put(l[i], (int) l[i + 1]);
        for (long k : tag.getLongArray("pillars")) d.pillars.add(k);
        for (long k : tag.getLongArray("origins")) d.origins.add(k);
        readMap(tag.getLongArray("frontierOrigin"), d.frontierOrigin);
        readMap(tag.getLongArray("coverOrigin"), d.coverOrigin);
        readMap(tag.getLongArray("rootPrev"), d.rootPrev);
        readMap(tag.getLongArray("rootNext"), d.rootNext);
        readMap(tag.getLongArray("rootStart"), d.rootStart);
        long[] fl = tag.getLongArray("flora");
        for (int i = 0; i + 1 < fl.length; i += 2) d.flora.put(fl[i], (byte) fl[i + 1]);
        long[] tb = tag.getLongArray("tubes");
        for (int i = 0; i + 1 < tb.length; i += 2) d.tubes.put(tb[i], (int) tb[i + 1]);
        for (long k : tag.getLongArray("creepOrigins")) d.creepOrigins.add(k);
        for (long k : tag.getLongArray("rootDone")) d.rootDone.add(k);
        d.speed = tag.contains("speed") ? tag.getDouble("speed") : 1.0;
        d.lastLeapCheck = tag.contains("lastLeapCheck") ? tag.getLong("lastLeapCheck") : Long.MIN_VALUE;
        return d;
    }

    private static void readMap(long[] flat, Long2LongOpenHashMap into) {
        for (int i = 0; i + 1 < flat.length; i += 2) into.put(flat[i], flat[i + 1]);
    }

    private static long[] flat(Long2LongOpenHashMap m) {
        long[] out = new long[m.size() * 2];
        int i = 0;
        for (Long2LongOpenHashMap.Entry e : m.long2LongEntrySet()) {
            out[i++] = e.getLongKey();
            out[i++] = e.getLongValue();
        }
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", VERSION);
        tag.putLongArray("arrival", flat(arrival));
        tag.putLongArray("frontier", flat(frontier));
        long[] t = new long[terrain.size() * 2];
        int i = 0;
        for (Long2ByteOpenHashMap.Entry e : terrain.long2ByteEntrySet()) {
            t[i++] = e.getLongKey();
            t[i++] = e.getByteValue();
        }
        tag.putLongArray("terrain", t);
        long[] a = new long[applied.size() * 2];
        i = 0;
        for (Long2ByteOpenHashMap.Entry e : applied.long2ByteEntrySet()) {
            a[i++] = e.getLongKey();
            a[i++] = e.getByteValue();
        }
        tag.putLongArray("applied", a);
        tag.putLongArray("counted", counted.toLongArray());
        long[] l = new long[lakeCells.size() * 2];
        i = 0;
        for (Long2IntOpenHashMap.Entry e : lakeCells.long2IntEntrySet()) {
            l[i++] = e.getLongKey();
            l[i++] = e.getIntValue();
        }
        tag.putLongArray("lakeCells", l);
        tag.putLongArray("pillars", pillars.toLongArray());
        tag.putLongArray("origins", origins.toLongArray());
        tag.putLongArray("frontierOrigin", flat(frontierOrigin));
        tag.putLongArray("coverOrigin", flat(coverOrigin));
        tag.putLongArray("rootPrev", flat(rootPrev));
        tag.putLongArray("rootNext", flat(rootNext));
        tag.putLongArray("rootStart", flat(rootStart));
        long[] fl = new long[flora.size() * 2];
        int fi = 0;
        for (Long2ByteOpenHashMap.Entry e : flora.long2ByteEntrySet()) {
            fl[fi++] = e.getLongKey();
            fl[fi++] = e.getByteValue();
        }
        tag.putLongArray("flora", fl);
        long[] tb = new long[tubes.size() * 2];
        int ti = 0;
        for (Long2IntOpenHashMap.Entry e : tubes.long2IntEntrySet()) {
            tb[ti++] = e.getLongKey();
            tb[ti++] = e.getIntValue();
        }
        tag.putLongArray("tubes", tb);
        tag.putLongArray("creepOrigins", creepOrigins.toLongArray());
        tag.putLongArray("rootDone", rootDone.toLongArray());
        tag.putDouble("speed", speed);
        tag.putLong("lastLeapCheck", lastLeapCheck);
        return tag;
    }

    /** そのネザーの台帳(ネザーの {@code data/} に保存)。 */
    public static NetherLedgerData get(ServerLevel nether) {
        return nether.getDataStorage().computeIfAbsent(FACTORY.load(), FACTORY.constructor(), ID);
    }

    // ---- 読む口 ----

    /** 前線が届いているか。 */
    public boolean isReached(long key) {
        return arrival.containsKey(key);
    }

    /** 届いた時刻(届いていなければ {@link Long#MIN_VALUE})。 */
    public long arrivalOf(long key) {
        return arrival.get(key);
    }

    /** 段階 3(過繁茂)に入ったチャンクか(胞子の濃さ・発芽の見回りが読む)。 */
    public boolean isOvergrown(long key) {
        return counted.contains(key);
    }

    public LongOpenHashSet overgrownKeys() {
        return counted;
    }

    public int overgrownCount() {
        return counted.size();
    }

    public int reachedCount() {
        return arrival.size();
    }

    public int frontierCount() {
        return frontier.size();
    }

    public int appliedStage(long key) {
        return applied.get(key);
    }

    public int lakeCellsOf(long key) {
        return lakeCells.get(key);
    }

    /** 根が溶岩の海に着いたか(その起点の根は止まっている)。 */
    /** 覆いが広がり始める所(無ければチャンクの真ん中。古いセーブ)。 */
    public long coverOriginOf(long key) {
        if (coverOrigin.containsKey(key)) return coverOrigin.get(key);
        return net.minecraft.core.BlockPos.asLong((net.minecraft.world.level.ChunkPos.getX(key) << 4) + 8, 0, (net.minecraft.world.level.ChunkPos.getZ(key) << 4) + 8);
    }

    public boolean rootReachedSea(long origin) {
        return rootDone.contains(origin);
    }

    public int pillarCount() {
        return pillars.size();
    }

    public double speed() {
        return speed;
    }

    /** 胞子の場の計算(裏のスレッド)へ渡す写し: 汚染された時刻・海/陸の覚え・起点。 */
    public Long2LongOpenHashMap arrivalCopy() {
        Long2LongOpenHashMap c = arrival.clone();
        c.defaultReturnValue(Long.MIN_VALUE);
        return c;
    }

    public Long2ByteOpenHashMap terrainCopy() {
        Long2ByteOpenHashMap c = terrain.clone();
        c.defaultReturnValue((byte) -1);
        return c;
    }

    public Long2IntOpenHashMap lakeCellsCopy() {
        return lakeCells.clone();
    }

    /** 管の樹を描き終えたチャンクの樹の数の写し(胞子の場の裏のスレッドへ渡す)。 */
    public Long2IntOpenHashMap tubesCopy() {
        Long2IntOpenHashMap c = new Long2IntOpenHashMap();
        c.defaultReturnValue(-1);
        for (Long2IntOpenHashMap.Entry e : tubes.long2IntEntrySet()) {
            if ((flora.get(e.getLongKey()) & 4) != 0) c.put(e.getLongKey(), e.getIntValue());
        }
        return c;
    }

    public int tubesOf(long key) {
        return tubes.get(key);
    }

    public long[] originsArray() {
        return origins.toLongArray();
    }

    /** テスト用: そのチャンクの記録を全部消す。 */
    public void forgetForTests(long key) {
        arrival.remove(key);
        frontier.remove(key);
        frontierOrigin.remove(key);
        coverOrigin.remove(key);
        flora.remove(key);
        tubes.remove(key);
        terrain.remove(key);
        applied.remove(key);
        counted.remove(key);
        lakeCells.remove(key);
        pillars.remove(key);
        setDirty();
    }

    /** テスト用: 台帳を空にする。 */
    public void clearForTests() {
        arrival.clear();
        frontier.clear();
        terrain.clear();
        applied.clear();
        counted.clear();
        lakeCells.clear();
        pillars.clear();
        origins.clear();
        frontierOrigin.clear();
        coverOrigin.clear();
        rootPrev.clear();
        rootNext.clear();
        rootStart.clear();
        flora.clear();
        tubes.clear();
        creepOrigins.clear();
        rootDone.clear();
        legacy.clear();
        speed = 1.0;
        lastLeapCheck = Long.MIN_VALUE;
        setDirty();
    }
}
