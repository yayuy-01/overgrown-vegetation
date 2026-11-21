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
 * 升 = 2^cellShift × 2^cellShift チャンク(既定 4×4)。升ごとに生産種(獲物の環境生物)・消費種(狩る側の環境生物)の仮想の頭数を持つ。満員は獲物の環境生物 12・狩る側の環境生物 4【仮】。
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
        double lackPrey, lackHunter;
        long atPrey, atHunter;
    }

    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    /** これまでに数えた殺し(環境生物。表示用・保存する)。 */
    private long killsPrey, killsHunter;

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

    /** 倒した: その升の頭数を 1 減らす(kind は Ecosystem.PREY か HUNTER)。 */
    public void kill(int kind, int chunkX, int chunkZ, long now) {
        EcoTable t = DesignData.ecosystem();
        long key = cellOf(chunkX, chunkZ, t.cellShift);
        Cell c = cells.get(key);
        if (c == null) {
            c = new Cell();
            cells.put(key, c);
        }
        double rt = recoveryTicks(t);
        double before = weightedLack(c, now, t);
        if (kind == Ecosystem.HUNTER) {
            c.lackHunter = lackNow(c.lackHunter, now - c.atHunter, t.fullHunter, rt) + 1.0D;
            c.atHunter = now;
            killsHunter++;
        } else {
            c.lackPrey = lackNow(c.lackPrey, now - c.atPrey, t.fullPrey, rt) + 1.0D;
            c.atPrey = now;
            killsPrey++;
        }
        // 多様性はこの升の重み付きの欠けの増えた分だけ下げる(全升の数え直しは 1 秒に 1 回のまま。コードの見直し 第 2 回 ⑤8: 倒すたびに全升を数え直していた)
        double after = weightedLack(c, now, t);
        if (stale || now < cachedAt) stale = true;
        else cachedHealth = Math.max(0.0D, Math.min(1.0D, cachedHealth - (after - before) / t.scale));
        setDirty();
    }

    /** その升の今の頭数(kind = Ecosystem.PREY・HUNTER)。 */
    public double heads(int kind, int chunkX, int chunkZ, long now) {
        EcoTable t = DesignData.ecosystem();
        Cell c = cells.get(cellOf(chunkX, chunkZ, t.cellShift));
        int full = t.full(kind);
        if (c == null) return full;
        double lack = kind == Ecosystem.HUNTER ? lackNow(c.lackHunter, now - c.atHunter, full, recoveryTicks(t)) : lackNow(c.lackPrey, now - c.atPrey, full, recoveryTicks(t));
        return Math.max(0.0D, full - lack);
    }

    /** その升の今の重み付きの欠け(守りを出す場所を選ぶ重み)。 */
    public double weightedLack(long cellKey, long now) {
        Cell c = cells.get(cellKey);
        return c == null ? 0.0D : weightedLack(c, now, DesignData.ecosystem());
    }

    private static double weightedLack(Cell c, long now, EcoTable t) {
        double rt = recoveryTicks(t);
        return lackNow(c.lackPrey, now - c.atPrey, t.fullPrey, rt) * t.weightPrey + lackNow(c.lackHunter, now - c.atHunter, t.fullHunter, rt) * t.weightHunter;
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

    public long kills(int kind) {
        return kind == Ecosystem.HUNTER ? killsHunter : killsPrey;
    }

    /** 全部を満員に戻す(確かめ用のコマンド・GameTest の後片付け)。 */
    public void reset() {
        cells.clear();
        stale = true;
        setDirty();
    }

    /** 確かめ用: 升を直に置く(重さの計測・保存の確かめ)。 */
    public void putCellForTests(long key, double lackPrey, long atPrey, double lackHunter, long atHunter) {
        Cell c = new Cell();
        c.lackPrey = lackPrey;
        c.atPrey = atPrey;
        c.lackHunter = lackHunter;
        c.atHunter = atHunter;
        cells.put(key, c);
        stale = true;
        setDirty();
    }

    // ---------------------------------------------------------------- 保存

    public static EcoLedger load(CompoundTag tag) {
        EcoLedger d = new EcoLedger();
        long[] keys = tag.getLongArray("Keys");
        // 2026-11-21 に保存のキーを旧 G2・G3 の番号(At2・Lack2・Kills2 など)から Prey・Hunter へ付け替えた。古いセーブは古いキーで読む
        long[] atPrey = tag.getLongArray(key(tag, "AtPrey", "At2")), atHunter = tag.getLongArray(key(tag, "AtHunter", "At3"));
        net.minecraft.nbt.ListTag lPrey = tag.getList(key(tag, "LackPrey", "Lack2"), net.minecraft.nbt.Tag.TAG_DOUBLE), lHunter = tag.getList(key(tag, "LackHunter", "Lack3"), net.minecraft.nbt.Tag.TAG_DOUBLE);
        int n = Math.min(Math.min(keys.length, atPrey.length), Math.min(atHunter.length, Math.min(lPrey.size(), lHunter.size())));
        for (int i = 0; i < n; i++) {
            Cell c = new Cell();
            c.lackPrey = lPrey.getDouble(i);
            c.atPrey = atPrey[i];
            c.lackHunter = lHunter.getDouble(i);
            c.atHunter = atHunter[i];
            d.cells.put(keys[i], c);
        }
        d.killsPrey = tag.getLong(key(tag, "KillsPrey", "Kills2"));
        d.killsHunter = tag.getLong(key(tag, "KillsHunter", "Kills3"));
        return d;
    }

    /** 新しいキーがあればそれ、無ければ古いキー(旧番号の保存)。 */
    private static String key(CompoundTag tag, String now, String old) {
        return tag.contains(now) ? now : old;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return write(tag);
    }

    /** 保存の中身(確かめ用にも)。 */
    public CompoundTag write(CompoundTag tag) {
        int n = cells.size();
        long[] keys = new long[n], atPrey = new long[n], atHunter = new long[n];
        net.minecraft.nbt.ListTag lPrey = new net.minecraft.nbt.ListTag(), lHunter = new net.minecraft.nbt.ListTag();
        int i = 0;
        for (Long2ObjectMap.Entry<Cell> e : Long2ObjectMaps.fastIterable(cells)) {
            Cell c = e.getValue();
            keys[i] = e.getLongKey();
            atPrey[i] = c.atPrey;
            atHunter[i] = c.atHunter;
            lPrey.add(net.minecraft.nbt.DoubleTag.valueOf(c.lackPrey));
            lHunter.add(net.minecraft.nbt.DoubleTag.valueOf(c.lackHunter));
            i++;
        }
        tag.putLongArray("Keys", keys);
        tag.putLongArray("AtPrey", atPrey);
        tag.putLongArray("AtHunter", atHunter);
        tag.put("LackPrey", lPrey);
        tag.put("LackHunter", lHunter);
        tag.putLong("KillsPrey", killsPrey);
        tag.putLong("KillsHunter", killsHunter);
        return tag;
    }
}
