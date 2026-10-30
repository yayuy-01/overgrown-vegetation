package com.yayuy.overgrownvegetation.corruption;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 台帳の書き換え待ち({@link LedgerSpread})の保存データ。オーバーワールドに 1 つ。2026-10-30 に止まっていた「5 分おきの縁ぬり」の回の記録
 * ({@code started}・{@code pendingPeriod}・{@code periodTicks}・{@code lastPeriodMarked})を消した(古いセーブのキーは読み飛ばす)。
 *
 * <ul>
 *     <li><b>書き換え待ち</b> —— 台帳に印を付けたが、まだ世界(バイオーム・地表)を書き換えていないチャンク。
 *     読み込まれたときに {@link LedgerSpreadCatchUp} が書き換えて外す。台帳の印は別の経路(ネザーの過繁茂も同じ台帳に入る、
 *     旧セーブの拾い直し)でも付くので、<b>台帳の広がり自身が付けた印だけ</b>をここで覚える(それ以外は書き換えない)。</li>
 * </ul>
 *
 */
public final class LedgerSpreadData extends SavedData {

    private static final String ID = "overgrown_vegetation_ledger_spread";

    public static final SavedData.Factory<LedgerSpreadData> FACTORY =
            new SavedData.Factory<>(LedgerSpreadData::new, (tag, registries) -> load(tag), null);

    private long markedTotal;
    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();

    private LedgerSpreadData() {
    }

    private static LedgerSpreadData load(CompoundTag tag) {
        LedgerSpreadData data = new LedgerSpreadData();
        data.markedTotal = tag.getLong("markedTotal");
        for (long key : tag.getLongArray("pending")) {
            data.pending.add(key);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("markedTotal", markedTotal);
        tag.putLongArray("pending", pending.toLongArray());
        return tag;
    }

    public static LedgerSpreadData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }


    /** これまでに台帳の広がりが付けた印の数。 */
    public long markedTotal() {
        return markedTotal;
    }



    void addPending(long chunkKey) {
        if (pending.add(chunkKey)) {
            markedTotal++;
            setDirty();
        }
    }

    boolean isPending(long chunkKey) {
        return pending.contains(chunkKey);
    }

    void removePending(long chunkKey) {
        if (pending.remove(chunkKey)) {
            setDirty();
        }
    }

    /** 書き換え待ちの数。 */
    public int pendingCount() {
        return pending.size();
    }
}
