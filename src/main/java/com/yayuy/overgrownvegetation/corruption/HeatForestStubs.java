package com.yayuy.overgrownvegetation.corruption;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * <b>傘を取られた菌の幹の印</b>(ネザー。2026-11-10 追記 13、ユーザー決定「近くで木が倒れたときだけ、傘の無い幹の塊を木として倒す」)。
 *
 * <p>密な菌の森では、背の低い木の傘が、先に倒れた隣の木に全部集められることがある。その幹は「葉の無い原木の塊 = 木ではない」に
 * 当たって立ったまま残る。そこで、木が倒れたときに、そのまわり(倒れた木の箱を 4 マス広げた中)で傘を 1 つも持たなくなった
 * 菌の幹の根元に印を付ける({@link HeatForestFungus#markCaplessStubs})。あとで汚染がその幹に届いたとき、印のある塊だけを、
 * 傘が無くても木として倒す({@link HeatForestFungus#isCaplessStub})。プレイヤーが菌の幹で立てた柱は、そばで木が倒れない限り印が付かない。
 *
 * <p>印は根元のマス。汚染は先の話になることがあるので、ワールドに保存する。倒したら消す。
 */
public final class HeatForestStubs extends SavedData {

    private static final String ID = "overgrown_vegetation_heat_fungus_stubs";
    /** 印の数の上限(超えたら古い順ではなく、足さないだけ。仮)。 */
    private static final int MAX_MARKS = 65536;

    public static final SavedData.Factory<HeatForestStubs> FACTORY =
            new SavedData.Factory<>(HeatForestStubs::new, (tag, registries) -> load(tag), null);

    private final LongOpenHashSet marks = new LongOpenHashSet();

    private static HeatForestStubs load(CompoundTag tag) {
        HeatForestStubs data = new HeatForestStubs();
        for (long l : tag.getLongArray("marks")) data.marks.add(l);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("marks", marks.toLongArray());
        return tag;
    }

    public static HeatForestStubs get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    void mark(BlockPos base) {
        if (marks.size() < MAX_MARKS && marks.add(base.asLong())) setDirty();
    }

    /** 印があれば消して true。 */
    boolean take(BlockPos base) {
        if (marks.remove(base.asLong())) {
            setDirty();
            return true;
        }
        return false;
    }

    boolean has(BlockPos base) {
        return marks.contains(base.asLong());
    }
}
