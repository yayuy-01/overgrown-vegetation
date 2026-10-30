package com.yayuy.overgrownvegetation.base.road;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 道の台帳(順 2 の着手順 7、2026-10-08)。オーバーワールドに 1 つ。
 *
 * <p>プレイヤーが置いた道のブロック(タグ {@code road_blocks})のマスと、そのうち苔に覆われているマスを持つ。基地どうしのつながりは
 * このマスをたどって求める({@link RoadGraph}) —— ブロックを読みに行くと、読み込まれていないチャンクに当たるため
 * (ユーザー指定 10-07「プレイヤーが敷いた道もつながりに数える」)。マスは置いた・壊した・汚染されたときに書き換え、
 * チャンクを読み込んだときに確かめ直す({@link BaseRoads})。
 */
public final class RoadLedger extends SavedData {

    private static final String ID = "overgrown_vegetation_roads";

    public static final SavedData.Factory<RoadLedger> FACTORY =
            new SavedData.Factory<>(RoadLedger::new, (tag, registries) -> load(tag), null);

    /** プレイヤーが置いた道のマス({@link BlockPos#asLong})。 */
    private final LongOpenHashSet cells = new LongOpenHashSet();
    /** そのうち苔に覆われているマス(覆われた道はつながりに数えない)。 */
    private final LongOpenHashSet covered = new LongOpenHashSet();
    /** チャンク({@link ChunkPos#toLong})→ そのチャンクのマス。保存しない(読み込んだときに作る)。 */
    private final transient Long2ObjectOpenHashMap<LongOpenHashSet> byChunk = new Long2ObjectOpenHashMap<>();
    /** 書き換えた回数(つながりを数え直すかの目印。保存しない)。 */
    private transient int version;

    /** 同盟軍の道(工事中も含む)。 */
    private final List<AlliedRoad> roads = new ArrayList<>();
    private int nextRoadId = 1;
    /** 道筋が見つからなかった組({@link #pair})→ 探し直してよい暦の日。 */
    private final Long2LongOpenHashMap retry = new Long2LongOpenHashMap();
    /** 基地の番号 → 正門の道の先の路面の高さ(そのチャンクが読み込まれたときに測る。道の両端をそろえる)。 */
    private final Int2IntOpenHashMap portTop = new Int2IntOpenHashMap();

    private RoadLedger() {
    }

    public static RoadLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static RoadLedger load(CompoundTag tag) {
        RoadLedger data = new RoadLedger();
        for (long p : tag.getLongArray("cells")) {
            data.cells.add(p);
            data.index(p);
        }
        for (long p : tag.getLongArray("covered")) {
            if (data.cells.contains(p)) {
                data.covered.add(p);
            }
        }
        data.nextRoadId = Math.max(1, tag.getInt("nextRoadId"));
        ListTag list = tag.getList("roads", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            AlliedRoad r = AlliedRoad.load(list.getCompound(i));
            if (r.length() > 1) {
                data.roads.add(r);
                data.nextRoadId = Math.max(data.nextRoadId, r.id + 1);
            }
        }
        long[] keys = tag.getLongArray("retryKeys");
        long[] days = tag.getLongArray("retryDays");
        for (int i = 0; i < Math.min(keys.length, days.length); i++) {
            data.retry.put(keys[i], days[i]);
        }
        int[] ports = tag.getIntArray("portTop");
        for (int i = 0; i + 1 < ports.length; i += 2) {
            data.portTop.put(ports[i], ports[i + 1]);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("cells", cells.toLongArray());
        tag.putLongArray("covered", covered.toLongArray());
        tag.putInt("nextRoadId", nextRoadId);
        ListTag list = new ListTag();
        for (AlliedRoad r : roads) {
            list.add(r.save());
        }
        tag.put("roads", list);
        tag.putLongArray("retryKeys", retry.keySet().toLongArray());
        long[] days = new long[retry.size()];
        int i = 0;
        for (long k : retry.keySet()) {
            days[i++] = retry.get(k);
        }
        tag.putLongArray("retryDays", days);
        int[] ports = new int[portTop.size() * 2];
        int j = 0;
        for (int site : portTop.keySet()) {
            ports[j++] = site;
            ports[j++] = portTop.get(site);
        }
        tag.putIntArray("portTop", ports);
        return tag;
    }

    /** 正門の道の先の路面の高さ(測っていなければ {@link RoadPlanner#UNKNOWN})。 */
    public int portTop(int site) {
        return portTop.containsKey(site) ? portTop.get(site) : RoadPlanner.UNKNOWN;
    }

    public void setPortTop(int site, int top) {
        if (!portTop.containsKey(site) || portTop.get(site) != top) {
            portTop.put(site, top);
            setDirty();
        }
    }

    // ---------- 同盟軍の道 ----------

    /** 基地の組の鍵(順番によらない)。 */
    static long pair(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFFFFFFL);
    }

    public List<AlliedRoad> roads() {
        return Collections.unmodifiableList(roads);
    }

    @Nullable
    public AlliedRoad road(int id) {
        for (AlliedRoad r : roads) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    @Nullable
    public AlliedRoad roadBetween(int a, int b) {
        for (AlliedRoad r : roads) {
            if (r.links(a, b)) {
                return r;
            }
        }
        return null;
    }

    public AlliedRoad addRoad(int a, int b, RoadPlanner.Plan plan, long now) {
        AlliedRoad r = new AlliedRoad(nextRoadId++, a, b, plan, now);
        roads.add(r);
        retry.remove(pair(a, b));
        roadsStamp++;
        roadsChanged();
        return r;
    }

    public boolean removeRoad(AlliedRoad r) {
        boolean removed = roads.remove(r);
        if (removed) {
            roadsStamp++;
            roadsChanged();
        }
        return removed;
    }

    /** 道が増えた・消えた回数(チャンク → 道の索引を作り直す目印。保存しない)。 */
    private transient int roadsStamp;

    public int roadsStamp() {
        return roadsStamp;
    }

    /** 探し直してよい暦の日(無ければ {@link Long#MIN_VALUE})。 */
    public long retryDay(int a, int b) {
        return retry.containsKey(pair(a, b)) ? retry.get(pair(a, b)) : Long.MIN_VALUE;
    }

    public void setRetry(int a, int b, long day) {
        retry.put(pair(a, b), day);
        setDirty();
    }

    /** 道が増えた・消えた・つながりの見え方が変わるほど進んだ(数え直しの目印を進めて保存する)。 */
    public void roadsChanged() {
        changed();
    }

    /** 工事が進んだ(保存だけ)。 */
    public void progressed() {
        setDirty();
    }

    static long chunkOf(long pos) {
        return ChunkPos.asLong(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4);
    }

    private void index(long pos) {
        byChunk.computeIfAbsent(chunkOf(pos), k -> new LongOpenHashSet()).add(pos);
    }

    private void changed() {
        version++;
        setDirty();
    }

    /** マスを足す(既にあれば覆われているかだけ合わせる)。変わったら {@code true}。 */
    public boolean add(long pos, boolean isCovered) {
        boolean added = cells.add(pos);
        if (added) {
            index(pos);
        }
        boolean flagChanged = isCovered ? covered.add(pos) : covered.remove(pos);
        if (added || flagChanged) {
            changed();
            return true;
        }
        return false;
    }

    /** マスを消す。あったら {@code true}。 */
    public boolean remove(long pos) {
        if (!cells.remove(pos)) {
            return false;
        }
        covered.remove(pos);
        LongOpenHashSet set = byChunk.get(chunkOf(pos));
        if (set != null) {
            set.remove(pos);
            if (set.isEmpty()) {
                byChunk.remove(chunkOf(pos));
            }
        }
        changed();
        return true;
    }

    /** 覆われているかを書き換える(台帳に無いマスは何もしない)。変わったら {@code true}。 */
    public boolean setCovered(long pos, boolean isCovered) {
        if (!cells.contains(pos)) {
            return false;
        }
        boolean flagChanged = isCovered ? covered.add(pos) : covered.remove(pos);
        if (flagChanged) {
            changed();
        }
        return flagChanged;
    }

    public boolean has(long pos) {
        return cells.contains(pos);
    }

    public boolean isCovered(long pos) {
        return covered.contains(pos);
    }

    /** 全部のマス(書き換えない)。 */
    public LongSet cells() {
        return LongSets.unmodifiable(cells);
    }

    /** そのチャンクのマス(書き換えない。無ければ空)。 */
    public LongSet cellsIn(long chunkKey) {
        LongOpenHashSet set = byChunk.get(chunkKey);
        return set == null ? LongSets.EMPTY_SET : LongSets.unmodifiable(set);
    }

    public int size() {
        return cells.size();
    }

    public int coveredCount() {
        return covered.size();
    }

    public int version() {
        return version;
    }

    /** 開発用: 全部消す。 */
    public void clear() {
        if (cells.isEmpty()) {
            return;
        }
        cells.clear();
        covered.clear();
        byChunk.clear();
        changed();
    }
}
