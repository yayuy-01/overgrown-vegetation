package com.yayuy.overgrownvegetation.entity.stilt;

import com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 竹馬型の列の調べ(2026-11-10。種によらない)。列 (x, z) を上から下へ見て、<b>足を置ける固い上面</b>と、その上の
 * 当たりのあるブロックの並び(胴が通れるか・かがんだ膝が当たるか)を 1 回で測り、しばらく使い回す。
 *
 * <h2>決め</h2>
 * <ul>
 *   <li><b>固い</b> = 当たりの形があり、葉でない(葉は足が押し分けて下の地面に着く。決定 11-10「腿・脛が葉に少しめり込むのは許す」)。
 *       バリアは固くない(GameTest の壁を幹と見ない)。</li>
 *   <li><b>地面</b> = 上から見て、固いブロックの上が固くない最初の所(屋根・床・壁の上もこれ。決定 11-10「固い上面ならどこにでも」)。
 *       測り始めが幹・壁の中なら、その下の地面は使わない(幹の列 = 足も胴も入れない)。</li>
 *   <li>水は深さを数える(足は水の底に着く)。溶岩の上は足場にしない。</li>
 * </ul>
 *
 * <p>重さ: 1 列 20〜30 回のブロック読み。結果は {@link #EXPIRE_TICKS} の間、列と「測り始めの高さの 4 段ごとの組」で使い回す
 * (ブロックの変化は追わない)。メインスレッドから呼ぶ。
 */
public final class StiltColumns {

    /** 地面が無い(幹・壁の中から始まった / 測る範囲に無い)。 */
    public static final int NONE = Integer.MIN_VALUE;
    /** 上へ・下へ測る段(測り始めの高さから)【仮】。 */
    static final int UP = 10, DOWN = 12;
    /** 地面から上を何段まで並びに持つか(ビットの数)。 */
    public static final int BITS = 16;
    /** 使い回す tick【仮】。 */
    static final long EXPIRE_TICKS = 100L;

    /** 計測用: 測った列の数・使い回した数・測るのにかかった時間(ns)。 */
    public static volatile long scans, hits, scanNanos;

    /**
     * 列の答え。{@code ground} は足を置く段(その下の段が固い)、{@code surface} は上面の正確な高さ(半ブロックなども)。
     * {@code coll} / {@code firm} は ground から上の段ごと(ビット k = ground + k)に「当たりがある」「固い」。
     */
    public record Column(int ground, double surface, int water, boolean lava, int coll, int firm) {
        public boolean hasGround() {
            return ground != NONE;
        }

        /** 足を置けるか(地面があり、溶岩でなく、水が深すぎない)。 */
        public boolean foothold(int maxWater) {
            return ground != NONE && !lava && water <= maxWater;
        }

        /** 世界の高さ y0〜y1 の間に当たりのあるブロックがあるか(胴の箱が通れるか)。ground より下は見ない。 */
        public boolean blocked(double y0, double y1) {
            return any(coll, y0, y1);
        }

        /** 世界の高さ y0〜y1 の間に固いブロック(葉でない)があるか(かがんだ膝が当たるか)。 */
        public boolean firmBetween(double y0, double y1) {
            return any(firm, y0, y1);
        }

        private boolean any(int bits, double y0, double y1) {
            if (ground == NONE) return true;
            int a = Math.max(0, (int) Math.floor(y0 - ground));
            int b = (int) Math.floor(y1 - ground - 1.0E-4D);
            if (b < a) return false;
            if (a >= BITS) return false;
            b = Math.min(b, BITS - 1);
            int mask = (b == 31 ? -1 : ((1 << (b + 1)) - 1)) & ~((1 << a) - 1);
            return (bits & mask) != 0;
        }
    }

    private record Cached(Column c, long at) {
    }

    private static final Map<ServerLevel, StiltColumns> ALL = new WeakHashMap<>();
    private final ServerLevel level;
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Cached> cache = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private long lastSweep;

    private StiltColumns(ServerLevel level) {
        this.level = level;
    }

    public static synchronized StiltColumns of(ServerLevel level) {
        return ALL.computeIfAbsent(level, StiltColumns::new);
    }

    /** 試験用: 使い回しを捨てる(テストで足場を組み替えた後)。 */
    public void clear() {
        this.cache.clear();
    }

    private static long key(int x, int z, int bucket) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (bucket & 0xFFF);
    }

    /** 列 (x, z) を、高さ {@code ref}(だいたいの地面の高さ)のまわりで測る。 */
    public Column at(int x, int z, double ref) {
        int bucket = (int) Math.floor(ref) >> 2;
        long k = key(x, z, bucket);
        long now = this.level.getGameTime();
        Cached c = this.cache.get(k);
        if (c != null && now - c.at() <= EXPIRE_TICKS) {
            hits++;
            return c.c();
        }
        if (now - this.lastSweep > 200L) {
            this.lastSweep = now;
            this.cache.values().removeIf(v -> now - v.at() > EXPIRE_TICKS);
        }
        long t0 = System.nanoTime();
        Column col = scan(this.level, x, z, (bucket << 2) + 2);
        scanNanos += System.nanoTime() - t0;
        scans++;
        this.cache.put(k, new Cached(col, now));
        return col;
    }

    /** 測る(使い回さない)。 */
    static Column scan(ServerLevel level, int x, int z, int ref) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return new Column(NONE, 0, 0, false, -1, -1);
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int top = Math.min(ref + UP, level.getMaxBuildHeight() - 1), bottom = Math.max(ref - DOWN, level.getMinBuildHeight());
        // 上から下へ。固い段の上が固くない最初の所が地面
        boolean firmAbove = firm(level, p.set(x, top + 1, z));
        int ground = NONE;
        for (int y = top; y >= bottom; y--) {
            boolean f = firm(level, p.set(x, y, z));
            if (f && !firmAbove) {
                ground = y + 1;
                break;
            }
            firmAbove = f;
        }
        if (ground == NONE) {
            return new Column(NONE, 0, 0, false, -1, -1);
        }
        BlockState below = level.getBlockState(p.set(x, ground - 1, z));
        VoxelShape shape = below.getCollisionShape(level, p);
        double surface = ground - 1 + (shape.isEmpty() ? 1.0D : shape.max(net.minecraft.core.Direction.Axis.Y));
        int coll = 0, firm = 0, water = 0;
        boolean lava = false, counting = true;
        for (int k = 0; k < BITS; k++) {
            BlockState s = level.getBlockState(p.set(x, ground + k, z));
            if (!s.isAir()) {
                if (!s.getCollisionShape(level, p).isEmpty() && !s.is(Blocks.BARRIER)) {
                    coll |= 1 << k;
                    if (!isLeaf(s)) firm |= 1 << k;
                }
                FluidState fl = s.getFluidState();
                if (counting && !fl.isEmpty()) {
                    if (fl.is(FluidTags.LAVA)) lava = true;
                    else water++;
                } else {
                    counting = false;
                }
            } else {
                counting = false;
            }
        }
        return new Column(ground, surface, water, lava, coll, firm);
    }

    static boolean firm(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir() || s.is(Blocks.BARRIER) || isLeaf(s)) return false;
        return !s.getCollisionShape(level, p).isEmpty();
    }

    static boolean isLeaf(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.getBlock() instanceof OvergrownLeavesBlock;
    }
}
