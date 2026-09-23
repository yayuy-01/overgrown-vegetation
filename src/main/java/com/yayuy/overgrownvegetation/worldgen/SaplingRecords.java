package com.yayuy.overgrownvegetation.worldgen;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * チャンクに付随させる「汚染された苗木の設置時刻」の表({@link BlockPos#asLong} → 設置時のゲーム内時刻)。
 * {@link SaplingOfflineGrowth} が、チャンクの読み込み時に「設置からどれだけ時間が経ったか」を見て
 * 未読み込み中に育つはずだった苗木をまとめて育てるのに使う。
 *
 * <p>tick で数えるカウンタではなく<b>時刻</b>を持つのがポイント: ワールド時間はチャンクが読み込まれていなくても
 * 進むので、未読み込み中の処理はゼロで済み、読み込んだ瞬間に引き算するだけで経過時間が分かる。
 * 苗木の無いチャンクはこの添付データ自体を持たない(`hasData` が false)。
 *
 * <p>セーブ形式は {@code [pos, time, pos, time, ...]} の long 配列(NBT の LongArray)。
 */
public final class SaplingRecords {

    public static final Codec<SaplingRecords> CODEC = Codec.LONG.listOf().xmap(SaplingRecords::fromList, SaplingRecords::toList);

    private final Long2LongOpenHashMap planted = new Long2LongOpenHashMap();

    public SaplingRecords() {
        planted.defaultReturnValue(Long.MIN_VALUE);
    }

    private static SaplingRecords fromList(List<Long> flat) {
        SaplingRecords records = new SaplingRecords();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            records.planted.put(flat.get(i).longValue(), flat.get(i + 1).longValue());
        }
        return records;
    }

    private static List<Long> toList(SaplingRecords records) {
        List<Long> flat = new ArrayList<>(records.planted.size() * 2);
        for (Long2LongMap.Entry e : records.planted.long2LongEntrySet()) {
            flat.add(e.getLongKey());
            flat.add(e.getLongValue());
        }
        return flat;
    }

    /** 設置時刻を記録する(既にあれば上書きしない)。 @return 新規に記録したら true */
    public boolean recordIfAbsent(BlockPos pos, long gameTime) {
        long key = pos.asLong();
        if (planted.containsKey(key)) {
            return false;
        }
        planted.put(key, gameTime);
        return true;
    }

    /** @return 記録があれば消して true */
    public boolean forget(BlockPos pos) {
        return planted.remove(pos.asLong()) != Long.MIN_VALUE;
    }

    public boolean contains(BlockPos pos) {
        return planted.containsKey(pos.asLong());
    }

    public boolean isEmpty() {
        return planted.isEmpty();
    }

    public int size() {
        return planted.size();
    }

    /** 記録された全エントリを走査する(読み取り専用)。 */
    public Iterable<Long2LongMap.Entry> entries() {
        return planted.long2LongEntrySet();
    }
}
