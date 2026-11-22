package com.yayuy.overgrownvegetation.port.component;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 【1.20.1 だけの物・互換の層】1.20.5 の {@code net.minecraft.world.item.component.ItemContainerContents}(アイテムの中の小さな入れ物)。
 * 本流が使う物だけ。値は変えられない(作り直す)。NBT はスロットの順の ItemStack の並び(空は空のまま)。
 */
public final class ItemContainerContents {

    public static final ItemContainerContents EMPTY = new ItemContainerContents(List.of());

    public static final Codec<ItemContainerContents> CODEC =
            ItemStack.CODEC.listOf().xmap(ItemContainerContents::new, c -> c.items);

    public static final StreamCodec<FriendlyByteBuf, ItemContainerContents> STREAM_CODEC = StreamCodec.of(
            (buf, c) -> {
                buf.writeVarInt(c.items.size());
                for (ItemStack s : c.items) {
                    buf.writeItem(s);
                }
            },
            buf -> {
                int n = buf.readVarInt();
                List<ItemStack> l = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    l.add(buf.readItem());
                }
                return new ItemContainerContents(l);
            });

    private final List<ItemStack> items;

    private ItemContainerContents(List<ItemStack> items) {
        // 末尾の空は落とす(1.20.5 と同じく、同じ中身なら同じ値になるように)
        int last = items.size();
        while (last > 0 && items.get(last - 1).isEmpty()) {
            last--;
        }
        List<ItemStack> copy = new ArrayList<>(last);
        for (int i = 0; i < last; i++) {
            copy.add(items.get(i).copy());
        }
        this.items = List.copyOf(copy);
    }

    public static ItemContainerContents fromItems(List<ItemStack> items) {
        return items.isEmpty() ? EMPTY : new ItemContainerContents(items);
    }

    public void copyInto(NonNullList<ItemStack> target) {
        for (int i = 0; i < target.size(); i++) {
            target.set(i, i < items.size() ? items.get(i).copy() : ItemStack.EMPTY);
        }
    }

    public int getSlots() {
        return items.size();
    }

    public ItemStack getStackInSlot(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot).copy() : ItemStack.EMPTY;
    }

    public Stream<ItemStack> stream() {
        return items.stream().map(ItemStack::copy);
    }

    public Stream<ItemStack> nonEmptyStream() {
        return items.stream().filter(s -> !s.isEmpty()).map(ItemStack::copy);
    }

    public Iterable<ItemStack> nonEmptyItems() {
        return () -> items.stream().filter(s -> !s.isEmpty()).iterator();
    }

    public Iterable<ItemStack> nonEmptyItemsCopy() {
        return () -> nonEmptyStream().iterator();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ItemContainerContents other) || other.items.size() != items.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (!ItemStack.matches(items.get(i), other.items.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (ItemStack s : items) {
            h = 31 * h + (s.isEmpty() ? 0 : s.getItem().hashCode() * 31 + s.getCount());
        }
        return h;
    }
}
