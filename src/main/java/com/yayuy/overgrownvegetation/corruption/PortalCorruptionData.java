package com.yayuy.overgrownvegetation.corruption;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ポータルを越えた侵食の記録({@link PortalCorruption}、2026-10-02)。オーバーワールドの {@code data/} に 1 つ。
 *
 * <ul>
 *     <li><b>侵食済みのネザーポータル</b> —— ポータル本体の矩形の最小の角({@code BlockUtil.FoundRectangle#minCorner})を
 *     次元付きで覚える。出口を見つけた(作った)ときに<b>両端を同時に</b>入れるので、向こうに置いた汚染の球が
 *     出口の枠に触れても同じポータルで行ったり来たりしない。</li>
 *     <li><b>森がエンドに着いたか</b> —— ストーリーの分岐(植物のドラゴンか、もう一つの最終ボスか)の材料。
 *     着いた時刻(ゲーム時刻)と、そのときエンドラが既に倒されていたかも残す。一度だけ。</li>
 * </ul>
 */
public final class PortalCorruptionData extends SavedData {

    private static final String ID = "overgrown_vegetation_portal_corruption";

    public static final SavedData.Factory<PortalCorruptionData> FACTORY =
            new SavedData.Factory<>(PortalCorruptionData::new, (tag, registries) -> load(tag), null);

    private final Set<GlobalPos> infected = new LinkedHashSet<>();
    private boolean endReached;
    private long endReachedGameTime;
    private boolean dragonKilledBeforeEnd;
    @Nullable
    private GlobalPos endReachedFrom;
    /** 過繫茂したエンドへ塗り替え終えた本島のチャンク({@link EndOvergrowth}、{@code ChunkPos#toLong})。 */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet endIslandDone = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /**
     * ネザーポータルの対(2026-10-23 胞子の場 F): {オーバーワールド側の枠の角, ネザー側の枠の角}({@link BlockPos#asLong})。
     * この対ごとに、両側のポータルのチャンクの胞子が毎回少しずつ染み出す({@code spore.field.PortalSeepage})。
     */
    private final it.unimi.dsi.fastutil.longs.LongArrayList pairs = new it.unimi.dsi.fastutil.longs.LongArrayList();

    private PortalCorruptionData() {
    }

    private static PortalCorruptionData load(CompoundTag tag) {
        PortalCorruptionData data = new PortalCorruptionData();
        ListTag list = tag.getList("infected", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            GlobalPos pos = readPos(list.getCompound(i));
            if (pos != null) {
                data.infected.add(pos);
            }
        }
        data.endReached = tag.getBoolean("endReached");
        data.endReachedGameTime = tag.getLong("endReachedGameTime");
        data.dragonKilledBeforeEnd = tag.getBoolean("dragonKilledBeforeEnd");
        data.endReachedFrom = tag.contains("endReachedFrom", Tag.TAG_COMPOUND)
                ? readPos(tag.getCompound("endReachedFrom")) : null;
        for (long key : tag.getLongArray("endIslandDone")) {
            data.endIslandDone.add(key);
        }
        for (long p : tag.getLongArray("portalPairs")) data.pairs.add(p);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (GlobalPos pos : infected) {
            list.add(writePos(pos));
        }
        tag.put("infected", list);
        tag.putBoolean("endReached", endReached);
        tag.putLong("endReachedGameTime", endReachedGameTime);
        tag.putBoolean("dragonKilledBeforeEnd", dragonKilledBeforeEnd);
        if (endReachedFrom != null) {
            tag.put("endReachedFrom", writePos(endReachedFrom));
        }
        tag.putLongArray("endIslandDone", endIslandDone.toLongArray());
        tag.putLongArray("portalPairs", pairs.toLongArray());
        return tag;
    }

    private static CompoundTag writePos(GlobalPos pos) {
        CompoundTag t = new CompoundTag();
        t.putString("dim", pos.dimension().location().toString());
        t.putLong("pos", pos.pos().asLong());
        return t;
    }

    @Nullable
    private static GlobalPos readPos(CompoundTag t) {
        ResourceLocation dim = ResourceLocation.tryParse(t.getString("dim"));
        if (dim == null) {
            return null;
        }
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(t.getLong("pos")));
    }

    public static PortalCorruptionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public boolean isInfected(GlobalPos anchor) {
        return infected.contains(anchor);
    }

    /** 新しく覚えたら {@code true}。 */
    boolean markInfected(GlobalPos anchor) {
        if (infected.add(anchor)) {
            setDirty();
            return true;
        }
        return false;
    }

    /** ネザーポータルの対を覚える(同じ対は 1 回だけ)。新しく覚えたら true。 */
    public boolean addPair(BlockPos overworld, BlockPos nether) {
        long a = overworld.asLong(), b = nether.asLong();
        for (int i = 0; i + 1 < pairs.size(); i += 2) if (pairs.getLong(i) == a && pairs.getLong(i + 1) == b) return false;
        pairs.add(a);
        pairs.add(b);
        setDirty();
        return true;
    }

    /** 対の一覧 {ow0, nether0, ow1, nether1, …}(写し)。 */
    public long[] pairs() {
        return pairs.toLongArray();
    }

    /** テスト用: 対を全部忘れる。 */
    public void clearPairsForTests() {
        pairs.clear();
        setDirty();
    }

    public Set<GlobalPos> infected() {
        return Collections.unmodifiableSet(infected);
    }

    public boolean endReached() {
        return endReached;
    }

    public long endReachedGameTime() {
        return endReachedGameTime;
    }

    /** 森がエンドに着いた時点で、エンドラが既に倒されていたか(ストーリーの「取り込まないルート」)。 */
    public boolean dragonKilledBeforeEnd() {
        return dragonKilledBeforeEnd;
    }

    @Nullable
    public GlobalPos endReachedFrom() {
        return endReachedFrom;
    }

    void markEndReached(long gameTime, boolean dragonKilled, GlobalPos from) {
        if (endReached) {
            return;
        }
        endReached = true;
        endReachedGameTime = gameTime;
        dragonKilledBeforeEnd = dragonKilled;
        endReachedFrom = from;
        setDirty();
    }

    /** 本島のそのチャンクを塗り替え終えたか。 */
    public boolean isEndIslandDone(long chunkKey) {
        return endIslandDone.contains(chunkKey);
    }

    void markEndIslandDone(long chunkKey) {
        if (endIslandDone.add(chunkKey)) {
            setDirty();
        }
    }

    public int endIslandDoneCount() {
        return endIslandDone.size();
    }

    /** {@code /ov portal reset} 用。記録を全部消す(侵食そのものは戻さない)。 */
    void clear() {
        infected.clear();
        endReached = false;
        endReachedGameTime = 0L;
        dragonKilledBeforeEnd = false;
        endReachedFrom = null;
        endIslandDone.clear();
        setDirty();
    }
}
