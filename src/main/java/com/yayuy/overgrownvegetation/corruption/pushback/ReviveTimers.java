package com.yayuy.overgrownvegetation.corruption.pushback;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 燻る灰ブロックの「見直す時刻」(次元ごと。汚染の暦)。燻る灰は砂のように落ちるので、予定 tick は落ちるのに使われていて 1 日先の予定を置けない。
 * 置かれたときに時刻を覚え、乱択の tick で期限を過ぎていたら見直す({@link SmolderingAshBlock})。覚えが無い灰(前の版・コマンドで置いた)は期限切れ扱い。
 */
public final class ReviveTimers extends SavedData {

    public static final String ID = "overgrown_vegetation_ash_timers";
    public static final SavedData.Factory<ReviveTimers> FACTORY = new SavedData.Factory<>(ReviveTimers::new, (t, r) -> load(t), null);

    private final Long2LongOpenHashMap due = new Long2LongOpenHashMap();

    static ReviveTimers get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static ReviveTimers load(CompoundTag tag) {
        ReviveTimers d = new ReviveTimers();
        long[] k = tag.getLongArray("pos"), t = tag.getLongArray("due");
        for (int i = 0; i < Math.min(k.length, t.length); i++) d.due.put(k[i], t[i]);
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        long[] k = new long[due.size()], t = new long[due.size()];
        int i = 0;
        for (Long2LongMap.Entry e : due.long2LongEntrySet()) {
            k[i] = e.getLongKey();
            t[i++] = e.getLongValue();
        }
        tag.putLongArray("pos", k);
        tag.putLongArray("due", t);
        return tag;
    }

    /** 置かれた: {@code fire.burntDays} 後に見直す。 */
    static void schedule(ServerLevel level, BlockPos pos) {
        ReviveTimers d = get(level);
        d.due.put(pos.asLong(), PhaseData.clockNow(level) + (long) (DesignData.pushBack().fire.burntDays * 24000L));
        d.setDirty();
    }

    /** 期限を過ぎたか(過ぎていれば覚えを消す)。 */
    static boolean due(ServerLevel level, BlockPos pos) {
        ReviveTimers d = get(level);
        long key = pos.asLong();
        if (d.due.containsKey(key) && d.due.get(key) > PhaseData.clockNow(level)) return false;
        d.due.remove(key);
        d.setDirty();
        return true;
    }

    /** 灰が無くなった(落ちた・掘られた・戻った)。 */
    static void forget(ServerLevel level, BlockPos pos) {
        ReviveTimers d = get(level);
        if (d.due.containsKey(pos.asLong())) {
            d.due.remove(pos.asLong());
            d.setDirty();
        }
    }

    /** テスト用: そのマスの期限を今にする。 */
    public static void expireForTests(ServerLevel level, BlockPos pos) {
        ReviveTimers d = get(level);
        d.due.put(pos.asLong(), Long.MIN_VALUE / 4);
    }

    /** 覚えている数(確かめ用)。 */
    public static int size(ServerLevel level) {
        return get(level).due.size();
    }
}
