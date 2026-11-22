package com.yayuy.overgrownvegetation.port.capabilities;

import net.minecraftforge.common.capabilities.Capability;

/** 【1.20.1 だけの物・互換の層】NeoForge の {@code ItemCapability<T, C>}。中身は Forge の {@link Capability}(C は使わない)。 */
public record ItemCapability<T, C>(Capability<T> forge) {
}
