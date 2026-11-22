package com.yayuy.overgrownvegetation.port.component;

import com.yayuy.overgrownvegetation.port.PortConst;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 【1.20.1 だけの物・互換の層】アイテムのデータコンポーネント({@code stack.get(TYPE)} など)を、static で行う所。
 * 本流の {@code stack.get(ModDataComponents.X)} は {@code OvItemData.get(stack, ModDataComponents.X)} に書き換える
 * (port-tools/port-rewrite.pl。{@code set}・{@code has}・{@code remove}・{@code getOrDefault}・{@code update} も同じ)。
 * NBT の形は {@link DataComponentType} の説明のとおり。
 *
 * <p>本流と同じ: {@code set(stack, T, null)} は消す。値が無くなったらタグも空なら消す(同じアイテムが重なるように)。
 */
public final class OvItemData {

    private OvItemData() {
    }

    @Nullable
    public static <T> T get(ItemStack stack, Supplier<DataComponentType<T>> type) {
        return get(stack, type.get());
    }

    @Nullable
    public static <T> T get(ItemStack stack, DataComponentType<T> type) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return null;
        }
        Tag t = tag.get(type.key());
        if (t == null) {
            return null;
        }
        return type.codec.parse(NbtOps.INSTANCE, t)
                .resultOrPartial(err -> PortConst.LOGGER.warn("item data {} を読めない: {}", type.id(), err))
                .orElse(null);
    }

    public static <T> T getOrDefault(ItemStack stack, Supplier<DataComponentType<T>> type, T fallback) {
        T v = get(stack, type.get());
        return v != null ? v : fallback;
    }

    public static <T> T getOrDefault(ItemStack stack, DataComponentType<T> type, T fallback) {
        T v = get(stack, type);
        return v != null ? v : fallback;
    }

    public static boolean has(ItemStack stack, Supplier<? extends DataComponentType<?>> type) {
        return has(stack, type.get());
    }

    public static boolean has(ItemStack stack, DataComponentType<?> type) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(type.key());
    }

    /** 前の値を返す。value が null なら消す。 */
    @Nullable
    public static <T> T set(ItemStack stack, Supplier<DataComponentType<T>> type, @Nullable T value) {
        return set(stack, type.get(), value);
    }

    @Nullable
    public static <T> T set(ItemStack stack, DataComponentType<T> type, @Nullable T value) {
        T prev = get(stack, type);
        if (value == null) {
            removeKey(stack, type);
            return prev;
        }
        type.codec.encodeStart(NbtOps.INSTANCE, value)
                .resultOrPartial(err -> PortConst.LOGGER.error("item data {} を書けない: {}", type.id(), err))
                .ifPresent(t -> stack.getOrCreateTag().put(type.key(), t));
        return prev;
    }

    @Nullable
    public static <T> T remove(ItemStack stack, Supplier<DataComponentType<T>> type) {
        return remove(stack, type.get());
    }

    @Nullable
    public static <T> T remove(ItemStack stack, DataComponentType<T> type) {
        T prev = get(stack, type);
        removeKey(stack, type);
        return prev;
    }

    public static <T> T update(ItemStack stack, Supplier<DataComponentType<T>> type, T fallback, UnaryOperator<T> op) {
        DataComponentType<T> t = type.get();
        T next = op.apply(getOrDefault(stack, t, fallback));
        set(stack, t, next);
        return next;
    }

    private static void removeKey(ItemStack stack, DataComponentType<?> type) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        tag.remove(type.key());
        if (tag.isEmpty()) {
            stack.setTag(null);
        }
    }
}
