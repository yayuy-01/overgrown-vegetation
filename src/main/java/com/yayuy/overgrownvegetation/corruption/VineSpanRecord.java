package com.yayuy.overgrownvegetation.corruption;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 置いた太い蔦の組の記録(2026-11-07)。太い蔦は汚染された葉だけで作る(11-07 ユーザー決定)ので、木の葉と見分けがつかない。
 * 「近くにほかの太い蔦があれば渡さない」({@code vineSpanSpacing})のために、組ごとに両岸の壁のブロック(a・b)を覚える。ディメンションごとに 1 つ。
 *
 * <p>組を壊しても記録は残る(その近くには新しく出ない)。数が増えすぎないよう、古いものから捨てる({@link #MAX})。
 */
public final class VineSpanRecord extends SavedData {

    private static final String ID = "overgrown_vegetation_vine_spans";
    /** 覚えておく組の数の上限(仮決め)。 */
    private static final int MAX = 4096;

    public static final SavedData.Factory<VineSpanRecord> FACTORY =
            new SavedData.Factory<>(VineSpanRecord::new, (tag, registries) -> load(tag), null);

    /** a, b, a, b, ... の順。 */
    private final LongArrayList ends = new LongArrayList();

    private VineSpanRecord() {
    }

    private static VineSpanRecord load(CompoundTag tag) {
        VineSpanRecord data = new VineSpanRecord();
        for (long key : tag.getLongArray("ends")) {
            data.ends.add(key);
        }
        if ((data.ends.size() & 1) != 0) {
            data.ends.removeLong(data.ends.size() - 1);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("ends", ends.toLongArray());
        return tag;
    }

    public static VineSpanRecord get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** 組を 1 つ覚える。 */
    public void add(BlockPos a, BlockPos b) {
        ends.add(a.asLong());
        ends.add(b.asLong());
        while (ends.size() > MAX * 2) {
            ends.removeElements(0, 2);
        }
        setDirty();
    }

    /** 弦 a–b の両端と真ん中のどれかから水平も上下も {@code spacing} 以内に(上下も同じにしたのは 11-07 ユーザー決定「重なりを抑える」。前は上下 6 で、深い渓谷に高さ違いで積み重なった)、覚えている組の両端か真ん中があるか。 */
    public boolean near(BlockPos a, BlockPos b, int spacing) {
        if (spacing <= 0) {
            return false;
        }
        long[] mine = {a.asLong(), mid(a.asLong(), b.asLong()), b.asLong()};
        for (int i = 0; i + 1 < ends.size(); i += 2) {
            long ea = ends.getLong(i);
            long eb = ends.getLong(i + 1);
            for (long other : new long[]{ea, mid(ea, eb), eb}) {
                for (long m : mine) {
                    if (Math.abs(BlockPos.getX(m) - BlockPos.getX(other)) <= spacing
                            && Math.abs(BlockPos.getZ(m) - BlockPos.getZ(other)) <= spacing
                            && Math.abs(BlockPos.getY(m) - BlockPos.getY(other)) <= spacing) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 覚えている組の両端(a, b, a, b, ... の順。dev の書き出し用の写し)。 */
    public long[] ends() {
        return ends.toLongArray();
    }

    /** 覚えている組の数。 */
    public int size() {
        return ends.size() / 2;
    }

    private static long mid(long a, long b) {
        return BlockPos.asLong((BlockPos.getX(a) + BlockPos.getX(b)) / 2, (BlockPos.getY(a) + BlockPos.getY(b)) / 2,
                (BlockPos.getZ(a) + BlockPos.getZ(b)) / 2);
    }
}
