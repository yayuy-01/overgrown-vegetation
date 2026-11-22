package com.yayuy.overgrownvegetation.survival.room;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirtSpec;
import com.yayuy.overgrownvegetation.data.VentSpec;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import com.yayuy.overgrownvegetation.survival.vent.VentBlockEntity;
import com.yayuy.overgrownvegetation.survival.vent.Ventilators;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;

/**
 * <b>部屋に積もった汚れ</b>(2026-11-14 サバイバルの背骨 段 3c、決定 32 の 2)。次元ごとの保存データ。
 *
 * <p>汚れは「置いた場所(部屋の中の空気のマス)ごとの量」で持つ。部屋の上乗せの濃度 = 部屋の中のマスにある量の和 ÷ 部屋の広さ(上限 {@code dirt.roomMaxPercent})。
 * 部屋の印(いちばん小さい座標)で持たないのは、奥のドアを開けて 2 つの部屋がつながったとき・分かれたときに、汚れがそのまま混ざって薄まり、
 * 分かれれば置いた側に残るようにするため(玄関の前室だけが汚れ、奥の部屋はきれいなまま)。
 *
 * <p>量は 1 秒ごとに半減期 {@code roomHalfLifeSeconds} で減る(持ち主が出ても、しばらく残る【仮】)。稼働中の換気機の範囲では
 * {@code roomVentHalfLifeSeconds} で速く減り、抜いた分だけその換気機のフィルターが詰まる(換気の機械で押し返せる)。
 */
public final class RoomDirt extends SavedData {

    private static final String NAME = OvergrownVegetation.MODID + "_room_dirt";
    /** これより少ない量は消す(量は「% × マス」)。 */
    static final double EPSILON = 0.01D;
    /** 持つ場所の上限(超えたら少ない物から捨てる)。 */
    static final int MAX_DEPOSITS = 4096;

    final Long2DoubleOpenHashMap mass = new Long2DoubleOpenHashMap();
    /** フィルターへの詰まりの端数(%秒。保存しない)。 */
    private double filterCarry;

    public static RoomDirt get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(RoomDirt::new, RoomDirt::load, null), NAME);
    }

    /**
     * 汚した部屋の箱(中の空気のマスの外接箱 minX..maxZ)。部屋を調べる前の安い確かめはこの箱の中だけ(基地の下の洞窟の Mob の読みで
     * 部屋の判定を走らせないように)。中に汚れが無くなった箱は捨てる。保存する(読み込み直後から読めるように)。
     */
    final java.util.List<int[]> boxes = new java.util.ArrayList<>();
    /** 持つ箱の上限。 */
    static final int MAX_BOXES = 256;

    private void addBox(RoomScan room) {
        int[] b = {room.minX(), room.minY(), room.minZ(), room.maxX(), room.maxY(), room.maxZ()};
        for (int[] o : boxes) if (java.util.Arrays.equals(o, b)) return;
        boxes.add(b);
        if (boxes.size() > MAX_BOXES) boxes.remove(0);
    }

    private static boolean inBox(int[] b, int x, int y, int z) {
        return x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
    }

    /** 汚れが 1 つも入っていない箱を捨てる。 */
    private void pruneBoxes() {
        boxes.removeIf(b -> {
            for (long k : mass.keySet()) if (inBox(b, BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k))) return false;
            return true;
        });
    }

    /** 読み込まずに、その次元に汚れがあるか(無ければ作らない)。 */
    static RoomDirt peek(ServerLevel level) {
        return level.getDataStorage().get(new SavedData.Factory<>(RoomDirt::new, RoomDirt::load, null), NAME);
    }

    static DirtSpec spec() {
        return DesignData.sporeField().dirt();
    }

    public boolean isEmpty() {
        return mass.isEmpty();
    }

    /** {@code pos} が汚した部屋の箱(± {@code margin})の中か(部屋を調べる前の安い確かめ)。 */
    public boolean anyNear(BlockPos pos, int margin) {
        if (mass.isEmpty()) return false;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (int[] b : boxes) {
            if (x >= b[0] - margin && x <= b[3] + margin && y >= b[1] - margin && y <= b[4] + margin && z >= b[2] - margin && z <= b[5] + margin) return true;
        }
        return false;
    }

    /** 部屋の中の量の和(% × マス)。 */
    public double massIn(RoomScan room) {
        if (mass.isEmpty() || room == null) return 0.0D;
        double sum = 0.0D;
        for (Long2DoubleMap.Entry e : mass.long2DoubleEntrySet()) {
            if (room.cells().contains(e.getLongKey())) sum += e.getDoubleValue();
        }
        return sum;
    }

    /** 部屋の上乗せの濃度(%)。 */
    public double percentIn(RoomScan room) {
        if (room == null || room.volume() <= 0) return 0.0D;
        return Math.min(spec().roomMaxPercent(), massIn(room) / room.volume());
    }

    /** 部屋の中の {@code cell} に、濃度で {@code percent} 分を足す(上限まで)。 */
    public void add(RoomScan room, long cell, double percent) {
        if (room == null || percent <= 0.0D || room.volume() <= 0) return;
        double room0 = massIn(room) / room.volume();
        double room1 = Math.min(spec().roomMaxPercent(), room0 + percent);
        if (room1 <= room0) return;
        mass.addTo(cell, (room1 - room0) * room.volume());
        addBox(room);
        if (mass.size() > MAX_DEPOSITS) dropSmallest();
        setDirty();
    }

    private void dropSmallest() {
        long worst = 0;
        double min = Double.MAX_VALUE;
        for (Long2DoubleMap.Entry e : mass.long2DoubleEntrySet()) {
            if (e.getDoubleValue() < min) {
                min = e.getDoubleValue();
                worst = e.getLongKey();
            }
        }
        mass.remove(worst);
    }

    /**
     * {@code seconds} 秒ぶん減らす(1 秒ごとに呼ぶ。GameTest からも)。稼働中の換気機の範囲の汚れは速く減り、抜いた分だけその機械のフィルターが詰まる。
     */
    public void decay(ServerLevel level, double seconds) {
        if (mass.isEmpty() || seconds <= 0.0D) return;
        DirtSpec s = spec();
        VentSpec vs = DesignData.sporeField().vent();
        List<VentBlockEntity> vents = Ventilators.running(level);
        double slow = Math.pow(0.5D, seconds / s.roomHalfLifeSeconds());
        double fast = Math.pow(0.5D, seconds / s.roomVentHalfLifeSeconds());
        int removed = 0;
        for (ObjectIterator<Long2DoubleMap.Entry> it = mass.long2DoubleEntrySet().fastIterator(); it.hasNext(); ) {
            Long2DoubleMap.Entry e = it.next();
            long k = e.getLongKey();
            VentBlockEntity vent = vents.isEmpty() ? null : coveringVent(vents, k, vs);
            double before = e.getDoubleValue();
            double after = before * (vent != null ? fast : slow);
            if (vent != null) {
                // 抜いた量(% × マス)を基準の広さの部屋の % に直して、フィルターを詰まらせる
                filterCarry += (before - after) / s.roomRefVolume() * s.roomVentFilterLoadPerPercent();
                int whole = (int) filterCarry;
                if (whole > 0 && !vent.filter().isEmpty()) {
                    filterCarry -= whole;
                    AirFilters.absorb(vent.filter(), whole);
                    vent.setChanged();
                }
            }
            if (after < EPSILON) {
                it.remove();
                removed++;
                continue;
            }
            e.setValue(after);
        }
        // 箱の見直しは汚れが消えたときだけ(2026-11-16 コードの見直し ③13: 前は毎秒、箱 × 汚れのマスを全部見直していた。最悪 256 × 4096 で約 60 ms)
        if (removed > 0) pruneBoxes();
        setDirty();
    }

    /** {@code cell} を範囲に入れている稼働中の換気機(チャンクの中心どうしの距離。無ければ null)。 */
    static VentBlockEntity coveringVent(List<VentBlockEntity> vents, long cell, VentSpec vs) {
        int cx = BlockPos.getX(cell) >> 4, cz = BlockPos.getZ(cell) >> 4;
        double r2 = vs.radiusChunks() * vs.radiusChunks();
        for (VentBlockEntity be : vents) {
            int dx = (be.getBlockPos().getX() >> 4) - cx, dz = (be.getBlockPos().getZ() >> 4) - cz;
            if (dx * dx + dz * dz <= r2) return be;
        }
        return null;
    }

    /** 稼働中の換気機の範囲か(開発用の表示)。 */
    public static boolean ventCovers(ServerLevel level, BlockPos pos) {
        List<VentBlockEntity> vents = Ventilators.running(level);
        return !vents.isEmpty() && coveringVent(vents, pos.asLong(), DesignData.sporeField().vent()) != null;
    }

    /**
     * 計測用(2026-11-16 コードの見直し ③13): 汚れ {@code deposits} 個・汚れの入っていない箱 {@code boxCount} 個(箱の見直しの最悪)で
     * {@link #decay} 1 回にかかる時間(ns)。保存はしない。
     */
    public static long benchDecayForTests(ServerLevel level, int deposits, int boxCount) {
        RoomDirt d = new RoomDirt();
        for (int i = 0; i < deposits; i++) d.mass.put(BlockPos.asLong(i, 1000, 0), 1000.0D);
        for (int i = 0; i < boxCount; i++) d.boxes.add(new int[] {-10 - i, -500, -10, -5 - i, -490, -5});
        long t0 = System.nanoTime();
        d.decay(level, 1.0D);
        return System.nanoTime() - t0;
    }

    /** 全部消す(開発用・GameTest)。 */
    public void clearAll() {
        mass.clear();
        boxes.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Long2DoubleMap.Entry e : mass.long2DoubleEntrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("p", e.getLongKey());
            c.putDouble("m", e.getDoubleValue());
            list.add(c);
        }
        tag.put("deposits", list);
        ListTag bl = new ListTag();
        for (int[] b : boxes) bl.add(new net.minecraft.nbt.IntArrayTag(b));
        tag.put("rooms", bl);
        return tag;
    }

    public static RoomDirt load(CompoundTag tag, HolderLookup.Provider registries) {
        RoomDirt d = new RoomDirt();
        ListTag list = tag.getList("deposits", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            double m = c.getDouble("m");
            if (m >= EPSILON && Double.isFinite(m)) d.mass.put(c.getLong("p"), m);
        }
        ListTag bl = tag.getList("rooms", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < bl.size(); i++) {
            int[] b = bl.getIntArray(i);
            if (b.length == 6) d.boxes.add(b);
        }
        return d;
    }

    /** 1 秒ごとに減らす。 */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Ticker {
        private Ticker() {
        }

        @SubscribeEvent
        public static void onLevelTick(LevelTickEvent.Post event) {
            if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 20 != 0) return;
            RoomDirt d = peek(level);
            if (d != null && !d.isEmpty()) d.decay(level, 1.0D);
        }
    }
}
