package com.yayuy.overgrownvegetation.port.capabilities;

import net.minecraftforge.common.capabilities.Capability;

/** 【1.20.1 だけの物・互換の層】NeoForge の {@code BlockCapability<T, C>}。中身は Forge の {@link Capability}(C は向き {@code Direction})。 */
public record BlockCapability<T, C>(Capability<T> forge) {
}
