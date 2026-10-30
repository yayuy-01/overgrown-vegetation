package com.yayuy.overgrownvegetation.corruption;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;

/**
 * {@link SwampFlooder} の列ごとの処理済み段階(0 = 未処理 / 1 = 水たまり段階まで / 2 = 池・棚田まで)。
 * チャンクごとに 256 バイト。ワールドと一緒に保存される。
 *
 * <p>以前は「上面が泥なら処理済み」で判定していたが、湿地では汚染された土がそもそも泥になる
 * (2026-09-10)ので目印にならなくなった。列を一度処理したら段階を記録し、同じ段階では二度と触らない
 * (地形を削ったことで平滑化高さが変わり、再処理のたびに段が下がっていく「侵食」を防ぐ)。
 */
public final class SwampFloodProgress extends SavedData {

    private static final String NAME = "overgrown_vegetation_swamp_flood";

    private final Long2ObjectOpenHashMap<byte[]> chunks = new Long2ObjectOpenHashMap<>();

    public static SwampFloodProgress get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SwampFloodProgress::new, SwampFloodProgress::load), NAME);
    }

    private SwampFloodProgress() {
    }

    private static SwampFloodProgress load(CompoundTag tag, HolderLookup.Provider registries) {
        SwampFloodProgress data = new SwampFloodProgress();
        ListTag list = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            byte[] columns = entry.getByteArray("columns");
            // 全列 0 の記録は「記録なし」と同じ意味なので読み込まない(2026-09-24 軽量化 報告 1527。以前の
            // columns() が読むだけで作っていた空の記録が、保存のたびに書き出され続けていた)。
            if (columns.length == 256 && !allZero(columns)) {
                data.chunks.put(entry.getLong("pos"), columns);
            }
        }
        return data;
    }

    private static boolean allZero(byte[] columns) {
        for (byte b : columns) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (var entry : chunks.long2ObjectEntrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("pos", entry.getLongKey());
            c.putByteArray("columns", entry.getValue());
            list.add(c);
        }
        tag.put("chunks", list);
        return tag;
    }

    /** チャンクの 256 列ぶんの段階配列(index = localX * 16 + localZ)。書き換えたら {@link #setDirty()} を呼ぶこと。 */
    public byte[] columns(ChunkPos pos) {
        return chunks.computeIfAbsent(pos.toLong(), k -> new byte[256]);
    }

    /**
     * 読むだけ用: 記録があればその配列、無ければ {@code null}(= 全列 0 と同じ意味)。配列は作らない
     * (2026-09-24 軽量化、報告 1527: 以前は読むだけでも {@link #columns} が全 0 の配列を台帳に足していき、
     * 何も書かないチャンクのぶんまで台帳と保存が膨らんでいた)。書き込むときは {@link #columns} を使うこと。
     */
    @Nullable
    public byte[] peek(ChunkPos pos) {
        return chunks.get(pos.toLong());
    }
}
