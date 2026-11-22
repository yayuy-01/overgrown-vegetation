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
import com.yayuy.overgrownvegetation.port.saved.OvSavedData;

/**
 * 地下の小さな亀裂の記録(2026-11-20。オーバーワールドに 1 つ)。司令塔が開いた亀裂ごとの場所・状態・根の道と進み、
 * それと森が知っている汲み上げ器の場所(読み込まれたときに覚える。読み込まれていない所の汲み上げ器も避けるため)。
 */
public final class FissureData extends OvSavedData {

    private static final String ID = "overgrown_vegetation_fissures";

    public static final OvSavedData.Factory<FissureData> FACTORY =
            new OvSavedData.Factory<>(FissureData::new, (tag, registries) -> load(tag), null);

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
        /** 地上へあふれる口(地下の台帳の前線が口のあるチャンクに届いたときに決まる)。null = まだ。 */
        @Nullable
        public BlockPos outletMouth;
        /** 口の外向き(ラジアン。NaN = 全周)と、床に根を積もらせ終えたか(入口 B)。 */
        public double outletYaw = Double.NaN;
        public boolean moundDone;
        /** 根を送り込む器官(閉じた洞窟を汚染し切ったときにできる。追記 2)。 */
        public final List<Borer> borers = new ArrayList<>();
        /** 台帳でこの亀裂が持つチャンク(地下の台帳の鍵)。 */
        public final LongArrayList cells = new LongArrayList();

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
            return outletMouth;
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
            int alive = 0;
            for (Borer b : borers) if (b.digging()) alive++;
            return String.format(java.util.Locale.ROOT, "#%d (%s) %s 巣の根 %d/%d%s%s・台帳 %d マス・送り込む器官 %d(掘っている %d)%s", id, where, s, grown, len,
                    routeHitsMouth ? "(口まで)" : route != null ? "(口なし)" : "", end, cells.size(), borers.size(), alive,
                    outletMouth != null ? "・出口 " + outletMouth.toShortString() : "");
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
            if (outletMouth != null) t.putLong("Outlet", outletMouth.asLong());
            t.putDouble("OutletYaw", outletYaw);
            t.putBoolean("Mound", moundDone);
            ListTag bl = new ListTag();
            for (Borer b : borers) bl.add(b.save());
            t.put("Borers", bl);
            t.putLongArray("Cells", cells.toLongArray());
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
            if (t.contains("Outlet")) f.outletMouth = BlockPos.of(t.getLong("Outlet"));
            f.outletYaw = t.contains("OutletYaw") ? t.getDouble("OutletYaw") : Double.NaN;
            f.moundDone = t.getBoolean("Mound");
            ListTag bl = t.getList("Borers", Tag.TAG_COMPOUND);
            for (int i = 0; i < bl.size(); i++) f.borers.add(Borer.load(bl.getCompound(i)));
            for (long c : t.getLongArray("Cells")) f.cells.add(c);
            return f;
        }
    }

    /**
     * 根を送り込む器官 1 つ(閉じた洞窟で、亀裂の前線が洞窟を広がり切ったときにできる。2026-11-20 追記 2)。生きていて役目を終えていない間だけ、
     * 前線が洞窟の無い・まだ分からないチャンク(岩)へ遅く進む。別の洞窟に当たったら役目を終える。芯を壊すと止まる。
     */
    public static final class Borer {
        /** 台帳のチャンク(この器官がいる所)。 */
        public final long chunk;
        public long createdAt;
        /** 岩を掘る根の先(台帳のチャンク)。岩へはここからだけ進む(根が 1 本ずつ地中へ伸びる)。 */
        public long tip;
        /** 置いた芯(読み込まれて形になるまで null)。 */
        @Nullable
        public BlockPos core;
        public boolean alive = true;
        /** 別の洞窟に当たって役目を終えた。 */
        public boolean done;
        /** 置いたブロック(枯らすとき)。 */
        public final LongArrayList placed = new LongArrayList();
        public int withered;

        public Borer(long chunk, long createdAt) {
            this.chunk = chunk;
            this.createdAt = createdAt;
            this.tip = chunk;
        }

        public boolean digging() {
            return alive && !done;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Chunk", chunk);
            t.putLong("At", createdAt);
            t.putLong("Tip", tip);
            if (core != null) t.putLong("Core", core.asLong());
            t.putBoolean("Alive", alive);
            t.putBoolean("Done", done);
            t.putLongArray("Placed", placed.toLongArray());
            t.putInt("Withered", withered);
            return t;
        }

        static Borer load(CompoundTag t) {
            Borer b = new Borer(t.getLong("Chunk"), t.getLong("At"));
            if (t.contains("Tip")) b.tip = t.getLong("Tip");
            if (t.contains("Core")) b.core = BlockPos.of(t.getLong("Core"));
            b.alive = t.getBoolean("Alive");
            b.done = t.getBoolean("Done");
            for (long p : t.getLongArray("Placed")) b.placed.add(p);
            b.withered = t.getInt("Withered");
            return b;
        }
    }

    /** 地下の台帳の 1 マス(1 チャンク)。前線が届いた所にだけある(追記 2)。 */
    public static final class Cell {
        public final int fissure;
        /** 前線が届いた時刻(時計)。 */
        public long reachedAt;
        /** 岩を掘って届いた(根を送り込む器官の前線)。 */
        public final boolean rock;
        /** このマスから岩へ掘っている送り込む器官の番号(亀裂の borers の添字。-1 = なし)。 */
        public int borer;
        /** 読み込んだときの追いつきで、汚染し終えた列の数(0〜256)。 */
        public int applied;
        /** 途中で止めた列の続きの高さ(保存しない。失っても列の頭からやり直すだけ)。 */
        public transient int resumeY = Integer.MAX_VALUE;

        public Cell(int fissure, long reachedAt, boolean rock, int borer) {
            this.fissure = fissure;
            this.reachedAt = reachedAt;
            this.rock = rock;
            this.borer = borer;
        }
    }

    /** 見立て: 洞窟が無い・洞窟がある・洞窟が地上へ口を開けている(無い = まだ分からない)。 */
    public static final byte K_NONE = 1, K_CAVE = 2, K_MOUTH = 3;

    /** 地下の台帳(チャンク → マス)。世界全体には持たず、亀裂の前線が届いたマスだけ。 */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Cell> ledger = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    /** チャンクの地下の見立て(読み込んだときに測った物)。 */
    private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap known = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
    /** 口のあるチャンクの、地上へ出る口のマス(BlockPos#asLong)。 */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap mouths = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    public it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Cell> ledger() {
        return ledger;
    }

    /** 見立て(0 = まだ分からない)。 */
    public byte known(long chunk) {
        return known.get(chunk);
    }

    public int knownCount() {
        return known.size();
    }

    /** 見立ての版(変わるたびに増える。保存しない)。 */
    private int knownVersion;

    public int knownVersion() {
        return knownVersion;
    }

    public void setKnown(long chunk, byte kind, @Nullable BlockPos mouth) {
        knownVersion++;
        known.put(chunk, kind);
        if (mouth != null) mouths.put(chunk, mouth.asLong());
        else mouths.remove(chunk);
        setDirty();
    }

    @Nullable
    public BlockPos mouthOf(long chunk) {
        return mouths.containsKey(chunk) ? BlockPos.of(mouths.get(chunk)) : null;
    }

    public void addCell(long chunk, Cell c) {
        ledger.put(chunk, c);
        Fissure f = byId(c.fissure);
        if (f != null) f.cells.add(chunk);
        setDirty();
    }

    public void removeCell(long chunk) {
        Cell c = ledger.remove(chunk);
        if (c != null) {
            Fissure f = byId(c.fissure);
            if (f != null) f.cells.rem(chunk);
            setDirty();
        }
    }

    /** 確かめ用: その亀裂の時刻を ticks だけ前へずらす(台帳・送り込む器官・口も)。 */
    public void shiftBack(Fissure f, long ticks) {
        f.openedAt -= ticks;
        if (f.reachedMouth()) f.mouthAt -= ticks;
        for (long k : f.cells) {
            Cell c = ledger.get(k);
            if (c != null) c.reachedAt -= ticks;
        }
        for (Borer b : f.borers) b.createdAt -= ticks;
        setDirty();
    }

    /** テスト用: 見立てを消す。 */
    public void forgetKnown(long chunk) {
        knownVersion++;
        known.remove(chunk);
        mouths.remove(chunk);
    }

    private final List<Fissure> fissures = new ArrayList<>();
    private int nextId = 1;
    private double planDay = -1.0D;
    /** 森が知っている汲み上げ器(BlockPos#asLong)。 */
    private final LongOpenHashSet pumps = new LongOpenHashSet();

    private FissureData() {
    }

    public static FissureData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.load(), FACTORY.constructor(), ID);
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
        Fissure gone = byId(id);
        if (gone != null) for (long k : gone.cells.toLongArray()) ledger.remove(k);
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
        ListTag cl = tag.getList("Ledger", Tag.TAG_COMPOUND);
        for (int i = 0; i < cl.size(); i++) {
            CompoundTag c = cl.getCompound(i);
            Cell cell = new Cell(c.getInt("F"), c.getLong("T"), c.getBoolean("R"), c.contains("B") ? c.getInt("B") : -1);
            cell.applied = c.getInt("A");
            d.ledger.put(c.getLong("K"), cell);
        }
        long[] kk = tag.getLongArray("KnownKeys");
        byte[] kv = tag.getByteArray("KnownVals");
        for (int i = 0; i < Math.min(kk.length, kv.length); i++) d.known.put(kk[i], kv[i]);
        long[] mk = tag.getLongArray("MouthKeys"), mv = tag.getLongArray("MouthVals");
        for (int i = 0; i < Math.min(mk.length, mv.length); i++) d.mouths.put(mk[i], mv[i]);
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
        ListTag cl = new ListTag();
        for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<Cell> e : ledger.long2ObjectEntrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("K", e.getLongKey());
            c.putInt("F", e.getValue().fissure);
            c.putLong("T", e.getValue().reachedAt);
            c.putBoolean("R", e.getValue().rock);
            c.putInt("B", e.getValue().borer);
            c.putInt("A", e.getValue().applied);
            cl.add(c);
        }
        tag.put("Ledger", cl);
        long[] kk = known.keySet().toLongArray();
        byte[] kv = new byte[kk.length];
        for (int i = 0; i < kk.length; i++) kv[i] = known.get(kk[i]);
        tag.putLongArray("KnownKeys", kk);
        tag.putByteArray("KnownVals", kv);
        long[] mk = mouths.keySet().toLongArray();
        long[] mv = new long[mk.length];
        for (int i = 0; i < mk.length; i++) mv[i] = mouths.get(mk[i]);
        tag.putLongArray("MouthKeys", mk);
        tag.putLongArray("MouthVals", mv);
        return tag;
    }
}
