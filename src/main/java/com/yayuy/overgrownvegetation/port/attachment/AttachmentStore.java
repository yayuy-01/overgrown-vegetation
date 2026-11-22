package com.yayuy.overgrownvegetation.port.attachment;

import com.mojang.serialization.DataResult;
import com.yayuy.overgrownvegetation.port.PortConst;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 【1.20.1 だけの物・互換の層】attachment の入れ物。Forge の Capability として Entity・LevelChunk・Level に 1 つずつ付く
 * ({@link AttachmentEvents})。中身は「型 → 値」の表。NeoForge の {@code AttachmentHolder} と同じく、スレッドをまたいだ同時の書き込みは考えない。
 *
 * <p>NBT の形: {@code {"<型の id>": <Codec で書いた値>, ...}}。Entity では Forge が {@code ForgeCaps."overgrown_vegetation:attachments"} の下に置く。
 * 読み込んだときに知らない id は、そのまま持っていて次の保存でも書き戻す(版をまたいでも消さない)。
 */
public final class AttachmentStore implements ICapabilitySerializable<CompoundTag> {

    public static final Capability<AttachmentStore> CAPABILITY = CapabilityManager.get(new CapabilityToken<>() {
    });
    public static final ResourceLocation KEY = PortConst.id("attachments");

    private final Map<AttachmentType<?>, Object> values = new IdentityHashMap<>();
    @Nullable
    private CompoundTag unknown;
    private final LazyOptional<AttachmentStore> self = LazyOptional.of(() -> this);

    @SuppressWarnings("unchecked")
    <T> T getOrCreate(AttachmentType<T> type) {
        Object v = values.get(type);
        if (v == null) {
            v = type.defaultValue.get();
            values.put(type, v);
        }
        return (T) v;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    <T> T getExisting(AttachmentType<T> type) {
        return (T) values.get(type);
    }

    boolean has(AttachmentType<?> type) {
        return values.containsKey(type);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    <T> T set(AttachmentType<T> type, T value) {
        return (T) values.put(type, value);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    <T> T remove(AttachmentType<T> type) {
        return (T) values.remove(type);
    }

    boolean isEmpty() {
        return values.isEmpty();
    }

    /** プレイヤーの作り直し(死・エンドからの帰還)で、保存する型を写す。onlyCopyOnDeath のときは copyOnDeath の型だけ。 */
    void copyFrom(AttachmentStore from, boolean onlyCopyOnDeath) {
        for (Map.Entry<AttachmentType<?>, Object> e : from.values.entrySet()) {
            AttachmentType<?> type = e.getKey();
            if (!type.isSerializable() || (onlyCopyOnDeath && !type.copyOnDeath)) {
                continue;
            }
            Object copy = roundTrip(type, e.getValue());
            if (copy != null) {
                values.put(type, copy);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static <T> T roundTrip(AttachmentType<T> type, Object value) {
        DataResult<Tag> enc = type.codec.encodeStart(NbtOps.INSTANCE, (T) value);
        return enc.result().flatMap(t -> type.codec.parse(NbtOps.INSTANCE, t).result()).orElse(null);
    }

    @Override
    public @NotNull <C> LazyOptional<C> getCapability(@NotNull Capability<C> cap, @Nullable Direction side) {
        return CAPABILITY.orEmpty(cap, self);
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = unknown != null ? unknown.copy() : new CompoundTag();
        for (Map.Entry<AttachmentType<?>, Object> e : values.entrySet()) {
            write(tag, e.getKey(), e.getValue());
        }
        return tag;
    }

    @SuppressWarnings("unchecked")
    private static <T> void write(CompoundTag tag, AttachmentType<T> type, Object value) {
        if (!type.isSerializable() || !((Predicate<Object>) (Predicate<?>) type.shouldSerialize).test(value)) {
            return;
        }
        type.codec.encodeStart(NbtOps.INSTANCE, (T) value)
                .resultOrPartial(err -> PortConst.LOGGER.error("attachment {} を保存できない: {}", type.id(), err))
                .ifPresent(t -> tag.put(type.id().toString(), t));
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        values.clear();
        unknown = null;
        for (String key : tag.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            AttachmentType<?> type = id == null ? null : AttachmentType.byId(id);
            if (type == null || type.codec == null) {
                if (unknown == null) {
                    unknown = new CompoundTag();
                }
                unknown.put(key, tag.get(key).copy());
                continue;
            }
            read(type, tag.get(key));
        }
    }

    private <T> void read(AttachmentType<T> type, Tag t) {
        type.codec.parse(NbtOps.INSTANCE, t)
                .resultOrPartial(err -> PortConst.LOGGER.error("attachment {} を読めない: {}", type.id(), err))
                .ifPresent(v -> values.put(type, v));
    }
}
