package com.yayuy.overgrownvegetation.port.saved;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 【1.20.1 だけの物・互換の層】1.20.5 の {@code SavedData}({@code save(tag, registries)}・{@code SavedData.Factory})の形を 1.20.1 で使うための土台。
 *
 * <p>書き換え(port-tools/port-rewrite.pl が機械的にやる):
 * <ul>
 *   <li>{@code extends SavedData} → {@code extends OvSavedData}(本流の {@code save(CompoundTag, HolderLookup.Provider)} がそのまま上書きになる)</li>
 *   <li>{@code SavedData.Factory} → {@code OvSavedData.Factory}(引数は同じ)</li>
 *   <li>{@code storage.computeIfAbsent(FACTORY, ID)} → {@code storage.computeIfAbsent(FACTORY.load(), FACTORY.constructor(), ID)}</li>
 * </ul>
 * registries は動いているサーバーの {@link RegistryAccess}(1.20.1 でも {@code HolderLookup.Provider} を継ぐ)。
 */
public abstract class OvSavedData extends SavedData {

    @Override
    public final CompoundTag save(CompoundTag tag) {
        return save(tag, registries());
    }

    public abstract CompoundTag save(CompoundTag tag, HolderLookup.Provider registries);

    public static HolderLookup.Provider registries() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("SavedData used without a running server");
        }
        return server.registryAccess();
    }

    /** 1.20.5 の {@code SavedData.Factory} と同じ並び。DataFixTypes は 1.20.1 では使わない。 */
    public record Factory<T extends SavedData>(Supplier<T> constructor, BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer,
                                               @Nullable DataFixTypes type) {
        public Function<CompoundTag, T> load() {
            return tag -> deserializer.apply(tag, registries());
        }
    }

    public static <T extends SavedData> T computeIfAbsent(DimensionDataStorage storage, Factory<T> factory, String name) {
        return storage.computeIfAbsent(factory.load(), factory.constructor(), name);
    }
}
