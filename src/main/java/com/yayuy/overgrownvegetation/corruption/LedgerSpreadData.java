package com.yayuy.overgrownvegetation.corruption;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import com.yayuy.overgrownvegetation.port.saved.OvSavedData;

/**
 * 台帳の書き換え待ち({@link LedgerSpread})の保存データ。オーバーワールドに 1 つ。2026-10-30 に止まっていた「5 分おきの縁ぬり」の回の記録
 * ({@code started}・{@code pendingPeriod}・{@code periodTicks}・{@code lastPeriodMarked})を消した(古いセーブのキーは読み飛ばす)。
 *
 * <ul>
 *     <li><b>書き換え待ち</b> —— 台帳に印を付けたが、まだ世界(バイオーム・地表)を書き換えていないチャンク。
 *     読み込まれたときに {@link LedgerSpreadCatchUp} が書き換えて外す。台帳の印は別の経路(ネザーの過繁茂も同じ台帳に入る、
 *     旧セーブの拾い直し)でも付くので、<b>台帳の広がり自身が付けた印だけ</b>をここで覚える(それ以外は書き換えない)。
 *     まだ行っていない発芽したチャンクが全部ここに入るので、台帳とほぼ同じ数になる(本物の写しで 50 万件。漏れではない。2026-11-18 の見立て)。</li>
 * </ul>
 *
 * <p>2026-11-18 大群の軽量化 B の P1: 書き換え待ちは 64 × 64 チャンクの区域ごとに持ち、区域のファイル({@link LedgerPendingStore})へ
 * <b>変わった区域だけ</b>を IO スレッドで書く。この保存データの中は {@code markedTotal} と形の印 {@code pendingFormat} だけ。
 * 前の形(中の {@code pending} の long 配列)のセーブは、読んだら全区域を書き直して移す。世界に付いていない写し(テスト)は前の形のまま中に入れる。
 */
public final class LedgerSpreadData extends OvSavedData {

    private static final String ID = "overgrown_vegetation_ledger_spread";
    /** 書き換え待ちを区域のファイルへ分けた形の印。 */
    static final int PENDING_FORMAT_REGIONS = 2;

    public static final OvSavedData.Factory<LedgerSpreadData> FACTORY =
            new OvSavedData.Factory<>(LedgerSpreadData::new, (tag, registries) -> load(tag), null);

    private long markedTotal;
    /** 区域 → その区域の書き換え待ちのチャンク。 */
    private final Long2ObjectOpenHashMap<LongOpenHashSet> byRegion = new Long2ObjectOpenHashMap<>();
    private int count;
    /** 次の保存で書く区域。 */
    private final LongOpenHashSet dirtyRegions = new LongOpenHashSet();
    /** 付いている世界(null = 世界に付いていない写し。待ちは中に入れる)。 */
    @javax.annotation.Nullable
    private transient MinecraftServer server;
    private int pendingFormat;

    private LedgerSpreadData() {
    }

    private static LedgerSpreadData load(CompoundTag tag) {
        LedgerSpreadData data = new LedgerSpreadData();
        data.markedTotal = tag.getLong("markedTotal");
        data.pendingFormat = tag.getInt("pendingFormat");
        for (long key : tag.getLongArray("pending")) { // 前の形(と世界に付いていない写し)
            data.put(key);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("markedTotal", markedTotal);
        if (server != null) {
            tag.putInt("pendingFormat", PENDING_FORMAT_REGIONS);
            flush();
        } else {
            long[] all = new long[count];
            int i = 0;
            for (LongOpenHashSet set : byRegion.values()) {
                for (long k : set) all[i++] = k;
            }
            tag.putLongArray("pending", all);
        }
        return tag;
    }

    /** 変わった区域の中身を写して(サーバースレッド。区域ごとなので小さい)、IO スレッドで書く。 */
    private void flush() {
        // 前の書き出しで書けなかった区域を書き直す(11-18 見直し ③12)
        Long failed;
        while ((failed = LedgerPendingStore.FAILED.poll()) != null) dirtyRegions.add(failed.longValue());
        if (dirtyRegions.isEmpty()) return;
        long[] regions = dirtyRegions.toLongArray();
        dirtyRegions.clear();
        List<long[]> keys = new ArrayList<>(regions.length);
        for (long r : regions) {
            LongOpenHashSet set = byRegion.get(r);
            keys.add(set == null ? new long[0] : set.toLongArray());
        }
        LedgerPendingStore.write(server, regions, keys);
    }

    public static LedgerSpreadData get(MinecraftServer server) {
        LedgerSpreadData data = server.overworld().getDataStorage().computeIfAbsent(FACTORY.load(), FACTORY.constructor(), ID);
        if (data.server != server) data.attach(server);
        return data;
    }

    /** 世界に付ける: 区域のファイルを読む。前の形(中に入っていた)なら、全区域を書き直して移す。 */
    private void attach(MinecraftServer server) {
        this.server = server;
        // 区域の形の印が無くても、待ちが空で区域のファイルがあるなら、前の版の MOD で一度開いて保存された(前の版は区域のファイルを知らず、
        // 空の待ちを書く)と見て、ファイルから読む
        boolean roundTripped = pendingFormat != PENDING_FORMAT_REGIONS && count == 0
                && java.nio.file.Files.isDirectory(LedgerPendingStore.dir(server));
        if (pendingFormat == PENDING_FORMAT_REGIONS || roundTripped) {
            long t0 = System.nanoTime();
            int n = 0;
            for (long[] part : LedgerPendingStore.loadAll(server)) {
                for (long k : part) put(k);
                n += part.length;
            }
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("台帳の書き換え待ちを区域のファイルから読みました: {} チャンク・{} ms",
                    n, (System.nanoTime() - t0) / 1_000_000L);
        } else if (count > 0) {
            dirtyRegions.addAll(byRegion.keySet());
            setDirty();
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("台帳の書き換え待ち {} チャンクを区域のファイルへ移します(前の形のセーブ)", count);
        }
    }

    private boolean put(long key) {
        long r = LedgerPendingStore.regionOf(key);
        LongOpenHashSet set = byRegion.get(r);
        if (set == null) {
            set = new LongOpenHashSet();
            byRegion.put(r, set);
        }
        if (!set.add(key)) return false;
        count++;
        return true;
    }

    /** これまでに台帳の広がりが付けた印の数。 */
    public long markedTotal() {
        return markedTotal;
    }

    void addPending(long chunkKey) {
        if (put(chunkKey)) {
            markedTotal++;
            dirtyRegions.add(LedgerPendingStore.regionOf(chunkKey));
            setDirty();
        }
    }

    boolean isPending(long chunkKey) {
        LongOpenHashSet set = byRegion.get(LedgerPendingStore.regionOf(chunkKey));
        return set != null && set.contains(chunkKey);
    }

    public void removePending(long chunkKey) {
        long r = LedgerPendingStore.regionOf(chunkKey);
        LongOpenHashSet set = byRegion.get(r);
        if (set != null && set.remove(chunkKey)) {
            count--;
            if (set.isEmpty()) byRegion.remove(r);
            dirtyRegions.add(r);
            setDirty();
        }
    }

    /** 書き換え待ちの数。 */
    public int pendingCount() {
        return count;
    }

    /** 確かめ用: 書き換え待ちの鍵を全部(並びは決まらない)。 */
    long[] pendingKeysForTests() {
        long[] all = new long[count];
        int i = 0;
        for (Long2ObjectMap.Entry<LongOpenHashSet> e : byRegion.long2ObjectEntrySet()) {
            for (long k : e.getValue()) all[i++] = k;
        }
        return all;
    }

    /** 確かめ用: 区域のファイルの書き出しが終わるのを待つ。 */
    static void awaitWritesForTests() {
        LedgerPendingStore.await();
    }
}
