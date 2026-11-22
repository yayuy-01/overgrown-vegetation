package com.yayuy.overgrownvegetation.port.component;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 【1.20.1 だけの物・互換の層】バニラ 1.20.5 の {@code net.minecraft.core.component.DataComponentType} と同じ名前・同じ作り方の物。
 * 1.20.1 にはデータコンポーネントが無いので、値はアイテムの NBT(タグの一番上、鍵 = 型の id)に Codec で書く({@link OvItemData})。
 *
 * <p><b>NBT の形(データの組 D との約束)</b>: {@code tag: {"overgrown_vegetation:<名前>": <Codec の値>}}。
 * 例: {@code {"overgrown_vegetation:plant_armor_stage": 2}}・{@code {"overgrown_vegetation:lantern_lit": 1b}}。
 * 1.21 の json の {@code "components": {"overgrown_vegetation:x": v}} は、1.20.1 の {@code set_nbt} / レシピの {@code nbt} で同じ鍵に書く。
 *
 * <p>{@code networkSynchronized} は受け取るだけ(1.20.1 は NBT ごと送られる)。persistent でない型は作れない(NBT に置くしか無いため)。
 */
public final class DataComponentType<T> {

    @Nullable
    final Codec<T> codec;
    @Nullable
    private volatile ResourceLocation id;
    @Nullable
    private volatile String key;

    private DataComponentType(@Nullable Codec<T> codec) {
        this.codec = codec;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** {@code DeferredRegister.DataComponents} が登録のときに呼ぶ。 */
    public void bind(ResourceLocation id) {
        if (this.id != null && !this.id.equals(id)) {
            throw new IllegalStateException("component bound twice: " + this.id + " / " + id);
        }
        this.id = id;
        this.key = id.toString();
    }

    @Nullable
    public ResourceLocation id() {
        return id;
    }

    /** NBT の鍵。 */
    String key() {
        String k = key;
        if (k == null) {
            throw new IllegalStateException("data component used before registration");
        }
        return k;
    }

    @Nullable
    public Codec<T> codec() {
        return codec;
    }

    @Override
    public String toString() {
        return "DataComponentType{" + id + "}";
    }

    public static final class Builder<T> {
        @Nullable
        private Codec<T> codec;

        public Builder<T> persistent(Codec<T> codec) {
            this.codec = codec;
            return this;
        }

        /** 1.20.1 では使わない(NBT ごと送られる)。型は何でも受ける。 */
        public Builder<T> networkSynchronized(Object ignoredStreamCodec) {
            return this;
        }

        public Builder<T> cacheEncoding() {
            return this;
        }

        public DataComponentType<T> build() {
            if (codec == null) {
                throw new IllegalStateException("1.20.1 port: data components must be persistent (stored in item NBT)");
            }
            return new DataComponentType<>(codec);
        }
    }
}
