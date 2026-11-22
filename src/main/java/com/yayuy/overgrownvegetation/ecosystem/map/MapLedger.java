package com.yayuy.overgrownvegetation.ecosystem.map;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 地図の上の生態系の台帳(2026-11-21)。オーバーワールドに 1 つの保存データ。升は生態系の台帳({@code EcoLedger})と同じ 4×4 チャンク。
 * <b>持つのは触った升だけ</b>(感染が進んだ・森の外で殺された・過繁茂にした升)。手つかずの升は満員とみなし、持たない。
 *
 * <p>升ごと: 満員(作ったときの動物・村人の数。後で設計値を変えても作り直さない)・感染した数(端数あり)・欠け(殺された数。森の外では
 * {@link MapTable#lackRecoveryDays} で戻る)・実物に反映済みの感染の数・過繁茂の状態。
 * 健康な数 = max(0, 満員 − 感染 − 欠け)。感染の割合 = 感染 ÷ 満員。
 */
public final class MapLedger extends SavedData {

    private static final String ID = "overgrown_vegetation_map_ecosystem";

    public static final SavedData.Factory<MapLedger> FACTORY =
            new SavedData.Factory<>(MapLedger::new, (tag, registries) -> load(tag), null);

    /** 過繁茂の状態: まだ・印を付けた(全部)・一部が断られて試し直し中(知らせは出した)。 */
    public static final byte NONE = 0, DONE = 1, PARTIAL = 2;

    /** 1 つの升。 */
    public static final class Cell {
        public int animals, villagers;
        public double infected;
        double lack;
        long lackAt;
        /** 実物に反映済みの感染の数(照らし合わせの基準)。 */
        public int synced;
        public byte state;

        public int full() {
            return animals + villagers;
        }
    }

    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    /** これまでの感染(地図の上・実物)の数(表示用・保存)。 */
    long infectedMap, infectedReal, overgrownCells;

    private MapLedger() {
    }

    public static MapLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** 世界を持たない台帳(純粋な確かめ用)。 */
    public static MapLedger createDetachedForTests() {
        return new MapLedger();
    }

    // ---------------------------------------------------------------- 計算(純粋)

    /** 欠けの今の値: 最後に減った時から recoveryTicks で 0 に戻る直線。recoveryTicks ≤ 0 なら戻らない。 */
    public static double lackNow(double lack, long elapsed, int full, double recoveryTicks) {
        if (lack <= 0.0D) return 0.0D;
        if (recoveryTicks <= 0.0D) return lack;
        double rate = Math.max(1, full) / recoveryTicks;
        return Math.max(0.0D, lack - rate * Math.max(0L, elapsed));
    }

    public static double lackNow(Cell c, long now, double recoveryTicks) {
        return lackNow(c.lack, now - c.lackAt, c.full(), recoveryTicks);
    }

    public static double healthy(Cell c, long now, double recoveryTicks) {
        return Math.max(0.0D, c.full() - c.infected - lackNow(c, now, recoveryTicks));
    }

    public static double ratio(Cell c) {
        return c.full() <= 0 ? 0.0D : Math.min(1.0D, c.infected / c.full());
    }

    // ---------------------------------------------------------------- 読む・書く

    public Cell get(long key) {
        return cells.get(key);
    }

    /** 升を作る(無ければ。満員は {@code info} から)。 */
    public Cell getOrCreate(long key, MapCapacity.Info info) {
        Cell c = cells.get(key);
        if (c == null) {
            c = new Cell();
            c.animals = info.animals();
            c.villagers = info.villagers();
            cells.put(key, c);
            setDirty();
        }
        return c;
    }

    /** 殺された(森の外の死): 欠けを 1 増やす(健康な数を超えない)。 */
    public void addLack(Cell c, double n, long now, double recoveryTicks) {
        double l = lackNow(c, now, recoveryTicks);
        c.lack = Math.min(Math.max(0.0D, c.full() - c.infected), l + n);
        c.lackAt = now;
        setDirty();
    }

    /** 生まれた: 欠けを 1 減らす。 */
    public void reduceLack(Cell c, double n, long now, double recoveryTicks) {
        double l = lackNow(c, now, recoveryTicks);
        if (l <= 0.0D) return;
        c.lack = Math.max(0.0D, l - n);
        c.lackAt = now;
        setDirty();
    }

    /** 戻りきった・何も起きていない升を消す(保存を小さく)。 */
    public void dropIfIdle(long key, Cell c, long now, double recoveryTicks) {
        if (c.infected <= 0.0D && c.state == NONE && c.synced == 0 && lackNow(c, now, recoveryTicks) <= 0.0D) {
            cells.remove(key);
            setDirty();
        }
    }

    public int size() {
        return cells.size();
    }

    public Iterable<Long2ObjectMap.Entry<Cell>> entries() {
        return Long2ObjectMaps.fastIterable(cells);
    }

    public long infectedMap() {
        return infectedMap;
    }

    public long infectedReal() {
        return infectedReal;
    }

    public long overgrownCells() {
        return overgrownCells;
    }

    public void reset() {
        cells.clear();
        infectedMap = infectedReal = overgrownCells = 0;
        setDirty();
    }

    /** 確かめ用: 升を直に置く(重さの計測・保存の確かめ)。 */
    public Cell putForTests(long key, int animals, int villagers, double infected, double lack, long lackAt, int synced, byte state) {
        Cell c = new Cell();
        c.animals = animals;
        c.villagers = villagers;
        c.infected = infected;
        c.lack = lack;
        c.lackAt = lackAt;
        c.synced = synced;
        c.state = state;
        cells.put(key, c);
        setDirty();
        return c;
    }

    void markDirty() {
        setDirty();
    }

    // ---------------------------------------------------------------- 保存

    public static MapLedger load(CompoundTag tag) {
        MapLedger d = new MapLedger();
        long[] keys = tag.getLongArray("Keys");
        int[] full = tag.getIntArray("Full"); // 動物 << 16 | 村人
        long[] inf = tag.getLongArray("Infected"), lack = tag.getLongArray("Lack"), lackAt = tag.getLongArray("LackAt");
        int[] synced = tag.getIntArray("Synced");
        byte[] state = tag.getByteArray("State");
        int n = Math.min(Math.min(keys.length, full.length), Math.min(Math.min(inf.length, lack.length), Math.min(lackAt.length, Math.min(synced.length, state.length))));
        for (int i = 0; i < n; i++) {
            Cell c = new Cell();
            c.animals = full[i] >>> 16;
            c.villagers = full[i] & 0xFFFF;
            c.infected = Double.longBitsToDouble(inf[i]);
            c.lack = Double.longBitsToDouble(lack[i]);
            c.lackAt = lackAt[i];
            c.synced = synced[i];
            c.state = state[i];
            d.cells.put(keys[i], c);
        }
        d.infectedMap = tag.getLong("InfectedMap");
        d.infectedReal = tag.getLong("InfectedReal");
        d.overgrownCells = tag.getLong("OvergrownCells");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return write(tag);
    }

    /** 保存の中身(確かめ用にも)。 */
    public CompoundTag write(CompoundTag tag) {
        int n = cells.size();
        long[] keys = new long[n], inf = new long[n], lack = new long[n], lackAt = new long[n];
        int[] full = new int[n], synced = new int[n];
        byte[] state = new byte[n];
        int i = 0;
        for (Long2ObjectMap.Entry<Cell> e : Long2ObjectMaps.fastIterable(cells)) {
            Cell c = e.getValue();
            keys[i] = e.getLongKey();
            full[i] = (Math.min(0xFFFF, c.animals) << 16) | Math.min(0xFFFF, c.villagers);
            inf[i] = Double.doubleToLongBits(c.infected);
            lack[i] = Double.doubleToLongBits(c.lack);
            lackAt[i] = c.lackAt;
            synced[i] = c.synced;
            state[i] = c.state;
            i++;
        }
        tag.putLongArray("Keys", keys);
        tag.putIntArray("Full", full);
        tag.putLongArray("Infected", inf);
        tag.putLongArray("Lack", lack);
        tag.putLongArray("LackAt", lackAt);
        tag.putIntArray("Synced", synced);
        tag.putByteArray("State", state);
        tag.putLong("InfectedMap", infectedMap);
        tag.putLong("InfectedReal", infectedReal);
        tag.putLong("OvergrownCells", overgrownCells);
        return tag;
    }
}
