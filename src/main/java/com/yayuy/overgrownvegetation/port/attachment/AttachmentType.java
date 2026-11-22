package com.yayuy.overgrownvegetation.port.attachment;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code net.neoforged.neoforge.attachment.AttachmentType} と同じ名前・同じ作り方の物。
 * 値は {@link OvAttach} で読み書きする(1.20.1 の Entity・LevelChunk・Level には {@code getData} が無いため)。
 * 中身は Forge の Capability 1 つ({@link AttachmentStore})の中の表。保存は {@link #serialize} の Codec を NBT にかける。
 *
 * <p>本流と同じ: 保存しない型は再読み込みで既定値に戻る。{@link Builder#copyOnDeath()} の型だけが死んだプレイヤーに引き継がれる
 * (エンドからの帰還など死ではない作り直しでは、保存する型を全部引き継ぐ)。
 */
public final class AttachmentType<T> {

    private static final Map<ResourceLocation, AttachmentType<?>> BY_ID = new ConcurrentHashMap<>();

    final Supplier<T> defaultValue;
    @Nullable
    final Codec<T> codec;
    final Predicate<? super T> shouldSerialize;
    final boolean copyOnDeath;
    @Nullable
    private volatile ResourceLocation id;

    private AttachmentType(Builder<T> b) {
        this.defaultValue = b.defaultValue;
        this.codec = b.codec;
        this.shouldSerialize = b.shouldSerialize;
        this.copyOnDeath = b.copyOnDeath;
    }

    public static <T> Builder<T> builder(Supplier<T> defaultValue) {
        return new Builder<>(defaultValue);
    }

    /** NeoForge の {@code AttachmentType.serializable(...)} の簡単な形(既定値 + Codec)。 */
    public static <T> Builder<T> serializable(Supplier<T> defaultValue, Codec<T> codec) {
        return new Builder<>(defaultValue).serialize(codec);
    }

    /** {@code DeferredRegister} が登録のときに呼ぶ(保存の鍵)。 */
    public void bind(ResourceLocation id) {
        if (this.id != null && !this.id.equals(id)) {
            throw new IllegalStateException("attachment type bound twice: " + this.id + " / " + id);
        }
        this.id = id;
        AttachmentType<?> prev = BY_ID.putIfAbsent(id, this);
        if (prev != null && prev != this) {
            throw new IllegalStateException("duplicate attachment id " + id);
        }
    }

    @Nullable
    public ResourceLocation id() {
        return id;
    }

    @Nullable
    static AttachmentType<?> byId(ResourceLocation id) {
        return BY_ID.get(id);
    }

    boolean isSerializable() {
        return codec != null && id != null;
    }

    @Override
    public String toString() {
        return "AttachmentType{" + id + "}";
    }

    public static final class Builder<T> {
        private final Supplier<T> defaultValue;
        @Nullable
        private Codec<T> codec;
        private Predicate<? super T> shouldSerialize = v -> true;
        private boolean copyOnDeath;

        private Builder(Supplier<T> defaultValue) {
            this.defaultValue = defaultValue;
        }

        public Builder<T> serialize(Codec<T> codec) {
            return serialize(codec, v -> true);
        }

        public Builder<T> serialize(Codec<T> codec, Predicate<? super T> shouldSerialize) {
            this.codec = codec;
            this.shouldSerialize = shouldSerialize;
            return this;
        }

        public Builder<T> copyOnDeath() {
            if (codec == null) {
                throw new IllegalStateException("copyOnDeath needs serialize() first (same rule as NeoForge)");
            }
            this.copyOnDeath = true;
            return this;
        }

        public AttachmentType<T> build() {
            return new AttachmentType<>(this);
        }
    }
}
