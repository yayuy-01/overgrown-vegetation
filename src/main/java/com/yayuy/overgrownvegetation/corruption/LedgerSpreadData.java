package com.yayuy.overgrownvegetation.corruption;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 台帳の広がり({@link LedgerSpread}、2026-09-26、2026-09-28 に規則を変更)の保存データ。オーバーワールドに 1 つ。
 *
 * <ul>
 *     <li><b>判定の回</b> —— まだ判定していない回({@link #pendingPeriod})と、その回を数えている間隔の長さ({@link #periodTicks})。
 *     汚染の暦の回(暦 ÷ 間隔)がこれを越えたら、その回の判定を 1 回して 1 つ進める。フェーズ1 に入った(または更新後に初めて動いた、
 *     間隔の設定が変わった)回から始める(それより前の回をさかのぼって処理しない)。</li>
 *     <li><b>書き換え待ち</b> —— 台帳に印を付けたが、まだ世界(バイオーム・地表)を書き換えていないチャンク。
 *     読み込まれたときに {@link LedgerSpreadCatchUp} が書き換えて外す。台帳の印は別の経路(ネザーの過繁茂も同じ台帳に入る、
 *     旧セーブの拾い直し)でも付くので、<b>台帳の広がり自身が付けた印だけ</b>をここで覚える(それ以外は書き換えない)。</li>
 * </ul>
 *
 * <p>前の版のキー(2026-09-26〜27 の {@code target} / {@code lastClock}、2026-09-28 午前の {@code pendingDay})は読まない
 * (その版のセーブは次に動いた回から新しい規則で始まる)。
 */
public final class LedgerSpreadData extends SavedData {

    private static final String ID = "overgrown_vegetation_ledger_spread";

    public static final SavedData.Factory<LedgerSpreadData> FACTORY =
            new SavedData.Factory<>(LedgerSpreadData::new, (tag, registries) -> load(tag), null);

    private boolean started;
    private long pendingPeriod;
    private int periodTicks;
    private long markedTotal;
    private long lastPeriodMarked;
    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();

    private LedgerSpreadData() {
    }

    private static LedgerSpreadData load(CompoundTag tag) {
        LedgerSpreadData data = new LedgerSpreadData();
        data.started = tag.getBoolean("started") && tag.contains("pendingPeriod");
        data.pendingPeriod = tag.getLong("pendingPeriod");
        data.periodTicks = tag.getInt("periodTicks");
        data.markedTotal = tag.getLong("markedTotal");
        data.lastPeriodMarked = tag.getLong("lastPeriodMarked");
        for (long key : tag.getLongArray("pending")) {
            data.pending.add(key);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("started", started);
        tag.putLong("pendingPeriod", pendingPeriod);
        tag.putInt("periodTicks", periodTicks);
        tag.putLong("markedTotal", markedTotal);
        tag.putLong("lastPeriodMarked", lastPeriodMarked);
        tag.putLongArray("pending", pending.toLongArray());
        return tag;
    }

    public static LedgerSpreadData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** 回を数えているか(フェーズ0 の間や凍結中は止まっていて、再開するとその回から数え直す)。 */
    public boolean started() {
        return started;
    }

    /** 回を数えている間隔の長さ(tick)。設定の {@code intervalTicks} と違ったら数え直す。 */
    public int periodTicks() {
        return periodTicks;
    }

    /** まだ判定していない回。汚染の暦の回がこれより大きくなったら判定する。 */
    public long pendingPeriod() {
        return pendingPeriod;
    }

    /** これまでに台帳の広がりが付けた印の数。 */
    public long markedTotal() {
        return markedTotal;
    }

    /** 直近の判定で付けた印の数(表示用)。 */
    public long lastPeriodMarked() {
        return lastPeriodMarked;
    }

    /** 間隔 {@code intervalTicks} の {@code period} 回目から数え始める(この回の判定はまだ来ていない)。 */
    void start(long period, int intervalTicks) {
        started = true;
        pendingPeriod = period;
        periodTicks = intervalTicks;
        setDirty();
    }

    /** 数えるのをやめる(次に動くときはその回から数え直す)。 */
    void stop() {
        if (started) {
            started = false;
            setDirty();
        }
    }

    /** {@link #pendingPeriod} の判定を終えた。{@code marked} = そのとき付けた印の数。 */
    void finishPeriod(int marked) {
        pendingPeriod++;
        lastPeriodMarked = marked;
        setDirty();
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
