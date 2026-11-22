package com.yayuy.overgrownvegetation.port.capabilities;

import org.jetbrains.annotations.Nullable;

/** 【1.20.1 だけの物・互換の層】NeoForge の {@code ICapabilityProvider<O, C, T>}(ブロックエンティティ・アイテムから口を作る)。 */
@FunctionalInterface
public interface ICapabilityProvider<O, C, T> {
    @Nullable
    T getCapability(O object, C context);
}
