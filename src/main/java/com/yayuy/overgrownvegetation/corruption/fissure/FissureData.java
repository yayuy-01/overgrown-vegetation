package com.yayuy.overgrownvegetation.corruption.fissure;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 地下の小さな亀裂の記録(2026-11-20。オーバーワールドに 1 つ)。司令塔が開いた亀裂ごとの場所・状態・根の道と進み、
 * それと森が知っている汲み上げ器の場所(読み込まれたときに覚える。読み込まれていない所の汲み上げ器も避けるため)。
 */
public final class FissureData extends SavedData {

    private static final String ID = "overgrown_vegetation_fissures";

    public static final SavedData.Factory<FissureData> FACTORY =
            new SavedData.Factory<>(FissureData::new, (tag, registries) -> load(tag), null);

    public enum State {
        /** 場所(x, z)は決めたが、まだ洞窟を見ていない(そのあたりが読み込まれるのを待つ)。 */
        PENDING,
        /** 開いた。巣が根を伸ばしている。 */
        OPEN,
        /** 芯を壊されて枯れた。 */
        DEAD
    }

    public static final class Fissure {
        public final int id;
        public int x, z;
        public final int seed;
        public State state = State.PENDING;
        /** 場所を選び直した回数。 */
        public int relocations;
        /** 払った値段(取り消すときに戻す)。 */
        public double paid;
        public long plannedAt;
        /** 開いた時刻(時計)。 */
        public long openedAt = -1L;
        /** 洞窟の床(亀裂の口)と芯。開くまで null。 */
        @Nullable
        public BlockPos floor, core;
        /** 根の道(空気のマス。巣から口・または行ける所まで)。開いた後、根が伸び始めるときに作る。 */
        @Nullable
        public long[] route;
        /** 道の最後が洞窟の口(空が見える)か。 */
        public boolean routeHitsMouth;
        /** 道のうち根を伸ばし終えた数。 */
        public int grown;
        /** 巣のまわりの最初の汚染を済ませたか。 */
        public boolean nestSpread;
        /** 巣のまわりの汚染を済ませた段(下から。1 tick に 1 段)。 */
        public int nestLayer;
        /** 根が口に着いた時刻(MIN_VALUE = まだ)。 */
        public long mouthAt = Long.MIN_VALUE;
        /** 口から這った根の数(腕ごとの伸ばし終えた長さ)。 */
        public int[] armDone = new int[0];
        /** 地上の汚染の点を足した数。 */
        public int pointsDone;
        /** 置いた根・巣のマス(枯らすとき)。 */
        public final LongArrayList placed = new LongArrayList();
        /** 枯らし終えた数。 */
        public int withered;
        public long diedAt = -1L;

        Fissure(int id, int x, int z, int seed, long plannedAt) {
            this.id = id;
            this.x = x;
            this.z = z;
            this.seed = seed;
            this.plannedAt = plannedAt;
        }

        /** 根が口に着いたか(時刻は時計を戻すと負になりうるので、印は MIN_VALUE)。 */
        public boolean reachedMouth() {
            return mouthAt != Long.MIN_VALUE;
        }

        public boolean alive() {
            return state != State.DEAD;
        }

        @Nullable
        public BlockPos mouth() {
            return route != null && routeHitsMouth && route.length > 0 ? BlockPos.of(route[route.length - 1]) : null;
        }

        public String describe() {
            String where = floor != null ? floor.toShortString() : x + ", ?, " + z;
            String s = switch (state) {
                case PENDING -> "洞窟待ち";
                case OPEN -> reachedMouth() ? "口からあふれている" : "根を伸ばしている";
                case DEAD -> "枯れた";
            };
            int len = route == null ? 0 : route.length;
            String end = route != null && route.length > 0 ? " 道の終わり " + BlockPos.of(route[route.length - 1]).toShortString() : "";
            return String.format(java.util.Locale.ROOT, "#%d (%s) %s 根 %d/%d%s%s", id, where, s, grown, len,
                    routeHitsMouth ? "(口まで)" : route != null ? "(口なし)" : "", end);
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", id);
            t.putInt("X", x);
            t.putInt("Z", z);
            t.putInt("Seed", seed);
            t.putString("State", state.name());
            t.putInt("Reloc", relocations);
            t.putDouble("Paid", paid);
            t.putLong("Planned", plannedAt);
            t.putLong("Opened", openedAt);
            if (floor != null) t.putLong("Floor", floor.asLong());
            if (core != null) t.putLong("Core", core.asLong());
            if (route != null) t.putLongArray("Route", route);
            t.putBoolean("RouteMouth", routeHitsMouth);
            t.putInt("Grown", grown);
            t.putBoolean("NestSpread", nestSpread);
            t.putInt("NestLayer", nestLayer);
            t.putLong("MouthAt", mouthAt);
            t.putIntArray("Arms", armDone);
            t.putInt("Points", pointsDone);
            t.putLongArray("Placed", placed.toLongArray());
            t.putInt("Withered", withered);
            t.putLong("Died", diedAt);
            return t;
        }

        static Fissure load(CompoundTag t) {
            Fissure f = new Fissure(t.getInt("Id"), t.getInt("X"), t.getInt("Z"), t.getInt("Seed"), t.getLong("Planned"));
            try {
                f.state = State.valueOf(t.getString("State"));
            } catch (IllegalArgumentException e) {
                f.state = State.PENDING;
            }
            f.relocations = t.getInt("Reloc");
            f.paid = t.getDouble("Paid");
            f.openedAt = t.getLong("Opened");
            if (t.contains("Floor")) f.floor = BlockPos.of(t.getLong("Floor"));
            if (t.contains("Core")) f.core = BlockPos.of(t.getLong("Core"));
            if (t.contains("Route")) f.route = t.getLongArray("Route");
            f.routeHitsMouth = t.getBoolean("RouteMouth");
            f.grown = t.getInt("Grown");
            f.nestSpread = t.getBoolean("NestSpread");
            f.nestLayer = t.getInt("NestLayer");
            f.mouthAt = t.contains("MouthAt") ? t.getLong("MouthAt") : Long.MIN_VALUE;
            f.armDone = t.getIntArray("Arms");
            f.pointsDone = t.getInt("Points");
            for (long p : t.getLongArray("Placed")) f.placed.add(p);
            f.withered = t.getInt("Withered");
            f.diedAt = t.getLong("Died");
            return f;
        }
    }

    private final List<Fissure> fissures = new ArrayList<>();
    private int nextId = 1;
    private double planDay = -1.0D;
    /** 森が知っている汲み上げ器(BlockPos#asLong)。 */
    private final LongOpenHashSet pumps = new LongOpenHashSet();

    private FissureData() {
    }

    public static FissureData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public static FissureData get(ServerLevel level) {
        return get(level.getServer());
    }

    public List<Fissure> fissures() {
        return Collections.unmodifiableList(fissures);
    }

    @Nullable
    public Fissure byId(int id) {
        for (Fissure f : fissures) if (f.id == id) return f;
        return null;
    }

    /** 生きている亀裂(洞窟待ちも数える)。 */
    public int aliveCount() {
        int n = 0;
        for (Fissure f : fissures) if (f.alive()) n++;
        return n;
    }

    public Fissure add(int x, int z, int seed, long now, double paid) {
        Fissure f = new Fissure(nextId++, x, z, seed, now);
        f.paid = paid;
        fissures.add(f);
        setDirty();
        return f;
    }

    public boolean remove(int id) {
        boolean r = fissures.removeIf(f -> f.id == id);
        if (r) setDirty();
        return r;
    }

    double planDay() {
        return planDay;
    }

    void setPlanDay(double d) {
        planDay = d;
        setDirty();
    }

    public LongOpenHashSet pumps() {
        return pumps;
    }

    void addPump(BlockPos p) {
        if (pumps.add(p.asLong())) setDirty();
    }

    void removePump(BlockPos p) {
        if (pumps.remove(p.asLong())) setDirty();
    }

    void changed() {
        setDirty();
    }

    private static FissureData load(CompoundTag tag) {
        FissureData d = new FissureData();
        d.nextId = Math.max(1, tag.getInt("NextId"));
        d.planDay = tag.contains("PlanDay") ? tag.getDouble("PlanDay") : -1.0D;
        ListTag list = tag.getList("Fissures", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Fissure f = Fissure.load(list.getCompound(i));
            d.fissures.add(f);
            d.nextId = Math.max(d.nextId, f.id + 1);
        }
        for (long p : tag.getLongArray("Pumps")) d.pumps.add(p);
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextId", nextId);
        tag.putDouble("PlanDay", planDay);
        ListTag list = new ListTag();
        for (Fissure f : fissures) list.add(f.save());
        tag.put("Fissures", list);
        tag.putLongArray("Pumps", pumps.toLongArray());
        return tag;
    }
}
