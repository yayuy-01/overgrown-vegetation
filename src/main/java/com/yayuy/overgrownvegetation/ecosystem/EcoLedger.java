package com.yayuy.overgrownvegetation.ecosystem;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Iterator;

/**
 * <b>生態系の台帳</b>(2026-11-16 作り直し。正本は Vault「生態系の台帳(兵站・バイオマス)」。退避した旧版 {@code parked/ecosystem} は参考だけ)。
 * オーバーワールドに 1 つの保存データ。
 *
 * <h2>升ごとの個体群</h2>
 * 升 = 2^cellShift × 2^cellShift チャンク(既定 4×4)。升ごとに生産種(G2)・消費種(G3)の仮想の頭数を持つ。満員は G2 12・G3 4【仮】。
 * <b>持つのは減った升だけ</b>(欠け = 満員から減った数と、その時刻)。満員の升は持たない。戻りは読むときに計算する(tick しない):
 * 欠けは「最後に倒された時から recoveryDays で 0 に戻る」直線(深く減った升ほど速く戻る = どの升も最後の殺しから 5 日で満員)。
 * 頭数 = max(0, 満員 − 欠け)。欠けは満員を超えて積める(頭数 0 の升で倒しても多様性は下がる)。
 *
 * <h2>多様性(森の健康)= 世界で 1 つ</h2>
 * 1 − Σ(欠け × 重み) ÷ 物差し(全体管理 11-16 決定 A: 固定の物差し。森の広さに関わらず倒した数で同じだけ下がる)。
 * 和は減った升だけを足し、1 秒に 1 回だけ数え直して取っておく。満員に戻った升はそのとき消す。
 *
 * <p>時計は {@link PhaseData#clock}(11-16「時計は 1 つ」。寝て飛ばした分も戻る)。
 * <p>保存: SavedData 1 つ(決定 E)。汚れの印は倒したときと升が戻って消えたときだけ。
 */
public final class EcoLedger extends SavedData {

    private static final String ID = "overgrown_vegetation_ecosystem";
    /** 数え直しの間(tick)。 */
    static final long RECOUNT_TICKS = 20L;

    public static final SavedData.Factory<EcoLedger> FACTORY =
            new SavedData.Factory<>(EcoLedger::new, (tag, registries) -> load(tag), null);

    /** 1 つの升(減った升だけ)。 */
    static final class Cell {
        double lack2, lack3;
        long at2, at3;
    }

    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    /** これまでに数えた殺し(G2・G3。表示用・保存する)。 */
    private long kills2, kills3;

    /** 取っておいた多様性と、数えた時刻(保存しない)。 */
    private double cachedHealth = 1.0D;
    private long cachedAt;
    /** 次に読むとき数え直す(倒した・置いた直後。保存しない)。 */
    private boolean stale = true;

    private EcoLedger() {
    }

    public static EcoLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public static EcoLedger get(ServerLevel level) {
        return get(level.getServer());
    }

    /** 世界を持たない台帳(純粋な確かめ用)。 */
    public static EcoLedger createDetachedForTests() {
        return new EcoLedger();
    }

    public static long now(ServerLevel level) {
        return PhaseData.clockNow(level);
    }

    // ---------------------------------------------------------------- 計算(純粋)

    /** 欠けの今の値: 最後に倒された時から {@code recoveryTicks} で 0 に戻る直線。 */
    public static double lackNow(double lack, long elapsed, int full, double recoveryTicks) {
        if (lack <= 0.0D) return 0.0D;
        double rate = Math.max(full, lack) / recoveryTicks;
        return Math.max(0.0D, lack - rate * Math.max(0L, elapsed));
    }

    /** 予算の貯まる速さの倍率(決定 C: floor + (1 − floor) × 多様性)。 */
    public static double incomeFactor(double health, double floor) {
        return floor + (1.0D - floor) * Math.max(0.0D, Math.min(1.0D, health));
    }

    /** 升のキー(升の座標を ChunkPos の形に詰める)。 */
    public static long cellOf(int chunkX, int chunkZ, int shift) {
        return ChunkPos.asLong(chunkX >> shift, chunkZ >> shift);
    }

    private static double recoveryTicks(EcoTable t) {
        return t.recoveryDays * 24000.0D;
    }

    // ---------------------------------------------------------------- 読む・書く

    /** 倒した: その升の頭数を 1 減らす(group は 2 か 3)。 */
    public void kill(int group, int chunkX, int chunkZ, long now) {
        EcoTable t = DesignData.ecosystem();
        long key = cellOf(chunkX, chunkZ, t.cellShift);
        Cell c = cells.get(key);
        if (c == null) {
            c = new Cell();
            cells.put(key, c);
        }
        double rt = recoveryTicks(t);
        double before = weightedLack(c, now, t);
        if (group == 3) {
            c.lack3 = lackNow(c.lack3, now - c.at3, t.fullG3, rt) + 1.0D;
            c.at3 = now;
            kills3++;
        } else {
            c.lack2 = lackNow(c.lack2, now - c.at2, t.fullG2, rt) + 1.0D;
            c.at2 = now;
            kills2++;
        }
        // 多様性はこの升の重み付きの欠けの増えた分だけ下げる(全升の数え直しは 1 秒に 1 回のまま。コードの見直し 第 2 回 ⑤8: 倒すたびに全升を数え直していた)
        double after = weightedLack(c, now, t);
        if (stale || now < cachedAt) stale = true;
        else cachedHealth = Math.max(0.0D, Math.min(1.0D, cachedHealth - (after - before) / t.scale));
        setDirty();
    }

    /** その升の今の頭数(group 2・3)。 */
    public double heads(int group, int chunkX, int chunkZ, long now) {
        EcoTable t = DesignData.ecosystem();
        Cell c = cells.get(cellOf(chunkX, chunkZ, t.cellShift));
        int full = t.full(group);
        if (c == null) return full;
        double lack = group == 3 ? lackNow(c.lack3, now - c.at3, full, recoveryTicks(t)) : lackNow(c.lack2, now - c.at2, full, recoveryTicks(t));
        return Math.max(0.0D, full - lack);
    }

    /** その升の今の重み付きの欠け(守りを出す場所を選ぶ重み)。 */
    public double weightedLack(long cellKey, long now) {
        Cell c = cells.get(cellKey);
        return c == null ? 0.0D : weightedLack(c, now, DesignData.ecosystem());
    }

    private static double weightedLack(Cell c, long now, EcoTable t) {
        double rt = recoveryTicks(t);
        return lackNow(c.lack2, now - c.at2, t.fullG2, rt) * t.weightG2 + lackNow(c.lack3, now - c.at3, t.fullG3, rt) * t.weightG3;
    }

    /** 多様性(0〜1)。1 秒に 1 回だけ数え直す。 */
    public double health(long now) {
        if (stale || now - cachedAt >= RECOUNT_TICKS || now < cachedAt) recount(now);
        return cachedHealth;
    }

    /** 数え直す: 減った升の重み付きの欠けを足し、戻りきった升を消す。 */
    public void recount(long now) {
        EcoTable t = DesignData.ecosystem();
        double sum = 0.0D;
        boolean removed = false;
        for (Iterator<Long2ObjectMap.Entry<Cell>> it = Long2ObjectMaps.fastIterator(cells); it.hasNext(); ) {
            double w = weightedLack(it.next().getValue(), now, t);
            if (w <= 0.0D) {
                it.remove();
                removed = true;
            } else {
                sum += w;
            }
        }
        if (removed) setDirty();
        cachedHealth = Math.max(0.0D, Math.min(1.0D, 1.0D - sum / t.scale));
        cachedAt = now;
        stale = false;
    }

    /** 減った升の数(数え直しで戻りきった升は消える)。 */
    public int damagedCells() {
        return cells.size();
    }

    /** 減った升を 1 つずつ(キー, 重み付きの欠け)。 */
    public void forEachDamaged(long now, java.util.function.LongPredicate filterKey, java.util.function.ObjDoubleConsumer<Long> out) {
        EcoTable t = DesignData.ecosystem();
        for (Long2ObjectMap.Entry<Cell> e : Long2ObjectMaps.fastIterable(cells)) {
            if (!filterKey.test(e.getLongKey())) continue;
            double w = weightedLack(e.getValue(), now, t);
            if (w > 0.0D) out.accept(e.getLongKey(), w);
        }
    }

    public long kills(int group) {
        return group == 3 ? kills3 : kills2;
    }

    /** 全部を満員に戻す(確かめ用のコマンド・GameTest の後片付け)。 */
    public void reset() {
        cells.clear();
        stale = true;
        setDirty();
    }

    /** 確かめ用: 升を直に置く(重さの計測・保存の確かめ)。 */
    public void putCellForTests(long key, double lack2, long at2, double lack3, long at3) {
        Cell c = new Cell();
        c.lack2 = lack2;
        c.at2 = at2;
        c.lack3 = lack3;
        c.at3 = at3;
        cells.put(key, c);
        stale = true;
        setDirty();
    }

    // ---------------------------------------------------------------- 保存

    public static EcoLedger load(CompoundTag tag) {
        EcoLedger d = new EcoLedger();
        long[] keys = tag.getLongArray("Keys");
        long[] at2 = tag.getLongArray("At2"), at3 = tag.getLongArray("At3");
        net.minecraft.nbt.ListTag l2 = tag.getList("Lack2", net.minecraft.nbt.Tag.TAG_DOUBLE), l3 = tag.getList("Lack3", net.minecraft.nbt.Tag.TAG_DOUBLE);
        int n = Math.min(Math.min(keys.length, at2.length), Math.min(at3.length, Math.min(l2.size(), l3.size())));
        for (int i = 0; i < n; i++) {
            Cell c = new Cell();
            c.lack2 = l2.getDouble(i);
            c.at2 = at2[i];
            c.lack3 = l3.getDouble(i);
            c.at3 = at3[i];
            d.cells.put(keys[i], c);
        }
        d.kills2 = tag.getLong("Kills2");
        d.kills3 = tag.getLong("Kills3");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return write(tag);
    }

    /** 保存の中身(確かめ用にも)。 */
    public CompoundTag write(CompoundTag tag) {
        int n = cells.size();
        long[] keys = new long[n], at2 = new long[n], at3 = new long[n];
        net.minecraft.nbt.ListTag l2 = new net.minecraft.nbt.ListTag(), l3 = new net.minecraft.nbt.ListTag();
        int i = 0;
        for (Long2ObjectMap.Entry<Cell> e : Long2ObjectMaps.fastIterable(cells)) {
            Cell c = e.getValue();
            keys[i] = e.getLongKey();
            at2[i] = c.at2;
            at3[i] = c.at3;
            l2.add(net.minecraft.nbt.DoubleTag.valueOf(c.lack2));
            l3.add(net.minecraft.nbt.DoubleTag.valueOf(c.lack3));
            i++;
        }
        tag.putLongArray("Keys", keys);
        tag.putLongArray("At2", at2);
        tag.putLongArray("At3", at3);
        tag.put("Lack2", l2);
        tag.put("Lack3", l3);
        tag.putLong("Kills2", kills2);
        tag.putLong("Kills3", kills3);
        return tag;
    }
}
