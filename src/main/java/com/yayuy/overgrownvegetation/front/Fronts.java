package com.yayuy.overgrownvegetation.front;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>前線の台帳</b>(2026-11-21 前線と部隊。正本は Vault「前線と部隊(地図の上の森の軍勢)(11-21)」)。
 * 前線は巨大樹ごと。巨大樹がまだ無い序盤は、汚染の発生源が前線になる(生産の場所・視界なし)。最初の巨大樹ができたら、発生源の前線の部隊は引き継ぐ。
 * 部隊そのものは仮想の群れの台帳({@code horde/VirtualHordes})に置き、前線の id で結ぶ。ここは前線の場所・攻められた度合い・作る途中の端数だけ。
 */
public final class Fronts extends SavedData {

    public static final String ID = OvergrownVegetation.MODID + "_fronts";
    public static final SavedData.Factory<Fronts> FACTORY = new SavedData.Factory<>(Fronts::new, (tag, registries) -> load(tag), null);

    /** 前線の種類。 */
    public enum Kind {
        /** 汚染の発生源(巨大樹がまだ無い)。 */
        SOURCE,
        /** 巨大樹(ハイヴノード)。 */
        TREE
    }

    /** 前線 1 つ。 */
    public static final class Front {
        final int id;
        final Kind kind;
        /** 巨大樹の中心({@code (x << 32) | (z & 0xFFFFFFFF)})。発生源なら Long.MIN_VALUE。 */
        final long treeKey;
        final double x, z;
        /** 最近倒された値段(attackDecayDays で半分に減る)。 */
        double losses;
        /** losses を最後に減らした日。 */
        double lossesAt;
        /** 作る途中の端数(体)。 */
        double produceCarry;
        /** 割り振りを最後に見直したゲーム時刻。 */
        long assignedAt = Long.MIN_VALUE;

        Front(int id, Kind kind, long treeKey, double x, double z) {
            this.id = id;
            this.kind = kind;
            this.treeKey = treeKey;
            this.x = x;
            this.z = z;
        }

        public int id() {
            return id;
        }

        public Kind kind() {
            return kind;
        }

        public long treeKey() {
            return treeKey;
        }

        public double x() {
            return x;
        }

        public double z() {
            return z;
        }

        public double losses() {
            return losses;
        }
    }

    private final List<Front> fronts = new ArrayList<>();
    private int nextId = 1;

    public static Fronts get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public List<Front> fronts() {
        return fronts;
    }

    @Nullable
    public Front byId(int id) {
        for (Front f : fronts) if (f.id == id) return f;
        return null;
    }

    @Nullable
    public Front byTree(long treeKey) {
        for (Front f : fronts) if (f.kind == Kind.TREE && f.treeKey == treeKey) return f;
        return null;
    }

    @Nullable
    public Front source() {
        for (Front f : fronts) if (f.kind == Kind.SOURCE) return f;
        return null;
    }

    Front add(Kind kind, long treeKey, double x, double z) {
        Front f = new Front(nextId++, kind, treeKey, x, z);
        fronts.add(f);
        setDirty();
        return f;
    }

    /** テスト用: 発生源の前線を足す。 */
    public Front addSourceForTests(double x, double z) {
        return add(Kind.SOURCE, Long.MIN_VALUE, x, z);
    }

    void remove(Front f) {
        if (fronts.remove(f)) setDirty();
    }

    /** いちばん近い前線(除く前線の id を外す。無ければ null)。 */
    @Nullable
    public Front nearest(double x, double z, int exceptId) {
        Front best = null;
        double bd = Double.MAX_VALUE;
        for (Front f : fronts) {
            if (f.id == exceptId) continue;
            double d = (f.x - x) * (f.x - x) + (f.z - z) * (f.z - z);
            if (d < bd) {
                bd = d;
                best = f;
            }
        }
        return best;
    }

    void touch() {
        setDirty();
    }

    /** 全部消す(確かめ用・テスト)。 */
    public void clear() {
        fronts.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Front f : fronts) {
            CompoundTag c = new CompoundTag();
            c.putInt("Id", f.id);
            c.putString("Kind", f.kind.name());
            c.putLong("Tree", f.treeKey);
            c.putDouble("X", f.x);
            c.putDouble("Z", f.z);
            c.putDouble("Losses", f.losses);
            c.putDouble("LossesAt", f.lossesAt);
            c.putDouble("Carry", f.produceCarry);
            list.add(c);
        }
        tag.put("Fronts", list);
        tag.putInt("NextId", nextId);
        return tag;
    }

    /** 保存した形から読む(テストからも)。 */
    public static Fronts load(CompoundTag tag) {
        Fronts d = new Fronts();
        d.nextId = Math.max(1, tag.getInt("NextId"));
        for (Tag t : tag.getList("Fronts", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Kind kind = "TREE".equals(c.getString("Kind")) ? Kind.TREE : Kind.SOURCE;
            Front f = new Front(c.getInt("Id"), kind, c.getLong("Tree"), c.getDouble("X"), c.getDouble("Z"));
            f.losses = c.getDouble("Losses");
            f.lossesAt = c.getDouble("LossesAt");
            f.produceCarry = c.getDouble("Carry");
            d.fronts.add(f);
            d.nextId = Math.max(d.nextId, f.id + 1);
        }
        return d;
    }
}
