package com.yayuy.overgrownvegetation.port.registries;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code net.neoforged.neoforge.registries.DeferredHolder} と同じ名前・同じ使い方の物。
 * 中身は Forge の {@link RegistryObject}(登録簿に載る物)か、登録簿の無い物(attachment・データコンポーネント)の遅延の値。
 *
 * <p>NeoForge 版との違い: 1.20.1 の {@link Holder} は {@code Supplier<T>} を継ぐので、ここでは {@code Holder<R>} を実装できない
 * ({@code Supplier<T>} と {@code Supplier<R>} を両方は継げない)。{@code Holder} が要る所は {@link #getDelegate()} を使う。
 *
 * @param <R> 登録簿の型(Block など)
 * @param <T> 実際の型
 */
public class DeferredHolder<R, T extends R> implements Supplier<T> {

    protected final ResourceKey<R> key;
    @Nullable
    private final RegistryObject<T> forge;
    @Nullable
    private final Supplier<? extends T> local;
    @Nullable
    private T localValue;

    /** Forge の登録簿に載る物。 */
    protected DeferredHolder(ResourceKey<R> key, RegistryObject<T> forge) {
        this.key = key;
        this.forge = forge;
        this.local = null;
    }

    /** 登録簿の無い物(最初に {@link #get()} されたときに作る)。 */
    protected DeferredHolder(ResourceKey<R> key, Supplier<? extends T> local) {
        this.key = key;
        this.forge = null;
        this.local = local;
    }

    /** NeoForge の {@code DeferredHolder.create(registryKey, id)} と同じ。すでにある物を指す(バニラの物・ほかの MOD の物)。 */
    public static <R, T extends R> DeferredHolder<R, T> create(ResourceKey<? extends Registry<R>> registryKey, ResourceLocation id) {
        return new DeferredHolder<>(ResourceKey.create(registryKey, id), RegistryObject.create(id, registryKey, id.getNamespace()));
    }

    @Override
    public T get() {
        if (forge != null) {
            return forge.get();
        }
        T v = localValue;
        if (v == null) {
            synchronized (this) {
                v = localValue;
                if (v == null) {
                    v = Objects.requireNonNull(local.get(), () -> "null from " + key);
                    localValue = v;
                }
            }
        }
        return v;
    }

    /** NeoForge の {@code Holder.value()} と同じ({@link #get()})。 */
    public T value() {
        return get();
    }

    public ResourceLocation getId() {
        return key.location();
    }

    public ResourceKey<R> getKey() {
        return key;
    }

    /** NeoForge の {@code getRegisteredName()}。 */
    public String getRegisteredName() {
        return key.location().toString();
    }

    public boolean isBound() {
        return forge != null ? forge.isPresent() : true;
    }

    /** 1.20.1 の {@link Holder}(登録簿の中の物)。登録の前に呼ぶと落ちる。 */
    @SuppressWarnings("unchecked")
    public Holder<R> getDelegate() {
        if (forge == null) {
            return Holder.direct(get());
        }
        return (Holder<R>) (Holder<?>) forge.getHolder().orElseThrow(() -> new IllegalStateException("not bound: " + key));
    }

    public boolean is(ResourceLocation id) {
        return key.location().equals(id);
    }

    public boolean is(ResourceKey<R> other) {
        return key.equals(other);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof DeferredHolder<?, ?> h && h.key.equals(key));
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "DeferredHolder{" + key + "}";
    }
}
