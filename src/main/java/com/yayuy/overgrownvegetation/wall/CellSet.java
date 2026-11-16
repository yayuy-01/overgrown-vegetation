package com.yayuy.overgrownvegetation.wall;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * 升の集合を、区画(16×16×16)ごとの 4096 ビットで持つ(2026-11-16 決定 その 8「作りを軽くする」)。
 * 升 1 つにつき 1 ビット(区画ごとに 512 バイト + 表の 1 項目)。数百万升でも {@code LongOpenHashSet} の
 * 作り直し(数十 MB の配列を一度に確保する)が起きず、GC と 1 tick の山が減る。直前に引いた区画を覚えておき、隣の升の問い合わせを速くする。
 */
final class CellSet {

    private final Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();
    private long lastKey = Long.MIN_VALUE;
    private long[] lastBits;
    private int size;

    private long[] bits(long pos, boolean create) {
        long key = SectionPos.asLong(BlockPos.getX(pos) >> 4, BlockPos.getY(pos) >> 4, BlockPos.getZ(pos) >> 4);
        if (key == lastKey && lastBits != null) {
            return lastBits;
        }
        long[] b = sections.get(key);
        if (b == null && create) {
            b = new long[64];
            sections.put(key, b);
        }
        if (b != null) {
            lastKey = key;
            lastBits = b;
        }
        return b;
    }

    private static int index(long pos) {
        return (BlockPos.getY(pos) & 15) << 8 | (BlockPos.getZ(pos) & 15) << 4 | (BlockPos.getX(pos) & 15);
    }

    boolean contains(long pos) {
        long[] b = bits(pos, false);
        if (b == null) {
            return false;
        }
        int i = index(pos);
        return (b[i >>> 6] & 1L << (i & 63)) != 0;
    }

    /** 足す。新しく足したら true。 */
    boolean add(long pos) {
        long[] b = bits(pos, true);
        int i = index(pos);
        long mask = 1L << (i & 63);
        if ((b[i >>> 6] & mask) != 0) {
            return false;
        }
        b[i >>> 6] |= mask;
        size++;
        return true;
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    int sectionCount() {
        return sections.size();
    }
}
