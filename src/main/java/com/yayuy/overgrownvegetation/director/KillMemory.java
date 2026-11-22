package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.KillMemoryTable;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>倒された記録</b>(2026-11-14、司令塔 第 3 段 区切り 1「学ぶ」)。オーバーワールドに 1 つの保存データ。
 *
 * <p>プレイヤー(と、その飛び道具・飼い犬など)に倒された G1(段階 0・1 段目・根付き・苗床・根の守り手)を、升(チャンク)ごとに数える。
 * 数は半減期 {@code halfLifeDays}(11-14 ユーザー: 1 日)で薄れる。まわり 3 × 3 升の数の和が、その升の「危ない」しきい値
 * (11-14 ユーザー「10〜13 体倒されれば」= 升ごとに 10〜13 のどれか。升の座標から決まり、変わらない)を超えたら「危ない所」。
 * 日はワールドの日付(拠点の記憶と同じ)。中身の計算は世界を見ない(テストは {@link #createForTests})。
 */
public final class KillMemory extends SavedData {

    private static final String ID = "overgrown_vegetation_kill_memory";

    public static final SavedData.Factory<KillMemory> FACTORY =
            new SavedData.Factory<>(KillMemory::new, (tag, registries) -> load(tag), null);

    /** 1 つの升の記録。 */
    private static final class Cell {
        double count;
        double at;
    }

    /** 危ない所 1 つ(数え直しのたびに作る)。 */
    public record Danger(long chunk, double x, double z, double kills, int threshold) {
    }

    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private long recorded;

    private KillMemory() {
    }

    public static KillMemory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public static KillMemory createForTests() {
        return new KillMemory();
    }

    private static KillMemoryTable table() {
        return DesignData.director().kills;
    }

    /** その升の「危ない」しきい値(升の座標から決まる。10〜13)。 */
    public static int threshold(long chunk) {
        KillMemoryTable t = table();
        int span = Math.max(0, t.dangerMaxKills - t.dangerMinKills) + 1;
        long h = chunk * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        return t.dangerMinKills + (int) Math.floorMod(h, span);
    }

    private static double decayed(Cell c, double day) {
        return c.count * Math.pow(0.5D, Math.max(0.0D, day - c.at) / table().halfLifeDays);
    }

    /** 1 体倒された。 */
    public void record(long chunk, double day) {
        Cell c = cells.get(chunk);
        if (c == null) {
            c = new Cell();
            cells.put(chunk, c);
        } else {
            c.count = decayed(c, day);
        }
        c.at = day;
        c.count += 1.0D;
        recorded++;
        setDirty();
    }

    /** まわり 3 × 3 升の、薄れた後の数の和。 */
    public double killsAround(long chunk, double day) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        double sum = 0.0D;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Cell c = cells.get(ChunkPos.asLong(cx + dx, cz + dz));
                if (c != null) sum += decayed(c, day);
            }
        }
        return sum;
    }

    /**
     * 今の危ない所。しきい値を超えた升のうち、まわり {@code mergeChunks} の中でいちばん数の多い升だけを残す(同じ戦場を 1 つに数える)。
     * 薄れきった升(0.5 体未満)はここで捨てる。
     */
    public List<Danger> dangers(double day) {
        KillMemoryTable t = table();
        cells.long2ObjectEntrySet().removeIf(e -> decayed(e.getValue(), day) < 0.5D);
        List<Danger> hot = new ArrayList<>();
        for (long k : cells.keySet()) {
            double kills = killsAround(k, day);
            int th = threshold(k);
            if (kills >= th) {
                hot.add(new Danger(k, ChunkPos.getX(k) * 16 + 8.0D, ChunkPos.getZ(k) * 16 + 8.0D, kills, th));
            }
        }
        hot.sort((a, b) -> Double.compare(b.kills(), a.kills()));
        List<Danger> out = new ArrayList<>();
        int m = t.mergeChunks;
        for (Danger d : hot) {
            boolean near = false;
            for (Danger o : out) {
                if (Math.abs(ChunkPos.getX(o.chunk()) - ChunkPos.getX(d.chunk())) <= m
                        && Math.abs(ChunkPos.getZ(o.chunk()) - ChunkPos.getZ(d.chunk())) <= m) {
                    near = true;
                    break;
                }
            }
            if (!near) out.add(d);
        }
        return out;
    }

    /** いちばん近い危ない所(真ん中まで {@code within} ブロック以内)。 */
    @Nullable
    public Danger nearest(double x, double z, double within, double day) {
        Danger best = null;
        double bestD = within * within;
        for (Danger d : dangers(day)) {
            double dx = d.x() - x, dz = d.z() - z, d2 = dx * dx + dz * dz;
            if (d2 <= bestD) {
                bestD = d2;
                best = d;
            }
        }
        return best;
    }

    /** 危ない所への近さ(0〜1。真ん中で 1、{@code within} で 0)。 */
    public double near(double x, double z, double within, double day) {
        Danger d = nearest(x, z, within, day);
        if (d == null) return 0.0D;
        return Math.max(0.0D, 1.0D - Math.sqrt((d.x() - x) * (d.x() - x) + (d.z() - z) * (d.z() - z)) / within);
    }

    public long recordedCount() {
        return recorded;
    }

    public List<String> describe(double day) {
        List<String> out = new ArrayList<>();
        List<Danger> ds = dangers(day);
        out.add(String.format(Locale.ROOT, "倒された記録: 升 %d・これまで %d 体・危ない所 %d(半減期 %.1f 日・しきい値 %d〜%d 体)",
                cells.size(), recorded, ds.size(), table().halfLifeDays, table().dangerMinKills, table().dangerMaxKills));
        for (Danger d : ds) {
            out.add(String.format(Locale.ROOT, "  危ない所 (%.0f, %.0f): まわりで %.1f 体(しきい値 %d)", d.x(), d.z(), d.kills(), d.threshold()));
        }
        return out;
    }

    /** 確かめ用: その升に n 体倒されたことにする。 */
    public void addForTests(long chunk, int n, double day) {
        for (int i = 0; i < n; i++) record(chunk, day);
    }

    public void clear() {
        cells.clear();
        setDirty();
    }

    // ---------------------------------------------------------------- 保存

    private static KillMemory load(CompoundTag tag) {
        KillMemory m = new KillMemory();
        m.recorded = tag.getLong("Recorded");
        for (Tag e : tag.getList("Cells", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) e;
            Cell cell = new Cell();
            cell.count = c.getDouble("N");
            cell.at = c.getDouble("At");
            m.cells.put(c.getLong("K"), cell);
        }
        return m;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("Recorded", recorded);
        ListTag list = new ListTag();
        for (var en : cells.long2ObjectEntrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("K", en.getLongKey());
            c.putDouble("N", en.getValue().count);
            c.putDouble("At", en.getValue().at);
            list.add(c);
        }
        tag.put("Cells", list);
        return tag;
    }

    public KillMemory roundTripForTests() {
        return load(save(new CompoundTag(), null));
    }
}
