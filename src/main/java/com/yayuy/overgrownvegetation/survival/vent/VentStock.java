package com.yayuy.overgrownvegetation.survival.vent;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 換気機の中のフィルター(11-23 夕方 ユーザー決定): 差してある 1 枚・予備 {@code push_back.json vent.spareFilters}(4)枚・
 * 詰まった物の置き場 {@code vent.cloggedSlots}【仮 4】。
 *
 * <p>差してある 1 枚が詰まったら置き場へ移し(置き場がいっぱいなら差したまま = 機械は止まる)、予備の先頭を差す({@link #rotate})。
 * 読み込まれている機械({@link VentBlockEntity})と留守の覚え({@link com.yayuy.overgrownvegetation.corruption.pushback.AbsentWards})で同じ物を使う。
 */
public final class VentStock {

    public ItemStack filter = ItemStack.EMPTY;
    public final ItemStack[] spares;
    public final ItemStack[] clogged;

    public VentStock() {
        this(spareCount(), cloggedCount());
    }

    private VentStock(int spares, int clogged) {
        this.spares = new ItemStack[spares];
        this.clogged = new ItemStack[clogged];
        java.util.Arrays.fill(this.spares, ItemStack.EMPTY);
        java.util.Arrays.fill(this.clogged, ItemStack.EMPTY);
    }

    public static int spareCount() {
        return Math.max(0, Math.min(27, DesignData.pushBack().vent.spareFilters));
    }

    public static int cloggedCount() {
        return Math.max(0, Math.min(27, DesignData.pushBack().vent.cloggedSlots));
    }

    public VentStock copy() {
        VentStock c = new VentStock(spares.length, clogged.length);
        c.filter = filter.copy();
        for (int i = 0; i < spares.length; i++) c.spares[i] = spares[i].copy();
        for (int i = 0; i < clogged.length; i++) c.clogged[i] = clogged[i].copy();
        return c;
    }

    /** 詰まっていないフィルターが差してあるか、予備にあるか(留守の間に動き続けられるか)。 */
    public boolean usable() {
        if (!filter.isEmpty() && !AirFilters.clogged(filter)) return true;
        for (ItemStack s : spares) if (!s.isEmpty() && !AirFilters.clogged(s)) return true;
        return false;
    }

    public int spareFilled() {
        int n = 0;
        for (ItemStack s : spares) if (!s.isEmpty()) n++;
        return n;
    }

    public int cloggedFilled() {
        int n = 0;
        for (ItemStack s : clogged) if (!s.isEmpty()) n++;
        return n;
    }

    /**
     * 詰まった 1 枚を置き場へ、予備を差す(何度でも呼んでよい)。変わったら true。
     * 置き場がいっぱいのときは詰まった物を差したままにする(下のホッパーで抜かれるまで止まる)。
     */
    public boolean rotate() {
        boolean changed = false;
        for (int guard = 0; guard <= spares.length; guard++) {
            if (!filter.isEmpty() && AirFilters.clogged(filter)) {
                int slot = firstEmpty(clogged);
                if (slot < 0) return changed;
                clogged[slot] = filter;
                filter = ItemStack.EMPTY;
                changed = true;
            }
            if (!filter.isEmpty()) return changed;
            int next = -1;
            for (int i = 0; i < spares.length; i++) if (!spares[i].isEmpty()) { next = i; break; }
            if (next < 0) return changed;
            filter = spares[next];
            // 予備を前へ詰める(差す順 = 入れた順)
            for (int i = next; i < spares.length - 1; i++) spares[i] = spares[i + 1];
            spares[spares.length - 1] = ItemStack.EMPTY;
            changed = true;
        }
        return changed;
    }

    /** 予備に 1 枚入れる(詰まっていないフィルターだけ)。入ったら true。 */
    public boolean addSpare(ItemStack one) {
        if (!AirFilters.isFilter(one) || AirFilters.clogged(one)) return false;
        int slot = firstEmpty(spares);
        if (slot < 0) return false;
        spares[slot] = one.copyWithCount(1);
        return true;
    }

    /** 中の物をすべて(壊したとき)。 */
    public List<ItemStack> all() {
        List<ItemStack> out = new ArrayList<>();
        if (!filter.isEmpty()) out.add(filter);
        for (ItemStack s : spares) if (!s.isEmpty()) out.add(s);
        for (ItemStack s : clogged) if (!s.isEmpty()) out.add(s);
        return out;
    }

    public void clear() {
        filter = ItemStack.EMPTY;
        java.util.Arrays.fill(spares, ItemStack.EMPTY);
        java.util.Arrays.fill(clogged, ItemStack.EMPTY);
    }

    private static int firstEmpty(ItemStack[] slots) {
        for (int i = 0; i < slots.length; i++) if (slots[i].isEmpty()) return i;
        return -1;
    }

    // ---------------------------------------------------------------- 保存

    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!filter.isEmpty()) tag.put("filter", filter.save(registries));
        tag.put("spares", list(spares, registries));
        tag.put("clogged", list(clogged, registries));
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        filter = tag.contains("filter") ? ItemStack.parseOptional(registries, tag.getCompound("filter")) : ItemStack.EMPTY;
        // 予備の数を design で減らしたときは、入りきらない予備を置き場へ寄せる
        List<ItemStack> sp = read(tag.getList("spares", Tag.TAG_COMPOUND), registries);
        List<ItemStack> cl = read(tag.getList("clogged", Tag.TAG_COMPOUND), registries);
        java.util.Arrays.fill(spares, ItemStack.EMPTY);
        java.util.Arrays.fill(clogged, ItemStack.EMPTY);
        int i = 0;
        for (ItemStack s : sp) if (i < spares.length) spares[i++] = s; else cl.add(s);
        int j = 0;
        for (ItemStack s : cl) if (j < clogged.length) clogged[j++] = s;
        if (cl.size() > clogged.length) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.warn("換気機: 置き場の数より多いフィルター {} 枚を読み込んだ(余りは消える)", cl.size() - clogged.length);
        }
    }

    private static ListTag list(ItemStack[] slots, HolderLookup.Provider registries) {
        ListTag l = new ListTag();
        for (ItemStack s : slots) if (!s.isEmpty()) l.add(s.save(registries));
        return l;
    }

    private static List<ItemStack> read(ListTag l, HolderLookup.Provider registries) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            ItemStack s = ItemStack.parseOptional(registries, l.getCompound(i));
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
