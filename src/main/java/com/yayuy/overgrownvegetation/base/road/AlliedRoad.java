package com.yayuy.overgrownvegetation.base.road;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

/**
 * 同盟軍が引く道 1 本(順 2 の着手順 7、2026-10-08)。基地 {@link #siteA} の正門の道の先から {@link #siteB} の正門の道の先まで。
 *
 * <p>持つのは道の真ん中の線(1 ブロックおき、{@link RoadPlanner.Plan})と工事の進み。マス(アスファルト・路肩・妨害灯の柱・橋脚)は
 * 線から決まった作り方で求める({@link #cells})。工事は両方の基地から進み、A からは線の頭から {@link #builtA} 点、B からは尻から {@link #builtB} 点。
 */
public final class AlliedRoad {

    public final int id;
    public final int siteA;
    public final int siteB;
    final int[] xs8;
    final int[] zs8;
    final int[] h2;
    final byte[] flags;
    /** 工事の進み(線の点の数)。 */
    double builtA;
    double builtB;
    /** 払い切れていない貯蓄の端数。 */
    double carryA;
    double carryB;
    /** 最後に工事を進めた汚染の暦の時刻。 */
    long lastClock;

    private transient Cells cells;
    private transient int cellsWidth;
    private transient int cellsLamp;

    AlliedRoad(int id, int siteA, int siteB, RoadPlanner.Plan plan, long now) {
        this(id, siteA, siteB, plan.xs8(), plan.zs8(), plan.h2(), plan.flags());
        this.lastClock = now;
    }

    private AlliedRoad(int id, int siteA, int siteB, int[] xs8, int[] zs8, int[] h2, byte[] flags) {
        this.id = id;
        this.siteA = siteA;
        this.siteB = siteB;
        this.xs8 = xs8;
        this.zs8 = zs8;
        this.h2 = h2;
        this.flags = flags;
    }

    public int length() {
        return xs8.length;
    }

    /** A 側(頭)から済んだ点の数。 */
    public double builtA() {
        return builtA;
    }

    /** B 側(尻)から済んだ点の数。 */
    public double builtB() {
        return builtB;
    }

    /** 最後に工事を進めた汚染の暦の時刻。 */
    public long lastClock() {
        return lastClock;
    }

    public boolean done() {
        return builtA + builtB >= length();
    }

    /** 進み 0〜1。 */
    public double progress() {
        return Math.min(1.0D, (builtA + builtB) / Math.max(1, length()));
    }

    /** 線の {@code i} 番目の点は工事が済んだか。 */
    public boolean isBuilt(int i) {
        return done() || i < (int) builtA || i >= length() - (int) builtB;
    }

    public boolean links(int a, int b) {
        return (siteA == a && siteB == b) || (siteA == b && siteB == a);
    }

    public boolean touches(int site) {
        return siteA == site || siteB == site;
    }

    /** 線の {@code i} 番目の点(ブロックの座標)。 */
    public int pointX(int i) {
        return Math.floorDiv(xs8[i], 8);
    }

    public int pointZ(int i) {
        return Math.floorDiv(zs8[i], 8);
    }

    /** 線の {@code i} 番目の点の路面の天辺(ブロック。ハーフの上なら .5)。 */
    public double surfaceY(int i) {
        return h2[i] / 2.0D;
    }

    public boolean bridge(int i) {
        return (flags[i] & RoadPlanner.BRIDGE) != 0;
    }

    /** 橋の点の数。 */
    public int bridgePoints() {
        int n = 0;
        for (byte f : flags) {
            if ((f & RoadPlanner.BRIDGE) != 0) {
                n++;
            }
        }
        return n;
    }

    /** 工事の先頭の点(A 側なら {@code fromA})。済んでいれば −1。 */
    public int frontier(boolean fromA) {
        if (done()) {
            return -1;
        }
        return fromA ? Math.min(length() - 1, (int) builtA) : Math.max(0, length() - 1 - (int) builtB);
    }

    // ---------- マス ----------

    /** マスの種類。 */
    public static final int ASPHALT = 0;
    public static final int SHOULDER = 1;
    public static final int LAMP = 2;

    /**
     * 道のマス。{@code pos} は路面の下のフルブロックの位置({@link BlockPos#asLong}、ハーフの道ならその 1 つ下)、
     * {@code meta} は線の点の番号(下位 24 ビット)・種類(24〜25)・ハーフ(26)・橋(27)・橋脚(28)。
     */
    public record Cells(long[] pos, int[] meta, Long2ObjectOpenHashMap<IntArrayList> byChunk) {
        public int index(int c) {
            return meta[c] & 0xFFFFFF;
        }

        public int kind(int c) {
            return (meta[c] >>> 24) & 3;
        }

        public boolean slab(int c) {
            return (meta[c] & (1 << 26)) != 0;
        }

        public boolean bridge(int c) {
            return (meta[c] & (1 << 27)) != 0;
        }

        public boolean pillar(int c) {
            return (meta[c] & (1 << 28)) != 0;
        }

        /** 真ん中の線の上のマスで、橋脚を立ててよい点(6 点おき)か。思いがけない深い窪み(ノイズに無い峡谷)を渡るときに使う。 */
        public boolean pillarSpot(int c) {
            return (meta[c] & (1 << 29)) != 0;
        }

        public int size() {
            return pos.length;
        }
    }

    /** 道のマスを求める(幅・妨害灯の間隔ごとに 1 回だけ作って持つ)。 */
    public Cells cells(int width, int lampSpacing) {
        if (cells == null || cellsWidth != width || cellsLamp != lampSpacing) {
            cells = buildCells(width, lampSpacing);
            cellsWidth = width;
            cellsLamp = lampSpacing;
        }
        return cells;
    }

    private Cells buildCells(int width, int lampSpacing) {
        int n = length();
        Long2IntOpenHashMap at = new Long2IntOpenHashMap(); // 列 → マスの番号
        at.defaultReturnValue(-1);
        LongArrayList cols = new LongArrayList();
        IntArrayList meta = new IntArrayList();
        double half = (width - 1) / 2.0D;
        for (int i = 0; i < n; i++) {
            double[] nrm = normal(i);
            for (int s = 0; s < 2; s++) {
                // 点と、次の点との真ん中(斜めの道に穴を開けない)
                if (s == 1 && i + 1 >= n) {
                    break;
                }
                double cx = (s == 0 ? xs8[i] : (xs8[i] + xs8[i + 1]) / 2.0D) / 8.0D;
                double cz = (s == 0 ? zs8[i] : (zs8[i] + zs8[i + 1]) / 2.0D) / 8.0D;
                for (double o = -half; o <= half + 1e-9; o += 0.5D) {
                    claim(at, cols, meta, cx + o * nrm[0], cz + o * nrm[1], i, ASPHALT);
                }
                for (double o : new double[]{half + 0.5D, half + 1.0D}) {
                    claim(at, cols, meta, cx + o * nrm[0], cz + o * nrm[1], i, SHOULDER);
                    claim(at, cols, meta, cx - o * nrm[0], cz - o * nrm[1], i, SHOULDER);
                }
            }
        }
        // 妨害灯: 間隔ごとに左右交互(橋の上は次の陸の点へずらす)
        for (int k = 0; ; k++) {
            int i = lampSpacing / 2 + k * lampSpacing;
            if (i >= n - 2) {
                break;
            }
            int j = i;
            while (j < Math.min(n - 2, i + lampSpacing / 2) && bridge(j)) {
                j++;
            }
            if (bridge(j)) {
                continue;
            }
            double[] nrm = normal(j);
            int side = (k % 2 == 0) ? 1 : -1;
            double o = (half + 1.0D) * side;
            int bx = (int) Math.floor(xs8[j] / 8.0D + o * nrm[0]);
            int bz = (int) Math.floor(zs8[j] / 8.0D + o * nrm[1]);
            long col = ChunkPos.asLong(bx, bz);
            int c = at.get(col);
            if (c < 0) {
                at.put(col, cols.size());
                cols.add(col);
                meta.add(j | (LAMP << 24));
            } else if (((meta.getInt(c) >>> 24) & 3) == SHOULDER) {
                meta.set(c, (meta.getInt(c) & 0xFFFFFF) | (LAMP << 24));
            }
        }
        long[] pos = new long[cols.size()];
        int[] m = new int[cols.size()];
        Long2ObjectOpenHashMap<IntArrayList> byChunk = new Long2ObjectOpenHashMap<>();
        for (int c = 0; c < cols.size(); c++) {
            long col = cols.getLong(c);
            int x = ChunkPos.getX(col);
            int z = ChunkPos.getZ(col);
            int idx = meta.getInt(c) & 0xFFFFFF;
            int kind = (meta.getInt(c) >>> 24) & 3;
            int top2 = h2[idx];
            boolean slab = (top2 & 1) != 0;
            int y0 = (slab ? (top2 - 1) / 2 : top2 / 2) - 1;
            int mm = idx | (kind << 24);
            if (slab && kind == ASPHALT) {
                mm |= 1 << 26;
            }
            boolean spot = kind == ASPHALT && idx % 6 == 0 && x == pointX(idx) && z == pointZ(idx);
            if (spot) {
                mm |= 1 << 29;
            }
            if (bridge(idx)) {
                mm |= 1 << 27;
                if (spot) {
                    mm |= 1 << 28;
                }
            }
            pos[c] = BlockPos.asLong(x, y0, z);
            m[c] = mm;
            byChunk.computeIfAbsent(ChunkPos.asLong(x >> 4, z >> 4), k -> new IntArrayList()).add(c);
        }
        return new Cells(pos, m, byChunk);
    }

    /** 1 つの列を取る。アスファルトは路肩より強い(先に路肩で取られていても塗り替える)。 */
    private static void claim(Long2IntOpenHashMap at, LongArrayList cols, IntArrayList meta, double x, double z, int i, int kind) {
        long col = ChunkPos.asLong((int) Math.floor(x), (int) Math.floor(z));
        int c = at.get(col);
        if (c < 0) {
            at.put(col, cols.size());
            cols.add(col);
            meta.add(i | (kind << 24));
        } else if (kind == ASPHALT && ((meta.getInt(c) >>> 24) & 3) != ASPHALT) {
            meta.set(c, i | (ASPHALT << 24));
        }
    }

    /** 点 {@code i} での横向き(進む向きを左へ 90 度)の単位ベクトル。 */
    private double[] normal(int i) {
        int a = Math.max(0, i - 1);
        int b = Math.min(length() - 1, i + 1);
        double dx = (xs8[b] - xs8[a]) / 8.0D;
        double dz = (zs8[b] - zs8[a]) / 8.0D;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            return new double[]{1.0D, 0.0D};
        }
        return new double[]{-dz / len, dx / len};
    }

    // ---------- 保存 ----------

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putInt("a", siteA);
        t.putInt("b", siteB);
        t.putIntArray("xs", xs8);
        t.putIntArray("zs", zs8);
        t.putIntArray("h2", h2);
        t.putByteArray("flags", flags);
        t.putDouble("builtA", builtA);
        t.putDouble("builtB", builtB);
        t.putDouble("carryA", carryA);
        t.putDouble("carryB", carryB);
        t.putLong("lastClock", lastClock);
        return t;
    }

    static AlliedRoad load(CompoundTag t) {
        AlliedRoad r = new AlliedRoad(t.getInt("id"), t.getInt("a"), t.getInt("b"), t.getIntArray("xs"), t.getIntArray("zs"),
                t.getIntArray("h2"), t.getByteArray("flags"));
        r.builtA = t.getDouble("builtA");
        r.builtB = t.getDouble("builtB");
        r.carryA = t.getDouble("carryA");
        r.carryB = t.getDouble("carryB");
        r.lastClock = t.getLong("lastClock");
        return r;
    }
}
