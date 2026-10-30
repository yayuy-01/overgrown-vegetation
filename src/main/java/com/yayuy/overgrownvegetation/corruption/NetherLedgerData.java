package com.yayuy.overgrownvegetation.corruption;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * <b>ネザーの過繁茂チャンクの台帳</b>(2026-10-02)。{@link NetherLedger} が使う。
 *
 * <p>オーバーワールドの台帳({@code PhaseData} の過繁茂チャンク)はキーに次元を持たないので、ネザーの分はここへ分けて持つ。
 * 保存先は<b>ネザーの</b> {@code data/}(次元ごとの保存場所なので、キーが座標だけでも混ざらない)。
 * 極相の印は持たない(ネザーに極相は無い)。
 */
public final class NetherLedgerData extends SavedData {

    private static final String ID = "overgrown_vegetation_nether_ledger";

    public static final SavedData.Factory<NetherLedgerData> FACTORY =
            new SavedData.Factory<>(NetherLedgerData::new, (tag, registries) -> load(tag), null);

    /** 過繁茂になったチャンク。 */
    private final LongOpenHashSet overgrown = new LongOpenHashSet();
    /** 台帳の広がりで印を付けたが、まだ読み込まれていなくて地面を塗っていないチャンク。 */
    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    private boolean started;
    private long pendingPeriod;
    private int periodTicks;
    private long markedTotal;

    private NetherLedgerData() {
    }

    private static NetherLedgerData load(CompoundTag tag) {
        NetherLedgerData data = new NetherLedgerData();
        for (long key : tag.getLongArray("overgrown")) {
            data.overgrown.add(key);
        }
        for (long key : tag.getLongArray("pending")) {
            data.pending.add(key);
        }
        data.started = tag.getBoolean("started") && tag.contains("pendingPeriod");
        data.pendingPeriod = tag.getLong("pendingPeriod");
        data.periodTicks = tag.getInt("periodTicks");
        data.markedTotal = tag.getLong("markedTotal");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("overgrown", overgrown.toLongArray());
        tag.putLongArray("pending", pending.toLongArray());
        tag.putBoolean("started", started);
        tag.putLong("pendingPeriod", pendingPeriod);
        tag.putInt("periodTicks", periodTicks);
        tag.putLong("markedTotal", markedTotal);
        return tag;
    }

    /** そのネザーの台帳(ネザーの {@code data/} に保存)。 */
    public static NetherLedgerData get(ServerLevel nether) {
        return nether.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public boolean isOvergrown(long key) {
        return overgrown.contains(key);
    }

    /** 新しく過繁茂として覚えたら {@code true}。 */
    boolean mark(long key) {
        if (overgrown.add(key)) {
            setDirty();
            return true;
        }
        return false;
    }

    public LongOpenHashSet overgrownKeys() {
        return overgrown;
    }

    public int overgrownCount() {
        return overgrown.size();
    }

    /** テスト用: そのチャンクの記録(過繁茂・書き換え待ち)を消す。 */
    public void forgetForTests(long key) {
        overgrown.remove(key);
        pending.remove(key);
        setDirty();
    }

    void addPending(long key) {
        if (pending.add(key)) {
            markedTotal++;
            setDirty();
        }
    }

    boolean isPending(long key) {
        return pending.contains(key);
    }

    void removePending(long key) {
        if (pending.remove(key)) {
            setDirty();
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    public long markedTotal() {
        return markedTotal;
    }

    boolean started() {
        return started;
    }

    long pendingPeriod() {
        return pendingPeriod;
    }

    int periodTicks() {
        return periodTicks;
    }

    void start(long period, int intervalTicks) {
        started = true;
        pendingPeriod = period;
        periodTicks = intervalTicks;
        setDirty();
    }

    void stop() {
        if (started) {
            started = false;
            setDirty();
        }
    }

    void finishPeriod() {
        pendingPeriod++;
        setDirty();
    }
}
